/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.container;

import li.cil.oc2.common.block.LuaComputerBlock;
import li.cil.oc2.common.blockentity.LuaComputerBlockEntity;
import li.cil.oc2.common.blockentity.LuaScreenBlockEntity;
import li.cil.oc2.common.item.Items;
import li.cil.oc2.common.network.Network;
import li.cil.oc2.common.network.message.LuaScreenPowerMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkHooks;

/**
 * The window right clicking a Lua computer opens, built like the RISC-V computer's: the same
 * background, the same sidebar and power button, the same player inventory underneath.
 * <p>
 * The difference is that its slots are locked. A machine an operating system such as MineOS can
 * rely on is a fixed configuration - the processor, memory, graphics card, canvas card and disk
 * are part of the block - so the parts are shown rather than installed. Making them removable
 * would mean a computer that boots or does not depending on what a player happened to leave in it,
 * which is exactly the failure mode this machine exists to avoid.
 * <p>
 * The screen slots are the exception in spirit if not in mechanism: they fill as screens are placed
 * against the block, because that part genuinely is up to the player, and a case with nothing
 * against it is the one mistake this arrangement invites.
 */
public final class LuaComputerContainer extends AbstractContainer {
    /**
     * Slot positions, matching the holes in the container background so the art lines up. Taken
     * from {@link ComputerInventoryContainer}, which is the same picture.
     */
    private static final int MEMORY_X = 64, MEMORY_Y = 24;
    private static final int CPU_X = 64, CPU_Y = 52;
    private static final int EEPROM_X = 64, EEPROM_Y = 78;
    private static final int DISK_X = 100, DISK_Y = 60;
    private static final int SCREEN_X = 118, SCREEN_Y = 60;
    private static final int KEYBOARD_X = 118, KEYBOARD_Y = 78;

    private static final int PARTS = 6;

    ///////////////////////////////////////////////////////////////////

    public static void createServer(final LuaComputerBlockEntity computer, final ServerPlayer player) {
        NetworkHooks.openScreen(player, new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.translatable(computer.getBlockState().getBlock().getDescriptionId());
            }

            @Override
            public AbstractContainerMenu createMenu(final int id, final Inventory inventory, final Player player) {
                return new LuaComputerContainer(id, player, computer.getLevel(), computer.getBlockPos());
            }
        }, computer.getBlockPos());
    }

    public static LuaComputerContainer createClient(final int id, final Inventory playerInventory, final FriendlyByteBuf data) {
        final BlockPos pos = data.readBlockPos();
        final Level level = playerInventory.player.level();
        final BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof LuaComputerBlockEntity) {
            return new LuaComputerContainer(id, playerInventory.player, level, pos);
        }

        throw new IllegalArgumentException();
    }

    ///////////////////////////////////////////////////////////////////

    private final Level level;
    private final BlockPos pos;
    private final SimpleContainer parts = new SimpleContainer(PARTS);

    ///////////////////////////////////////////////////////////////////

    private LuaComputerContainer(final int id, final Player player, final Level level, final BlockPos pos) {
        super(Containers.LUA_COMPUTER.get(), id);
        this.level = level;
        this.pos = pos;

        refreshParts();

        addSlot(new LockedSlot(parts, 0, MEMORY_X, MEMORY_Y));
        addSlot(new LockedSlot(parts, 1, CPU_X, CPU_Y));
        addSlot(new LockedSlot(parts, 2, EEPROM_X, EEPROM_Y));
        addSlot(new LockedSlot(parts, 3, DISK_X, DISK_Y));
        addSlot(new LockedSlot(parts, 4, SCREEN_X, SCREEN_Y));
        addSlot(new LockedSlot(parts, 5, KEYBOARD_X, KEYBOARD_Y));

        createPlayerInventoryAndHotbarSlots(player.getInventory(), 8, 115);
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * How many screens are touching the computer. Counted rather than stored so the window is
     * right the moment one is placed or broken, without a packet to say so.
     */
    public int getAttachedScreenCount() {
        int count = 0;
        for (final Direction direction : Direction.values()) {
            if (level.getBlockEntity(pos.relative(direction)) instanceof LuaScreenBlockEntity) {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether the machine is running, read from the block state's lit flag: the machine itself is
     * on the server, and that flag is the part of it already synchronized to the client.
     */
    public boolean isMachineRunning() {
        final BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof LuaComputerBlock && state.getValue(LuaComputerBlock.LIT);
    }

    public void sendPowerStateToServer(final boolean value) {
        Network.sendToServer(new LuaScreenPowerMessage(pos, value));
    }

    public BlockPos getPos() {
        return pos;
    }

    public Level getLevel() {
        return level;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public void broadcastChanges() {
        // The screen and keyboard counts follow the blocks around the computer rather than an
        // inventory, so nothing marks them changed; refreshing here keeps them honest while the
        // window is open.
        refreshParts();
        super.broadcastChanges();
    }

    @Override
    public boolean stillValid(final Player player) {
        return level.getBlockEntity(pos) instanceof LuaComputerBlockEntity
            && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64;
    }

    ///////////////////////////////////////////////////////////////////

    private void refreshParts() {
        parts.setItem(0, new ItemStack(Items.MEMORY_LARGE.get()));
        parts.setItem(1, new ItemStack(Items.CPU_TIER_3.get()));
        parts.setItem(2, new ItemStack(Items.FLASH_MEMORY.get()));
        parts.setItem(3, new ItemStack(Items.HARD_DRIVE_EXTRA_LARGE.get()));

        final int screens = getAttachedScreenCount();
        if (screens > 0) {
            parts.setItem(4, new ItemStack(Items.LUA_SCREEN.get(), screens));
            parts.setItem(5, new ItemStack(Items.KEYBOARD.get(), screens));
        } else {
            parts.setItem(4, ItemStack.EMPTY);
            parts.setItem(5, ItemStack.EMPTY);
        }
    }
}
