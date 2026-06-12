package com.kestalkayden.hydrofarm.block;

import com.mojang.serialization.MapCodec;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.menu.MendingStationMenu;
import com.kestalkayden.hydrofarm.platform.HydrofarmPlatform;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.phys.BlockHitResult;

/** Mending Station block — BE-backed durability repairer. {@link #REPAIRING} drives the green glow
 *  on the top "+" (and a small light level); right-click opens the GUI. */
public class MendingStationBlock extends BaseEntityBlock {

    public static final MapCodec<MendingStationBlock> CODEC = simpleCodec(MendingStationBlock::new);

    /** True while actively restoring durability — lights the top plus. */
    public static final BooleanProperty REPAIRING = BooleanProperty.create("repairing");

    public MendingStationBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(REPAIRING, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(REPAIRING);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MendingStationBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof MendingStationBlockEntity be) {
            HydrofarmPlatform.menus().open(player,
                Component.translatable(state.getBlock().getDescriptionId()),
                (id, inv, p) -> new MendingStationMenu(id, inv, be),
                pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof MendingStationBlockEntity ms) {
                ms.serverTick((ServerLevel) lvl, pos, st);
            }
        };
    }
}
