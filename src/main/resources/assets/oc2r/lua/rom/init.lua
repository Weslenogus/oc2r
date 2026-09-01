--[[ SPDX-License-Identifier: MIT

  The built in shell, held in the computer's ROM.

  A Lua Computer that has just been placed has an empty disk. Without something here the stock BIOS
  scans every filesystem, finds no /init.lua on any of them, and stops with "no bootable medium
  found" - which a player experiences as a screen that stays black and a keyboard that appears to
  do nothing. This is what it boots instead: a small operating system that draws a prompt, reads
  the keyboard, and runs commands.

  It is deliberately not an OpenOS replacement. It is the floor: enough of a system to look around,
  write and run a Lua program, and install a real operating system onto the disk. Anything
  installed there takes precedence, because the BIOS only falls back to a read only medium once
  every writable one has been tried.

  Everything here is written against the plain OpenComputers 1 API - component, computer, unicode -
  because that is all the sandbox offers, and because a program that works here works on a real
  OpenComputers machine too.

  The syntax stays inside Lua 5.2. The mod prefers real Lua 5.3 but falls back to the pure Java
  backend when the native library will not load, and that backend is a 5.2 implementation: an
  integer division or a bitwise operator here would be a syntax error on the very machines that
  most need something to boot.
]]

local VERSION = "oc2r shell 1.0"

-- Palette. Kept muted on purpose: a terminal that is read for minutes at a time should not be
-- shouting, and the colours have to stay legible at the 8 bit depth a tier 3 screen has.
local COLORS = {
  text = 0xD0D0D0,
  dim = 0x808080,
  accent = 0x66CCFF,
  good = 0x99DD66,
  bad = 0xFF6666,
  background = 0x000000,
}

local term = {}
local files = {}
local shell = {}
local commands = {}

-------------------------------------------------------------------------------
-- Terminal.
--
-- One screen, one graphics card, a cursor, and a scrolling region that is the whole screen. The
-- cursor is drawn by inverting the cell under it rather than by writing a block character, so it
-- never destroys what is beneath it.

term.gpu = nil
term.screen = nil
term.width = 0
term.height = 0
term.x = 1
term.y = 1
term.cursorVisible = false

function term.attach()
  local gpuAddress = component.list("gpu")()
  local screenAddress = component.list("screen")()
  if not gpuAddress or not screenAddress then
    return false
  end

  term.gpu = component.proxy(gpuAddress)
  term.screen = screenAddress
  term.gpu.bind(screenAddress)

  local width, height = term.gpu.maxResolution()
  term.gpu.setResolution(width, height)
  term.width, term.height = term.gpu.getResolution()

  term.gpu.setBackground(COLORS.background)
  term.gpu.setForeground(COLORS.text)
  term.clear()
  return true
end

--- Waits until a screen and a graphics card are both present.
-- A computer can legitimately be running before anyone has placed a screen next to it, and dying
-- because of that would put the explanation somewhere nobody can read it.
function term.waitForScreen()
  while not term.attach() do
    computer.pullSignal(1)
  end
end

function term.clear()
  term.gpu.setBackground(COLORS.background)
  term.gpu.setForeground(COLORS.text)
  term.gpu.fill(1, 1, term.width, term.height, " ")
  term.x, term.y = 1, 1
  term.cursorVisible = false
end

function term.scroll()
  term.gpu.copy(1, 2, term.width, term.height - 1, 0, -1)
  term.gpu.setBackground(COLORS.background)
  term.gpu.fill(1, term.height, term.width, 1, " ")
end

function term.newLine()
  term.x = 1
  if term.y >= term.height then
    term.scroll()
    term.y = term.height
  else
    term.y = term.y + 1
  end
end

--- Writes text at the cursor, wrapping at the right edge and scrolling at the bottom.
function term.write(text, color)
  if term.gpu == nil then
    return
  end

  term.gpu.setForeground(color or COLORS.text)
  text = tostring(text)

  while #text > 0 do
    local line, rest = text:match("^([^\n]*)\n(.*)$")
    if not line then
      line, rest = text, nil
    end

    while unicode.len(line) > 0 do
      if term.x > term.width then
        term.newLine()
      end
      local chunk = unicode.sub(line, 1, term.width - term.x + 1)
      term.gpu.set(term.x, term.y, chunk)
      term.x = term.x + unicode.len(chunk)
      line = unicode.sub(line, unicode.len(chunk) + 1, unicode.len(line))
    end

    if rest then
      term.newLine()
      text = rest
    else
      text = ""
    end
  end

  term.cursorVisible = false
  term.gpu.setForeground(COLORS.text)
end

function term.line(text, color)
  term.write((text or "") .. "\n", color)
end

--- Draws, or undraws, the block cursor.
function term.showCursor(visible)
  if term.cursorVisible == visible or term.gpu == nil then
    return
  end
  term.cursorVisible = visible

  local char = term.gpu.get(term.x, term.y)
  if char == nil or char == "" then
    char = " "
  end

  if visible then
    term.gpu.setForeground(COLORS.background)
    term.gpu.setBackground(COLORS.text)
  end
  term.gpu.set(term.x, term.y, char)
  term.gpu.setForeground(COLORS.text)
  term.gpu.setBackground(COLORS.background)
end

-------------------------------------------------------------------------------
-- Keyboard.
--
-- Minecraft splits a keystroke in two: a key event carrying the physical key, and, for printable
-- keys, a character event carrying what was typed. Both arrive here as key_down signals, one with
-- a code and no character, one with a character and no code, so both shapes have to be handled.
--
-- The codes are the ones OpenComputers programs expect, which are the old LWJGL 2 scan codes. The
-- mod translates from what Minecraft reports before the signal is queued.

local KEYS = {
  back = 0x0E,
  tab = 0x0F,
  enter = 0x1C,
  numpadEnter = 0x9C,
  control = 0x1D,
  rightControl = 0x9D,
  escape = 0x01,
  delete = 0xD3,
  home = 0xC7,
  ["end"] = 0xCF,
  up = 0xC8,
  down = 0xD0,
  left = 0xCB,
  right = 0xCD,
  c = 0x2E,
  l = 0x26,
}

local controlHeld = false

--- Pulls the next signal, keeping track of the control key on the way past.
local function pullSignal(timeout)
  local signal = table.pack(computer.pullSignal(timeout))
  local name, code = signal[1], signal[4]
  if name == "key_down" and (code == KEYS.control or code == KEYS.rightControl) then
    controlHeld = true
  elseif name == "key_up" and (code == KEYS.control or code == KEYS.rightControl) then
    controlHeld = false
  end
  return signal
end

-------------------------------------------------------------------------------
-- Line editing.
--
-- The line being edited lives on one screen row and scrolls sideways within it. On a 160 column
-- screen that is not a limit anyone will meet, and it avoids the reflow bookkeeping that wrapping
-- an editable line across several rows needs.

-- What is left of a multi line paste. Held here rather than passed around because it belongs to
-- the terminal, not to any one prompt: the next read is the one that should see it.
local pendingInput = nil

local function readLine(prompt, history)
  term.write(prompt, COLORS.accent)

  local startX, row = term.x, term.y
  local room = term.width - startX
  local buffer, cursor, offset = "", 0, 0
  local index = history and (#history + 1) or 1

  local function redraw()
    -- Keep the cursor inside the visible window before drawing, so the two agree.
    if cursor < offset then
      offset = cursor
    elseif cursor - offset > room - 1 then
      offset = cursor - room + 1
    end

    local visible = unicode.sub(buffer, offset + 1, offset + room)
    term.gpu.setForeground(COLORS.text)
    term.gpu.set(startX, row, visible .. string.rep(" ", room - unicode.len(visible)))
    term.x = startX + (cursor - offset)
    term.y = row
    term.cursorVisible = false
  end

  local function insert(text)
    text = tostring(text):gsub("%c", "")
    if #text == 0 then
      return
    end
    buffer = unicode.sub(buffer, 1, cursor) .. text .. unicode.sub(buffer, cursor + 1, unicode.len(buffer))
    cursor = cursor + unicode.len(text)
    redraw()
  end

  local function recall(direction)
    if not history or #history == 0 then
      return
    end
    index = math.max(1, math.min(#history + 1, index + direction))
    buffer = history[index] or ""
    cursor = unicode.len(buffer)
    redraw()
  end

  local function submit()
    term.showCursor(false)
    term.x = startX + math.min(unicode.len(buffer) - offset, room)
    term.y = row
    term.newLine()
    if history and #buffer > 0 and history[#history] ~= buffer then
      history[#history + 1] = buffer
    end
    return buffer
  end

  redraw()

  -- A line that was pasted in behind this one is answered without waiting for a keystroke.
  if pendingInput then
    local head, tail = pendingInput:match("^([^\n]*)\n(.*)$")
    if head then
      pendingInput = (tail ~= "" and tail or nil)
    else
      head, pendingInput = pendingInput, nil
    end
    insert(head)
    return submit()
  end

  while true do
    term.showCursor(computer.uptime() % 1 < 0.5)
    local signal = pullSignal(0.25)
    local name, char, code = signal[1], signal[3], signal[4]

    if name == "key_down" then
      term.showCursor(false)

      if controlHeld and code == KEYS.c then
        term.line("^C", COLORS.dim)
        return nil, "interrupted"
      elseif controlHeld and code == KEYS.l then
        term.clear()
        term.write(prompt, COLORS.accent)
        startX, row = term.x, term.y
        room = term.width - startX
        offset = 0
        redraw()
      elseif code == KEYS.enter or code == KEYS.numpadEnter then
        return submit()
      elseif code == KEYS.back then
        if cursor > 0 then
          buffer = unicode.sub(buffer, 1, cursor - 1)
            .. unicode.sub(buffer, cursor + 1, unicode.len(buffer))
          cursor = cursor - 1
          redraw()
        end
      elseif code == KEYS.delete then
        if cursor < unicode.len(buffer) then
          buffer = unicode.sub(buffer, 1, cursor)
            .. unicode.sub(buffer, cursor + 2, unicode.len(buffer))
          redraw()
        end
      elseif code == KEYS.left then
        cursor = math.max(0, cursor - 1)
        redraw()
      elseif code == KEYS.right then
        cursor = math.min(unicode.len(buffer), cursor + 1)
        redraw()
      elseif code == KEYS.home then
        cursor = 0
        redraw()
      elseif code == KEYS["end"] then
        cursor = unicode.len(buffer)
        redraw()
      elseif code == KEYS.up then
        recall(-1)
      elseif code == KEYS.down then
        recall(1)
      elseif char and char > 0 then
        insert(unicode.char(char))
      end
    elseif name == "clipboard" then
      -- A paste. Everything up to the first newline joins this line; a newline in the middle of a
      -- paste means whoever pasted it meant to press enter there, and the rest waits for the next
      -- prompt.
      term.showCursor(false)
      local text = signal[3] or ""
      local head, tail = text:match("^([^\n]*)\n(.*)$")
      insert(head or text)
      if head then
        pendingInput = (tail ~= "" and tail or nil)
        return submit()
      end
    end
  end
end

-------------------------------------------------------------------------------
-- Files.
--
-- Every filesystem component shows up as a directory under the root, named after its label, so
-- /disk is the computer's hard drive and /rom is this shell. That keeps copying between two
-- filesystems an ordinary cp, and it makes "ls /" answer the question "what storage do I have".

files.mounts = {}

function files.rescan()
  files.mounts = {}
  local taken = {}

  for address in component.list("filesystem") do
    local proxy = component.proxy(address)
    local name = proxy.getLabel() or ""
    if name == "" then
      name = address:sub(1, 4)
    end
    name = name:gsub("[^%w_%-%.]", "_")

    -- Two disks may carry the same label; the address suffix keeps them apart without renaming
    -- anything on the disk itself.
    if taken[name] then
      name = name .. "_" .. address:sub(1, 4)
    end
    taken[name] = true

    files.mounts[#files.mounts + 1] = {
      name = name,
      address = address,
      proxy = proxy,
      readOnly = proxy.isReadOnly(),
    }
  end

  table.sort(files.mounts, function(a, b) return a.name < b.name end)
end

function files.mount(name)
  for _, mount in ipairs(files.mounts) do
    if mount.name == name then
      return mount
    end
  end
  return nil
end

--- Turns any path into an absolute one with no . or .. left in it.
function files.canonical(path, cwd)
  if path == nil or path == "" then
    return cwd or "/"
  end
  if path:sub(1, 1) ~= "/" then
    path = (cwd or "/") .. "/" .. path
  end

  local parts = {}
  for segment in path:gmatch("[^/]+") do
    if segment == ".." then
      parts[#parts] = nil
    elseif segment ~= "." then
      parts[#parts + 1] = segment
    end
  end
  return "/" .. table.concat(parts, "/")
end

--- Splits an absolute path into the filesystem it names and the path within it.
-- @return the mount and the path inside it, or nil, nil and a reason.
function files.resolve(path, cwd)
  local absolute = files.canonical(path, cwd)
  local name, rest = absolute:match("^/([^/]+)(.*)$")
  if not name then
    return nil, nil, "no filesystem in path: " .. absolute
  end

  local mount = files.mount(name)
  if not mount then
    return nil, nil, "no such filesystem: " .. name
  end
  return mount, (rest == "" and "/" or rest), nil
end

function files.read(path, cwd)
  local mount, inner, reason = files.resolve(path, cwd)
  if not mount then
    return nil, reason
  end

  local handle, openReason = mount.proxy.open(inner, "r")
  if not handle then
    return nil, openReason or "cannot open"
  end

  local parts = {}
  repeat
    local chunk = mount.proxy.read(handle, math.huge)
    parts[#parts + 1] = chunk
  until not chunk
  mount.proxy.close(handle)
  return table.concat(parts)
end

function files.write(path, data, cwd, mode)
  local mount, inner, reason = files.resolve(path, cwd)
  if not mount then
    return nil, reason
  end
  if mount.readOnly then
    return nil, mount.name .. " is read only"
  end

  local handle, openReason = mount.proxy.open(inner, mode or "w")
  if not handle then
    return nil, openReason or "cannot open"
  end

  local ok, writeReason = pcall(mount.proxy.write, handle, data)
  mount.proxy.close(handle)
  if not ok then
    return nil, tostring(writeReason)
  end
  return true
end

-------------------------------------------------------------------------------
-- The shell.

shell.cwd = "/"
shell.history = {}
shell.running = true

local function formatSize(bytes)
  if bytes == math.huge then
    return "unlimited"
  end
  local units = {"B", "KB", "MB", "GB"}
  local index = 1
  while bytes >= 1024 and index < #units do
    bytes = bytes / 1024
    index = index + 1
  end
  return string.format(index == 1 and "%d%s" or "%.1f%s", bytes, units[index])
end

--- Splits a command line into words, honouring quotes so paths with spaces survive.
local function tokenize(line)
  local words, current, quote = {}, nil, nil
  for index = 1, #line do
    local char = line:sub(index, index)
    if quote then
      if char == quote then
        quote = nil
      else
        current = (current or "") .. char
      end
    elseif char == "'" or char == '"' then
      quote = char
      current = current or ""
    elseif char:match("%s") then
      if current then
        words[#words + 1] = current
        current = nil
      end
    else
      current = (current or "") .. char
    end
  end
  if current then
    words[#words + 1] = current
  end
  return words
end

function shell.prompt()
  return shell.cwd .. " > "
end

function shell.execute(line)
  local words = tokenize(line)
  if #words == 0 then
    return
  end

  local command = commands[words[1]]
  if not command then
    term.line(words[1] .. ": no such command. Try 'help'.", COLORS.bad)
    return
  end

  local ok, reason = pcall(command, table.unpack(words, 2))
  if not ok then
    term.line("error: " .. tostring(reason), COLORS.bad)
  end
end

-------------------------------------------------------------------------------
-- Commands.

local HELP = {
  {"help", "[command]", "this list, or what one command does"},
  {"ls", "[path]", "list a directory"},
  {"cd", "[path]", "change directory"},
  {"pwd", "", "print the working directory"},
  {"cat", "<file>", "print a file"},
  {"edit", "<file>", "type a file, one line at a time"},
  {"run", "<file> [args]", "run a Lua program"},
  {"lua", "", "an interactive Lua prompt"},
  {"mkdir", "<path>", "make a directory"},
  {"rm", "<path>", "delete a file or directory"},
  {"cp", "<from> <to>", "copy a file"},
  {"mv", "<from> <to>", "move or rename a file"},
  {"echo", "<text>", "print its arguments"},
  {"clear", "", "clear the screen"},
  {"df", "", "storage, used and free"},
  {"free", "", "memory, used and free"},
  {"uptime", "", "how long this machine has run"},
  {"devices", "", "the components attached to this machine"},
  {"label", "<fs> [name]", "read or set a filesystem label"},
  {"canvas", "", "draw on the screen in canvas mode"},
  {"text", "", "switch the screen back to text mode"},
  {"reboot", "", "restart the computer"},
  {"shutdown", "", "turn the computer off"},
}

function commands.help(name)
  if name then
    for _, entry in ipairs(HELP) do
      if entry[1] == name then
        term.line(entry[1] .. " " .. entry[2], COLORS.accent)
        term.line("  " .. entry[3])
        return
      end
    end
    term.line("no such command: " .. name, COLORS.bad)
    return
  end

  term.line(VERSION, COLORS.accent)
  term.line("Commands:", COLORS.dim)
  for _, entry in ipairs(HELP) do
    local usage = entry[1] .. " " .. entry[2]
    term.write("  " .. usage .. string.rep(" ", math.max(1, 20 - #usage)))
    term.line(entry[3], COLORS.dim)
  end
  term.line("")
  term.line("Storage lives under / - one directory per filesystem. Your disk is /disk.", COLORS.dim)
  term.line("Ctrl+C interrupts, Ctrl+L clears, the up arrow recalls a command.", COLORS.dim)
end

function commands.ls(path)
  local target = files.canonical(path, shell.cwd)

  if target == "/" then
    files.rescan()
    for _, mount in ipairs(files.mounts) do
      term.write(mount.name .. "/" .. string.rep(" ", math.max(1, 16 - #mount.name)), COLORS.accent)
      term.line(string.format("%s  %s of %s used%s",
        mount.address:sub(1, 8),
        formatSize(mount.proxy.spaceUsed()),
        formatSize(mount.proxy.spaceTotal()),
        mount.readOnly and ", read only" or ""), COLORS.dim)
    end
    return
  end

  local mount, inner, reason = files.resolve(target, shell.cwd)
  if not mount then
    term.line(reason, COLORS.bad)
    return
  end

  local list = mount.proxy.list(inner)
  if not list then
    term.line("not a directory: " .. target, COLORS.bad)
    return
  end

  local names = {}
  for index = 1, #list do
    names[#names + 1] = list[index]
  end
  table.sort(names)

  if #names == 0 then
    term.line("(empty)", COLORS.dim)
    return
  end

  for _, name in ipairs(names) do
    if name:sub(-1) == "/" then
      term.line(name, COLORS.accent)
    else
      term.write(name .. string.rep(" ", math.max(1, 28 - #name)))
      term.line(formatSize(mount.proxy.size(inner .. "/" .. name)), COLORS.dim)
    end
  end
end

function commands.cd(path)
  local target = files.canonical(path or "/", shell.cwd)
  if target ~= "/" then
    local mount, inner, reason = files.resolve(target, shell.cwd)
    if not mount then
      term.line(reason, COLORS.bad)
      return
    end
    if not mount.proxy.isDirectory(inner) then
      term.line("not a directory: " .. target, COLORS.bad)
      return
    end
  end
  shell.cwd = target
end

function commands.pwd()
  term.line(shell.cwd)
end

function commands.cat(path)
  if not path then
    term.line("usage: cat <file>", COLORS.bad)
    return
  end
  local data, reason = files.read(path, shell.cwd)
  if not data then
    term.line(reason, COLORS.bad)
    return
  end
  term.write(data)
  if data:sub(-1) ~= "\n" then
    term.line("")
  end
end

function commands.edit(path)
  if not path then
    term.line("usage: edit <file>", COLORS.bad)
    return
  end

  term.line("Type the file. A line holding a single . saves it; :q throws it away.", COLORS.dim)
  local lines = {}
  while true do
    local line = readLine(string.format("%3d| ", #lines + 1), nil)
    if line == nil or line == ":q" then
      term.line("not saved", COLORS.dim)
      return
    end
    if line == "." then
      break
    end
    lines[#lines + 1] = line
  end

  local ok, reason = files.write(path, table.concat(lines, "\n") .. "\n", shell.cwd)
  if ok then
    term.line(string.format("wrote %d line%s to %s",
      #lines, #lines == 1 and "" or "s", files.canonical(path, shell.cwd)), COLORS.good)
  else
    term.line(reason, COLORS.bad)
  end
end

function commands.run(path, ...)
  if not path then
    term.line("usage: run <file> [args]", COLORS.bad)
    return
  end

  local source, reason = files.read(path, shell.cwd)
  if not source then
    term.line(reason, COLORS.bad)
    return
  end

  local program, loadReason = load(source, "=" .. path)
  if not program then
    term.line(tostring(loadReason), COLORS.bad)
    return
  end

  local results = table.pack(pcall(program, ...))
  if not results[1] then
    term.line("error: " .. tostring(results[2]), COLORS.bad)
    return
  end
  for index = 2, results.n do
    term.line(tostring(results[index]))
  end
end

function commands.lua()
  term.line("An interactive Lua prompt. 'exit' leaves; expressions print their value.", COLORS.dim)
  local history = {}
  while true do
    local line = readLine("lua> ", history)
    if line == nil or line == "exit" then
      return
    end
    if #line > 0 then
      -- Try it as an expression first, so typing 1+1 prints 2 rather than complaining about
      -- syntax, then fall back to running it as a statement.
      local chunk, reason = load("return " .. line, "=stdin")
      if not chunk then
        chunk, reason = load(line, "=stdin")
      end

      if not chunk then
        term.line(tostring(reason), COLORS.bad)
      else
        local results = table.pack(pcall(chunk))
        if not results[1] then
          term.line("error: " .. tostring(results[2]), COLORS.bad)
        else
          for index = 2, results.n do
            term.line(tostring(results[index]), COLORS.good)
          end
        end
      end
    end
  end
end

function commands.mkdir(path)
  if not path then
    term.line("usage: mkdir <path>", COLORS.bad)
    return
  end
  local mount, inner, reason = files.resolve(path, shell.cwd)
  if not mount then
    term.line(reason, COLORS.bad)
    return
  end
  if mount.readOnly then
    term.line(mount.name .. " is read only", COLORS.bad)
    return
  end
  if not mount.proxy.makeDirectory(inner) then
    term.line("could not create " .. path, COLORS.bad)
  end
end

function commands.rm(path)
  if not path then
    term.line("usage: rm <path>", COLORS.bad)
    return
  end
  local mount, inner, reason = files.resolve(path, shell.cwd)
  if not mount then
    term.line(reason, COLORS.bad)
    return
  end
  if mount.readOnly then
    term.line(mount.name .. " is read only", COLORS.bad)
    return
  end
  if not mount.proxy.remove(inner) then
    term.line("could not remove " .. path, COLORS.bad)
  end
end

function commands.cp(from, to)
  if not from or not to then
    term.line("usage: cp <from> <to>", COLORS.bad)
    return
  end
  local data, reason = files.read(from, shell.cwd)
  if not data then
    term.line(reason, COLORS.bad)
    return
  end
  local ok, writeReason = files.write(to, data, shell.cwd)
  if not ok then
    term.line(writeReason, COLORS.bad)
  end
end

function commands.mv(from, to)
  if not from or not to then
    term.line("usage: mv <from> <to>", COLORS.bad)
    return
  end

  local source, sourcePath, sourceReason = files.resolve(from, shell.cwd)
  local target, targetPath, targetReason = files.resolve(to, shell.cwd)
  if not source then
    term.line(sourceReason, COLORS.bad)
    return
  end
  if not target then
    term.line(targetReason, COLORS.bad)
    return
  end

  -- Within one filesystem this is a rename; across two it has to be a copy, because a rename
  -- cannot cross a device boundary any more than it can on a real machine.
  if source.address == target.address then
    if source.readOnly then
      term.line(source.name .. " is read only", COLORS.bad)
    elseif not source.proxy.rename(sourcePath, targetPath) then
      term.line("could not move " .. from, COLORS.bad)
    end
    return
  end

  commands.cp(from, to)
  commands.rm(from)
end

function commands.echo(...)
  term.line(table.concat({...}, " "))
end

function commands.clear()
  term.clear()
end

function commands.df()
  files.rescan()
  for _, mount in ipairs(files.mounts) do
    local total = mount.proxy.spaceTotal()
    local used = mount.proxy.spaceUsed()
    term.write(mount.name .. string.rep(" ", math.max(1, 12 - #mount.name)), COLORS.accent)
    term.line(string.format("%8s used, %8s free%s",
      formatSize(used),
      formatSize(total == math.huge and math.huge or total - used),
      mount.readOnly and "   (read only)" or ""))
  end
end

function commands.free()
  local total = computer.totalMemory()
  local free = computer.freeMemory()
  term.line(string.format("%s of %s free (%d%% used)",
    formatSize(free), formatSize(total), math.floor((total - free) / total * 100)))
end

function commands.uptime()
  local seconds = math.floor(computer.uptime())
  term.line(string.format("%d:%02d:%02d",
    math.floor(seconds / 3600), math.floor(seconds / 60) % 60, seconds % 60))
end

function commands.devices()
  local found = {}
  for address, kind in component.list() do
    found[#found + 1] = {address = address, kind = kind}
  end
  table.sort(found, function(a, b)
    if a.kind == b.kind then
      return a.address < b.address
    end
    return a.kind < b.kind
  end)

  for _, entry in ipairs(found) do
    term.write(entry.kind .. string.rep(" ", math.max(1, 14 - #entry.kind)), COLORS.accent)
    term.line(entry.address, COLORS.dim)
  end
end

function commands.label(name, ...)
  if not name then
    term.line("usage: label <filesystem> [new name]", COLORS.bad)
    return
  end

  files.rescan()
  local mount = files.mount(name)
  if not mount then
    term.line("no such filesystem: " .. name, COLORS.bad)
    return
  end

  local newLabel = table.concat({...}, " ")
  if #newLabel == 0 then
    term.line(mount.proxy.getLabel() or "(none)")
    return
  end
  if mount.readOnly then
    term.line(mount.name .. " is read only", COLORS.bad)
    return
  end
  mount.proxy.setLabel(newLabel)
  files.rescan()
end

--- Draws something on the tier 4 canvas, which is also the easiest way to see one working.
function commands.canvas()
  local address = component.list("canvas")()
  if not address then
    term.line("no canvas card in this machine", COLORS.bad)
    return
  end

  local canvas = component.proxy(address)
  canvas.bind(term.screen)
  local width, height = canvas.getResolution()
  local step = math.max(1, math.floor(width / 32))

  canvas.clear(0xFF101820)
  for index = 0, 31 do
    -- A rising bar chart, each bar a little lighter than the one before it.
    local shade = 0xFF000000 + index * 8 * 65536 + index * 4 * 256 + 0x60
    local barHeight = math.floor(height * (index + 1) / 40)
    canvas.fill(1 + index * step, height - barHeight + 1, step - 1, barHeight, shade)
  end

  local size = math.min(width, height) / 3
  canvas.fillEllipse(math.floor(width / 2 - size / 2), 12, math.floor(size), math.floor(size), 0xFFFFCC33)
  canvas.ellipse(math.floor(width / 2 - size / 2), 12, math.floor(size), math.floor(size), 0xFFFFFFFF)
  canvas.line(1, math.floor(height / 2), width, math.floor(height / 2) - 20, 0xFF66CCFF)
  canvas.fillPolygon({20, height - 20, 60, height - 70, 100, height - 20}, 0xFF99DD66)

  term.line("Drawn. Run 'text' to bring the terminal back.", COLORS.good)
end

function commands.text()
  -- Whichever card last drew decides what the screen shows, so redrawing the text buffer is what
  -- actually switches the mode back.
  term.gpu.bind(term.screen)
  term.clear()
  shell.banner()
end

function commands.reboot()
  shell.running = false
  computer.shutdown(true)
end

function commands.shutdown()
  shell.running = false
  computer.shutdown()
end

-------------------------------------------------------------------------------
-- Boot.

function shell.banner()
  term.line(VERSION, COLORS.accent)
  term.line("A Lua Computer running the OpenComputers 1 environment.", COLORS.dim)
  term.line("Type 'help' for the command list. Storage is under /; your disk is /disk.", COLORS.dim)
  term.line("")
end

--- Picks somewhere sensible to start: the first writable disk, or the root if there is none.
function shell.chooseStartDirectory()
  for _, mount in ipairs(files.mounts) do
    if not mount.readOnly and mount.name ~= "tmpfs" then
      return "/" .. mount.name
    end
  end
  return "/"
end

term.waitForScreen()
files.rescan()
shell.cwd = shell.chooseStartDirectory()

shell.banner()

while shell.running do
  local line = readLine(shell.prompt(), shell.history)
  if line then
    shell.execute(line)
  end
end
