/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.lua;

import li.cil.oc2.api.machine.Signal;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;

/**
 * The per machine event queue drained by {@code computer.pullSignal}.
 * <p>
 * Producers are all over the place: the server thread pushing {@code touch} and {@code key_down}
 * from player interaction, the network thread pushing {@code network_message}, the machine thread
 * pushing through {@code computer.pushSignal}. The consumer is only ever the machine thread, and
 * only between time slices. Every method here is therefore synchronized on the queue itself,
 * which is cheap given how rarely it is contended.
 * <p>
 * The queue is bounded the way OpenComputers 1 bounds it. Dropping the newest signal when full is
 * deliberate: a machine that has stopped draining its queue is already wedged, and letting the
 * queue grow without limit would turn that into a memory leak.
 */
public final class SignalQueue {
    /**
     * Maximum number of queued signals, matching the OpenComputers 1 default.
     */
    public static final int MAX_SIZE = 256;

    private final Queue<Signal> queue = new ArrayDeque<>();
    private final int maxSize;

    public SignalQueue() {
        this(MAX_SIZE);
    }

    public SignalQueue(final int maxSize) {
        this.maxSize = maxSize;
    }

    /**
     * Appends a signal.
     *
     * @return {@code true} if the signal was queued; {@code false} if the queue is full.
     */
    public boolean push(final Signal signal) {
        synchronized (queue) {
            if (queue.size() >= maxSize) {
                return false;
            }
            queue.add(signal);
            return true;
        }
    }

    /**
     * Removes and returns the oldest signal.
     *
     * @return the signal, or {@code null} if the queue is empty.
     */
    @Nullable
    public Signal poll() {
        synchronized (queue) {
            return queue.poll();
        }
    }

    public boolean isEmpty() {
        synchronized (queue) {
            return queue.isEmpty();
        }
    }

    public int size() {
        synchronized (queue) {
            return queue.size();
        }
    }

    public void clear() {
        synchronized (queue) {
            queue.clear();
        }
    }

    /**
     * A snapshot of the queued signals, for persisting the machine across a save.
     */
    public List<Signal> toList() {
        synchronized (queue) {
            return new ArrayList<>(queue);
        }
    }

    /**
     * Replaces the queue contents, for restoring a machine from a save.
     */
    public void setAll(final Collection<Signal> signals) {
        synchronized (queue) {
            queue.clear();
            for (final Signal signal : signals) {
                if (queue.size() >= maxSize) {
                    break;
                }
                queue.add(signal);
            }
        }
    }
}
