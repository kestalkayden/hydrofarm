package com.kestalkayden.hydrofarm.block;

import java.util.Map;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 2-voxel-tall bookend, mountable on any of the 6 faces of a neighboring block. FACING is the
 *  direction the grate faces (= the face the player clicked when placing). The plate sits flush
 *  on the opposite side, against the attached block. Sneak-drain works for FACING=UP only;
 *  right-click extraction works on all facings. */
public class XpDrainBlock extends BaseEntityBlock {

    public static final MapCodec<XpDrainBlock> CODEC = simpleCodec(XpDrainBlock::new);

    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    /** True only when the floor-mounted drain has a sneaking survival player above + a valid
     *  target. Wall-mounted variants stay inactive — they're a manual right-click endpoint. */
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    /** 14x2x14 plate, positioned on the side opposite to FACING (against the attached block). */
    private static final Map<Direction, VoxelShape> SHAPES = Map.of(
        Direction.UP,    Block.box(1, 0,  1,  15, 2,  15),
        Direction.DOWN,  Block.box(1, 14, 1,  15, 16, 15),
        Direction.NORTH, Block.box(1, 1,  14, 15, 15, 16),
        Direction.SOUTH, Block.box(1, 1,  0,  15, 15, 2),
        Direction.EAST,  Block.box(0, 1,  1,  2,  15, 15),
        Direction.WEST,  Block.box(14, 1, 1,  16, 15, 15));

    public XpDrainBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.UP)
            .setValue(ACTIVE, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new XpDrainBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof XpDrainBlockEntity xdbe) {
                xdbe.serverTick((ServerLevel) lvl, pos, st);
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof XpDrainBlockEntity be)) {
            return InteractionResult.PASS;
        }
        int returned = be.returnXpToPlayer((ServerLevel) level, pos, player);
        if (returned > 0) {
            level.playSound(null, pos, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 0.5F, 1.0F);
        }
        return InteractionResult.SUCCESS;
    }
}
