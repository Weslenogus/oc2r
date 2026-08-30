/* SPDX-License-Identifier: MIT */

package li.cil.oc2.api.machine;

/**
 * A device visible to the OpenComputers 1 compatible Lua runtime through {@code component.*}.
 * <p>
 * Implementations expose their functionality by annotating methods with {@link Callback}. The
 * component bus scans the class once, caches the result and makes the methods reachable as
 * {@code component.invoke(address, method, ...)}.
 * <p>
 * Addresses are UUID strings and are assigned by whoever creates the component; they must remain
 * stable across save and load, because operating systems persist them. {@code /etc/} on an
 * OpenOS install, for example, refers to filesystems by address.
 */
public interface LuaComponent {
    /**
     * The component type, as reported by {@code component.type} and used to filter
     * {@code component.list}. For example {@code "gpu"}, {@code "screen"} or
     * {@code "filesystem"}.
     *
     * @return the type name of this component.
     */
    String getComponentType();

    /**
     * The unique address of this component. Must be a lowercase UUID string and must not change
     * over the lifetime of the component.
     *
     * @return the address of this component.
     */
    String getComponentAddress();

    /**
     * The inventory slot this component lives in, or {@code -1} if it is not slot bound.
     *
     * @return the slot of this component.
     */
    default int getComponentSlot() {
        return -1;
    }

    /**
     * Called after the component has been attached to a machine's bus, before the
     * {@code component_added} signal is queued.
     *
     * @param machine the machine this component was attached to.
     */
    default void onConnect(final Machine machine) {
    }

    /**
     * Called after the component has been detached from a machine's bus. Implementations should
     * release per machine state here, such as open file handles.
     *
     * @param machine the machine this component was detached from.
     */
    default void onDisconnect(final Machine machine) {
    }
}
