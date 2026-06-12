package com.kestalkayden.hydrofarm.compat;

import java.util.List;

import com.kestalkayden.hydrofarm.block.CropAdapter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

/** Farmers Delight compat for the hydroponics planter. Resolves rice via ResourceLocation
 *  lookups so this class is safe to load even when FD isn't installed — if any lookup misses,
 *  registration silently bails. No compile-time dependency on FD's classes. */
public final class FarmersDelightCropAdapters {

    private static final String MOD_ID = "farmersdelight";
    private static final int RICE_PER_CYCLE = 2;
    /** Cycle length (growth stages) for the foraged wild plants — matches a standard crop. */
    private static final int WILD_MAX_AGE = 7;

    private FarmersDelightCropAdapters() {}

    public static void registerIfPresent() {
        registerRice();
        registerTomato();
        registerWildCrops();
    }

    private static void registerRice() {
        Identifier riceId = Identifier.fromNamespaceAndPath(MOD_ID, "rice");
        if (!BuiltInRegistries.ITEM.containsKey(riceId)) return;  // FD not loaded
        Item riceItem = BuiltInRegistries.ITEM.getValue(riceId);
        Item ricePanicle = lookupItem("rice_panicle");
        Block riceBlock = BuiltInRegistries.BLOCK.getValue(riceId);
        if (riceItem == Items.AIR || riceBlock == null) return;

        // FD's RiceBlock has AGE_3 (0-3) + SUPPORTING. We look the property up by name so we
        // don't reference the FD class directly.
        Property<?> ageRaw = riceBlock.getStateDefinition().getProperty("age");
        if (!(ageRaw instanceof IntegerProperty ageProp)) return;
        int maxAge = ageProp.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(3);

        CropAdapter.registerExtension(riceItem, new CropAdapter() {
            @Override public int maxAge() { return maxAge; }
            @Override public BlockState growthState(int age) {
                int clamped = Math.min(Math.max(age, 0), maxAge);
                return riceBlock.defaultBlockState().setValue(ageProp, clamped);
            }
            @Override public List<ItemStack> produceDrops(ServerLevel level, BlockPos pos) {
                // Vanilla FD: knife-harvested panicle → 1 rice, knife-less → 1 rice_panicle.
                // Hydroponics: yield the loot-table union as deterministic bulk drops so the
                // player gets both the seed (rice, to replant downstream) and the byproduct
                // (rice_panicle, used in FD recipes like rice rolls).
                ItemStack rice = new ItemStack(riceItem, RICE_PER_CYCLE);
                if (ricePanicle == Items.AIR) return List.of(rice);
                return List.of(rice, new ItemStack(ricePanicle, 1));
            }
        });
    }

    private static void registerTomato() {
        Item seed = lookupItem("tomato_seeds");
        Block crop = lookupBlock("budding_tomatoes");
        Item tomato = lookupItem("tomato");
        if (seed == Items.AIR || crop == null || tomato == Items.AIR) return;  // FD not loaded

        // Tomato is planted as budding_tomatoes — a BushBlock, not a CropBlock, so the generic
        // resolver skips it. Render it through its "age" property and yield tomatoes per cycle.
        Property<?> ageRaw = crop.getStateDefinition().getProperty("age");
        if (!(ageRaw instanceof IntegerProperty ageProp)) return;
        int maxAge = ageProp.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(4);

        CropAdapter.registerExtension(seed, new CropAdapter() {
            @Override public int maxAge() { return maxAge; }
            @Override public BlockState growthState(int age) {
                int clamped = Math.min(Math.max(age, 0), maxAge);
                return crop.defaultBlockState().setValue(ageProp, clamped);
            }
            @Override public List<ItemStack> produceDrops(ServerLevel level, BlockPos pos) {
                // FD's mature pick is 1-2 tomatoes; mirror that as the per-cycle yield.
                return List.of(new ItemStack(tomato, 1 + level.getRandom().nextInt(2)));
            }
        });
    }

    /** Make the foraged "wild" plants farmable in the hydroponics planter (they're decorative
     *  FlowerBlocks in vanilla FD). Each renders its wild block and yields its food crop per
     *  cycle, so grabbing a wild onion and planting it does what a player intuitively expects. */
    private static void registerWildCrops() {
        registerWildCrop("wild_onions", "onion");
        registerWildCrop("wild_cabbages", "cabbage");
        registerWildCrop("wild_tomatoes", "tomato");
        registerWildCrop("wild_rice", "rice");
    }

    private static void registerWildCrop(String wildId, String foodId) {
        Item wildItem = lookupItem(wildId);
        Block wildBlock = lookupBlock(wildId);
        Item food = lookupItem(foodId);
        if (wildItem == Items.AIR || wildBlock == null || food == Items.AIR) return;

        // Wild blocks have no growth stages — render the single block state throughout, like the
        // existing tall-plant adapters. The hydroponics bed regrows the same plant each cycle, so
        // the food drop need not be replantable.
        BlockState render = wildBlock.defaultBlockState();
        CropAdapter.registerExtension(wildItem, new CropAdapter() {
            @Override public int maxAge() { return WILD_MAX_AGE; }
            @Override public BlockState growthState(int age) { return render; }
            @Override public List<ItemStack> produceDrops(ServerLevel level, BlockPos pos) {
                return List.of(new ItemStack(food, 1 + level.getRandom().nextInt(2)));
            }
        });
    }

    private static Item lookupItem(String path) {
        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, path);
        return BuiltInRegistries.ITEM.containsKey(id)
            ? BuiltInRegistries.ITEM.getValue(id)
            : Items.AIR;
    }

    private static Block lookupBlock(String path) {
        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, path);
        return BuiltInRegistries.BLOCK.containsKey(id)
            ? BuiltInRegistries.BLOCK.getValue(id)
            : null;
    }
}
