/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.network.message;

import li.cil.oc2.common.blockentity.LuaComputerBlockEntity;
import li.cil.oc2.common.blockentity.LuaScreenView;
import li.cil.oc2.common.network.MessageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

/**
 * The power button on a Lua terminal window.
 * <p>
 * The button appears in two places - the terminal window a screen opens, and the computer's own
 * panel - so this carries the position of whichever block was clicked and lets that block work out
 * whose power is being switched: its own, for a computer, or every computer it touches, for a
 * screen. The client never names a machine it cannot see.
 */
public final class LuaScreenPowerMessage extends AbstractMessage {
    private BlockPos pos;
    private boolean running;

    ///////////////////////////////////////////////////////////////////

    public LuaScreenPowerMessage(final LuaScreenView view, final boolean running) {
        this(view.getViewPos(), running);
    }

    /**
     * For the computer's own panel, which has no display to name: the block at this position is
     * the machine itself.
     */
    public LuaScreenPowerMessage(final BlockPos pos, final boolean running) {
        this.pos = pos;
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
                if (blockEntity instanceof final LuaComputerBlockEntity computer) {
                    if (running) {
                        computer.start();
                    } else {
                        computer.stop();
                    }
                } else if (blockEntity instanceof final LuaScreenView view) {
                    view.setMachineRunning(running);
                }
            });
    }
}
