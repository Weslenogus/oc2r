/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.block;

import li.cil.oc2.client.gui.LuaTerminalScreens;
import li.cil.oc2.common.blockentity.BlockEntities;
import li.cil.oc2.common.blockentity.LuaScreenBlockEntity;
import li.cil.oc2.common.blockentity.TickableBlockEntity;
import li.cil.oc2.common.integration.Wrenches;
import li.cil.oc2.common.util.VoxelShapeUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;

/**
 * A screen for the OpenComputers 1 compatible runtime: a character grid a graphics card binds to.
 * <p>
 * Right clicking opens the terminal rather than typing at the block. A Tier 3 screen is 160 by 50
 * characters, which is unreadable rendered on the side of a block from across a room, and MineOS is
 * a desktop that expects a mouse; a full window is the only way either is usable.
 */
public final class LuaScreenBlock extends HorizontalDirectionalBlock implements EntityBlock {
    // A whole block, with the screen's indent bitten out of the front. The same shape the Monitor
    // uses, and for the same reason: a shallow panel leaves most of the block empty, and Minecraft
    // will happily let you place something else inside what looks like a solid screen.
    private static final VoxelShape NEG_Z_SHAPE = Shapes.or(
        Block.box(0, 0, 1, 16, 16, 16),  // main body
        Block.box(0, 15, 0, 16, 16, 1),  // across top
        Block.box(0, 0, 0, 16, 4, 1),    // across bottom
        Block.box(0, 0, 0, 2, 16, 1),    // up left
        Block.box(14, 0, 0, 16, 16, 1)   // up right
    );
    private static final VoxelShape NEG_X_SHAPE = VoxelShapeUtils.rotateHorizontalClockwise(NEG_Z_SHAPE);
    private static final VoxelShape POS_Z_SHAPE = VoxelShapeUtils.rotateHorizontalClockwise(NEG_X_SHAPE);
    private static final VoxelShape POS_X_SHAPE = VoxelShapeUtils.rotateHorizontalClockwise(POS_Z_SHAPE);

    ///////////////////////////////////////////////////////////////////

    public LuaScreenBlock() {
        super(Properties
            .of()
            .mapColor(MapColor.METAL)
            .sound(SoundType.METAL)
            .lightLevel(state -> 5)
            .strength(1.5f, 6.0f));
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    ///////////////////////////////////////////////////////////////////

    @SuppressWarnings("deprecation")
    @Override
    public VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case NORTH -> NEG_Z_SHAPE;
            case SOUTH -> POS_Z_SHAPE;
            case WEST -> NEG_X_SHAPE;
            default -> POS_X_SHAPE;
        };
    }

    @SuppressWarnings("deprecation")
    @Override
    public InteractionResult use(final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final BlockHitResult hit) {
        if (Wrenches.isWrench(player.getItemInHand(hand))) {
            return super.use(state, level, pos, player, hand, hit);
        }

        if (level.isClientSide()) {
            final BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof final LuaScreenBlockEntity screen) {
                // Through DistExecutor so the client-only screen class is never resolved on a
                // dedicated server, where loading it would fail at verification time.
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> LuaTerminalScreens.open(screen));
            }
        }

        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        return super.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    ///////////////////////////////////////////////////////////////////
    // EntityBlock

    @Nullable
    @Override
    public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
        return BlockEntities.LUA_SCREEN.get().create(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(final Level level, final BlockState state, final BlockEntityType<T> type) {
        return TickableBlockEntity.createServerTicker(level, type, BlockEntities.LUA_SCREEN.get());
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FACING);
    }
}
