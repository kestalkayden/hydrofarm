package com.kestalkayden.hydrofarm.platform;

import java.nio.file.Path;

/** Platform seam for locating the loader's config directory — the only piece of the config
 *  system that differs per loader. The actual file format and option set live loader-agnostic
 *  in {@link com.kestalkayden.hydrofarm.HydrofarmConfig}. */
public interface ConfigApi {

    /** The loader's config directory (e.g. {@code .minecraft/config}). */
    Path configDir();
}
