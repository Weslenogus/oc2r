--[[ SPDX-License-Identifier: MIT

  Kernel and sandbox for the OpenComputers 1 compatible Lua runtime.

  Java loads this once per machine with the raw natives sitting in the global _JAVA. Everything
  needed is captured into locals here and Java clears _JAVA immediately afterwards, so the only
  route from sandboxed code back to Java is through the functions this file chooses to expose.

  Two pieces of machinery are worth understanding.

  1. The yield protocol. Java has to be able to regain control from arbitrarily deep inside a
     program: to deliver a signal, to run a component call that needs the server thread, or simply
     because the time slice ran out. Lua coroutines only yield one level, so a nested coroutine's
     yield would stop at whoever resumed it.

     The fix, taken from OpenComputers 1, is to split yields in two. A system yield passes a
     non-nil first value and is re-yielded by every resume it passes through until it reaches Java;
     a user yield passes nil first and is handled by the innermost resume, exactly like a normal
     coroutine. Sandboxed code only ever sees the user form, because the coroutine table below
     rewrites it.

  2. The environment. LuaJ only runs debug hooks for chunks whose environment is a Globals object,
     and the debug hook is what enforces the time slice. So the sandbox is not a fresh table: it is
     the globals table itself, stripped of everything that could reach the host and extended with
     the OpenComputers libraries. A chunk loaded with its own environment, which is how an
     operating system isolates the programs it runs, gets that environment wrapped so it is a
     Globals too. Without this a program could sidestep the CPU limiter entirely by doing its work
     inside load(code, nil, nil, {}).
]]

local raw = _JAVA
local rawComponent = raw.component
local rawComputer = raw.computer
local rawUnicode = raw.unicode
local sethook = raw.sethook
local newEnvironment = raw.newenv

local coroutine_create = coroutine.create
local coroutine_resume = coroutine.resume
local coroutine_yield = coroutine.yield
local coroutine_status = coroutine.status
local coroutine_running = coroutine.running

local rawLoad = load
local raw_next = next
local raw_rawget = rawget
local raw_rawset = rawset
local raw_rawlen = rawlen
local raw_rawequal = rawequal
local raw_getmetatable = getmetatable
local raw_setmetatable = setmetatable
local raw_type = type
local raw_error = error

local rawOsDate = os.date
local rawOsTime = os.time
local rawTraceback = debug.traceback
local rawGetinfo = debug.getinfo

local table_pack, table_unpack, table_concat = table.pack, table.unpack, table.concat
local string_format, string_gmatch = string.format, string.gmatch

local sandbox = _G

-------------------------------------------------------------------------------
-- Strip the globals down to what an OpenComputers program is allowed to see.

sandbox.dofile = nil
sandbox.loadfile = nil
sandbox.print = nil
sandbox.io = nil
sandbox.luajava = nil
-- package and require would hand back the unfiltered debug table through package.loaded, and
-- would let a program load code off the host's class path.
sandbox.package = nil
sandbox.require = nil
sandbox.module = nil
sandbox.string.dump = nil

sandbox.os = {
  date = rawOsDate,
  time = rawOsTime,
  difftime = function(t2, t1) return t2 - t1 end,
  -- Programs use os.clock as a monotonic timer, and machine uptime is the honest answer here.
  clock = function() return rawComputer.uptime() end,
}

sandbox.debug = {
  traceback = rawTraceback,
  getinfo = rawGetinfo,
}

-- Letting a program call for a full collection whenever it likes is a way to make the server
-- stutter from inside a sandbox. Reporting zero is what a machine with no visibility into the
-- host heap should say anyway.
sandbox.collectgarbage = function() return 0 end

sandbox._VERSION = "Lua 5.2"

-------------------------------------------------------------------------------
-- Environments.

-- Maps a wrapper back to the table it stands in for. Weak keyed, so wrapping an environment does
-- not keep it alive.
local environments = raw_setmetatable({}, {__mode = "k"})

--- Wraps a plain table so it can serve as a chunk environment.
-- The wrapper is a Globals object, which is what makes LuaJ run debug hooks in the chunk, with a
-- metatable pointing every read and write straight back at the original table. The wrapper itself
-- stays empty, so __index and __newindex always fire and the real table remains the only storage.
local function asEnvironment(env)
  if env == nil then
    return sandbox
  end
  if raw_type(env) ~= "table" then
    raw_error("bad argument #4 (table expected, got " .. raw_type(env) .. ")", 3)
  end
  if env == sandbox or environments[env] then
    return env
  end

  local existing = environments[env]
  if existing then
    return existing
  end

  local wrapper = newEnvironment()
  raw_setmetatable(wrapper, {
    __index = env,
    __newindex = env,
    __len = function() return raw_rawlen(env) end,
  })
  environments[wrapper] = env
  environments[env] = wrapper
  return wrapper
end

-- Raw accessors have to see through a wrapper, or a program inspecting its own _ENV with rawget,
-- next or pairs would find the empty wrapper instead of its variables.
local function unwrap(value)
  if raw_type(value) == "table" then
    return environments[value] or value
  end
  return value
end

function sandbox.next(t, k)
  return raw_next(unwrap(t), k)
end

function sandbox.pairs(t)
  local target = unwrap(t)
  local metatable = raw_getmetatable(target)
  local handler = metatable and raw_rawget(metatable, "__pairs")
  if handler then
    return handler(target)
  end
  return raw_next, target, nil
end

function sandbox.rawget(t, k)
  return raw_rawget(unwrap(t), k)
end

function sandbox.rawset(t, k, v)
  raw_rawset(unwrap(t), k, v)
  return t
end

function sandbox.rawlen(t)
  return raw_rawlen(unwrap(t))
end

function sandbox.rawequal(a, b)
  return raw_rawequal(unwrap(a), unwrap(b))
end

--- Loads a chunk.
-- The mode is pinned to text: accepting precompiled chunks would let carefully crafted bytecode
-- walk straight out of the sandbox, and no operating system needs it.
function sandbox.load(chunk, name, mode, env)
  return rawLoad(chunk, name, "t", asEnvironment(env))
end

-------------------------------------------------------------------------------
-- Argument checking, used pervasively by OpenOS and MineOS.

local function checkArg(n, have, ...)
  have = raw_type(have)
  local function check(want, ...)
    if not want then
      return false
    else
      return have == want or check(...)
    end
  end
  if not check(...) then
    raw_error(string_format("bad argument #%d (%s expected, got %s)",
      n, table_concat({...}, " or "), have), 3)
  end
end

sandbox.checkArg = checkArg

-------------------------------------------------------------------------------
-- Coroutines, with system yields bubbling out to Java.

local function bubble(co, ...)
  local args = table_pack(...)
  while true do
    local result = table_pack(coroutine_resume(co, table_unpack(args, 1, args.n)))
    if result[1] then
      if coroutine_status(co) == "dead" then
        -- Returned normally. Its results start right after the success flag.
        return true, table_unpack(result, 2, result.n)
      elseif result[2] ~= nil then
        -- System yield. Pass it further out and hand whatever comes back down again.
        args = table_pack(coroutine_yield(result[2]))
      else
        -- User yield. The leading nil is the marker, so the real values start at 3.
        return true, table_unpack(result, 3, result.n)
      end
    else
      return false, result[2]
    end
  end
end

local sandboxCoroutine = {
  create = function(f)
    checkArg(1, f, "function")
    local co = coroutine_create(f)
    -- Hooks are per coroutine, so every one of them needs its own or a program could dodge
    -- preemption simply by doing its work inside a coroutine.
    sethook(co)
    return co
  end,

  resume = function(co, ...)
    checkArg(1, co, "thread")
    return bubble(co, ...)
  end,

  yield = function(...)
    -- The leading nil is what marks this as a user yield rather than a system one.
    return coroutine_yield(nil, ...)
  end,

  status = coroutine_status,

  running = coroutine_running,

  isyieldable = function()
    return coroutine_running() ~= nil
  end,
}

function sandboxCoroutine.wrap(f)
  local co = sandboxCoroutine.create(f)
  return function(...)
    local result = table_pack(sandboxCoroutine.resume(co, ...))
    if result[1] then
      return table_unpack(result, 2, result.n)
    else
      raw_error(result[2], 0)
    end
  end
end

sandbox.coroutine = sandboxCoroutine

-------------------------------------------------------------------------------
-- computer

local computer = {}
for name, value in raw_next, rawComputer do
  computer[name] = value
end

--- Blocks until a signal arrives or the timeout expires.
-- This is the only place ordinary programs give control back to the host, so it is also where the
-- machine gets rescheduled. The loop guards against being resumed without a signal, which can
-- happen after a save and reload.
function computer.pullSignal(seconds)
  local deadline = computer.uptime() +
    (raw_type(seconds) == "number" and seconds or math.huge)
  repeat
    local signal = table_pack(coroutine_yield(deadline - computer.uptime()))
    if signal.n > 0 then
      return table_unpack(signal, 1, signal.n)
    end
  until computer.uptime() >= deadline
end

function computer.shutdown(reboot)
  coroutine_yield(reboot ~= nil and reboot ~= false)
  -- Java stops resuming us, so this is only reached if something went very wrong.
  raw_error("computer.shutdown did not shut down", 0)
end

sandbox.computer = computer

-------------------------------------------------------------------------------
-- component

local component = {
  doc = rawComponent.doc,
  invoke = rawComponent.invoke,
  methods = rawComponent.methods,
  type = rawComponent.type,
  slot = rawComponent.slot,
  fields = rawComponent.fields,
}

--- Lists attached components, both as a table and as an iterator.
-- `for address, type in component.list("filesystem") do` is the idiom everything uses, and it
-- needs the returned table to be callable.
function component.list(filter, exact)
  checkArg(1, filter, "string", "nil")
  local list = rawComponent.list(filter, exact)
  local key = nil
  return raw_setmetatable(list, {
    __call = function()
      key = raw_next(list, key)
      if key then
        return key, list[key]
      end
    end
  })
end

function component.proxy(address)
  checkArg(1, address, "string")
  local componentType, reason = component.type(address)
  if not componentType then
    return nil, reason
  end
  local slot = component.slot(address)
  local methods, methodsReason = component.methods(address)
  if not methods then
    return nil, methodsReason
  end

  local proxy = {address = address, type = componentType, slot = slot}
  for method in raw_next, methods do
    -- A callable table rather than a plain function, so that tostring(proxy.method) yields the
    -- documentation string. OpenOS's shell relies on this for its help output.
    proxy[method] = raw_setmetatable({}, {
      __call = function(_, ...)
        return component.invoke(address, method, ...)
      end,
      __tostring = function()
        return component.doc(address, method) or method
      end
    })
  end
  return proxy
end

sandbox.component = component
sandbox.unicode = rawUnicode

-------------------------------------------------------------------------------
-- Kernel entry point. Java runs this as the machine's main coroutine.

return function()
  local address = component.list("eeprom")()
  if not address then
    raw_error("no bios found; install a configured EEPROM", 0)
  end

  local code = component.invoke(address, "get")
  if not code or #code == 0 then
    raw_error("no bios found; install a configured EEPROM", 0)
  end

  local bios, reason = sandbox.load(code, "=bios")
  if not bios then
    raw_error("failed loading bios: " .. tostring(reason), 0)
  end

  -- Run in this coroutine rather than a nested one: a raw yield from anywhere inside reaches
  -- Java directly, and coroutines the BIOS creates go through the wrapper above, which bubbles.
  return bios()
end
