package com.kestalkayden.hydrofarm;

import com.kestalkayden.hydrofarm.block.HydrofarmBlockEntities;
import com.kestalkayden.hydrofarm.block.HydrofarmBlocks;
import com.kestalkayden.hydrofarm.fluid.HydrofarmFluids;
import com.kestalkayden.hydrofarm.menu.HydrofarmMenus;

/** Binds {@link HydrofarmRefs} to Fabric's directly-registered objects. Called from
 *  {@code HydrofarmFabric} after registration. */
public final class HydrofarmRefsBinder {
    private HydrofarmRefsBinder() {}

    public static void bind() {
        HydrofarmRefs.LIQUID_TANK        = () -> HydrofarmBlocks.LIQUID_TANK;
        HydrofarmRefs.SPRINKLER          = () -> HydrofarmBlocks.SPRINKLER;
        HydrofarmRefs.LIQUID_SIPHON      = () -> HydrofarmBlocks.LIQUID_SIPHON;
        HydrofarmRefs.LIQUID_PIPE        = () -> HydrofarmBlocks.LIQUID_PIPE;
        HydrofarmRefs.LIQUID_PIPE_TERMINAL = () -> HydrofarmBlocks.LIQUID_PIPE_TERMINAL;
        HydrofarmRefs.ITEM_PIPE          = () -> HydrofarmBlocks.ITEM_PIPE;
        HydrofarmRefs.ITEM_PIPE_TERMINAL = () -> HydrofarmBlocks.ITEM_PIPE_TERMINAL;
        HydrofarmRefs.XP_DRAIN           = () -> HydrofarmBlocks.XP_DRAIN;
        HydrofarmRefs.HYDROPONICS_BED    = () -> HydrofarmBlocks.HYDROPONICS_BED;
        HydrofarmRefs.TREE_FARM_BED      = () -> HydrofarmBlocks.TREE_FARM_BED;
        HydrofarmRefs.HUSBANDRY_BED      = () -> HydrofarmBlocks.HUSBANDRY_BED;
        HydrofarmRefs.BUTCHER_BED        = () -> HydrofarmBlocks.BUTCHER_BED;
        HydrofarmRefs.HYDROELECTRIC_GENERATOR = () -> HydrofarmBlocks.HYDROELECTRIC_GENERATOR;
        HydrofarmRefs.ENERGY_PIPE        = () -> HydrofarmBlocks.ENERGY_PIPE;
        HydrofarmRefs.AUTOCRAFTER         = () -> HydrofarmBlocks.AUTOCRAFTER;
        HydrofarmRefs.REPULSER            = () -> HydrofarmBlocks.REPULSER;
        HydrofarmRefs.MENDING_STATION     = () -> HydrofarmBlocks.MENDING_STATION;
        HydrofarmRefs.ENERGY_CELL         = () -> HydrofarmBlocks.ENERGY_CELL;

        HydrofarmRefs.LIQUID_TANK_BE     = () -> HydrofarmBlockEntities.LIQUID_TANK_BE;
        HydrofarmRefs.SPRINKLER_BE       = () -> HydrofarmBlockEntities.SPRINKLER_BE;
        HydrofarmRefs.LIQUID_SIPHON_BE   = () -> HydrofarmBlockEntities.LIQUID_SIPHON_BE;
        HydrofarmRefs.LIQUID_PIPE_TERMINAL_BE = () -> HydrofarmBlockEntities.LIQUID_PIPE_TERMINAL_BE;
        HydrofarmRefs.ITEM_PIPE_TERMINAL_BE = () -> HydrofarmBlockEntities.ITEM_PIPE_TERMINAL_BE;
        HydrofarmRefs.XP_DRAIN_BE        = () -> HydrofarmBlockEntities.XP_DRAIN_BE;
        HydrofarmRefs.HYDROPONICS_BED_BE = () -> HydrofarmBlockEntities.HYDROPONICS_BED_BE;
        HydrofarmRefs.TREE_FARM_BED_BE   = () -> HydrofarmBlockEntities.TREE_FARM_BED_BE;
        HydrofarmRefs.HUSBANDRY_BED_BE   = () -> HydrofarmBlockEntities.HUSBANDRY_BED_BE;
        HydrofarmRefs.BUTCHER_BED_BE     = () -> HydrofarmBlockEntities.BUTCHER_BED_BE;
        HydrofarmRefs.HYDROELECTRIC_GENERATOR_BE = () -> HydrofarmBlockEntities.HYDROELECTRIC_GENERATOR_BE;
        HydrofarmRefs.AUTOCRAFTER_BE     = () -> HydrofarmBlockEntities.AUTOCRAFTER_BE;
        HydrofarmRefs.REPULSER_BE        = () -> HydrofarmBlockEntities.REPULSER_BE;
        HydrofarmRefs.MENDING_STATION_BE = () -> HydrofarmBlockEntities.MENDING_STATION_BE;
        HydrofarmRefs.ENERGY_CELL_BE     = () -> HydrofarmBlockEntities.ENERGY_CELL_BE;

        HydrofarmRefs.LIQUID_XP          = () -> HydrofarmFluids.LIQUID_XP;
        HydrofarmRefs.FLUID_MILK         = () -> HydrofarmFluids.FLUID_MILK;

        HydrofarmRefs.HYDROFARM_PLANTER_ITEM  = () -> HydrofarmBlocks.HYDROFARM_PLANTER_ITEM;
        HydrofarmRefs.ANIMAL_CAPTURE_NET_ITEM = () -> HydrofarmBlocks.ANIMAL_CAPTURE_NET_ITEM;
        HydrofarmRefs.CAPTURED_ENTITY = () -> com.kestalkayden.hydrofarm.item.HydrofarmDataComponents.CAPTURED_ENTITY;

        HydrofarmRefs.HYDROPONICS_BED_MENU = () -> HydrofarmMenus.HYDROPONICS_BED;
        HydrofarmRefs.ANIMAL_BED_MENU      = () -> HydrofarmMenus.ANIMAL_BED;
        HydrofarmRefs.AUTOCRAFTER_MENU     = () -> HydrofarmMenus.AUTOCRAFTER;
        HydrofarmRefs.ITEM_TERMINAL_MENU   = () -> HydrofarmMenus.ITEM_TERMINAL;
        HydrofarmRefs.LIQUID_TERMINAL_MENU = () -> HydrofarmMenus.LIQUID_TERMINAL;
        HydrofarmRefs.MENDING_STATION_MENU = () -> HydrofarmMenus.MENDING_STATION;
    }
}
