package com.kestalkayden.hydrofarm.platform;

import java.util.ServiceLoader;

/** Locates the per-loader implementations of the platform seams. Each loader ships a
 *  {@code META-INF/services} entry naming its implementation class; this resolves the single
 *  provider on first access and caches it.
 *
 *  <p>Seams are intentionally tiny and few — fluid access, item access, menu opening — so the
 *  overwhelming majority of gameplay logic stays loader-agnostic in {@code common}. */
public final class HydrofarmPlatform {

    private static final FluidApi FLUIDS = load(FluidApi.class);
    private static final ItemApi ITEMS = load(ItemApi.class);
    private static final EnergyApi ENERGY = load(EnergyApi.class);
    private static final MenuApi MENUS = load(MenuApi.class);
    private static final ConfigApi CONFIG = load(ConfigApi.class);

    private HydrofarmPlatform() {}

    public static ConfigApi config() {
        return CONFIG;
    }

    public static FluidApi fluids() {
        return FLUIDS;
    }

    public static ItemApi items() {
        return ITEMS;
    }

    public static EnergyApi energy() {
        return ENERGY;
    }

    public static MenuApi menus() {
        return MENUS;
    }

    private static <T> T load(Class<T> type) {
        return ServiceLoader.load(type, type.getClassLoader()).findFirst().orElseThrow(() ->
            new IllegalStateException("No Hydrofarm platform implementation registered for "
                + type.getName() + " — the loader module is missing its META-INF/services entry."));
    }
}
