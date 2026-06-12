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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;

public class LiquidSiphonBlock extends BaseEntityBlock {

    public static final MapCodec<LiquidSiphonBlock> CODEC = simpleCodec(LiquidSiphonBlock::new);

    /** True while the BE has at least one water source in range and room in the buffer.
     *  Drives the side-strip animated-green-noise variant in the blockstate. */
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    private static final int BUCKET_MB = 1000;

    public LiquidSiphonBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LiquidSiphonBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof LiquidSiphonBlockEntity wsbe) {
                wsbe.serverTick((ServerLevel) lvl, pos, st);
            }
        };
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof LiquidSiphonBlockEntity be)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }

        // Empty bucket → drain 1000 mB out as a water bucket. Provides a manual extraction path
        // before pumps are placed. Insertion is intentionally not supported — the siphon's job is
        // to generate water, not store it for the player.
        if (stack.is(Items.BUCKET) && be.getWaterMb() >= BUCKET_MB) {
            if (!level.isClientSide()) {
                be.extractWater(BUCKET_MB);
                if (!player.getAbilities().instabuild) {
                    ItemStack filled = new ItemStack(Items.WATER_BUCKET);
                    if (stack.getCount() == 1) {
                        player.setItemInHand(hand, filled);
                    } else {
                        stack.shrink(1);
                        player.getInventory().placeItemBackInInventory(filled);
                    }
                }
                level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
            }
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }
}
