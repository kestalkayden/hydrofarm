package com.kestalkayden.hydrofarm.platform;

import java.nio.file.Path;

import net.neoforged.fml.loading.FMLPaths;

/** NeoForge implementation of {@link ConfigApi}. */
public final class NeoForgeConfigApi implements ConfigApi {

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }
}
