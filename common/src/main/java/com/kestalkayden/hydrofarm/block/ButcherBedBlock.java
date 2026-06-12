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

/** Butcher Bed — 2 stalls per block, single-layer cluster shared with same-type neighbors.
 *  Captured animals mature on a cull cycle and produce death loot to the cluster pool when
 *  matured. Sim-breeding refills empty stalls when the cluster has 2+ adults of a species. */
public class ButcherBedBlock extends AbstractClusterBedBlock {

    public static final MapCodec<ButcherBedBlock> CODEC = simpleCodec(ButcherBedBlock::new);

    public ButcherBedBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ButcherBedBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useItemOnBed(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ButcherBedBlockEntity be)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (!CaptureNetCompat.isCaptureNet(stack)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }

        CapturedEntity captured = CaptureNetCompat.read(stack);

        if (player.isShiftKeyDown()) {
            if (captured != null) return InteractionResult.PASS;
            return tryExtract(level, pos, player, hand, stack, be);
        }

        if (captured == null) return InteractionResult.TRY_WITH_EMPTY_HAND;

        // Install into the next free stall anywhere in the cluster — the whole pen is one pool.
        int[] counts = be.clusterStallCounts();
        if (counts[0] >= counts[1]) {
            denyInstall(level, pos, player, "gui.hydrofarm.butcher_bed.full");
            return InteractionResult.SUCCESS;
        }
        if (!ButcherAnimal.isSupported(captured.entityType())) {
            denyInstall(level, pos, player, "gui.hydrofarm.butcher_bed.unsupported");
            return InteractionResult.SUCCESS;
        }
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        ButcherBedBlockEntity.InstallResult installed =
            be.clusterInstallHost(captured.entityType(), captured.entityNbt());
        if (installed == null) return InteractionResult.PASS;
        CaptureNetCompat.clear(stack);
        player.setItemInHand(hand, stack);
        if (player.containerMenu != null) player.containerMenu.broadcastChanges();

        spawnStallParticles((ServerLevel) level, installed.member().getBlockPos(), installed.stall(),
            ParticleTypes.HAPPY_VILLAGER);
        level.playSound(null, pos, SoundEvents.BUNDLE_INSERT, SoundSource.BLOCKS, 0.6F, 0.95F);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ButcherBedBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            HydrofarmPlatform.menus().open(player,
                Component.translatable("block.hydrofarm.butcher_bed"),
                (id, inv, p) -> new AnimalBedMenu(id, inv, new AnimalBedContainerData(be), pos),
                pos);
        }
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult tryExtract(Level level, BlockPos pos, Player player,
                                                 InteractionHand hand, ItemStack net,
                                                 ButcherBedBlockEntity be) {
        if (be.clusterStallCounts()[0] <= 0) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        ButcherBedBlockEntity.EvictResult evicted = be.clusterEvictLast();
        if (evicted == null) return InteractionResult.PASS;
        CaptureNetCompat.set(net, new CapturedEntity(evicted.host().type(), evicted.host().nbt()));
        player.setItemInHand(hand, net);
        if (player.containerMenu != null) player.containerMenu.broadcastChanges();

        spawnStallParticles((ServerLevel) level, evicted.member().getBlockPos(), evicted.stall(),
            ParticleTypes.POOF);
        level.playSound(null, pos, SoundEvents.BUNDLE_DROP_CONTENTS, SoundSource.BLOCKS, 0.6F, 0.85F);
        return InteractionResult.SUCCESS;
    }

    private static void denyInstall(Level level, BlockPos pos, Player player, String messageKey) {
        if (level.isClientSide()) return;
        level.playSound(null, pos, SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.4F, 0.9F);
        if (player instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.translatable(messageKey));
        }
    }

    private static void spawnStallParticles(ServerLevel level, BlockPos pos, int stall,
                                              net.minecraft.core.particles.ParticleOptions type) {
        boolean east = stall == ButcherBedBlockEntity.STALL_RIGHT;
        double cx = pos.getX() + (east ? 0.75 : 0.25);
        double cy = pos.getY() + 0.55;
        double cz = pos.getZ() + 0.5;
        level.sendParticles(type, cx, cy, cz, 8, 0.12, 0.06, 0.12, 0.02);
    }
}
