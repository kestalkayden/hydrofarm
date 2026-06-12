package com.kestalkayden.hydrofarm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.kestalkayden.hydrofarm.HydrofarmRefs;

public class LiquidSiphonBlockEntity extends BlockEntity {

    public static final int BUFFER_CAPACITY_MB = 2_000;

    /** mB added per source-block consumed. Each consumption removes one whole water source
     *  block — can't go sub-bucket because vanilla water sources are whole-bucket units. */
    private static final int MB_PER_SOURCE = 1_000;
    /** Ticks between extraction attempts — slow baseline (1 source per 10 sec ≈ 100 mB/sec).
     *  Tuned so vanilla source regen comfortably keeps up, and so a future powered version
     *  (2-4× speedup with adjacent energy storage) is a meaningful upgrade. */
    private static final int EXTRACTION_INTERVAL = 200;
    /** Ticks between ACTIVE-state polls. Independent of extraction so the side-strip animation
     *  reacts within ~1 sec of placing/breaking nearby water, rather than waiting for the next
     *  10-second consume cycle. */
    private static final int ACTIVE_POLL_INTERVAL = 20;
    /** Direct-adjacency water output: push into a touching tank/machine with no pipe (on top of the
     *  pipe network). Rate sits above the ~5 mB/tick intake so a touching tank drains the buffer as
     *  fast as it fills. */
    private static final int OUTPUT_INTERVAL = 10;
    private static final int OUTPUT_RATE = 200;
    /** Back off the push (re-probe 10→20→40 ticks) when neighbours are full, so a siphon next to a
     *  full tank stops walking its neighbours every output cycle. Source consumption is unaffected. */
    private static final int PUSH_BASE_COOLDOWN = 10;
    private static final int PUSH_MAX_COOLDOWN  = 40;
    private static final int PUSH_RAMP_CAP      = 2;

    private int waterMb = 0;
    private int pushCooldown = 0;
    private int pushFutileStreak = 0;

    public LiquidSiphonBlockEntity(BlockPos pos, BlockState state) {
        super(HydrofarmRefs.LIQUID_SIPHON_BE.get(), pos, state);
    }

    public int getWaterMb() { return waterMb; }
    public int getCapacityMb() { return BUFFER_CAPACITY_MB; }
    public int getRoomMb() { return BUFFER_CAPACITY_MB - waterMb; }

    /** Pull water out — used by external fluid caps (drain pumps) and by bucket interaction. */
    public int extractWater(int amount) {
        if (amount <= 0) return 0;
        int taken = Math.min(amount, waterMb);
        if (taken > 0) {
            waterMb -= taken;
            sync();
        }
        return taken;
    }

    /** Restores buffer to a snapshot value during transaction rollback. */
    public void setWaterMb(int amount) {
        waterMb = Math.max(0, Math.min(BUFFER_CAPACITY_MB, amount));
        sync();
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(getBlockPos(), state, state, Block.UPDATE_CLIENTS);
        }
    }

    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        long t = level.getGameTime();

        if (t % ACTIVE_POLL_INTERVAL == 0) {
            boolean newActive = getRoomMb() >= MB_PER_SOURCE && hasWaterSourceInRange(level, pos);
            if (state.getValue(LiquidSiphonBlock.ACTIVE) != newActive) {
                // UPDATE_CLIENTS: ACTIVE drives the visual only — no neighbor reacts to it.
                level.setBlock(pos, state.setValue(LiquidSiphonBlock.ACTIVE, newActive), Block.UPDATE_CLIENTS);
            }
        }

        // Offload buffered water directly into any adjacent tank/machine — no pipe required. Runs
        // before the extraction gate so it keeps draining even on ticks the siphon isn't consuming.
        // Backs off when neighbours are full (FluidNeighbors.push returns mB moved); resets on a
        // successful push. Source consumption below is unaffected.
        if (waterMb > 0 && t % OUTPUT_INTERVAL == 0) {
            if (pushCooldown > 0) {
                pushCooldown -= OUTPUT_INTERVAL;
            } else {
                int moved = FluidNeighbors.push(level, pos, Fluids.WATER, Math.min(OUTPUT_RATE, waterMb));
                if (moved > 0) {
                    pushFutileStreak = 0;
                } else {
                    pushFutileStreak++;
                    pushCooldown = Math.min(PUSH_MAX_COOLDOWN,
                        PUSH_BASE_COOLDOWN << Math.min(pushFutileStreak - 1, PUSH_RAMP_CAP));
                }
            }
        }

        if (t % EXTRACTION_INTERVAL != 0) return;
        if (getRoomMb() < MB_PER_SOURCE) return;
        tryConsumeOneSourceBlock(level, pos);
    }

    /** Read-only scan: returns true if any water source sits in the 3×3×3 cube around the siphon.
     *  Used by the ACTIVE poll; does not consume. */
    private boolean hasWaterSourceInRange(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    FluidState fluid = level.getBlockState(cursor).getFluidState();
                    if (fluid.is(Fluids.WATER) && fluid.isSource()) return true;
                }
            }
        }
        return false;
    }

    /** Scans the 3×3×3 area centered on the siphon (skipping the siphon's own position) and
     *  removes the first water source it finds, adding {@link #MB_PER_SOURCE} to the buffer.
     *  Vanilla water-source regeneration fills the gap from neighboring sources, making a
     *  2×2+ pool effectively infinite. Accepts pools below, beside, or above the siphon. */
    private void tryConsumeOneSourceBlock(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    BlockState neighbor = level.getBlockState(cursor);
                    FluidState fluid = neighbor.getFluidState();
                    if (fluid.is(Fluids.WATER) && fluid.isSource()) {
                        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        waterMb = Math.min(BUFFER_CAPACITY_MB, waterMb + MB_PER_SOURCE);
                        sync();
                        return;
                    }
                }
            }
        }
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

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return saveCustomOnly(provider);
    }
}
