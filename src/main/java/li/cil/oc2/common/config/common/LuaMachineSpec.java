package li.cil.oc2.common.config.common;

import li.cil.oc2.common.Constants;
import li.cil.oc2.common.config.Config;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Limits for the OpenComputers 1 compatible Lua machines, which are a separate thing from the
 * RISC-V virtual machines above and are budgeted separately.
 * <p>
 * A Lua machine has no upgrade path in the world: the block is a fixed configuration, so these are
 * both the defaults and, in practice, the specification of the hardware. The numbers are sized for
 * a machine that has to run a graphical operating system, which is a much heavier job than the
 * 2MB an OpenComputers 1 tier 3 computer had to do it in.
 */
public class LuaMachineSpec {
    public final ForgeConfigSpec.IntValue defaultRam;
    public final ForgeConfigSpec.IntValue maxRam;
    public final ForgeConfigSpec.IntValue cpuTimeoutMs;
    public final ForgeConfigSpec.IntValue cpuSliceMs;
    public final ForgeConfigSpec.IntValue maxDiskSize;
    public final ForgeConfigSpec.IntValue directCallsPerTickFactor;
    public final ForgeConfigSpec.IntValue energyPerTick;

    LuaMachineSpec(ForgeConfigSpec.Builder builder) {
        defaultRam = builder.comment(
            "Memory installed in a Lua computer, in bytes. This is what computer.totalMemory()",
            "reports and, on the native Lua backend, the ceiling actually enforced on the heap.",
            "MineOS takes roughly 1.5MB just to compile its libraries, before any of them has run,",
            "so the OpenComputers 1 maximum of 3.5MB leaves very little room to work in.",
            "Clamped to maxRam below."
        ).defineInRange("defaultRam", 16 * Constants.MEGABYTE, 64 * 1024, Integer.MAX_VALUE);

        maxRam = builder.comment(
            "Ceiling on the above, in bytes. Nothing raises a Lua machine's memory past this."
        ).defineInRange("maxRam", 64 * Constants.MEGABYTE, 64 * 1024, Integer.MAX_VALUE);

        cpuTimeoutMs = builder.comment(
            "How long a Lua machine may run without yielding before it is stopped with",
            "'too long without yielding', in milliseconds.",
            "",
            "This is an error that kills the computer, not a scheduling interval, so it wants to be",
            "well clear of any legitimate workload. Measured on the native backend: sorting 200,000",
            "numbers takes about 100ms, decoding a megabyte of hex about 200ms, and a slower server",
            "CPU will be several times that. Setting this to a couple of hundred milliseconds will",
            "kill computers doing ordinary work. It costs nothing to leave generous: slices run on a",
            "worker thread, so a machine that overruns delays only itself.",
            "",
            "To bound how long a machine may hold the server thread, use cpuSliceMs instead."
        ).defineInRange("cpuTimeoutMs", 5000, 50, 120_000);

        cpuSliceMs = builder.comment(
            "How long one slice of execution may run before the machine is preempted and rescheduled,",
            "in milliseconds. Unlike cpuTimeoutMs this is not an error; the machine carries on from",
            "where it stood on its next turn.",
            "",
            "Only the pure Java backend can honour this. Real Lua runs its hook inside a C call and",
            "will not yield across one, so on the native backend a slice runs until the program yields",
            "of its own accord, which is what OpenComputers 1 does as well."
        ).defineInRange("cpuSliceMs", 100, 1, 10_000);

        maxDiskSize = builder.comment(
            "Capacity of the disk built into a Lua computer, in bytes. Storage is allocated as it is",
            "used rather than up front, so this is a ceiling and not a cost.",
            "",
            "The disk travels in the dropped item's NBT, so that mining a computer keeps what was",
            "installed on it, and it is written into the chunk on every save. Both are compressed;",
            "measured against the real MineOS sources that is about 4.7 to 1. What this really costs,",
            "then, is chunk size and save time for computers that are actually full, which is why it",
            "is a limit at all rather than being left open.",
            "",
            "The contents are kept off the network deliberately - a client has no use for a disk it",
            "cannot read - so the two megabyte cap Minecraft puts on a tag received over the wire",
            "does not apply here."
        ).defineInRange("maxDiskSize", 32 * Constants.MEGABYTE, 64 * 1024, Integer.MAX_VALUE);

        directCallsPerTickFactor = builder.comment(
            "Multiplier on how many direct component calls a method may serve per tick before further",
            "calls are promoted to the synchronized path, which costs the caller a tick each.",
            "",
            "The stock allowances come from OpenComputers 1, where a screen was 80x25. Repainting a",
            "160x50 screen a cell at a time is 8000 gpu calls, so at a factor of 1 an operating system",
            "redrawing itself spends most of a second waiting on ticks instead of drawing. Direct calls",
            "do not touch the server thread, so raising this costs the calling machine's own worker",
            "thread and nothing else; runaway loops are caught by cpuTimeoutMs, not by this."
        ).defineInRange("directCallsPerTickFactor", 8, 1, 1024);

        energyPerTick = builder.comment(
            "Energy a running Lua computer draws per tick. Zero means it needs no power at all,",
            "which is the default.",
            "",
            "Unlike the RISC-V computer, a Lua computer has no inventory screen and no energy bar:",
            "it is a block you place and use. Charging one for ten seconds of uptime, from a buffer",
            "nothing on the block shows you, is a puzzle with no clue attached - the machine simply",
            "stops the tick after it starts and the screen stays dark. So the cost is off unless a",
            "server turns it on. The block still accepts energy from any side, so a charger or a",
            "cable works the moment it is worth anything."
        ).defineInRange("energyPerTick", 0, 0, 4096);
    }

    public void loadValues() {
        Config.luaDefaultRam = defaultRam.get();
        Config.luaMaxRam = maxRam.get();
        Config.luaCpuTimeoutMs = cpuTimeoutMs.get();
        Config.luaCpuSliceMs = cpuSliceMs.get();
        Config.luaMaxDiskSize = maxDiskSize.get();
        Config.luaDirectCallsPerTickFactor = directCallsPerTickFactor.get();
        Config.luaComputerEnergyPerTick = energyPerTick.get();
    }
}
