/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.blockentity;

import li.cil.oc2.common.machine.components.KeyboardComponent;
import li.cil.oc2.common.machine.components.ScreenComponent;
import li.cil.oc2.common.machine.screen.ScreenMode;
import li.cil.oc2.common.machine.serialization.MachineSerialization;
import li.cil.oc2.common.util.NBTTagIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * An external display for the OpenComputers 1 computers around it: a character grid a graphics card
 * can bind to, plus the keyboard that comes with it.
 * <p>
 * A {@link LuaComputerBlockEntity} has a screen of its own, so this is not required to use one. It
 * is what you place when one display is not enough - a second view of the same machine, a wall of
 * them, or a screen somewhere the computer is not.
 * <p>
 * Both sides hold a {@link ScreenComponent}, but for different reasons. On the server it is a real
 * component on a machine's bus, edited by {@code gpu} calls. On the client it is only a render
 * target, kept in step by the deltas {@link LuaScreenSync} sends.
 */
public final class LuaScreenBlockEntity extends ModBlockEntity implements TickableBlockEntity, LuaScreenView {
    private static final String SCREEN_TAG_NAME = "screen";
    private static final String KEYBOARD_TAG_NAME = "keyboard";

    private final ScreenComponent screen;
    private final KeyboardComponent keyboard;

    ///////////////////////////////////////////////////////////////////

    public LuaScreenBlockEntity(final BlockPos pos, final BlockState state) {
        super(BlockEntities.LUA_SCREEN.get(), pos, state);

        // Addresses are replaced by the saved ones in load(); these only stand in for a screen
        // that has never been placed before.
        screen = new ScreenComponent(UUID.randomUUID().toString());
        keyboard = new KeyboardComponent(UUID.randomUUID().toString());
        keyboard.setScreen(screen);
    }

    ///////////////////////////////////////////////////////////////////
    // LuaScreenView

    @Override
    public ScreenComponent getScreen() {
        return screen;
    }

    @Override
    public String getKeyboardAddress() {
        return keyboard.getComponentAddress();
    }

    @Override
    public BlockEntity getBlockEntity() {
        return this;
    }

    /**
     * Delivers an input signal to every machine this screen is attached to.
     * <p>
     * Attachment is checked rather than assumed: a screen next to two computers belongs to
     * whichever ones actually have it on their bus, and a player typing at it should not be driving
     * a machine that cannot see it.
     */
    @Override
    public void signalMachines(final String name, final Object... args) {
        forEachAttachedComputer(computer -> computer.getMachine().signal(name, args));
    }

    @Override
    public boolean isMachineRunning() {
        if (level == null) {
            return false;
        }

        // On the client the machine is not here to ask, so the neighbouring computer's block state
        // answers instead - it carries the lit flag, and it is already synchronized.
        for (final Direction direction : Direction.values()) {
            final BlockPos neighbour = getBlockPos().relative(direction);
            if (level.isClientSide()) {
                final BlockState state = level.getBlockState(neighbour);
                if (state.getBlock() instanceof li.cil.oc2.common.block.LuaComputerBlock
                    && state.getValue(li.cil.oc2.common.block.LuaComputerBlock.LIT)) {
                    return true;
                }
            } else if (level.getBlockEntity(neighbour) instanceof final LuaComputerBlockEntity computer
                && computer.isRunning()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void setMachineRunning(final boolean value) {
        forEachAttachedComputer(computer -> {
            if (value) {
                computer.start();
            } else {
                computer.stop();
            }
        });
    }

    ///////////////////////////////////////////////////////////////////

    public KeyboardComponent getKeyboard() {
        return keyboard;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public void serverTick() {
        if (level != null) {
            LuaScreenSync.tick(this);
        }
    }

    public void sendFullSync(final ServerPlayer player) {
        LuaScreenSync.sendFullSync(this, player);
    }

    public void applyDeltaClient(final ScreenMode mode, final byte[] payload) {
        LuaScreenSync.applyDelta(this, mode, payload);
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

    ///////////////////////////////////////////////////////////////////

    /**
     * Runs an action on every computer that actually has this screen on its bus.
     */
    private void forEachAttachedComputer(final java.util.function.Consumer<LuaComputerBlockEntity> action) {
        if (level == null || level.isClientSide()) {
            return;
        }

        final String address = screen.getComponentAddress();
        for (final Direction direction : Direction.values()) {
            if (level.getBlockEntity(getBlockPos().relative(direction))
                instanceof final LuaComputerBlockEntity computer
                && computer.getMachine().getComponent(address).isPresent()) {
                action.accept(computer);
            }
        }
    }
}
