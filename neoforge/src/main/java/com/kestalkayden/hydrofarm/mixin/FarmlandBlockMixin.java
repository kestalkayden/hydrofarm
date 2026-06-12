package com.kestalkayden.hydrofarm.mixin;

import com.kestalkayden.hydrofarm.block.HydrofarmBlocks;
import com.kestalkayden.hydrofarm.block.SprinklerBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FarmlandBlock.class)
public abstract class FarmlandBlockMixin {

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void hydrofarm$preventDecayNearSprinkler(
            BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        // Decay-save costs the sprinkler 1 mB (irrigation upkeep); a sprinkler that can't pay
        // loses its protection and the farmland decays normally — see the Fabric twin's javadoc.
        SprinklerBlockEntity sprinkler = hydrofarm$findWateredSprinkler(level, pos);
        if (sprinkler != null && sprinkler.chargeMoistureUpkeep()) {
            int moisture = state.getValue(FarmlandBlock.MOISTURE);
            if (moisture < 7) {
                level.setBlock(pos, state.setValue(FarmlandBlock.MOISTURE, 7), Block.UPDATE_CLIENTS);
            }
            ci.cancel();
        }
    }

    /** First sprinkler with water remaining within vanilla's water-scan box, or null. */
    @Unique
    private static SprinklerBlockEntity hydrofarm$findWateredSprinkler(ServerLevel level, BlockPos pos) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))) {
            if (!level.getBlockState(p).is(HydrofarmBlocks.SPRINKLER.get())) continue;
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof SprinklerBlockEntity sbe && sbe.getWaterMb() > 0) {
                return sbe;
            }
        }
        return null;
    }
}
