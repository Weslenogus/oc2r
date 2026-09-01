/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;

/**
 * The item form of a Lua Computer, which carries the machine's disk.
 * <p>
 * Mining one keeps what was installed on it, which means the whole file system travels in the
 * item's NBT. That is what a player expects of a computer they just mined, and it is also several
 * megabytes on an item stack, so it is kept off the network. {@link LuaComputerItemTag} is where
 * that happens, and why.
 */
public final class LuaComputerItem extends ModBlockItem {
    public LuaComputerItem(final Block block) {
        super(block);
    }

    ///////////////////////////////////////////////////////////////////

    @Nullable
    @Override
    public CompoundTag getShareTag(final ItemStack stack) {
        return LuaComputerItemTag.withoutBulkData(super.getShareTag(stack));
    }
}
