package com.kestalkayden.hydrofarm.item;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;

/** Registry of per-entity variant readers. Vanilla NBT field names vary by entity:
 *  - Sheep saves "Color" as a byte (0–15 DyeColor index)
 *  - Mooshroom/Fox save "Type" as a string ("red"/"brown" or "red"/"snow")
 *  - Axolotl/Parrot save "Variant" as an int
 *  - Cat/Wolf/Frog save "variant" as a ResourceLocation string ("minecraft:tabby" etc.)
 *
 *  Each adapter is responsible for tolerating missing or malformed NBT — vanilla mobs that
 *  weren't paired with a default value during world creation may not have the field at all.
 *
 *  Mirrors the equivalent class in the standalone Capture Net mod (lang keys are duplicated
 *  there too). When both mods are installed the standalone's net takes precedence for new
 *  captures, but Hydrofarm's net still uses these adapters when it's the only one loaded. */
public final class EntityTooltipAdapters {

    private static final Map<Identifier, EntityTooltipAdapter> ADAPTERS = new HashMap<>();

    static {
        register("sheep", nbt -> {
            if (!nbt.contains("Color")) return null;
            byte b = nbt.getByteOr("Color", (byte) 0);
            DyeColor color = DyeColor.byId(b & 0xFF);
            if (color == null) return null;
            return Component.translatable("color.minecraft." + color.getName());
        });

        register("wolf", nbt -> namespacedVariant(nbt, "wolf"));
        register("cat", nbt -> namespacedVariant(nbt, "cat"));
        register("frog", nbt -> namespacedVariant(nbt, "frog"));
        register("mooshroom", nbt -> stringTypeVariant(nbt, "mooshroom"));
        register("fox", nbt -> stringTypeVariant(nbt, "fox"));

        register("axolotl", nbt -> {
            if (!nbt.contains("Variant")) return null;
            int v = nbt.getIntOr("Variant", 0);
            return switch (v) {
                case 0 -> Component.translatable("hydrofarm.variant.axolotl.lucy");
                case 1 -> Component.translatable("hydrofarm.variant.axolotl.wild");
                case 2 -> Component.translatable("hydrofarm.variant.axolotl.gold");
                case 3 -> Component.translatable("hydrofarm.variant.axolotl.cyan");
                case 4 -> Component.translatable("hydrofarm.variant.axolotl.blue");
                default -> null;
            };
        });

        register("parrot", nbt -> {
            if (!nbt.contains("Variant")) return null;
            int v = nbt.getIntOr("Variant", 0);
            return switch (v) {
                case 0 -> Component.translatable("hydrofarm.variant.parrot.red");
                case 1 -> Component.translatable("hydrofarm.variant.parrot.blue");
                case 2 -> Component.translatable("hydrofarm.variant.parrot.green");
                case 3 -> Component.translatable("hydrofarm.variant.parrot.yellow");
                case 4 -> Component.translatable("hydrofarm.variant.parrot.gray");
                default -> null;
            };
        });
    }

    private EntityTooltipAdapters() {}

    private static void register(String mcPath, EntityTooltipAdapter adapter) {
        ADAPTERS.put(Identifier.fromNamespaceAndPath("minecraft", mcPath), adapter);
    }

    private static @Nullable Component namespacedVariant(CompoundTag nbt, String entityKey) {
        if (!nbt.contains("variant")) return null;
        String raw = nbt.getStringOr("variant", "");
        if (raw.isEmpty()) return null;
        int colon = raw.indexOf(':');
        String path = colon >= 0 ? raw.substring(colon + 1) : raw;
        return Component.translatable("hydrofarm.variant." + entityKey + "." + path);
    }

    private static @Nullable Component stringTypeVariant(CompoundTag nbt, String entityKey) {
        if (!nbt.contains("Type")) return null;
        String type = nbt.getStringOr("Type", "");
        if (type.isEmpty()) return null;
        return Component.translatable("hydrofarm.variant." + entityKey + "." + type);
    }

    public static @Nullable Component variantFor(Identifier entityType, CompoundTag nbt) {
        EntityTooltipAdapter a = ADAPTERS.get(entityType);
        return a == null ? null : a.variant(nbt);
    }

    public static boolean isBaby(CompoundTag nbt) {
        if (!nbt.contains("Age")) return false;
        return nbt.getIntOr("Age", 0) < 0;
    }
}
