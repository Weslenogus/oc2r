--[[ SPDX-License-Identifier: MIT

  Preamble for the real Lua backend, run with the raw natives in _JAVA just before machine.lua.

  It fills in the two entries machine.lua needs but that a real Lua cannot get from Java.

  sethook. The binding exposes no lua_sethook, so the deadline hook has to be a Lua function
  installed through debug.sethook. That is also how OpenComputers 1 does it, and it is where the
  trick below comes from: once the deadline has passed the hook re-arms itself to fire on every
  single instruction and raises. A program can catch that error, but the next instruction it
  executes raises again, and the one after that, so it cannot get anywhere. Yielding instead, which
  is what the pure Java backend does, is not an option here: a Lua hook runs inside a C call, and
  Lua will not yield across one.

  newenv. Real Lua runs its hook for whatever code the coroutine is executing, whatever environment
  that code was loaded with, so a chunk with its own environment needs no wrapping and this answers
  nothing. The pure Java backend hands back a stand-in instead.
]]

local raw = _JAVA

local debug_sethook = debug.sethook
local coroutine_running = coroutine.running
local error = error
local checkDeadline = raw.checkdeadline
local interval = raw.HOOK_INTERVAL

local function hook()
  local reason = checkDeadline()
  if reason then
    debug_sethook(coroutine_running(), hook, "", 1)
    error(reason, 0)
  end
end

function raw.sethook(co)
  debug_sethook(co, hook, "", interval)
end

function raw.newenv()
  return nil
end
