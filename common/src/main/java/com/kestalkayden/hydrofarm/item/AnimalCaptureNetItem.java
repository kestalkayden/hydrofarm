package com.kestalkayden.hydrofarm.item;

import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.storage.TagValueOutput;

import com.kestalkayden.hydrofarm.util.AnimalNbt;

/** Reusable capture-and-release item. Empty net + right-click on a capturable entity stores
 *  the entity's full NBT in a data component and removes it from the world. Filled net +
 *  right-click on a block face spawns the entity on top of that block and empties the net.
 *
 *  Capture allowed: passive/neutral living entities. Blocked: players, bosses (Ender Dragon,
 *  Wither, Warden), and anything in {@link MobCategory#MONSTER}. Hostile capture is reserved
 *  for a future Tier-2 net. */
public class AnimalCaptureNetItem extends Item {

    public AnimalCaptureNetItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        // Safety-net path. The loader pre-interact hooks (Fabric UseEntityCallback / NeoForge
        // EntityInteract) are what actually win against mobs whose own mobInteract consumes the
        // click first — villagers (trade GUI), allays (item swap), golems. This still captures
        // normally if for some reason that hook didn't fire (foreign call path, mod intercept).
        return tryCapture(stack, player, target, hand);
    }

    /** Capture {@code target} into the empty net if eligible. SUCCESS on capture; PASS when the net
     *  is already full, the target is blocked, or its type is unknown. Static + public so the loader
     *  pre-interact hooks can call it BEFORE the entity's own interaction — the only way to net
     *  villagers/allays/golems, whose mobInteract would otherwise swallow the click. A PASS lets
     *  vanilla handle the click, so a filled net still opens a villager's trades. */
    public static InteractionResult tryCapture(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (target.level().isClientSide()) {
            return isCapturable(stack, target) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (!isCapturable(stack, target)) return InteractionResult.PASS;

        // MC 26.x routes entity save through ValueOutput; TagValueOutput is the CompoundTag-
        // backed impl. ProblemReporter.DISCARDING silently swallows validation issues —
        // appropriate here since we trust the entity is well-formed.
        //
        // saveAsPassenger, NOT save(): Entity.save() writes NOTHING and returns false for a
        // passenger (e.g. a villager riding a minecart), which used to store empty NBT and then
        // discard the mob — leaving an unreleasable net ("contains villager" but does nothing) and
        // a permanently lost entity. This is the single live-entity serialization point in the mod
        // (the husbandry/butcher beds only shuttle the NBT the net already produced), so fixing it
        // here fixes the bed flows too. saveAsPassenger serializes the entity regardless of ride
        // state (it's what vehicles call to persist their riders); the discard() below then cleanly
        // detaches it from the vehicle. A false return means the entity is genuinely unserializable
        // (no encode id) — bail out WITHOUT discarding, so we never delete what we couldn't store.
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
            target.level().registryAccess());
        if (!target.saveAsPassenger(out)) return InteractionResult.PASS;
        CompoundTag nbt = out.buildResult();
        Identifier typeId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        if (typeId == null) return InteractionResult.PASS;  // Unknown entity type — safety bail

        stack.set(com.kestalkayden.hydrofarm.HydrofarmRefs.CAPTURED_ENTITY.get(), new CapturedEntity(typeId, nbt));
        // Glint cue via the 26.1 component (more reliable than overriding isFoil under
        // the DataComponent refactor).
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        // Force the held-item slot to re-sync. In survival the next broadcastChanges
        // catches the component update, but creative mode's client-authoritative
        // inventory can drop it without this prod.
        player.setItemInHand(hand, stack);
        if (player.containerMenu != null) {
            player.containerMenu.broadcastChanges();
        }

        ServerLevel level = (ServerLevel) target.level();
        level.sendParticles(ParticleTypes.POOF,
            target.getX(), target.getY() + target.getBbHeight() / 2.0, target.getZ(),
            16, 0.3, 0.3, 0.3, 0.05);
        level.playSound(null, target.blockPosition(),
            SoundEvents.BUNDLE_INSERT, SoundSource.PLAYERS, 0.6F, 1.0F);

        target.discard();
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        CapturedEntity captured = stack.get(com.kestalkayden.hydrofarm.HydrofarmRefs.CAPTURED_ENTITY.get());
        if (captured == null) return InteractionResult.PASS;
        if (context.getLevel().isClientSide()) return InteractionResult.SUCCESS;

        ServerLevel level = (ServerLevel) context.getLevel();
        CompoundTag nbt = captured.entityNbt();

        if (!nbt.contains("id")) {
            // Legacy corrupt capture: before the passenger fix, right-clicking a passenger (e.g. a
            // villager in a minecart) stored empty NBT and discarded the mob, leaving a net that
            // reads "contains <mob>" but can never release. The mob is unrecoverable; free the net
            // so it's usable again instead of staying permanently stuck, and tell the player.
            clearCapture(stack);
            resync(context.getPlayer(), context.getHand(), stack);
            if (context.getPlayer() instanceof ServerPlayer serverPlayer) {
                serverPlayer.connection.send(new ClientboundSetActionBarTextPacket(
                    Component.translatable("item.hydrofarm.animal_capture_net.released_empty")
                        .withStyle(ChatFormatting.RED)));
            }
            return InteractionResult.SUCCESS;
        }

        BlockPos spawnPos = context.getClickedPos().relative(context.getClickedFace());

        // EntityType.loadEntityRecursive reconstructs from the stored "id" + state tag.
        Entity spawned = EntityType.loadEntityRecursive(nbt, level, EntitySpawnReason.LOAD, e -> {
            e.setPos(
                spawnPos.getX() + 0.5,
                spawnPos.getY(),
                spawnPos.getZ() + 0.5);
            return e;
        });
        if (spawned == null) return InteractionResult.PASS;

        if (!level.addFreshEntity(spawned)) return InteractionResult.PASS;

        level.sendParticles(ParticleTypes.CLOUD,
            spawned.getX(), spawned.getY() + 0.5, spawned.getZ(),
            12, 0.2, 0.2, 0.2, 0.02);
        level.playSound(null, spawnPos,
            SoundEvents.BUNDLE_DROP_CONTENTS, SoundSource.PLAYERS, 0.6F, 1.0F);

        clearCapture(stack);
        resync(context.getPlayer(), context.getHand(), stack);
        return InteractionResult.SUCCESS;
    }

    /** Return the net to empty: drop the captured-entity component and the "loaded" glint cue. */
    private static void clearCapture(ItemStack stack) {
        stack.remove(com.kestalkayden.hydrofarm.HydrofarmRefs.CAPTURED_ENTITY.get());
        stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
    }

    /** Push the mutated stack back to the client. Creative's client-authoritative inventory can
     *  otherwise drop a silent component update. No-op for a null player (e.g. dispenser use). */
    private static void resync(@Nullable Player player, InteractionHand hand, ItemStack stack) {
        if (player == null) return;
        player.setItemInHand(hand, stack);
        if (player.containerMenu != null) player.containerMenu.broadcastChanges();
    }

    private static boolean isCapturable(ItemStack stack, LivingEntity entity) {
        if (stack.has(com.kestalkayden.hydrofarm.HydrofarmRefs.CAPTURED_ENTITY.get())) return false;
        // Hardcoded absolute blocks — not overridable by the always_capturable tag.
        if (entity instanceof Player) return false;
        if (entity instanceof EnderDragon) return false;
        if (entity instanceof WitherBoss) return false;
        if (entity instanceof Warden) return false;

        EntityType<?> type = entity.getType();
        // Datapack overrides via the shared capturenet: tags, so one datapack governs both this net
        // and the standalone mod's. The blocklist wins when an author tags an entity both ways.
        if (type.builtInRegistryHolder().is(CaptureNetCompat.CANNOT_CAPTURE)) return false;
        if (type.builtInRegistryHolder().is(CaptureNetCompat.ALWAYS_CAPTURABLE)) return true;
        return type.getCategory() != MobCategory.MONSTER;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                 Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        CapturedEntity captured = stack.get(com.kestalkayden.hydrofarm.HydrofarmRefs.CAPTURED_ENTITY.get());
        if (captured == null) {
            tooltip.accept(Component.translatable("item.hydrofarm.animal_capture_net.tooltip.empty")
                .withStyle(ChatFormatting.GRAY));
            return;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(captured.entityType());
        if (type == null) return;

        CompoundTag nbt = captured.entityNbt();
        Component variant = EntityTooltipAdapters.variantFor(captured.entityType(), nbt);
        Component customName = readCustomName(context, nbt);
        boolean baby = AnimalNbt.isBaby(nbt);

        MutableComponent contents = Component.empty();
        if (customName != null) {
            contents.append(customName).append(Component.literal(" · "));
        }
        if (variant != null) {
            contents.append(variant).append(Component.literal(" "));
        }
        contents.append(type.getDescription());
        if (baby) {
            contents.append(Component.literal(" · "))
                    .append(Component.translatable("hydrofarm.tooltip.baby"));
        }

        tooltip.accept(Component.translatable("item.hydrofarm.animal_capture_net.tooltip.contains",
            contents).withStyle(ChatFormatting.AQUA));
    }

    /** CustomName in saved entity NBT is stored as a component-as-tag. Decode via
     *  ComponentSerialization with registry-aware ops so style refs resolve. Returns null
     *  on any parse failure — tooltip falls back to no name. */
    private static Component readCustomName(TooltipContext context, CompoundTag nbt) {
        if (!nbt.contains("CustomName")) return null;
        try {
            Tag tag = nbt.get("CustomName");
            if (tag == null) return null;
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, context.registries());
            return ComponentSerialization.CODEC.parse(ops, tag).result().orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
