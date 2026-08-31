/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine;

import li.cil.oc2.api.machine.LuaComponent;
import li.cil.oc2.api.machine.MachineHost;
import li.cil.oc2.common.machine.lua.LuaMachine;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A {@link MachineHost} with no world behind it, so the Lua runtime can be tested without a server.
 * <p>
 * This is the payoff for keeping the runtime free of Minecraft types: a machine can be booted,
 * driven and inspected in a plain unit test, and the parts that are easiest to get subtly wrong,
 * the yield protocol and the screen buffer, are the parts that get covered.
 */
public final class TestMachineHost implements MachineHost {
    private final List<LuaComponent> components = new ArrayList<>();
    private final List<String> crashes = new ArrayList<>();
    private final List<String> beeps = new ArrayList<>();

    private double energy = 5000;
    private int memorySize = 2 * 1024 * 1024;
    private int cpuTimeoutMillis = 5000;
    private int cpuSliceMillis = 100;
    private int directCallsPerTickFactor = 1;

    public void add(final LuaComponent component) {
        components.add(component);
    }

    public List<String> getCrashes() {
        return crashes;
    }

    public List<String> getBeeps() {
        return beeps;
    }

    public void setEnergy(final double value) {
        energy = value;
    }

    @Override
    public Collection<LuaComponent> getComponents() {
        return components;
    }

    @Override
    public int getMemorySize() {
        return memorySize;
    }

    public void setMemorySize(final int value) {
        memorySize = value;
    }

    @Override
    public int getCpuTimeoutMillis() {
        return cpuTimeoutMillis;
    }

    public void setCpuTimeoutMillis(final int value) {
        cpuTimeoutMillis = value;
    }

    @Override
    public int getCpuSliceMillis() {
        return cpuSliceMillis;
    }

    public void setCpuSliceMillis(final int value) {
        cpuSliceMillis = value;
    }

    @Override
    public int getDirectCallsPerTickFactor() {
        return directCallsPerTickFactor;
    }

    public void setDirectCallsPerTickFactor(final int value) {
        directCallsPerTickFactor = value;
    }

    @Override
    public double getEnergyStored() {
        return energy;
    }

    @Override
    public double getEnergyCapacity() {
        return 5000;
    }

    @Override
    public boolean tryConsumeEnergy(final double amount) {
        if (energy < amount) {
            return false;
        }
        energy -= amount;
        return true;
    }

    @Nullable
    private String tmpAddress;

    public void setTmpAddress(final String value) {
        tmpAddress = value;
    }

    @Override
    @Nullable
    public String getTmpAddress() {
        return tmpAddress;
    }

    @Override
    public void beep(final int frequency, final double duration) {
        beeps.add(frequency + "Hz");
    }

    @Override
    public void onMachineCrashed(@Nullable final String message) {
        crashes.add(String.valueOf(message));
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Ticks a machine until it stops or the budget runs out, waiting for each time slice so the
     * test sees a deterministic sequence rather than racing the worker thread.
     *
     * @param machine  the machine to drive.
     * @param maxTicks how many ticks to allow before giving up.
     * @return the number of ticks actually run.
     */
    public static int run(final LuaMachine machine, final int maxTicks) throws InterruptedException {
        return run(machine, maxTicks, () -> {
        });
    }

    /**
     * As {@link #run(LuaMachine, int)}, but calls {@code betweenTicks} once the machine has settled
     * after each tick, which is where a test injects input.
     */
    public static int run(final LuaMachine machine, final int maxTicks, final Runnable betweenTicks)
        throws InterruptedException {
        int ticks = 0;
        for (; ticks < maxTicks && machine.isRunning(); ticks++) {
            machine.tick();
            // Wait out the worker slice, so the test looks at the machine between slices rather
            // than racing it.
            for (int i = 0; i < 3000 && machine.isSliceInFlight(); i++) {
                Thread.sleep(1);
            }
            betweenTicks.run();
        }
        return ticks;
    }
}
