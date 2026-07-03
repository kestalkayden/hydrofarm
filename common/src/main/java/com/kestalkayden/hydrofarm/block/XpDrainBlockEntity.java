package com.kestalkayden.hydrofarm.block;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.platform.FluidApi;
import com.kestalkayden.hydrofarm.platform.HydrofarmPlatform;

/** Stateless passthrough: drains XP from sneaking survival players standing on or beside it (a 3x3
 *  footprint) and pushes it directly into the fluid block below. No internal buffer — if the
 *  downstream block is full or absent, the player keeps their XP. Right-click pulls XP back from the
 *  same downstream block. */
public class XpDrainBlockEntity extends BlockEntity {

    public static final int MB_PER_XP = 20;
    private static final int DRAIN_INTERVAL = 1;
    /** Max XP drained per sneaking player per cycle. */
    private static final int XP_PER_DRAIN_CYCLE = 20;
    /** Max XP returned per right-click. Hold-right-click fires ~5 times/sec, so 100 here is
     *  ~500 XP/sec sustained — drains a full single-block tank (16,000 mB = 800 XP) in ~2 sec
     *  of holding, and a 27-block 3x3x3 cluster in ~45 sec. */
    private static final int XP_PER_CLICK_CAP = 100;
    private static final int ACTIVE_POLL_INTERVAL = 5;

    public XpDrainBlockEntity(BlockPos pos, BlockState state) {
        super(HydrofarmRefs.XP_DRAIN_BE.get(), pos, state);
    }

    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        long t = level.getGameTime();
        Direction facing = state.getValue(XpDrainBlock.FACING);

        // Sneak-drain only makes sense for floor-mounted (FACING=UP). Wall/ceiling variants are
        // right-click-only.
        // Only run the per-tick entity scan + drain once the 5-tick ACTIVE poll has confirmed a
        // sneaking player over a valid target. Keeps idle floor drains from querying entities every
        // tick; a newly-sneaking player warms up within one poll (≤5 ticks).
        if (facing == Direction.UP && state.getValue(XpDrainBlock.ACTIVE) && t % DRAIN_INTERVAL == 0) {
            drainFromPlayersAbove(level, pos, facing);
        }

        if (facing == Direction.UP && t % ACTIVE_POLL_INTERVAL == 0) {
            boolean shouldBeActive = hasSneakingDrainablePlayer(level, pos)
                && findTarget(level, pos, facing) != null;
            if (state.getValue(XpDrainBlock.ACTIVE) != shouldBeActive) {
                // UPDATE_CLIENTS: ACTIVE drives the visual only — no neighbor reacts to it.
                level.setBlock(pos, state.setValue(XpDrainBlock.ACTIVE, shouldBeActive), Block.UPDATE_CLIENTS);
            }
        }
    }

    /** First valid fluid-capable neighbor. Priority 1: block the drain attaches to
     *  ({@code facing.getOpposite()}). Priority 2: the 4 perpendicular directions to FACING. */
    private static FluidApi.Endpoint findTarget(ServerLevel level, BlockPos pos, Direction facing) {
        Direction back = facing.getOpposite();
        FluidApi.Endpoint primary =
            HydrofarmPlatform.fluids().find(level, pos.relative(back), back.getOpposite());
        if (primary != null) return primary;
        for (Direction d : Direction.values()) {
            if (d == facing || d == back) continue;
            FluidApi.Endpoint side =
                HydrofarmPlatform.fluids().find(level, pos.relative(d), d.getOpposite());
            if (side != null) return side;
        }
        return null;
    }

    /** AABB over the 2-voxel pad, widened 1 block on each horizontal axis so it catches a sneaking
     *  player standing on the drain OR in any of the 8 adjacent cells (a 3x3 footprint). The height
     *  band (just above the pad up to 1.5) is unchanged, so a player on the same floor level beside
     *  the drain is caught but not one on a distant platform. Cached: the BE never moves, so this box
     *  (a pure function of {@code pos}) is built once and reused, avoiding a fresh allocation on every
     *  5-tick ACTIVE poll and every per-tick drain. */
    private AABB scanBox;
    private AABB playerScanBox(BlockPos pos) {
        AABB box = scanBox;
        if (box == null) {
            box = scanBox = new AABB(
                pos.getX(),       pos.getY() + 2.0 / 16.0, pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.5,        pos.getZ() + 1.0)
                .inflate(1.0, 0.0, 1.0);
        }
        return box;
    }

    private boolean hasSneakingDrainablePlayer(ServerLevel level, BlockPos pos) {
        List<Player> players = level.getEntitiesOfClass(Player.class, playerScanBox(pos),
            p -> p.isCrouching() && !p.isCreative() && !p.isSpectator()
                  && (p.experienceLevel > 0 || p.experienceProgress > 0));
        return !players.isEmpty();
    }

    /** Push directly from each sneaking player into the first available fluid-capable neighbor.
     *  XP moves in whole points ({@link #MB_PER_XP} each); any sub-XP remainder the target accepts
     *  (the insert capped on room that isn't a whole-point multiple) is undone (extract-back), so the
     *  player is charged for exactly the whole points banked and no fluid is minted. Only invoked
     *  when FACING=UP. */
    private void drainFromPlayersAbove(ServerLevel level, BlockPos pos, Direction facing) {
        FluidApi.Endpoint target = findTarget(level, pos, facing);
        if (target == null) return;

        List<Player> players = level.getEntitiesOfClass(Player.class, playerScanBox(pos),
            p -> p.isCrouching() && !p.isCreative() && !p.isSpectator()
                  && (p.experienceLevel > 0 || p.experienceProgress > 0));
        if (players.isEmpty()) return;

        Fluid xp = HydrofarmRefs.LIQUID_XP.get();
        for (Player p : players) {
            int insertedMb = target.insert(xp, XP_PER_DRAIN_CYCLE * MB_PER_XP);
            if (insertedMb <= 0) continue;
            int actualXp = insertedMb / MB_PER_XP;
            // Undo any sub-XP remainder so we never bank fluid the player didn't pay for — whether
            // the whole insert was sub-XP (actualXp == 0) or just the leftover above a whole point.
            int remainderMb = insertedMb - actualXp * MB_PER_XP;
            if (remainderMb > 0) target.extract(xp, remainderMb);
            if (actualXp > 0) p.giveExperiencePoints(-actualXp);
        }
    }

    /** Right-click: pull Liquid XP from the connected tank back to the player as XP points.
     *  Works on any facing — uses the block's FACING to know which side is "back". Any sub-XP
     *  remainder of the pull is returned to the tank so no fluid is ever destroyed at the
     *  whole-point boundary. */
    public int returnXpToPlayer(ServerLevel level, BlockPos pos, Player player) {
        Direction facing = level.getBlockState(pos).getValue(XpDrainBlock.FACING);
        FluidApi.Endpoint source = findTarget(level, pos, facing);
        if (source == null) return 0;

        Fluid xp = HydrofarmRefs.LIQUID_XP.get();
        int extractedMb = source.extract(xp, XP_PER_CLICK_CAP * MB_PER_XP);
        if (extractedMb == 0) return 0;
        int xpPoints = extractedMb / MB_PER_XP;
        // Return any sub-XP remainder so a non-multiple-of-20 pull never destroys Liquid XP. The
        // source just gave up extractedMb, so there is always room to take the remainder back.
        int remainderMb = extractedMb - xpPoints * MB_PER_XP;
        if (remainderMb > 0) source.insert(xp, remainderMb);
        if (xpPoints > 0) player.giveExperiencePoints(xpPoints);
        return xpPoints;
    }
}
