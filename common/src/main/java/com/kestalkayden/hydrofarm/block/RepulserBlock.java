package com.kestalkayden.hydrofarm.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** Monster Repulser block. A siphon-silhouette machine (red, sound-wave themed) that runs a powered
 *  anti-mob field. State lives in {@link RepulserBlockEntity}; the {@link #ACTIVE} property drives
 *  the red glow + animated side strip while the field is up. */
public class RepulserBlock extends BaseEntityBlock {

    public static final MapCodec<RepulserBlock> CODEC = simpleCodec(RepulserBlock::new);

    /** True while the field is powered and enabled. Drives the active model + light. */
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public RepulserBlock(Properties properties) {
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
        return new RepulserBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof RepulserBlockEntity rbe) {
                rbe.serverTick((ServerLevel) lvl, pos, st);
            }
        };
    }
}
