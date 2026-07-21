package com.kestalkayden.hydrofarm.client;

import com.kestalkayden.hydrofarm.block.HydrofarmBlockEntities;
import com.kestalkayden.hydrofarm.fluid.HydrofarmFluids;
import com.kestalkayden.hydrofarm.menu.HydrofarmMenus;

import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** All client-only mod-bus wiring lives here, isolated from the {@code @Mod} class.
 *
 *  <p>This separation is load-bearing on a dedicated server: the JVM verifies every method of a
 *  class when that class is linked, and {@code @Mod} is instantiated during {@code constructMods}.
 *  If the renderer/screen/config references lived on the {@code @Mod} class itself, verifying it
 *  would force-load vanilla {@code net.minecraft.client.*} classes (e.g. {@code Screen}) which the
 *  dedicated server strips — crashing before any {@code Dist.CLIENT} runtime guard can run. By
 *  keeping them here and touching this class only via a guarded {@code invokestatic} (method
 *  resolution is lazy), the server never loads or verifies any of it. */
public final class HydrofarmNeoForgeClient {
    private HydrofarmNeoForgeClient() {}

    public static void init(IEventBus modBus, ModContainer container) {
        modBus.addListener(HydrofarmNeoForgeClient::onRegisterRenderers);
        modBus.addListener(HydrofarmNeoForgeClient::onRegisterMenuScreens);
        modBus.addListener(HydrofarmNeoForgeClient::onRegisterFluidModels);
        // Config button on the Mods screen — the NeoForge twin of the Fabric ModMenu entrypoint,
        // opening the same shared config screen.
        container.registerExtensionPoint(
            IConfigScreenFactory.class,
            (c, parent) -> new HydrofarmConfigScreen(parent));
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(HydrofarmBlockEntities.LIQUID_TANK_BE, LiquidTankRenderer::new);
        event.registerBlockEntityRenderer(HydrofarmBlockEntities.HYDROPONICS_BED_BE, HydroponicsBedRenderer::new);
        event.registerBlockEntityRenderer(HydrofarmBlockEntities.TREE_FARM_BED_BE, HydroponicsBedRenderer::new);
        event.registerBlockEntityRenderer(HydrofarmBlockEntities.HUSBANDRY_BED_BE, AnimalBedRenderer::new);
        event.registerBlockEntityRenderer(HydrofarmBlockEntities.BUTCHER_BED_BE, AnimalBedRenderer::new);
        event.registerBlockEntityRenderer(HydrofarmBlockEntities.AUTOCRAFTER_BE, AutocrafterRenderer::new);
        event.registerBlockEntityRenderer(HydrofarmBlockEntities.MENDING_STATION_BE, MendingStationRenderer::new);
    }

    /** Registers our fluids' sprites with the vanilla FluidStateModelSet — the NeoForge twin of the
     *  Fabric FluidRenderingRegistry calls in HydrofarmClient, kept deliberately in sync.
     *
     *  <p>Without this NeoForge logs "Missing FluidModel for fluid 'hydrofarm:liquid_xp'" and any
     *  third-party tank that renders our fluid (backpacks, Jade, JEI) draws the magenta/black
     *  missing-model fallback. Tint is null on both — the colour is baked into the textures. Liquid
     *  XP intentionally uses its still sprite for the flow slot too, matching Fabric. */
    private static void onRegisterFluidModels(RegisterFluidModelsEvent event) {
        Material xpStill = new Material(Identifier.fromNamespaceAndPath("hydrofarm", "block/liquid_xp_still"));
        event.register(new FluidModel.Unbaked(xpStill, xpStill, null, null),
            HydrofarmFluids.LIQUID_XP_HOLDER);

        Material milkStill = new Material(Identifier.fromNamespaceAndPath("hydrofarm", "block/fluid_milk_still"));
        Material milkFlow  = new Material(Identifier.fromNamespaceAndPath("hydrofarm", "block/fluid_milk_flow"));
        event.register(new FluidModel.Unbaked(milkStill, milkFlow, null, null),
            HydrofarmFluids.FLUID_MILK_HOLDER);
    }

    /** Client-only screen registration via NeoForge's RegisterMenuScreensEvent — vanilla
     *  MenuScreens.register is private. */
    private static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(HydrofarmMenus.HYDROPONICS_BED.get(), HydroponicsBedScreen::new);
        event.register(HydrofarmMenus.ANIMAL_BED.get(), AnimalBedScreen::new);
        event.register(HydrofarmMenus.AUTOCRAFTER.get(), AutocrafterScreen::new);
        event.register(HydrofarmMenus.ITEM_TERMINAL.get(), ItemTerminalScreen::new);
        event.register(HydrofarmMenus.LIQUID_TERMINAL.get(), LiquidTerminalScreen::new);
        event.register(HydrofarmMenus.MENDING_STATION.get(), MendingStationScreen::new);
    }
}
