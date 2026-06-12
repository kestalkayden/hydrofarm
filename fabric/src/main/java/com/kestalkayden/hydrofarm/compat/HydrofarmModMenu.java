package com.kestalkayden.hydrofarm.compat;

import com.kestalkayden.hydrofarm.client.HydrofarmConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** ModMenu integration — gives Hydrofarm a "Configure" button in the mod list, opening the
 *  shared {@link HydrofarmConfigScreen}. ModMenu is compile-only (same pattern as the Jade
 *  plugin): this class loads only when ModMenu discovers it via the {@code modmenu} entrypoint,
 *  so the mod runs fine without ModMenu installed. */
public class HydrofarmModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return HydrofarmConfigScreen::new;
    }
}
