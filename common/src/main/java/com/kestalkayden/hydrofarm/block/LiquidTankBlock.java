package com.kestalkayden.hydrofarm.block;

import com.mojang.serialization.MapCodec;

import com.kestalkayden.hydrofarm.HydrofarmRefs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;

public class LiquidTankBlock extends BaseEntityBlock {

    public static final MapCodec<LiquidTankBlock> CODEC = simpleCodec(LiquidTankBlock::new);

    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty EAST  = BlockStateProperties.EAST;
    public static final BooleanProperty WEST  = BlockStateProperties.WEST;
    public static final BooleanProperty UP    = BlockStateProperties.UP;
    public static final BooleanProperty DOWN  = BlockStateProperties.DOWN;
    /** Drives Properties.lightLevel — the BE flips this when a glowing fluid is stored. Both
     *  loaders register a flat lightLevel(state -> LIT ? 12 : 0); we use a blockstate-driven
     *  boolean + an explicit light-engine checkBlock from the BE's sync(). */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public LiquidTankBlock(Properties properties) {
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
            .setValue(NORTH, isLiquidTank(level.getBlockState(pos.north())))
            .setValue(SOUTH, isLiquidTank(level.getBlockState(pos.south())))
            .setValue(EAST,  isLiquidTank(level.getBlockState(pos.east())))
            .setValue(WEST,  isLiquidTank(level.getBlockState(pos.west())))
            .setValue(UP,    isLiquidTank(level.getBlockState(pos.above())))
            .setValue(DOWN,  isLiquidTank(level.getBlockState(pos.below())));
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess scheduledTick,
                                      BlockPos pos, Direction direction, BlockPos neighborPos,
                                      BlockState neighborState, RandomSource random) {
        BooleanProperty prop = propertyForDirection(direction);
        if (prop == null) return state;
        boolean connected = isLiquidTank(neighborState);
        if (state.getValue(prop) != connected) {
            // A neighbour tank just appeared or vanished, so cluster membership may have changed.
            // Drop every tank's cached cluster (see LiquidTankBlockEntity#findCluster). This hook
            // runs on both sides — it's what visually merges adjacent tank fluid this frame.
            LiquidTankBlockEntity.bumpClusterEpoch();
        }
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

    private static boolean isLiquidTank(BlockState state) {
        return state.is(HydrofarmRefs.LIQUID_TANK.get());
    }

    /** Redistribute the cluster's water bottom-up when a tank joins it, so the new member shares
     *  the existing pool rather than sitting empty next to a full neighbor. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.isClientSide() && !oldState.is(state.getBlock())) {
            LiquidTankBlockEntity.redistributeCluster(level, pos);
        }
    }

    /** The "preserve water on break" logic lives in {@link LiquidTankBlockEntity#preRemoveSideEffects}
     *  — in 26.1 the old Block.onRemove hook was split, and the BE's preRemoveSideEffects is the
     *  one that fires with the BE still queryable. */

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LiquidTankBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof LiquidTankBlockEntity be)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }

        // Filled bucket -> insert its fluid (water/lava/milk). A fluid-mismatch is rejected by
        // canAcceptFluid (a lava bucket on a water tank does nothing).
        Fluid bucketFluid = FluidContainers.bucketContentFluid(stack.getItem());
        if (bucketFluid != Fluids.EMPTY && canAcceptFluid(be, bucketFluid)) {
            if (!level.isClientSide()) {
                be.insert(bucketFluid, FluidContainers.BUCKET_MB);
                if (!player.getAbilities().instabuild) {
                    FluidContainers.replaceHandItem(player, hand, stack, new ItemStack(Items.BUCKET));
                }
                level.playSound(null, pos, FluidContainers.emptySoundFor(bucketFluid),
                    SoundSource.BLOCKS, 1.0F, 1.0F);
            }
            return InteractionResult.SUCCESS;
        }

        // Empty bucket / glass bottle -> fill from the stored fluid. Shared with the cluster beds
        // via FluidContainers so the amounts and fluid->item mappings stay in lockstep.
        InteractionResult filled = FluidContainers.tryFill(stack, player, hand, level, pos,
            be.getFluid(), be.getAmountMb(), mb -> be.extract(be.getFluid(), mb));
        if (filled != InteractionResult.PASS) return filled;

        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    /** Tank accepts {@code fluid} when its whole CLUSTER is empty OR already holds the same fluid, and
     *  there's room. Checks {@link LiquidTankBlockEntity#clusterFluid()} (not just this tank) so a
     *  bucket can't fill an empty member of a different-fluid cluster and re-mix it. */
    private static boolean canAcceptFluid(LiquidTankBlockEntity be, Fluid fluid) {
        Fluid clusterFluid = be.clusterFluid();
        return (clusterFluid == Fluids.EMPTY || clusterFluid == fluid)
            && be.getRoomMb() >= FluidContainers.BUCKET_MB;
    }
}
