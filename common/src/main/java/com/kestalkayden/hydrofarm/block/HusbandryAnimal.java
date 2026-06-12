package com.kestalkayden.hydrofarm.block;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Per-species config for the Husbandry Bed. Each entry binds an entity type to its
 *  food predicate, cycle duration, and drop producer. Sheep wool color tracks the
 *  captured sheep's NBT-stored Color byte.
 *
 *  <p>Conservative defaults — Husbandry is a quality-of-life convenience, not a faster
 *  alternative to vanilla wool/egg farming. */
public final class HusbandryAnimal {

    public static final int TICKS_PER_SECOND = 20;

    /** entityType → config. */
    public static final Map<Identifier, HusbandryAnimal> ANIMALS = new HashMap<>();

    public final Identifier entityType;
    public final Predicate<Item> foodAcceptor;
    /** Display name for the primary food, used in "out of <food>" messages. */
    public final Item primaryFood;
    public final int cycleTicks;
    public final BiFunction<CompoundTag, RandomSource, List<ItemStack>> dropProducer;
    /** mB of the bed's output fluid (currently {@code fluid_milk}) emitted per harvest cycle.
     *  0 for species that produce only items (sheep, chicken). Intentionally low (cow = 200 mB
     *  per 120s = 1 bucket per ~10 min) — vanilla cows hand-milk instantly, so automated
     *  milking trades patience for hands-off operation. */
    public final int fluidProducedMb;
    /** mB of water drawn from the cluster pool per production cycle. Animals drink too: no water,
     *  no production (gated exactly like food). Tunable per species. */
    public final int waterPerCycle;

    private HusbandryAnimal(Identifier entityType, Predicate<Item> foodAcceptor, Item primaryFood,
                             int cycleTicks,
                             BiFunction<CompoundTag, RandomSource, List<ItemStack>> dropProducer,
                             int fluidProducedMb, int waterPerCycle) {
        this.entityType = entityType;
        this.foodAcceptor = foodAcceptor;
        this.primaryFood = primaryFood;
        this.cycleTicks = cycleTicks;
        this.dropProducer = dropProducer;
        this.fluidProducedMb = fluidProducedMb;
        this.waterPerCycle = waterPerCycle;
    }

    public static HusbandryAnimal forEntity(Identifier id) {
        return ANIMALS.get(id);
    }

    public static boolean isSupported(Identifier id) {
        return ANIMALS.containsKey(id);
    }

    /** True if {@code item} is feed for any supported species. Used to lock food into the bed so
     *  pumps/hoppers can't pull it back out. */
    public static boolean isFood(Item item) {
        for (HusbandryAnimal a : ANIMALS.values()) {
            if (a.foodAcceptor.test(item)) return true;
        }
        return false;
    }

    private static void register(HusbandryAnimal a) {
        ANIMALS.put(a.entityType, a);
    }

    private static Identifier mc(String path) {
        return Identifier.fromNamespaceAndPath("minecraft", path);
    }

    /** Sheep wool color matches the captured sheep's NBT-stored Color byte (0–15 = DyeColor
     *  index). Falls back to white if the tag is missing — keeps un-tinted sheep working. */
    private static Item woolForSheep(CompoundTag nbt) {
        if (nbt == null) return Items.WHITE_WOOL;
        try {
            // MC 26.1 — entity NBT stores "Color" as a byte. The exact accessor varies, so
            // try the standard CompoundTag.getByte path. Other code paths might fail; the
            // try-catch keeps unknown sheep producing white wool rather than crashing.
            if (!nbt.contains("Color")) return Items.WHITE_WOOL;
            byte b = nbt.getByteOr("Color", (byte) 0);
            DyeColor color = DyeColor.byId(b & 0xFF);
            return woolForColor(color);
        } catch (Throwable t) {
            return Items.WHITE_WOOL;
        }
    }

    private static Item woolForColor(DyeColor color) {
        if (color == null) return Items.WHITE_WOOL;
        return switch (color) {
            case WHITE      -> Items.WHITE_WOOL;
            case ORANGE     -> Items.ORANGE_WOOL;
            case MAGENTA    -> Items.MAGENTA_WOOL;
            case LIGHT_BLUE -> Items.LIGHT_BLUE_WOOL;
            case YELLOW     -> Items.YELLOW_WOOL;
            case LIME       -> Items.LIME_WOOL;
            case PINK       -> Items.PINK_WOOL;
            case GRAY       -> Items.GRAY_WOOL;
            case LIGHT_GRAY -> Items.LIGHT_GRAY_WOOL;
            case CYAN       -> Items.CYAN_WOOL;
            case PURPLE     -> Items.PURPLE_WOOL;
            case BLUE       -> Items.BLUE_WOOL;
            case BROWN      -> Items.BROWN_WOOL;
            case GREEN      -> Items.GREEN_WOOL;
            case RED        -> Items.RED_WOOL;
            case BLACK      -> Items.BLACK_WOOL;
        };
    }

    private static boolean isAnySeed(Item item) {
        return item == Items.WHEAT_SEEDS
            || item == Items.MELON_SEEDS
            || item == Items.PUMPKIN_SEEDS
            || item == Items.BEETROOT_SEEDS;
    }

    static {
        register(new HusbandryAnimal(
            mc("sheep"),
            item -> item == Items.WHEAT,
            Items.WHEAT,
            240 * TICKS_PER_SECOND,
            (nbt, rng) -> List.of(new ItemStack(woolForSheep(nbt))),
            0, 100));

        register(new HusbandryAnimal(
            mc("chicken"),
            HusbandryAnimal::isAnySeed,
            Items.WHEAT_SEEDS,
            120 * TICKS_PER_SECOND,
            (nbt, rng) -> List.of(new ItemStack(Items.EGG)),
            0, 60));

        // Cow produces milk and nothing else — vanilla cows have no renewable item drop. Milk
        // lands in the bed's output-fluid slot (fluid_milk) and is pulled out via liquid pumps;
        // the tank itself supports bucket-extraction by right-click. Same 120s cycle + wheat
        // food as sheep for tunability symmetry. Rate is intentionally slow (200 mB / cycle =
        // 1 bucket per ~10 min per cow) — vanilla cows are instant-milk on right-click, so the
        // automation premium here is "hands-off" rather than "faster than vanilla".
        register(new HusbandryAnimal(
            mc("cow"),
            item -> item == Items.WHEAT,
            Items.WHEAT,
            240 * TICKS_PER_SECOND,
            (nbt, rng) -> List.of(),
            200, 200));

        // Bees pollinate flowers in the wild; here a captured colony turns flowers (or bone meal)
        // into honeycomb. 240s cycle = 1 honeycomb every 4 min — convenience, deliberately at or
        // below a vanilla dispenser-hive rate so piping them never trivializes copper waxing.
        // Water-gated like the others (bees drink too). Honeycomb feeds the Glowcube light line.
        register(new HusbandryAnimal(
            mc("bee"),
            item -> item == Items.BONE_MEAL || item.builtInRegistryHolder().is(ItemTags.FLOWERS),
            Items.BONE_MEAL,
            240 * TICKS_PER_SECOND,
            (nbt, rng) -> List.of(new ItemStack(Items.HONEYCOMB)),
            0, 50));
    }
}
