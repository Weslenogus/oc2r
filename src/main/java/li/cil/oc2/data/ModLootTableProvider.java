/* SPDX-License-Identifier: MIT */

package li.cil.oc2.data;

import li.cil.oc2.common.block.Blocks;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.CopyNbtFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.providers.nbt.ContextNbtProvider;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraftforge.registries.RegistryObject;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;
import static li.cil.oc2.common.Constants.*;

public final class ModLootTableProvider extends LootTableProvider {
    public ModLootTableProvider(final PackOutput output, final Set<ResourceLocation> additionalTables, final List<SubProviderEntry> subProviders) {
        super(output, additionalTables, subProviders);
    }

    @Override
    public List<SubProviderEntry> getTables() {
        return singletonList(
            new LootTableProvider.SubProviderEntry(
                ModBlockLootTables::new,
                LootContextParamSets.BLOCK
            )
        );
    }

    public static final class ModBlockLootTables extends BlockLootSubProvider {
        public ModBlockLootTables() {
            super(Collections.emptySet(), FeatureFlags.REGISTRY.allFlags());
        }

        @Override
        protected void generate() {
            dropSelf(Blocks.CHARGER.get());
            add(Blocks.COMPUTER.get(), this::droppingWithInventory);
            dropSelf(Blocks.DISK_DRIVE.get());
            dropSelf(Blocks.KEYBOARD.get());
            dropSelf(Blocks.NETWORK_CONNECTOR.get());
            dropSelf(Blocks.NETWORK_HUB.get());
            dropSelf(Blocks.PROJECTOR.get());
            dropSelf(Blocks.REDSTONE_INTERFACE.get());
            // A Lua computer carries its disk and a screen carries its address, so both are
            // dropped with those intact: mining a computer and putting it back down should not
            // wipe the operating system installed on it.
            add(Blocks.LUA_COMPUTER.get(), block -> droppingWithData(block,
                LUA_COMPUTER_DATA_TAG_NAMES));
            add(Blocks.LUA_SCREEN.get(), block -> droppingWithData(block,
                LUA_SCREEN_DATA_TAG_NAMES));
        }

        @Override
        protected Iterable<Block> getKnownBlocks() {
            return Blocks.BLOCKS.getEntries()
                .stream()
                .filter(blockRegObj -> blockRegObj.get() != Blocks.BUS_CABLE.get())
                .map(RegistryObject::get)
                .collect(Collectors.toList());
        }

        /**
         * Tags a Lua computer keeps when mined: the disk it was carrying, its EEPROM, the
         * machine's own address and its stored energy.
         */
        private static final String[] LUA_COMPUTER_DATA_TAG_NAMES =
            {"machine", "eeprom", "disk", "diskAddress", "energy"};

        /**
         * A screen keeps its addresses and what was on it, so an operating system that recorded
         * which display it was using still finds it.
         */
        private static final String[] LUA_SCREEN_DATA_TAG_NAMES = {"screen", "keyboard"};

        private LootTable.Builder droppingWithData(final Block block, final String[] tagNames) {
            final CopyNbtFunction.Builder copy = CopyNbtFunction.copyData(ContextNbtProvider.BLOCK_ENTITY);
            for (final String tagName : tagNames) {
                copy.copy(tagName, concat(BLOCK_ENTITY_TAG_NAME_IN_ITEM, tagName),
                    CopyNbtFunction.MergeStrategy.REPLACE);
            }
            return LootTable.lootTable()
                .withPool(applyExplosionCondition(block, LootPool.lootPool()
                    .setRolls(ConstantValue.exactly(1))
                    .add(LootItem.lootTableItem(block).apply(copy))));
        }

        private LootTable.Builder droppingWithInventory(final Block block) {
            return LootTable.lootTable()
                .withPool(applyExplosionCondition(block, LootPool.lootPool()
                    .setRolls(ConstantValue.exactly(1))
                    .add(LootItem.lootTableItem(block)
                        .apply(CopyNbtFunction.copyData(ContextNbtProvider.BLOCK_ENTITY)
                            .copy(ITEMS_TAG_NAME,
                                concat(BLOCK_ENTITY_TAG_NAME_IN_ITEM, ITEMS_TAG_NAME),
                                CopyNbtFunction.MergeStrategy.REPLACE)
                            .copy(ENERGY_TAG_NAME,
                                concat(BLOCK_ENTITY_TAG_NAME_IN_ITEM, ENERGY_TAG_NAME),
                                CopyNbtFunction.MergeStrategy.REPLACE)
                        )
                    )
                ));
        }

        private static String concat(final String... paths) {
            return String.join(".", paths);
        }
    }
}
