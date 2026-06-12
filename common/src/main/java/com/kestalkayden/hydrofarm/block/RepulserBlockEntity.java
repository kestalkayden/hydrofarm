package com.kestalkayden.hydrofarm.block;

import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.platform.EnergyBuffer;

/** Monster Repulser — a powered area-denial field. While powered (and not redstone-disabled) it
 *  suppresses hostile-mob spawns and poofs wanderers within a flat disc ({@link #RADIUS} blocks
 *  horizontal, ±{@link #VERTICAL} vertical). Energy is pulled from the cable network (sink-pull) and
 *  spent as a flat upkeep — one Hydroelectric Generator (20 energy/s) sustains exactly one field.
 *
 *  <p>The spawn suppression + sweep land in the next phase; this phase is the powered block: buffer,
 *  cable pull, upkeep, and the ACTIVE state that drives the glow. */
public class RepulserBlockEntity extends BlockEntity implements EnergyBuffer {

    /** Field geometry. Horizontal radius (and its square, for cheap distance tests) + vertical reach. */
    public static final int RADIUS = 64;
    public static final int RADIUS_SQ = RADIUS * RADIUS;
    public static final int VERTICAL = 8;

    public static final int ENERGY_CAPACITY = 5_000;
    /** Flat upkeep spent per {@link #UPKEEP_INTERVAL} while the field is up (≈20 energy/second). */
    public static final int UPKEEP = 20;
    private static final int UPKEEP_INTERVAL = 20;
    /** Reactivation hysteresis. The field drops the instant it can't pay (so it NEVER runs for free —
     *  every active cycle pays full {@link #UPKEEP}, preserving the "one generator per field"
     *  contract), but once down it won't re-arm until the buffer has rebuilt this margin (2× upkeep).
     *  That debounces the on/off so marginal supply doesn't strobe ACTIVE — and its setBlock +
     *  light-engine relight — every upkeep tick. It is pure hysteresis, not an upkeep discount. */
    private static final int REACTIVATE_ENERGY = 2 * UPKEEP;

    private static final int PULL_INTERVAL = 5;
    private static final int PULL_RATE = 256;

    private int energyStored = 0;

    private final EnergyNetwork energyNet = new EnergyNetwork();
    /** Cached loader insert-only energy wrapper (one per BE for transaction consistency). */
    private Object energyExposure;

    public RepulserBlockEntity(BlockPos pos, BlockState state) {
        super(HydrofarmRefs.REPULSER_BE.get(), pos, state);
    }

    @SuppressWarnings("unchecked")
    public <T> T energyExposure(Function<RepulserBlockEntity, T> factory) {
        if (energyExposure == null) energyExposure = factory.apply(this);
        return (T) energyExposure;
    }

    // ---- EnergyBuffer ---------------------------------------------------------------------
    @Override public int getEnergyStored()   { return energyStored; }
    @Override public int getEnergyCapacity() { return ENERGY_CAPACITY; }

    @Override
    public int insertEnergy(int amount) {
        if (amount <= 0) return 0;
        int accepted = Math.min(amount, ENERGY_CAPACITY - energyStored);
        if (accepted > 0) { energyStored += accepted; setChanged(); }
        return accepted;
    }

    @Override
    public void setEnergyStored(int v) {
        energyStored = Math.max(0, Math.min(ENERGY_CAPACITY, v));
        setChanged();
    }

    /** Whether the field is currently up — powered and not redstone-disabled. */
    public boolean isFieldActive() {
        BlockState state = getBlockState();
        return state.hasProperty(RepulserBlock.ACTIVE) && state.getValue(RepulserBlock.ACTIVE);
    }

    // ---- Tick -----------------------------------------------------------------------------
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        long now = level.getGameTime();

        if (now % PULL_INTERVAL == 0) {
            energyNet.pull(level, pos, this, PULL_RATE);
        }

        if (now % UPKEEP_INTERVAL == 0) {
            // A redstone signal switches the field off instantly.
            boolean enabled = !level.hasNeighborSignal(pos);
            boolean wasActive = state.getValue(RepulserBlock.ACTIVE);
            // Stay up while we can pay the full upkeep; once down, wait for a 2× margin before
            // re-arming so marginal supply doesn't strobe. No free uptime — active always pays.
            boolean active = enabled && energyStored >= (wasActive ? UPKEEP : REACTIVATE_ENERGY);
            if (active) {
                energyStored -= UPKEEP;
                setChanged();
            }
            // Refresh the suppression registry every upkeep tick (also self-heals after a reload).
            RepulserField.setActive(level, pos, active);
            if (wasActive != active) {
                // UPDATE_CLIENTS (not UPDATE_ALL): the ACTIVE flip drives the glow only — no
                // neighbor reactions needed. Keep the explicit relight (setBlock alone isn't reliable).
                level.setBlock(pos, state.setValue(RepulserBlock.ACTIVE, active), Block.UPDATE_CLIENTS);
                level.getLightEngine().checkBlock(pos);
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide()) {
            RepulserField.setActive(level, getBlockPos(), false);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putInt("Energy", energyStored);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        energyStored = Math.max(0, Math.min(ENERGY_CAPACITY, in.getIntOr("Energy", 0)));
    }
}
