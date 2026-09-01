# Getting Started

This article describes the steps required to get a [computer](block/computer.md) up and running, and gives an example of how it can be used to interact with devices.

If you are after the other kind of machine, the one that runs OpenComputers 1 software such as OpenOS and MineOS, see [Lua computers](lua_getting_started.md) instead.

## Building

First things first, you need an actual computer, plus the parts that go inside it. It will not start until it has all of these:

- 1x [computer](block/computer.md)
- 1x **CPU** of any tier. The number in brackets is its clock speed; a faster one runs the same software faster and draws more energy.
- 1x **Flash Memory (Minux)**, which is the firmware. Craft it from a blank [flash memory](item/flash_memory.md) and a [wrench](item/wrench.md). Minux carries the Linux kernel, which is what actually boots.
- 3x 8MB [Memory](item/memory.md). The kernel is loaded into memory before anything runs, so a computer without enough of it stops with *Insufficient Memory*; the preconfigured computer uses four.

A [hard drive](item/hard_drive.md) is optional. The system boots without one; what a drive gives you is somewhere to keep files that survives a restart.

In creative mode there is a shortcut: the **Preconfigured Computer** in the creative tab arrives with all of the above already installed.

![The basics](../img/getting_started_basics.png)

Once you have the parts, place the computer down and open its inventory screen: use it with a [wrench](item/wrench.md), or open the terminal and press the bottom of the three buttons to the left of it. Put the CPU, the flash memory and the memory into their slots. Each slot only takes the kind of part it is for.

![Computer inventory](../img/getting_started_inventory.png)

## Starting

Computers need energy. The bar to the left of the terminal shows how much is stored; its tooltip gives the amount and how much is drawn per tick. A computer with nothing feeding it will not get far, so put a generator, a cable, or a [charger](block/charger.md) on it first. In creative mode, the Infinite Energy Cube does the job.

![Computer energy info](../img/getting_started_energy.png)

Now switch to the terminal and press the **top** button to the left of it, the power button. Sneaking and using the computer starts it too, without opening anything. The screen should fill with kernel messages, and after a few seconds you are asked to log in.

![Login prompt](../img/getting_started_login.png)

## Typing at it

**The keyboard does nothing until input is captured.** That is the middle of the three buttons to the left of the terminal. With it off, what you type goes to Minecraft; with it on, it goes to the computer, including keys Minecraft would otherwise use.

So: press the middle button, then type *root* at the login prompt and press enter. Well done, you now have a computer that is ready for use.

## When it will not start

The terminal says why, in the middle of the screen:

- **Missing CPU** - there is no CPU in the computer.
- **Missing Firmware** - there is no flash memory with a firmware on it. A blank one does not count; it needs to be a Minux chip, or one written with a [Flash Memory Flasher](block/flash_memory_flasher.md).
- **Insufficient Memory** - add more memory.
- **Not Enough Energy** - it cannot afford to run. Check the energy bar.
- **Bus Incomplete**, **Bus Too Complex**, **Multiple Bus Controllers** - something is wrong with the [bus](block/bus_cable.md) rather than with the computer. Only one computer may control a bus.

A screen that is simply black, with no message and no power light, is a computer that has not been switched on.

## Next steps

You can now add more devices, depending on what you want to use your computer for. For information on how to control devices, have a look at the [scripting](scripting.md) manual entry.

Good luck, and most importantly, have fun!
