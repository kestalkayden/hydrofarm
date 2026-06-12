package com.kestalkayden.hydrofarm.compat;

import com.kestalkayden.hydrofarm.block.HydroponicsBedBlockEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/** Jade tooltip for the Hydroponics Bed. Only adds info Jade can't auto-detect — fluid
 *  levels (water + Liquid XP) come from Jade's built-in FluidStorage display because the
 *  bed exposes a cluster-aware FluidStorage capability. Per-quadrant info is omitted now
 *  that cluster-wide stats matter more than per-bed (and the in-block GUI covers details). */
public class HydroponicsBedJadeProvider implements IBlockComponentProvider {

    private static final Identifier UID = Identifier.fromNamespaceAndPath("hydrofarm", "hydroponics_bed");

    @Override
    public Identifier getUid() {
        return UID;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (!(accessor.getBlockEntity() instanceof HydroponicsBedBlockEntity be)) return;

        int clusterSize = be.clusterMembers().size();
        int[] counts = be.clusterPlanterCounts();  // [installed, configured, growing, ready]

        if (clusterSize > 1) {
            tooltip.add(Component.literal("Cluster: " + clusterSize + " beds")
                .withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltip.add(Component.literal("Planters: " + counts[1] + " / " + (clusterSize * 4)
                + " configured"));
        if (counts[3] > 0) {
            tooltip.add(Component.literal(counts[3] + " ready to harvest")
                .withStyle(ChatFormatting.GOLD));
        }
    }
}
