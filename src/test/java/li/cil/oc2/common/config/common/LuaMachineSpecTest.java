/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.config.common;

import li.cil.oc2.common.Constants;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped defaults for the Lua machines.
 * <p>
 * Worth pinning, because these are the numbers a server owner inherits without touching anything,
 * and two of them have a wrong answer that looks reasonable.
 */
public class LuaMachineSpecTest {
    private static LuaMachineSpec spec() {
        final ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("lua_machine");
        final LuaMachineSpec spec = new LuaMachineSpec(builder);
        builder.pop();
        builder.build();
        return spec;
    }

    @Test
    void shipsAModernHardwareBudget() {
        final LuaMachineSpec spec = spec();

        assertEquals(16 * Constants.MEGABYTE, spec.defaultRam.getDefault());
        assertEquals(64 * Constants.MEGABYTE, spec.maxRam.getDefault());
        // A computer's disk rides in the dropped item's NBT, and is kept out of what that item
        // sends to clients, so this is bounded by what is reasonable to write into a chunk rather
        // than by the network. DiskNbtTest holds the two ends of that together.
        assertEquals(32 * Constants.MEGABYTE, spec.maxDiskSize.getDefault());

        assertTrue(spec.defaultRam.getDefault() <= spec.maxRam.getDefault(),
            "a machine would boot with less memory than the config appears to offer");

        // A Lua computer has no inventory screen and no energy bar. Charging it for its uptime out
        // of a buffer nothing shows you means a placed computer starts, cannot pay for its first
        // tick, and stops before anything reaches the screen - indistinguishable from a block that
        // does not work. Off unless a server turns it on.
        assertEquals(0, spec.energyPerTick.getDefault());
    }

    @Test
    void keepsTheNoYieldBudgetClearOfOrdinaryWork() {
        // The trap this guards. cpuTimeoutMs is not a scheduling interval, it is the point at which
        // the computer is killed with "too long without yielding", so it has to sit well above any
        // legitimate workload. Measured on the native backend: sorting 200,000 numbers takes about
        // 100ms and decoding a megabyte of hex about 200ms, neither with a component call in it to
        // yield through, and a server CPU is several times slower than the machine those were taken
        // on. Anything in the low hundreds here kills computers doing ordinary work.
        //
        // The knob for bounding how long one turn takes is cpuSliceMs, which only reschedules.
        final LuaMachineSpec spec = spec();
        assertTrue(spec.cpuTimeoutMs.getDefault() >= 2000,
            "cpuTimeoutMs default is too tight for ordinary Lua work");
        assertTrue(spec.cpuSliceMs.getDefault() < spec.cpuTimeoutMs.getDefault(),
            "a slice longer than the timeout would kill every machine before it was rescheduled");
    }

    @Test
    void givesTheGpuRoomForAFullRepaint() {
        // A 160x50 repaint is 8000 gpu calls; the stock per method allowance is 256 a tick, sized
        // for OpenComputers 1's 80x25 screens. See MachineLimitsTest for what the factor buys.
        assertTrue(spec().directCallsPerTickFactor.getDefault() >= 4,
            "an operating system would spend over a second of ticks on one frame");
    }
}
