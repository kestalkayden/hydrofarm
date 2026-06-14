package com.kestalkayden.hydrofarm.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kestalkayden.hydrofarm.HydrofarmConfig;
import com.kestalkayden.hydrofarm.block.AbstractClusterBedBlockEntity;
import com.kestalkayden.hydrofarm.block.AbstractClusterBedBlockEntity.Cluster;
import com.kestalkayden.hydrofarm.block.AnimalBedHost;
import com.kestalkayden.hydrofarm.util.PositionStagger;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Renders the captured animals of a husbandry/butcher bed as render-only entity puppets that
 *  <b>roam the whole connected pen</b> (Option B). The roaming math — position, facing, walk
 *  cycle — lives in {@link AnimalPenRoaming}; this class just routes and draws.
 *
 *  <p><b>Position-routed + bounded:</b> once per <em>tick</em> the first bed of a pen builds a shared
 *  render plan — every animal posed once, sorted by camera distance, truncated to the configured
 *  puppet cap, and each kept animal's {@link EntityRenderState} extracted once — and each bed draws the plotted
 *  animals standing above <em>its own</em> tile. So every animal is rendered by the bed it's standing
 *  on (ordinary per-block frustum culling suffices, no expanded render bounds), the per-frame cost is
 *  O(animals) not O(beds × animals), and a large pen shows a lively sample at a capped cost.
 *
 *  <p><b>Per-tick extraction (perf):</b> {@code extractEntity} — a full pose/feature render-state
 *  rebuild — is the expensive half and is the dominant cost in a farm view. The plan (and each
 *  animal's extracted render state) is rebuilt only when the game tick advances, so it runs once per
 *  tick instead of once per frame per bed; on the intervening frames each bed just re-uses the cached
 *  state and re-submits it. Poses are therefore sampled at the tick boundary (20 Hz) rather than
 *  interpolated per frame — at 0.4× scale within the configured view distance the difference
 *  is negligible, and {@code submit()} (the GL batch) still runs every frame.
 *
 *  <p>The engine is held per renderer instance, and Minecraft creates one renderer per BE type, so
 *  its agent/puppet cache is shared across all beds of a type — keeping an animal's animation
 *  continuous as it walks from one bed's tile onto a neighbour's. */
public class AnimalBedRenderer<T extends AbstractClusterBedBlockEntity & AnimalBedHost>
        implements BlockEntityRenderer<T, AnimalBedRenderer.State> {

    private static final Logger LOGGER = LoggerFactory.getLogger("hydrofarm-animal-render");
    /** One-shot WARN flags — render-thread errors were silently swallowed, which made "animals
     *  not rendering" undiagnosable from logs. Log the first occurrence, suppress the rest (these
     *  sites can fire per animal per frame). */
    private static boolean loggedExtractFailure;
    private static boolean loggedSubmitFailure;

    /** Max fresh puppet ENTITY builds (full NBT deserializations — the expensive part of first
     *  view) per pen per tick; the rest stay unrendered and pop in over the next ticks. Without
     *  the budget, a pen scrolling into view paid up to the whole puppet cap of NBT loads on a
     *  single frame — a visible hitch every time pens entered the view. */
    private static final int COLD_BUILD_BUDGET = 2;

    /** Scale factor for the rendered animal. 0.4x keeps wider species (cow ~0.56) from clipping
     *  the rails; sheep/chicken are still clearly visible. Tune visually. */
    private static final float ANIMAL_SCALE = 0.4F;
    /** Local Y of the pad surface the animals stand on. */
    private static final float PAD_Y = 0.375F;
    // The puppet view distance and per-pen render cap (each rendered animal is a full mob-model GL
    // batch every frame — the single biggest lever on farm FPS) are config-driven:
    // client.animalViewDistance (default 32; BE default is 64) and client.animalPuppetCap (default 8).

    private final EntityRenderDispatcher entityRenderer;
    private final AnimalPenRoaming roaming = new AnimalPenRoaming();

    /** Per-pen render plan, rebuilt once per tick by the first bed of the pen to render (see
     *  {@link #ensureFrame}). Keyed by the cluster's identity hash; render-thread only. */
    private final Long2ObjectOpenHashMap<ClusterFrame> frames = new Long2ObjectOpenHashMap<>();
    private long lastFrameSweep = Long.MIN_VALUE;

    public AnimalBedRenderer(BlockEntityRendererProvider.Context ctx) {
        this.entityRenderer = ctx.entityRenderer();
    }

    @Override
    public State createRenderState() { return new State(); }

    /** Default is 64 blocks; clamp the animal puppets to the configured distance. The bed
     *  block keeps rendering with its chunk — this gates only the per-frame puppet renders. */
    @Override
    public int getViewDistance() { return HydrofarmConfig.animalViewDistance(); }

    @Override
    public void extractRenderState(T be, State state, float partialTick, Vec3 cameraPos,
                                    ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderState.extractBase(be, state, crumbling);
        state.drawn.clear();
        if (!HydrofarmConfig.renderAnimalPuppets() || HydrofarmConfig.animalPuppetCap() <= 0) {
            // Toggled off: drop the cached plans + puppet entities so they aren't held while disabled.
            if (!frames.isEmpty()) frames.clear();
            roaming.clearAll();
            return;
        }
        Level level = be.getLevel();
        if (level == null) return;

        long gameTime = level.getGameTime();
        roaming.sweep(gameTime);
        sweepFrames(gameTime);

        Cluster cluster = be.findCluster();
        if (cluster == null) return;
        ClusterFrame frame = ensureFrame(cluster, gameTime, cameraPos, level);
        BlockPos bedPos = be.getBlockPos();

        // Draw the plotted animals standing over THIS bed's tile — the shared plan already chose,
        // posed, and extracted them once for the whole pen this tick. The per-frame work here is just
        // re-pointing the cached render state at this bed's light and queuing it for submit().
        for (Plotted p : frame.render) {
            AnimalPenRoaming.RoamPose rp = p.pose;
            if (Mth.floor(rp.x()) != bedPos.getX() || Mth.floor(rp.z()) != bedPos.getZ()) continue;
            EntityRenderState es = p.es;
            if (es == null) continue;
            // The animal is over this bed's tile, so the bed's light is the right light.
            es.lightCoords = state.lightCoords;
            state.drawn.add(new Drawn(es,
                (float) (rp.x() - bedPos.getX()), PAD_Y, (float) (rp.z() - bedPos.getZ())));
        }
    }

    /** Returns this pen's render plan for the current tick, building it on the first bed of the pen
     *  to render: enumerate the cluster's animals, pose each ONCE, sort by camera distance, keep the
     *  nearest puppet-cap, and extract each kept animal's {@link EntityRenderState} ONCE.
     *  Keyed by {@link Cluster#version()}; the game-tick token makes every later bed of the pen — and
     *  every later frame of the same tick — reuse it instead of re-enumerating and re-extracting
     *  (O(animals) per tick, not O(beds × animals) per frame). */
    private ClusterFrame ensureFrame(Cluster cluster, long gameTime, Vec3 cameraPos, Level level) {
        ClusterFrame cf = frames.get(cluster.version());
        if (cf == null) {
            cf = new ClusterFrame();
            frames.put(cluster.version(), cf);
        }
        cf.lastAccess = gameTime;
        if (cf.builtTick == gameTime) return cf;
        cf.builtTick = gameTime;
        // Keep last tick's plotted entries so unchanged puppets can reuse their extracted render
        // state on alternating ticks (see the extract loop below).
        List<Plotted> prevRender = cf.render.isEmpty() ? List.of() : List.copyOf(cf.render);
        cf.render.clear();

        // Sample poses at the tick boundary so the extracted render states below are valid for every
        // frame until the next tick.
        double t = gameTime;
        List<Plotted> all = new ArrayList<>();
        for (BlockPos home : cluster.members()) {
            if (!(level.getBlockEntity(home) instanceof AnimalBedHost host)) continue;
            int stalls = Math.min(host.stallCount(), State.MAX_STALLS);
            for (int s = 0; s < stalls; s++) {
                if (!host.hasHost(s)) continue;
                Identifier type = host.getHostType(s);
                CompoundTag nbt = host.getHostNbt(s);
                if (type == null || nbt == null) continue;
                AnimalPenRoaming.RoamPose rp =
                    roaming.computePose(home, s, type, cluster, cluster.version(), t, gameTime);
                all.add(new Plotted(home, s, type, nbt, rp));
            }
        }

        int cap = HydrofarmConfig.animalPuppetCap();
        if (all.size() > cap) {
            all.sort(Comparator.<Plotted>comparingDouble(p -> {
                double dx = p.pose.x() - cameraPos.x, dz = p.pose.z() - cameraPos.z;
                return dx * dx + dz * dz;
            }));
            for (int i = 0; i < cap; i++) cf.render.add(all.get(i));
        } else {
            cf.render.addAll(all);
        }

        // Extract each kept animal's render state for this tick (reused on every frame until the
        // next; submit() still runs per frame). Two spike-flattening bounds on the extraction —
        // without them EVERY visible pen rebuilt on the SAME first frame after each tick boundary,
        // a 20 Hz sawtooth:
        //  - Alternation: each puppet re-extracts only every OTHER tick, reusing last tick's state
        //    in between. Position stays 20 Hz smooth (it's applied per frame from the freshly
        //    computed pose, not baked into the state) — only limb/yaw sampling drops to 10 Hz,
        //    invisible at 0.4x scale. Halves the per-frame extractEntity burst.
        //  - Cold-build budget: a puppet with no cached entity needs a full NBT deserialization
        //    (the first-view hitch). At most COLD_BUILD_BUDGET per pen per tick; the rest stay
        //    unrendered (es == null) and pop in over the following ticks.
        int coldBuilds = 0;
        for (Plotted p : cf.render) {
            boolean reuseTick =
                ((PositionStagger.mix(p.home.asLong()) >>> 32) + p.stall + gameTime & 1L) != 0L;
            if (reuseTick) {
                EntityRenderState prev = findPrevState(prevRender, p);
                if (prev != null) {
                    p.es = prev;
                    continue;
                }
            }
            if (!roaming.hasPuppet(p.home, p.stall, p.type, p.nbt)
                    && ++coldBuilds > COLD_BUILD_BUDGET) continue;
            Entity entity = roaming.drivenPuppet(p.home, p.stall, p.type, p.nbt, p.pose, level, gameTime);
            if (entity == null) continue;
            try {
                EntityRenderState es = entityRenderer.extractEntity(entity, 0.0F);
                // Skip the shadow pass — a decorative puppet doesn't need one, and it's an extra
                // submit per animal (the dispatcher only draws a shadow when shadowPieces is set).
                es.shadowPieces.clear();
                p.es = es;
            } catch (Throwable thr) {
                // Malformed entity (e.g. NBT references a removed mod) — drop it, rebuild next tick.
                roaming.invalidate(p.home, p.stall);
                if (!loggedExtractFailure) {
                    loggedExtractFailure = true;
                    LOGGER.warn("Animal puppet render-state extraction failed for {} (further failures suppressed)",
                        p.type, thr);
                }
            }
        }
        return cf;
    }

    /** Last tick's extracted state for the same animal (home + stall + species), or null. Linear
     *  scan — both lists are at most the puppet cap. */
    private static EntityRenderState findPrevState(List<Plotted> prev, Plotted p) {
        for (Plotted q : prev) {
            if (q.es != null && q.stall == p.stall && q.home.equals(p.home) && q.type.equals(p.type)) {
                return q.es;
            }
        }
        return null;
    }

    /** Evict render plans for pens that stopped rendering. Rate-limited to ~twice per second. */
    private void sweepFrames(long gameTime) {
        if (gameTime == lastFrameSweep || (gameTime % 40L) != 0L) return;
        lastFrameSweep = gameTime;
        frames.values().removeIf(v -> gameTime - v.lastAccess > 100);
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector,
                       CameraRenderState camera) {
        for (Drawn d : state.drawn) {
            pose.pushPose();
            pose.translate(d.lx, d.ly, d.lz);
            pose.scale(ANIMAL_SCALE, ANIMAL_SCALE, ANIMAL_SCALE);
            try {
                entityRenderer.submit(d.state, camera, 0.0, 0.0, 0.0, pose, collector);
            } catch (Throwable thr) {
                // A render-thread crash on one animal shouldn't take down the whole frame.
                if (!loggedSubmitFailure) {
                    loggedSubmitFailure = true;
                    LOGGER.warn("Animal puppet submit failed (further failures suppressed)", thr);
                }
            }
            pose.popPose();
        }
    }

    /** One animal to draw this frame: its render state + block-local offset on the pad. */
    public static final class Drawn {
        final EntityRenderState state;
        final float lx, ly, lz;
        Drawn(EntityRenderState state, float lx, float ly, float lz) {
            this.state = state;
            this.lx = lx;
            this.ly = ly;
            this.lz = lz;
        }
    }

    /** One pen's shared render plan for a single tick: the bounded, pre-posed, pre-extracted set. */
    private static final class ClusterFrame {
        long builtTick = Long.MIN_VALUE;
        final List<Plotted> render = new ArrayList<>();
        long lastAccess;
    }

    /** One animal chosen for rendering this tick, with its pose computed and its render state
     *  extracted once (filled in by {@link #ensureFrame}; reused across the tick's frames). */
    private static final class Plotted {
        final BlockPos home;
        final int stall;
        final Identifier type;
        final CompoundTag nbt;
        final AnimalPenRoaming.RoamPose pose;
        EntityRenderState es;
        Plotted(BlockPos home, int stall, Identifier type, CompoundTag nbt, AnimalPenRoaming.RoamPose pose) {
            this.home = home;
            this.stall = stall;
            this.type = type;
            this.nbt = nbt;
            this.pose = pose;
        }
    }

    public static class State extends BlockEntityRenderState {
        public static final int MAX_STALLS = 4;  // defensive cap; v0.6 beds use 2
        public final List<Drawn> drawn = new ArrayList<>();
    }
}
