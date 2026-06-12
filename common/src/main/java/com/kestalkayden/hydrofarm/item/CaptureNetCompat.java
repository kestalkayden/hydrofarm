package com.kestalkayden.hydrofarm.item;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Cross-mod compat for the Capture Net spinoff (mod ID "capturenet").
 *
 *  When the standalone mod is installed alongside Hydrofarm, it registers its own
 *  {@code capturenet:captured_entity} DataComponent and its own item. Both have structurally
 *  identical record types and codecs — just different Java classes. This helper bridges the
 *  two so Hydrofarm's beds can accept either net:
 *
 *  - Item identity check is replaced by the shared {@code #capturenet:nets} item tag.
 *  - Component reads try Hydrofarm's component first, then fall back to the foreign one
 *    via a codec round-trip through NBT (encode under the foreign codec, decode under ours).
 *  - On write, we preserve whichever component the stack already used so a player's
 *    capturenet net stays a capturenet net.
 *
 *  Soft lookup: when the capturenet mod isn't loaded, {@link #foreignType()} returns null
 *  permanently and the helper falls back to Hydrofarm-only behavior with zero overhead. */
public final class CaptureNetCompat {

    public static final TagKey<Item> NETS_TAG = TagKey.create(Registries.ITEM,
        Identifier.fromNamespaceAndPath("capturenet", "nets"));

    /** Entity types the net must NEVER capture, even if passive — for modpack blocklists of modded
     *  "bosses" or special encounters. Shared {@code capturenet:} tag so a single datapack governs
     *  both Hydrofarm's net and the standalone mod's. Takes precedence over {@link #ALWAYS_CAPTURABLE};
     *  resolves empty (no-op) when no datapack contributes to it. */
    public static final TagKey<EntityType<?>> CANNOT_CAPTURE = TagKey.create(Registries.ENTITY_TYPE,
        Identifier.fromNamespaceAndPath("capturenet", "cannot_capture"));

    /** Entity types that ARE capturable even when their MobCategory is MONSTER — for whitelisting a
     *  friendly companion mob a modder labeled hostile. Shared {@code capturenet:} tag. */
    public static final TagKey<EntityType<?>> ALWAYS_CAPTURABLE = TagKey.create(Registries.ENTITY_TYPE,
        Identifier.fromNamespaceAndPath("capturenet", "always_capturable"));

    private static final Identifier FOREIGN_ID =
        Identifier.fromNamespaceAndPath("capturenet", "captured_entity");

    // Lazy + cached. After the first lookup, foreignTypeChecked stays true forever (mod set
    // doesn't change at runtime) and we return the cached value directly.
    private static volatile DataComponentType<?> foreignTypeCache;
    private static volatile boolean foreignTypeChecked;

    private CaptureNetCompat() {}

    /** True if the stack is in the cross-mod {@code #capturenet:nets} tag. Works whether
     *  or not the capturenet mod is loaded — Hydrofarm contributes its own net to the tag. */
    public static boolean isCaptureNet(ItemStack stack) {
        return stack.is(NETS_TAG);
    }

    /** Read the captured entity from either Hydrofarm's or the standalone's component.
     *  @return the captured entity, or null if the stack has neither component set. */
    @Nullable
    public static CapturedEntity read(ItemStack stack) {
        CapturedEntity own = stack.get(com.kestalkayden.hydrofarm.HydrofarmRefs.CAPTURED_ENTITY.get());
        if (own != null) return own;
        DataComponentType<?> foreign = foreignType();
        if (foreign == null) return null;
        Object foreignValue = stack.get(foreign);
        if (foreignValue == null) return null;
        return convertFromForeign(foreignValue, foreign);
    }

    /** Set the captured entity on the stack. If the stack already holds a foreign component,
     *  the new value goes there; otherwise we use Hydrofarm's component. Keeps a capturenet
     *  net behaving as a capturenet net even after a round-trip through a Hydrofarm bed.
     *  Also sets the glint override so the net shimmers — matches the in-item behavior
     *  whether the bed or the item itself filled the net. */
    public static void set(ItemStack stack, CapturedEntity captured) {
        DataComponentType<?> foreign = foreignType();
        if (foreign != null && stack.has(foreign)) {
            Object asForeign = convertToForeign(captured, foreign);
            if (asForeign != null) {
                writeForeign(stack, foreign, asForeign);
                stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                return;
            }
            // Conversion failed for some reason — fall through to Hydrofarm component so we
            // don't lose the captured entity entirely.
        }
        stack.set(com.kestalkayden.hydrofarm.HydrofarmRefs.CAPTURED_ENTITY.get(), captured);
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
    }

    /** Clear the captured entity (both possible components) and the glint override.
     *  Safe on already-empty stacks. */
    public static void clear(ItemStack stack) {
        stack.remove(com.kestalkayden.hydrofarm.HydrofarmRefs.CAPTURED_ENTITY.get());
        DataComponentType<?> foreign = foreignType();
        if (foreign != null) stack.remove(foreign);
        stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
    }

    @Nullable
    private static DataComponentType<?> foreignType() {
        if (foreignTypeChecked) return foreignTypeCache;
        DataComponentType<?> t = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(FOREIGN_ID);
        foreignTypeCache = t;
        foreignTypeChecked = true;
        return t;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static CapturedEntity convertFromForeign(Object value, DataComponentType<?> foreign) {
        try {
            Codec<Object> foreignCodec = (Codec<Object>) foreign.codec();
            if (foreignCodec == null) return null;
            Tag encoded = foreignCodec.encodeStart(NbtOps.INSTANCE, value).result().orElse(null);
            if (encoded == null) return null;
            return CapturedEntity.CODEC.parse(NbtOps.INSTANCE, encoded).result().orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static Object convertToForeign(CapturedEntity value, DataComponentType<?> foreign) {
        try {
            Codec<Object> foreignCodec = (Codec<Object>) foreign.codec();
            if (foreignCodec == null) return null;
            Tag encoded = CapturedEntity.CODEC.encodeStart(NbtOps.INSTANCE, value).result().orElse(null);
            if (encoded == null) return null;
            return foreignCodec.parse(NbtOps.INSTANCE, encoded).result().orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void writeForeign(ItemStack stack, DataComponentType<?> foreign, Object value) {
        stack.set((DataComponentType) foreign, value);
    }
}
