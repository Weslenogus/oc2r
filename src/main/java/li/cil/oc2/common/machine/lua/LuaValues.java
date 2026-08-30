/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.lua;

import li.cil.oc2.api.machine.Value;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Conversion between the plain Java values components deal in and their Lua representation.
 * <p>
 * Lua strings are byte strings, and this matters more than it looks: filesystem reads have to come
 * back byte for byte or every binary file on a floppy is corrupted, while {@code gpu.set} wants
 * text. The rule here is that {@code byte[]} crosses the boundary verbatim and {@link String} is
 * encoded as UTF-8, which is exactly the split OpenComputers 1 uses.
 */
public final class LuaValues {
    /**
     * How deep a table may nest before conversion gives up. Guards against a pathological, or
     * simply hostile, structure eating the machine thread's stack.
     */
    private static final int MAX_DEPTH = 16;

    private LuaValues() {
    }

    /**
     * Converts a Java value produced by a component into its Lua representation.
     *
     * @param value        the value to convert.
     * @param valueWrapper turns a {@link Value} into the table Lua sees; supplied by the machine
     *                     because binding callbacks needs machine state.
     * @return the Lua value.
     */
    public static LuaValue toLua(@Nullable final Object value, final Function<Value, LuaValue> valueWrapper) {
        return toLua(value, valueWrapper, 0);
    }

    private static LuaValue toLua(@Nullable final Object value, final Function<Value, LuaValue> valueWrapper, final int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Value nests too deeply to convert to Lua.");
        }

        if (value == null) {
            return LuaValue.NIL;
        }
        if (value instanceof final Boolean b) {
            return LuaValue.valueOf(b);
        }
        if (value instanceof final Character c) {
            return LuaValue.valueOf(String.valueOf(c));
        }
        if (value instanceof final Number n) {
            // Lua 5.2 has a single number type, so everything becomes a double unless it is an
            // integer that survives the round trip, which keeps tostring() from printing "1.0".
            if (n instanceof Integer || n instanceof Short || n instanceof Byte) {
                return LuaValue.valueOf(n.intValue());
            }
            final double d = n.doubleValue();
            return d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE
                ? LuaValue.valueOf((int) d)
                : LuaValue.valueOf(d);
        }
        if (value instanceof final String s) {
            return LuaValue.valueOf(s.getBytes(StandardCharsets.UTF_8));
        }
        if (value instanceof final byte[] bytes) {
            return LuaString.valueOf(bytes);
        }
        if (value instanceof final Value v) {
            return valueWrapper.apply(v);
        }
        if (value instanceof final Enum<?> e) {
            return LuaValue.valueOf(e.name());
        }
        if (value instanceof final Map<?, ?> map) {
            final LuaTable table = new LuaTable();
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                final LuaValue key = toLua(entry.getKey(), valueWrapper, depth + 1);
                if (!key.isnil()) {
                    table.set(key, toLua(entry.getValue(), valueWrapper, depth + 1));
                }
            }
            return table;
        }
        if (value instanceof final Object[] array) {
            final LuaTable table = new LuaTable();
            for (int i = 0; i < array.length; i++) {
                table.set(i + 1, toLua(array[i], valueWrapper, depth + 1));
            }
            return table;
        }
        if (value instanceof final Collection<?> collection) {
            final LuaTable table = new LuaTable();
            int i = 1;
            for (final Object item : collection) {
                table.set(i++, toLua(item, valueWrapper, depth + 1));
            }
            return table;
        }

        throw new IllegalArgumentException("Cannot convert [" + value.getClass().getName() + "] to a Lua value.");
    }

    /**
     * Packs a component's multi-return array into a {@link Varargs}.
     */
    public static Varargs toVarargs(@Nullable final Object[] values, final Function<Value, LuaValue> valueWrapper) {
        if (values == null || values.length == 0) {
            return LuaValue.NONE;
        }
        final LuaValue[] converted = new LuaValue[values.length];
        for (int i = 0; i < values.length; i++) {
            converted[i] = toLua(values[i], valueWrapper, 0);
        }
        return LuaValue.varargsOf(converted);
    }

    /**
     * Converts a Lua value into the plain Java representation components and signals use.
     * <p>
     * Strings come back as {@link String}, decoded as UTF-8. Callers that need the exact bytes
     * must use {@link #toByteArray(LuaValue)} instead.
     */
    @Nullable
    public static Object toJava(final LuaValue value) {
        return toJava(value, new IdentityHashMap<>(), 0);
    }

    @Nullable
    private static Object toJava(final LuaValue value, final Map<LuaValue, Object> seen, final int depth) {
        // Switch on the concrete type rather than the is* predicates: in LuaJ those model Lua's
        // coercion rules, so a numeric string answers isnumber() and every number answers
        // isstring(), which would make the order of the checks decide the result.
        if (value == null) {
            return null;
        }
        switch (value.type()) {
            case LuaValue.TNIL:
                return null;
            case LuaValue.TBOOLEAN:
                return value.toboolean();
            case LuaValue.TNUMBER:
                return value.todouble();
            case LuaValue.TSTRING:
                return toString(value);
            case LuaValue.TTABLE: {
                if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("Table nests too deeply to convert from Lua.");
            }
                final Object existing = seen.get(value);
                if (existing != null) {
                    return existing;
                }

                final Map<Object, Object> result = new LinkedHashMap<>();
                seen.put(value, result);

                final LuaTable table = value.checktable();
                LuaValue key = LuaValue.NIL;
                while (true) {
                    final Varargs next = table.next(key);
                    key = next.arg1();
                    if (key.isnil()) {
                        break;
                    }
                    final Object javaKey = toJava(key, seen, depth + 1);
                    if (javaKey != null) {
                        result.put(javaKey, toJava(next.arg(2), seen, depth + 1));
                    }
                }
                return result;
            }
            default:
                // Functions, threads and userdata have no meaning outside the Lua state; refusing
                // them beats handing components something they cannot use.
                throw new IllegalArgumentException(
                    "Cannot convert a Lua " + value.typename() + " to a Java value.");
        }
    }

    /**
     * Decodes a Lua string as UTF-8. Numbers are accepted the way Lua's own coercion does.
     */
    public static String toString(final LuaValue value) {
        return new String(toByteArray(value), StandardCharsets.UTF_8);
    }

    /**
     * The exact bytes of a Lua string.
     */
    public static byte[] toByteArray(final LuaValue value) {
        final LuaString string = value.checkstring();
        final byte[] result = new byte[string.m_length];
        System.arraycopy(string.m_bytes, string.m_offset, result, 0, string.m_length);
        return result;
    }

    /**
     * Converts a whole varargs list, used when a machine pushes a signal from Lua.
     */
    public static Object[] toJavaArray(final Varargs args, final int firstIndex) {
        final List<Object> result = new ArrayList<>(Math.max(0, args.narg() - firstIndex + 1));
        for (int i = firstIndex; i <= args.narg(); i++) {
            result.add(toJava(args.arg(i)));
        }
        return result.toArray();
    }
}
