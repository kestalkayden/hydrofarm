package com.kestalkayden.hydrofarm.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class SprinklerBlock extends BaseEntityBlock {

    public static final MapCodec<SprinklerBlock> CODEC = simpleCodec(SprinklerBlock::new);

    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    private static final int BUCKET_MB = 1000;

    /** Flush pad, transmitter-style — looks like a sprinkler lid set into the ground. */
    private static final VoxelShape IDLE_SHAPE =
        Block.box(1, 0, 1, 15, 2, 15);

    /** Same pad with a small 4×4×4 spray nozzle popped up out of the lid when actively spraying.
     *  Small size reads as a nozzle (not a turret) and minimizes the visible off-center pixel
     *  pattern on the head's top face. */
    private static final VoxelShape ACTIVE_SHAPE = Shapes.or(
        Block.box(1, 0, 1, 15, 2, 15),
        Block.box(6, 2, 6, 10, 6, 10)
    );

    public SprinklerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LIT, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SprinklerBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(LIT) ? ACTIVE_SHAPE : IDLE_SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(LIT) ? ACTIVE_SHAPE : IDLE_SHAPE;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof SprinklerBlockEntity sb) {
                sb.serverTick((ServerLevel) lvl, pos, st);
            }
        };
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof SprinklerBlockEntity be)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }

        if (stack.is(Items.WATER_BUCKET) && be.getRoomMb() >= BUCKET_MB) {
            if (!level.isClientSide()) {
                be.insertWater(BUCKET_MB);
                if (!player.getAbilities().instabuild) {
                    ItemStack empty = new ItemStack(Items.BUCKET);
                    if (stack.getCount() == 1) {
                        player.setItemInHand(hand, empty);
                    } else {
                        stack.shrink(1);
                        player.getInventory().placeItemBackInInventory(empty);
                    }
                }
                level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
            }
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }
}
