/* SPDX-License-Identifier: MIT */

package li.cil.oc2.api.machine;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Optional;

/**
 * Handed to {@link Callback} annotated methods as their first parameter, giving components a way
 * back to the machine that called them.
 * <p>
 * Instances are valid for the duration of the call only. Components that need to reach the machine
 * later should keep the reference obtained from {@link LuaComponent#onConnect(Machine)} instead.
 */
public interface Context {
    /**
     * The machine performing the call.
     *
     * @return the calling machine.
     */
    Machine machine();

    /**
     * The address of the calling machine, which is also the address of its {@code computer}
     * component.
     *
     * @return the machine address.
     */
    default String machineAddress() {
        return machine().getAddress();
    }

    /**
     * Whether this call is running on the server thread. Indirect calls always are, direct calls
     * never are. Components that touch the level must assert on this rather than assume.
     *
     * @return {@code true} if it is safe to touch level state.
     */
    boolean isSynchronized();

    /**
     * Queues a signal on the calling machine.
     *
     * @param name the name of the signal.
     * @param args the signal arguments.
     * @return {@code true} if the signal was queued; {@code false} if the queue is full.
     */
    boolean signal(String name, Object... args);

    /**
     * Attempts to draw energy from the machine's host.
     *
     * @param amount the amount of energy to consume.
     * @return {@code true} if the energy was available and consumed.
     */
    boolean consumeEnergy(double amount);

    /**
     * All components currently attached to the calling machine.
     *
     * @return the attached components.
     */
    Collection<LuaComponent> components();

    /**
     * Looks up an attached component by address.
     *
     * @param address the address to look up.
     * @return the component, if one is attached under that address.
     */
    Optional<LuaComponent> component(@Nullable String address);

    /**
     * The object hosting the machine, typically a block entity or entity. Components that need
     * level access can narrow this, having first checked {@link #isSynchronized()}.
     *
     * @return the machine host.
     */
    MachineHost host();
}
