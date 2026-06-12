package com.kestalkayden.hydrofarm.compat;

import com.kestalkayden.hydrofarm.block.ItemPipeTerminalBlock;
import com.kestalkayden.hydrofarm.block.LiquidPipeTerminalBlock;
import com.kestalkayden.hydrofarm.block.PipeFace;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/** Jade tooltip for item/liquid pipe terminals. Summarises the node's configured ports from the
 *  blockstate (which is synced to the client), so it needs no extra block-entity networking: how
 *  many sides extract, how many insert, and whether unconfigured gray ports remain. An all-gray
 *  node reads as inactive with a hint to configure it. */
public class PipeTerminalJadeProvider implements IBlockComponentProvider {

    private static final Identifier UID = Identifier.fromNamespaceAndPath("hydrofarm", "pipe_terminal");

    @Override
    public Identifier getUid() {
        return UID;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        BlockState state = accessor.getBlockState();
        Block block = state.getBlock();

        int extract = 0, insert = 0, port = 0;
        for (Direction d : Direction.values()) {
            EnumProperty<PipeFace> prop;
            if (block instanceof ItemPipeTerminalBlock) prop = ItemPipeTerminalBlock.faceProp(d);
            else if (block instanceof LiquidPipeTerminalBlock) prop = LiquidPipeTerminalBlock.faceProp(d);
            else return;
            switch (state.getValue(prop)) {
                case EXTRACT -> extract++;
                case INSERT -> insert++;
                case PORT -> port++;
                default -> { }
            }
        }

        if (extract == 0 && insert == 0) {
            tooltip.add(Component.literal("Inactive — right-click a port to set extract/insert")
                .withStyle(ChatFormatting.GRAY));
            return;
        }
        if (extract > 0) {
            tooltip.add(Component.literal("Extract: " + extract + (extract > 1 ? " sides" : " side"))
                .withStyle(ChatFormatting.GOLD));
        }
        if (insert > 0) {
            tooltip.add(Component.literal("Insert: " + insert + (insert > 1 ? " sides" : " side"))
                .withStyle(ChatFormatting.AQUA));
        }
        if (port > 0) {
            tooltip.add(Component.literal(port + (port > 1 ? " unconfigured ports" : " unconfigured port"))
                .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
