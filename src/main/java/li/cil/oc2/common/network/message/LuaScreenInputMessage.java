/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.network.message;

import li.cil.oc2.common.blockentity.LuaScreenView;
import li.cil.oc2.common.machine.screen.CanvasBuffer;
import li.cil.oc2.common.machine.screen.ScreenMode;
import li.cil.oc2.common.machine.screen.TextBuffer;
import li.cil.oc2.common.network.MessageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Keyboard and mouse input from a player with a Lua terminal open.
 * <p>
 * One message type for every kind of input, because they all end the same way: as a signal on the
 * machines the screen is attached to. Which address the signal is attributed to differs, though —
 * key events come from the keyboard component, mouse events from the screen — and that is what
 * lets an operating system tell one input device from another.
 */
public final class LuaScreenInputMessage extends AbstractMessage {
    public enum Type {
        KEY_DOWN,
        KEY_UP,
        CLIPBOARD,
        TOUCH,
        DRAG,
        DROP,
        SCROLL,
    }

    /**
     * How much pasted text is accepted in one go. A clipboard signal is player-supplied and would
     * otherwise be an easy way to make a server allocate whatever a client felt like sending.
     */
    private static final int MAX_CLIPBOARD_LENGTH = 4096;

    private BlockPos pos;
    private Type type;
    private int codePoint;
    private int keyCode;
    private int x;
    private int y;
    private String text = "";

    ///////////////////////////////////////////////////////////////////

    private LuaScreenInputMessage() {
    }

    public LuaScreenInputMessage(final FriendlyByteBuf buffer) {
        super(buffer);
    }

    public static LuaScreenInputMessage key(final LuaScreenView screen, final boolean down,
                                            final int codePoint, final int keyCode) {
        final LuaScreenInputMessage message = new LuaScreenInputMessage();
        message.pos = screen.getViewPos();
        message.type = down ? Type.KEY_DOWN : Type.KEY_UP;
        message.codePoint = codePoint;
        message.keyCode = keyCode;
        return message;
    }

    public static LuaScreenInputMessage clipboard(final LuaScreenView screen, final String value) {
        final LuaScreenInputMessage message = new LuaScreenInputMessage();
        message.pos = screen.getViewPos();
        message.type = Type.CLIPBOARD;
        message.text = value;
        return message;
    }

    public static LuaScreenInputMessage mouse(final LuaScreenView screen, final Type type,
                                              final int x, final int y, final int button) {
        final LuaScreenInputMessage message = new LuaScreenInputMessage();
        message.pos = screen.getViewPos();
        message.type = type;
        message.x = x;
        message.y = y;
        message.keyCode = button;
        return message;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public void fromBytes(final FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        type = buffer.readEnum(Type.class);
        codePoint = buffer.readVarInt();
        keyCode = buffer.readVarInt();
        x = buffer.readVarInt();
        y = buffer.readVarInt();
        text = buffer.readUtf(MAX_CLIPBOARD_LENGTH);
    }

    @Override
    public void toBytes(final FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeEnum(type);
        buffer.writeVarInt(codePoint);
        buffer.writeVarInt(keyCode);
        buffer.writeVarInt(x);
        buffer.writeVarInt(y);
        buffer.writeUtf(text, MAX_CLIPBOARD_LENGTH);
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected void handleMessage(final NetworkEvent.Context context) {
        // Either kind of display; the block entity behind the position decides which.
        MessageUtils.withNearbyServerBlockEntityForInteraction(context, pos, BlockEntity.class,
            (player, blockEntity) -> {
                if (blockEntity instanceof final LuaScreenView view) {
                    dispatch(player, view);
                }
            });
    }

    private void dispatch(final ServerPlayer player, final LuaScreenView screen) {
        final String name = player.getGameProfile().getName();

        switch (type) {
            case KEY_DOWN -> screen.signalMachines("key_down",
                screen.getKeyboardAddress(), (double) codePoint, (double) keyCode, name);
            case KEY_UP -> screen.signalMachines("key_up",
                screen.getKeyboardAddress(), (double) codePoint, (double) keyCode, name);
            case CLIPBOARD -> {
                if (!text.isEmpty()) {
                    screen.signalMachines("clipboard",
                        screen.getKeyboardAddress(), text, name);
                }
            }
            case TOUCH, DRAG, DROP -> {
                final int[] cell = clampToScreen(screen, x, y);
                screen.signalMachines(type.name().toLowerCase(),
                    screen.getScreenAddress(), (double) cell[0], (double) cell[1],
                    (double) keyCode, name);
            }
            case SCROLL -> {
                final int[] cell = clampToScreen(screen, x, y);
                // The button field carries the scroll direction here, positive for up.
                screen.signalMachines("scroll",
                    screen.getScreenAddress(), (double) cell[0], (double) cell[1],
                    (double) Integer.signum(keyCode), name);
            }
        }
    }

    /**
     * Clamps a cell coordinate to the screen and converts it to the one based coordinates Lua uses.
     * <p>
     * The client sends where it thinks the cursor is, and the client is not to be trusted about
     * that. A program reading a touch out of bounds would index past its own buffer.
     */
    private static int[] clampToScreen(final LuaScreenView screen, final int x, final int y) {
        synchronized (screen.getScreen().getLock()) {
            // Whichever buffer is on show decides what a coordinate means: a cell in text mode, a
            // pixel in canvas mode. Clamping against the wrong one would put a click on a 320 by
            // 200 canvas somewhere in the first fifty rows.
            final int width;
            final int height;
            if (screen.getScreen().getMode() == ScreenMode.CANVAS) {
                final CanvasBuffer canvas = screen.getScreen().getOrCreateCanvas();
                width = canvas.getWidth();
                height = canvas.getHeight();
            } else {
                final TextBuffer buffer = screen.getBuffer();
                width = buffer.getWidth();
                height = buffer.getHeight();
            }

            return new int[]{
                Math.max(1, Math.min(width, x + 1)),
                Math.max(1, Math.min(height, y + 1)),
            };
        }
    }
}
