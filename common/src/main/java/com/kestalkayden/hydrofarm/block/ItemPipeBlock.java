package com.kestalkayden.hydrofarm.block;

import com.mojang.serialization.MapCodec;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.menu.ItemTerminalMenu;
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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Pure-topology item pipe — no BE. Each face carries a {@link PipeFace}: {@code PIPE} (an arm to a
 *  neighbouring pipe/terminal) or {@code PORT} (a neutral nozzle that appears automatically wherever
 *  the pipe touches an item inventory). Right-click a PORT with an empty hand to promote this node
 *  into an {@link ItemPipeTerminalBlock} (that face = EXTRACT) and open its filter GUI. The plain
 *  pipe never holds EXTRACT/INSERT itself — those live on the terminal. */
public class ItemPipeBlock extends Block {

    public static final MapCodec<ItemPipeBlock> CODEC = simpleCodec(ItemPipeBlock::new);

    public static final EnumProperty<PipeFace> F_NORTH = EnumProperty.create("face_north", PipeFace.class);
    public static final EnumProperty<PipeFace> F_SOUTH = EnumProperty.create("face_south", PipeFace.class);
    public static final EnumProperty<PipeFace> F_EAST  = EnumProperty.create("face_east",  PipeFace.class);
    public static final EnumProperty<PipeFace> F_WEST  = EnumProperty.create("face_west",  PipeFace.class);
    public static final EnumProperty<PipeFace> F_UP    = EnumProperty.create("face_up",    PipeFace.class);
    public static final EnumProperty<PipeFace> F_DOWN  = EnumProperty.create("face_down",  PipeFace.class);

    private static final VoxelShape CORE = Block.box(6, 6, 6, 10, 10, 10);
    private static final VoxelShape[] ARM = PipeShapes.arms();
    private static final VoxelShape[] NOZZLE = PipeShapes.nozzles();

    public ItemPipeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(F_NORTH, PipeFace.NONE).setValue(F_SOUTH, PipeFace.NONE)
            .setValue(F_EAST,  PipeFace.NONE).setValue(F_WEST,  PipeFace.NONE)
            .setValue(F_UP,    PipeFace.NONE).setValue(F_DOWN,  PipeFace.NONE));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
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
            state = state.setValue(faceProp(d), recompute(level, np, level.getBlockState(np), d));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess scheduledTick,
                                      BlockPos pos, Direction direction, BlockPos neighborPos,
                                      BlockState neighborState, RandomSource random) {
        EnumProperty<PipeFace> prop = faceProp(direction);
        PipeFace next = recompute(level, neighborPos, neighborState, direction);
        // A non-pipe neighbour changing may have added/removed an inventory → re-evaluate whether
        // this node should become a terminal (so an inactive PORT still reads as a terminal block).
        if (!isPipe(neighborState)) scheduledTick.scheduleTick(pos, this, 1);
        if (state.getValue(prop) != next) {
            TransportNetwork.bump();
            return state.setValue(prop, next);
        }
        return state;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.isClientSide()) level.scheduleTick(pos, this, 1);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        PipeNodes.normalizeItem(level, pos);
    }

    /** A neighbour pipe/terminal → PIPE; a neighbour item inventory → PORT (auto nozzle); else NONE. */
    private static PipeFace recompute(LevelReader level, BlockPos neighborPos, BlockState neighborState, Direction dir) {
        if (isPipe(neighborState)) return PipeFace.PIPE;
        if (level instanceof Level l
            && HydrofarmPlatform.items().find(l, neighborPos, dir.getOpposite()) != null) {
            return PipeFace.PORT;
        }
        return PipeFace.NONE;
    }

    static boolean isPipe(BlockState s) {
        return s.is(HydrofarmRefs.ITEM_PIPE.get()) || s.is(HydrofarmRefs.ITEM_PIPE_TERMINAL.get());
    }

    /** Empty-hand right-click a PORT (auto nozzle) to promote this node into a terminal with that
     *  face = EXTRACT and open the filter GUI. Sneak to promote without opening the GUI. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        Direction face = PipeShapes.nearestFace(pos, hit, d -> state.getValue(faceProp(d)) == PipeFace.PORT);
        if (face == null) return InteractionResult.PASS;   // pipe touches no inventory

        BlockState term = HydrofarmRefs.ITEM_PIPE_TERMINAL.get().defaultBlockState();
        for (Direction d : Direction.values()) {
            PipeFace f = d == face ? PipeFace.EXTRACT : state.getValue(faceProp(d));
            term = term.setValue(ItemPipeTerminalBlock.faceProp(d), f);
        }
        level.setBlock(pos, term, Block.UPDATE_ALL);
        TransportNetwork.bump();
        level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.3F, 0.9F);

        if (!player.isShiftKeyDown() && level.getBlockEntity(pos) instanceof ItemPipeTerminalBlockEntity be) {
            HydrofarmPlatform.menus().openTerminal(player,
                Component.translatable(term.getBlock().getDescriptionId()),
                (id, inv, p) -> new ItemTerminalMenu(id, inv, be, face),
                pos, face);
        }
        return InteractionResult.SUCCESS;
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
}
