/* SPDX-License-Identifier: MIT */

package li.cil.oc2.api.machine;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * The argument list a {@link Callback} annotated method was invoked with.
 * <p>
 * Values have already been converted from their Lua representation to plain Java objects. The
 * possible types are {@code null}, {@link Boolean}, {@link Double}, {@link String},
 * {@code byte[]} and {@link Map}. Note that Lua has a single number type, so every number
 * arrives as a {@link Double}; the integer accessors round towards zero the way OpenComputers 1
 * does.
 * <p>
 * Lua strings are byte strings. {@link #checkString(int)} decodes them as UTF-8, which is what
 * text oriented APIs want; anything binary (file contents, for instance) must go through
 * {@link #checkByteArray(int)} instead, or it will be mangled.
 * <p>
 * All indices are zero based, unlike the Lua side they originate from.
 */
public interface Arguments {
    /**
     * The number of arguments passed, including trailing {@code nil}s that were explicitly given.
     *
     * @return the argument count.
     */
    int count();

    /**
     * The raw argument at the specified index, or {@code null} if there is none.
     *
     * @param index the zero based index of the argument.
     * @return the argument value.
     */
    @Nullable
    Object get(int index);

    /**
     * The arguments as a plain array. Modifying the returned array does not affect this instance.
     *
     * @return the arguments.
     */
    Object[] toArray();

    boolean isDefined(int index);

    boolean isBoolean(int index);

    boolean isNumber(int index);

    boolean isString(int index);

    boolean isTable(int index);

    /**
     * @throws IllegalArgumentException if there is no argument at the specified index.
     */
    Object checkAny(int index);

    boolean checkBoolean(int index);

    /**
     * @throws IllegalArgumentException if the argument is not a number.
     */
    double checkDouble(int index);

    /**
     * @throws IllegalArgumentException if the argument is not a number.
     */
    int checkInteger(int index);

    /**
     * @throws IllegalArgumentException if the argument is not a number.
     */
    long checkLong(int index);

    /**
     * The argument decoded as a UTF-8 string.
     *
     * @throws IllegalArgumentException if the argument is neither a string nor a number.
     */
    String checkString(int index);

    /**
     * The argument as the exact bytes the Lua string held.
     *
     * @throws IllegalArgumentException if the argument is neither a string nor a number.
     */
    byte[] checkByteArray(int index);

    /**
     * @throws IllegalArgumentException if the argument is not a table.
     */
    Map<?, ?> checkTable(int index);

    boolean optBoolean(int index, boolean defaultValue);

    double optDouble(int index, double defaultValue);

    int optInteger(int index, int defaultValue);

    long optLong(int index, long defaultValue);

    @Nullable
    String optString(int index, @Nullable String defaultValue);

    @Nullable
    byte[] optByteArray(int index, @Nullable byte[] defaultValue);

    @Nullable
    Map<?, ?> optTable(int index, @Nullable Map<?, ?> defaultValue);
}
