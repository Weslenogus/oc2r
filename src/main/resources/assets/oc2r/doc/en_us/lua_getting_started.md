# Lua Computers: Getting Started

This is a walkthrough for the [Lua Computer](block/lua_computer.md), from placing the block to writing your first program. It is a different machine from the [Computer](block/computer.md): that one boots Linux on a virtual RISC-V processor, this one runs the OpenComputers 1 Lua environment, which is what software like OpenOS and MineOS is written for.

## What you need

One [Lua Computer](block/lua_computer.md). That is the whole list.

It has no card slots and no inventory: the processor, memory, graphics card, disk and screen are all part of the block. It needs no power either, unless the server has turned that cost on.

## Switching it on

**Sneak and right click** the computer to turn it on, and again to turn it off. It lights up while it is running, which is the quickest way to tell from across the room.

## Using it

**Right click** the computer to open its terminal. The front of the block shows what is on the screen too, but a tier 3 screen is 160 by 50 characters, which is unreadable from where you would be standing, so the window is where the machine is actually used.

Down the left of that window are two buttons:

- The **power** button, which does the same as sneaking and right clicking the block.
- The **input** button, which decides who gets your keystrokes.

**The keyboard does nothing until input is captured.** Press the input button, then hold the pointer over the screen: while both are true, everything you type goes to the computer, Escape included, because programs use it. Move the pointer off the screen and Escape closes the window as usual. The window tells you which of the two is missing.

The mouse works as well: clicking, dragging and scrolling on the screen reach the program as touch, drag and scroll events, which is what a desktop like MineOS listens for. Ctrl+V pastes.

## A bigger screen

A [Lua Screen](block/lua_screen.md) is an external display. Place one directly against the computer, on any of the six sides, and the machine can draw to it as well: it is a second, much larger view of the same computer, and it brings a keyboard of its own. Right click it to open the same terminal window.

Adjacency is the whole connection - no cable, no bus interface - and a screen one block away is a screen the computer cannot see.

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

**Nothing happens when I right click it.** Right clicking opens the terminal; if the window does not appear, you are holding a [wrench](item/wrench.md), which configures the block instead.

**The screen is black.** The computer is off. Press the power button in the terminal window, or sneak and right click the block.

**Typing does nothing.** Press the input button, and keep the pointer over the screen.

**A message on a dark screen.** That is the machine telling you why it stopped. `no bootable medium found` means the `init.lua` it tried would not load; `not enough energy` means the server charges for uptime and the block has none; anything else is an error from the program that was running.

## Settings

Server owners can change what a Lua machine is made of in `config/oc2r-common.toml`, under `[lua_machine]`: memory, the disk size, how long a program may run without yielding, how many component calls it may make per tick, and what running costs in energy. Every setting there carries a comment explaining what it costs.
