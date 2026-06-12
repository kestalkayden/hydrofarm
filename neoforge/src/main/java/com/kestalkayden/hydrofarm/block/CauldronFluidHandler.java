package com.kestalkayden.hydrofarm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** Block-level NeoForge fluid handler for vanilla cauldrons — there is no block entity, so all state
 *  lives in the {@link BlockState} and the snapshot is the block state itself. Gives NeoForge the
 *  same pipe-connectable cauldron behaviour the Fabric Transfer API ships for free, in matching
 *  whole-level increments: a water cauldron holds 1-3 levels (1000 mB full), a lava cauldron is a
 *  single 1000 mB unit, and an empty cauldron is a fillable target. Powder-snow cauldrons aren't
 *  registered (powder snow isn't a {@link Fluid}).
 *
 *  <p>Whole-level quantized like Fabric's storage: a sub-level request moves nothing (return 0) so
 *  the transfer is always leak-free. The liquid pipe terminal's coarse-source fallback issues a
 *  bucket-sized request that clears the level threshold. */
public class CauldronFluidHandler extends SnapshotJournal<BlockState>
        implements ResourceHandler<FluidResource> {

    private static final int BUCKET_MB = 1000;
    private static final int MAX_WATER_LEVEL = 3;

    private final Level level;
    private final BlockPos pos;

    public CauldronFluidHandler(Level level, BlockPos pos) {
        this.level = level;
        this.pos = pos;
    }

    /** Cumulative mB held by a water cauldron at fill level L (0-3); full (level 3) = one bucket. */
    private static int waterMbForLevel(int fillLevel) {
        return Math.round(fillLevel * (float) BUCKET_MB / MAX_WATER_LEVEL);   // {0:0,1:333,2:667,3:1000}
    }

    @Override
    public int size() { return 1; }

    @Override
    public FluidResource getResource(int slot) {
        BlockState s = level.getBlockState(pos);
        if (s.is(Blocks.WATER_CAULDRON)) return FluidResource.of(Fluids.WATER);
        if (s.is(Blocks.LAVA_CAULDRON)) return FluidResource.of(Fluids.LAVA);
        return FluidResource.EMPTY;
    }

    @Override
    public long getAmountAsLong(int slot) {
        BlockState s = level.getBlockState(pos);
        if (s.is(Blocks.WATER_CAULDRON)) return waterMbForLevel(s.getValue(LayeredCauldronBlock.LEVEL));
        if (s.is(Blocks.LAVA_CAULDRON)) return BUCKET_MB;
        return 0;
    }

    @Override
    public long getCapacityAsLong(int slot, FluidResource resource) {
        return BUCKET_MB;   // a cauldron holds one bucket
    }

    @Override
    public boolean isValid(int slot, FluidResource resource) {
        if (resource.isEmpty()) return false;
        Fluid f = resource.getFluid();
        BlockState s = level.getBlockState(pos);
        if (s.is(Blocks.WATER_CAULDRON)) return f == Fluids.WATER;
        if (s.is(Blocks.LAVA_CAULDRON)) return f == Fluids.LAVA;
        // Empty cauldron (Blocks.CAULDRON) accepts either; anything else (e.g. powder snow) accepts none.
        return s.is(Blocks.CAULDRON) && (f == Fluids.WATER || f == Fluids.LAVA);
    }

    @Override
    public int insert(int slot, FluidResource resource, int amount, TransactionContext tx) {
        if (resource.isEmpty() || amount <= 0) return 0;
        Fluid f = resource.getFluid();
        BlockState s = level.getBlockState(pos);

        if (f == Fluids.WATER) {
            int current;
            if (s.is(Blocks.WATER_CAULDRON)) current = s.getValue(LayeredCauldronBlock.LEVEL);
            else if (s.is(Blocks.CAULDRON)) current = 0;
            else return 0;   // lava / non-cauldron — can't add water
            int currentMb = waterMbForLevel(current);
            int target = current;
            for (int t = current + 1; t <= MAX_WATER_LEVEL; t++) {
                if (waterMbForLevel(t) - currentMb <= amount) target = t; else break;
            }
            if (target == current) return 0;   // not even one whole level fits
            updateSnapshots(tx);
            level.setBlock(pos, Blocks.WATER_CAULDRON.defaultBlockState()
                .setValue(LayeredCauldronBlock.LEVEL, target), Block.UPDATE_ALL);
            return waterMbForLevel(target) - currentMb;
        }
        if (f == Fluids.LAVA) {
            if (!s.is(Blocks.CAULDRON) || amount < BUCKET_MB) return 0;   // lava is all-or-nothing
            updateSnapshots(tx);
            level.setBlock(pos, Blocks.LAVA_CAULDRON.defaultBlockState(), Block.UPDATE_ALL);
            return BUCKET_MB;
        }
        return 0;
    }

    @Override
    public int extract(int slot, FluidResource resource, int amount, TransactionContext tx) {
        if (resource.isEmpty() || amount <= 0) return 0;
        Fluid f = resource.getFluid();
        BlockState s = level.getBlockState(pos);

        if (f == Fluids.WATER) {
            if (!s.is(Blocks.WATER_CAULDRON)) return 0;
            int current = s.getValue(LayeredCauldronBlock.LEVEL);
            int currentMb = waterMbForLevel(current);
            int target = current;
            for (int t = current - 1; t >= 0; t--) {
                if (currentMb - waterMbForLevel(t) <= amount) target = t; else break;
            }
            if (target == current) return 0;   // can't remove even one whole level
            updateSnapshots(tx);
            BlockState next = target == 0
                ? Blocks.CAULDRON.defaultBlockState()
                : Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, target);
            level.setBlock(pos, next, Block.UPDATE_ALL);
            return currentMb - waterMbForLevel(target);
        }
        if (f == Fluids.LAVA) {
            if (!s.is(Blocks.LAVA_CAULDRON) || amount < BUCKET_MB) return 0;   // all-or-nothing
            updateSnapshots(tx);
            level.setBlock(pos, Blocks.CAULDRON.defaultBlockState(), Block.UPDATE_ALL);
            return BUCKET_MB;
        }
        return 0;
    }

    @Override
    protected BlockState createSnapshot() {
        return level.getBlockState(pos);
    }

    @Override
    protected void revertToSnapshot(BlockState snapshot) {
        level.setBlock(pos, snapshot, Block.UPDATE_ALL);
    }
}
