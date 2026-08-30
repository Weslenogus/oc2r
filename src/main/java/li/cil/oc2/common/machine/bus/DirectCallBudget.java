/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.bus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per tick allowance for {@link li.cil.oc2.api.machine.Callback#direct() direct} component calls.
 * <p>
 * Direct calls skip the server thread entirely, which makes them cheap for the caller but means a
 * tight Lua loop could otherwise hammer a component without ever yielding. Once a method has used
 * up its allowance for the current tick, further calls are promoted to indirect calls, which cost
 * the caller a tick and hand the server room to breathe.
 * <p>
 * Counters are incremented from the machine thread and cleared from the server thread.
 */
public final class DirectCallBudget {
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * Tries to spend one direct call for the given component method.
     *
     * @param address the address of the component being called.
     * @param method  the method being called.
     * @return {@code true} if the call may proceed directly; {@code false} if it must be promoted
     * to a synchronized call.
     */
    public boolean tryConsume(final String address, final CallbackMethod method) {
        if (!method.isDirect()) {
            return false;
        }
        final AtomicInteger counter = counters.computeIfAbsent(
            address + '\0' + method.getName(), ignored -> new AtomicInteger());
        return counter.incrementAndGet() <= method.getLimit();
    }

    /**
     * Refills every allowance. Called once per tick from the server thread.
     */
    public void reset() {
        counters.clear();
    }
}
