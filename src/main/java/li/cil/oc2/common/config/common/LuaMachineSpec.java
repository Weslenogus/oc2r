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
            "There is a real ceiling above this one. A computer's disk travels in the dropped item's",
            "NBT so that mining one keeps what was installed on it, and Minecraft refuses to read a",
            "tag over 2MB off the network: a disk bigger than that, once actually full, cannot be",
            "mined without disconnecting whoever picks it up. Contents are compressed, and measured",
            "against the real MineOS sources that is about 4.7 to 1, so a full disk of this size",
            "packs to a little under the limit. Raising it is safe for a disk that stays mostly",
            "empty or holds text; a full one of random or already compressed data is not, and the",
            "log says so when it happens."
        ).defineInRange("maxDiskSize", 8 * Constants.MEGABYTE, 64 * 1024, Integer.MAX_VALUE);

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
    }

    public void loadValues() {
        Config.luaDefaultRam = defaultRam.get();
        Config.luaMaxRam = maxRam.get();
        Config.luaCpuTimeoutMs = cpuTimeoutMs.get();
        Config.luaCpuSliceMs = cpuSliceMs.get();
        Config.luaMaxDiskSize = maxDiskSize.get();
        Config.luaDirectCallsPerTickFactor = directCallsPerTickFactor.get();
    }
}
