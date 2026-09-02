/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.block;

import li.cil.oc2.client.gui.LuaTerminalScreens;
import li.cil.oc2.common.blockentity.BlockEntities;
import li.cil.oc2.common.blockentity.LuaComputerBlockEntity;
import li.cil.oc2.common.blockentity.LuaScreenBlockEntity;
import li.cil.oc2.common.blockentity.TickableBlockEntity;
import li.cil.oc2.common.config.Config;
import li.cil.oc2.common.integration.Wrenches;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A computer running the OpenComputers 1 compatible Lua runtime, as opposed to
 * {@link ComputerBlock}, which boots Linux on a virtual RISC-V core.
 * <p>
 * Unlike its RISC-V sibling this has no cards to install, because a machine MineOS can run is a
 * fixed configuration. It has no display either: like an OpenComputers computer case it needs a
 * screen block placed against it, which is where both the picture and the keyboard come from.
 */
public final class LuaComputerBlock extends HorizontalDirectionalBlock implements EnergyConsumingBlock, EntityBlock {
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    ///////////////////////////////////////////////////////////////////

    public LuaComputerBlock() {
        super(Properties
            .of()
            .mapColor(MapColor.METAL)
            .sound(SoundType.METAL)
            .lightLevel(state -> state.getValue(LIT) ? 7 : 0)
            .strength(1.5f, 6.0f));
        registerDefaultState(getStateDefinition().any()
            .setValue(FACING, Direction.NORTH)
            .setValue(LIT, false));
    }

    ///////////////////////////////////////////////////////////////////

    @OnlyIn(Dist.CLIENT)
    @Override
    public void appendHoverText(final ItemStack stack, @Nullable final BlockGetter level, final List<Component> tooltip, final TooltipFlag advanced) {
        super.appendHoverText(stack, level, tooltip, advanced);
    }

    @SuppressWarnings("deprecation")
    @Override
    public InteractionResult use(final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final BlockHitResult hit) {
        if (Wrenches.isWrench(player.getItemInHand(hand))) {
            return super.use(state, level, pos, player, hand, hit);
        }

        // The same two gestures the RISC-V computer uses, so one habit works on both: use it to
        // look at the screen, sneak and use it to switch it on or off. Toggling power on a plain
        // right click, which is what this used to do, gives no sign of having done anything - the
        // block lights up and there is nothing to look at.
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide()) {
                final BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity instanceof final LuaComputerBlockEntity computer) {
                    if (computer.isRunning()) {
                        computer.stop();
                    } else {
                        computer.start();
                    }
                }
            }
        } else if (level.isClientSide()) {
            // The computer has no display of its own, so the terminal is opened on whichever screen
            // is against it. With none there is nothing to show, and saying so beats opening an
            // empty window: a computer with no monitor is the one mistake this arrangement invites.
            final LuaScreenBlockEntity screen = findAttachedScreen(level, pos);
            if (screen != null) {
                // Through DistExecutor so the client-only screen class is never resolved on a
                // dedicated server, where loading it would fail at verification time.
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> LuaTerminalScreens.open(screen));
            } else {
                player.displayClientMessage(
                    Component.translatable("gui.oc2r.lua_computer.no_screen"), true);
            }
        }

        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        return super.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /**
     * The first screen block touching this computer, or {@code null} if it has none.
     */
    @Nullable
    private static LuaScreenBlockEntity findAttachedScreen(final Level level, final BlockPos pos) {
        for (final Direction direction : Direction.values()) {
            if (level.getBlockEntity(pos.relative(direction))
                instanceof final LuaScreenBlockEntity screen) {
                return screen;
            }
        }
        return null;
    }

    ///////////////////////////////////////////////////////////////////
    // EntityBlock

    @Nullable
    @Override
    public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
        return BlockEntities.LUA_COMPUTER.get().create(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(final Level level, final BlockState state, final BlockEntityType<T> type) {
        return TickableBlockEntity.createServerTicker(level, type, BlockEntities.LUA_COMPUTER.get());
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FACING, LIT);
    }

    @Override
    public int getEnergyConsumption() {
        return Config.computerEnergyPerTick;
    }
}
