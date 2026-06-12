package com.kestalkayden.hydrofarm;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kestalkayden.hydrofarm.platform.HydrofarmPlatform;

/** Hydrofarm's config — a hand-editable {@code config/hydrofarm.properties}, read once at first
 *  access (restart to apply). Loader-agnostic: only the config directory comes from the platform
 *  seam. The file is self-healing — on every load the canonical commented layout is written back
 *  with the user's values preserved, so options added in updates appear automatically and typos
 *  fall back to defaults (with a log warning) instead of crashing.
 *
 *  <p>Three groups: {@code client.*} (render-side FPS levers, only read by the client),
 *  {@code server.*} (tick-side performance), and {@code gameplay.*} (balance toggles, read by
 *  the side running the game logic — the GUI learns of them through synced menu data, so a
 *  dedicated server's settings win, as they should). */
public final class HydrofarmConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("hydrofarm-config");
    private static final String FILE_NAME = "hydrofarm.properties";

    // Shared option ranges (load-time clamp + the in-game config screen's setters).
    public static final int VIEW_DISTANCE_MIN = 16, VIEW_DISTANCE_MAX = 128;
    public static final int PUPPET_CAP_MIN = 0, PUPPET_CAP_MAX = 32;
    public static final int PARTICLE_COUNT_MIN = 0, PARTICLE_COUNT_MAX = 64;
    public static final int PARTICLE_INTERVAL_MIN = 1, PARTICLE_INTERVAL_MAX = 200;

    // Volatile: the config screen writes these from the client/render thread while the integrated
    // server thread reads them mid-tick (gameplay.* toggles apply live in singleplayer). Without it
    // the JMM lets the server thread read a stale value indefinitely; volatile reads are free on
    // x86 and these are simple independent flags (no compound invariants), so it's the whole fix.

    // ---- Client rendering (FPS levers) ----
    private static volatile boolean renderCrops = true;
    private static volatile int cropViewDistance = 48;
    private static volatile boolean renderAnimalPuppets = true;
    private static volatile int animalPuppetCap = 8;
    private static volatile int animalViewDistance = 32;

    // ---- Server performance ----
    private static volatile int sprinklerParticleCount = 12;
    private static volatile int sprinklerParticleInterval = 8;

    // ---- Gameplay ----
    private static volatile boolean hydroponicsBedXp = true;
    private static volatile boolean treeFarmBedXp = true;
    private static volatile boolean butcherBedXp = true;

    static {
        load();
    }

    private HydrofarmConfig() {}

    public static boolean renderCrops() { return renderCrops; }
    public static int cropViewDistance() { return cropViewDistance; }
    public static boolean renderAnimalPuppets() { return renderAnimalPuppets; }
    public static int animalPuppetCap() { return animalPuppetCap; }
    public static int animalViewDistance() { return animalViewDistance; }

    public static int sprinklerParticleCount() { return sprinklerParticleCount; }
    public static int sprinklerParticleInterval() { return sprinklerParticleInterval; }

    public static boolean hydroponicsBedXp() { return hydroponicsBedXp; }
    public static boolean treeFarmBedXp() { return treeFarmBedXp; }
    public static boolean butcherBedXp() { return butcherBedXp; }

    // ---- In-game config screen mutators -------------------------------------------------------
    // Values apply live (every consumer re-reads per frame/tick); call save() to persist. In
    // singleplayer the integrated server shares these statics, so gameplay toggles apply to the
    // running world too; on a dedicated server only the server's own file matters.

    public static void setRenderCrops(boolean v) { renderCrops = v; }
    public static void setCropViewDistance(int v) {
        cropViewDistance = Math.max(VIEW_DISTANCE_MIN, Math.min(VIEW_DISTANCE_MAX, v));
    }
    public static void setRenderAnimalPuppets(boolean v) { renderAnimalPuppets = v; }
    public static void setAnimalPuppetCap(int v) {
        animalPuppetCap = Math.max(PUPPET_CAP_MIN, Math.min(PUPPET_CAP_MAX, v));
    }
    public static void setAnimalViewDistance(int v) {
        animalViewDistance = Math.max(VIEW_DISTANCE_MIN, Math.min(VIEW_DISTANCE_MAX, v));
    }
    public static void setSprinklerParticleCount(int v) {
        sprinklerParticleCount = Math.max(PARTICLE_COUNT_MIN, Math.min(PARTICLE_COUNT_MAX, v));
    }
    public static void setSprinklerParticleInterval(int v) {
        sprinklerParticleInterval = Math.max(PARTICLE_INTERVAL_MIN, Math.min(PARTICLE_INTERVAL_MAX, v));
    }
    public static void setHydroponicsBedXp(boolean v) { hydroponicsBedXp = v; }
    public static void setTreeFarmBedXp(boolean v) { treeFarmBedXp = v; }
    public static void setButcherBedXp(boolean v) { butcherBedXp = v; }

    /** Persists the current values to {@code config/hydrofarm.properties} (canonical layout). */
    public static void save() {
        try {
            Path file = HydrofarmPlatform.config().configDir().resolve(FILE_NAME);
            Files.createDirectories(file.getParent());
            Files.writeString(file, canonicalFile(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            LOGGER.warn("Could not save config: {}", t.toString());
        }
    }

    private static void load() {
        Path file;
        try {
            file = HydrofarmPlatform.config().configDir().resolve(FILE_NAME);
        } catch (Throwable t) {
            LOGGER.warn("Config dir unavailable, using defaults: {}", t.toString());
            return;
        }
        Properties props = new Properties();
        String existing = null;
        try {
            if (Files.exists(file)) {
                existing = Files.readString(file, StandardCharsets.UTF_8);
                props.load(new StringReader(existing));
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read {}, using defaults: {}", file, e.toString());
        }

        renderCrops         = getBool(props, "client.renderCrops", renderCrops);
        cropViewDistance    = getInt(props, "client.cropViewDistance", cropViewDistance,
                                     VIEW_DISTANCE_MIN, VIEW_DISTANCE_MAX);
        renderAnimalPuppets = getBool(props, "client.renderAnimalPuppets", renderAnimalPuppets);
        animalPuppetCap     = getInt(props, "client.animalPuppetCap", animalPuppetCap,
                                     PUPPET_CAP_MIN, PUPPET_CAP_MAX);
        animalViewDistance  = getInt(props, "client.animalViewDistance", animalViewDistance,
                                     VIEW_DISTANCE_MIN, VIEW_DISTANCE_MAX);

        sprinklerParticleCount    = getInt(props, "server.sprinklerParticleCount", sprinklerParticleCount,
                                           PARTICLE_COUNT_MIN, PARTICLE_COUNT_MAX);
        sprinklerParticleInterval = getInt(props, "server.sprinklerParticleInterval", sprinklerParticleInterval,
                                           PARTICLE_INTERVAL_MIN, PARTICLE_INTERVAL_MAX);

        hydroponicsBedXp = getBool(props, "gameplay.hydroponicsBedXp", hydroponicsBedXp);
        treeFarmBedXp    = getBool(props, "gameplay.treeFarmBedXp", treeFarmBedXp);
        butcherBedXp     = getBool(props, "gameplay.butcherBedXp", butcherBedXp);

        // Self-heal: rewrite the canonical commented layout (preserving the values just loaded)
        // whenever the on-disk file is missing or stale — so new options show up after updates.
        String canonical = canonicalFile();
        if (!canonical.equals(existing)) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, canonical, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.warn("Could not write {}: {}", file, e.toString());
            }
        }
    }

    private static boolean getBool(Properties props, String key, boolean def) {
        String raw = props.getProperty(key);
        if (raw == null) return def;
        String v = raw.trim().toLowerCase();
        if (v.equals("true")) return true;
        if (v.equals("false")) return false;
        LOGGER.warn("Config {}={} is not true/false; using {}", key, raw, def);
        return def;
    }

    private static int getInt(Properties props, String key, int def, int min, int max) {
        String raw = props.getProperty(key);
        if (raw == null) return def;
        try {
            int v = Integer.parseInt(raw.trim());
            int clamped = Math.max(min, Math.min(max, v));
            if (clamped != v) LOGGER.warn("Config {}={} clamped to {} (range {}..{})", key, v, clamped, min, max);
            return clamped;
        } catch (NumberFormatException e) {
            LOGGER.warn("Config {}={} is not a number; using {}", key, raw, def);
            return def;
        }
    }

    private static String canonicalFile() {
        return """
            # Hydrofarm configuration. Changes apply on the next game launch.
            # Delete a line (or the whole file) to restore its default.

            # ---- Client rendering (FPS levers; only read by your own client) ----
            # Render the miniature crops growing on hydroponics / tree farm beds.
            client.renderCrops=%s
            # Distance (blocks) beyond which bed crops stop rendering. 16-128.
            client.cropViewDistance=%d
            # Render the roaming animal puppets on husbandry / butcher pens.
            client.renderAnimalPuppets=%s
            # Most animals drawn per pen at once (a lively sample, not a headcount). 0-32.
            client.animalPuppetCap=%d
            # Distance (blocks) beyond which pen animals stop rendering. 16-128.
            client.animalViewDistance=%d

            # ---- Server performance ----
            # Splash particles per sprinkler burst. 0 disables the spray visual. 0-64.
            server.sprinklerParticleCount=%d
            # Ticks between sprinkler particle bursts (8 = 2.5 bursts/sec). 1-200.
            server.sprinklerParticleInterval=%d

            # ---- Gameplay ----
            # Whether each bed type produces Liquid XP. When false, the bed makes no XP, its
            # XP gauge disappears from the GUI and Jade, and pipes can't pull XP from it
            # (XP already inside a bed can still be bottled out with a glass bottle).
            gameplay.hydroponicsBedXp=%s
            gameplay.treeFarmBedXp=%s
            gameplay.butcherBedXp=%s
            """.formatted(
                renderCrops, cropViewDistance, renderAnimalPuppets, animalPuppetCap, animalViewDistance,
                sprinklerParticleCount, sprinklerParticleInterval,
                hydroponicsBedXp, treeFarmBedXp, butcherBedXp);
    }
}
