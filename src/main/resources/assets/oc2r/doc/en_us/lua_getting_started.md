# Lua Computers: Getting Started

This is a walkthrough for the [Lua Computer](block/lua_computer.md), from placing the block to writing your first program. It is a different machine from the [Computer](block/computer.md): that one boots Linux on a virtual RISC-V processor, this one runs the OpenComputers 1 Lua environment, which is what software like OpenOS and MineOS is written for.

## What you need

Two blocks: a [Lua Computer](block/lua_computer.md) and a [Lua Screen](block/lua_screen.md).

The computer has no card slots and no inventory - the processor, memory, graphics card and disk are all part of the block - but it has no display, the way an OpenComputers computer case has none. The screen is the other half of the machine, and it brings the keyboard with it. It needs no power either, unless the server has turned that cost on.

## Putting one together

Place the computer, then place the screen against it: directly on top is the usual arrangement, but any of the six sides works. Adjacency is the whole connection - no cable, no bus interface - and a screen one block away is a screen the computer cannot see.

One computer can see every screen it is touching, and a screen delivers what is typed at it to every computer it is touching, so a wall of screens or a shared terminal are both just a matter of where the blocks go.

## Switching it on

**Sneak and right click** the computer to turn it on, and again to turn it off. It lights up while it is running, which is the quickest way to tell from across the room.

## Using it

**Right click** the screen to open its terminal window. Right clicking the computer opens the same window, on whichever screen is against it; if it has none, it says so instead, because a case with no monitor has nothing to show.

The face of the screen block shows what the machine is drawing too, but a tier 3 screen is 160 by 50 characters, which is unreadable from where you would be standing, so the window is where the machine is actually used.

Down the left of that window, in a panel against the edge of the screen, are two buttons:

- The **power** button, the one with the arrow through the ring, which does the same as sneaking and right clicking the block.
- The **input** button below it, which decides who gets your keystrokes.

**The keyboard does nothing until input is captured.** Press the input button, then hold the pointer over the screen: while both are true, everything you type goes to the computer, Escape included, because programs use it. Move the pointer off the screen and Escape closes the window as usual. The window tells you which of the two is missing.

The mouse works as well: clicking, dragging and scrolling on the screen reach the program as touch, drag and scroll events, which is what a desktop like MineOS listens for. Ctrl+V pastes.

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

**Nothing happens when I right click the computer.** It has no screen of its own to open, so it opens the terminal on the screen against it. `No screen. Place a Lua Screen against the computer.` above your hotbar means there is none touching it. If nothing at all appears, you are holding a [wrench](item/wrench.md), which configures the block instead.

**The screen is black.** The computer is off, or there is no computer touching that screen. Press the power button in the terminal window, or sneak and right click the computer.

**Typing does nothing.** Press the input button, and keep the pointer over the screen.

**A message on a dark screen.** That is the machine telling you why it stopped. `no bootable medium found` means the `init.lua` it tried would not load; `not enough energy` means the server charges for uptime and the block has none; anything else is an error from the program that was running.

## Settings

Server owners can change what a Lua machine is made of in `config/oc2r-common.toml`, under `[lua_machine]`: memory, the disk size, how long a program may run without yielding, how many component calls it may make per tick, and what running costs in energy. Every setting there carries a comment explaining what it costs.
