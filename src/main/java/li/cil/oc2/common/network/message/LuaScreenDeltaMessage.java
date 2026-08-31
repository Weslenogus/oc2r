/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.network.message;

import li.cil.oc2.common.blockentity.LuaScreenBlockEntity;
import li.cil.oc2.common.network.MessageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Carries the cells of a Lua screen that changed since the last tick.
 * <p>
 * The payload is produced by {@link li.cil.oc2.common.machine.screen.TextBufferDelta}, which is
 * where the encoding lives; this only puts it on the wire. A full 160 by 50 screen is around 32KB
 * of state, so sending it whole every tick is not an option, and what actually changes between
 * ticks is usually a line of text.
 */
public final class LuaScreenDeltaMessage extends AbstractMessage {
    private BlockPos pos;
    private byte[] payload;

    ///////////////////////////////////////////////////////////////////

    public LuaScreenDeltaMessage(final LuaScreenBlockEntity screen, final byte[] payload) {
        this.pos = screen.getBlockPos();
        this.payload = payload;
    }

    public LuaScreenDeltaMessage(final FriendlyByteBuf buffer) {
        super(buffer);
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public void fromBytes(final FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        payload = buffer.readByteArray();
    }

    @Override
    public void toBytes(final FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeByteArray(payload);
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected void handleMessage(final NetworkEvent.Context context) {
        MessageUtils.withClientBlockEntityAt(pos, LuaScreenBlockEntity.class,
            screen -> screen.applyDeltaClient(payload));
    }
}
