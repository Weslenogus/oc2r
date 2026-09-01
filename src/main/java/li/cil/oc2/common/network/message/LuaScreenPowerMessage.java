/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.network.message;

import li.cil.oc2.common.blockentity.LuaScreenView;
import li.cil.oc2.common.network.MessageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

/**
 * The power button on a Lua terminal window.
 * <p>
 * The window is opened on a display, and the machine behind that display is whatever the block
 * decides: itself, for a computer, or the computers it is attached to, for a monitor. So this
 * carries the display's position and lets the block work out whose power is being switched, rather
 * than having the client name a machine it cannot see.
 */
public final class LuaScreenPowerMessage extends AbstractMessage {
    private BlockPos pos;
    private boolean running;

    ///////////////////////////////////////////////////////////////////

    public LuaScreenPowerMessage(final LuaScreenView view, final boolean running) {
        this.pos = view.getViewPos();
        this.running = running;
    }

    public LuaScreenPowerMessage(final FriendlyByteBuf buffer) {
        super(buffer);
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public void fromBytes(final FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        running = buffer.readBoolean();
    }

    @Override
    public void toBytes(final FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeBoolean(running);
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected void handleMessage(final NetworkEvent.Context context) {
        MessageUtils.withNearbyServerBlockEntityForInteraction(context, pos, BlockEntity.class,
            (player, blockEntity) -> {
                if (blockEntity instanceof final LuaScreenView view) {
                    view.setMachineRunning(running);
                }
            });
    }
}
