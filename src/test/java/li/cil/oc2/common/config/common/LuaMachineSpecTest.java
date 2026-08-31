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
        // Bounded by what a client will read off the network rather than by taste: a computer's
        // disk rides in the dropped item's NBT. DiskNbtTest is the measurement behind this figure.
        assertEquals(8 * Constants.MEGABYTE, spec.maxDiskSize.getDefault());

        assertTrue(spec.defaultRam.getDefault() <= spec.maxRam.getDefault(),
            "a machine would boot with less memory than the config appears to offer");
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
