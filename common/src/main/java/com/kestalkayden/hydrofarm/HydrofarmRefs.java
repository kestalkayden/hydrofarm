package com.kestalkayden.hydrofarm;

import java.util.function.Supplier;

import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;

/** Loader-agnostic accessors for the mod's registered objects. Each loader binds these lazy
 *  suppliers from its own registration (Fabric registers directly; NeoForge via DeferredRegister),
 *  and shared {@code common} code reads them through {@code .get()}.
 *
 *  <p>This single indirection is what lets block/BE/menu logic live in {@code common} without
 *  importing the per-loader registration classes (whose field types differ — a plain
 *  {@code Block} on Fabric vs a {@code DeferredBlock} on NeoForge). The suppliers are lazy, so a
 *  loader may bind them before its deferred registries have actually fired; resolution happens at
 *  first {@code get()}, which is always well after registration. */
public final class HydrofarmRefs {
    private HydrofarmRefs() {}

    // Blocks
    public static Supplier<Block> LIQUID_TANK, SPRINKLER, LIQUID_SIPHON, LIQUID_PIPE,
        LIQUID_PIPE_TERMINAL, ITEM_PIPE, ITEM_PIPE_TERMINAL, XP_DRAIN,
        HYDROPONICS_BED, TREE_FARM_BED, HUSBANDRY_BED, BUTCHER_BED, HYDROELECTRIC_GENERATOR,
        ENERGY_PIPE, AUTOCRAFTER, REPULSER, MENDING_STATION, ENERGY_CELL;

    // Block-entity types
    public static Supplier<BlockEntityType<?>> LIQUID_TANK_BE, SPRINKLER_BE, LIQUID_SIPHON_BE,
        LIQUID_PIPE_TERMINAL_BE, ITEM_PIPE_TERMINAL_BE, XP_DRAIN_BE, HYDROPONICS_BED_BE,
        TREE_FARM_BED_BE, HUSBANDRY_BED_BE, BUTCHER_BED_BE, HYDROELECTRIC_GENERATOR_BE,
        AUTOCRAFTER_BE, REPULSER_BE, MENDING_STATION_BE, ENERGY_CELL_BE;

    // Fluids
    public static Supplier<Fluid> LIQUID_XP, FLUID_MILK;

    // Items
    public static Supplier<Item> HYDROFARM_PLANTER_ITEM, ANIMAL_CAPTURE_NET_ITEM;

    // Data components
    public static Supplier<net.minecraft.core.component.DataComponentType<
        com.kestalkayden.hydrofarm.item.CapturedEntity>> CAPTURED_ENTITY;

    public static Supplier<net.minecraft.core.component.DataComponentType<
        com.kestalkayden.hydrofarm.item.ContainedEntities>> CONTAINED_ENTITIES;

    // Menu types
    public static Supplier<MenuType<?>> HYDROPONICS_BED_MENU, ANIMAL_BED_MENU,
        AUTOCRAFTER_MENU, ITEM_TERMINAL_MENU, LIQUID_TERMINAL_MENU, MENDING_STATION_MENU;
}
