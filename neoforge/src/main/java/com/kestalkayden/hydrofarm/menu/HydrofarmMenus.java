package com.kestalkayden.hydrofarm.menu;

import com.kestalkayden.hydrofarm.HydrofarmNeoForge;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Menu registration (NeoForge). Uses IMenuTypeExtension.create which gives us a
 *  factory that receives a RegistryFriendlyByteBuf — server writes the BlockPos to it
 *  in openMenu, the client reads it from the buf in the menu constructor. */
public final class HydrofarmMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(Registries.MENU, HydrofarmNeoForge.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<HydroponicsBedMenu>> HYDROPONICS_BED =
        MENUS.register("hydroponics_bed", () -> IMenuTypeExtension.create(
            (id, inv, buf) -> new HydroponicsBedMenu(id, inv, buf.readBlockPos())));

    public static final DeferredHolder<MenuType<?>, MenuType<AnimalBedMenu>> ANIMAL_BED =
        MENUS.register("animal_bed", () -> IMenuTypeExtension.create(
            (id, inv, buf) -> new AnimalBedMenu(id, inv, buf.readBlockPos())));

    public static final DeferredHolder<MenuType<?>, MenuType<AutocrafterMenu>> AUTOCRAFTER =
        MENUS.register("autocrafter", () -> IMenuTypeExtension.create(
            (id, inv, buf) -> new AutocrafterMenu(id, inv, buf.readBlockPos())));

    public static final DeferredHolder<MenuType<?>, MenuType<ItemTerminalMenu>> ITEM_TERMINAL =
        MENUS.register("item_terminal", () -> IMenuTypeExtension.create(
            (id, inv, buf) -> new ItemTerminalMenu(id, inv, TerminalMenuData.STREAM_CODEC.decode(buf))));

    public static final DeferredHolder<MenuType<?>, MenuType<LiquidTerminalMenu>> LIQUID_TERMINAL =
        MENUS.register("liquid_terminal", () -> IMenuTypeExtension.create(
            (id, inv, buf) -> new LiquidTerminalMenu(id, inv, TerminalMenuData.STREAM_CODEC.decode(buf))));

    public static final DeferredHolder<MenuType<?>, MenuType<MendingStationMenu>> MENDING_STATION =
        MENUS.register("mending_station", () -> IMenuTypeExtension.create(
            (id, inv, buf) -> new MendingStationMenu(id, inv, buf.readBlockPos())));

    private HydrofarmMenus() {}
}
