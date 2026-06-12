package com.kestalkayden.hydrofarm.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.ChatFormatting;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.menu.BedContainerData;
import com.kestalkayden.hydrofarm.menu.HydroponicsBedMenu;
import com.kestalkayden.hydrofarm.platform.HydrofarmPlatform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/** Hydroponics bed — 4 installable planter quadrants per block, single-layer cluster shared
 *  with same-type neighbors. PLANTER_NW/NE/SW/SE drive the multipart dome variants and the
 *  always-on faint glow. Inherits connection props + cluster wiring from {@link AbstractClusterBedBlock}. */
public class HydroponicsBedBlock extends AbstractClusterBedBlock {

    public static final MapCodec<HydroponicsBedBlock> CODEC = simpleCodec(HydroponicsBedBlock::new);

    /** Per-quadrant installed flags — synced by the BE when a planter module is installed.
     *  Indexes match {@link HydroponicsBedBlockEntity#QUADRANT_NW} etc. */
    public static final BooleanProperty PLANTER_NW = BooleanProperty.create("planter_nw");
    public static final BooleanProperty PLANTER_NE = BooleanProperty.create("planter_ne");
    public static final BooleanProperty PLANTER_SW = BooleanProperty.create("planter_sw");
    public static final BooleanProperty PLANTER_SE = BooleanProperty.create("planter_se");

    public HydroponicsBedBlock(Properties properties) {
        super(properties);
        registerDefaultState(connectionDefaults(stateDefinition.any())
            .setValue(PLANTER_NW, false).setValue(PLANTER_NE, false)
            .setValue(PLANTER_SW, false).setValue(PLANTER_SE, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(PLANTER_NW, PLANTER_NE, PLANTER_SW, PLANTER_SE);
    }

    public static BooleanProperty quadrantProperty(int q) {
        return switch (q) {
            case HydroponicsBedBlockEntity.QUADRANT_NW -> PLANTER_NW;
            case HydroponicsBedBlockEntity.QUADRANT_NE -> PLANTER_NE;
            case HydroponicsBedBlockEntity.QUADRANT_SW -> PLANTER_SW;
            case HydroponicsBedBlockEntity.QUADRANT_SE -> PLANTER_SE;
            default -> null;
        };
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HydroponicsBedBlockEntity(pos, state);
    }

    /** Subclasses (e.g. Tree Farm Bed) override to filter which seeds they accept. */
    protected CropAdapter.Category getAcceptedCategory() {
        return CropAdapter.Category.CROP;
    }

    /** Right-click auto-picks a quadrant rather than using the crosshair position. Aiming at
     *  specific quadrants is too tedious in a cluster — internal beds are surrounded and only
     *  reachable top-down. Players install up to 4 planters per bed in NW→NE→SW→SE order,
     *  then configure seeds the same way. Sneak + right-click uninstalls the last-installed
     *  planter (LIFO order) regardless of held item. */
    @Override
    protected InteractionResult useItemOnBed(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof HydroponicsBedBlockEntity be)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (player.isShiftKeyDown()) {
            return tryUninstall(level, pos, player, be);
        }
        Item item = stack.getItem();

        if (item == HydrofarmRefs.HYDROFARM_PLANTER_ITEM.get()) {
            int q = be.firstEmptyQuadrant();
            if (q < 0) return InteractionResult.TRY_WITH_EMPTY_HAND;
            if (!level.isClientSide()) {
                be.installPlanter(q);
                if (!player.getAbilities().instabuild) stack.shrink(1);
                spawnQuadrantParticles((ServerLevel) level, pos, q, ParticleTypes.HAPPY_VILLAGER);
            }
            return InteractionResult.SUCCESS;
        }

        if (CropAdapter.resolveForCategory(item, getAcceptedCategory()) != null) {
            // Valid seed/sapling for this bed, but the player hasn't installed a planter yet —
            // there's nowhere for it to go. New players don't know planters are required, so give
            // them an explicit hint instead of silently doing nothing (which read as "broken").
            if (!be.anyInstalled()) {
                if (!level.isClientSide()) notifyNeedsPlanter(player);
                return InteractionResult.SUCCESS;
            }
            int q = be.firstUnconfiguredInstalledQuadrant();
            if (q < 0) q = be.firstInstalledQuadrant();
            if (q < 0) return InteractionResult.TRY_WITH_EMPTY_HAND;
            if (!level.isClientSide()) {
                be.configureSeed(q, item);
                if (!player.getAbilities().instabuild) stack.shrink(1);
                spawnQuadrantParticles((ServerLevel) level, pos, q, ParticleTypes.COMPOSTER);
            }
            return InteractionResult.SUCCESS;
        }

        // Plantable, but not for THIS bed's category (e.g. a sapling on a hydroponics bed, or
        // seeds on a tree farm bed). Point the player at the right bed rather than silently
        // opening the GUI — they almost certainly grabbed the wrong block.
        CropAdapter.Category mismatch = mismatchedPlantCategory(item);
        if (mismatch != null) {
            if (!level.isClientSide()) notifyWrongBed(player, mismatch);
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    /** If {@code item} is a plant the mod knows how to grow but of a category this bed doesn't
     *  accept, returns that (mismatched) category; otherwise null. Non-plant items return null so
     *  the GUI still opens for them. The matching-category case is handled before this is called. */
    private CropAdapter.Category mismatchedPlantCategory(Item item) {
        CropAdapter ca = CropAdapter.resolve(item);
        if (ca == null || ca.category() == getAcceptedCategory()) return null;
        return ca.category();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof HydroponicsBedBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            return tryUninstall(level, pos, player, be);
        }
        if (!level.isClientSide()) {
            HydrofarmPlatform.menus().open(player,
                Component.translatable("block.hydrofarm.hydroponics_bed"),
                (id, inv, p) -> new HydroponicsBedMenu(id, inv, new BedContainerData(be), pos),
                pos);
        }
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult tryUninstall(Level level, BlockPos pos, Player player,
                                                   HydroponicsBedBlockEntity be) {
        int q = be.lastInstalledQuadrant();
        if (q < 0) return InteractionResult.PASS;
        if (!level.isClientSide() && be.uninstallPlanter(q)) {
            ItemStack drop = new ItemStack(HydrofarmRefs.HYDROFARM_PLANTER_ITEM.get());
            if (!player.getInventory().add(drop)) {
                Containers.dropItemStack(level,
                    pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, drop);
            }
            spawnQuadrantParticles((ServerLevel) level, pos, q, ParticleTypes.SMOKE);
        }
        return InteractionResult.SUCCESS;
    }

    /** Nudge shown when a player right-clicks the bed with a valid seed/sapling but no planter
     *  installed. Action bar (overlay), so repeated attempts don't spam the chat log. */
    private static void notifyNeedsPlanter(Player player) {
        sendActionBar(player, Component.translatable("gui.hydrofarm.bed.needs_planter"));
    }

    /** Nudge shown when a player uses a plantable item of the wrong category for this bed —
     *  keyed to the item's category so it names the bed they actually want. */
    private static void notifyWrongBed(Player player, CropAdapter.Category itemCategory) {
        String key = switch (itemCategory) {
            case TREE -> "gui.hydrofarm.bed.wrong_bed_tree";
            case CROP -> "gui.hydrofarm.bed.wrong_bed_crop";
            default   -> "gui.hydrofarm.bed.wrong_bed";
        };
        sendActionBar(player, Component.translatable(key));
    }

    /** Sends a yellow action-bar (overlay) message. The 26.1 mappings dropped the old
     *  {@code displayClientMessage(component, true)} convenience, so we send the packet directly. */
    private static void sendActionBar(Player player, Component msg) {
        if (player instanceof ServerPlayer sp) {
            sp.connection.send(new ClientboundSetActionBarTextPacket(msg.copy().withStyle(ChatFormatting.YELLOW)));
        }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!(level.getBlockEntity(pos) instanceof HydroponicsBedBlockEntity be)) return;
        if (!be.anyInstalled()) return;
        if (random.nextInt(8) != 0) return;

        DustParticleOptions dust = new DustParticleOptions(0xCC55EE, 0.8f);
        double x = pos.getX() + 0.15 + random.nextDouble() * 0.70;
        double y = pos.getY() + 0.60 + random.nextDouble() * 0.30;
        double z = pos.getZ() + 0.15 + random.nextDouble() * 0.70;
        level.addParticle(dust, x, y, z, 0, 0.01 + random.nextDouble() * 0.015, 0);
    }

    private static void spawnQuadrantParticles(ServerLevel level, BlockPos pos, int q,
                                                net.minecraft.core.particles.ParticleOptions type) {
        boolean east  = q == HydroponicsBedBlockEntity.QUADRANT_NE
                     || q == HydroponicsBedBlockEntity.QUADRANT_SE;
        boolean south = q == HydroponicsBedBlockEntity.QUADRANT_SW
                     || q == HydroponicsBedBlockEntity.QUADRANT_SE;
        double cx = pos.getX() + (east  ? 0.75 : 0.25);
        double cz = pos.getZ() + (south ? 0.75 : 0.25);
        double cy = pos.getY() + 0.55;
        level.sendParticles(type, cx, cy, cz, 8, 0.12, 0.06, 0.12, 0.02);
    }
}
