package com.kestalkayden.hydrofarm.block;

import com.mojang.serialization.MapCodec;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.menu.LiquidTerminalMenu;
import com.kestalkayden.hydrofarm.platform.HydrofarmPlatform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Liquid pipe terminal — fluid twin of {@link ItemPipeTerminalBlock}. See it for the shared
 *  per-face model (PIPE / PORT / EXTRACT / INSERT), cycling, and revert-to-pipe behaviour. */
public class LiquidPipeTerminalBlock extends BaseEntityBlock {

    public static final MapCodec<LiquidPipeTerminalBlock> CODEC = simpleCodec(LiquidPipeTerminalBlock::new);

    public static final EnumProperty<PipeFace> F_NORTH = EnumProperty.create("face_north", PipeFace.class);
    public static final EnumProperty<PipeFace> F_SOUTH = EnumProperty.create("face_south", PipeFace.class);
    public static final EnumProperty<PipeFace> F_EAST  = EnumProperty.create("face_east",  PipeFace.class);
    public static final EnumProperty<PipeFace> F_WEST  = EnumProperty.create("face_west",  PipeFace.class);
    public static final EnumProperty<PipeFace> F_UP    = EnumProperty.create("face_up",    PipeFace.class);
    public static final EnumProperty<PipeFace> F_DOWN  = EnumProperty.create("face_down",  PipeFace.class);

    private static final VoxelShape CORE = Block.box(6, 6, 6, 10, 10, 10);
    private static final VoxelShape[] ARM = PipeShapes.arms();
    private static final VoxelShape[] NOZZLE = PipeShapes.nozzles();

    public LiquidPipeTerminalBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(F_NORTH, PipeFace.NONE).setValue(F_SOUTH, PipeFace.NONE)
            .setValue(F_EAST,  PipeFace.NONE).setValue(F_WEST,  PipeFace.NONE)
            .setValue(F_UP,    PipeFace.NONE).setValue(F_DOWN,  PipeFace.NONE));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(F_NORTH, F_SOUTH, F_EAST, F_WEST, F_UP, F_DOWN);
    }

    public static EnumProperty<PipeFace> faceProp(Direction dir) {
        return switch (dir) {
            case NORTH -> F_NORTH;
            case SOUTH -> F_SOUTH;
            case EAST  -> F_EAST;
            case WEST  -> F_WEST;
            case UP    -> F_UP;
            case DOWN  -> F_DOWN;
        };
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = defaultBlockState();
        for (Direction d : Direction.values()) {
            BlockPos np = pos.relative(d);
            state = state.setValue(faceProp(d), recompute(level, np, level.getBlockState(np), d, PipeFace.NONE));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess scheduledTick,
                                      BlockPos pos, Direction direction, BlockPos neighborPos,
                                      BlockState neighborState, RandomSource random) {
        EnumProperty<PipeFace> prop = faceProp(direction);
        PipeFace current = state.getValue(prop);
        PipeFace next = recompute(level, neighborPos, neighborState, direction, current);
        if (!LiquidPipeBlock.isPipe(neighborState)) scheduledTick.scheduleTick(pos, this, 1);
        if (current != next) {
            TransportNetwork.bump();
            return state.setValue(prop, next);
        }
        return state;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.isClientSide() && !oldState.is(state.getBlock())) level.scheduleTick(pos, this, 1);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        PipeNodes.normalizeFluid(level, pos);
    }

    private static PipeFace recompute(LevelReader level, BlockPos neighborPos, BlockState neighborState,
                                       Direction dir, PipeFace current) {
        if (LiquidPipeBlock.isPipe(neighborState)) return PipeFace.PIPE;
        boolean inventory = level instanceof Level l
            && HydrofarmPlatform.fluids().find(l, neighborPos, dir.getOpposite()) != null;
        if (inventory) return current.isPort() ? current : PipeFace.PORT;
        return PipeFace.NONE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LiquidPipeTerminalBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    private static final VoxelShape[] SHAPE_CACHE = new VoxelShape[PipeShapes.SHAPE_COUNT];

    private static VoxelShape shapeFor(BlockState state) {
        int idx = PipeShapes.shapeIndex(d -> state.getValue(faceProp(d)));
        VoxelShape cached = SHAPE_CACHE[idx];
        if (cached != null) return cached;
        VoxelShape shape = CORE;
        for (Direction d : Direction.values()) {
            PipeFace f = state.getValue(faceProp(d));
            if (f == PipeFace.PIPE) shape = Shapes.or(shape, ARM[d.ordinal()]);
            else if (f.isInventory()) shape = Shapes.or(shape, NOZZLE[d.ordinal()]);
        }
        shape = shape.optimize();
        SHAPE_CACHE[idx] = shape;
        return shape;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shapeFor(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shapeFor(state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        Direction face = PipeShapes.nearestFace(pos, hit, d -> state.getValue(faceProp(d)).isInventory());
        if (face == null) return InteractionResult.PASS;
        EnumProperty<PipeFace> prop = faceProp(face);
        PipeFace current = state.getValue(prop);

        if (player.isShiftKeyDown()) {
            PipeFace next = switch (current) {
                case PORT, NONE, PIPE -> PipeFace.EXTRACT;
                case EXTRACT -> PipeFace.INSERT;
                case INSERT -> PipeFace.PORT;
            };
            applyFace(level, pos, state, prop, next);
            level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.3F,
                next == PipeFace.EXTRACT ? 0.9F : next == PipeFace.INSERT ? 0.6F : 0.4F);
            return InteractionResult.SUCCESS;
        }

        if (!current.isPort()) {
            level.setBlock(pos, state.setValue(prop, PipeFace.EXTRACT), Block.UPDATE_ALL);
            TransportNetwork.bump();
        }
        if (level.getBlockEntity(pos) instanceof LiquidPipeTerminalBlockEntity be) {
            HydrofarmPlatform.menus().openTerminal(player,
                Component.translatable(state.getBlock().getDescriptionId()),
                (id, inv, p) -> new LiquidTerminalMenu(id, inv, be, face),
                pos, face);
        }
        return InteractionResult.SUCCESS;
    }

    private static void applyFace(Level level, BlockPos pos, BlockState state,
                                   EnumProperty<PipeFace> prop, PipeFace next) {
        BlockState updated = state.setValue(prop, next);
        TransportNetwork.bump();
        if (!hasConfiguredPort(updated)) {
            level.setBlock(pos, toPlainPipe(updated), Block.UPDATE_ALL);
        } else {
            level.setBlock(pos, updated, Block.UPDATE_ALL);
        }
    }

    private static boolean hasConfiguredPort(BlockState terminalState) {
        for (Direction d : Direction.values()) {
            if (terminalState.getValue(faceProp(d)).isPort()) return true;
        }
        return false;
    }

    private static BlockState toPlainPipe(BlockState terminalState) {
        BlockState pipe = HydrofarmRefs.LIQUID_PIPE.get().defaultBlockState();
        for (Direction d : Direction.values()) {
            pipe = pipe.setValue(LiquidPipeBlock.faceProp(d), terminalState.getValue(faceProp(d)));
        }
        return pipe;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        boolean sourcing = false;
        for (Direction d : Direction.values()) {
            if (state.getValue(faceProp(d)) == PipeFace.EXTRACT) { sourcing = true; break; }
        }
        if (!sourcing) return null;
        return (lvl, p, st, be) -> {
            if (be instanceof LiquidPipeTerminalBlockEntity term) {
                term.serverTick((ServerLevel) lvl, p, st);
            }
        };
    }
}
