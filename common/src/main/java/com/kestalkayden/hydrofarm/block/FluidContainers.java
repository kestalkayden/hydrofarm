package com.kestalkayden.hydrofarm.block;

import java.util.function.IntConsumer;

import com.kestalkayden.hydrofarm.HydrofarmRefs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/** Single source of truth for fluid&lt;-&gt;container interactions, shared by the Liquid Tank and the
 *  cluster beds so the amounts and fluid-to-item mappings can never drift apart.
 *
 *  <p>Covers the EXTRACT side generically — {@link #tryFill} fills a held empty bucket or glass
 *  bottle from a stored fluid via a caller-supplied drain. The tank adds its own insert side on
 *  top using {@link #bucketContentFluid}. */
public final class FluidContainers {
    private FluidContainers() {}

    /** A bucket of any fluid is exactly 1000 mB. */
    public static final int BUCKET_MB = 1000;

    /** A glass-bottle extraction: the produced bottle item + how many mB it drains. */
    private record BottleProduct(ItemStack result, int drainMb) {}

    /** Fills the held empty bucket or glass bottle from a fluid source. The caller supplies the
     *  stored fluid, how much is available (already cluster-pooled where relevant), and a
     *  {@code drain} that removes the consumed mB. Only mutates server-side, and only when the
     *  container has a vanilla equivalent for that fluid and enough is available. Returns
     *  {@link InteractionResult#PASS} when the held item isn't a fillable container for this fluid,
     *  so the caller can fall through to its own handling. */
    public static InteractionResult tryFill(ItemStack held, Player player, InteractionHand hand,
                                             Level level, BlockPos pos, Fluid stored, int availableMb,
                                             IntConsumer drain) {
        if (held.is(Items.BUCKET)) {
            Item filled = filledBucketFor(stored);
            if (filled != null && availableMb >= BUCKET_MB) {
                if (!level.isClientSide()) {
                    drain.accept(BUCKET_MB);
                    if (!player.getAbilities().instabuild) {
                        replaceHandItem(player, hand, held, new ItemStack(filled));
                    }
                    level.playSound(null, pos, fillSoundFor(stored), SoundSource.BLOCKS, 1.0F, 1.0F);
                }
                return InteractionResult.SUCCESS;
            }
        }
        if (held.is(Items.GLASS_BOTTLE)) {
            BottleProduct product = bottleProductFor(stored);
            if (product != null && availableMb >= product.drainMb()) {
                if (!level.isClientSide()) {
                    drain.accept(product.drainMb());
                    if (!player.getAbilities().instabuild) {
                        replaceHandItem(player, hand, held, product.result());
                    }
                    level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
                }
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }

    /** Fluid carried by a filled bucket, for the tank's insert side. EMPTY when {@code item} isn't
     *  a known filled bucket (mod fluids without a bucket fall through to the caller). */
    public static Fluid bucketContentFluid(Item item) {
        if (item == Items.WATER_BUCKET) return Fluids.WATER;
        if (item == Items.LAVA_BUCKET)  return Fluids.LAVA;
        if (item == Items.MILK_BUCKET)  return HydrofarmRefs.FLUID_MILK.get();
        return Fluids.EMPTY;
    }

    /** Sound for emptying a filled bucket INTO a source (tank insert side). */
    public static SoundEvent emptySoundFor(Fluid fluid) {
        return fluid == Fluids.LAVA ? SoundEvents.BUCKET_EMPTY_LAVA : SoundEvents.BUCKET_EMPTY;
    }

    /** Replaces one of the held container items with the produced item, handling stacks (&gt;1) by
     *  shrinking and placing the result back in the inventory. */
    public static void replaceHandItem(Player player, InteractionHand hand, ItemStack used, ItemStack replacement) {
        if (used.getCount() == 1) {
            player.setItemInHand(hand, replacement);
        } else {
            used.shrink(1);
            player.getInventory().placeItemBackInInventory(replacement);
        }
    }

    // ---- internals (extract side) ---------------------------------------------------------------

    /** Filled-bucket item for a stored fluid, or null when the fluid has no vanilla bucket (e.g.
     *  liquid XP — bottle that with a glass bottle instead). */
    private static Item filledBucketFor(Fluid fluid) {
        if (fluid == Fluids.WATER) return Items.WATER_BUCKET;
        if (fluid == Fluids.LAVA)  return Items.LAVA_BUCKET;
        if (fluid == HydrofarmRefs.FLUID_MILK.get()) return Items.MILK_BUCKET;
        return null;
    }

    /** Glass-bottle product for a stored fluid, or null when the fluid can't be bottled. */
    private static BottleProduct bottleProductFor(Fluid fluid) {
        if (fluid == Fluids.WATER) {
            // Vanilla cauldron drains ~1/3 per bottle; 250 mB is a clean quarter-bucket.
            ItemStack waterBottle = new ItemStack(Items.POTION);
            waterBottle.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.WATER));
            return new BottleProduct(waterBottle, 250);
        }
        if (fluid == HydrofarmRefs.LIQUID_XP.get()) {
            // A thrown experience_bottle yields 3-11 XP (avg ~7); at the XP drain's 20 mB/XP that's
            // ~140 mB, so bottling is XP-neutral vs draining (you pay one glass bottle for portable XP).
            return new BottleProduct(new ItemStack(Items.EXPERIENCE_BOTTLE), 140);
        }
        return null;
    }

    /** Sound for filling an empty bucket FROM a source (extract side). */
    private static SoundEvent fillSoundFor(Fluid fluid) {
        return fluid == Fluids.LAVA ? SoundEvents.BUCKET_FILL_LAVA : SoundEvents.BUCKET_FILL;
    }
}
