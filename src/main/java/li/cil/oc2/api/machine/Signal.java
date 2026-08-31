/* SPDX-License-Identifier: MIT */

package li.cil.oc2.api.machine;

import java.util.Arrays;
import java.util.Objects;

/**
 * An event queued for a machine, delivered to Lua by {@code computer.pullSignal}.
 * <p>
 * Argument values are restricted to what can cross the Lua boundary: {@code null},
 * {@link Boolean}, {@link Number}, {@link String}, {@code byte[]} and {@link java.util.Map}.
 * Anything else is rejected up front rather than at delivery time, so a bad push is attributable
 * to whoever made it.
 */
public record Signal(String name, Object... args) {
    public Signal {
        Objects.requireNonNull(name, "name");
        args = args == null ? new Object[0] : args.clone();
        for (final Object arg : args) {
            if (!isSupported(arg)) {
                throw new IllegalArgumentException(
                    "Unsupported signal argument type: " + arg.getClass().getName());
            }
        }
    }

    public static boolean isSupported(final Object value) {
        return value == null
            || value instanceof Boolean
            || value instanceof Number
            || value instanceof String
            || value instanceof byte[]
            || value instanceof java.util.Map<?, ?>;
    }

    @Override
    public Object[] args() {
        return args.clone();
    }

    @Override
    public String toString() {
        return "Signal[" + name + ", " + Arrays.toString(args) + "]";
    }
}
