package com.kestalkayden.hydrofarm.menu;

import com.kestalkayden.hydrofarm.HydrofarmFabric;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.MenuType;

/** Menu registration. Uses Fabric's ExtendedMenuType so we can send the bed's BlockPos
 *  to the client when opening — vanilla MenuType's constructor is private. */
public final class HydrofarmMenus {

    public static MenuType<HydroponicsBedMenu> HYDROPONICS_BED;
    public static MenuType<AnimalBedMenu> ANIMAL_BED;
    public static MenuType<AutocrafterMenu> AUTOCRAFTER;
    public static MenuType<ItemTerminalMenu> ITEM_TERMINAL;
    public static MenuType<LiquidTerminalMenu> LIQUID_TERMINAL;
    public static MenuType<MendingStationMenu> MENDING_STATION;

    private HydrofarmMenus() {}

    public static void register() {
        Identifier bedId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "hydroponics_bed");
        HYDROPONICS_BED = new ExtendedMenuType<>(
            HydroponicsBedMenu::new,
            BlockPos.STREAM_CODEC);
        Registry.register(BuiltInRegistries.MENU, bedId, HYDROPONICS_BED);

        Identifier animalBedId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "animal_bed");
        ANIMAL_BED = new ExtendedMenuType<>(
            AnimalBedMenu::new,
            BlockPos.STREAM_CODEC);
        Registry.register(BuiltInRegistries.MENU, animalBedId, ANIMAL_BED);

        Identifier autocrafterId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "autocrafter");
        AUTOCRAFTER = new ExtendedMenuType<>(
            AutocrafterMenu::new,
            BlockPos.STREAM_CODEC);
        Registry.register(BuiltInRegistries.MENU, autocrafterId, AUTOCRAFTER);

        Identifier itemTerminalId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "item_terminal");
        ITEM_TERMINAL = new ExtendedMenuType<>(
            ItemTerminalMenu::new,
            TerminalMenuData.STREAM_CODEC);
        Registry.register(BuiltInRegistries.MENU, itemTerminalId, ITEM_TERMINAL);

        Identifier liquidTerminalId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "liquid_terminal");
        LIQUID_TERMINAL = new ExtendedMenuType<>(
            LiquidTerminalMenu::new,
            TerminalMenuData.STREAM_CODEC);
        Registry.register(BuiltInRegistries.MENU, liquidTerminalId, LIQUID_TERMINAL);

        Identifier mendingId = Identifier.fromNamespaceAndPath(HydrofarmFabric.MOD_ID, "mending_station");
        MENDING_STATION = new ExtendedMenuType<>(
            MendingStationMenu::new,
            BlockPos.STREAM_CODEC);
        Registry.register(BuiltInRegistries.MENU, mendingId, MENDING_STATION);
    }
}
