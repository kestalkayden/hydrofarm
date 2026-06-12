package com.kestalkayden.hydrofarm.client;

import com.kestalkayden.hydrofarm.block.HydrofarmBlockEntities;
import com.kestalkayden.hydrofarm.fluid.HydrofarmFluids;
import com.kestalkayden.hydrofarm.menu.HydrofarmMenus;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;

public final class HydrofarmClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BlockEntityRenderers.register(HydrofarmBlockEntities.LIQUID_TANK_BE, LiquidTankRenderer::new);
        BlockEntityRenderers.register(HydrofarmBlockEntities.HYDROPONICS_BED_BE, HydroponicsBedRenderer::new);
        BlockEntityRenderers.register(HydrofarmBlockEntities.TREE_FARM_BED_BE, HydroponicsBedRenderer::new);
        BlockEntityRenderers.register(HydrofarmBlockEntities.HUSBANDRY_BED_BE, AnimalBedRenderer::new);
        BlockEntityRenderers.register(HydrofarmBlockEntities.BUTCHER_BED_BE, AnimalBedRenderer::new);
        BlockEntityRenderers.register(HydrofarmBlockEntities.AUTOCRAFTER_BE, AutocrafterRenderer::new);
        BlockEntityRenderers.register(HydrofarmBlockEntities.MENDING_STATION_BE, MendingStationRenderer::new);

        MenuScreens.register(HydrofarmMenus.HYDROPONICS_BED, HydroponicsBedScreen::new);
        MenuScreens.register(HydrofarmMenus.ANIMAL_BED, AnimalBedScreen::new);
        MenuScreens.register(HydrofarmMenus.AUTOCRAFTER, AutocrafterScreen::new);
        MenuScreens.register(HydrofarmMenus.ITEM_TERMINAL, ItemTerminalScreen::new);
        MenuScreens.register(HydrofarmMenus.LIQUID_TERMINAL, LiquidTerminalScreen::new);
        MenuScreens.register(HydrofarmMenus.MENDING_STATION, MendingStationScreen::new);

        // Registers Liquid XP's still/flow sprite with the vanilla FluidStateModelSet so
        // FluidVariantRendering (used by Jade, JEI, etc.) resolves to our texture instead of
        // the pink/black missing-model fallback. Tint is null (color baked into the texture).
        Material xpStill = new Material(Identifier.fromNamespaceAndPath("hydrofarm", "block/liquid_xp_still"));
        FluidRenderingRegistry.register(
            HydrofarmFluids.LIQUID_XP,
            new FluidModel.Unbaked(xpStill, xpStill, null, null));

        Material milkStill = new Material(Identifier.fromNamespaceAndPath("hydrofarm", "block/fluid_milk_still"));
        Material milkFlow  = new Material(Identifier.fromNamespaceAndPath("hydrofarm", "block/fluid_milk_flow"));
        FluidRenderingRegistry.register(
            HydrofarmFluids.FLUID_MILK,
            new FluidModel.Unbaked(milkStill, milkFlow, null, null));
    }
}
