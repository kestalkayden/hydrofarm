package com.kestalkayden.hydrofarm.block;

import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.util.Backoff;

/** Hydroelectric Generator — consumes water from its internal tank and produces energy into an
 *  internal buffer, exposed to tech-mod cables via the standard energy capability on each loader
 *  (Team Reborn Energy on Fabric, NeoForge Energy on NeoForge). The energy value is unit-neutral
 *  here; each loader's wrapper presents it in the puller's native unit (E / FE), so no conversion
 *  is needed. Water in is insert-only (it's fuel); energy out is extract-only (cables pull).
 *
 *  <p>This block introduces no dependency on any tech mod — it only speaks the standard energy
 *  interface. With nothing on the other end it simply fills its buffer and idles. */
public class HydroelectricGeneratorBlockEntity extends BlockEntity {

    public static final int WATER_CAPACITY_MB = 5_000;
    public static final int ENERGY_CAPACITY   = 10_000;
    /** Generation runs on a short cycle so the water:energy ratio can be fractional with integer
     *  amounts: every {@link #GENERATION_INTERVAL} ticks, consume {@link #WATER_PER_CYCLE} mB and
     *  produce {@link #ENERGY_PER_CYCLE}. That averages 2.5 mB/tick and 1 energy/tick.
     *
     *  <p>Tuning: a Liquid Siphon outputs 5 mB/tick, so at 2.5 mB/tick a siphon feeds exactly TWO
     *  generators — water throughput, not just block count, gates how wide you can scale. Energy
     *  stays ~1/tick (below a TechReborn Basic Solar Panel's ~1.5/tick daily average). */
    public static final int GENERATION_INTERVAL = 2;
    public static final int WATER_PER_CYCLE  = 5;
    public static final int ENERGY_PER_CYCLE = 2;
    /** How many ticks the WORKING glow lingers past the last actual production. Generation happens
     *  in bursts (every {@link #GENERATION_INTERVAL} ticks, and less often when under-supplied), so
     *  a linger longer than any realistic gap keeps the blockstate steady instead of flickering the
     *  light engine. Only a fully-starved generator (no production for this long) goes dark. */
    private static final int WORKING_LINGER = 20;

    /** How often, and how much, the generator pushes its buffered energy out to the connected cable
     *  network — so it powers tech-mod machines + Hydrofarm sinks alike (sinks/cables also pull). */
    private static final int PUSH_INTERVAL = 5;
    private static final int PUSH_RATE = 512;
    /** Back off the energy push (re-probe 10→20→40 ticks) when nothing accepts, so an unplugged or
     *  satisfied generator stops walking the cable network every 5 ticks. Generation is unaffected. */
    private static final int PUSH_BASE_COOLDOWN = 10;
    private static final int PUSH_MAX_COOLDOWN  = 40;
    private static final int PUSH_RAMP_CAP      = 2;
    private final Backoff pushBackoff = new Backoff(PUSH_BASE_COOLDOWN, PUSH_MAX_COOLDOWN, PUSH_RAMP_CAP);

    /** Direct-adjacency water intake: pull from a touching tank/siphon with no pipe (on top of the
     *  pipe network). Rate sits well above the {@code 2.5 mB/tick} burn so a touching tank keeps the
     *  buffer topped; beds/sprinklers expose water insert-only, so this can never drain a farm. */
    private static final int WATER_PULL_INTERVAL = 10;
    private static final int WATER_PULL_RATE = 200;

    private final EnergyNetwork energyNet = new EnergyNetwork();

    private int waterMb = 0;
    private int energyStored = 0;
    /** Game-tick of the last successful generation; drives the lingering WORKING flag. Transient —
     *  on reload it re-lights within a couple of ticks of resuming production. */
    private long lastGenTick = Long.MIN_VALUE;

    /** Cached loader-specific capability wrappers (Fabric Storage/EnergyStorage, NeoForge
     *  ResourceHandler/IEnergyStorage). Held as Object and built via a loader-supplied factory,
     *  mirroring {@link LiquidTankBlockEntity#fluidExposure}. One instance per BE keeps a wrapper's
     *  transaction snapshots consistent across calls. */
    private Object fluidExposure;
    private Object energyExposure;

    public HydroelectricGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(HydrofarmRefs.HYDROELECTRIC_GENERATOR_BE.get(), pos, state);
    }

    @SuppressWarnings("unchecked")
    public <T> T fluidExposure(Function<HydroelectricGeneratorBlockEntity, T> factory) {
        if (fluidExposure == null) fluidExposure = factory.apply(this);
        return (T) fluidExposure;
    }

    @SuppressWarnings("unchecked")
    public <T> T energyExposure(Function<HydroelectricGeneratorBlockEntity, T> factory) {
        if (energyExposure == null) energyExposure = factory.apply(this);
        return (T) energyExposure;
    }

    // ---- Water (input, fuel) --------------------------------------------------------------
    public int getWaterMb()    { return waterMb; }
    public int getWaterCapMb() { return WATER_CAPACITY_MB; }

    /** Accepts up to {@code mb} of water; returns the amount accepted. */
    public int insertWater(int mb) {
        if (mb <= 0) return 0;
        int accepted = Math.min(mb, WATER_CAPACITY_MB - waterMb);
        if (accepted > 0) {
            waterMb += accepted;
            setChanged();
        }
        return accepted;
    }

    /** Direct setter used by the loader wrappers' transaction snapshot restore. */
    public void setWaterMb(int v) {
        waterMb = Mth.clamp(v, 0, WATER_CAPACITY_MB);
        setChanged();
    }

    // ---- Energy (output) ------------------------------------------------------------------
    public int getEnergyStored()   { return energyStored; }
    public int getEnergyCapacity() { return ENERGY_CAPACITY; }

    /** Removes up to {@code amount} of energy; returns the amount removed. */
    public int extractEnergy(int amount) {
        if (amount <= 0) return 0;
        int taken = Math.min(amount, energyStored);
        if (taken > 0) {
            energyStored -= taken;
            setChanged();
        }
        return taken;
    }

    /** Direct setter used by the loader wrappers' transaction snapshot restore. */
    public void setEnergyStored(int v) {
        energyStored = Mth.clamp(v, 0, ENERGY_CAPACITY);
        setChanged();
    }

    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        long now = level.getGameTime();
        // Top up from any directly-adjacent water store (tank/siphon) before generating, so a
        // pipe-free "tank touching generator" build works. Capped to free space so the atomic pull
        // always commits; runs even mid-generation so this tick can use what it draws.
        if (waterMb < WATER_CAPACITY_MB && now % WATER_PULL_INTERVAL == 0) {
            FluidNeighbors.pull(level, pos, Fluids.WATER, Math.min(WATER_PULL_RATE, WATER_CAPACITY_MB - waterMb));
        }
        // Produce only on the cycle tick, and only when a full step fits the buffer so water is
        // never spent on energy that would overflow and be discarded.
        boolean canRun = waterMb >= WATER_PER_CYCLE && energyStored + ENERGY_PER_CYCLE <= ENERGY_CAPACITY;
        if (canRun && now % GENERATION_INTERVAL == 0) {
            waterMb -= WATER_PER_CYCLE;
            energyStored += ENERGY_PER_CYCLE;
            lastGenTick = now;
            setChanged();
        }
        // Push buffered energy out to the cable network — this is what powers tech-mod machines and
        // Hydrofarm sinks downstream (they also pull, which covers tech generators feeding us). Backs
        // off when nothing accepts (no acceptor / all full) so an unplugged generator doesn't walk the
        // network forever; a successful push (detected via the energyStored drop) resets it.
        if (energyStored > 0 && now % PUSH_INTERVAL == 0 && pushBackoff.ready(PUSH_INTERVAL)) {
            int before = energyStored;
            energyNet.push(level, pos, PUSH_RATE);
            if (energyStored < before) {
                pushBackoff.recordMoved();
            } else {
                pushBackoff.recordFutile();
            }
        }
        // WORKING lingers past the last production so a marginally-supplied generator (which makes
        // energy in bursts) stays steadily lit rather than flickering the blockstate + light engine
        // every few ticks. setBlock only fires on a genuine on/off transition.
        boolean working = lastGenTick != Long.MIN_VALUE && (now - lastGenTick) < WORKING_LINGER;
        if (state.getValue(HydroelectricGeneratorBlock.WORKING) != working) {
            BlockState next = state.setValue(HydroelectricGeneratorBlock.WORKING, working);
            // UPDATE_CLIENTS: WORKING drives the glow only (relight is explicit below) — no
            // neighbor reacts to it, so skip the neighborChanged fan-out.
            level.setBlock(pos, next, Block.UPDATE_CLIENTS);
            level.getLightEngine().checkBlock(pos);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putInt("WaterMb", waterMb);
        out.putInt("Energy", energyStored);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        this.waterMb = Mth.clamp(in.getIntOr("WaterMb", 0), 0, WATER_CAPACITY_MB);
        this.energyStored = Mth.clamp(in.getIntOr("Energy", 0), 0, ENERGY_CAPACITY);
    }
}
