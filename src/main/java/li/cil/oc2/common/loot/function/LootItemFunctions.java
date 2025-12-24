package li.cil.oc2.common.loot.function;

import li.cil.oc2.api.API;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class LootItemFunctions {
    private static final DeferredRegister<LootItemFunctionType<?>> LOOT_ITEM_FUNCTIONS = DeferredRegister.create(BuiltInRegistries.LOOT_FUNCTION_TYPE, API.MOD_ID);

    public static final DeferredHolder<LootItemFunctionType<?>, LootItemFunctionType<CopyComputerInventory>> COPY_COMPUTER_INVENTORY = LOOT_ITEM_FUNCTIONS.register(
        "copy_computer_inventory",
        x -> new LootItemFunctionType<>(CopyComputerInventory.CODEC)
    );

    public static void initialize(IEventBus modBus) {
        LOOT_ITEM_FUNCTIONS.register(modBus);
    }
}
