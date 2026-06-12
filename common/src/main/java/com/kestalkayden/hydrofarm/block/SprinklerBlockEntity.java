package com.kestalkayden.hydrofarm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.kestalkayden.hydrofarm.HydrofarmConfig;
import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.platform.FluidApi;
import com.kestalkayden.hydrofarm.platform.HydrofarmPlatform;

public class SprinklerBlockEntity extends BlockEntity {

    public static final int BUFFER_CAPACITY_MB = 1_000;

    /** Fresh-state hydration cost — fires once when farmland in AoE has MOISTURE<7. With the
     *  FarmlandBlockMixin handling vanilla random-tick decay, this only kicks in for the
     *  initial moisture=0 state of freshly-hoed farmland. */
    private static final int HYDRATION_COST_MB = 5;

    /** Charged per particle burst — ties water depletion to visible activity. */
    private static final int PARTICLE_COST_MB = 1;
    /** Charged per immature crop random-tick boost. Priced for rough water-parity with the
     *  hydroponics bed so the dedicated machine stays the efficient way to RUN a farm while the
     *  sprinkler stays the cheap way to BUILD one: a 2.5×-boosted wheat takes ~12.6 charged ticks
     *  per harvest ≈ 126 mB/item vs the bed's 150. A packed 9×9 (80 immature crops) draws
     *  ~800 mB per growth pass ≈ 17.6 mB/s; empty or fully-grown plots ≈ 0. */
    private static final int GROWTH_COST_PER_CROP_MB = 10;
    /** Charged per farmland decay-save (see {@link #chargeMoistureUpkeep}) — keeping a field wet
     *  is irrigation, not a freebie: ~1.2 mB/s for a full 80-tile box at default randomTickSpeed. */
    private static final int UPKEEP_COST_MB = 1;

    /** Bonus random-tick cadence, tuned for ~2.5× total growth speed. Vanilla random-ticks each
     *  block once per 4096/randomTickSpeed ticks — ~1365 ticks (~68 s) at the default speed of 3 —
     *  so one extra tick every 910 adds 1.5× the natural rate: boosted crops average
     *  1 + 1365.3/910 ≈ 2.5× vanilla. (The old value of 60 assumed vanilla's mean random-tick
     *  interval was ~60 TICKS; it's ~68 SECONDS, which made the boost an accidental ~24×.) */
    private static final int GROWTH_INTERVAL = 910;
    private static final int PULL_INTERVAL = 10;
    // Particle burst size + cadence are config-driven (server.sprinklerParticleCount /
    // server.sprinklerParticleInterval) — the spray is the classic transparent-overdraw FPS sink,
    // so big farms can tone it down or turn it off.
    /** How often the ~162-block farmland scan + fresh-hydrate runs. Moisture decays slowly and
     *  FarmlandBlockMixin handles steady state, so a slow cadence is invisible and avoids scanning
     *  the AoE every tick. */
    private static final int SCAN_INTERVAL = 20;

    /** 9×9 AoE (radius 4) centered on the block, two Y layers. Deliberately the SAME box as
     *  vanilla's farmland isNearWater scan — which {@code FarmlandBlockMixin} keys on to keep
     *  farmland moist near a watered sprinkler — so the boosted area and the kept-moist area are
     *  one and the same: one sprinkler serves exactly the 80 tiles a water source block would. */
    private static final int AOE_RADIUS = 4;

    private int waterMb = 0;
    /** Cached result of the last farmland scan; refreshed every {@link #SCAN_INTERVAL} ticks so LIT,
     *  particles and growth don't each re-scan the AoE per tick. */
    private boolean hasFarmland = false;

    /** Per-sprinkler phase offsets for the periodic gates: the scan and growth passes are
     *  t-modulo-interval, so without a phase EVERY sprinkler in the world would walk its
     *  162-block box on the same global tick. Position-hashed, deterministic across reloads. */
    private final int scanPhase;
    private final int growthPhase;

    public SprinklerBlockEntity(BlockPos pos, BlockState state) {
        super(HydrofarmRefs.SPRINKLER_BE.get(), pos, state);
        long mixed = pos.asLong() * 0x9E3779B97F4A7C15L;
        this.scanPhase = (int) Math.floorMod(mixed, (long) SCAN_INTERVAL);
        this.growthPhase = (int) Math.floorMod(mixed >>> 17, (long) GROWTH_INTERVAL);
    }

    public int getWaterMb() { return waterMb; }
    public int getCapacityMb() { return BUFFER_CAPACITY_MB; }
    public int getRoomMb() { return BUFFER_CAPACITY_MB - waterMb; }

    public int insertWater(int amount) {
        if (amount <= 0) return 0;
        int accepted = Math.min(amount, getRoomMb());
        if (accepted > 0) {
            waterMb += accepted;
            setChanged();
        }
        return accepted;
    }

    /** Restores buffer to a snapshot value during transaction rollback. */
    public void setWaterMb(int amount) {
        waterMb = Math.max(0, Math.min(BUFFER_CAPACITY_MB, amount));
        setChanged();
    }

    /** Charges {@link #UPKEEP_COST_MB} for one farmland decay-save. Called by FarmlandBlockMixin
     *  each time it cancels the random-tick decay of a protected farmland block in range. Returns
     *  false when the buffer can't cover it — that farmland then decays normally, so an unplumbed
     *  sprinkler's field dries out once the buffer runs dry instead of staying wet for free. */
    public boolean chargeMoistureUpkeep() {
        if (waterMb < UPKEEP_COST_MB) return false;
        waterMb -= UPKEEP_COST_MB;
        setChanged();
        return true;
    }

    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        long t = level.getGameTime();

        if (t % PULL_INTERVAL == 0 && getRoomMb() > 0) {
            tryPullFromNeighbors(level, pos);
        }

        // Refresh the farmland scan and top up freshly-hoed MOISTURE=0 dirt on a slow cadence.
        // FarmlandBlockMixin handles steady-state maintenance, so the ≤1 s lag is invisible and we
        // skip a ~162-block scan every tick.
        if ((t + scanPhase) % SCAN_INTERVAL == 0) {
            hasFarmland = scanForFarmland(level, pos);
            // Gate hydration on hasFarmland: with none in range, hydrateFarmland would walk the same
            // AoE and find nothing, so skipping it is identical state and drops the redundant scan.
            if (hasFarmland && waterMb >= HYDRATION_COST_MB && hydrateFarmland(level, pos)) {
                waterMb -= HYDRATION_COST_MB;
                setChanged();
            }
        }

        // LIT, particles and growth all gate on the cached scan + current water. "Lit" = has water
        // AND farmland in its AoE worth spraying onto.
        boolean shouldBeLit = waterMb > 0 && hasFarmland;
        if (state.getValue(SprinklerBlock.LIT) != shouldBeLit) {
            BlockState newState = state.setValue(SprinklerBlock.LIT, shouldBeLit);
            // UPDATE_CLIENTS (not UPDATE_ALL): LIT drives the visual only — no neighbor reacts to
            // it, so skip the six neighborChanged calls (same pattern as the repulser's ACTIVE).
            level.setBlock(pos, newState, Block.UPDATE_CLIENTS);
            state = newState;
        }

        if (shouldBeLit && (t + growthPhase) % GROWTH_INTERVAL == 0 && waterMb >= GROWTH_COST_PER_CROP_MB) {
            int ticked = growImmatureCrops(level, pos);
            if (ticked > 0) {
                waterMb -= ticked * GROWTH_COST_PER_CROP_MB;
                setChanged();
            }
        }

        if (shouldBeLit && HydrofarmConfig.sprinklerParticleCount() > 0
                && t % HydrofarmConfig.sprinklerParticleInterval() == 0 && waterMb >= PARTICLE_COST_MB) {
            spawnSprayParticles(level, pos);
            waterMb -= PARTICLE_COST_MB;
            setChanged();
        }
    }

    /** Quick scan for any farmland in the 9×9 AoE at Y or Y−1 — covers both in-ground placement
     *  (sprinkler at farmland's Y) and on-top placement (sprinkler at Y+1 of farmland). Bails on
     *  first hit. */
    private boolean scanForFarmland(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = -1; dy <= 0; dy++) {
            for (int dx = -AOE_RADIUS; dx <= AOE_RADIUS; dx++) {
                for (int dz = -AOE_RADIUS; dz <= AOE_RADIUS; dz++) {
                    if (dx == 0 && dz == 0 && dy == 0) continue;
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (level.getBlockState(cursor).is(Blocks.FARMLAND)) return true;
                }
            }
        }
        return false;
    }

    /** Emits a horizontal spray from above the head. Vanilla particle constraints we have to
     *  work around: SplashParticle ignores all velocity when ySpeed != 0 (so no upward arc),
     *  and DripParticle (FALLING_WATER) ignores velocity entirely. So we use SPLASH with
     *  ySpeed=0 to get pure horizontal spread, spawn at y+0.6 to give the droplets a fall
     *  distance, and let vanilla gravity pull them down onto the surrounding farmland. */
    private void spawnSprayParticles(ServerLevel level, BlockPos pos) {
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.6;
        double cz = pos.getZ() + 0.5;
        var rand = level.getRandom();
        int count = HydrofarmConfig.sprinklerParticleCount();
        for (int i = 0; i < count; i++) {
            double angle = rand.nextDouble() * Math.PI * 2.0;
            double horizSpeed = 0.18 + rand.nextDouble() * 0.16;
            double vx = Math.cos(angle) * horizSpeed;
            double vz = Math.sin(angle) * horizSpeed;
            level.sendParticles(ParticleTypes.SPLASH,
                cx, cy, cz,
                0,
                vx, 0.0, vz,
                1.0);
        }
    }

    /** Iterates the 6 neighbors and pulls water from the first fluid-capable block that has any.
     *  Pulls up to (capacity - current) mB. Stops at the first successful pull each tick so a tank
     *  with leftovers can keep feeding even if other neighbors are dry. Uses the platform fluid
     *  seam so the same logic serves both loaders. */
    private void tryPullFromNeighbors(ServerLevel level, BlockPos pos) {
        int room = getRoomMb();
        if (room <= 0) return;

        for (Direction dir : Direction.values()) {
            FluidApi.Endpoint endpoint =
                HydrofarmPlatform.fluids().find(level, pos.relative(dir), dir.getOpposite());
            if (endpoint == null) continue;
            int pulled = endpoint.extract(Fluids.WATER, room);
            if (pulled > 0) {
                waterMb = Math.min(BUFFER_CAPACITY_MB, waterMb + pulled);
                setChanged();
                return;
            }
        }
    }

    /** Sets all FARMLAND blocks in the 9×9 AoE at Y or Y−1 to MOISTURE=7. Covers both
     *  in-ground placement (farmland at sprinkler's Y) and on-top placement (farmland at Y−1).
     *  UPDATE_SKIP_ON_PLACE bypasses FarmlandBlock.canSurvive checks defensively. */
    private boolean hydrateFarmland(ServerLevel level, BlockPos pos) {
        boolean any = false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = -1; dy <= 0; dy++) {
            for (int dx = -AOE_RADIUS; dx <= AOE_RADIUS; dx++) {
                for (int dz = -AOE_RADIUS; dz <= AOE_RADIUS; dz++) {
                    if (dx == 0 && dz == 0 && dy == 0) continue;
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (state.is(Blocks.FARMLAND) && state.getValue(FarmlandBlock.MOISTURE) < 7) {
                        level.setBlock(cursor, state.setValue(FarmlandBlock.MOISTURE, 7),
                            Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ON_PLACE);
                        any = true;
                    }
                }
            }
        }
        return any;
    }

    /** Random-ticks every immature crop in the 9×9 AoE at Y or Y+1, bounded by available water.
     *  Each tick costs GROWTH_COST_PER_CROP_MB so a dense plot drains the buffer faster than a
     *  sparse one. Returns the number of crops ticked so the caller can charge the right amount. */
    private int growImmatureCrops(ServerLevel level, BlockPos pos) {
        int budgetCrops = waterMb / GROWTH_COST_PER_CROP_MB;
        if (budgetCrops <= 0) return 0;
        int ticked = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -AOE_RADIUS; dx <= AOE_RADIUS; dx++) {
                for (int dz = -AOE_RADIUS; dz <= AOE_RADIUS; dz++) {
                    if (dx == 0 && dz == 0 && dy == 0) continue;
                    if (ticked >= budgetCrops) return ticked;
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (state.getBlock() instanceof CropBlock cb && !cb.isMaxAge(state)) {
                        state.randomTick(level, cursor.immutable(), level.getRandom());
                        ticked++;
                    }
                }
            }
        }
        return ticked;
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putInt("WaterMb", waterMb);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        this.waterMb = Math.max(0, Math.min(BUFFER_CAPACITY_MB, in.getIntOr("WaterMb", 0)));
    }
}
