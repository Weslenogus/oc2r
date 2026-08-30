/* SPDX-License-Identifier: MIT */

package li.cil.oc2.api.machine;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method of a {@link LuaComponent} or {@link Value} as callable from the
 * OpenComputers 1 compatible Lua runtime.
 * <p>
 * Annotated methods must have the signature
 * <pre>{@code Object[] name(Context context, Arguments args)}</pre>
 * A {@code void} return type and a bare {@link Object} return type are also accepted; the
 * latter is wrapped into a single element result array. Anything else is rejected when the
 * declaring class is first scanned, so mistakes surface at load time rather than from Lua.
 * <p>
 * This is the OC1 flavoured counterpart of {@link li.cil.oc2.api.bus.device.object.Callback},
 * which describes RPC methods exposed to the RISC-V virtual machine. The two annotation types
 * are intentionally kept separate: their invocation models differ, and a device may expose a
 * different surface to each runtime.
 *
 * @see Callbacks
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Callback {
    /**
     * Whether this method may be invoked straight from the machine thread.
     * <p>
     * Direct calls do not cost the caller a tick, but they run concurrently with the server
     * thread and must therefore only touch thread safe state. Anything reaching into the level,
     * block entities or entities must stay indirect, in which case the call is queued and
     * executed on the server thread, costing the caller at least one tick.
     *
     * @return {@code true} if the method is thread safe; {@code false} otherwise.
     */
    boolean direct() default false;

    /**
     * How often a {@link #direct()} method may be called per tick before calls start falling
     * back to the synchronized path.
     * <p>
     * This is what keeps a tight Lua loop over a direct call from starving the server: once the
     * budget for the current tick is spent, further calls are promoted to indirect calls and
     * hence yield. Ignored for indirect methods.
     *
     * @return the number of direct invocations permitted per tick.
     */
    int limit() default 10;

    /**
     * Explicit name of the method as seen from Lua. Defaults to the Java method name.
     *
     * @return the name of the method.
     */
    String name() default "";

    /**
     * Documentation string returned by {@code component.doc(address, method)}.
     *
     * @return the documentation of the method.
     */
    String doc() default "";

    /**
     * Marks the method as a property getter for documentation purposes.
     *
     * @return {@code true} if this method reads a property.
     */
    boolean getter() default false;

    /**
     * Marks the method as a property setter for documentation purposes.
     *
     * @return {@code true} if this method writes a property.
     */
    boolean setter() default false;
}
