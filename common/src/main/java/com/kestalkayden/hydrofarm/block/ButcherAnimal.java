package com.kestalkayden.hydrofarm.block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Per-species config for the Butcher Bed. Each entry binds an entity type to its food
 *  predicate, cull cycle duration, and death-loot producer. Drops are intentionally
 *  conservative — Butcher Bed is a steady-state meat farm, not a meat-grinder.
 *
 *  <p>Hardcoded drop ranges (not vanilla loot tables) for simplicity in v1 — the loot
 *  table path would require LootContext setup that doesn't add user-visible value at
 *  this scope. Polish pass can swap in real loot table calls if needed. */
public final class ButcherAnimal {

    public static final int TICKS_PER_SECOND = 20;

    public static final Map<Identifier, ButcherAnimal> ANIMALS = new HashMap<>();

    public final Identifier entityType;
    public final Predicate<Item> foodAcceptor;
    public final Item primaryFood;
    public final int cycleTicks;
    public final BiFunction<CompoundTag, RandomSource, List<ItemStack>> dropProducer;
    /** Liquid XP (mB) deposited into the cluster pool on each cull. The XP drain converts at
     *  20 mB per XP point, so these (12-30 mB) put a cull at ~0.6-1.5 XP — roughly a vanilla
     *  animal kill. The XP slot is drained by liquid pumps into XP tanks. */
    public final int xpProducedMb;
    /** mB of water drawn from the cluster pool per cull cycle. Animals drink too: no water, no
     *  maturation (gated exactly like food). Tunable per species. */
    public final int waterPerCycle;

    private ButcherAnimal(Identifier entityType, Predicate<Item> foodAcceptor, Item primaryFood,
                           int cycleTicks,
                           BiFunction<CompoundTag, RandomSource, List<ItemStack>> dropProducer,
                           int xpProducedMb, int waterPerCycle) {
        this.entityType = entityType;
        this.foodAcceptor = foodAcceptor;
        this.primaryFood = primaryFood;
        this.cycleTicks = cycleTicks;
        this.dropProducer = dropProducer;
        this.xpProducedMb = xpProducedMb;
        this.waterPerCycle = waterPerCycle;
    }

    public static ButcherAnimal forEntity(Identifier id) {
        return ANIMALS.get(id);
    }

    public static boolean isSupported(Identifier id) {
        return ANIMALS.containsKey(id);
    }

    /** True if {@code item} is feed for any supported species. Used to lock food into the bed so
     *  pumps/hoppers can't pull it back out. */
    public static boolean isFood(Item item) {
        for (ButcherAnimal a : ANIMALS.values()) {
            if (a.foodAcceptor.test(item)) return true;
        }
        return false;
    }

    private static void register(ButcherAnimal a) {
        ANIMALS.put(a.entityType, a);
    }

    private static Identifier mc(String path) {
        return Identifier.fromNamespaceAndPath("minecraft", path);
    }

    private static boolean isAnySeed(Item item) {
        return item == Items.WHEAT_SEEDS
            || item == Items.MELON_SEEDS
            || item == Items.PUMPKIN_SEEDS
            || item == Items.BEETROOT_SEEDS;
    }

    private static boolean isPigFood(Item item) {
        return item == Items.CARROT
            || item == Items.POTATO
            || item == Items.BEETROOT;
    }

    /** Random integer in [min, max] inclusive. */
    private static int rand(RandomSource rng, int min, int max) {
        return min + rng.nextInt(max - min + 1);
    }

    private static List<ItemStack> stacks(int count, Item item) {
        if (count <= 0) return List.of();
        return List.of(new ItemStack(item, count));
    }

    private static List<ItemStack> stacks(int a, Item itemA, int b, Item itemB) {
        List<ItemStack> out = new ArrayList<>(2);
        if (a > 0) out.add(new ItemStack(itemA, a));
        if (b > 0) out.add(new ItemStack(itemB, b));
        return out;
    }

    static {
        // Sheep cull: 16 min, 1-2 mutton + 1 wool + 24 mB XP. Wool is always white here (the
        // host NBT would carry color, but for v1 simplicity we drop white — the husbandry bed
        // handles wool-color farming).
        register(new ButcherAnimal(
            mc("sheep"),
            item -> item == Items.WHEAT,
            Items.WHEAT,
            16 * 60 * TICKS_PER_SECOND,
            (nbt, rng) -> stacks(rand(rng, 1, 2), Items.MUTTON, 1, Items.WOOL.white()),
            24, 150));

        // Chicken cull: 10 min, 1 chicken meat + 1 feather + 12 mB XP (smallest yield, matches
        // vanilla 1 XP for small animals).
        register(new ButcherAnimal(
            mc("chicken"),
            ButcherAnimal::isAnySeed,
            Items.WHEAT_SEEDS,
            10 * 60 * TICKS_PER_SECOND,
            (nbt, rng) -> stacks(1, Items.CHICKEN, 1, Items.FEATHER),
            12, 80));

        // Pig cull: 12 min, 1-3 porkchop + 20 mB XP.
        register(new ButcherAnimal(
            mc("pig"),
            ButcherAnimal::isPigFood,
            Items.CARROT,
            12 * 60 * TICKS_PER_SECOND,
            (nbt, rng) -> stacks(rand(rng, 1, 3), Items.PORKCHOP),
            20, 120));

        // Cow cull: 16 min, 1-3 beef + 0-2 leather + 30 mB XP (largest yield, matches vanilla
        // 3 XP for big animals).
        register(new ButcherAnimal(
            mc("cow"),
            item -> item == Items.WHEAT,
            Items.WHEAT,
            16 * 60 * TICKS_PER_SECOND,
            (nbt, rng) -> stacks(rand(rng, 1, 3), Items.BEEF, rand(rng, 0, 2), Items.LEATHER),
            30, 200));
    }
}
