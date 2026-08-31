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
     * Multiplier on every method's declared allowance.
     * <p>
     * The allowances themselves come from OpenComputers 1, where a screen was 80x25. Repainting a
     * 160x50 one a cell at a time is 8000 gpu calls, which at the stock 256 per tick would take an
     * operating system two thirds of a second of waiting for ticks to get one frame out. Direct
     * calls never touch the server thread, so the host is free to say that its hardware can take
     * rather more of them than a 2015 mod assumed.
     */
    private final int factor;

    /**
     * @param factor the host's multiplier; anything below one is treated as one, so a
     *               misconfigured server gets the stock allowances rather than a machine that
     *               cannot call anything.
     */
    public DirectCallBudget(final int factor) {
        this.factor = Math.max(1, factor);
    }

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
        // Saturating, so a host with a large factor and a method with a large allowance cannot
        // wrap into a negative limit and refuse every call.
        final long limit = (long) method.getLimit() * factor;
        return counter.incrementAndGet() <= limit;
    }

    /**
     * Refills every allowance. Called once per tick from the server thread.
     */
    public void reset() {
        counters.clear();
    }
}
