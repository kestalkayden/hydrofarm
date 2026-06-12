package com.kestalkayden.hydrofarm.block;

import com.kestalkayden.hydrofarm.HydrofarmNeoForge;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class HydrofarmBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(HydrofarmNeoForge.MOD_ID);
    public static final DeferredRegister.Items  ITEMS  = DeferredRegister.createItems(HydrofarmNeoForge.MOD_ID);

    public static final DeferredBlock<LiquidTankBlock> LIQUID_TANK = BLOCKS.register("liquid_tank", id -> {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return new LiquidTankBlock(BlockBehaviour.Properties.of()
            .strength(1.0F)
            .sound(SoundType.GLASS)
            .noOcclusion()
            .mapColor(MapColor.METAL)
            .lightLevel(state -> state.getValue(LiquidTankBlock.LIT) ? 12 : 0)
            .setId(key));
    });

    public static final DeferredItem<BlockItem> LIQUID_TANK_ITEM = ITEMS.register("liquid_tank", id -> {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return new BlockItem(LIQUID_TANK.get(),
            new Item.Properties().setId(key).useBlockDescriptionPrefix());
    });

    public static final DeferredBlock<SprinklerBlock> SPRINKLER = BLOCKS.register("sprinkler", id -> {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return new SprinklerBlock(BlockBehaviour.Properties.of()
            .strength(1.0F)
            .sound(SoundType.METAL)
            .noOcclusion()
            .mapColor(MapColor.METAL)
            .setId(key));
    });

    public static final DeferredItem<BlockItem> SPRINKLER_ITEM = ITEMS.register("sprinkler", id -> {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return new BlockItem(SPRINKLER.get(),
            new Item.Properties().setId(key).useBlockDescriptionPrefix());
    });

    public static final DeferredBlock<LiquidSiphonBlock> LIQUID_SIPHON = BLOCKS.register("liquid_siphon", id -> {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return new LiquidSiphonBlock(BlockBehaviour.Properties.of()
            .strength(1.5F)
            .sound(SoundType.METAL)
            .mapColor(MapColor.METAL)
            .setId(key));
    });

    public static final DeferredItem<BlockItem> LIQUID_SIPHON_ITEM = ITEMS.register("liquid_siphon", id -> {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return new BlockItem(LIQUID_SIPHON.get(),
            new Item.Properties().setId(key).useBlockDescriptionPrefix());
    });

    public static final DeferredBlock<LiquidPipeBlock> LIQUID_PIPE = BLOCKS.register("liquid_pipe", id -> {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return new LiquidPipeBlock(BlockBehaviour.Properties.of()
            .strength(0.5F)
            .sound(SoundType.METAL)
            .noOcclusion()
            .mapColor(MapColor.METAL)
            .setId(key));
    });

    public static final DeferredItem<BlockItem> LIQUID_PIPE_ITEM = ITEMS.register("liquid_pipe", id -> {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        List<Component> lore = List.of(
            Component.translatable("item.hydrofarm.liquid_pipe.tooltip1").withStyle(ChatFormatting.GRAY),
            Component.translatable("item.hydrofarm.liquid_pipe.tooltip2").withStyle(ChatFormatting.GRAY));
        return new BlockItem(LIQUID_PIPE.get(),
            new Item.Properties().setId(key).useBlockDescriptionPrefix()
                .component(DataComponents.LORE, new ItemLore(lore)));
    });

    /** No item/tab entry — created by promoting a placed liquid pipe and drops the pipe. */
    public static final DeferredBlock<LiquidPipeTerminalBlock> LIQUID_PIPE_TERMINAL = BLOCKS.register("liquid_pipe_terminal", id -> {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return new LiquidPipeTerminalBlock(BlockBehaviour.Properties.of()
            .strength(0.5F)
            .sound(SoundType.METAL)
            .noOcclusion()
            .mapColor(MapColor.METAL)
            .setId(key));
    });

    public static final DeferredBlock<ItemPipeBlock> ITEM_PIPE = BLOCKS.register("item_pipe", id -> {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return new ItemPipeBlock(BlockBehaviour.Properties.of()
            .strength(0.5F)
            .sound(SoundType.METAL)
            .noOcclusion()
            .mapColor(MapColor.COLOR_BLACK)
            .setId(key));
    });

    public static final DeferredItem<BlockItem> ITEM_PIPE_ITEM = ITEMS.register("item_pipe", id -> {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        List<Component> lore = List.of(
            Component.translatable("item.hydrofarm.item_pipe.tooltip1").withStyle(ChatFormatting.GRAY),
            Component.translatable("item.hydrofarm.item_pipe.tooltip2").withStyle(ChatFormatting.GRAY));
        return new BlockItem(ITEM_PIPE.get(),
            new Item.Properties().setId(key).useBlockDescriptionPrefix()
                .component(DataComponents.LORE, new ItemLore(lore)));
    });

    /** No item/tab entry — a terminal is created by promoting a placed item pipe and drops the pipe. */
    public static final DeferredBlock<ItemPipeTerminalBlock> ITEM_PIPE_TERMINAL = BLOCKS.register("item_pipe_terminal", id -> {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return new ItemPipeTerminalBlock(BlockBehaviour.Properties.of()
            .strength(0.5F)
            .sound(SoundType.METAL)
            .noOcclusion()
            .mapColor(MapColor.COLOR_BLACK)
            .setId(key));
    });

    public static final DeferredBlock<XpDrainBlock> XP_DRAIN = BLOCKS.register("xp_drain", id -> {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return new XpDrainBlock(BlockBehaviour.Properties.of()
            .strength(1.5F)
            .sound(SoundType.METAL)
            .noOcclusion()
            .mapColor(MapColor.COLOR_BLACK)
            .setId(key));
    });

    public static final DeferredItem<BlockItem> XP_DRAIN_ITEM = ITEMS.register("xp_drain", id -> {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return new BlockItem(XP_DRAIN.get(),
            new Item.Properties().setId(key).useBlockDescriptionPrefix());
    });

    public static final DeferredBlock<HydroponicsBedBlock> HYDROPONICS_BED = BLOCKS.register("hydroponics_bed", id -> {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return new HydroponicsBedBlock(BlockBehaviour.Properties.of()
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
            .setId(key));
    });

    public static final DeferredItem<BlockItem> HYDROPONICS_BED_ITEM = ITEMS.register("hydroponics_bed", id -> {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return new BlockItem(HYDROPONICS_BED.get(),
            new Item.Properties().setId(key).useBlockDescriptionPrefix());
    });

    public static final DeferredBlock<TreeFarmBedBlock> TREE_FARM_BED = BLOCKS.register("tree_farm_bed", id -> {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return new TreeFarmBedBlock(BlockBehaviour.Properties.of()
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
            .setId(key));
    });

    public static final DeferredItem<BlockItem> TREE_FARM_BED_ITEM = ITEMS.register("tree_farm_bed", id -> {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return new BlockItem(TREE_FARM_BED.get(),
            new Item.Properties().setId(key).useBlockDescriptionPrefix());
    });

    public static final DeferredBlock<HusbandryBedBlock> HUSBANDRY_BED = BLOCKS.register("husbandry_bed", id -> {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return new HusbandryBedBlock(BlockBehaviour.Properties.of()
            .strength(1.0F)
            .sound(SoundType.WOOL)
            .noOcclusion()
            .mapColor(MapColor.WOOL)
            .setId(key));
    });

    public static final DeferredItem<BlockItem> HUSBANDRY_BED_ITEM = ITEMS.register("husbandry_bed", id -> {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        Component line = Component.translatable("item.hydrofarm.husbandry_bed.tooltip")
            .withStyle(ChatFormatting.GRAY);
        return new BlockItem(HUSBANDRY_BED.get(),
            new Item.Properties().setId(key).useBlockDescriptionPrefix()
                .component(DataComponents.LORE, new ItemLore(List.of(line))));
    });

    public static final DeferredBlock<ButcherBedBlock> BUTCHER_BED = BLOCKS.register("butcher_bed", id -> {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return new ButcherBedBlock(BlockBehaviour.Properties.of()
            .strength(1.0F)
            .sound(SoundType.STONE)
            .noOcclusion()
            .mapColor(MapColor.NETHER)
            .setId(key));
    });

    public static final DeferredItem<BlockItem> BUTCHER_BED_ITEM = ITEMS.register("butcher_bed", id -> {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        Component line = Component.translatable("item.hydrofarm.butcher_bed.tooltip")
            .withStyle(ChatFormatting.GRAY);
        return new BlockItem(BUTCHER_BED.get(),
            new Item.Properties().setId(key).useBlockDescriptionPrefix()
                .component(DataComponents.LORE, new ItemLore(List.of(line))));
    });

    public static final DeferredBlock<HydroelectricGeneratorBlock> HYDROELECTRIC_GENERATOR =
        BLOCKS.register("hydroelectric_generator", id -> {
            ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
            return new HydroelectricGeneratorBlock(BlockBehaviour.Properties.of()
                .strength(2.0F)
                .sound(SoundType.METAL)
                .mapColor(MapColor.METAL)
                .lightLevel(state -> state.getValue(HydroelectricGeneratorBlock.WORKING) ? 4 : 0)
                .setId(key));
        });

    public static final DeferredItem<BlockItem> HYDROELECTRIC_GENERATOR_ITEM =
        ITEMS.register("hydroelectric_generator", id -> {
            ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
            return new BlockItem(HYDROELECTRIC_GENERATOR.get(),
                new Item.Properties().setId(key).useBlockDescriptionPrefix());
        });

    public static final DeferredBlock<EnergyPipeBlock> ENERGY_PIPE = BLOCKS.register("energy_pipe", id -> {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return new EnergyPipeBlock(BlockBehaviour.Properties.of()
            .strength(0.5F)
            .sound(SoundType.METAL)
            .noOcclusion()
            .mapColor(MapColor.COLOR_RED)
            .setId(key));
    });

    public static final DeferredItem<BlockItem> ENERGY_PIPE_ITEM = ITEMS.register("energy_pipe", id -> {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return new BlockItem(ENERGY_PIPE.get(), new Item.Properties().setId(key).useBlockDescriptionPrefix());
    });

    public static final DeferredBlock<AutocrafterBlock> AUTOCRAFTER = BLOCKS.register("autocrafter", id -> {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return new AutocrafterBlock(BlockBehaviour.Properties.of()
            .strength(2.0F)
            .sound(SoundType.METAL)
            .mapColor(MapColor.METAL)
            .setId(key));
    });

    public static final DeferredItem<BlockItem> AUTOCRAFTER_ITEM = ITEMS.register("autocrafter", id -> {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return new BlockItem(AUTOCRAFTER.get(), new Item.Properties().setId(key).useBlockDescriptionPrefix());
    });

    public static final DeferredBlock<RepulserBlock> REPULSER = BLOCKS.register("repulser", id -> {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return new RepulserBlock(BlockBehaviour.Properties.of()
            .strength(2.0F)
            .sound(SoundType.METAL)
            .mapColor(MapColor.COLOR_RED)
            .lightLevel(state -> state.getValue(RepulserBlock.ACTIVE) ? 8 : 0)
            .setId(key));
    });

    public static final DeferredItem<BlockItem> REPULSER_ITEM = ITEMS.register("repulser", id -> {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        List<Component> lore = List.of(
            Component.translatable("item.hydrofarm.repulser.tooltip1").withStyle(ChatFormatting.GRAY),
            Component.translatable("item.hydrofarm.repulser.tooltip2").withStyle(ChatFormatting.GRAY));
        return new BlockItem(REPULSER.get(),
            new Item.Properties().setId(key).useBlockDescriptionPrefix()
                .component(DataComponents.LORE, new ItemLore(lore)));
    });

    public static final DeferredBlock<MendingStationBlock> MENDING_STATION = BLOCKS.register("mending_station", id -> {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return new MendingStationBlock(BlockBehaviour.Properties.of()
            .strength(2.0F)
            .sound(SoundType.METAL)
            .mapColor(MapColor.METAL)
            .lightLevel(state -> state.getValue(MendingStationBlock.REPAIRING) ? 8 : 0)
            .setId(key));
    });

    public static final DeferredItem<BlockItem> MENDING_STATION_ITEM = ITEMS.register("mending_station", id -> {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        List<Component> lore = List.of(
            Component.translatable("item.hydrofarm.mending_station.tooltip1").withStyle(ChatFormatting.GRAY),
            Component.translatable("item.hydrofarm.mending_station.tooltip2").withStyle(ChatFormatting.GRAY));
        return new BlockItem(MENDING_STATION.get(),
            new Item.Properties().setId(key).useBlockDescriptionPrefix()
                .component(DataComponents.LORE, new ItemLore(lore)));
    });

    public static final DeferredBlock<EnergyCellBlock> ENERGY_CELL = BLOCKS.register("energy_cell", id -> {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return new EnergyCellBlock(BlockBehaviour.Properties.of()
            .strength(2.0F)
            .sound(SoundType.METAL)
            .mapColor(MapColor.METAL)
            .lightLevel(state -> state.getValue(EnergyCellBlock.LIT) ? 8 : 0)
            .setId(key));
    });

    public static final DeferredItem<BlockItem> ENERGY_CELL_ITEM = ITEMS.register("energy_cell", id -> {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return new BlockItem(ENERGY_CELL.get(),
            new Item.Properties().setId(key).useBlockDescriptionPrefix());
    });

    /** Planter is no longer a block — it's an Item installed into a bed quadrant via right-click. */
    public static final DeferredItem<Item> HYDROFARM_PLANTER_ITEM = ITEMS.register("hydrofarm_planter", id -> {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return new Item(new Item.Properties().setId(key));
    });

    public static final DeferredItem<Item> ANIMAL_CAPTURE_NET_ITEM = ITEMS.register("animal_capture_net", id -> {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return new com.kestalkayden.hydrofarm.item.AnimalCaptureNetItem(
            new Item.Properties().setId(key).stacksTo(1));
    });

    /** All 17 glowcube block-items in creative-tab order (undyed, then DyeColor order). Glowcubes are
     *  inert full-block light-15 sources; only registration and the tab need a Java reference. This
     *  static block runs after BLOCKS/ITEMS are initialised above, so the DeferredRegisters exist. */
    public static final List<DeferredItem<BlockItem>> GLOWCUBE_ITEMS = new ArrayList<>();
    static {
        registerGlowcube("glowcube");
        for (DyeColor c : DyeColor.values()) registerGlowcube(c.getName() + "_glowcube");
    }

    /** Registers one always-on light-15 full-cube glowcube + its BlockItem, appending the item to
     *  {@link #GLOWCUBE_ITEMS}. Plain Block — no behaviour, no block entity. */
    private static void registerGlowcube(String name) {
        DeferredBlock<Block> block = BLOCKS.register(name, id -> {
            ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
            return new Block(BlockBehaviour.Properties.of()
                .strength(0.3F)
                .sound(SoundType.GLASS)
                .mapColor(MapColor.GLOW_LICHEN)
                .lightLevel(state -> 13)
                .noOcclusion()
                .setId(key));
        });
        GLOWCUBE_ITEMS.add(ITEMS.register(name, id -> {
            ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
            return new BlockItem(block.get(), new Item.Properties().setId(key).useBlockDescriptionPrefix());
        }));
    }

    private HydrofarmBlocks() {}
}
