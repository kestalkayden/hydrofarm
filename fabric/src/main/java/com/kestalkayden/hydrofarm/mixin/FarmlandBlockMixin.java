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

/** Cancels {@link FarmlandBlock}'s random-tick decay path when a Hydrofarm sprinkler is within
 *  the same 9×2×9 area vanilla scans for isNearWater — CHARGING that sprinkler 1 mB per save
 *  (irrigation upkeep, ~1.2 mB/s across a full 80-tile box). Restores MOISTURE=7 along the way
 *  so freshly-hoed (MOISTURE=0) farmland in a sprinkler's AoE jumps straight to fully hydrated.
 *  A sprinkler that can't pay loses its protection — the farmland decays back to dirt, which
 *  makes water depletion meaningful as a UX signal.
 *
 *  Without this, vanilla decay can race ahead of our BE tick on freshly-placed farmland and
 *  convert it to dirt. The fluid-state-as-water alternative worked but rendered visible water
 *  inside the sprinkler block and exposed a fake fluid in Jade/JEI — this mixin avoids both. */
@Mixin(FarmlandBlock.class)
public abstract class FarmlandBlockMixin {

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void hydrofarm$preventDecayNearSprinkler(
            BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
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
