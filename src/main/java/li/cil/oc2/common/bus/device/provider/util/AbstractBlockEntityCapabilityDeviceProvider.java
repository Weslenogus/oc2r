/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.bus.device.provider.util;

import li.cil.oc2.api.bus.device.Device;
import li.cil.oc2.api.bus.device.provider.BlockDeviceQuery;
import li.cil.oc2.api.util.Invalidatable;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

public abstract class AbstractBlockEntityCapabilityDeviceProvider<TCapability, TBlockEntity extends BlockEntity> extends AbstractBlockEntityDeviceProvider<TBlockEntity> {
    private final Supplier<BlockCapability<TCapability, @Nullable Direction>> capabilitySupplier;

    ///////////////////////////////////////////////////////////////////

    protected AbstractBlockEntityCapabilityDeviceProvider(final BlockEntityType<TBlockEntity> blockEntityType, final Supplier<BlockCapability<TCapability, @Nullable Direction>> capabilitySupplier) {
        super(blockEntityType);
        this.capabilitySupplier = capabilitySupplier;
    }

    protected AbstractBlockEntityCapabilityDeviceProvider(final Supplier<BlockCapability<TCapability, @Nullable Direction>> capabilitySupplier) {
        this.capabilitySupplier = capabilitySupplier;
    }

    ///////////////////////////////////////////////////////////////////

    // This class exists to allow Invalidatable<Device> to hold the strong reference to the listener it relies on
    private static class InvalidateableAdapter<T> implements Consumer<Invalidatable<T>> {
        // NeoForge will only hold a weak reference to this listener (so that registering a listener cause a memory leak)
        // Therefore we must hold the reference to keep it from being garbage collected while we're still around
        public ICapabilityInvalidationListener capabilityInvalidationListener;

        public InvalidateableAdapter(ICapabilityInvalidationListener capabilityInvalidationListener) {
            this.capabilityInvalidationListener = capabilityInvalidationListener;
        }

        @Override
        public void accept(final Invalidatable<T> tInvalidatable) {
            // Untangle circular reference of us -> capabilityInvalidationListener -> Invalidateable<T> -> us
            capabilityInvalidationListener = null;
        }
    }

    @Override
    protected final Invalidatable<Device> getBlockDevice(final BlockDeviceQuery query, final TBlockEntity blockEntity) {
        final BlockCapability<TCapability, @Nullable Direction> capability = capabilitySupplier.get();
        if (capability == null) throw new IllegalStateException();
        if (!(blockEntity.getLevel() instanceof ServerLevel level)) throw new IllegalStateException();

        final var blockPos = blockEntity.getBlockPos();
        final TCapability optional = level.getCapability(capability, blockPos, null, blockEntity, query.getQuerySide());
        if (optional == null) {
            return Invalidatable.empty();
        }

        final Invalidatable<Device> device = getBlockDevice(query, optional);
        var adapter = new InvalidateableAdapter<Device>(() -> {
            device.invalidate();
            return false;
        });
        level.registerCapabilityListener(blockPos, adapter.capabilityInvalidationListener);
        device.addListener(adapter);

        return device;
    }

    protected abstract Invalidatable<Device> getBlockDevice(final BlockDeviceQuery query, final TCapability value);
}
