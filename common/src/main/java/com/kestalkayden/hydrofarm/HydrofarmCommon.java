package com.kestalkayden.hydrofarm;

import net.minecraft.resources.Identifier;

/** Shared constants and helpers compiled once against vanilla Minecraft and bundled into both
 *  the Fabric and NeoForge jars. Real platform-agnostic logic (cluster beds, crop adapters,
 *  mover algorithms, …) migrates into this module; the per-loader trees keep only registration
 *  and the thin platform seams (fluid/item access, menu opening).
 *
 *  <p>This placeholder also serves as the smoke test for the NeoForm classpath: it imports a
 *  vanilla MC type, so if {@code :common:compileJava} succeeds the vanilla-mode setup is sound. */
public final class HydrofarmCommon {
    public static final String MOD_ID = "hydrofarm";

    private HydrofarmCommon() {}

    /** Namespaced identifier in the mod's namespace. */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
