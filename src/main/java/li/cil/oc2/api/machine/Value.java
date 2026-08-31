/* SPDX-License-Identifier: MIT */

package li.cil.oc2.api.machine;

/**
 * A Java object handed to Lua as an opaque, callable value rather than as a component.
 * <p>
 * Values are how OpenComputers 1 models short lived, unaddressed objects such as the handle
 * returned by {@code internet.request}. Their {@link Callback} annotated methods are exposed as
 * fields on the table Lua receives, so {@code handle.read(n)} and {@code handle.close()} work
 * the way user code expects.
 * <p>
 * Unlike a {@link LuaComponent}, a value has no address and is not visible through
 * {@code component.list}. It stays alive for as long as Lua holds a reference to it.
 */
public interface Value {
    /**
     * Called when the machine that handed out this value stops, so native resources such as
     * sockets can be released. Implementations must tolerate being called more than once.
     */
    default void dispose() {
    }
}
