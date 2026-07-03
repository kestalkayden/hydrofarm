package com.kestalkayden.hydrofarm.mixin;

import com.kestalkayden.hydrofarm.block.SprinklerBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FarmlandBlock;
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

    /** Delegates to the sprinkler position index instead of scanning a 162-block getBlockState box
     *  on every farmland decay tick — see {@link SprinklerBlockEntity#wateredSprinklerNear}. */
    @Unique
    private static SprinklerBlockEntity hydrofarm$findWateredSprinkler(ServerLevel level, BlockPos pos) {
        return SprinklerBlockEntity.wateredSprinklerNear(level, pos);
    }
}
