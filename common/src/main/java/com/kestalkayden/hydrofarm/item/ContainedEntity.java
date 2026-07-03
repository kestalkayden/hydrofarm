package com.kestalkayden.hydrofarm.item;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/** One entity stored inside a Capture Crate.
 *
 *  <ul>
 *    <li>{@code nbt} — the entity's full save NBT (trades, inventory, attributes), preserved
 *        verbatim. Release reads it back via {@code loadEntityRecursive} on the server.</li>
 *    <li>{@code name}/{@code variant}/{@code baby} — display bits precomputed at capture time so
 *        the tooltip doesn't have to re-parse the NBT each frame.</li>
 *  </ul>
 *  Both the persistent and network forms carry the full data — see {@link #STREAM_CODEC} for why a
 *  lossy network form is unsafe for an item that lives in inventories. */
public record ContainedEntity(Identifier type, CompoundTag nbt,
                              Optional<Component> name, Optional<Component> variant, boolean baby) {

    /** Persistent (disk / server) form — round-trips every field, including the heavy {@code nbt}. */
    public static final Codec<ContainedEntity> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Identifier.CODEC.fieldOf("type").forGetter(ContainedEntity::type),
            CompoundTag.CODEC.fieldOf("nbt").forGetter(ContainedEntity::nbt),
            ComponentSerialization.CODEC.optionalFieldOf("name").forGetter(ContainedEntity::name),
            ComponentSerialization.CODEC.optionalFieldOf("variant").forGetter(ContainedEntity::variant),
            Codec.BOOL.optionalFieldOf("baby", false).forGetter(ContainedEntity::baby)
        ).apply(instance, ContainedEntity::new));

    /** Network (client) form — fully symmetric with {@link #CODEC}, INCLUDING {@code nbt}.
     *
     *  <p>It MUST be symmetric. A data component is part of an ItemStack's identity, and Minecraft
     *  validates container interactions by comparing the client's view of a stack against the
     *  server's. A lossy network form (NBT dropped) makes those two stacks unequal, desyncing
     *  inventory handling — observed as the stack duplicating on pickup. So we sync the whole tag,
     *  exactly like vanilla containers (bundles, shulker boxes). The precomputed name/variant/baby
     *  ride along so the tooltip still avoids re-parsing NBT. */
    public static final StreamCodec<RegistryFriendlyByteBuf, ContainedEntity> STREAM_CODEC =
        StreamCodec.composite(
            Identifier.STREAM_CODEC, ContainedEntity::type,
            ByteBufCodecs.COMPOUND_TAG, ContainedEntity::nbt,
            ByteBufCodecs.optional(ComponentSerialization.STREAM_CODEC), ContainedEntity::name,
            ByteBufCodecs.optional(ComponentSerialization.STREAM_CODEC), ContainedEntity::variant,
            ByteBufCodecs.BOOL, ContainedEntity::baby,
            ContainedEntity::new);
}
