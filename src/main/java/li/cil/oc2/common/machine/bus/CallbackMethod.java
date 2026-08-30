/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.bus;

import li.cil.oc2.api.machine.Arguments;
import li.cil.oc2.api.machine.Callback;
import li.cil.oc2.api.machine.Context;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * A single {@link Callback} annotated method, resolved and validated once by {@link Callbacks}.
 */
public final class CallbackMethod {
    private static final Object[] EMPTY = new Object[0];

    private final String name;
    private final Method method;
    private final boolean direct;
    private final int limit;
    private final String doc;
    private final boolean getter;
    private final boolean setter;
    private final boolean returnsArray;
    private final boolean returnsVoid;

    CallbackMethod(final String name, final Method method, final Callback annotation) {
        this.name = name;
        this.method = method;
        this.direct = annotation.direct();
        this.limit = Math.max(1, annotation.limit());
        this.doc = annotation.doc();
        this.getter = annotation.getter();
        this.setter = annotation.setter();
        this.returnsArray = method.getReturnType() == Object[].class;
        this.returnsVoid = method.getReturnType() == void.class;
        method.setAccessible(true);
    }

    public String getName() {
        return name;
    }

    /**
     * Whether the method may run on the machine thread. See {@link Callback#direct()}.
     */
    public boolean isDirect() {
        return direct;
    }

    /**
     * How many direct invocations are permitted per tick. See {@link Callback#limit()}.
     */
    public int getLimit() {
        return limit;
    }

    public boolean isGetter() {
        return getter;
    }

    public boolean isSetter() {
        return setter;
    }

    /**
     * The documentation string, or the method signature if none was given, matching what
     * OpenComputers 1 returns from {@code component.doc}.
     */
    public String getDoc() {
        return doc.isEmpty() ? name + "(...)" : doc;
    }

    /**
     * Invokes the method, normalizing its result into the multi-return array Lua expects.
     *
     * @throws Throwable whatever the method threw, unwrapped from its reflection wrapper so the
     *                   Lua side sees the original message.
     */
    public Object[] invoke(final Object instance, final Context context, final Arguments args) throws Throwable {
        final Object result;
        try {
            result = method.invoke(instance, context, args);
        } catch (final InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException("Callback [" + name + "] became inaccessible.", e);
        }

        if (returnsVoid) {
            return EMPTY;
        }
        if (returnsArray) {
            return result == null ? EMPTY : (Object[]) result;
        }
        return new Object[]{result};
    }

    @Override
    public String toString() {
        return method.getDeclaringClass().getSimpleName() + "." + name + (direct ? " [direct]" : "");
    }
}
