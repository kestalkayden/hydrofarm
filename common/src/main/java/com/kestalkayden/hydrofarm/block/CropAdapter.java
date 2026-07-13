package com.kestalkayden.hydrofarm.block;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kestalkayden.hydrofarm.compat.CroptopiaCropAdapters;
import com.kestalkayden.hydrofarm.compat.FarmersDelightCropAdapters;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/** Uniform interface over the assorted vanilla growable plants the hydroponics planter
 *  supports. {@link CropBlock} handles wheat/carrot/potato/beetroot natively; other plants
 *  (stems, nether wart, sweet berries, cocoa, chorus flower) extend different block classes
 *  and need bespoke growth + harvest mapping. The adapter unifies them so the bed BE doesn't
 *  branch on type. */
public interface CropAdapter {
    /** Which kind of bed accepts this adapter. The hydroponics bed filters to CROP, the tree
     *  farm bed filters to TREE, and future animal beds will filter to ANIMAL_PASSIVE /
     *  ANIMAL_CULL. Adapters default to CROP so the existing 11 implementations don't need
     *  to declare anything. */
    enum Category { CROP, TREE, MUSHROOM, ANIMAL_PASSIVE, ANIMAL_CULL }

    default Category category() { return Category.CROP; }

    /** Number of distinct VISUAL growth stages (the plant's vanilla max age). Now that the harvest
     *  cadence is normalized via {@link #growthSteps()}, this is purely cosmetic — the renderer
     *  interpolates the on-screen stage across it. Caller clamps {@code age} to maxAge. */
    int maxAge();
    /** BlockState shown in the planter while growing. Caller clamps {@code age} to maxAge. */
    BlockState growthState(int age);
    /** BlockState whose loot table we sample at harvest. For fruit-bearing plants this is the
     *  FRUIT block (melon → MELON, pumpkin → PUMPKIN, chorus → CHORUS_PLANT). Defaults to the
     *  mature growth state, which is correct for crops that drop their own seeds (wheat, etc.). */
    default BlockState harvestState() { return growthState(maxAge()); }

    /** Growth windows (each {@code GROWTH_INTERVAL_TICKS}) before a quadrant harvests. Crops are
     *  NORMALIZED to a flat cadence decoupled from the plant's vanilla growth speed — so every crop
     *  produces at the same rate regardless of how fast it grows in vanilla. Trees and other
     *  non-CROP adapters keep their own {@link #maxAge}-based cadence. */
    int NORMALIZED_CROP_GROWTH_STEPS = 6;  // 6 grow + 1 harvest = 7 windows × 40s = 280s ≈ 12.9/hr
    default int growthSteps() {
        return category() == Category.CROP ? NORMALIZED_CROP_GROWTH_STEPS : maxAge();
    }

    /** The item this CROP yields per harvest (quantity from {@link #harvestCount()}) — deterministic,
     *  with NO per-harvest loot-table evaluation. Return {@link Items#AIR} to opt out: trees and modded
     *  crops without an explicit product fall back to {@link #produceDrops}. */
    default Item harvestItem() { return Items.AIR; }

    /** How many {@link #harvestItem()} one harvest yields. Defaults to 1 — every normalized crop is a
     *  single product per cycle. Melon overrides this (4) to reflect its multi-slice vanilla yield;
     *  a single slice per cycle made it the least valuable crop by a wide margin. */
    default int harvestCount() { return 1; }

    /** Drops produced for ONE harvest cycle. Default samples {@link #harvestState()}'s loot
     *  table once. "Tall plants" like bamboo override to return deterministic bulk drops since
     *  one harvest abstractly represents an N-block tower of growth. */
    default List<ItemStack> produceDrops(ServerLevel level, BlockPos pos) {
        LootParams.Builder params = new LootParams.Builder(level)
            .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
            .withParameter(LootContextParams.TOOL, ItemStack.EMPTY);
        return harvestState().getDrops(params);
    }

    /** Registry of mod-provided adapters. Populated lazily on first resolve(). Keyed by Item
     *  so lookups are O(1). Mod compat modules register here via {@link #registerExtension}. */
    Map<Item, CropAdapter> EXTENSIONS = new ConcurrentHashMap<>();
    AtomicBoolean EXTENSIONS_INITIALIZED = new AtomicBoolean(false);

    /** Result cache for {@link #resolve(Item)}. The vanilla {@link CropBlock} and generic-sapling
     *  paths each allocate a fresh adapter (and saplings do registry lookups) on every call, while
     *  the renderer resolves up to 4 quadrants per bed every frame. An adapter is immutable per seed
     *  Item, so the resolved value — or {@link #NONE} for unsupported items — is memoized here. */
    Map<Item, CropAdapter> RESOLVED_CACHE = new ConcurrentHashMap<>();
    /** Sentinel stored in {@link #RESOLVED_CACHE} for items with no adapter, so repeated lookups of
     *  an unsupported item skip the resolve chain (ConcurrentHashMap can't store a null value). */
    CropAdapter NONE = new CropAdapter() {
        @Override public int maxAge() { return 0; }
        @Override public BlockState growthState(int age) { return Blocks.AIR.defaultBlockState(); }
    };

    static void registerExtension(Item seed, CropAdapter adapter) {
        if (seed != null && seed != Items.AIR && adapter != null) {
            EXTENSIONS.put(seed, adapter);
        }
    }

    /** Lazy mod-compat init. Triggered on first {@link #resolve(Item)} call so we don't depend
     *  on mod load order — by the time the first crop ticks, all mods have registered items. */
    static void ensureModCompatInitialized() {
        if (EXTENSIONS_INITIALIZED.compareAndSet(false, true)) {
            // Compat failures must not crash the farm — but log them (this runs once ever), so a
            // broken integration shows up as "their crops won't grow + this warning" instead of
            // silently growing nothing.
            try { FarmersDelightCropAdapters.registerIfPresent(); }
            catch (Throwable t) {
                org.slf4j.LoggerFactory.getLogger("hydrofarm-compat")
                    .warn("Farmer's Delight crop-compat init failed; its crops won't grow in beds", t);
            }
            try { CroptopiaCropAdapters.registerIfPresent(); }
            catch (Throwable t) {
                org.slf4j.LoggerFactory.getLogger("hydrofarm-compat")
                    .warn("Croptopia crop-compat init failed; its crops won't grow in beds", t);
            }
        }
    }

    static CropAdapter resolve(Item seed) {
        if (seed == null || seed == Items.AIR) return null;
        CropAdapter cached = RESOLVED_CACHE.get(seed);
        if (cached != null) return cached == NONE ? null : cached;
        CropAdapter resolved = resolveUncached(seed);
        RESOLVED_CACHE.put(seed, resolved == null ? NONE : resolved);
        return resolved;
    }

    private static CropAdapter resolveUncached(Item seed) {
        if (seed == null || seed == Items.AIR) return null;
        ensureModCompatInitialized();
        CropAdapter ext = EXTENSIONS.get(seed);
        if (ext != null) return ext;
        if (seed == Items.MELON_SEEDS)        return STEM_MELON;
        if (seed == Items.PUMPKIN_SEEDS)      return STEM_PUMPKIN;
        if (seed == Items.NETHER_WART)        return NETHER_WART;
        if (seed == Items.SWEET_BERRIES)      return SWEET_BERRY;
        if (seed == Items.COCOA_BEANS)        return COCOA;
        if (seed == Items.CHORUS_FLOWER)      return CHORUS;
        if (seed == Items.BAMBOO)             return BAMBOO;
        if (seed == Items.SUGAR_CANE)         return SUGAR_CANE;
        if (seed == Items.CACTUS)             return CACTUS;
        // Hardcoded tree species with bonuses or non-standard naming
        if (seed == Items.OAK_SAPLING)        return OAK_TREE;
        if (seed == Items.MANGROVE_PROPAGULE) return MANGROVE_TREE;

        if (seed instanceof BlockItem bi) {
            // Generic sapling fallback — naming convention "[name]_sapling" → "[name]_log".
            // Covers birch/spruce/jungle/acacia/dark_oak/cherry/pale_oak + any modded sapling
            // that follows the standard pair. Saplings whose corresponding log doesn't exist
            // (e.g. Croptopia, which reuses vanilla logs) are silently rejected — players
            // farm those via the FRUIT in the hydroponics planter instead.
            if (bi.getBlock() instanceof SaplingBlock) {
                CropAdapter sap = buildGenericSaplingAdapter(seed);
                if (sap != null) return sap;
            }
            if (bi.getBlock() instanceof CropBlock cb) {
                return new CropBlockAdapter(cb);
            }
        }
        return null;
    }

    /** Variant of {@link #resolve(Item)} that returns null when the resolved adapter doesn't
     *  match the requested category. Bed types use this so e.g. the tree farm bed silently
     *  rejects wheat seeds without growing them. */
    static CropAdapter resolveForCategory(Item seed, Category category) {
        CropAdapter ca = resolve(seed);
        return (ca != null && ca.category() == category) ? ca : null;
    }

    final class CropBlockAdapter implements CropAdapter {
        private final CropBlock crop;
        private final Item product;
        CropBlockAdapter(CropBlock crop) { this.crop = crop; this.product = vanillaCropProduct(crop); }
        @Override public int maxAge() { return crop.getMaxAge(); }
        @Override public Item harvestItem() { return product; }
        @Override public BlockState growthState(int age) {
            return crop.getStateForAge(Math.min(Math.max(age, 0), crop.getMaxAge()));
        }
    }

    /** Maps the four vanilla {@link CropBlock}s to their product item so a normalized harvest can
     *  drop exactly one without a loot roll. Modded CropBlocks return AIR → loot-table fallback. */
    private static Item vanillaCropProduct(CropBlock crop) {
        if (crop == Blocks.WHEAT)     return Items.WHEAT;
        if (crop == Blocks.CARROTS)   return Items.CARROT;
        if (crop == Blocks.POTATOES)  return Items.POTATO;
        if (crop == Blocks.BEETROOTS) return Items.BEETROOT;
        return Items.AIR;
    }

    CropAdapter STEM_MELON = new CropAdapter() {
        @Override public int maxAge() { return StemBlock.MAX_AGE; }
        @Override public Item harvestItem() { return Items.MELON_SLICE; }
        @Override public int harvestCount() { return 4; }
        @Override public BlockState growthState(int age) {
            return Blocks.MELON_STEM.defaultBlockState().setValue(StemBlock.AGE, clamp(age, StemBlock.MAX_AGE));
        }
        @Override public BlockState harvestState() { return Blocks.MELON.defaultBlockState(); }
    };

    CropAdapter STEM_PUMPKIN = new CropAdapter() {
        @Override public int maxAge() { return StemBlock.MAX_AGE; }
        @Override public Item harvestItem() { return Items.PUMPKIN; }
        @Override public BlockState growthState(int age) {
            return Blocks.PUMPKIN_STEM.defaultBlockState().setValue(StemBlock.AGE, clamp(age, StemBlock.MAX_AGE));
        }
        @Override public BlockState harvestState() { return Blocks.PUMPKIN.defaultBlockState(); }
    };

    CropAdapter NETHER_WART = new CropAdapter() {
        @Override public int maxAge() { return 3; }
        @Override public Item harvestItem() { return Items.NETHER_WART; }
        @Override public BlockState growthState(int age) {
            return Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, clamp(age, 3));
        }
    };

    CropAdapter SWEET_BERRY = new CropAdapter() {
        @Override public int maxAge() { return 3; }
        @Override public Item harvestItem() { return Items.SWEET_BERRIES; }
        @Override public BlockState growthState(int age) {
            return Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, clamp(age, 3));
        }
    };

    CropAdapter COCOA = new CropAdapter() {
        @Override public int maxAge() { return 2; }
        @Override public Item harvestItem() { return Items.COCOA_BEANS; }
        @Override public BlockState growthState(int age) {
            return Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, clamp(age, 2));
        }
    };

    /** Chorus is a simplification: vanilla grows chorus flower vertically into chorus_plant
     *  columns. Here we treat each cycle as one chorus_flower grow 0→5, then harvest produces
     *  chorus_fruit by sampling the chorus_plant loot table (which is the block that actually
     *  drops the fruit when broken). Drops 0-1 chorus_fruit per cycle (uniform), matching
     *  vanilla. */
    /** Bamboo abstraction: rendered as the static sapling block in the planter (we don't fake a
     *  tall stalk in the tight voxel). Like every crop it yields 1 bamboo per normalized harvest
     *  via {@link #harvestItem()}. */
    int BAMBOO_MAX_AGE = 6;
    CropAdapter BAMBOO = new CropAdapter() {
        @Override public int maxAge() { return BAMBOO_MAX_AGE; }
        @Override public Item harvestItem() { return Items.BAMBOO; }
        @Override public BlockState growthState(int age) {
            return Blocks.BAMBOO_SAPLING.defaultBlockState();
        }
    };

    /** Sugarcane: tall-plant abstraction (same shape as bamboo), rendered as a static cane block. */
    CropAdapter SUGAR_CANE = new CropAdapter() {
        @Override public int maxAge() { return BAMBOO_MAX_AGE; }
        @Override public Item harvestItem() { return Items.SUGAR_CANE; }
        @Override public BlockState growthState(int age) {
            return Blocks.SUGAR_CANE.defaultBlockState();
        }
    };

    /** Cactus: same abstraction. The hydroponics planter ignores the no-water-needed quirk —
     *  abstracting the cactus to "needs water" keeps the rate maths consistent across plants. */
    CropAdapter CACTUS = new CropAdapter() {
        @Override public int maxAge() { return BAMBOO_MAX_AGE; }
        @Override public Item harvestItem() { return Items.CACTUS; }
        @Override public BlockState growthState(int age) {
            return Blocks.CACTUS.defaultBlockState();
        }
    };

    int CHORUS_MAX_AGE = 5;
    CropAdapter CHORUS = new CropAdapter() {
        @Override public int maxAge() { return CHORUS_MAX_AGE; }
        @Override public Item harvestItem() { return Items.CHORUS_FRUIT; }
        @Override public BlockState growthState(int age) {
            return Blocks.CHORUS_FLOWER.defaultBlockState().setValue(ChorusFlowerBlock.AGE, clamp(age, CHORUS_MAX_AGE));
        }
        @Override public BlockState harvestState() { return Blocks.CHORUS_PLANT.defaultBlockState(); }
    };

    private static int clamp(int age, int max) {
        return Math.min(Math.max(age, 0), max);
    }

    // ---- Tree adapters --------------------------------------------------------------------
    // Tree-farm beds run the SAME normalized 11-window cadence as crops (Category.TREE keeps the
    // produceDrops path rather than the crop 1-item harvestItem) but yield BULK wood: 2 logs + 1
    // sapling per cycle (~16 logs/hr/quad — a middle ground, well above the ~8/hr crop rate). The
    // bed keeps only ~1/SEED_KEEP_DENOM of the sapling as a spare (the planter self-replants), so
    // saplings are a low expand-the-farm trickle; oak adds a 50% chance of an apple as its
    // species perk. growthState always returns the sapling block — we don't fake a tall stalk.

    int TREE_MAX_AGE = 10;
    int TREE_LOGS_PER_CYCLE = 2;

    private static List<ItemStack> standardTreeDrops(Item log, Item sapling, ItemStack bonus) {
        if (bonus.isEmpty()) {
            return List.of(new ItemStack(log, TREE_LOGS_PER_CYCLE), new ItemStack(sapling, 1));
        }
        return List.of(new ItemStack(log, TREE_LOGS_PER_CYCLE), new ItemStack(sapling, 1), bonus);
    }

    /** Oak — hardcoded because it gets the apple bonus other species don't. */
    CropAdapter OAK_TREE = new CropAdapter() {
        @Override public Category category() { return Category.TREE; }
        @Override public int maxAge() { return TREE_MAX_AGE; }
        @Override public BlockState growthState(int age) { return Blocks.OAK_SAPLING.defaultBlockState(); }
        @Override public List<ItemStack> produceDrops(ServerLevel level, BlockPos pos) {
            ItemStack apple = level.getRandom().nextFloat() < 0.5f
                ? new ItemStack(Items.APPLE, 1) : ItemStack.EMPTY;
            return standardTreeDrops(Items.OAK_LOG, Items.OAK_SAPLING, apple);
        }
    };

    /** Mangrove — hardcoded because the item is "_propagule" not "_sapling", breaking the
     *  generic naming-convention fallback. */
    CropAdapter MANGROVE_TREE = new CropAdapter() {
        @Override public Category category() { return Category.TREE; }
        @Override public int maxAge() { return TREE_MAX_AGE; }
        @Override public BlockState growthState(int age) { return Blocks.MANGROVE_PROPAGULE.defaultBlockState(); }
        @Override public List<ItemStack> produceDrops(ServerLevel level, BlockPos pos) {
            return standardTreeDrops(Items.MANGROVE_LOG, Items.MANGROVE_PROPAGULE, ItemStack.EMPTY);
        }
    };

    /** Generic sapling adapter: matches any item whose path ends in "_sapling" and has a
     *  corresponding "_log" item in the same namespace. Covers all vanilla species except
     *  oak (apple bonus) and mangrove (propagule) which are hardcoded above, plus any modded
     *  sapling that follows the convention. Returns null when no matching log exists. */
    private static CropAdapter buildGenericSaplingAdapter(Item seed) {
        Identifier seedId = BuiltInRegistries.ITEM.getKey(seed);
        if (seedId == null) return null;
        String path = seedId.getPath();
        if (!path.endsWith("_sapling")) return null;
        String logPath = path.substring(0, path.length() - "_sapling".length()) + "_log";
        Identifier logId = Identifier.fromNamespaceAndPath(seedId.getNamespace(), logPath);
        if (!BuiltInRegistries.ITEM.containsKey(logId)) return null;
        Item logItem = BuiltInRegistries.ITEM.getValue(logId);
        // Capture the sapling block for rendering.
        if (!(seed instanceof BlockItem bi)) return null;
        BlockState saplingState = bi.getBlock().defaultBlockState();
        return new CropAdapter() {
            @Override public Category category() { return Category.TREE; }
            @Override public int maxAge() { return TREE_MAX_AGE; }
            @Override public BlockState growthState(int age) { return saplingState; }
            @Override public List<ItemStack> produceDrops(ServerLevel level, BlockPos pos) {
                return standardTreeDrops(logItem, seed, ItemStack.EMPTY);
            }
        };
    }
}
