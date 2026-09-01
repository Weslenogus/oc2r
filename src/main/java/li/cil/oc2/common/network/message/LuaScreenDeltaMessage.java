/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.network.message;

import li.cil.oc2.common.blockentity.LuaComputerBlockEntity;
import li.cil.oc2.common.blockentity.LuaScreenBlockEntity;
import li.cil.oc2.common.machine.screen.ScreenMode;
import li.cil.oc2.common.network.MessageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Carries what changed on a Lua screen since the last tick.
 * <p>
 * A screen has two buffers and shows one, so the payload is one of two encodings and the mode says
 * which: {@link li.cil.oc2.common.machine.screen.TextBufferDelta} for the character grid, or
 * {@link li.cil.oc2.common.machine.screen.CanvasBufferDelta} for the pixel canvas. Both are opaque
 * here; this only puts them on the wire.
 * <p>
 * Neither buffer is small. A full 160 by 50 text screen is around 32KB and a 320 by 200 canvas is
 * a quarter of a megabyte, so neither is sent whole unless it has to be, and what actually changes
 * between ticks is a line of text or the part of a frame that moved.
 */
public final class LuaScreenDeltaMessage extends AbstractMessage {
    private BlockPos pos;
    private ScreenMode mode;
    private byte[] payload;

    ///////////////////////////////////////////////////////////////////

    public LuaScreenDeltaMessage(final BlockPos pos, final ScreenMode mode, final byte[] payload) {
        this.pos = pos;
        this.mode = mode;
        this.payload = payload;
    }

    public LuaScreenDeltaMessage(final FriendlyByteBuf buffer) {
        super(buffer);
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public void fromBytes(final FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        mode = ScreenMode.fromOrdinal(buffer.readByte());
        payload = buffer.readByteArray();
    }

    @Override
    public void toBytes(final FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeByte(mode.ordinal());
        buffer.writeByteArray(payload);
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected void handleMessage(final NetworkEvent.Context context) {
        // Either kind of display: the computer's own screen, or a monitor block.
        MessageUtils.withClientBlockEntityAt(pos, LuaScreenBlockEntity.class,
            screen -> screen.applyDeltaClient(mode, payload));
        MessageUtils.withClientBlockEntityAt(pos, LuaComputerBlockEntity.class,
            computer -> computer.applyDeltaClient(mode, payload));
    }
}
