/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.container;

import li.cil.oc2.api.bus.device.DeviceType;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;

public abstract class AbstractTypedDeviceItemStackHandler extends AbstractDeviceItemStackHandler {
    private final DeviceType deviceType;

    ///////////////////////////////////////////////////////////////////

    public AbstractTypedDeviceItemStackHandler(Supplier<HolderLookup.Provider> providerSupplier, final int size, final DeviceType deviceType) {
        super(providerSupplier, size);
        this.deviceType = deviceType;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public boolean isItemValid(final int slot, final ItemStack stack) {
        return super.isItemValid(slot, stack) && stack.is(deviceType.getTag());
    }
}
