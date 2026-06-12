package com.kestalkayden.hydrofarm.platform;

import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/** Fabric implementation of {@link ConfigApi}. */
public final class FabricConfigApi implements ConfigApi {

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }
}
