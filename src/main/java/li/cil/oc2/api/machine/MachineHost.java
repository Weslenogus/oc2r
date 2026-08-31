/* SPDX-License-Identifier: MIT */

package li.cil.oc2.api.machine;

import javax.annotation.Nullable;
import java.util.Collection;

/**
 * Everything a {@link Machine} needs from the world it lives in.
 * <p>
 * Kept deliberately free of Minecraft types so the runtime can be exercised, and unit tested,
 * without a server. The block entity and entity backed implementations live in
 * {@code li.cil.oc2.common.machine.host}.
 */
public interface MachineHost {
    /**
     * The components currently reachable from this host, including those contributed by adjacent
     * blocks and installed cards. Queried whenever the machine rescans its bus.
     * <p>
     * Instances must be stable: a component that is still attached has to be returned as the same
     * object every time. The bus diffs by identity, so a host that rebuilt its list each tick
     * would look like every device being unplugged and plugged back in, and a machine would spend
     * its life processing {@code component_removed} and {@code component_added}.
     *
     * @return the attached components.
     */
    Collection<LuaComponent> getComponents();

    /**
     * Total memory available to the machine, in bytes. Determines
     * {@code computer.totalMemory()} and the allocation ceiling enforced on the Lua heap.
     *
     * @return the installed memory in bytes.
     */
    int getMemorySize();

    /**
     * How long the machine may run without yielding before it is stopped with
     * "too long without yielding", in milliseconds.
     * <p>
     * This kills the machine, so it wants to sit well clear of any legitimate workload; it is not
     * the knob for bounding how long a turn takes, which is {@link #getCpuSliceMillis()}.
     *
     * @return the no-yield budget in milliseconds.
     */
    default int getCpuTimeoutMillis() {
        return 5000;
    }

    /**
     * How long one slice of execution may run before the machine is preempted and rescheduled, in
     * milliseconds. Unlike {@link #getCpuTimeoutMillis()} this is not an error: the machine carries
     * on from where it stood.
     * <p>
     * Only a backend that can interrupt running code honours this. Real Lua cannot, because its
     * hook runs inside a C call and Lua will not yield across one, so there a slice runs until the
     * program yields of its own accord.
     *
     * @return the slice length in milliseconds.
     */
    default int getCpuSliceMillis() {
        return 100;
    }

    /**
     * Multiplier on how many {@link Callback#direct() direct} calls a component method may serve
     * per tick before further calls are promoted to the synchronized path.
     * <p>
     * The per method allowances are sized for OpenComputers 1's 80x25 screens. A host driving
     * something larger, or simply running on hardware from this century, raises this so an
     * operating system redrawing itself is not spending its life waiting for ticks.
     *
     * @return the multiplier, at least 1.
     */
    default int getDirectCallsPerTickFactor() {
        return 1;
    }

    /**
     * Energy currently stored, in whatever unit the host's energy system uses.
     *
     * @return the stored energy.
     */
    double getEnergyStored();

    /**
     * Energy capacity, in the same unit as {@link #getEnergyStored()}.
     *
     * @return the energy capacity.
     */
    double getEnergyCapacity();

    /**
     * Energy drawn every tick the machine is running, in the same unit as
     * {@link #getEnergyStored()}. A machine that cannot pay this stops.
     *
     * @return the running cost per tick.
     */
    default double getEnergyPerTick() {
        return 0;
    }

    /**
     * Draws energy, failing without side effects if not enough is stored.
     *
     * @param amount the amount to draw.
     * @return {@code true} if the energy was drawn.
     */
    boolean tryConsumeEnergy(double amount);

    /**
     * Address of the temporary filesystem handed to the machine, or {@code null} if the host
     * provides none. Reported by {@code computer.tmpAddress()}.
     *
     * @return the tmpfs address.
     */
    @Nullable
    default String getTmpAddress() {
        return null;
    }

    /**
     * Plays a note through the host's speaker. Called on the server thread only.
     *
     * @param frequency the frequency in hertz.
     * @param duration  the duration in seconds.
     */
    default void beep(final int frequency, final double duration) {
    }

    /**
     * Called on the server thread when the machine's run state changes, so the host can sync it to
     * clients and update its block state.
     *
     * @param isRunning whether the machine is now running.
     */
    default void onMachineRunStateChanged(final boolean isRunning) {
    }

    /**
     * Called on the server thread when the machine crashed, so the host can surface the error.
     *
     * @param message the error message, or {@code null} if the machine stopped cleanly.
     */
    default void onMachineCrashed(@Nullable final String message) {
    }
}
