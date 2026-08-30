/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.bus;

import li.cil.oc2.api.machine.Arguments;
import li.cil.oc2.api.machine.Callback;
import li.cil.oc2.api.machine.Context;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection front end for the {@link Callback} annotation.
 * <p>
 * Each class is scanned exactly once and the result cached, because {@code component.methods} and
 * every single {@code component.invoke} go through here, and Lua code is happy to call components
 * thousands of times per second.
 */
public final class Callbacks {
    private static final Map<Class<?>, Map<String, CallbackMethod>> CACHE = new ConcurrentHashMap<>();

    private Callbacks() {
    }

    /**
     * The callbacks exposed by the given object's class, keyed by their Lua visible name.
     *
     * @param instance the object to scan.
     * @return the callbacks, in declaration order; never {@code null}.
     */
    public static Map<String, CallbackMethod> collect(final Object instance) {
        return collect(instance.getClass());
    }

    /**
     * The callbacks exposed by the given class, keyed by their Lua visible name.
     *
     * @param type the class to scan.
     * @return the callbacks, in declaration order; never {@code null}.
     * @throws IllegalArgumentException if an annotated method has an unsupported signature, or if
     *                                  two annotated methods claim the same Lua name.
     */
    public static Map<String, CallbackMethod> collect(final Class<?> type) {
        return CACHE.computeIfAbsent(type, Callbacks::scan);
    }

    private static Map<String, CallbackMethod> scan(final Class<?> type) {
        final Map<String, CallbackMethod> methods = new LinkedHashMap<>();

        // getMethods() walks superclasses and interfaces for us, which is what we want: a device
        // may inherit part of its surface from an abstract base or pick it up from a default
        // method on an interface.
        for (final Method method : type.getMethods()) {
            final Callback annotation = method.getAnnotation(Callback.class);
            if (annotation == null || method.isSynthetic() || method.isBridge()) {
                continue;
            }

            validate(type, method);

            final String name = annotation.name().isEmpty() ? method.getName() : annotation.name();
            final CallbackMethod previous = methods.put(name, new CallbackMethod(name, method, annotation));
            if (previous != null) {
                throw new IllegalArgumentException(
                    "Duplicate callback name [" + name + "] on [" + type.getName() + "].");
            }
        }

        return Collections.unmodifiableMap(methods);
    }

    private static void validate(final Class<?> type, final Method method) {
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException(
                "Callback [" + method + "] on [" + type.getName() + "] must not be static.");
        }

        final Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length != 2
            || parameters[0] != Context.class
            || parameters[1] != Arguments.class) {
            throw new IllegalArgumentException(
                "Callback [" + method + "] on [" + type.getName() + "] must have the signature " +
                    "(" + Context.class.getSimpleName() + ", " + Arguments.class.getSimpleName() + ").");
        }

        final Class<?> returnType = method.getReturnType();
        if (returnType.isPrimitive() && returnType != void.class) {
            throw new IllegalArgumentException(
                "Callback [" + method + "] on [" + type.getName() + "] must return Object[], " +
                    "Object or void; primitive return types are not supported because they cannot " +
                    "express a nil result.");
        }
    }
}
