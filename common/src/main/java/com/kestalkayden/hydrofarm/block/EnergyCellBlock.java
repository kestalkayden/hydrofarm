package com.kestalkayden.hydrofarm.block;

import com.mojang.serialization.MapCodec;

import com.kestalkayden.hydrofarm.HydrofarmRefs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
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

/** Energy storage block. Carries the 6 connection booleans (like {@link LiquidTankBlock}) to drive a
 *  connection-aware multipart model — adjacent cells omit their shared face panels and frame edges, so
 *  a cluster renders as one framed battery — and to invalidate the cluster cache only when a real
 *  connection flips. Adjacent cells auto-cluster (the same BFS the tank uses) into one energy pool. */
public class EnergyCellBlock extends BaseEntityBlock {

    public static final MapCodec<EnergyCellBlock> CODEC = simpleCodec(EnergyCellBlock::new);

    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty EAST  = BlockStateProperties.EAST;
    public static final BooleanProperty WEST  = BlockStateProperties.WEST;
    public static final BooleanProperty UP    = BlockStateProperties.UP;
    public static final BooleanProperty DOWN  = BlockStateProperties.DOWN;
    /** Drives Properties.lightLevel — the BE flips this on while the bank holds any energy, so a
     *  charged cell emits a faint glow (same LIT + light-engine pattern as the tank/repulser). */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public EnergyCellBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(NORTH, false).setValue(SOUTH, false)
            .setValue(EAST,  false).setValue(WEST,  false)
            .setValue(UP,    false).setValue(DOWN,  false)
            .setValue(LIT,   false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, SOUTH, EAST, WEST, UP, DOWN, LIT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        LevelAccessor level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        return defaultBlockState()
            .setValue(NORTH, isEnergyCell(level.getBlockState(pos.north())))
            .setValue(SOUTH, isEnergyCell(level.getBlockState(pos.south())))
            .setValue(EAST,  isEnergyCell(level.getBlockState(pos.east())))
            .setValue(WEST,  isEnergyCell(level.getBlockState(pos.west())))
            .setValue(UP,    isEnergyCell(level.getBlockState(pos.above())))
            .setValue(DOWN,  isEnergyCell(level.getBlockState(pos.below())));
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess scheduledTick,
                                      BlockPos pos, Direction direction, BlockPos neighborPos,
                                      BlockState neighborState, RandomSource random) {
        BooleanProperty prop = propertyForDirection(direction);
        if (prop == null) return state;
        boolean connected = isEnergyCell(neighborState);
        // A cell joined or left this side — cluster membership may have changed; drop every cell's
        // cached cluster (mirrors LiquidTankBlock). The flag diff makes this fire only on a real
        // topology change, not on every unrelated neighbour update (crops, redstone, …).
        if (state.getValue(prop) != connected) EnergyCellBlockEntity.bumpClusterEpoch();
        return state.setValue(prop, connected);
    }

    private static BooleanProperty propertyForDirection(Direction dir) {
        return switch (dir) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case EAST  -> EAST;
            case WEST  -> WEST;
            case UP    -> UP;
            case DOWN  -> DOWN;
        };
    }

    private static boolean isEnergyCell(BlockState state) {
        return state.is(HydrofarmRefs.ENERGY_CELL.get());
    }

    /** Rebalance the cluster's energy when a cell joins it, so the new member shares the pool. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.isClientSide() && !oldState.is(state.getBlock())) {
            EnergyCellBlockEntity.redistributeCluster(level, pos);
        }
    }

    /** Break redistribution lives in {@link EnergyCellBlockEntity#preRemoveSideEffects}; the cluster
     *  cache is invalidated by the neighbours' {@link #updateShape} when this block becomes air. */

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EnergyCellBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /** Server-only ticker: drives the faint charge glow by flipping {@link #LIT} when the bank's
     *  stored energy crosses 0 (see {@link EnergyCellBlockEntity#serverTick}). */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                   BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof EnergyCellBlockEntity cell) cell.serverTick((ServerLevel) lvl, pos, st);
        };
    }
}
