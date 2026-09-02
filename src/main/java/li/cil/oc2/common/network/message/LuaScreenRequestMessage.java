/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.network.message;

import li.cil.oc2.common.blockentity.LuaScreenBlockEntity;
import li.cil.oc2.common.network.MessageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Asks the server for a complete copy of a screen.
 * <p>
 * Deltas describe changes, so a client that has just started tracking a block has nothing to apply
 * them to. Rather than putting the whole buffer in the chunk update tag, where it would be resent
 * on every chunk sync, the client asks once when it first needs to draw the screen.
 */
public final class LuaScreenRequestMessage extends AbstractMessage {
    private BlockPos pos;

    ///////////////////////////////////////////////////////////////////

    public LuaScreenRequestMessage(final BlockPos pos) {
        this.pos = pos;
    }

    public LuaScreenRequestMessage(final FriendlyByteBuf buffer) {
        super(buffer);
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public void fromBytes(final FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
    }

    @Override
    public void toBytes(final FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected void handleMessage(final NetworkEvent.Context context) {
        // Range checked, so a client cannot use this to read screens across the world.
        MessageUtils.withNearbyServerBlockEntityForInteraction(context, pos, LuaScreenBlockEntity.class,
            (player, screen) -> screen.sendFullSync(player));
    }
}
