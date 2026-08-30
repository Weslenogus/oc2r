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
