package com.kestalkayden.hydrofarm.block;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Server-side registry of active Monster Repulsers, plus the two consumers of it:
 *  {@link #covers} (used by the per-loader spawn-block hooks) and {@link #sweep} (the periodic
 *  wander-in cleanup). All access is on the server thread, so a plain map/set is safe.
 *
 *  <p>The sweep is <em>player-centric</em>: it scans a fixed box around each player near a field
 *  rather than each field's whole disc, so cost scales with players + mob activity (not field
 *  radius or repulser count), and overlapping fields never multiply the work. A mob already removed
 *  by an overlapping player's pass is skipped via its alive check (cheap de-dup). */
public final class RepulserField {

    private static final int SWEEP_INTERVAL = 20;   // ticks between sweeps (1 s)
    private static final int SWEEP_RADIUS = 24;     // half-box scanned around each player

    private static final Map<ResourceKey<Level>, Set<BlockPos>> ACTIVE = new HashMap<>();

    private RepulserField() {}

    /** Mark a repulser active/inactive for its dimension. Refreshed each upkeep tick by the BE, and
     *  cleared on {@code setRemoved}. */
    public static void setActive(Level level, BlockPos pos, boolean active) {
        ResourceKey<Level> dim = level.dimension();
        if (active) {
            ACTIVE.computeIfAbsent(dim, k -> new HashSet<>()).add(pos.immutable());
        } else {
            Set<BlockPos> set = ACTIVE.get(dim);
            if (set != null) {
                set.remove(pos);
                if (set.isEmpty()) ACTIVE.remove(dim);
            }
        }
    }

    /** Cheap "is there anything to check" gate for the hot spawn hook. */
    public static boolean anyActive(Level level) {
        Set<BlockPos> set = ACTIVE.get(level.dimension());
        return set != null && !set.isEmpty();
    }

    /** Whether an active field covers {@code (x,y,z)}. Verifies the BE is still a live active
     *  repulser (and prunes confirmed-stale entries), so a leaked registry entry can never wrongly
     *  block spawns. Early-outs on the first covering field, so overlaps add no cost. */
    public static boolean covers(ServerLevel level, double x, double y, double z) {
        Set<BlockPos> set = ACTIVE.get(level.dimension());
        if (set == null || set.isEmpty()) return false;
        Iterator<BlockPos> it = set.iterator();
        while (it.hasNext()) {
            BlockPos p = it.next();
            if (!RepulserTargeting.withinField(p, x, y, z)) continue;
            if (level.getBlockEntity(p) instanceof RepulserBlockEntity r && r.isFieldActive()) return true;
            if (level.hasChunkAt(p)) it.remove();   // loaded but not an active repulser → stale
        }
        return false;
    }

    /** Poof hostile wanderers near players. Call every tick from the server-tick hook; it self-gates
     *  to {@link #SWEEP_INTERVAL}. */
    public static void sweep(ServerLevel level) {
        if (level.getGameTime() % SWEEP_INTERVAL != 0) return;
        Set<BlockPos> set = ACTIVE.get(level.dimension());
        if (set == null || set.isEmpty()) return;
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        for (ServerPlayer player : players) {
            if (!nearAnyField(set, player.getX(), player.getZ())) continue;
            AABB box = AABB.ofSize(player.position(), 2.0 * SWEEP_RADIUS, 2.0 * SWEEP_RADIUS, 2.0 * SWEEP_RADIUS);
            for (Mob mob : level.getEntitiesOfClass(Mob.class, box, RepulserTargeting::isRepellable)) {
                if (!mob.isAlive()) continue;   // already poofed by an overlapping player's pass
                if (covers(level, mob.getX(), mob.getY(), mob.getZ())) poof(level, mob);
            }
        }
    }

    private static boolean nearAnyField(Set<BlockPos> set, double px, double pz) {
        double reach = RepulserBlockEntity.RADIUS + SWEEP_RADIUS;
        double reachSq = reach * reach;
        for (BlockPos p : set) {
            double dx = px - (p.getX() + 0.5);
            double dz = pz - (p.getZ() + 0.5);
            if (dx * dx + dz * dz <= reachSq) return true;
        }
        return false;
    }

    private static void poof(ServerLevel level, Mob mob) {
        double x = mob.getX();
        double y = mob.getY() + mob.getBbHeight() * 0.5;
        double z = mob.getZ();
        level.sendParticles(ParticleTypes.POOF, x, y, z, 12, 0.3, 0.4, 0.3, 0.02);
        level.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.5F, 1.4F);
        mob.discard();
    }
}
