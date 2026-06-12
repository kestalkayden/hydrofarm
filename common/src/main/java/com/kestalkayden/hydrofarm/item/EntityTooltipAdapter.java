package com.kestalkayden.hydrofarm.item;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/** Pulls the variant-defining piece of an entity's NBT (sheep color, cat coat, etc.) into
 *  a user-facing label. Registered by entity Identifier in {@link EntityTooltipAdapters}.
 *  Unknown entity types are tolerated — they fall back to no variant prefix. */
@FunctionalInterface
public interface EntityTooltipAdapter {
    /** @return the variant label to prefix the entity name, or {@code null} if not applicable. */
    @Nullable Component variant(CompoundTag nbt);
}
