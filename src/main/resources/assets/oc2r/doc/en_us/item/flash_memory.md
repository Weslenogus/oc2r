# Flash Memory
![Not that Flash](item:oc2r:flash_memory)

Flash memory contains the initial code loaded into a [computer's](../block/computer.md) memory upon boot. This typically at least includes the firmware for the system. The default firmware provided with new computers and [robots](robot.md) also contains a Linux kernel. This kernel expects a root filesystem to be present on the first [hard drive](hard_drive.md) in the system.

### Two kinds
There are two flash memory items, and the name in brackets tells them apart:

- **Flash Memory (12MB)** is blank. The size in brackets is its capacity. A computer will not boot from it until something has been written onto it with a [Flash Memory Flasher](../block/flash_memory_flasher.md).
- **Flash Memory (Minux)** already has a firmware on it, and the name in brackets is which one. Minux boots a computer from its hard drive, and is the one to use unless you are writing your own firmware.

A [Lua Computer](../block/lua_computer.md) needs neither: its firmware is part of the block.
