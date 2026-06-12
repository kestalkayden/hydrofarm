package com.kestalkayden.hydrofarm;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kestalkayden.hydrofarm.block.CauldronFluidHandler;
import com.kestalkayden.hydrofarm.block.EnergyCellEnergyHandler;
import com.kestalkayden.hydrofarm.block.HydrofarmBlockEntities;
import com.kestalkayden.hydrofarm.block.HydrofarmBlocks;
import com.kestalkayden.hydrofarm.block.SprinklerFluidHandler;
import com.kestalkayden.hydrofarm.block.LiquidSiphonFluidHandler;
import com.kestalkayden.hydrofarm.block.LiquidTankFluidHandler;
import com.kestalkayden.hydrofarm.fluid.HydrofarmFluids;
import com.kestalkayden.hydrofarm.item.AnimalCaptureNetItem;
import com.kestalkayden.hydrofarm.menu.HydrofarmMenus;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(HydrofarmNeoForge.MOD_ID)
public final class HydrofarmNeoForge {
    public static final String MOD_ID = "hydrofarm";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    public static final Supplier<CreativeModeTab> HYDROFARM_TAB = TABS.register("hydrofarm",
        () -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
            .title(Component.translatable("itemGroup.hydrofarm.hydrofarm"))
            .icon(() -> new ItemStack(HydrofarmBlocks.LIQUID_TANK.get()))
            .displayItems((params, output) -> {
                output.accept(HydrofarmBlocks.LIQUID_TANK_ITEM.get());
                output.accept(HydrofarmBlocks.SPRINKLER_ITEM.get());
                output.accept(HydrofarmBlocks.LIQUID_SIPHON_ITEM.get());
                output.accept(HydrofarmBlocks.LIQUID_PIPE_ITEM.get());
                output.accept(HydrofarmBlocks.ITEM_PIPE_ITEM.get());
                output.accept(HydrofarmBlocks.XP_DRAIN_ITEM.get());
                output.accept(HydrofarmBlocks.HYDROPONICS_BED_ITEM.get());
                output.accept(HydrofarmBlocks.TREE_FARM_BED_ITEM.get());
                output.accept(HydrofarmBlocks.HUSBANDRY_BED_ITEM.get());
                output.accept(HydrofarmBlocks.BUTCHER_BED_ITEM.get());
                output.accept(HydrofarmBlocks.HYDROELECTRIC_GENERATOR_ITEM.get());
                output.accept(HydrofarmBlocks.ENERGY_PIPE_ITEM.get());
                output.accept(HydrofarmBlocks.AUTOCRAFTER_ITEM.get());
                output.accept(HydrofarmBlocks.REPULSER_ITEM.get());
                output.accept(HydrofarmBlocks.MENDING_STATION_ITEM.get());
                output.accept(HydrofarmBlocks.ENERGY_CELL_ITEM.get());
                for (var glowcube : HydrofarmBlocks.GLOWCUBE_ITEMS) output.accept(glowcube.get());
                output.accept(HydrofarmBlocks.HYDROFARM_PLANTER_ITEM.get());
                // Defer the Animal Capture Net to the standalone Capture Net mod when present.
                // The item stays registered so existing inventories survive, but it's hidden
                // from creative and its recipe is disabled (see recipe JSON) when capturenet
                // ships its own copy.
                if (!ModList.get().isLoaded("capturenet")) {
                    output.accept(HydrofarmBlocks.ANIMAL_CAPTURE_NET_ITEM.get());
                }
            })
            .build());

    public HydrofarmNeoForge(IEventBus modBus, ModContainer container) {
        LOGGER.info("Initializing Hydrofarm (NeoForge)");

        HydrofarmFluids.FLUIDS.register(modBus);
        com.kestalkayden.hydrofarm.item.HydrofarmDataComponents.COMPONENTS.register(modBus);
        HydrofarmBlocks.BLOCKS.register(modBus);
        HydrofarmBlocks.ITEMS.register(modBus);
        HydrofarmBlockEntities.BES.register(modBus);
        HydrofarmMenus.MENUS.register(modBus);
        TABS.register(modBus);

        HydrofarmRefsBinder.bind();

        modBus.addListener(HydrofarmNeoForge::onRegisterCapabilities);

        // Pre-interact hook on the game event bus (PlayerInteractEvent fires there, not the mod
        // bus). Fires before entity.interact(), so an empty net wins against villagers/allays/
        // golems/mountable animals whose mobInteract would otherwise consume the click first.
        NeoForge.EVENT_BUS.addListener(HydrofarmNeoForge::onEntityInteract);

        // Monster Repulser: cancel hostile spawns inside an active field (the mob never appears),
        // and sweep wanderers each server level tick.
        NeoForge.EVENT_BUS.addListener(HydrofarmNeoForge::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(HydrofarmNeoForge::onLevelTick);

        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            modBus.addListener(HydrofarmNeoForge::onRegisterRenderers);
            modBus.addListener(HydrofarmNeoForge::onRegisterMenuScreens);
            // Config button on the Mods screen — the NeoForge twin of the Fabric ModMenu
            // entrypoint, opening the same shared config screen.
            container.registerExtensionPoint(
                net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                (c, parent) -> new com.kestalkayden.hydrofarm.client.HydrofarmConfigScreen(parent));
        }
    }

    /** Pre-interact hook: fires before entity.interact(), so an empty net wins against mobs whose
     *  own mobInteract would consume the click first — villagers (trade GUI), allays (item swap),
     *  golems, mountable animals. Guards on this mod's own item; the standalone capturenet mod
     *  registers its own hook for its net. */
    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof LivingEntity living)) return;
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof AnimalCaptureNetItem)) return;
        InteractionResult result = AnimalCaptureNetItem.tryCapture(
            stack, event.getEntity(), living, event.getHand());
        if (result.consumesAction()) {
            event.setCanceled(true);
            event.setCancellationResult(result);
        }
    }

    /** Cancel hostile spawns inside an active repulser field. Fires both sides — server only. */
    private static void onEntityJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        if (!com.kestalkayden.hydrofarm.block.RepulserField.anyActive(level)) return;
        net.minecraft.world.entity.Entity entity = event.getEntity();
        if (!com.kestalkayden.hydrofarm.block.RepulserTargeting.isRepellable(entity)) return;
        if (com.kestalkayden.hydrofarm.block.RepulserField.covers(level, entity.getX(), entity.getY(), entity.getZ())) {
            event.setCanceled(true);
        }
    }

    /** Periodic wander-in sweep per server level. */
    private static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            com.kestalkayden.hydrofarm.block.RepulserField.sweep(level);
        }
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(HydrofarmBlockEntities.LIQUID_TANK_BE,
            com.kestalkayden.hydrofarm.client.LiquidTankRenderer::new);
        event.registerBlockEntityRenderer(HydrofarmBlockEntities.HYDROPONICS_BED_BE,
            com.kestalkayden.hydrofarm.client.HydroponicsBedRenderer::new);
        event.registerBlockEntityRenderer(HydrofarmBlockEntities.TREE_FARM_BED_BE,
            com.kestalkayden.hydrofarm.client.HydroponicsBedRenderer::new);
        event.registerBlockEntityRenderer(HydrofarmBlockEntities.HUSBANDRY_BED_BE,
            com.kestalkayden.hydrofarm.client.AnimalBedRenderer::new);
        event.registerBlockEntityRenderer(HydrofarmBlockEntities.BUTCHER_BED_BE,
            com.kestalkayden.hydrofarm.client.AnimalBedRenderer::new);
        event.registerBlockEntityRenderer(HydrofarmBlockEntities.AUTOCRAFTER_BE,
            com.kestalkayden.hydrofarm.client.AutocrafterRenderer::new);
        event.registerBlockEntityRenderer(HydrofarmBlockEntities.MENDING_STATION_BE,
            com.kestalkayden.hydrofarm.client.MendingStationRenderer::new);
    }

    /** Client-only screen registration via NeoForge's RegisterMenuScreensEvent — vanilla
     *  MenuScreens.register is private. */
    private static void onRegisterMenuScreens(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) {
        event.register(HydrofarmMenus.HYDROPONICS_BED.get(),
            com.kestalkayden.hydrofarm.client.HydroponicsBedScreen::new);
        event.register(HydrofarmMenus.ANIMAL_BED.get(),
            com.kestalkayden.hydrofarm.client.AnimalBedScreen::new);
        event.register(HydrofarmMenus.AUTOCRAFTER.get(),
            com.kestalkayden.hydrofarm.client.AutocrafterScreen::new);
        event.register(HydrofarmMenus.ITEM_TERMINAL.get(),
            com.kestalkayden.hydrofarm.client.ItemTerminalScreen::new);
        event.register(HydrofarmMenus.LIQUID_TERMINAL.get(),
            com.kestalkayden.hydrofarm.client.LiquidTerminalScreen::new);
        event.register(HydrofarmMenus.MENDING_STATION.get(),
            com.kestalkayden.hydrofarm.client.MendingStationScreen::new);
    }

    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        // Vanilla cauldrons have no block entity, so register a block-level fluid capability for them
        // (parity with the support Fabric's Transfer API ships for free). Water/lava cauldrons then act
        // as pipe-connectable fluid sources/sinks in whole-level increments; powder snow isn't a fluid.
        event.registerBlock(
            Capabilities.Fluid.BLOCK,
            (level, pos, state, be, side) -> new CauldronFluidHandler(level, pos),
            Blocks.CAULDRON, Blocks.WATER_CAULDRON, Blocks.LAVA_CAULDRON);
        event.registerBlockEntity(
            Capabilities.Fluid.BLOCK,
            HydrofarmBlockEntities.LIQUID_TANK_BE,
            (be, side) -> be.fluidExposure(LiquidTankFluidHandler::new));
        event.registerBlockEntity(
            Capabilities.Fluid.BLOCK,
            HydrofarmBlockEntities.SPRINKLER_BE,
            (be, side) -> new SprinklerFluidHandler(be));
        event.registerBlockEntity(
            Capabilities.Fluid.BLOCK,
            HydrofarmBlockEntities.LIQUID_SIPHON_BE,
            (be, side) -> new LiquidSiphonFluidHandler(be));
        event.registerBlockEntity(
            Capabilities.Fluid.BLOCK,
            HydrofarmBlockEntities.HYDROPONICS_BED_BE,
            (be, side) -> be.fluidExposure(com.kestalkayden.hydrofarm.block.ClusterBedFluidHandler::new));

        event.registerBlockEntity(
            Capabilities.Item.BLOCK,
            HydrofarmBlockEntities.HYDROPONICS_BED_BE,
            (be, side) -> new com.kestalkayden.hydrofarm.block.ClusterBedItemHandler(be));

        // Tree farm bed reuses the same handlers via BE inheritance.
        event.registerBlockEntity(
            Capabilities.Fluid.BLOCK,
            HydrofarmBlockEntities.TREE_FARM_BED_BE,
            (be, side) -> be.fluidExposure(com.kestalkayden.hydrofarm.block.ClusterBedFluidHandler::new));

        event.registerBlockEntity(
            Capabilities.Item.BLOCK,
            HydrofarmBlockEntities.TREE_FARM_BED_BE,
            (be, side) -> new com.kestalkayden.hydrofarm.block.ClusterBedItemHandler(be));

        // Husbandry bed reuses the cluster handlers via BE inheritance.
        event.registerBlockEntity(
            Capabilities.Fluid.BLOCK,
            HydrofarmBlockEntities.HUSBANDRY_BED_BE,
            (be, side) -> be.fluidExposure(com.kestalkayden.hydrofarm.block.ClusterBedFluidHandler::new));

        event.registerBlockEntity(
            Capabilities.Item.BLOCK,
            HydrofarmBlockEntities.HUSBANDRY_BED_BE,
            (be, side) -> new com.kestalkayden.hydrofarm.block.ClusterBedItemHandler(be));

        // Butcher bed — same pattern.
        event.registerBlockEntity(
            Capabilities.Fluid.BLOCK,
            HydrofarmBlockEntities.BUTCHER_BED_BE,
            (be, side) -> be.fluidExposure(com.kestalkayden.hydrofarm.block.ClusterBedFluidHandler::new));

        event.registerBlockEntity(
            Capabilities.Item.BLOCK,
            HydrofarmBlockEntities.BUTCHER_BED_BE,
            (be, side) -> new com.kestalkayden.hydrofarm.block.ClusterBedItemHandler(be));

        // Hydroelectric Generator: water in (NeoForge fluid), energy out (NeoForge FE).
        event.registerBlockEntity(
            Capabilities.Fluid.BLOCK,
            HydrofarmBlockEntities.HYDROELECTRIC_GENERATOR_BE,
            (be, side) -> be.fluidExposure(
                com.kestalkayden.hydrofarm.block.HydroelectricGeneratorWaterHandler::new));
        event.registerBlockEntity(
            Capabilities.Energy.BLOCK,
            HydrofarmBlockEntities.HYDROELECTRIC_GENERATOR_BE,
            (be, side) -> be.energyExposure(
                com.kestalkayden.hydrofarm.block.HydroelectricGeneratorEnergyHandler::new));

        // Autocrafter: insert-only energy buffer (tech cables push in; the BE also pulls itself).
        event.registerBlockEntity(
            Capabilities.Energy.BLOCK,
            HydrofarmBlockEntities.AUTOCRAFTER_BE,
            (be, side) -> be.energyExposure(
                com.kestalkayden.hydrofarm.block.AutocrafterEnergyHandler::new));
        // Autocrafter: template-filtered input (push ingredients in) + extract-only output (drain
        // the crafted result), as one block item capability.
        event.registerBlockEntity(
            Capabilities.Item.BLOCK,
            HydrofarmBlockEntities.AUTOCRAFTER_BE,
            (be, side) -> be.itemExposure(
                com.kestalkayden.hydrofarm.block.AutocrafterItemHandler::new));

        // Repulser: insert-only energy buffer (cables push in; the BE also pulls itself).
        event.registerBlockEntity(
            Capabilities.Energy.BLOCK,
            HydrofarmBlockEntities.REPULSER_BE,
            (be, side) -> be.energyExposure(
                com.kestalkayden.hydrofarm.block.InsertOnlyEnergyHandler::new));

        // Mending Station: pipe-automatable item view (damaged-in / repaired-out), insert-only Liquid
        // XP, insert-only energy (also self-pulled from the cable network).
        event.registerBlockEntity(
            Capabilities.Item.BLOCK,
            HydrofarmBlockEntities.MENDING_STATION_BE,
            (be, side) -> be.itemExposure(
                com.kestalkayden.hydrofarm.block.MendingStationItemHandler::new));
        event.registerBlockEntity(
            Capabilities.Fluid.BLOCK,
            HydrofarmBlockEntities.MENDING_STATION_BE,
            (be, side) -> be.fluidExposure(
                com.kestalkayden.hydrofarm.block.MendingStationXpHandler::new));
        event.registerBlockEntity(
            Capabilities.Energy.BLOCK,
            HydrofarmBlockEntities.MENDING_STATION_BE,
            (be, side) -> be.energyExposure(
                com.kestalkayden.hydrofarm.block.InsertOnlyEnergyHandler::new));

        // Energy Cell: cluster-aware insert+extract energy bank (generators push in, consumers pull out).
        event.registerBlockEntity(
            Capabilities.Energy.BLOCK,
            HydrofarmBlockEntities.ENERGY_CELL_BE,
            (be, side) -> be.energyExposure(EnergyCellEnergyHandler::new));
    }
}
