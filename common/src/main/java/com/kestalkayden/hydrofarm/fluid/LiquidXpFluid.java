package com.kestalkayden.hydrofarm.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Minimal Fluid subclass for "Liquid XP" identity. Lives only inside tanks/pipes/pumps — not
 *  placeable in the world for v1 (no source/flowing block, no bucket). The tank renderer
 *  resolves its still sprite by convention from {@code hydrofarm:block/liquid_xp_still}. */
public class LiquidXpFluid extends Fluid {

    @Override
    public Item getBucket() { return Items.AIR; }

    @Override
    public boolean canBeReplacedWith(FluidState state, BlockGetter level, BlockPos pos,
                                     Fluid other, Direction direction) {
        return true;
    }

    @Override
    public Vec3 getFlow(BlockGetter level, BlockPos pos, FluidState fluidState) {
        return Vec3.ZERO;
    }

    @Override
    public int getTickDelay(LevelReader level) { return 0; }

    @Override
    protected float getExplosionResistance() { return 100.0F; }

    @Override
    public float getHeight(FluidState fluidState, BlockGetter level, BlockPos pos) { return 0.0F; }

    @Override
    public float getOwnHeight(FluidState fluidState) { return 0.0F; }

    @Override
    protected BlockState createLegacyBlock(FluidState fluidState) {
        return Blocks.AIR.defaultBlockState();
    }

    /** Reported as source so Fabric's FluidVariant.of() accepts it. We don't have a flowing
     *  variant at all — this is a "still-only" fluid that only exists inside our tank network. */
    @Override
    public boolean isSource(FluidState fluidState) { return true; }

    @Override
    public int getAmount(FluidState fluidState) { return 0; }

    @Override
    public VoxelShape getShape(FluidState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }
}
