package com.kestalkayden.hydrofarm.client;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kestalkayden.hydrofarm.block.AbstractClusterBedBlockEntity.Cluster;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/** Client-only deterministic roaming engine for animal-bed pens. Given a captured animal's home
 *  (bed pos + stall) and the current time, it produces a position, facing, and walk speed so the
 *  animal ambles across the whole connected bed-top surface — walking legs, pausing, occasionally
 *  dipping its head to graze/peck.
 *
 *  <p><b>Deterministic + stateless-in-time.</b> An animal's pose is a pure function of
 *  {@code (homeBed, stall, gameTime+partialTick)} and the pen's shape, so every bed renderer
 *  computes identical positions with no shared mutable simulation, no owner election, and no
 *  cross-client desync. (The old monotony was the single sine loop — not determinism itself.)
 *
 *  <p><b>On-pen guarantee.</b> Each animal follows a hash-derived loop of {@link #PATH_LEN}
 *  waypoints, traversed ping-pong, where consecutive waypoints always sit in the same or an
 *  <em>adjacent</em> pen tile. A leg therefore never crosses a non-bed gap, so the path stays on
 *  the surface even for L-shaped clusters or ones with holes.
 *
 *  <p>The reconstructed render-only entity puppets are cached per {@code (homeBed, stall)} and
 *  shared across every bed that draws them (the renderer is a singleton per BE type), so the bed an
 *  animal happens to be standing on can drive and draw it while keeping the animation continuous as
 *  it crosses tile boundaries. Caches are swept when a pen stops being rendered. */
public final class AnimalPenRoaming {

    private static final Logger LOGGER = LoggerFactory.getLogger("hydrofarm-penroam");

    /** Waypoints per agent loop. Traversed ping-pong (0..N-1..0) so consecutive waypoints are
     *  always in the same or an adjacent pen tile. */
    private static final int PATH_LEN = 24;
    /** Agents (per home bed) untouched for this many ticks are evicted. 600t = 30s: eviction
     *  throws away the deserialized puppet entities, and rebuilding one is a full NBT entity load
     *  (the first-view hitch) — at the old 5s, just panning the camera away and back re-paid the
     *  whole pen's rebuild. The held memory is a handful of unticked entities per pen. */
    private static final int EVICT_TTL = 600;
    private static final int MAX_STALLS = 4;
    /** Head-down angle (degrees) at the peak of a graze/peck dip. */
    private static final double DIP_DEGREES = 32.0;

    // ---- Per-species ambient pacing (client cosmetic; intentionally NOT in the gameplay tables) -

    /** @param walkTicks  ticks to walk one leg   @param pauseTicks ticks paused at the waypoint
     *  @param jitter     in-tile offset radius     @param dipChance fraction of pauses that graze */
    private record Personality(int walkTicks, int pauseTicks, double jitter, double dipChance) {}

    private static final Personality DEFAULT = new Personality(36, 90, 0.25, 0.30);
    private static final Map<Identifier, Personality> PERSONALITY = new HashMap<>();
    static {
        PERSONALITY.put(mc("sheep"),   new Personality(36, 100, 0.25, 0.55));  // grazes a lot
        PERSONALITY.put(mc("cow"),     new Personality(48, 130, 0.20, 0.30));  // slow amble
        PERSONALITY.put(mc("pig"),     new Personality(32, 80, 0.28, 0.40));
        PERSONALITY.put(mc("chicken"), new Personality(28, 80, 0.30, 0.60));  // darty but no longer frantic
    }

    private static Identifier mc(String path) {
        return Identifier.fromNamespaceAndPath("minecraft", path);
    }

    private static Personality personality(Identifier type) {
        return PERSONALITY.getOrDefault(type, DEFAULT);
    }

    /** One animal's roaming pose at one instant. {@code x}/{@code z} are world coords on the pen. */
    public record RoamPose(double x, double z, float yaw, float xRotDip, float walkSpeed) {}

    // ---- Agent cache ----------------------------------------------------------------------------

    private static final class Agent {
        Identifier type;                 // species the cached entity was built for
        boolean baby;                    // baby flag the cached entity was built for
        long penVersion = Long.MIN_VALUE; // pen shape the path was generated for
        final double[] px = new double[PATH_LEN];
        final double[] pz = new double[PATH_LEN];
        Entity entity;                   // lazy render-only puppet (only for animals actually drawn)
        long lastWalkTick = Long.MIN_VALUE;
    }

    private static final class BedAgents {
        final Agent[] stalls = new Agent[MAX_STALLS];
        long lastAccess;
    }

    private final Map<Long, BedAgents> cache = new HashMap<>();
    private long lastSweep = Long.MIN_VALUE;

    private Agent agent(long homeLong, int stall, long gameTime) {
        BedAgents ba = cache.computeIfAbsent(homeLong, k -> new BedAgents());
        ba.lastAccess = gameTime;
        Agent a = ba.stalls[stall];
        if (a == null) {
            a = new Agent();
            ba.stalls[stall] = a;
        }
        return a;
    }

    // ---- Public API used by the renderer --------------------------------------------------------

    /** Position (+ facing/gait) for one animal at time {@code t} = gameTime + partialTick. Cheap:
     *  a cached path lookup plus a lerp. Called for every cluster animal so the renderer can route
     *  each to the bed it's currently standing on. */
    public RoamPose computePose(BlockPos home, int stall, Identifier type,
                                 Cluster cluster, long penV, double t, long gameTime) {
        Agent a = agent(home.asLong(), stall, gameTime);
        if (a.penVersion != penV) {
            generatePath(a, home, stall, type, cluster);
            a.penVersion = penV;
        }
        return evalPose(a, home.asLong(), stall, type, t);
    }

    /** Returns the cached render-only puppet for an animal (rebuilt on species/baby change), driven
     *  to {@code rp}: body/head yaw, head-dip pitch, walk-cycle limb swing, and idle tick. Returns
     *  null if the entity can't be reconstructed. Call only for animals being drawn this frame. */
    public Entity drivenPuppet(BlockPos home, int stall, Identifier type, CompoundTag nbt,
                                RoamPose rp, Level level, long gameTime) {
        Agent a = agent(home.asLong(), stall, gameTime);
        boolean baby = isBabyNbt(nbt);
        if (a.entity == null || !type.equals(a.type) || baby != a.baby) {
            a.entity = rebuildEntity(level, type, nbt);
            a.type = type;
            a.baby = baby;
            a.lastWalkTick = Long.MIN_VALUE;
        }
        if (a.entity == null) return null;
        drive(a, rp, gameTime);
        return a.entity;
    }

    /** True if a driven puppet for (home, stall) is already cached and matches the species/baby
     *  flag — i.e. {@link #drivenPuppet} would NOT need a fresh NBT entity build. Lets the renderer
     *  budget cold builds (the expensive first-view deserializations) separately from cheap
     *  re-drives of existing puppets. */
    public boolean hasPuppet(BlockPos home, int stall, Identifier type, CompoundTag nbt) {
        BedAgents ba = cache.get(home.asLong());
        if (ba == null || stall < 0 || stall >= MAX_STALLS) return false;
        Agent a = ba.stalls[stall];
        return a != null && a.entity != null && type.equals(a.type) && isBabyNbt(nbt) == a.baby;
    }

    /** Drop every cached agent/puppet (used when puppet rendering is config-toggled off, so the
     *  entities aren't held while disabled). */
    public void clearAll() {
        if (!cache.isEmpty()) cache.clear();
    }

    /** Drop a cached puppet (e.g. after a render-thread error) so it rebuilds next frame. */
    public void invalidate(BlockPos home, int stall) {
        BedAgents ba = cache.get(home.asLong());
        if (ba != null && stall >= 0 && stall < MAX_STALLS && ba.stalls[stall] != null) {
            ba.stalls[stall].entity = null;
            ba.stalls[stall].type = null;
        }
    }

    /** Evict agents for pens that stopped rendering. Rate-limited to ~twice per second. */
    public void sweep(long gameTime) {
        if (gameTime == lastSweep || (gameTime % 40L) != 0L) return;
        lastSweep = gameTime;
        cache.entrySet().removeIf(e -> gameTime - e.getValue().lastAccess > EVICT_TTL);
    }

    // ---- Pose evaluation ------------------------------------------------------------------------

    private static RoamPose evalPose(Agent a, long homeLong, int stall, Identifier type, double t) {
        Personality p = personality(type);
        long seed = mix(homeLong * 0x9E3779B97F4A7C15L + stall);

        int legPeriod = p.walkTicks() + p.pauseTicks();
        // Per-agent phase offset so same-species animals (which share legPeriod) don't march in
        // lockstep — staggers each one's walk/pause clock across the period.
        double legF = (t + Math.floorMod(seed, legPeriod)) / legPeriod;
        long leg = (long) Math.floor(legF);
        double localPhase = (legF - leg) * legPeriod;           // [0, legPeriod)

        int fromIdx = pingpong(leg, PATH_LEN);
        int toIdx = pingpong(leg + 1, PATH_LEN);
        double fx = a.px[fromIdx], fz = a.pz[fromIdx];
        double tx = a.px[toIdx], tz = a.pz[toIdx];
        double ddx = tx - fx, ddz = tz - fz;

        boolean walking = localPhase < p.walkTicks();
        double x, z;
        float walkSpeed;
        if (walking) {
            double prog = localPhase / p.walkTicks();
            prog = prog * prog * (3.0 - 2.0 * prog);            // smoothstep ease in/out
            x = fx + ddx * prog;
            z = fz + ddz * prog;
            double legDist = Math.sqrt(ddx * ddx + ddz * ddz);
            walkSpeed = (float) Math.min(legDist / p.walkTicks() * 4.0, 1.0);
        } else {
            x = tx;
            z = tz;
            walkSpeed = 0.0F;
        }

        // Facing: heading of this leg. When a leg barely moves (lingering in one tile) the heading
        // is ill-defined, so fall back to a stable per-leg idle facing.
        float yaw;
        if (ddx * ddx + ddz * ddz < 1.0e-5) {
            yaw = (float) (hash01(seed ^ 0x5A5AL, leg) * 360.0 - 180.0);
        } else {
            // MC yaw: 0deg faces +Z (south), 90deg faces -X (west). atan2(-dx, dz) in degrees.
            yaw = (float) Math.toDegrees(Math.atan2(-ddx, ddz));
        }

        // Graze/peck: dip the head during some pauses (eased down and back up).
        float dip = 0.0F;
        if (!walking && hash01(seed ^ 0xD1D1L, leg) < p.dipChance()) {
            double dipPhase = (localPhase - p.walkTicks()) / p.pauseTicks();   // 0..1 across pause
            dip = (float) (Math.sin(dipPhase * Math.PI) * DIP_DEGREES);
        }

        return new RoamPose(x, z, yaw, dip, walkSpeed);
    }

    /** Generates a {@link #PATH_LEN}-waypoint loop by random-walking the pen's tile adjacency graph
     *  (staying put is allowed, so animals linger), placing one in-tile waypoint per step. */
    private static void generatePath(Agent a, BlockPos home, int stall, Identifier type, Cluster cluster) {
        long seed = mix(home.asLong() * 0x9E3779B97F4A7C15L + stall);
        Random rng = new Random(seed);
        double jitter = personality(type).jitter();

        BlockPos tile = cluster.contains(home) ? home : cluster.members().get(0);
        for (int i = 0; i < PATH_LEN; i++) {
            double jx = (rng.nextDouble() - 0.5) * 2.0 * jitter;   // +/- jitter, kept < 0.5
            double jz = (rng.nextDouble() - 0.5) * 2.0 * jitter;
            a.px[i] = tile.getX() + 0.5 + jx;
            a.pz[i] = tile.getZ() + 0.5 + jz;
            tile = nextTile(tile, cluster, rng);
        }
    }

    /** One random step over the pen tiles: stay, or move to a cardinal neighbor that's part of the
     *  pen. Never leaves the surface, so a leg between consecutive waypoints can't cross a gap. */
    private static BlockPos nextTile(BlockPos tile, Cluster cluster, Random rng) {
        BlockPos[] cand = new BlockPos[5];
        int n = 0;
        cand[n++] = tile;                                        // "stay" — bias toward lingering
        BlockPos north = tile.north(); if (cluster.contains(north)) cand[n++] = north;
        BlockPos south = tile.south(); if (cluster.contains(south)) cand[n++] = south;
        BlockPos east  = tile.east();  if (cluster.contains(east))  cand[n++] = east;
        BlockPos west  = tile.west();  if (cluster.contains(west))  cand[n++] = west;
        return cand[rng.nextInt(n)];
    }

    // ---- Entity puppet driving ------------------------------------------------------------------

    private static void drive(Agent a, RoamPose rp, long gameTime) {
        Entity e = a.entity;
        e.setPos(0, 0, 0);                  // draw position comes from the BER pose, not the entity
        e.setDeltaMovement(0, 0, 0);
        e.setYRot(rp.yaw());
        e.setXRot(rp.xRotDip());
        e.setOldPosAndRot();                // syncs xo/yo/zo, yRotO, xRotO -> no pos/pitch interp
        if (e instanceof LivingEntity living) {
            living.setYBodyRot(rp.yaw());
            living.setYHeadRot(rp.yaw());
            // setOldPosAndRot ignores body/head yaw and this puppet is never ticked, so pin the *O
            // fields to suppress the renderer's yaw lerp (the historical "violent shake").
            living.yBodyRotO = rp.yaw();
            living.yHeadRotO = rp.yaw();
            // Limb swing: WalkAnimationState.update accumulates per tick, so gate to once per game
            // tick (catch up a few if frames were skipped). extractRenderState then interpolates it
            // smoothly by partialTick, giving a real walk cycle that settles to a stand when paused.
            if (a.lastWalkTick != gameTime) {
                int steps = a.lastWalkTick == Long.MIN_VALUE ? 1 : (int) Math.min(4, gameTime - a.lastWalkTick);
                for (int i = 0; i < steps; i++) {
                    living.walkAnimation.update(rp.walkSpeed(), 0.4F, a.baby ? 3.0F : 1.0F);
                }
                a.lastWalkTick = gameTime;
            }
        }
        // Advance tick so idle animations play (ear wiggle, head bob, tail flick).
        e.tickCount = (int) (gameTime & 0x7FFFFFFFL);
    }

    // ---- Entity reconstruction ------------------------------------------------------------------

    /** Vanilla baby convention: AgeableMob stores age as a negative int, counting up toward 0. */
    private static boolean isBabyNbt(CompoundTag nbt) {
        if (nbt == null || !nbt.contains("Age")) return false;
        return nbt.getIntOr("Age", 0) < 0;
    }

    /** Rebuild a transient render-only entity from the stored host NBT. Tries an NBT load first
     *  (preserves sheep wool colour, names, etc.); on failure — common for 26.1's new-variant
     *  species when captured NBT doesn't round-trip cleanly on the client level — falls back to a
     *  default entity of the species so a recognisable silhouette still renders. */
    private static Entity rebuildEntity(Level level, Identifier typeId, CompoundTag nbt) {
        if (level == null) return null;
        Entity loaded = null;
        try {
            loaded = EntityType.loadEntityRecursive(nbt, level, EntitySpawnReason.LOAD, e -> {
                stabilize(e);
                return e;
            });
        } catch (Throwable t) {
            LOGGER.warn("loadEntityRecursive threw for {}: {}", typeId, t.toString());
        }
        if (loaded != null) return loaded;

        EntityType<?> et = BuiltInRegistries.ENTITY_TYPE.getValue(typeId);
        if (et == null) {
            LOGGER.warn("Unknown entity type {} for bed render", typeId);
            return null;
        }
        try {
            Entity fresh = et.create(level, EntitySpawnReason.LOAD);
            if (fresh != null) stabilize(fresh);
            return fresh;
        } catch (Throwable t) {
            LOGGER.warn("EntityType.create threw for {}: {}", typeId, t.toString());
            return null;
        }
    }

    /** Zero an entity's position, motion, and rotations (current AND old) so its first rendered
     *  frame has no interpolation to resolve — {@link #drive} then sets the live values each frame. */
    private static void stabilize(Entity e) {
        e.setPos(0, 0, 0);
        e.setDeltaMovement(0, 0, 0);
        e.setYRot(0);
        e.setXRot(0);
        e.setOldPosAndRot();
        if (e instanceof LivingEntity living) {
            living.setYHeadRot(0);
            living.setYBodyRot(0);
            living.yHeadRotO = 0;
            living.yBodyRotO = 0;
        }
    }

    // ---- Hashing --------------------------------------------------------------------------------

    private static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xFF51AFD7ED558CCDL;
        z = (z ^ (z >>> 33)) * 0xC4CEB9FE1A85EC53L;
        return z ^ (z >>> 33);
    }

    /** Deterministic pseudo-random double in [0, 1) from two longs. */
    private static double hash01(long a, long b) {
        long h = mix(a * 0x9E3779B97F4A7C15L + b);
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Triangle-wave index: 0,1,..,N-1,N-2,..,1,0,1,.. so consecutive legs map to adjacent path
     *  entries (hence same/adjacent tiles) and the loop never needs a long closing segment. */
    private static int pingpong(long j, int n) {
        if (n <= 1) return 0;
        long period = 2L * (n - 1);
        long m = ((j % period) + period) % period;
        return (int) (m < n ? m : period - m);
    }
}
