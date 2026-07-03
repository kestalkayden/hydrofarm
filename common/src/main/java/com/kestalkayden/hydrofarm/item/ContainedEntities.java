package com.kestalkayden.hydrofarm.item;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** The ordered set of entities held in a Capture Crate, plus which one is currently selected for
 *  release. Stored as a single data component on the crate's ItemStack; absence of the component
 *  means the crate is empty (mirrors the single-net {@code captured_entity} convention, so the
 *  filled/empty item model swap via {@code has_component} keeps working).
 *
 *  <p>Immutable — every mutation returns a fresh instance so the item code can {@code stack.set(..)}
 *  it back like any other component value. */
public record ContainedEntities(List<ContainedEntity> entities, int selected) {

    /** Hard cap on how many entities one crate holds. Also the segment count for the capacity bar. */
    public static final int MAX_CAPACITY = 10;

    /** The contents of an empty crate (no component present resolves to this). */
    public static final ContainedEntities EMPTY = new ContainedEntities(List.of(), 0);

    public static final Codec<ContainedEntities> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            ContainedEntity.CODEC.listOf().fieldOf("entities").forGetter(ContainedEntities::entities),
            Codec.INT.optionalFieldOf("selected", 0).forGetter(ContainedEntities::selected)
        ).apply(instance, ContainedEntities::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ContainedEntities> STREAM_CODEC =
        StreamCodec.composite(
            ContainedEntity.STREAM_CODEC.apply(ByteBufCodecs.list()), ContainedEntities::entities,
            ByteBufCodecs.VAR_INT, ContainedEntities::selected,
            ContainedEntities::new);

    public boolean isEmpty() { return entities.isEmpty(); }

    public boolean isFull() { return entities.size() >= MAX_CAPACITY; }

    public int size() { return entities.size(); }

    /** {@code selected} as a valid index into a non-empty list, tolerating stale/out-of-range
     *  values (e.g. after entities were removed). Returns 0 for an empty crate. */
    public int clampedSelected() {
        return entities.isEmpty() ? 0 : Math.floorMod(selected, entities.size());
    }

    /** The entity that a release action would spawn. Caller must ensure the crate is non-empty. */
    public ContainedEntity selectedEntity() {
        return entities.get(clampedSelected());
    }

    /** A copy with {@code entity} appended (selection unchanged). Caller checks {@link #isFull()}. */
    public ContainedEntities withAdded(ContainedEntity entity) {
        List<ContainedEntity> next = new ArrayList<>(entities);
        next.add(entity);
        return new ContainedEntities(next, selected);
    }

    /** A copy with the selection advanced to the next entity (wraps around). */
    public ContainedEntities cycled() {
        if (entities.isEmpty()) return this;
        return new ContainedEntities(entities, Math.floorMod(selected + 1, entities.size()));
    }

    /** A copy with the currently selected entity removed and the selection kept sensible
     *  (stays at the same slot index, or the new last slot if it was the tail). */
    public ContainedEntities withSelectedReleased() {
        if (entities.isEmpty()) return this;
        int idx = clampedSelected();
        List<ContainedEntity> next = new ArrayList<>(entities);
        next.remove(idx);
        int nextSelected = next.isEmpty() ? 0 : Math.floorMod(idx, next.size());
        return new ContainedEntities(next, nextSelected);
    }
}
