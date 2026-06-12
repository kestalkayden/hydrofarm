package com.kestalkayden.hydrofarm.block;

import com.kestalkayden.hydrofarm.HydrofarmNeoForge;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class HydrofarmBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, HydrofarmNeoForge.MOD_ID);

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

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LiquidTankBlockEntity>> LIQUID_TANK_BE_HOLDER =
        BES.register("liquid_tank", () -> {
            LIQUID_TANK_BE = new BlockEntityType<>(LiquidTankBlockEntity::new,
                HydrofarmBlocks.LIQUID_TANK.get());
            return LIQUID_TANK_BE;
        });

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SprinklerBlockEntity>> SPRINKLER_BE_HOLDER =
        BES.register("sprinkler", () -> {
            SPRINKLER_BE = new BlockEntityType<>(SprinklerBlockEntity::new,
                HydrofarmBlocks.SPRINKLER.get());
            return SPRINKLER_BE;
        });

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LiquidSiphonBlockEntity>> LIQUID_SIPHON_BE_HOLDER =
        BES.register("liquid_siphon", () -> {
            LIQUID_SIPHON_BE = new BlockEntityType<>(LiquidSiphonBlockEntity::new,
                HydrofarmBlocks.LIQUID_SIPHON.get());
            return LIQUID_SIPHON_BE;
        });

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LiquidPipeTerminalBlockEntity>> LIQUID_PIPE_TERMINAL_BE_HOLDER =
        BES.register("liquid_pipe_terminal", () -> {
            LIQUID_PIPE_TERMINAL_BE = new BlockEntityType<>(LiquidPipeTerminalBlockEntity::new,
                HydrofarmBlocks.LIQUID_PIPE_TERMINAL.get());
            return LIQUID_PIPE_TERMINAL_BE;
        });

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ItemPipeTerminalBlockEntity>> ITEM_PIPE_TERMINAL_BE_HOLDER =
        BES.register("item_pipe_terminal", () -> {
            ITEM_PIPE_TERMINAL_BE = new BlockEntityType<>(ItemPipeTerminalBlockEntity::new,
                HydrofarmBlocks.ITEM_PIPE_TERMINAL.get());
            return ITEM_PIPE_TERMINAL_BE;
        });

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<XpDrainBlockEntity>> XP_DRAIN_BE_HOLDER =
        BES.register("xp_drain", () -> {
            XP_DRAIN_BE = new BlockEntityType<>(XpDrainBlockEntity::new,
                HydrofarmBlocks.XP_DRAIN.get());
            return XP_DRAIN_BE;
        });

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<HydroponicsBedBlockEntity>> HYDROPONICS_BED_BE_HOLDER =
        BES.register("hydroponics_bed", () -> {
            HYDROPONICS_BED_BE = new BlockEntityType<>(HydroponicsBedBlockEntity::new,
                HydrofarmBlocks.HYDROPONICS_BED.get());
            return HYDROPONICS_BED_BE;
        });

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TreeFarmBedBlockEntity>> TREE_FARM_BED_BE_HOLDER =
        BES.register("tree_farm_bed", () -> {
            TREE_FARM_BED_BE = new BlockEntityType<>(TreeFarmBedBlockEntity::new,
                HydrofarmBlocks.TREE_FARM_BED.get());
            return TREE_FARM_BED_BE;
        });

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<HusbandryBedBlockEntity>> HUSBANDRY_BED_BE_HOLDER =
        BES.register("husbandry_bed", () -> {
            HUSBANDRY_BED_BE = new BlockEntityType<>(HusbandryBedBlockEntity::new,
                HydrofarmBlocks.HUSBANDRY_BED.get());
            return HUSBANDRY_BED_BE;
        });

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ButcherBedBlockEntity>> BUTCHER_BED_BE_HOLDER =
        BES.register("butcher_bed", () -> {
            BUTCHER_BED_BE = new BlockEntityType<>(ButcherBedBlockEntity::new,
                HydrofarmBlocks.BUTCHER_BED.get());
            return BUTCHER_BED_BE;
        });

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<HydroelectricGeneratorBlockEntity>> HYDROELECTRIC_GENERATOR_BE_HOLDER =
        BES.register("hydroelectric_generator", () -> {
            HYDROELECTRIC_GENERATOR_BE = new BlockEntityType<>(HydroelectricGeneratorBlockEntity::new,
                HydrofarmBlocks.HYDROELECTRIC_GENERATOR.get());
            return HYDROELECTRIC_GENERATOR_BE;
        });

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AutocrafterBlockEntity>> AUTOCRAFTER_BE_HOLDER =
        BES.register("autocrafter", () -> {
            AUTOCRAFTER_BE = new BlockEntityType<>(AutocrafterBlockEntity::new,
                HydrofarmBlocks.AUTOCRAFTER.get());
            return AUTOCRAFTER_BE;
        });

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RepulserBlockEntity>> REPULSER_BE_HOLDER =
        BES.register("repulser", () -> {
            REPULSER_BE = new BlockEntityType<>(RepulserBlockEntity::new,
                HydrofarmBlocks.REPULSER.get());
            return REPULSER_BE;
        });

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MendingStationBlockEntity>> MENDING_STATION_BE_HOLDER =
        BES.register("mending_station", () -> {
            MENDING_STATION_BE = new BlockEntityType<>(MendingStationBlockEntity::new,
                HydrofarmBlocks.MENDING_STATION.get());
            return MENDING_STATION_BE;
        });

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EnergyCellBlockEntity>> ENERGY_CELL_BE_HOLDER =
        BES.register("energy_cell", () -> {
            ENERGY_CELL_BE = new BlockEntityType<>(EnergyCellBlockEntity::new,
                HydrofarmBlocks.ENERGY_CELL.get());
            return ENERGY_CELL_BE;
        });

    private HydrofarmBlockEntities() {}
}
