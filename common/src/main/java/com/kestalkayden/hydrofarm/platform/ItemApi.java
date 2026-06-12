package com.kestalkayden.hydrofarm.platform;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Platform seam for moving items between block inventories, mirroring {@link FluidApi}.
 *  Implemented per loader (Fabric Transfer ItemStorage; NeoForge item capability/transfer) and
 *  resolved via {@link HydrofarmPlatform#items()}. */
public interface ItemApi {

    /** Item endpoint on {@code side} of the block at {@code pos}, or {@code null} if none. */
    Endpoint find(Level level, BlockPos pos, Direction side);

    /** A repeat-lookup cache for the endpoint on {@code side} of {@code pos}, backed by the
     *  loader's purpose-built cache (NeoForge {@code BlockCapabilityCache} / Fabric
     *  {@code BlockApiCache}) — those skip the per-lookup chunk/BE/provider resolution that
     *  {@link #find} pays every call. Create ONCE per stable lookup site (a terminal face, a
     *  cached network source) and hold it; creating one costs about as much as a find().
     *  Server-side only: both loader caches require a ServerLevel. */
    EndpointCache cache(ServerLevel level, BlockPos pos, Direction side);

    /** A held resolver for one (pos, side). {@link #get()} revalidates cheaply — the loader
     *  invalidates internally when the block there changes — and returns the current endpoint,
     *  or null while none exists. Safe to call every burst. */
    interface EndpointCache {
        Endpoint get();
    }

    /** A resolved item store at one block face. */
    interface Endpoint {

        /** Distinct item variants currently present, as single-count prototype stacks — used by
         *  the item pump for candidate discovery and filter matching. */
        List<ItemStack> contents();

        /** Move up to {@code maxCount} items matching {@code prototype} (same item + components)
         *  from this endpoint into {@code dest}. Partial moves are allowed: the portion the target
         *  can't accept is returned to the source, so it never disappears. Returns the count moved.
         *  {@code dest} must originate from the same loader. */
        int moveTo(Endpoint dest, ItemStack prototype, int maxCount);

        /** How many items matching {@code prototype} could be extracted right now (simulated, no
         *  mutation). Lets the autocrafter confirm a full recipe is available before pulling any of
         *  it. */
        int amountAvailable(ItemStack prototype);

        /** Extract up to {@code maxCount} items matching {@code prototype} and commit. Returns the
         *  extracted stack (empty if none) — used to pull ingredients into the autocrafter. */
        ItemStack extract(ItemStack prototype, int maxCount);

        /** Insert as much of {@code stack} as fits and commit. Returns the count accepted — used to
         *  hand container remainders (empty buckets…) back to a neighbour. */
        int insert(ItemStack stack);
    }
}
