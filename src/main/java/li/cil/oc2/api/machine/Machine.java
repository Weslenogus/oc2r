/* SPDX-License-Identifier: MIT */

package li.cil.oc2.api.machine;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Optional;

/**
 * A running OpenComputers 1 compatible Lua machine.
 * <p>
 * The execution model splits into two halves. {@link #runThreaded()} advances Lua on a worker
 * thread and must never touch level state; {@link #runSynchronized()} runs on the server thread
 * and services the indirect component calls the Lua side asked for. The host drives both from
 * {@link #tick()}, which is itself a server thread call.
 */
public interface Machine {
    /**
     * The machine's own address, which doubles as the address of its {@code computer} component.
     *
     * @return the machine address.
     */
    String getAddress();

    /**
     * The host this machine belongs to.
     *
     * @return the machine host.
     */
    MachineHost getHost();

    /**
     * Whether the machine is powered on. Stays {@code true} while it sleeps in
     * {@code computer.pullSignal}.
     *
     * @return {@code true} if the machine is on.
     */
    boolean isRunning();

    /**
     * Whether execution is suspended, for example because the chunk is not ticking.
     *
     * @return {@code true} if the machine is paused.
     */
    boolean isPaused();

    /**
     * Boots the machine. Does nothing if it is already running.
     *
     * @return {@code true} if the machine transitioned to running.
     */
    boolean start();

    /**
     * Powers the machine off, discarding its Lua state.
     *
     * @return {@code true} if the machine transitioned to stopped.
     */
    boolean stop();

    /**
     * Queues a signal for delivery to {@code computer.pullSignal}. Safe to call from any thread.
     *
     * @param name the name of the signal.
     * @param args the signal arguments.
     * @return {@code true} if the signal was queued; {@code false} if the machine is off or its
     * queue is full.
     */
    boolean signal(String name, Object... args);

    /**
     * Seconds of world time the machine has been running for, as reported by
     * {@code computer.uptime()}.
     *
     * @return the uptime in seconds.
     */
    double getUptime();

    /**
     * The last error the machine crashed with, if any.
     *
     * @return the crash message.
     */
    @Nullable
    String getLastError();

    /**
     * The components currently on this machine's bus.
     *
     * @return the attached components.
     */
    Collection<LuaComponent> getComponents();

    /**
     * Looks up an attached component by address.
     *
     * @param address the address to look up.
     * @return the component, if one is attached under that address.
     */
    Optional<LuaComponent> getComponent(@Nullable String address);

    /**
     * Advances the machine by one server tick. Must be called from the server thread. Rescans the
     * component bus, resets per tick direct call budgets, services pending synchronized calls and
     * schedules the next slice of Lua execution.
     */
    void tick();

    /**
     * Runs the pending indirect component call. Must be called from the server thread, and only
     * when the previous {@link #runThreaded()} returned
     * {@link ExecutionResult.SynchronizedCall}.
     */
    void runSynchronized();

    /**
     * Advances Lua execution by one time slice. Runs on a worker thread and must not touch level
     * state.
     *
     * @return how the host should schedule the machine next.
     */
    ExecutionResult runThreaded();
}
