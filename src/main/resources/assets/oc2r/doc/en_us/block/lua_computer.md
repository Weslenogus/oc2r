# Lua Computer
![Hello from Lua](block:oc2r:lua_computer)

A computer running the OpenComputers 1 Lua environment, which is what software such as OpenOS and MineOS is written against. It is a separate machine from the [Computer](computer.md), which boots Linux on a virtual RISC-V processor instead.

Read the [Lua getting started guide](../lua_getting_started.md) for a walkthrough from placing the block to writing a program.

### Using it
**Right click** to open the computer's panel: the power button, whether the machine is running, and how many screens are touching it. **Sneak and right click** does the same as that power button; the block glows while it is running.

The terminal is not here. It belongs to the [Lua Screen](lua_screen.md), which is what you right click to actually use the machine.

### Configuration
There is none, and that is the point: the processor, memory, graphics card, canvas card and disk are all part of the block, so it works as soon as it is placed and a screen is put against it. Server owners can change what those parts are worth in `config/oc2r-common.toml`, under `[lua_machine]`.

### Power
None needed. A Lua computer has no energy bar to read, so charging it for its uptime would mean a block that stops a second after it starts with nothing to say why; the cost is off unless a server turns it on, and the block accepts energy from any side for when it does.

### Screens
The case has no display, the way an OpenComputers computer case has none. Place a [Lua Screen](lua_screen.md) directly against it, on any of the six sides - on top is the usual arrangement - and that screen is the machine's display and its keyboard both. Touching is the whole connection: there is no cable and no bus interface, and a screen one block away is a screen the computer cannot see.

The computer's panel says how many screens are touching it, which is the quickest way to find out that the one you placed is a block short.

### Storage
The disk is built in and travels with the block: mine the computer and whatever was installed on it comes along in the item. It holds 32MB by default.

The machine also has a temporary filesystem, which is emptied whenever the computer restarts, and a read only ROM carrying a small shell. That shell is what a computer with an empty disk boots into, so a new machine has a working prompt rather than a blank screen.
