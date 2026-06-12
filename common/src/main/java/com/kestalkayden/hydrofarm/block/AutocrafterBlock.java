package com.kestalkayden.hydrofarm.block;

import com.mojang.serialization.MapCodec;

import com.kestalkayden.hydrofarm.menu.AutocrafterMenu;
import com.kestalkayden.hydrofarm.platform.HydrofarmPlatform;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.phys.BlockHitResult;

/** Powered autocrafter. A plain full cube: right-click opens the ghost-template + input/output GUI;
 *  the server ticker drives crafting and the cable-network energy pull. State lives entirely in
 *  {@link AutocrafterBlockEntity}, so the block itself carries no blockstate properties. */
public class AutocrafterBlock extends BaseEntityBlock {

    public static final MapCodec<AutocrafterBlock> CODEC = simpleCodec(AutocrafterBlock::new);

    /** True for a short linger after each craft — the side accent glows green while producing. */
    public static final BooleanProperty CRAFTING = BooleanProperty.create("crafting");

    public AutocrafterBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(CRAFTING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CRAFTING);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AutocrafterBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof AutocrafterBlockEntity be) {
            HydrofarmPlatform.menus().open(player,
                Component.translatable(state.getBlock().getDescriptionId()),
                (id, inv, p) -> new AutocrafterMenu(id, inv, be),
                pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof AutocrafterBlockEntity acbe) {
                acbe.serverTick((ServerLevel) lvl, pos, st);
            }
        };
    }
}
