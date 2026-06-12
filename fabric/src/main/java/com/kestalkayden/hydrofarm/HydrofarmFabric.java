package com.kestalkayden.hydrofarm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kestalkayden.hydrofarm.block.ClusterBedContainer;
import com.kestalkayden.hydrofarm.block.EnergyCellEnergyStorage;
import com.kestalkayden.hydrofarm.block.HydrofarmBlockEntities;
import com.kestalkayden.hydrofarm.block.HydrofarmBlocks;
import com.kestalkayden.hydrofarm.block.SprinklerFluidStorage;
import com.kestalkayden.hydrofarm.block.LiquidSiphonFluidStorage;
import com.kestalkayden.hydrofarm.block.LiquidTankFluidStorage;
import com.kestalkayden.hydrofarm.fluid.HydrofarmFluids;
import com.kestalkayden.hydrofarm.menu.HydrofarmMenus;

import com.kestalkayden.hydrofarm.item.AnimalCaptureNetItem;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public final class HydrofarmFabric implements ModInitializer {
    public static final String MOD_ID = "hydrofarm";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Hydrofarm (Fabric)");

        HydrofarmFluids.register();
        com.kestalkayden.hydrofarm.item.HydrofarmDataComponents.register();
        HydrofarmBlocks.register();
        HydrofarmBlockEntities.register();
        HydrofarmMenus.register();

        HydrofarmRefsBinder.bind();

        // Pre-interact hook: fires before entity.interact(), so an empty net wins against mobs whose
        // own mobInteract would consume the click first — villagers (trade GUI), allays (item swap),
        // golems, mountable animals. interactLivingEntity alone can't reach them. Guards on this
        // mod's own item; the standalone capturenet mod registers its own hook for its net.
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(entity instanceof LivingEntity living)) return InteractionResult.PASS;
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof AnimalCaptureNetItem)) return InteractionResult.PASS;
            return AnimalCaptureNetItem.tryCapture(stack, player, living, hand);
        });

        // Monster Repulser: block hostile spawns inside an active field (discard the moment they
        // load — 0 AI ticks, no flicker), and sweep wanderers each world tick.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!com.kestalkayden.hydrofarm.block.RepulserField.anyActive(world)) return;
            if (!com.kestalkayden.hydrofarm.block.RepulserTargeting.isRepellable(entity)) return;
            if (com.kestalkayden.hydrofarm.block.RepulserField.covers(world, entity.getX(), entity.getY(), entity.getZ())) {
                entity.discard();
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(
            com.kestalkayden.hydrofarm.block.RepulserField::sweep);

        FluidStorage.SIDED.registerForBlockEntity(
            (be, dir) -> be.fluidExposure(LiquidTankFluidStorage::new),
            HydrofarmBlockEntities.LIQUID_TANK_BE);

        FluidStorage.SIDED.registerForBlockEntity(
            (be, dir) -> new SprinklerFluidStorage(be),
            HydrofarmBlockEntities.SPRINKLER_BE);

        FluidStorage.SIDED.registerForBlockEntity(
            (be, dir) -> new LiquidSiphonFluidStorage(be),
            HydrofarmBlockEntities.LIQUID_SIPHON_BE);

        FluidStorage.SIDED.registerForBlockEntity(
            (be, dir) -> be.fluidExposure(com.kestalkayden.hydrofarm.block.ClusterBedFluidStorage::new),
            HydrofarmBlockEntities.HYDROPONICS_BED_BE);

        ItemStorage.SIDED.registerForBlockEntity(
            (be, dir) -> ContainerStorage.of(
                new ClusterBedContainer(be.clusterMembers()), dir),
            HydrofarmBlockEntities.HYDROPONICS_BED_BE);

        // Tree farm bed shares the same cluster-pool storage classes via its BE inheritance.
        FluidStorage.SIDED.registerForBlockEntity(
            (be, dir) -> be.fluidExposure(com.kestalkayden.hydrofarm.block.ClusterBedFluidStorage::new),
            HydrofarmBlockEntities.TREE_FARM_BED_BE);

        ItemStorage.SIDED.registerForBlockEntity(
            (be, dir) -> ContainerStorage.of(
                new ClusterBedContainer(be.clusterMembers()), dir),
            HydrofarmBlockEntities.TREE_FARM_BED_BE);

        FluidStorage.SIDED.registerForBlockEntity(
            (be, dir) -> be.fluidExposure(com.kestalkayden.hydrofarm.block.ClusterBedFluidStorage::new),
            HydrofarmBlockEntities.HUSBANDRY_BED_BE);

        ItemStorage.SIDED.registerForBlockEntity(
            (be, dir) -> ContainerStorage.of(
                new ClusterBedContainer(be.clusterMembers()), dir),
            HydrofarmBlockEntities.HUSBANDRY_BED_BE);

        FluidStorage.SIDED.registerForBlockEntity(
            (be, dir) -> be.fluidExposure(com.kestalkayden.hydrofarm.block.ClusterBedFluidStorage::new),
            HydrofarmBlockEntities.BUTCHER_BED_BE);

        ItemStorage.SIDED.registerForBlockEntity(
            (be, dir) -> ContainerStorage.of(
                new ClusterBedContainer(be.clusterMembers()), dir),
            HydrofarmBlockEntities.BUTCHER_BED_BE);

        // Hydroelectric Generator: water in (Fabric Transfer), energy out (Team Reborn Energy).
        FluidStorage.SIDED.registerForBlockEntity(
            (be, dir) -> be.fluidExposure(
                com.kestalkayden.hydrofarm.block.HydroelectricGeneratorWaterStorage::new),
            HydrofarmBlockEntities.HYDROELECTRIC_GENERATOR_BE);
        team.reborn.energy.api.EnergyStorage.SIDED.registerForBlockEntity(
            (be, dir) -> be.energyExposure(
                com.kestalkayden.hydrofarm.block.HydroelectricGeneratorEnergyStorage::new),
            HydrofarmBlockEntities.HYDROELECTRIC_GENERATOR_BE);

        // Autocrafter: insert-only energy buffer (tech cables push in; the BE also pulls itself).
        team.reborn.energy.api.EnergyStorage.SIDED.registerForBlockEntity(
            (be, dir) -> be.energyExposure(
                com.kestalkayden.hydrofarm.block.AutocrafterEnergyStorage::new),
            HydrofarmBlockEntities.AUTOCRAFTER_BE);
        // Autocrafter: template-filtered input (push ingredients in) + extract-only output (drain
        // the result), as one capability. The combined ItemView is a WorldlyContainer whose sided
        // rules ContainerStorage honours.
        ItemStorage.SIDED.registerForBlockEntity(
            (be, dir) -> ContainerStorage.of(be.itemView(), dir),
            HydrofarmBlockEntities.AUTOCRAFTER_BE);

        // Repulser: insert-only energy buffer (cables push in; the BE also pulls itself).
        team.reborn.energy.api.EnergyStorage.SIDED.registerForBlockEntity(
            (be, dir) -> be.energyExposure(
                com.kestalkayden.hydrofarm.block.InsertOnlyEnergyStorage::new),
            HydrofarmBlockEntities.REPULSER_BE);

        // Mending Station: pipe-automatable item view (damaged-in / repaired-out), insert-only Liquid
        // XP, insert-only energy (also self-pulled from the cable network).
        ItemStorage.SIDED.registerForBlockEntity(
            (be, dir) -> ContainerStorage.of(be.itemView(), dir),
            HydrofarmBlockEntities.MENDING_STATION_BE);
        FluidStorage.SIDED.registerForBlockEntity(
            (be, dir) -> be.fluidExposure(
                com.kestalkayden.hydrofarm.block.MendingStationXpStorage::new),
            HydrofarmBlockEntities.MENDING_STATION_BE);
        team.reborn.energy.api.EnergyStorage.SIDED.registerForBlockEntity(
            (be, dir) -> be.energyExposure(
                com.kestalkayden.hydrofarm.block.InsertOnlyEnergyStorage::new),
            HydrofarmBlockEntities.MENDING_STATION_BE);

        // Energy Cell: cluster-aware insert+extract energy bank (generators push in, consumers pull out).
        team.reborn.energy.api.EnergyStorage.SIDED.registerForBlockEntity(
            (be, dir) -> be.energyExposure(EnergyCellEnergyStorage::new),
            HydrofarmBlockEntities.ENERGY_CELL_BE);

        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
            Identifier.fromNamespaceAndPath(MOD_ID, "hydrofarm"),
            CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                .title(Component.translatable("itemGroup.hydrofarm.hydrofarm"))
                .icon(() -> new ItemStack(HydrofarmBlocks.LIQUID_TANK))
                .displayItems((params, output) -> {
                    output.accept(HydrofarmBlocks.LIQUID_TANK_ITEM);
                    output.accept(HydrofarmBlocks.SPRINKLER_ITEM);
                    output.accept(HydrofarmBlocks.LIQUID_SIPHON_ITEM);
                    output.accept(HydrofarmBlocks.LIQUID_PIPE_ITEM);
                    output.accept(HydrofarmBlocks.ITEM_PIPE_ITEM);
                    output.accept(HydrofarmBlocks.XP_DRAIN_ITEM);
                    output.accept(HydrofarmBlocks.HYDROPONICS_BED_ITEM);
                    output.accept(HydrofarmBlocks.TREE_FARM_BED_ITEM);
                    output.accept(HydrofarmBlocks.HUSBANDRY_BED_ITEM);
                    output.accept(HydrofarmBlocks.BUTCHER_BED_ITEM);
                    output.accept(HydrofarmBlocks.HYDROELECTRIC_GENERATOR_ITEM);
                    output.accept(HydrofarmBlocks.ENERGY_PIPE_ITEM);
                    output.accept(HydrofarmBlocks.AUTOCRAFTER_ITEM);
                    output.accept(HydrofarmBlocks.REPULSER_ITEM);
                    output.accept(HydrofarmBlocks.MENDING_STATION_ITEM);
                    output.accept(HydrofarmBlocks.ENERGY_CELL_ITEM);
                    for (var glowcube : HydrofarmBlocks.GLOWCUBE_ITEMS) output.accept(glowcube);
                    output.accept(HydrofarmBlocks.HYDROFARM_PLANTER_ITEM);
                    // Defer the Animal Capture Net to the standalone Capture Net mod when present.
                    // The item stays registered so existing inventories survive, but it's hidden
                    // from creative and its recipe is disabled (see recipe JSON) when capturenet
                    // ships its own copy.
                    if (!FabricLoader.getInstance().isModLoaded("capturenet")) {
                        output.accept(HydrofarmBlocks.ANIMAL_CAPTURE_NET_ITEM);
                    }
                })
                .build());
    }
}
