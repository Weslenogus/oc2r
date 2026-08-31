/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.lua;

import li.cil.oc2.api.machine.Arguments;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * {@link Arguments} over a snapshot of a call's arguments.
 * <p>
 * The pure Java backend can hold on to the argument list itself and convert lazily. A real Lua
 * state cannot: the values live on a stack that is gone the moment the call returns, and a deferred
 * component call outlives its call by a yield and a hop to the server thread. So the values are
 * copied out up front, with strings kept as bytes so that whether an argument is text or data
 * stays the component's decision.
 */
public final class NativeArguments implements Arguments {
    private final Object[] values;
    private final int offset;

    /**
     * @param values the snapshot, as {@link NativeLuaValues#snapshot}produces it.
     * @param offset how many leading values to hide, used to drop the address and method name that
     *               {@code component.invoke} passes ahead of the real arguments.
     */
    public NativeArguments(final Object[] values, final int offset) {
        this.values = values;
        this.offset = offset;
    }

    /**
     * The same arguments with a further {@code count} leading values hidden.
     */
    public NativeArguments skip(final int count) {
        return new NativeArguments(values, offset + count);
    }

    @Override
    public int count() {
        return Math.max(0, values.length - offset);
    }

    @Nullable
    private Object raw(final int index) {
        final int actual = index + offset;
        return index < 0 || actual >= values.length ? null : values[actual];
    }

    @Override
    @Nullable
    public Object get(final int index) {
        final Object value = raw(index);
        // Byte strings are the storage form, not the reported one: everything outside this class
        // sees the same String the pure Java backend would hand it.
        return value instanceof final byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value;
    }

    @Override
    public Object[] toArray() {
        final Object[] result = new Object[count()];
        for (int i = 0; i < result.length; i++) {
            result[i] = get(i);
        }
        return result;
    }

    @Override
    public boolean isDefined(final int index) {
        return raw(index) != null;
    }

    @Override
    public boolean isBoolean(final int index) {
        return raw(index) instanceof Boolean;
    }

    @Override
    public boolean isNumber(final int index) {
        final Object value = raw(index);
        // Lua coerces a numeric string wherever a number is wanted, and programs lean on it.
        return value instanceof Double
            || (value instanceof final byte[] bytes && parse(bytes) != null);
    }

    @Override
    public boolean isString(final int index) {
        final Object value = raw(index);
        return value instanceof byte[] || value instanceof Double;
    }

    @Override
    public boolean isTable(final int index) {
        return raw(index) instanceof Map;
    }

    @Override
    public Object checkAny(final int index) {
        final Object value = get(index);
        if (value == null) {
            throw badArgument(index, "value expected");
        }
        return value;
    }

    @Override
    public boolean checkBoolean(final int index) {
        if (!(raw(index) instanceof final Boolean b)) {
            throw badArgument(index, "boolean expected, got " + typeName(index));
        }
        return b;
    }

    @Override
    public double checkDouble(final int index) {
        final Object value = raw(index);
        if (value instanceof final Double d) {
            return d;
        }
        if (value instanceof final byte[] bytes) {
            final Double parsed = parse(bytes);
            if (parsed != null) {
                return parsed;
            }
        }
        throw badArgument(index, "number expected, got " + typeName(index));
    }

    @Override
    public int checkInteger(final int index) {
        final double value = checkDouble(index);
        if (Double.isNaN(value)) {
            throw badArgument(index, "number has no integer representation");
        }
        // Lua truncates towards zero when a number is used where an integer is wanted, and OpenOS
        // leans on that in places, for instance when passing computed coordinates.
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (long) value));
    }

    @Override
    public long checkLong(final int index) {
        final double value = checkDouble(index);
        if (Double.isNaN(value)) {
            throw badArgument(index, "number has no integer representation");
        }
        return (long) value;
    }

    @Override
    public String checkString(final int index) {
        return new String(checkByteArray(index), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] checkByteArray(final int index) {
        final Object value = raw(index);
        if (value instanceof final byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof final Double d) {
            return NativeLuaValues.formatNumber(d).getBytes(StandardCharsets.UTF_8);
        }
        throw badArgument(index, "string expected, got " + typeName(index));
    }

    @Override
    public Map<?, ?> checkTable(final int index) {
        if (!(raw(index) instanceof final Map<?, ?> table)) {
            throw badArgument(index, "table expected, got " + typeName(index));
        }
        return table;
    }

    @Override
    public boolean optBoolean(final int index, final boolean defaultValue) {
        return isDefined(index) ? checkBoolean(index) : defaultValue;
    }

    @Override
    public double optDouble(final int index, final double defaultValue) {
        return isDefined(index) ? checkDouble(index) : defaultValue;
    }

    @Override
    public int optInteger(final int index, final int defaultValue) {
        return isDefined(index) ? checkInteger(index) : defaultValue;
    }

    @Override
    public long optLong(final int index, final long defaultValue) {
        return isDefined(index) ? checkLong(index) : defaultValue;
    }

    @Override
    @Nullable
    public String optString(final int index, @Nullable final String defaultValue) {
        return isDefined(index) ? checkString(index) : defaultValue;
    }

    @Override
    @Nullable
    public byte[] optByteArray(final int index, @Nullable final byte[] defaultValue) {
        return isDefined(index) ? checkByteArray(index) : defaultValue;
    }

    @Override
    @Nullable
    public Map<?, ?> optTable(final int index, @Nullable final Map<?, ?> defaultValue) {
        return isDefined(index) ? checkTable(index) : defaultValue;
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Reads a string as a number the way Lua does, decimal or hexadecimal, or answers null.
     */
    @Nullable
    private static Double parse(final byte[] bytes) {
        final String text = new String(bytes, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            final String digits = text.startsWith("-") || text.startsWith("+")
                ? text.substring(1) : text;
            if (digits.length() > 2 && digits.charAt(0) == '0'
                && (digits.charAt(1) == 'x' || digits.charAt(1) == 'X')) {
                final double magnitude = Long.parseLong(digits.substring(2), 16);
                return text.startsWith("-") ? -magnitude : magnitude;
            }
            // Java would also accept "1d" and "0x1p3"; Lua accepts neither, and letting them
            // through would make a component see a number where the program wrote a string.
            for (int i = 0; i < text.length(); i++) {
                final char c = text.charAt(i);
                if (!(Character.isDigit(c) || c == '.' || c == '-' || c == '+'
                    || c == 'e' || c == 'E')) {
                    return null;
                }
            }
            return Double.parseDouble(text);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private String typeName(final int index) {
        final Object value = raw(index);
        if (value == null) {
            return "no value";
        }
        if (value instanceof byte[]) {
            return "string";
        }
        if (value instanceof Double) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Map) {
            return "table";
        }
        return value.getClass().getSimpleName();
    }

    private IllegalArgumentException badArgument(final int index, final String message) {
        // Report the index the way Lua counts, so the message lines up with the call site.
        return new IllegalArgumentException("bad argument #" + (index + 1) + " (" + message + ")");
    }
}
