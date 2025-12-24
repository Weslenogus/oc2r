/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.bus.device.data;

import li.cil.oc2.api.API;
import li.cil.oc2.api.bus.device.data.Firmware;
import li.cil.oc2.api.util.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.RegistryBuilder;
import net.minecraftforge.registries.RegistryObject;

import javax.annotation.Nullable;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class FirmwareRegistry {
    private static final DeferredRegister<Firmware> INITIALIZER = DeferredRegister.create(Registries.FIRMWARE, API.MOD_ID);

    ///////////////////////////////////////////////////////////////////

    private static final Supplier<IForgeRegistry<Firmware>> REGISTRY = INITIALIZER.makeRegistry(RegistryBuilder::new);

    ///////////////////////////////////////////////////////////////////

    public static final RegistryObject<Firmware> MINUX = INITIALIZER.register("minux", MinuxFirmware::new);

    ///////////////////////////////////////////////////////////////////

    public static void initialize(IEventBus modBus) {
        INITIALIZER.register(modBus);
    }

    @SuppressWarnings("unused")
    public static ResourceLocation getKey(final Firmware firmware) {
        return INITIALIZER.getRegistryName();
    }

    @Nullable
    public static Firmware getValue(final ResourceLocation location) {
        return REGISTRY.get().getValue(location);
    }

    public static Stream<Firmware> values() {
        return REGISTRY.get().getValues().stream();
    }
}
