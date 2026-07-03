package com.kestalkayden.hydrofarm;

import com.kestalkayden.hydrofarm.block.HydrofarmBlockEntities;
import com.kestalkayden.hydrofarm.block.HydrofarmBlocks;
import com.kestalkayden.hydrofarm.fluid.HydrofarmFluids;
import com.kestalkayden.hydrofarm.menu.HydrofarmMenus;

/** Binds {@link HydrofarmRefs} to NeoForge's deferred registrations. The suppliers are lazy, so
 *  this runs safely in the mod constructor — before the deferred registries actually fire — and
 *  resolves each object on first {@code get()}. Blocks/items/menus go through their DeferredHolder's
 *  {@code .get()}; BE types and fluids read the plain static fields NeoForge sets in its
 *  registration callbacks. */
public final class HydrofarmRefsBinder {
    private HydrofarmRefsBinder() {}

    public static void bind() {
        HydrofarmRefs.LIQUID_TANK        = () -> HydrofarmBlocks.LIQUID_TANK.get();
        HydrofarmRefs.SPRINKLER          = () -> HydrofarmBlocks.SPRINKLER.get();
        HydrofarmRefs.LIQUID_SIPHON      = () -> HydrofarmBlocks.LIQUID_SIPHON.get();
        HydrofarmRefs.LIQUID_PIPE        = () -> HydrofarmBlocks.LIQUID_PIPE.get();
        HydrofarmRefs.LIQUID_PIPE_TERMINAL = () -> HydrofarmBlocks.LIQUID_PIPE_TERMINAL.get();
        HydrofarmRefs.ITEM_PIPE          = () -> HydrofarmBlocks.ITEM_PIPE.get();
        HydrofarmRefs.ITEM_PIPE_TERMINAL = () -> HydrofarmBlocks.ITEM_PIPE_TERMINAL.get();
        HydrofarmRefs.XP_DRAIN           = () -> HydrofarmBlocks.XP_DRAIN.get();
        HydrofarmRefs.HYDROPONICS_BED    = () -> HydrofarmBlocks.HYDROPONICS_BED.get();
        HydrofarmRefs.TREE_FARM_BED      = () -> HydrofarmBlocks.TREE_FARM_BED.get();
        HydrofarmRefs.HUSBANDRY_BED      = () -> HydrofarmBlocks.HUSBANDRY_BED.get();
        HydrofarmRefs.BUTCHER_BED        = () -> HydrofarmBlocks.BUTCHER_BED.get();
        HydrofarmRefs.HYDROELECTRIC_GENERATOR = () -> HydrofarmBlocks.HYDROELECTRIC_GENERATOR.get();
        HydrofarmRefs.ENERGY_PIPE        = () -> HydrofarmBlocks.ENERGY_PIPE.get();
        HydrofarmRefs.AUTOCRAFTER         = () -> HydrofarmBlocks.AUTOCRAFTER.get();
        HydrofarmRefs.REPULSER            = () -> HydrofarmBlocks.REPULSER.get();
        HydrofarmRefs.MENDING_STATION     = () -> HydrofarmBlocks.MENDING_STATION.get();
        HydrofarmRefs.ENERGY_CELL         = () -> HydrofarmBlocks.ENERGY_CELL.get();

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

        HydrofarmRefs.HYDROFARM_PLANTER_ITEM  = () -> HydrofarmBlocks.HYDROFARM_PLANTER_ITEM.get();
        HydrofarmRefs.ANIMAL_CAPTURE_NET_ITEM = () -> HydrofarmBlocks.ANIMAL_CAPTURE_NET_ITEM.get();
        HydrofarmRefs.CAPTURED_ENTITY = () -> com.kestalkayden.hydrofarm.item.HydrofarmDataComponents.CAPTURED_ENTITY;
        HydrofarmRefs.CONTAINED_ENTITIES = () -> com.kestalkayden.hydrofarm.item.HydrofarmDataComponents.CONTAINED_ENTITIES;

        HydrofarmRefs.HYDROPONICS_BED_MENU = () -> HydrofarmMenus.HYDROPONICS_BED.get();
        HydrofarmRefs.ANIMAL_BED_MENU      = () -> HydrofarmMenus.ANIMAL_BED.get();
        HydrofarmRefs.AUTOCRAFTER_MENU     = () -> HydrofarmMenus.AUTOCRAFTER.get();
        HydrofarmRefs.ITEM_TERMINAL_MENU   = () -> HydrofarmMenus.ITEM_TERMINAL.get();
        HydrofarmRefs.LIQUID_TERMINAL_MENU = () -> HydrofarmMenus.LIQUID_TERMINAL.get();
        HydrofarmRefs.MENDING_STATION_MENU = () -> HydrofarmMenus.MENDING_STATION.get();
    }
}
