/* SPDX-License-Identifier: MIT */

package li.cil.oc2.api.machine;

import javax.annotation.Nullable;

/**
 * What came back from a slice of Lua execution, telling the host how to schedule the machine next.
 */
public sealed interface ExecutionResult {
    /**
     * The machine yielded and wants to be left alone for up to {@code ticks} ticks, or until a
     * signal arrives, whichever happens first. This is what {@code computer.pullSignal} produces.
     *
     * @param ticks the number of ticks to sleep for; clamped by the host.
     */
    record Sleep(int ticks) implements ExecutionResult {
    }

    /**
     * The machine used up its time slice without yielding on its own and was preempted. It should
     * be rescheduled as soon as there is budget again; no signal is consumed.
     */
    record Preempted() implements ExecutionResult {
        public static final Preempted INSTANCE = new Preempted();
    }

    /**
     * The machine requested an indirect component call. The host must run
     * {@link Machine#runSynchronized()} on the server thread before resuming execution.
     */
    record SynchronizedCall() implements ExecutionResult {
        public static final SynchronizedCall INSTANCE = new SynchronizedCall();
    }

    /**
     * The machine finished, either because the kernel returned or because {@code computer.shutdown}
     * was called.
     *
     * @param reboot whether the machine should be started again right away.
     */
    record Shutdown(boolean reboot) implements ExecutionResult {
    }

    /**
     * The machine crashed. The host should stop it and surface the message.
     *
     * @param message the error to display, if any.
     */
    record Error(@Nullable String message) implements ExecutionResult {
    }
}
