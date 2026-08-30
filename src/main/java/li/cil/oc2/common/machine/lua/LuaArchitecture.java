/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.lua;

import li.cil.oc2.api.machine.ExecutionResult;
import li.cil.oc2.api.machine.Signal;

import javax.annotation.Nullable;

/**
 * The Lua state behind a {@link li.cil.oc2.api.machine.Machine}, kept behind an interface so the
 * scheduler does not depend on which Lua implementation is underneath.
 * <p>
 * {@link LuaJArchitecture} is the pure Java implementation that ships with the mod. A native
 * backend, which could report real memory usage instead of an estimate, would slot in here
 * without the machine noticing.
 */
public interface LuaArchitecture {
    /**
     * Builds the Lua state and loads the kernel. Called from the server thread when the machine
     * boots.
     *
     * @return {@code true} if the state came up; {@code false} if the machine should stay off.
     */
    boolean initialize();

    /**
     * Whether {@link #initialize()} has completed successfully.
     */
    boolean isInitialized();

    /**
     * Tears the Lua state down and releases everything it held.
     */
    void close();

    /**
     * Advances Lua by one time slice. Called on the machine thread.
     *
     * @param signal the signal to deliver, or {@code null} to resume without one. A signal is
     *               only ever passed when the previous slice ended in
     *               {@link ExecutionResult.Sleep}: a machine that was preempted, or that is
     *               waiting on a synchronized call, is in the middle of an expression and has
     *               nowhere to put one.
     * @return how the host should schedule the machine next.
     */
    ExecutionResult runThreaded(@Nullable Signal signal);

    /**
     * Runs the component call the Lua side asked to have executed on the server thread. Called
     * from the server thread after {@link #runThreaded(Signal)} returned
     * {@link ExecutionResult.SynchronizedCall}.
     */
    void runSynchronized();

    /**
     * Whether the next {@link #runThreaded(Signal)} would deliver a signal, that is, whether the
     * machine is sitting in {@code computer.pullSignal} rather than mid-expression.
     */
    boolean isAcceptingSignals();

    /**
     * Total memory available to the machine, in bytes.
     */
    int getMemoryTotal();

    /**
     * Memory currently in use, in bytes.
     */
    int getMemoryUsed();
}
