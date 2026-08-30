/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.lua;

import li.cil.oc2.api.machine.Arguments;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * {@link Arguments} backed by the {@link Varargs} a callback was invoked with.
 * <p>
 * Values are converted lazily, one accessor call at a time. That keeps a binary
 * {@code filesystem.write} from being decoded as text on its way in, and it means a component
 * that only looks at its first argument does not pay for the rest.
 */
public final class LuaArguments implements Arguments {
    private final Varargs args;
    private final int offset;

    /**
     * @param args   the raw argument list.
     * @param offset how many leading values to skip, used to hide the address and method name
     *               that {@code component.invoke} passes ahead of the real arguments.
     */
    public LuaArguments(final Varargs args, final int offset) {
        this.args = args;
        this.offset = offset;
    }

    @Override
    public int count() {
        return Math.max(0, args.narg() - offset);
    }

    private LuaValue raw(final int index) {
        // Arguments is zero based on the Java side, Varargs is one based on the Lua side.
        return index < 0 ? LuaValue.NIL : args.arg(index + offset + 1);
    }

    @Override
    @Nullable
    public Object get(final int index) {
        return LuaValues.toJava(raw(index));
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
        return index >= 0 && index < count() && !raw(index).isnil();
    }

    @Override
    public boolean isBoolean(final int index) {
        return raw(index).type() == LuaValue.TBOOLEAN;
    }

    @Override
    public boolean isNumber(final int index) {
        final int type = raw(index).type();
        return type == LuaValue.TNUMBER || (type == LuaValue.TSTRING && raw(index).isnumber());
    }

    @Override
    public boolean isString(final int index) {
        final int type = raw(index).type();
        return type == LuaValue.TSTRING || type == LuaValue.TNUMBER;
    }

    @Override
    public boolean isTable(final int index) {
        return raw(index).istable();
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
        if (!isBoolean(index)) {
            throw badArgument(index, "boolean expected, got " + typeName(index));
        }
        return raw(index).toboolean();
    }

    @Override
    public double checkDouble(final int index) {
        if (!isNumber(index)) {
            throw badArgument(index, "number expected, got " + typeName(index));
        }
        return raw(index).todouble();
    }

    @Override
    public int checkInteger(final int index) {
        final double value = checkDouble(index);
        if (Double.isNaN(value)) {
            throw badArgument(index, "number has no integer representation");
        }
        // Lua truncates towards zero when a number is used where an integer is wanted, and
        // OpenOS leans on that in places, for instance when passing computed coordinates.
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
        if (!isString(index)) {
            throw badArgument(index, "string expected, got " + typeName(index));
        }
        return LuaValues.toString(raw(index));
    }

    @Override
    public byte[] checkByteArray(final int index) {
        if (!isString(index)) {
            throw badArgument(index, "string expected, got " + typeName(index));
        }
        return LuaValues.toByteArray(raw(index));
    }

    @Override
    public Map<?, ?> checkTable(final int index) {
        if (!isTable(index)) {
            throw badArgument(index, "table expected, got " + typeName(index));
        }
        return (Map<?, ?>) LuaValues.toJava(raw(index));
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

    private String typeName(final int index) {
        return raw(index).typename();
    }

    private IllegalArgumentException badArgument(final int index, final String message) {
        // Report the index the way Lua counts, so the message lines up with the call site.
        return new IllegalArgumentException("bad argument #" + (index + 1) + " (" + message + ")");
    }
}
