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

  Java decides which of those two an error is. Running out of time is fatal and takes the re-arm;
  running out of memory is not, and is raised as an ordinary error the program may catch and
  recover from, which is how it behaves in OpenComputers 1 and what an operating system there is
  written to expect.

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
  local reason, fatal = checkDeadline()
  if reason then
    if fatal then
      -- Re-arm to fire on the very next instruction. A program can catch the error, but the
      -- instruction after the handler raises it again, so it cannot get anywhere.
      debug_sethook(coroutine_running(), hook, "", 1)
    end
    error(reason, 0)
  end
end

function raw.sethook(co)
  debug_sethook(co, hook, "", interval)
end

function raw.newenv()
  return nil
end
