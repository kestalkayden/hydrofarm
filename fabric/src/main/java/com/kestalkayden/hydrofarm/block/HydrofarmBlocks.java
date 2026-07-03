package com.kestalkayden.hydrofarm.block;

import com.kestalkayden.hydrofarm.HydrofarmFabric;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class HydrofarmBlocks {
    public static LiquidTankBlock LIQUID_TANK;
    public static BlockItem LIQUID_TANK_ITEM;
    public static SprinklerBlock SPRINKLER;
    public static BlockItem SPRINKLER_ITEM;
    public static LiquidSiphonBlock LIQUID_SIPHON;
    public static BlockItem LIQUID_SIPHON_ITEM;
    public static LiquidPipeBlock LIQUID_PIPE;
    public static BlockItem LIQUID_PIPE_ITEM;
    /** No BlockItem: created by promoting a placed liquid pipe; drops the pipe. */
    public static LiquidPipeTerminalBlock LIQUID_PIPE_TERMINAL;
    public static ItemPipeBlock ITEM_PIPE;
    public static BlockItem ITEM_PIPE_ITEM;
    /** No BlockItem: a terminal is created by promoting a placed item pipe and drops the pipe. */
    public static ItemPipeTerminalBlock ITEM_PIPE_TERMINAL;
    public static XpDrainBlock XP_DRAIN;
    public static BlockItem XP_DRAIN_ITEM;
    public static HydroponicsBedBlock HYDROPONICS_BED;
    public static BlockItem HYDROPONICS_BED_ITEM;
    public static TreeFarmBedBlock TREE_FARM_BED;
    public static BlockItem TREE_FARM_BED_ITEM;
    public static HusbandryBedBlock HUSBANDRY_BED;
    public static BlockItem HUSBANDRY_BED_ITEM;
    public static ButcherBedBlock BUTCHER_BED;
    public static BlockItem BUTCHER_BED_ITEM;
    public static HydroelectricGeneratorBlock HYDROELECTRIC_GENERATOR;
    public static BlockItem HYDROELECTRIC_GENERATOR_ITEM;
    public static EnergyPipeBlock ENERGY_PIPE;
    public static BlockItem ENERGY_PIPE_ITEM;
    public static AutocrafterBlock AUTOCRAFTER;
    public static BlockItem AUTOCRAFTER_ITEM;
    public static RepulserBlock REPULSER;
    public static BlockItem REPULSER_ITEM;
    public static MendingStationBlock MENDING_STATION;
    public static BlockItem MENDING_STATION_ITEM;
    public static EnergyCellBlock ENERGY_CELL;
    public static BlockItem ENERGY_CELL_ITEM;
    /** Planter is no longer a block — it's an Item installed into a bed quadrant via right-click. */
    public static Item HYDROFARM_PLANTER_ITEM;
    public static Item ANIMAL_CAPTURE_NET_ITEM;
    public static Item CAPTURE_CRATE_ITEM;
    /** All 17 glowcube block-items in creative-tab order (undyed, then DyeColor order). Glowcubes
     *  are inert full-block light-15 sources; only registration and the tab need a Java reference. */
    public static final List<BlockItem> GLOWCUBE_ITEMS = new ArrayList<>();

    private HydrofarmBlocks() {}

    public static void register() {
        Identifier tankId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "liquid_tank");
        ResourceKey<Block> tankKey = ResourceKey.create(Registries.BLOCK, tankId);
        LIQUID_TANK = Registry.register(BuiltInRegistries.BLOCK, tankId,
            new LiquidTankBlock(BlockBehaviour.Properties.of()
                .strength(1.0F)
                .sound(SoundType.GLASS)
                .noOcclusion()
                .mapColor(MapColor.METAL)
                .lightLevel(state -> state.getValue(LiquidTankBlock.LIT) ? 12 : 0)
                .setId(tankKey)));
        LIQUID_TANK_ITEM = registerBlockItem("liquid_tank", LIQUID_TANK);

        // Hydroponics bed registration moved up so HYDROFARM_PLANTER_ITEM registration
        // (below, after XP drain) doesn't conflict with field initialization order.

        Identifier sprinklerId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "sprinkler");
        ResourceKey<Block> sprinklerKey = ResourceKey.create(Registries.BLOCK, sprinklerId);
        SPRINKLER = Registry.register(BuiltInRegistries.BLOCK, sprinklerId,
            new SprinklerBlock(BlockBehaviour.Properties.of()
                .strength(1.0F)
                .sound(SoundType.METAL)
                .noOcclusion()
                .mapColor(MapColor.METAL)
                .setId(sprinklerKey)));
        SPRINKLER_ITEM = registerBlockItem("sprinkler", SPRINKLER);

        Identifier siphonId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "liquid_siphon");
        ResourceKey<Block> siphonKey = ResourceKey.create(Registries.BLOCK, siphonId);
        LIQUID_SIPHON = Registry.register(BuiltInRegistries.BLOCK, siphonId,
            new LiquidSiphonBlock(BlockBehaviour.Properties.of()
                .strength(1.5F)
                .sound(SoundType.METAL)
                .mapColor(MapColor.METAL)
                .setId(siphonKey)));
        LIQUID_SIPHON_ITEM = registerBlockItem("liquid_siphon", LIQUID_SIPHON);

        Identifier pipeId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "liquid_pipe");
        ResourceKey<Block> pipeKey = ResourceKey.create(Registries.BLOCK, pipeId);
        LIQUID_PIPE = Registry.register(BuiltInRegistries.BLOCK, pipeId,
            new LiquidPipeBlock(BlockBehaviour.Properties.of()
                .strength(0.5F)
                .sound(SoundType.METAL)
                .noOcclusion()
                .mapColor(MapColor.METAL)
                .setId(pipeKey)));
        LIQUID_PIPE_ITEM = registerBlockItemWithTooltip("liquid_pipe", LIQUID_PIPE,
            "item.hydrofarm.liquid_pipe.tooltip1", "item.hydrofarm.liquid_pipe.tooltip2");

        Identifier liquidTerminalId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "liquid_pipe_terminal");
        ResourceKey<Block> liquidTerminalKey = ResourceKey.create(Registries.BLOCK, liquidTerminalId);
        LIQUID_PIPE_TERMINAL = Registry.register(BuiltInRegistries.BLOCK, liquidTerminalId,
            new LiquidPipeTerminalBlock(BlockBehaviour.Properties.of()
                .strength(0.5F)
                .sound(SoundType.METAL)
                .noOcclusion()
                .mapColor(MapColor.METAL)
                .setId(liquidTerminalKey)));
        // Intentionally no BlockItem — placed by promoting a liquid pipe; its loot drops the pipe.

        Identifier itemPipeId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "item_pipe");
        ResourceKey<Block> itemPipeKey = ResourceKey.create(Registries.BLOCK, itemPipeId);
        ITEM_PIPE = Registry.register(BuiltInRegistries.BLOCK, itemPipeId,
            new ItemPipeBlock(BlockBehaviour.Properties.of()
                .strength(0.5F)
                .sound(SoundType.METAL)
                .noOcclusion()
                .mapColor(MapColor.COLOR_BLACK)
                .setId(itemPipeKey)));
        ITEM_PIPE_ITEM = registerBlockItemWithTooltip("item_pipe", ITEM_PIPE,
            "item.hydrofarm.item_pipe.tooltip1", "item.hydrofarm.item_pipe.tooltip2");

        Identifier itemTerminalId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "item_pipe_terminal");
        ResourceKey<Block> itemTerminalKey = ResourceKey.create(Registries.BLOCK, itemTerminalId);
        ITEM_PIPE_TERMINAL = Registry.register(BuiltInRegistries.BLOCK, itemTerminalId,
            new ItemPipeTerminalBlock(BlockBehaviour.Properties.of()
                .strength(0.5F)
                .sound(SoundType.METAL)
                .noOcclusion()
                .mapColor(MapColor.COLOR_BLACK)
                .setId(itemTerminalKey)));
        // Intentionally no BlockItem — placed by promoting an item pipe; its loot drops the pipe.

        Identifier xpDrainId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "xp_drain");
        ResourceKey<Block> xpDrainKey = ResourceKey.create(Registries.BLOCK, xpDrainId);
        XP_DRAIN = Registry.register(BuiltInRegistries.BLOCK, xpDrainId,
            new XpDrainBlock(BlockBehaviour.Properties.of()
                .strength(1.5F)
                .sound(SoundType.METAL)
                .noOcclusion()
                .mapColor(MapColor.COLOR_BLACK)
                .setId(xpDrainKey)));
        XP_DRAIN_ITEM = registerBlockItem("xp_drain", XP_DRAIN);

        Identifier bedId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "hydroponics_bed");
        ResourceKey<Block> bedKey = ResourceKey.create(Registries.BLOCK, bedId);
        HYDROPONICS_BED = Registry.register(BuiltInRegistries.BLOCK, bedId,
            new HydroponicsBedBlock(BlockBehaviour.Properties.of()
                .strength(1.0F)
                .sound(SoundType.METAL)
                .noOcclusion()
                .mapColor(MapColor.METAL)
                .lightLevel(state -> {
                    boolean anyInstalled = state.getValue(HydroponicsBedBlock.PLANTER_NW)
                        || state.getValue(HydroponicsBedBlock.PLANTER_NE)
                        || state.getValue(HydroponicsBedBlock.PLANTER_SW)
                        || state.getValue(HydroponicsBedBlock.PLANTER_SE);
                    return anyInstalled ? 4 : 0;
                })
                .setId(bedKey)));
        HYDROPONICS_BED_ITEM = registerBlockItem("hydroponics_bed", HYDROPONICS_BED);

        Identifier treeBedId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "tree_farm_bed");
        ResourceKey<Block> treeBedKey = ResourceKey.create(Registries.BLOCK, treeBedId);
        TREE_FARM_BED = Registry.register(BuiltInRegistries.BLOCK, treeBedId,
            new TreeFarmBedBlock(BlockBehaviour.Properties.of()
                .strength(1.0F)
                .sound(SoundType.WOOD)
                .noOcclusion()
                .mapColor(MapColor.WOOD)
                .lightLevel(state -> {
                    boolean anyInstalled = state.getValue(HydroponicsBedBlock.PLANTER_NW)
                        || state.getValue(HydroponicsBedBlock.PLANTER_NE)
                        || state.getValue(HydroponicsBedBlock.PLANTER_SW)
                        || state.getValue(HydroponicsBedBlock.PLANTER_SE);
                    return anyInstalled ? 4 : 0;
                })
                .setId(treeBedKey)));
        TREE_FARM_BED_ITEM = registerBlockItem("tree_farm_bed", TREE_FARM_BED);

        Identifier husbandryBedId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "husbandry_bed");
        ResourceKey<Block> husbandryBedKey = ResourceKey.create(Registries.BLOCK, husbandryBedId);
        HUSBANDRY_BED = Registry.register(BuiltInRegistries.BLOCK, husbandryBedId,
            new HusbandryBedBlock(BlockBehaviour.Properties.of()
                .strength(1.0F)
                .sound(SoundType.WOOL)
                .noOcclusion()
                .mapColor(MapColor.WOOL)
                .setId(husbandryBedKey)));
        HUSBANDRY_BED_ITEM = registerBlockItemWithTooltip("husbandry_bed", HUSBANDRY_BED,
            "item.hydrofarm.husbandry_bed.tooltip");

        Identifier butcherBedId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "butcher_bed");
        ResourceKey<Block> butcherBedKey = ResourceKey.create(Registries.BLOCK, butcherBedId);
        BUTCHER_BED = Registry.register(BuiltInRegistries.BLOCK, butcherBedId,
            new ButcherBedBlock(BlockBehaviour.Properties.of()
                .strength(1.0F)
                .sound(SoundType.STONE)
                .noOcclusion()
                .mapColor(MapColor.NETHER)
                .setId(butcherBedKey)));
        BUTCHER_BED_ITEM = registerBlockItemWithTooltip("butcher_bed", BUTCHER_BED,
            "item.hydrofarm.butcher_bed.tooltip");

        Identifier generatorId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "hydroelectric_generator");
        ResourceKey<Block> generatorKey = ResourceKey.create(Registries.BLOCK, generatorId);
        HYDROELECTRIC_GENERATOR = Registry.register(BuiltInRegistries.BLOCK, generatorId,
            new HydroelectricGeneratorBlock(BlockBehaviour.Properties.of()
                .strength(2.0F)
                .sound(SoundType.METAL)
                .mapColor(MapColor.METAL)
                .lightLevel(state -> state.getValue(HydroelectricGeneratorBlock.WORKING) ? 4 : 0)
                .setId(generatorKey)));
        HYDROELECTRIC_GENERATOR_ITEM = registerBlockItem("hydroelectric_generator", HYDROELECTRIC_GENERATOR);

        Identifier energyPipeId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "energy_pipe");
        ResourceKey<Block> energyPipeKey = ResourceKey.create(Registries.BLOCK, energyPipeId);
        ENERGY_PIPE = Registry.register(BuiltInRegistries.BLOCK, energyPipeId,
            new EnergyPipeBlock(BlockBehaviour.Properties.of()
                .strength(0.5F)
                .sound(SoundType.METAL)
                .noOcclusion()
                .mapColor(MapColor.COLOR_RED)
                .setId(energyPipeKey)));
        ENERGY_PIPE_ITEM = registerBlockItem("energy_pipe", ENERGY_PIPE);

        Identifier autocrafterId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "autocrafter");
        ResourceKey<Block> autocrafterKey = ResourceKey.create(Registries.BLOCK, autocrafterId);
        AUTOCRAFTER = Registry.register(BuiltInRegistries.BLOCK, autocrafterId,
            new AutocrafterBlock(BlockBehaviour.Properties.of()
                .strength(2.0F)
                .sound(SoundType.METAL)
                .mapColor(MapColor.METAL)
                .setId(autocrafterKey)));
        AUTOCRAFTER_ITEM = registerBlockItem("autocrafter", AUTOCRAFTER);

        Identifier repulserId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "repulser");
        ResourceKey<Block> repulserKey = ResourceKey.create(Registries.BLOCK, repulserId);
        REPULSER = Registry.register(BuiltInRegistries.BLOCK, repulserId,
            new RepulserBlock(BlockBehaviour.Properties.of()
                .strength(2.0F)
                .sound(SoundType.METAL)
                .mapColor(MapColor.COLOR_RED)
                .lightLevel(state -> state.getValue(RepulserBlock.ACTIVE) ? 8 : 0)
                .setId(repulserKey)));
        REPULSER_ITEM = registerBlockItemWithTooltip("repulser", REPULSER,
            "item.hydrofarm.repulser.tooltip1", "item.hydrofarm.repulser.tooltip2");

        Identifier mendingId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "mending_station");
        ResourceKey<Block> mendingKey = ResourceKey.create(Registries.BLOCK, mendingId);
        MENDING_STATION = Registry.register(BuiltInRegistries.BLOCK, mendingId,
            new MendingStationBlock(BlockBehaviour.Properties.of()
                .strength(2.0F)
                .sound(SoundType.METAL)
                .mapColor(MapColor.METAL)
                .lightLevel(state -> state.getValue(MendingStationBlock.REPAIRING) ? 8 : 0)
                .setId(mendingKey)));
        MENDING_STATION_ITEM = registerBlockItemWithTooltip("mending_station", MENDING_STATION,
            "item.hydrofarm.mending_station.tooltip1", "item.hydrofarm.mending_station.tooltip2");

        Identifier energyCellId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "energy_cell");
        ResourceKey<Block> energyCellKey = ResourceKey.create(Registries.BLOCK, energyCellId);
        ENERGY_CELL = Registry.register(BuiltInRegistries.BLOCK, energyCellId,
            new EnergyCellBlock(BlockBehaviour.Properties.of()
                .strength(2.0F)
                .sound(SoundType.METAL)
                .mapColor(MapColor.METAL)
                .lightLevel(state -> state.getValue(EnergyCellBlock.LIT) ? 8 : 0)
                .setId(energyCellKey)));
        ENERGY_CELL_ITEM = registerBlockItem("energy_cell", ENERGY_CELL);

        registerGlowcube("glowcube");
        for (DyeColor c : DyeColor.values()) registerGlowcube(c.getName() + "_glowcube");

        Identifier planterItemId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "hydrofarm_planter");
        ResourceKey<Item> planterItemKey = ResourceKey.create(Registries.ITEM, planterItemId);
        HYDROFARM_PLANTER_ITEM = Registry.register(BuiltInRegistries.ITEM, planterItemId,
            new Item(new Item.Properties().setId(planterItemKey)));

        Identifier netId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "animal_capture_net");
        ResourceKey<Item> netKey = ResourceKey.create(Registries.ITEM, netId);
        ANIMAL_CAPTURE_NET_ITEM = Registry.register(BuiltInRegistries.ITEM, netId,
            new com.kestalkayden.hydrofarm.item.AnimalCaptureNetItem(
                new Item.Properties().setId(netKey).stacksTo(1)));

        Identifier crateId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "capture_crate");
        ResourceKey<Item> crateKey = ResourceKey.create(Registries.ITEM, crateId);
        CAPTURE_CRATE_ITEM = Registry.register(BuiltInRegistries.ITEM, crateId,
            new com.kestalkayden.hydrofarm.item.CaptureCrateItem(
                new Item.Properties().setId(crateKey).stacksTo(1)));
    }

    private static BlockItem registerBlockItem(String name, Block block) {
        Identifier id = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, name);
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return Registry.register(BuiltInRegistries.ITEM, id,
            new BlockItem(block, new Item.Properties().setId(key).useBlockDescriptionPrefix()));
    }

    /** Adds an inventory tooltip line via the lore data component — renders as gray italic
     *  text under the item name. Used to clarify what each bed type does at a glance. */
    private static BlockItem registerBlockItemWithTooltip(String name, Block block, String... tooltipKeys) {
        Identifier id = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, name);
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        List<Component> lines = new java.util.ArrayList<>();
        for (String k : tooltipKeys) lines.add(Component.translatable(k).withStyle(ChatFormatting.GRAY));
        Item.Properties props = new Item.Properties()
            .setId(key)
            .useBlockDescriptionPrefix()
            .component(DataComponents.LORE, new ItemLore(lines));
        return Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, props));
    }

    /** Registers one always-on light-15 full-cube glowcube + its BlockItem, appending the item to
     *  {@link #GLOWCUBE_ITEMS} for the creative tab. Plain Block — no behaviour, no block entity. */
    private static void registerGlowcube(String name) {
        Identifier id = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, name);
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        Block block = Registry.register(BuiltInRegistries.BLOCK, id,
            new Block(BlockBehaviour.Properties.of()
                .strength(0.3F)
                .sound(SoundType.GLASS)
                .mapColor(MapColor.GLOW_LICHEN)
                .lightLevel(state -> 13)
                .noOcclusion()
                .setId(key)));
        GLOWCUBE_ITEMS.add(registerBlockItem(name, block));
    }
}
