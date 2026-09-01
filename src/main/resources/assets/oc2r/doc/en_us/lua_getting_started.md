# Lua Computers: Getting Started

This is a walkthrough for the [Lua Computer](block/lua_computer.md), from placing the block to writing your first program. It is a different machine from the [Computer](block/computer.md): that one boots Linux on a virtual RISC-V processor, this one runs the OpenComputers 1 Lua environment, which is what software like OpenOS and MineOS is written for.

## What you need

- 1x [Lua Computer](block/lua_computer.md)
- 1x [Lua Screen](block/lua_screen.md)
- Something to power them. Any mod's RF generator works, or an [Infinite Energy Cube](block/index.md) in creative mode.

That is the whole shopping list. Unlike its RISC-V sibling, a Lua Computer has no card slots: the processor, memory, graphics card and disk are all part of the block.

## Putting it together

Place the computer, then place the screen **directly against it**. Any of the six sides will do - above, below, or beside. Adjacency is what connects them; there is no cable and no bus interface, and a screen one block away is a screen the computer cannot see.

Then give it power. The computer holds a small buffer of its own, enough for about ten seconds, so a machine with nothing feeding it will start, run briefly, and stop with `not enough energy` on its screen.

## Switching it on

**Right click the computer** to turn it on. Right click it again to turn it off. It lights up while it is running, which is the quickest way to tell from across the room.

## Using it

**Right click the screen** to open the terminal. The screen shows a preview on its face, but a tier 3 screen is 160 by 50 characters, which is unreadable on the side of a block, so the window is where the machine is actually used.

At the top right of that window is a button that toggles input capture:

- **Capture Input** (the default) sends every key you press to the computer, including Escape, because programs use it. Press the button to get out.
- **Release Input** gives the keys back to Minecraft, and Escape closes the window.

The mouse works too: clicking, dragging and scrolling on the screen are delivered to the program as touch, drag and scroll events, which is what a desktop like MineOS listens for. Ctrl+V pastes.

## The built in shell

A computer that has nothing installed boots the shell in its ROM. You should see a prompt like this:

    /disk >

Type `help` for the full list of commands. The ones to start with:

| Command | What it does |
| --- | --- |
| `ls` | list a directory |
| `cd /disk` | change directory |
| `edit hello.lua` | type a file; a line with a single `.` saves it |
| `run hello.lua` | run a Lua program |
| `lua` | an interactive Lua prompt; `exit` leaves |
| `devices` | every component this machine has, with its address |
| `df`, `free` | how much disk and memory you have |
| `canvas`, `text` | switch the screen between pixels and text |
| `reboot`, `shutdown` | what they say |

Ctrl+C interrupts what you are typing, Ctrl+L clears the screen, and the up arrow recalls what you typed before.

Storage appears as one directory per filesystem under the root, so `ls /` tells you what storage the machine has. Your disk is `/disk`, the scratch space is `/tmpfs` and is emptied when the computer restarts, and `/rom` is the shell itself, which is read only.

## Your first program

    edit /disk/hello.lua

Type this, then a line with a single `.` to save:

    local gpu = component.proxy(component.list("gpu")())
    gpu.set(3, 3, "Hello from " .. computer.address():sub(1, 8))
    return "done"

Then run it:

    run /disk/hello.lua

Two things in there are worth remembering. `component.list("gpu")` returns a *table* that can be called as an iterator, so calling it once gives you the first address - and it returns two values, the address and the type, which is why it is wrapped in parentheses when only one is wanted. `component.proxy` turns an address into a table of functions you can call.

For what else is available, `devices` lists the components, and every OpenComputers 1 program and tutorial applies: this is that API.

## Drawing pictures

The computer also has a Tier 4 canvas card, which draws pixels rather than characters. Try `canvas` at the prompt for a demonstration, and `text` to go back.

From a program it works like the graphics card, but in ARGB colour:

    local canvas = component.proxy(component.list("canvas")())
    canvas.bind((component.list("screen")()))
    canvas.clear(0xFF101820)
    canvas.fillEllipse(40, 20, 120, 120, 0xFFFFCC33)
    canvas.line(1, 1, 320, 200, 0xFF66CCFF)

The screen shows one buffer at a time and follows whichever card drew last, so a `gpu` call brings the terminal back. Nothing is lost in either direction: the text is still there while the canvas is showing, and vice versa.

## Installing an operating system

The shell in ROM is a floor, not a destination. To run something better, put it on the disk:

1. Copy the operating system's files onto `/disk`, with its `/disk/init.lua` in place.
2. `reboot`.

The BIOS tries every writable filesystem before it falls back to the ROM, so an installed system always wins, and the ROM is never remembered as the boot device. Deleting `/disk/init.lua` and rebooting puts you back at the shell.

## When something is wrong

**The screen is black and nothing happens.** The computer is off, or the screen is not touching it. Right click the computer; check it lights up.

**`not enough energy`.** Feed it. The internal buffer is small on purpose.

**Typing does nothing.** Check the button at the top right of the terminal window says *Release Input* - if it says *Capture Input*, keys are going to Minecraft instead.

**A message on a dark screen.** That is the machine telling you why it stopped. `no bootable medium found` means the `init.lua` it tried would not load; anything else is an error from the program that was running.

## Settings

Server owners can change what a Lua machine is made of in `config/oc2r-common.toml`, under `[lua_machine]`: memory, the disk size, how long a program may run without yielding, and how many component calls it may make per tick. Every setting there carries a comment explaining what it costs.
