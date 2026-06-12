package com.kestalkayden.hydrofarm.block;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

/** Marker interface for any bed-type BE that hosts captured animals in stalls. Lets the
 *  shared {@link com.kestalkayden.hydrofarm.client.AnimalBedRenderer} read stall state
 *  without coupling to a concrete subclass. Implemented by HusbandryBedBlockEntity and
 *  ButcherBedBlockEntity — same stall semantics, different cycle behavior on the BE side. */
public interface AnimalBedHost {

    /** Total number of stalls on this bed. Same value for husbandry and butcher (= 2 in v0.6). */
    int stallCount();

    /** True if {@code stall} currently holds a captured animal. */
    boolean hasHost(int stall);

    /** Captured animal's entity-type identifier (e.g. {@code minecraft:sheep}), or null if empty. */
    Identifier getHostType(int stall);

    /** Captured animal's full NBT snapshot, or null if the stall is empty. */
    CompoundTag getHostNbt(int stall);
}
