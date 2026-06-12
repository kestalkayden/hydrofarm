package com.kestalkayden.hydrofarm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
import net.minecraft.world.phys.BlockHitResult;

/** Abstract parent for any cluster-bed block. Provides shared connection properties
 *  (N/S/E/W booleans that drive frame merging between adjacent beds of the SAME type),
 *  the redistribute-on-place hook, and the ticker that delegates to
 *  {@link AbstractClusterBedBlockEntity#serverTick}. Subclasses add their own bed-specific
 *  state properties (planter quadrants, stall occupancy, etc.) and interaction logic. */
public abstract class AbstractClusterBedBlock extends BaseEntityBlock {

    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty EAST  = BlockStateProperties.EAST;
    public static final BooleanProperty WEST  = BlockStateProperties.WEST;

    protected AbstractClusterBedBlock(Properties properties) {
        super(properties);
        registerDefaultState(connectionDefaults(stateDefinition.any()));
    }

    /** Subclasses call this from their own registerDefaultState chain to apply the connection
     *  defaults (all four directions = false). Public so subclasses in other packages can use it. */
    protected BlockState connectionDefaults(BlockState state) {
        return state
            .setValue(NORTH, false).setValue(SOUTH, false)
            .setValue(EAST, false).setValue(WEST, false);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, SOUTH, EAST, WEST);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        LevelAccessor level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        return defaultBlockState()
            .setValue(NORTH, isSameBedType(level.getBlockState(pos.north())))
            .setValue(SOUTH, isSameBedType(level.getBlockState(pos.south())))
            .setValue(EAST,  isSameBedType(level.getBlockState(pos.east())))
            .setValue(WEST,  isSameBedType(level.getBlockState(pos.west())));
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess scheduledTick,
                                      BlockPos pos, Direction direction, BlockPos neighborPos,
                                      BlockState neighborState, RandomSource random) {
        BooleanProperty prop = propertyForDirection(direction);
        if (prop == null) return state;
        boolean connected = isSameBedType(neighborState);
        if (state.getValue(prop) != connected) {
            // A same-type neighbor just appeared or vanished, so cluster membership may have changed.
            // Drop every bed's cached cluster (see AbstractClusterBedBlockEntity#findCluster). This
            // hook already runs on both sides — it's what visually merges adjacent bed frames.
            AbstractClusterBedBlockEntity.bumpClusterEpoch();
        }
        return state.setValue(prop, connected);
    }

    protected static BooleanProperty propertyForDirection(Direction dir) {
        return switch (dir) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case EAST  -> EAST;
            case WEST  -> WEST;
            default    -> null;
        };
    }

    /** A neighbor state belongs in this block's cluster iff it's the SAME block instance.
     *  Each subclass (hydroponics, tree, husbandry, butcher) is its own block type, so cluster
     *  identity comes for free. */
    protected boolean isSameBedType(BlockState state) {
        return state.is(this);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.isClientSide() && !oldState.is(state.getBlock())) {
            AbstractClusterBedBlockEntity.redistributeCluster(level, pos, state.getBlock());
        }
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                   BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof AbstractClusterBedBlockEntity bed) {
                bed.serverTick((ServerLevel) lvl, pos, st);
            }
        };
    }

    @Override
    protected final InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                Player player, InteractionHand hand, BlockHitResult hit) {
        // Shared across every bed type: fill a held bucket/bottle from the cluster's output-fluid
        // pool (milk -> bucket on husbandry; liquid XP -> bottle o' enchanting on the others),
        // reusing the Liquid Tank's exact fluid<->container logic. Falls through to the bed's own
        // item handling when the held item isn't a fillable container for the stored fluid.
        if (level.getBlockEntity(pos) instanceof AbstractClusterBedBlockEntity be) {
            InteractionResult fluid = FluidContainers.tryFill(stack, player, hand, level, pos,
                be.getOutputFluid(), be.clusterXpMb(), mb -> be.extractLiquidXpFromCluster(mb));
            if (fluid != InteractionResult.PASS) return fluid;
        }
        return useItemOnBed(stack, state, level, pos, player, hand, hit);
    }

    /** Bed-specific item interaction (planter modules, captured animals). Runs after the shared
     *  bucket/bottle fluid extraction; the default defers to the empty-hand path (which opens the
     *  GUI), so a subclass that doesn't take held items needs no override. */
    protected InteractionResult useItemOnBed(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                             Player player, InteractionHand hand, BlockHitResult hit) {
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }
}
