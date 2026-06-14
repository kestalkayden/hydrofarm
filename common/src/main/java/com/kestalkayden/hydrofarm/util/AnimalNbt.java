package com.kestalkayden.hydrofarm.util;

import net.minecraft.nbt.CompoundTag;

/** Shared helpers for the captured-animal NBT the husbandry/butcher beds, the capture net, and the
 *  pen renderer all read. Vanilla's {@code AgeableMob} stores age as an int that is negative for a
 *  baby and counts up toward 0 (adult); these center that convention in one place. */
public final class AnimalNbt {

    private AnimalNbt() {}

    /** True if {@code nbt} represents a baby (Age present and &lt; 0). Null-safe. */
    public static boolean isBaby(CompoundTag nbt) {
        return nbt != null && nbt.contains("Age") && nbt.getIntOr("Age", 0) < 0;
    }

    /** Age a host's NBT up by {@code delta}, capped at 0 (adult). Returns true iff this call is the
     *  one that crosses from baby to adult (Age reaches 0). No-op returning false for null, age-less,
     *  or already-adult NBT. */
    public static boolean ageBy(CompoundTag nbt, int delta) {
        if (nbt == null || !nbt.contains("Age")) return false;
        int oldAge = nbt.getIntOr("Age", 0);
        if (oldAge >= 0) return false;
        int newAge = Math.min(0, oldAge + delta);
        nbt.putInt("Age", newAge);
        return newAge >= 0;
    }
}
