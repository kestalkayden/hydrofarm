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

/** Croptopia compat for the hydroponics planter. FarmlandCrop crops (58 ground crops) work
 *  out of the box via the vanilla CropBlock fallback — players plant {@code [name]_seeds}
 *  which is a SeedItem extending BlockItem pointing at a CroptopiaCropBlock.
 *
 *  This file handles TreeCrops (26 fruits/nuts). Croptopia models tree fruits as a fruit
 *  ITEM that doesn't double as a seed plus a separate {@code [name]_crop} LeafCropBlock that
 *  grows on tree leaves. We map fruit-item → leaf-block adapter so a player can drop e.g. an
 *  apple into the planter and grow more apples, skipping the tree-growing detour entirely.
 *
 *  Discovery is by duck-typing: any block in the {@code croptopia:} namespace whose ID ends
 *  in "_crop" and which exposes BOTH an {@code age} and a {@code distance} blockstate
 *  property is treated as a leaf-crop (FarmlandCrop blocks have only {@code age}). No
 *  compile-time dependency on Croptopia. */
public final class CroptopiaCropAdapters {

    private static final String MOD_ID = "croptopia";
    private static final String CROP_SUFFIX = "_crop";
    /** Drops per harvest cycle. Net +1 fruit per cycle (one for replant + one for the pile). */
    private static final int FRUIT_PER_CYCLE = 2;

    private CroptopiaCropAdapters() {}

    public static void registerIfPresent() {
        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
            if (!MOD_ID.equals(id.getNamespace())) continue;
            if (!id.getPath().endsWith(CROP_SUFFIX)) continue;
            tryRegisterLeafCrop(id);
        }
    }

    private static void tryRegisterLeafCrop(Identifier blockId) {
        Block block = BuiltInRegistries.BLOCK.getValue(blockId);
        if (block == null) return;

        // Duck-type the leaf-crop: needs both `age` AND `distance` blockstate properties.
        // FarmlandCrop CroptopiaCropBlocks have only `age` — those don't need our help.
        Property<?> ageRaw = block.getStateDefinition().getProperty("age");
        Property<?> distanceRaw = block.getStateDefinition().getProperty("distance");
        if (!(ageRaw instanceof IntegerProperty ageProp) || distanceRaw == null) return;
        int maxAge = ageProp.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(3);

        // Strip "_crop" from "apple_crop" → "apple", look up the matching fruit item.
        String fruitName = blockId.getPath().substring(0, blockId.getPath().length() - CROP_SUFFIX.length());
        Item fruit = lookupFruit(fruitName);
        if (fruit == Items.AIR) return;

        CropAdapter.registerExtension(fruit, new CropAdapter() {
            @Override public int maxAge() { return maxAge; }
            @Override public BlockState growthState(int age) {
                int clamped = Math.min(Math.max(age, 0), maxAge);
                return block.defaultBlockState().setValue(ageProp, clamped);
            }
            @Override public List<ItemStack> produceDrops(ServerLevel level, BlockPos pos) {
                return List.of(new ItemStack(fruit, FRUIT_PER_CYCLE));
            }
        });
    }

    /** Croptopia uses {@code Items.APPLE} (vanilla) for its apple fruit rather than registering
     *  {@code croptopia:apple}, so we fall back to the vanilla item when the namespaced lookup
     *  misses on a known-vanilla name. */
    private static Item lookupFruit(String fruitName) {
        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, fruitName);
        if (BuiltInRegistries.ITEM.containsKey(id)) {
            return BuiltInRegistries.ITEM.getValue(id);
        }
        if ("apple".equals(fruitName)) return Items.APPLE;
        return Items.AIR;
    }
}
