/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.item;

import li.cil.oc2.common.blockentity.LuaComputerBlockEntity;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;

/**
 * What a mined Lua Computer carries in its NBT, and how to trim it down for the network.
 * <p>
 * Kept apart from {@link LuaComputerItem} on purpose. This is the part worth testing - getting it
 * wrong deletes a player's disk - and an item class cannot be loaded outside a running game, while
 * this can.
 */
public final class LuaComputerItemTag {
    /**
     * Where a block item keeps the block entity's saved data. Spelled out rather than taken from
     * {@code BlockItem}, which cannot be loaded without the game's registries behind it.
     */
    public static final String BLOCK_ENTITY_TAG = "BlockEntityTag";

    private LuaComputerItemTag() {
    }

    /**
     * Returns a copy of a saved computer's tag with the disk and the machine state taken out.
     * <p>
     * Mining a computer keeps what was installed on it, so the whole file system rides in the
     * item's NBT - several megabytes of it, on an item stack that would otherwise be sent to every
     * client that can see it. Minecraft refuses to read a tag over two megabytes off the wire, and
     * a client has no use for the contents of a disk it cannot read either way.
     * <p>
     * A copy, because what {@code getShareTag} is handed is the stack's own tag: trimming it in
     * place would not be deciding what goes on the wire, it would be deleting the disk.
     *
     * @param tag the tag to trim; may be {@code null}.
     * @return the trimmed copy, or the argument if there was nothing to trim.
     */
    @Nullable
    public static CompoundTag withoutBulkData(@Nullable final CompoundTag tag) {
        if (tag == null || !tag.contains(BLOCK_ENTITY_TAG)) {
            return tag;
        }

        final CompoundTag shared = tag.copy();
        final CompoundTag blockEntity = shared.getCompound(BLOCK_ENTITY_TAG);
        blockEntity.remove(LuaComputerBlockEntity.DISK_TAG_NAME);
        blockEntity.remove(LuaComputerBlockEntity.MACHINE_TAG_NAME);
        return shared;
    }
}
