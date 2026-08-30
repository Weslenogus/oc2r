--[[ SPDX-License-Identifier: MIT

  Default EEPROM contents: the Lua BIOS.

  This is the first thing that runs inside the sandbox. Its whole job is to find a filesystem with
  an /init.lua on it, load that, and get out of the way, which is exactly the contract OpenOS and
  MineOS are written against.

  It is deliberately the stock OpenComputers 1 BIOS in behaviour, down to remembering the boot
  device in the EEPROM's data area. Operating system installers write that address themselves when
  they finish installing, and an installer that comes back to a machine which has forgotten where
  it booted from will offer to install again.
]]

local eeprom = component.list("eeprom")()

function computer.getBootAddress()
  return component.invoke(eeprom, "getData")
end

function computer.setBootAddress(address)
  return component.invoke(eeprom, "setData", address or "")
end

-- Bind the graphics card to a screen before anything else, so a failure below has somewhere to be
-- reported. Without this a machine with no bootable disk would simply sit there, dark.
do
  local screen = component.list("screen")()
  local gpu = component.list("gpu")()
  if gpu and screen then
    component.invoke(gpu, "bind", screen)
  end
end

local function tryLoadFrom(address)
  local handle, reason = component.invoke(address, "open", "/init.lua")
  if not handle then
    return nil, reason
  end

  local buffer = ""
  repeat
    -- math.huge asks for everything; the component caps each read and returns nil at the end, so
    -- this is a loop rather than a single call.
    local data, readReason = component.invoke(address, "read", handle, math.huge)
    if not data and readReason then
      component.invoke(address, "close", handle)
      return nil, readReason
    end
    buffer = buffer .. (data or "")
  until not data

  component.invoke(address, "close", handle)
  return load(buffer, "=init")
end

local init, reason

-- The remembered device first. Trying it before scanning keeps a machine with several disks
-- booting the same one every time, which matters once an OS has written configuration to it.
local bootAddress = computer.getBootAddress()
if bootAddress and #bootAddress > 0 then
  init, reason = tryLoadFrom(bootAddress)
end

if not init then
  computer.setBootAddress()
  for address in component.list("filesystem") do
    local candidate, candidateReason = tryLoadFrom(address)
    if candidate then
      init = candidate
      computer.setBootAddress(address)
      break
    end
    reason = reason or candidateReason
  end
end

if not init then
  error("no bootable medium found" .. (reason and (": " .. tostring(reason)) or ""), 0)
end

computer.beep(1000, 0.2)
return init()
