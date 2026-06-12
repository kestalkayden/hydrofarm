package com.kestalkayden.hydrofarm.block;

import com.mojang.serialization.MapCodec;

import com.kestalkayden.hydrofarm.item.CaptureNetCompat;
import com.kestalkayden.hydrofarm.item.CapturedEntity;
import com.kestalkayden.hydrofarm.menu.AnimalBedContainerData;
import com.kestalkayden.hydrofarm.menu.AnimalBedMenu;
import com.kestalkayden.hydrofarm.platform.HydrofarmPlatform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Husbandry Bed — 2 stalls per block, single-layer cluster shared with same-type neighbors.
 *  Animals are installed via the Animal Capture Net (right-click bed with filled net) and
 *  extracted the same way (sneak + right-click with empty net). */
public class HusbandryBedBlock extends AbstractClusterBedBlock {

    public static final MapCodec<HusbandryBedBlock> CODEC = simpleCodec(HusbandryBedBlock::new);

    public HusbandryBedBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HusbandryBedBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useItemOnBed(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof HusbandryBedBlockEntity be)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (!CaptureNetCompat.isCaptureNet(stack)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }

        CapturedEntity captured = CaptureNetCompat.read(stack);

        if (player.isShiftKeyDown()) {
            // Sneak + filled net: no-op — the user wanted to release into the world via useOn
            // of the net itself, which already handles spawning on the clicked face. Falling
            // through with TRY_WITH_EMPTY_HAND would let useWithoutItem try to open a menu,
            // which we don't want during a sneak-with-item gesture.
            if (captured != null) return InteractionResult.PASS;
            // Sneak + empty net: extract the last-occupied stall back into the net.
            return tryExtract(level, pos, player, hand, stack, be);
        }

        // Normal click with filled net: install into the next free stall anywhere in the cluster.
        // The whole pen is one pool, so a player can load it by clicking any single bed.
        if (captured == null) return InteractionResult.TRY_WITH_EMPTY_HAND;

        int[] counts = be.clusterStallCounts();
        if (counts[0] >= counts[1]) {
            denyInstall(level, pos, player, "gui.hydrofarm.husbandry_bed.full");
            return InteractionResult.SUCCESS;
        }
        if (!HusbandryAnimal.isSupported(captured.entityType())) {
            denyInstall(level, pos, player, "gui.hydrofarm.husbandry_bed.unsupported");
            return InteractionResult.SUCCESS;
        }
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        HusbandryBedBlockEntity.InstallResult installed =
            be.clusterInstallHost(captured.entityType(), captured.entityNbt());
        if (installed == null) return InteractionResult.PASS;
        // Clear the net's contents, sync the stack for creative-mode safety.
        CaptureNetCompat.clear(stack);
        player.setItemInHand(hand, stack);
        if (player.containerMenu != null) player.containerMenu.broadcastChanges();

        spawnStallParticles((ServerLevel) level, installed.member().getBlockPos(), installed.stall(),
            ParticleTypes.HAPPY_VILLAGER);
        level.playSound(null, pos, SoundEvents.BUNDLE_INSERT, SoundSource.BLOCKS, 0.6F, 1.1F);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof HusbandryBedBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            HydrofarmPlatform.menus().open(player,
                Component.translatable("block.hydrofarm.husbandry_bed"),
                (id, inv, p) -> new AnimalBedMenu(id, inv, new AnimalBedContainerData(be), pos),
                pos);
        }
        return InteractionResult.SUCCESS;
    }

    /** Sneak + right-click with an empty net: evicts the cluster's last-occupied stall into the
     *  net (cluster-wide LIFO — the whole pen is one pool, so any bed extracts from the pen). */
    private static InteractionResult tryExtract(Level level, BlockPos pos, Player player,
                                                 InteractionHand hand, ItemStack net,
                                                 HusbandryBedBlockEntity be) {
        if (be.clusterStallCounts()[0] <= 0) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        HusbandryBedBlockEntity.EvictResult evicted = be.clusterEvictLast();
        if (evicted == null) return InteractionResult.PASS;
        CaptureNetCompat.set(net, new CapturedEntity(evicted.host().type(), evicted.host().nbt()));
        player.setItemInHand(hand, net);
        if (player.containerMenu != null) player.containerMenu.broadcastChanges();

        spawnStallParticles((ServerLevel) level, evicted.member().getBlockPos(), evicted.stall(),
            ParticleTypes.POOF);
        level.playSound(null, pos, SoundEvents.BUNDLE_DROP_CONTENTS, SoundSource.BLOCKS, 0.6F, 1.0F);
        return InteractionResult.SUCCESS;
    }

    /** Audible + chat-line feedback for a failed install (bed full, unsupported species).
     *  Server-side only — the click handler runs on both sides and we don't want the sound
     *  to play twice. */
    private static void denyInstall(Level level, BlockPos pos, Player player, String messageKey) {
        if (level.isClientSide()) return;
        level.playSound(null, pos, SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.4F, 1.0F);
        if (player instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.translatable(messageKey));
        }
    }

    private static void spawnStallParticles(ServerLevel level, BlockPos pos, int stall,
                                              net.minecraft.core.particles.ParticleOptions type) {
        // Two stalls split along the X axis — left = west, right = east.
        boolean east = stall == HusbandryBedBlockEntity.STALL_RIGHT;
        double cx = pos.getX() + (east ? 0.75 : 0.25);
        double cy = pos.getY() + 0.55;
        double cz = pos.getZ() + 0.5;
        level.sendParticles(type, cx, cy, cz, 8, 0.12, 0.06, 0.12, 0.02);
    }
}
