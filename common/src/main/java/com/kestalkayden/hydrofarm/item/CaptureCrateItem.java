package com.kestalkayden.hydrofarm.item;

import java.util.Optional;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
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
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.util.AnimalNbt;

/** Reusable multi-capacity capture item — the Capture Crate. Holds up to
 *  {@link ContainedEntities#MAX_CAPACITY} entities (mixed types, full NBT), captured one at a
 *  time and released one at a time at a chosen spot. It catches exactly what the single
 *  {@link AnimalCaptureNetItem} catches (shared {@link CaptureRules#isEligible}).
 *
 *  <p>Controls:
 *  <ul>
 *    <li>right-click an eligible entity → capture &amp; append (until full)</li>
 *    <li>right-click a block → release the <em>selected</em> entity on that face</li>
 *    <li>sneak + right-click air → cycle which entity is selected next</li>
 *  </ul>
 *
 *  <p>Contents live in the {@code contained_entities} data component, whose network form carries
 *  the full NBT (see {@link ContainedEntity}); the precomputed display bits ride along so the
 *  tooltip needn't re-parse. The component is removed entirely when the crate empties, so the
 *  {@code has_component} item-model swap and the glint cue match the single net. */
public class CaptureCrateItem extends Item {

    public CaptureCrateItem(Properties properties) {
        super(properties);
    }

    // ---- capture ----------------------------------------------------------------------------

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        // Safety-net path; the loader pre-interact hooks are the ones that win against mobs whose
        // own mobInteract consumes the click first (see CaptureCrateItem usage in the loader mains).
        return tryCapture(stack, player, target, hand);
    }

    /** Capture {@code target} into the crate if it's eligible and there's room. SUCCESS on
     *  capture; PASS when blocked / full / unknown type (so a full crate still lets trade GUIs
     *  etc. open). Safe to call from a pre-interact event. */
    public static InteractionResult tryCapture(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (target.level().isClientSide()) {
            return canAccept(stack, target) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (!canAccept(stack, target)) return InteractionResult.PASS;

        ContainedEntities contents = contentsOf(stack);

        // MC 26.x routes entity save through ValueOutput; TagValueOutput is the CompoundTag-backed
        // impl. ProblemReporter.DISCARDING silently swallows validation issues (we trust the entity).
        //
        // saveAsPassenger, NOT save(): Entity.save() writes NOTHING and returns false for a
        // passenger (e.g. a villager riding a minecart), which would store empty NBT and then
        // discard the mob. saveAsPassenger serializes it regardless of ride state (the discard()
        // below detaches it from the vehicle); a false return means the entity is genuinely
        // unserializable — bail WITHOUT discarding so we never delete what we couldn't store.
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
            target.level().registryAccess());
        if (!target.saveAsPassenger(out)) return InteractionResult.PASS;
        CompoundTag nbt = out.buildResult();
        Identifier typeId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        if (typeId == null) return InteractionResult.PASS;  // Unknown entity type — safety bail

        // Precompute the display bits now, while we hold the live NBT server-side, so the tooltip
        // doesn't have to re-parse the NBT each frame.
        Optional<Component> variant = Optional.ofNullable(EntityTooltipAdapters.variantFor(typeId, nbt));
        Optional<Component> name = Optional.ofNullable(readCustomName(target.level().registryAccess(), nbt));
        boolean baby = AnimalNbt.isBaby(nbt);

        ContainedEntities next = contents.withAdded(new ContainedEntity(typeId, nbt, name, variant, baby));
        stack.set(HydrofarmRefs.CONTAINED_ENTITIES.get(), next);
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        resync(player, hand, stack);

        ServerLevel level = (ServerLevel) target.level();
        // Pitch rises as the crate fills — audible "how full" cue alongside the bar.
        float pitch = 0.8F + 0.05F * (next.size() - 1);
        level.sendParticles(ParticleTypes.POOF,
            target.getX(), target.getY() + target.getBbHeight() / 2.0, target.getZ(),
            16, 0.3, 0.3, 0.3, 0.05);
        level.playSound(null, target.blockPosition(),
            SoundEvents.BUNDLE_INSERT, SoundSource.PLAYERS, 0.6F, pitch);

        target.discard();
        return InteractionResult.SUCCESS;
    }

    private static boolean canAccept(ItemStack stack, LivingEntity entity) {
        return CaptureRules.isEligible(entity) && !contentsOf(stack).isFull();
    }

    // ---- release ----------------------------------------------------------------------------

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        // Sneaking means "cycle the selection", never release — even when pointed at the ground
        // (a block hit, which would otherwise land here in useOn instead of use()).
        if (player != null && player.isShiftKeyDown()) {
            return cycle(context.getLevel(), player, context.getHand(), stack);
        }
        ContainedEntities contents = contentsOf(stack);
        if (contents.isEmpty()) return InteractionResult.PASS;
        if (context.getLevel().isClientSide()) return InteractionResult.SUCCESS;

        ServerLevel level = (ServerLevel) context.getLevel();
        BlockPos spawnPos = context.getClickedPos().relative(context.getClickedFace());
        ContainedEntity release = contents.selectedEntity();

        // Mirror of the save side: load via ValueInput from the stored full tag (server-only).
        ValueInput in = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), release.nbt());
        Entity spawned = EntityType.loadEntityRecursive(in, level, EntitySpawnReason.LOAD, e -> {
            e.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
            return e;
        });
        if (spawned == null) {
            // Unspawnable entry — corrupt/legacy data (e.g. an empty-NBT passenger capture from
            // before the fix), or an entity type whose mod is no longer present. Drop it instead of
            // leaving the crate permanently stuck on it, and tell the player rather than failing silently.
            applyContents(stack, contents.withSelectedReleased());
            resync(player, context.getHand(), stack);
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.connection.send(new ClientboundSetActionBarTextPacket(
                    Component.translatable("item.hydrofarm.capture_crate.discarded")
                        .withStyle(ChatFormatting.RED)));
            }
            return InteractionResult.SUCCESS;
        }
        if (!level.addFreshEntity(spawned)) return InteractionResult.PASS;

        level.sendParticles(ParticleTypes.CLOUD,
            spawned.getX(), spawned.getY() + 0.5, spawned.getZ(),
            12, 0.2, 0.2, 0.2, 0.02);
        level.playSound(null, spawnPos,
            SoundEvents.BUNDLE_DROP_CONTENTS, SoundSource.PLAYERS, 0.6F, 1.0F);

        applyContents(stack, contents.withSelectedReleased());
        resync(player, context.getHand(), stack);
        return InteractionResult.SUCCESS;
    }

    // ---- cycle selection --------------------------------------------------------------------

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        // Sneak + right-click air cycles; a plain air right-click does nothing (release is on blocks).
        if (!player.isShiftKeyDown()) return InteractionResult.PASS;
        return cycle(level, player, hand, player.getItemInHand(hand));
    }

    /** Advance the selection to the next entity. Consumes the interaction whenever the crate holds
     *  anything — so a sneak-click never falls through to release or block placement — but only
     *  actually moves the cursor when there are 2+ entities. */
    private static InteractionResult cycle(Level level, Player player, InteractionHand hand, ItemStack stack) {
        ContainedEntities contents = contentsOf(stack);
        if (contents.isEmpty()) return InteractionResult.PASS;       // nothing held — let vanilla handle the click
        if (contents.size() < 2) return InteractionResult.SUCCESS;   // single occupant — consume, nothing to cycle
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        ContainedEntities next = contents.cycled();
        stack.set(HydrofarmRefs.CONTAINED_ENTITIES.get(), next);
        resync(player, hand, stack);
        // Action-bar feedback so cycling works without the full list shown in the tooltip.
        // (ServerPlayer.connection packet — Player.displayClientMessage isn't present on 26.2.)
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.connection.send(new ClientboundSetActionBarTextPacket(
                Component.translatable("item.hydrofarm.capture_crate.selected", describe(next.selectedEntity()))
                    .withStyle(ChatFormatting.AQUA)));
        }
        level.playSound(null, player.blockPosition(),
            SoundEvents.BUNDLE_INSERT, SoundSource.PLAYERS, 0.4F, 1.4F);
        return InteractionResult.SUCCESS;
    }

    // ---- capacity bar -----------------------------------------------------------------------

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return !contentsOf(stack).isEmpty();
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        int size = contentsOf(stack).size();
        return Math.round(13.0F * size / ContainedEntities.MAX_CAPACITY);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        float ratio = (float) contentsOf(stack).size() / ContainedEntities.MAX_CAPACITY;
        // Hue sweeps green (lots of room) → gold → red (full): 1/3 down to 0 in HSV.
        return Mth.hsvToRgb((1.0F - ratio) / 3.0F, 1.0F, 1.0F);
    }

    // ---- tooltip ----------------------------------------------------------------------------

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                 Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        ContainedEntities contents = contentsOf(stack);
        if (contents.isEmpty()) {
            tooltip.accept(Component.translatable("item.hydrofarm.capture_crate.tooltip.empty")
                .withStyle(ChatFormatting.GRAY));
            return;
        }
        tooltip.accept(Component.translatable("item.hydrofarm.capture_crate.tooltip.count",
            contents.size(), ContainedEntities.MAX_CAPACITY).withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("item.hydrofarm.capture_crate.tooltip.selected",
            describe(contents.selectedEntity())).withStyle(ChatFormatting.AQUA));
        if (contents.size() > 1) {
            tooltip.accept(Component.translatable("item.hydrofarm.capture_crate.tooltip.cycle_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** Build a "[Name · ][Variant ]EntityType[ · baby]" label from a stored entry. Uses only the
     *  precomputed display fields, so it works client-side without the (server-only) NBT. */
    public static MutableComponent describe(ContainedEntity entry) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(entry.type());
        MutableComponent label = Component.empty();
        if (entry.name().isPresent()) {
            label.append(entry.name().get()).append(Component.literal(" · "));
        }
        if (entry.variant().isPresent()) {
            label.append(entry.variant().get()).append(Component.literal(" "));
        }
        label.append(type != null ? type.getDescription() : Component.literal(entry.type().toString()));
        if (entry.baby()) {
            label.append(Component.literal(" · "))
                 .append(Component.translatable("hydrofarm.tooltip.baby"));
        }
        return label;
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static ContainedEntities contentsOf(ItemStack stack) {
        ContainedEntities contents = stack.get(HydrofarmRefs.CONTAINED_ENTITIES.get());
        return contents == null ? ContainedEntities.EMPTY : contents;
    }

    /** Write {@code contents} back to the stack, removing the component (and glint) entirely when
     *  the crate is now empty so the empty item-model and tooltip kick in. */
    private static void applyContents(ItemStack stack, ContainedEntities contents) {
        if (contents.isEmpty()) {
            stack.remove(HydrofarmRefs.CONTAINED_ENTITIES.get());
            stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
        } else {
            stack.set(HydrofarmRefs.CONTAINED_ENTITIES.get(), contents);
        }
    }

    private static void resync(@Nullable Player player, InteractionHand hand, ItemStack stack) {
        if (player == null) return;
        // creative's client-authoritative inventory can drop a silent component update without this.
        player.setItemInHand(hand, stack);
        if (player.containerMenu != null) player.containerMenu.broadcastChanges();
    }

    /** CustomName in saved entity NBT is a component-as-tag; decode with registry-aware ops.
     *  Returns null on any parse failure. */
    private static @Nullable Component readCustomName(RegistryAccess registries, CompoundTag nbt) {
        if (!nbt.contains("CustomName")) return null;
        try {
            Tag tag = nbt.get("CustomName");
            if (tag == null) return null;
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
            return ComponentSerialization.CODEC.parse(ops, tag).result().orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
