/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.bus;

import li.cil.oc2.api.machine.LuaComponent;
import li.cil.oc2.api.machine.Machine;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The set of components a machine can see, and the source of the {@code component_added} and
 * {@code component_removed} signals.
 * <p>
 * Mutated from the server thread when the host rescans its neighbourhood, and read concurrently
 * from the machine thread on every {@code component.invoke}, hence the concurrent map.
 */
public final class ComponentBus {
    private final Machine machine;
    private final Map<String, LuaComponent> componentsByAddress = new ConcurrentHashMap<>();

    public ComponentBus(final Machine machine) {
        this.machine = machine;
    }

    /**
     * Replaces the attached components with the given set, queueing a signal for every addition
     * and removal. Must be called from the server thread.
     *
     * @param components the components that should now be attached.
     */
    public void setComponents(final Collection<LuaComponent> components) {
        final Map<String, LuaComponent> wanted = new LinkedHashMap<>();
        for (final LuaComponent component : components) {
            final String address = component.getComponentAddress();
            if (address != null && !address.isEmpty()) {
                wanted.put(address, component);
            }
        }

        // Snapshot first: we mutate the backing map while iterating over the differences.
        final List<LuaComponent> removed = new ArrayList<>();
        for (final Map.Entry<String, LuaComponent> entry : componentsByAddress.entrySet()) {
            if (wanted.get(entry.getKey()) != entry.getValue()) {
                removed.add(entry.getValue());
            }
        }

        for (final LuaComponent component : removed) {
            detach(component);
        }

        for (final Map.Entry<String, LuaComponent> entry : wanted.entrySet()) {
            if (!componentsByAddress.containsKey(entry.getKey())) {
                attach(entry.getValue());
            }
        }
    }

    /**
     * Detaches every component without signalling, used when the machine shuts down.
     */
    public void clear() {
        for (final LuaComponent component : new ArrayList<>(componentsByAddress.values())) {
            componentsByAddress.remove(component.getComponentAddress(), component);
            component.onDisconnect(machine);
        }
    }

    public Collection<LuaComponent> getComponents() {
        return Collections.unmodifiableCollection(componentsByAddress.values());
    }

    public Optional<LuaComponent> getComponent(@Nullable final String address) {
        return address == null ? Optional.empty() : Optional.ofNullable(componentsByAddress.get(address));
    }

    /**
     * Backs {@code component.list(filter, exact)}.
     *
     * @param filter the type filter, or {@code null} to list everything.
     * @param exact  whether the filter must match the type exactly rather than as a substring.
     * @return a map of address to component type.
     */
    public Map<String, String> list(@Nullable final String filter, final boolean exact) {
        final Map<String, String> result = new LinkedHashMap<>();
        for (final LuaComponent component : componentsByAddress.values()) {
            final String type = component.getComponentType();
            if (filter == null || filter.isEmpty()
                || (exact ? type.equals(filter) : type.contains(filter))) {
                result.put(component.getComponentAddress(), type);
            }
        }
        return result;
    }

    /**
     * Backs {@code component.methods(address)}.
     *
     * @param address the component to describe.
     * @return a map of method name to whether the method is direct, or {@code null} if there is no
     * such component.
     */
    @Nullable
    public Map<String, Boolean> methods(@Nullable final String address) {
        final LuaComponent component = address == null ? null : componentsByAddress.get(address);
        if (component == null) {
            return null;
        }

        final Map<String, Boolean> result = new LinkedHashMap<>();
        Callbacks.collect(component).forEach((name, method) -> result.put(name, method.isDirect()));
        return result;
    }

    /**
     * Resolves a callback for {@code component.invoke}.
     *
     * @param address the component to call.
     * @param method  the method to call.
     * @return the callback, or {@code null} if either the component or the method is unknown.
     */
    @Nullable
    public CallbackMethod lookup(@Nullable final String address, final String method) {
        final LuaComponent component = address == null ? null : componentsByAddress.get(address);
        return component == null ? null : Callbacks.collect(component).get(method);
    }

    private void attach(final LuaComponent component) {
        componentsByAddress.put(component.getComponentAddress(), component);
        component.onConnect(machine);
        machine.signal("component_added", component.getComponentAddress(), component.getComponentType());
    }

    private void detach(final LuaComponent component) {
        componentsByAddress.remove(component.getComponentAddress(), component);
        component.onDisconnect(machine);
        machine.signal("component_removed", component.getComponentAddress(), component.getComponentType());
    }
}
