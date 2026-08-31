/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.blockentity;

import li.cil.oc2.common.machine.components.KeyboardComponent;
import li.cil.oc2.common.machine.components.ScreenComponent;
import li.cil.oc2.common.machine.screen.TextBuffer;
import li.cil.oc2.common.machine.screen.TextBufferDelta;
import li.cil.oc2.common.machine.serialization.MachineSerialization;
import li.cil.oc2.common.network.Network;
import li.cil.oc2.common.network.message.LuaScreenDeltaMessage;
import li.cil.oc2.common.util.NBTTagIds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The display half of an OpenComputers 1 computer: a character grid a graphics card can bind to.
 * <p>
 * Both sides hold a {@link ScreenComponent}, but for different reasons. On the server it is a real
 * component on a machine's bus, edited by {@code gpu} calls. On the client it is only a render
 * target, kept in step by the deltas this sends.
 * <p>
 * Only what changed goes on the wire, and only when something did. A screen showing a blinking
 * cursor costs a handful of bytes per second; one that has not been drawn to costs nothing at all.
 */
public final class LuaScreenBlockEntity extends ModBlockEntity implements TickableBlockEntity {
    private static final String SCREEN_TAG_NAME = "screen";
    private static final String KEYBOARD_TAG_NAME = "keyboard";

    private final ScreenComponent screen;
    private final KeyboardComponent keyboard;

    ///////////////////////////////////////////////////////////////////

    public LuaScreenBlockEntity(final BlockPos pos, final BlockState state) {
        super(BlockEntities.LUA_SCREEN.get(), pos, state);

        // Addresses are replaced by the saved ones in load(); these only stand in for a screen
        // that has never been placed before.
        screen = new ScreenComponent(java.util.UUID.randomUUID().toString());
        keyboard = new KeyboardComponent(java.util.UUID.randomUUID().toString());
        keyboard.setScreen(screen);
    }

    ///////////////////////////////////////////////////////////////////

    public ScreenComponent getScreen() {
        return screen;
    }

    public KeyboardComponent getKeyboard() {
        return keyboard;
    }

    /**
     * The buffer the client renders. Also the buffer the server draws into; which one this is
     * depends on which side the block entity lives on.
     */
    public TextBuffer getBuffer() {
        return screen.getBuffer();
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public void serverTick() {
        if (level == null) {
            return;
        }

        final byte[] payload;
        synchronized (screen.getLock()) {
            final TextBuffer buffer = screen.getBuffer();
            if (!buffer.isDirty()) {
                return;
            }
            payload = TextBufferDelta.encode(buffer);
            // Cleared only after encoding, so a frame cannot be lost between the two.
            buffer.clearDirty();
        }

        if (payload.length > 0) {
            Network.sendToClientsTrackingBlockEntity(new LuaScreenDeltaMessage(this, payload), this);
        }
    }

    /**
     * Sends the whole screen to one player, for a client that has just started tracking this block
     * and has nothing to apply deltas to.
     */
    public void sendFullSync(final ServerPlayer player) {
        final byte[] payload;
        synchronized (screen.getLock()) {
            final TextBuffer buffer = screen.getBuffer();
            buffer.markAllDirty();
            payload = TextBufferDelta.encode(buffer);
            buffer.clearDirty();
        }

        if (payload.length > 0) {
            Network.sendToClient(new LuaScreenDeltaMessage(this, payload), player);
        }
    }

    /**
     * Delivers an input signal to every machine this screen is attached to.
     * <p>
     * Attachment is checked rather than assumed: a screen next to two computers belongs to
     * whichever ones actually have it on their bus, and a player typing at it should not be
     * driving a machine that cannot see it.
     */
    public void signalAttachedMachines(final String name, final Object... args) {
        if (level == null || level.isClientSide()) {
            return;
        }

        final String address = screen.getComponentAddress();
        for (final net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            if (level.getBlockEntity(getBlockPos().relative(direction))
                instanceof final LuaComputerBlockEntity computer
                && computer.getMachine().getComponent(address).isPresent()) {
                computer.getMachine().signal(name, args);
            }
        }
    }

    /**
     * The address input events are attributed to, which differs by kind: key events come from the
     * keyboard, mouse events from the screen itself.
     */
    public String getKeyboardAddress() {
        return keyboard.getComponentAddress();
    }

    public String getScreenAddress() {
        return screen.getComponentAddress();
    }

    /**
     * Applies a delta received from the server. Client side only.
     */
    public void applyDeltaClient(final byte[] payload) {
        synchronized (screen.getLock()) {
            TextBufferDelta.apply(payload, screen.getBuffer());
        }
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected void saveAdditional(final CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put(SCREEN_TAG_NAME, MachineSerialization.serialize(screen));
        tag.putString(KEYBOARD_TAG_NAME, keyboard.getComponentAddress());
    }

    @Override
    public void load(final CompoundTag tag) {
        super.load(tag);
        if (tag.contains(SCREEN_TAG_NAME, NBTTagIds.TAG_COMPOUND)) {
            MachineSerialization.deserialize(tag.getCompound(SCREEN_TAG_NAME), screen);
        }
        // The keyboard's address matters as much as the screen's: it is what key_down signals are
        // attributed to, and an operating system may have bound its input handling to it.
        final String keyboardAddress = tag.getString(KEYBOARD_TAG_NAME);
        if (!keyboardAddress.isEmpty()) {
            keyboard.setComponentAddress(keyboardAddress);
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        // The buffer is deliberately left out of the chunk update tag. It can be 32KB, it would be
        // resent every time the chunk syncs, and the client asks for a full frame on load anyway.
        return new CompoundTag();
    }
}
