package li.cil.oc2.common.loot.function;

import com.mojang.serialization.MapCodec;
import li.cil.oc2.common.blockentity.ComputerBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.jetbrains.annotations.NotNull;

public class CopyComputerInventory implements LootItemFunction {
    public static final MapCodec<CopyComputerInventory> CODEC = MapCodec.unit(new CopyComputerInventory());

    @Override
    public @NotNull LootItemFunctionType<? extends LootItemFunction> getType() {
        return LootItemFunctions.COPY_COMPUTER_INVENTORY.get();
    }

    @Override
    public ItemStack apply(final ItemStack itemStack, final LootContext context) {
        BlockEntity blockentity = context.getParamOrNull(LootContextParams.BLOCK_ENTITY);
        if (blockentity == null) return itemStack;
        if (!(blockentity instanceof ComputerBlockEntity computer)) return itemStack;

        computer.exportToItemStack(itemStack);
        return itemStack;
    }

    public static class Builder implements LootItemFunction.Builder {
        @Override
        public @NotNull LootItemFunction build() {
            return new CopyComputerInventory();
        }
    }

    public static Builder copyComputerInventory() {
        return new Builder();
    }
}
