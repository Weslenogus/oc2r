/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.lua;

/**
 * The vocabulary a machine uses to tell Java why it stopped.
 * <p>
 * A system yield passes one of these as its first value, which is what distinguishes it from an
 * ordinary {@code coroutine.yield} in a program: the sandbox's coroutine library prepends
 * {@code nil} to every user yield, so a non-nil first value can only have come from the kernel.
 * <p>
 * {@link #SLEEP}, {@link #SHUTDOWN} and {@link #SYNCHRONIZED_CALL} are produced by
 * {@code machine.lua} and shared with it through the native table. {@link #PREEMPT} and
 * {@link #KILL} come from a backend's own deadline hook, so a backend that cannot preempt simply
 * never produces them.
 */
public final class SystemYield {
    /**
     * {@code computer.pullSignal}: sleep for the payload's seconds, or until a signal arrives.
     */
    public static final int SLEEP = 0;

    /**
     * {@code computer.shutdown}: stop, and restart if the payload is true.
     */
    public static final int SHUTDOWN = 1;

    /**
     * A component call Java declined to run on the machine thread. The server thread runs it and
     * the results come back as the yield's return values.
     */
    public static final int SYNCHRONIZED_CALL = 2;

    /**
     * The machine used up its time slice and was stopped where it stood. No signal is consumed;
     * it simply carries on when it is next scheduled.
     */
    public static final int PREEMPT = 3;

    /**
     * The machine used up its no-yield budget and is being stopped for good.
     */
    public static final int KILL = 4;

    private SystemYield() {
    }
}
