package com.kestalkayden.hydrofarm.block;

import com.mojang.serialization.MapCodec;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.platform.HydrofarmPlatform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Pure-topology energy cable — no internal buffer, no BE. Auto-connects visually to adjacent
 *  energy cables and energy pumps. Energy pumps BFS this graph to move energy source → sink.
 *  Mirrors {@link LiquidPipeBlock}. */
public class EnergyPipeBlock extends Block {

    public static final MapCodec<EnergyPipeBlock> CODEC = simpleCodec(EnergyPipeBlock::new);

    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty EAST  = BlockStateProperties.EAST;
    public static final BooleanProperty WEST  = BlockStateProperties.WEST;
    public static final BooleanProperty UP    = BlockStateProperties.UP;
    public static final BooleanProperty DOWN  = BlockStateProperties.DOWN;

    private static final VoxelShape CORE = Block.box(6, 6, 6, 10, 10, 10);
    private static final VoxelShape ARM_NORTH = Block.box(6, 6, 0, 10, 10, 6);
    private static final VoxelShape ARM_SOUTH = Block.box(6, 6, 10, 10, 10, 16);
    private static final VoxelShape ARM_WEST  = Block.box(0, 6, 6, 6, 10, 10);
    private static final VoxelShape ARM_EAST  = Block.box(10, 6, 6, 16, 10, 10);
    private static final VoxelShape ARM_DOWN  = Block.box(6, 0, 6, 10, 6, 10);
    private static final VoxelShape ARM_UP    = Block.box(6, 10, 6, 10, 16, 10);

    public EnergyPipeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(NORTH, false).setValue(SOUTH, false)
            .setValue(EAST,  false).setValue(WEST,  false)
            .setValue(UP,    false).setValue(DOWN,  false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, SOUTH, EAST, WEST, UP, DOWN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        LevelAccessor level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        return defaultBlockState()
            .setValue(NORTH, canConnectTo(level, pos.north(), level.getBlockState(pos.north()), Direction.NORTH))
            .setValue(SOUTH, canConnectTo(level, pos.south(), level.getBlockState(pos.south()), Direction.SOUTH))
            .setValue(EAST,  canConnectTo(level, pos.east(),  level.getBlockState(pos.east()),  Direction.EAST))
            .setValue(WEST,  canConnectTo(level, pos.west(),  level.getBlockState(pos.west()),  Direction.WEST))
            .setValue(UP,    canConnectTo(level, pos.above(), level.getBlockState(pos.above()), Direction.UP))
            .setValue(DOWN,  canConnectTo(level, pos.below(), level.getBlockState(pos.below()), Direction.DOWN));
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess scheduledTick,
                                      BlockPos pos, Direction direction, BlockPos neighborPos,
                                      BlockState neighborState, RandomSource random) {
        BooleanProperty prop = propertyForDirection(direction);
        if (prop == null) return state;
        boolean connected = canConnectTo(level, neighborPos, neighborState, direction);
        if (state.getValue(prop) != connected) TransportNetwork.bump();
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

    /** Connects to other energy cables and to anything that speaks the standard energy capability —
     *  Hydrofarm's own machines AND tech-mod machines/cables (TechReborn, AE2 acceptors, …).
     *  Hydrofarm energy blocks are whitelisted directly (rock-solid, no capability dependency);
     *  everything else is matched by an actual energy-cap probe. {@code updateShape}'s
     *  {@link LevelReader} is a real {@link Level} in practice (Server/ClientLevel); the worldgen
     *  edge (where it isn't) falls through to cable + whitelist only. */
    private static boolean canConnectTo(LevelReader level, BlockPos neighborPos,
                                        BlockState neighborState, Direction directionToNeighbor) {
        if (neighborState.is(HydrofarmRefs.ENERGY_PIPE.get())
            || neighborState.is(HydrofarmRefs.HYDROELECTRIC_GENERATOR.get())
            || neighborState.is(HydrofarmRefs.AUTOCRAFTER.get())
            || neighborState.is(HydrofarmRefs.REPULSER.get())
            || neighborState.is(HydrofarmRefs.ENERGY_CELL.get())) {
            return true;
        }
        return level instanceof Level l
            && HydrofarmPlatform.energy().find(l, neighborPos, directionToNeighbor.getOpposite()) != null;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        VoxelShape shape = CORE;
        if (state.getValue(NORTH)) shape = Shapes.or(shape, ARM_NORTH);
        if (state.getValue(SOUTH)) shape = Shapes.or(shape, ARM_SOUTH);
        if (state.getValue(EAST))  shape = Shapes.or(shape, ARM_EAST);
        if (state.getValue(WEST))  shape = Shapes.or(shape, ARM_WEST);
        if (state.getValue(UP))    shape = Shapes.or(shape, ARM_UP);
        if (state.getValue(DOWN))  shape = Shapes.or(shape, ARM_DOWN);
        return shape;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return getShape(state, level, pos, ctx);
    }
}
