package com.kestalkayden.hydrofarm.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/** State of one entity stored inside an Animal Capture Net.
 *  - {@code entityType}: the Identifier of the captured entity type (e.g. "minecraft:sheep").
 *  - {@code entityNbt}: the entity's full save NBT (includes the type id), preserving
 *    color/name/age/tame state and any modded NBT extensions transparently.
 *
 *  Stored on the net's ItemStack as a DataComponent. Absence of the component means the net
 *  is empty. */
public record CapturedEntity(Identifier entityType, CompoundTag entityNbt) {

    public static final Codec<CapturedEntity> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Identifier.CODEC.fieldOf("type").forGetter(CapturedEntity::entityType),
            CompoundTag.CODEC.fieldOf("nbt").forGetter(CapturedEntity::entityNbt)
        ).apply(instance, CapturedEntity::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, CapturedEntity> STREAM_CODEC =
        StreamCodec.composite(
            Identifier.STREAM_CODEC, CapturedEntity::entityType,
            ByteBufCodecs.COMPOUND_TAG, CapturedEntity::entityNbt,
            CapturedEntity::new);
}
