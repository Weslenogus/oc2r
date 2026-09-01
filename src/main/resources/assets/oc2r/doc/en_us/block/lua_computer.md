# Lua Computer
![Hello from Lua](block:oc2r:lua_computer)

A computer running the OpenComputers 1 Lua environment, which is what software such as OpenOS and MineOS is written against. It is a separate machine from the [Computer](computer.md), which boots Linux on a virtual RISC-V processor instead.

Read the [Lua getting started guide](../lua_getting_started.md) for a walkthrough from placing the block to writing a program.

### Using it
**Right click** to open the terminal. **Sneak and right click** to switch the machine on or off; it glows while it is running, and its front panel shows what is on the screen.

### Configuration
There is none, and that is the point: the processor, memory, graphics card, canvas card, disk and screen are all part of the block, so it works as soon as it is placed. Server owners can change what those parts are worth in `config/oc2r-common.toml`, under `[lua_machine]`.

### Power
None needed. A Lua computer has no energy bar to read, so charging it for its uptime would mean a block that stops a second after it starts with nothing to say why; the cost is off unless a server turns it on, and the block accepts energy from any side for when it does.

### Screens
The computer has a display of its own, so nothing else is required. A [Lua Screen](lua_screen.md) placed directly against it is an external monitor: much larger, with a keyboard of its own, and showing the same machine.

### Storage
The disk is built in and travels with the block: mine the computer and whatever was installed on it comes along in the item. It holds 32MB by default.

The machine also has a temporary filesystem, which is emptied whenever the computer restarts, and a read only ROM carrying a small shell. That shell is what a computer with an empty disk boots into, so a new machine has a working prompt rather than a blank screen.
