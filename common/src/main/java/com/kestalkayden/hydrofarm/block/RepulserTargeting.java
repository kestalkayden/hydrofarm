package com.kestalkayden.hydrofarm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Enemy;

/** Shared "what does the repulser act on, and where" logic — used identically by the spawn-blocking
 *  hook and the wander-in sweep so they never disagree. */
public final class RepulserTargeting {

    /** Force-include these entity types even if they don't read as hostile (for packs/mods). */
    public static final TagKey<EntityType<?>> TARGETS = TagKey.create(Registries.ENTITY_TYPE,
        Identifier.fromNamespaceAndPath("hydrofarm", "repulser_targets"));
    /** Force-exclude these (defaults to bosses — see the tag JSON). */
    public static final TagKey<EntityType<?>> IMMUNE = TagKey.create(Registries.ENTITY_TYPE,
        Identifier.fromNamespaceAndPath("hydrofarm", "repulser_immune"));

    private RepulserTargeting() {}

    /** Whether the repulser repels this entity: a hostile mob that isn't a boss, named, tamed, or
     *  persistence-required. Modded hostiles are caught via {@link Enemy} / {@link MobCategory}, and
     *  packs can tune both ends with the two tags. */
    public static boolean isRepellable(Entity entity) {
        if (!(entity instanceof Mob mob)) return false;
        EntityType<?> type = mob.getType();

        // Spared — never touch these.
        if (type.builtInRegistryHolder().is(IMMUNE)) return false;
        if (mob.hasCustomName()) return false;
        if (mob instanceof TamableAnimal tame && tame.isTame()) return false;
        if (mob.isPersistenceRequired()) return false;

        // Targeted.
        if (type.builtInRegistryHolder().is(TARGETS)) return true;
        if (mob instanceof Enemy) return true;
        return type.getCategory() == MobCategory.MONSTER;
    }

    /** Whether {@code (x,y,z)} is inside the flat field disc centred on the repulser at {@code pos}. */
    public static boolean withinField(BlockPos pos, double x, double y, double z) {
        double dx = x - (pos.getX() + 0.5);
        double dz = z - (pos.getZ() + 0.5);
        if (dx * dx + dz * dz > RepulserBlockEntity.RADIUS_SQ) return false;
        double dy = y - (pos.getY() + 0.5);
        return Math.abs(dy) <= RepulserBlockEntity.VERTICAL;
    }
}
