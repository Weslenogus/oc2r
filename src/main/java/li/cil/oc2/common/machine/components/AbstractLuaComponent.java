/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.components;

import li.cil.oc2.api.machine.LuaComponent;

import java.util.UUID;

/**
 * Common base for components: type name, address and slot.
 * <p>
 * Addresses have to survive a save and reload, because operating systems write them down.
 * OpenOS's {@code /etc/fstab} refers to filesystems by address, and MineOS remembers which screen
 * it was using. Whoever creates a component is therefore expected to hand it the address it had
 * last time, and a fresh random one only when it is genuinely new.
 */
public abstract class AbstractLuaComponent implements LuaComponent {
    private final String type;
    private final String address;
    private int slot = -1;

    protected AbstractLuaComponent(final String type) {
        this(type, UUID.randomUUID().toString());
    }

    protected AbstractLuaComponent(final String type, final String address) {
        this.type = type;
        this.address = address;
    }

    @Override
    public final String getComponentType() {
        return type;
    }

    @Override
    public final String getComponentAddress() {
        return address;
    }

    @Override
    public final int getComponentSlot() {
        return slot;
    }

    public final void setComponentSlot(final int value) {
        slot = value;
    }

    @Override
    public String toString() {
        return type + "@" + address;
    }
}
