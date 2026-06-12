package com.kestalkayden.hydrofarm.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** Hydroelectric Generator block — a simple full-cube machine. Consumes water (pumped in via a
 *  Hydrofarm liquid pump or any fluid source) and produces energy drawn out by tech-mod cables.
 *  All logic lives in {@link HydroelectricGeneratorBlockEntity}; the capability exposure is wired
 *  per loader. The {@link #WORKING} flag is driven by the BE while it's actively generating and
 *  drives a faint block-light emission (registered per loader). */
public class HydroelectricGeneratorBlock extends BaseEntityBlock {

    public static final MapCodec<HydroelectricGeneratorBlock> CODEC = simpleCodec(HydroelectricGeneratorBlock::new);

    /** True while the generator is actively converting water → energy. Drives the per-loader
     *  {@code lightLevel} so a powered generator faintly glows for non-shader players. */
    public static final BooleanProperty WORKING = BooleanProperty.create("working");

    public HydroelectricGeneratorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(WORKING, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WORKING);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HydroelectricGeneratorBlockEntity(pos, state);
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
            if (be instanceof HydroelectricGeneratorBlockEntity gen) {
                gen.serverTick((ServerLevel) lvl, pos, st);
            }
        };
    }
}
