# Lua Computer
![Hello from Lua](block:oc2r:lua_computer)

A computer running the OpenComputers 1 Lua environment, which is what software such as OpenOS and MineOS is written against. It is a separate machine from the [Computer](computer.md), which boots Linux on a virtual RISC-V processor instead.

Read the [Lua getting started guide](../lua_getting_started.md) for a walkthrough from placing the block to writing a program.

### Configuration
There is none, and that is the point: the processor, memory, graphics card, canvas card and disk are all part of the block, so it works as soon as it is placed. Server owners can change what those parts are worth in `config/oc2r-common.toml`, under `[lua_machine]`.

### Screens
Place a [Lua Screen](lua_screen.md) directly against the computer, on any of its six sides. Adjacency is the whole connection; there is no cable to run. A screen brings a keyboard with it, so anything typed into that screen's terminal window arrives at this machine.

### Power
Right click to switch it on and off. It glows while it is running.

Computers need energy. The block holds a small buffer, enough for about ten seconds, so one with nothing feeding it will start and then stop with `not enough energy` written on its screen.

### Storage
The disk is built in and travels with the block: mine the computer and whatever was installed on it comes along in the item. It holds 32MB by default.

The machine also has a temporary filesystem, which is emptied whenever the computer restarts, and a read only ROM carrying a small shell. That shell is what a computer with an empty disk boots into, so a new machine has a working prompt rather than a blank screen.
