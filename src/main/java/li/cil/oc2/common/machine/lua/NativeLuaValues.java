/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.lua;

import li.cil.oc2.api.machine.Value;
import party.iroiro.luajava.Lua;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Moves values between Java and a real Lua state, byte for byte.
 * <p>
 * Lua strings are byte strings, and the JNI bridge underneath is not: pushing a Java string hands
 * JNI a UTF-16 sequence, which comes out the other side as modified UTF-8. That is fine for text
 * and quietly ruinous for anything else, because a {@code filesystem.read} of a compiled image or
 * a saved screenshot would arrive in Lua with every byte above 0x7F re-encoded as two.
 * <p>
 * So there are two paths in. Bytes that are plain ASCII are pushed directly, because modified UTF-8
 * leaves 0x01 through 0x7F alone and that covers Lua source, component addresses and method names,
 * which is nearly everything. Anything else is compiled: the bytes are written out as a
 * {@code return "\ddd\ddd..."} chunk and loaded, which is the only route to an exact string when
 * the binding offers no {@code lua_pushlstring}. It costs about a millisecond per 64 KiB, and only
 * for data that actually needs it.
 * <p>
 * Coming back out is simpler, because {@code luaJ_tobuffer} hands over the string's bytes as they
 * are.
 * <p>
 * An instance owns a scratch buffer and is therefore not thread safe. One per machine, used only
 * from the machine thread, which is where every conversion happens.
 */
public final class NativeLuaValues {
    /**
     * Builds the table Lua sees for a {@link Value}. Supplied by the architecture, because binding
     * callbacks needs machine state.
     */
    @FunctionalInterface
    public interface ValuePusher {
        void push(Lua state, Value value);
    }

    /**
     * How deep a table may nest before conversion gives up, in either direction.
     */
    private static final int MAX_DEPTH = 16;

    private static final byte[] CHUNK_PREFIX = "return \"".getBytes(StandardCharsets.ISO_8859_1);

    private final ValuePusher valuePusher;

    /**
     * Source buffer for the escaping path, grown as needed and reused. It has to be direct: the
     * binding's load takes a raw address, and rejects anything else.
     */
    private ByteBuffer scratch = ByteBuffer.allocateDirect(1024).order(ByteOrder.nativeOrder());

    public NativeLuaValues(final ValuePusher valuePusher) {
        this.valuePusher = valuePusher;
    }

    ///////////////////////////////////////////////////////////////////
    // Java to Lua

    /**
     * Pushes a Java value produced by a component, leaving exactly one value on the stack.
     */
    public void push(final Lua state, @Nullable final Object value) {
        push(state, value, 0);
    }

    private void push(final Lua state, @Nullable final Object value, final int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Value nests too deeply to convert to Lua.");
        }

        if (value == null) {
            state.pushNil();
        } else if (value instanceof final Boolean b) {
            state.push(b.booleanValue());
        } else if (value instanceof final Character c) {
            pushString(state, String.valueOf(c));
        } else if (value instanceof final Number n) {
            pushNumber(state, n);
        } else if (value instanceof final String s) {
            pushString(state, s);
        } else if (value instanceof final byte[] bytes) {
            pushBytes(state, bytes);
        } else if (value instanceof final Value v) {
            valuePusher.push(state, v);
        } else if (value instanceof final Enum<?> e) {
            pushString(state, e.name());
        } else if (value instanceof final Map<?, ?> map) {
            state.checkStack(3);
            state.createTable(0, map.size());
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                push(state, entry.getKey(), depth + 1);
                if (state.isNil(-1)) {
                    // A nil key is not an error in Java and cannot exist in Lua, so drop the pair
                    // rather than raising out of the middle of a half built table.
                    state.pop(1);
                    continue;
                }
                push(state, entry.getValue(), depth + 1);
                state.rawSet(-3);
            }
        } else if (value instanceof final Object[] array) {
            state.checkStack(2);
            state.createTable(array.length, 0);
            for (int i = 0; i < array.length; i++) {
                push(state, array[i], depth + 1);
                state.rawSetI(-2, i + 1);
            }
        } else if (value instanceof final Collection<?> collection) {
            state.checkStack(2);
            state.createTable(collection.size(), 0);
            int i = 1;
            for (final Object item : collection) {
                push(state, item, depth + 1);
                state.rawSetI(-2, i++);
            }
        } else {
            throw new IllegalArgumentException(
                "Cannot convert [" + value.getClass().getName() + "] to a Lua value.");
        }
    }

    /**
     * Pushes a number, preferring Lua 5.3's integer subtype wherever the value is exactly an
     * integer.
     * <p>
     * This mirrors what the pure Java backend does with the same value, which is what keeps a
     * signal that has been through a save and reload, where every number comes back as a double,
     * identical to the one a component pushed in the first place.
     */
    private static void pushNumber(final Lua state, final Number value) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte
            || value instanceof Long) {
            state.push(value.longValue());
            return;
        }
        final double d = value.doubleValue();
        if (d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE) {
            state.push((long) d);
        } else {
            state.push((Number) Double.valueOf(d));
        }
    }

    /**
     * Pushes a Java string as its UTF-8 bytes.
     */
    public void pushString(final Lua state, final String value) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == 0 || c > 0x7F) {
                pushBytes(state, value.getBytes(StandardCharsets.UTF_8));
                return;
            }
        }
        state.push(value);
    }

    /**
     * Pushes the exact bytes given, whatever they are.
     */
    public void pushBytes(final Lua state, final byte[] bytes) {
        pushBytes(state, bytes, 0, bytes.length);
    }

    public void pushBytes(final Lua state, final byte[] bytes, final int offset, final int length) {
        if (isPlainAscii(bytes, offset, length)) {
            // Modified UTF-8 leaves this range alone, so the direct route is already exact.
            state.push(new String(bytes, offset, length, StandardCharsets.ISO_8859_1));
            return;
        }

        final ByteBuffer buffer = scratch(CHUNK_PREFIX.length + length * 4 + 1);
        buffer.put(CHUNK_PREFIX);
        for (int i = 0; i < length; i++) {
            final int v = bytes[offset + i] & 0xFF;
            if (v >= 0x20 && v < 0x7F && v != '"' && v != '\\') {
                buffer.put((byte) v);
            } else {
                // Always three digits: a two digit escape followed by a literal digit would be
                // read as one longer escape.
                buffer.put((byte) '\\')
                    .put((byte) ('0' + v / 100))
                    .put((byte) ('0' + v / 10 % 10))
                    .put((byte) ('0' + v % 10));
            }
        }
        buffer.put((byte) '"');
        buffer.flip();

        state.checkStack(2);
        state.load(buffer, "=bytes");
        state.pCall(0, 1);
    }

    private static boolean isPlainAscii(final byte[] bytes, final int offset, final int length) {
        for (int i = 0; i < length; i++) {
            final int v = bytes[offset + i];
            if (v <= 0) {
                // Negative is a high byte; zero would be encoded as two bytes.
                return false;
            }
        }
        return true;
    }

    private ByteBuffer scratch(final int needed) {
        if (scratch.capacity() < needed) {
            int size = scratch.capacity();
            while (size < needed) {
                size <<= 1;
            }
            scratch = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        }
        scratch.clear();
        return scratch;
    }

    ///////////////////////////////////////////////////////////////////
    // Lua to Java

    /**
     * Converts a value on the stack into the plain Java representation components and signals use.
     * <p>
     * Strings come back as {@link String}, decoded as UTF-8, matching the pure Java backend.
     * Callers that need the exact bytes ask for {@link #toBytes(Lua, int)} instead.
     */
    @Nullable
    public Object toJava(final Lua state, final int index) {
        return toJava(state, index, new HashMap<>(), 0);
    }

    @Nullable
    private Object toJava(final Lua state, final int index, final Map<Long, Object> seen, final int depth) {
        switch (state.type(index)) {
            case NIL:
            case NONE:
                return null;
            case BOOLEAN:
                return state.toBoolean(index);
            case NUMBER:
                // One Java type for both of Lua's, so that a program cannot make a component see a
                // different argument type by writing 1 instead of 1.0.
                return state.toNumber(index);
            case STRING:
                return new String(toBytes(state, index), StandardCharsets.UTF_8);
            case TABLE:
                return tableToJava(state, index, seen, depth);
            default:
                // Functions, threads and userdata have no meaning outside the Lua state; refusing
                // them beats handing components something they cannot use.
                throw new IllegalArgumentException(
                    "Cannot convert a Lua " + state.type(index) + " to a Java value.");
        }
    }

    private Object tableToJava(final Lua state, final int index, final Map<Long, Object> seen, final int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Table nests too deeply to convert from Lua.");
        }

        final long identity = state.getLuaNatives().lua_topointer(state.getPointer(), index);
        final Object existing = seen.get(identity);
        if (existing != null) {
            // A table that refers to itself is unusual but legal, and has to come out the other
            // side as the same map rather than as an infinite regress.
            return existing;
        }

        final Map<Object, Object> result = new LinkedHashMap<>();
        seen.put(identity, result);

        final int table = absolute(state, index);
        state.checkStack(3);
        state.pushNil();
        while (state.next(table) != 0) {
            // The key is at -2 and the value at -1. Converting the key must not coerce it in
            // place, which is why numbers are read as numbers and never as strings here.
            final Object key = toJava(state, -2, seen, depth + 1);
            final Object value = toJava(state, -1, seen, depth + 1);
            state.pop(1);
            if (key != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * The exact bytes of a string on the stack.
     */
    public byte[] toBytes(final Lua state, final int index) {
        if (state.type(index) == Lua.LuaType.NUMBER) {
            // Reading a number as a string would convert the stack slot in place, which is fatal
            // in the middle of a table traversal. Formatting it here avoids touching the stack.
            return formatNumber(state.toNumber(index)).getBytes(StandardCharsets.UTF_8);
        }
        final ByteBuffer buffer = state.toBuffer(index);
        if (buffer == null) {
            throw new IllegalArgumentException("Value is not a string.");
        }
        final byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    /**
     * Snapshots a call's arguments, because a deferred component call keeps them across a yield
     * and a hop to the server thread, long after the stack they came from is gone.
     * <p>
     * Strings are kept as bytes: whether a given argument is text or data is the component's to
     * decide, and deciding it here would corrupt every binary write.
     */
    public Object[] snapshot(final Lua state, final int from) {
        final int top = state.getTop();
        final Object[] result = new Object[Math.max(0, top - from + 1)];
        for (int i = 0; i < result.length; i++) {
            final int index = from + i;
            result[i] = state.type(index) == Lua.LuaType.STRING
                ? toBytes(state, index)
                : toJava(state, index);
        }
        return result;
    }

    /**
     * Converts a whole argument list, used where a machine hands Java plain values, such as
     * {@code computer.pushSignal}.
     */
    public Object[] toJavaArray(final Lua state, final int from) {
        final int top = state.getTop();
        final Object[] result = new Object[Math.max(0, top - from + 1)];
        for (int i = 0; i < result.length; i++) {
            result[i] = toJava(state, from + i);
        }
        return result;
    }

    private static int absolute(final Lua state, final int index) {
        return index > 0 ? index : state.getTop() + index + 1;
    }

    /**
     * Renders a number the way Lua's own {@code tostring} would, so that a component asking for a
     * string and getting a number sees what the program would have seen.
     */
    public static String formatNumber(final double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)
            && Math.abs(value) <= Long.MAX_VALUE) {
            return Long.toString((long) value);
        }
        return String.format("%.14g", value);
    }
}
