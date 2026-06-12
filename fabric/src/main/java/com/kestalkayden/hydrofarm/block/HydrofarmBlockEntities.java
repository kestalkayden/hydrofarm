package com.kestalkayden.hydrofarm.block;

import com.kestalkayden.hydrofarm.HydrofarmFabric;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class HydrofarmBlockEntities {
    public static BlockEntityType<LiquidTankBlockEntity> LIQUID_TANK_BE;
    public static BlockEntityType<SprinklerBlockEntity> SPRINKLER_BE;
    public static BlockEntityType<LiquidSiphonBlockEntity> LIQUID_SIPHON_BE;
    public static BlockEntityType<LiquidPipeTerminalBlockEntity> LIQUID_PIPE_TERMINAL_BE;
    public static BlockEntityType<ItemPipeTerminalBlockEntity> ITEM_PIPE_TERMINAL_BE;
    public static BlockEntityType<XpDrainBlockEntity> XP_DRAIN_BE;
    public static BlockEntityType<HydroponicsBedBlockEntity> HYDROPONICS_BED_BE;
    public static BlockEntityType<TreeFarmBedBlockEntity> TREE_FARM_BED_BE;
    public static BlockEntityType<HusbandryBedBlockEntity> HUSBANDRY_BED_BE;
    public static BlockEntityType<ButcherBedBlockEntity> BUTCHER_BED_BE;
    public static BlockEntityType<HydroelectricGeneratorBlockEntity> HYDROELECTRIC_GENERATOR_BE;
    public static BlockEntityType<AutocrafterBlockEntity> AUTOCRAFTER_BE;
    public static BlockEntityType<RepulserBlockEntity> REPULSER_BE;
    public static BlockEntityType<MendingStationBlockEntity> MENDING_STATION_BE;
    public static BlockEntityType<EnergyCellBlockEntity> ENERGY_CELL_BE;

    private HydrofarmBlockEntities() {}

    public static void register() {
        LIQUID_TANK_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "liquid_tank"),
            FabricBlockEntityTypeBuilder.create(LiquidTankBlockEntity::new,
                HydrofarmBlocks.LIQUID_TANK
            ).build());

        SPRINKLER_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "sprinkler"),
            FabricBlockEntityTypeBuilder.create(SprinklerBlockEntity::new,
                HydrofarmBlocks.SPRINKLER
            ).build());

        LIQUID_SIPHON_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "liquid_siphon"),
            FabricBlockEntityTypeBuilder.create(LiquidSiphonBlockEntity::new,
                HydrofarmBlocks.LIQUID_SIPHON
            ).build());

        LIQUID_PIPE_TERMINAL_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "liquid_pipe_terminal"),
            FabricBlockEntityTypeBuilder.create(LiquidPipeTerminalBlockEntity::new,
                HydrofarmBlocks.LIQUID_PIPE_TERMINAL
            ).build());

        ITEM_PIPE_TERMINAL_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "item_pipe_terminal"),
            FabricBlockEntityTypeBuilder.create(ItemPipeTerminalBlockEntity::new,
                HydrofarmBlocks.ITEM_PIPE_TERMINAL
            ).build());

        XP_DRAIN_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "xp_drain"),
            FabricBlockEntityTypeBuilder.create(XpDrainBlockEntity::new,
                HydrofarmBlocks.XP_DRAIN
            ).build());

        HYDROPONICS_BED_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "hydroponics_bed"),
            FabricBlockEntityTypeBuilder.create(HydroponicsBedBlockEntity::new,
                HydrofarmBlocks.HYDROPONICS_BED
            ).build());

        TREE_FARM_BED_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "tree_farm_bed"),
            FabricBlockEntityTypeBuilder.create(TreeFarmBedBlockEntity::new,
                HydrofarmBlocks.TREE_FARM_BED
            ).build());

        HUSBANDRY_BED_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "husbandry_bed"),
            FabricBlockEntityTypeBuilder.create(HusbandryBedBlockEntity::new,
                HydrofarmBlocks.HUSBANDRY_BED
            ).build());

        BUTCHER_BED_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "butcher_bed"),
            FabricBlockEntityTypeBuilder.create(ButcherBedBlockEntity::new,
                HydrofarmBlocks.BUTCHER_BED
            ).build());

        HYDROELECTRIC_GENERATOR_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "hydroelectric_generator"),
            FabricBlockEntityTypeBuilder.create(HydroelectricGeneratorBlockEntity::new,
                HydrofarmBlocks.HYDROELECTRIC_GENERATOR
            ).build());

        AUTOCRAFTER_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "autocrafter"),
            FabricBlockEntityTypeBuilder.create(AutocrafterBlockEntity::new,
                HydrofarmBlocks.AUTOCRAFTER
            ).build());

        REPULSER_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "repulser"),
            FabricBlockEntityTypeBuilder.create(RepulserBlockEntity::new,
                HydrofarmBlocks.REPULSER
            ).build());

        MENDING_STATION_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "mending_station"),
            FabricBlockEntityTypeBuilder.create(MendingStationBlockEntity::new,
                HydrofarmBlocks.MENDING_STATION
            ).build());

        ENERGY_CELL_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "energy_cell"),
            FabricBlockEntityTypeBuilder.create(EnergyCellBlockEntity::new,
                HydrofarmBlocks.ENERGY_CELL
            ).build());
    }
}
