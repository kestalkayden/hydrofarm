package com.kestalkayden.hydrofarm.block;

import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.platform.EnergyBuffer;

/** Powered durability-repair station — the sink for Liquid XP. Feed it damaged tools/armor (an item
 *  in the repair slot) plus Liquid XP (piped in, or Bottles o' Enchanting in the bottle slot) and
 *  energy; it trickles durability back, preserving enchantments with no anvil "Too Expensive" tax.
 *
 *  <p>Repair is deliberately steep: {@link #MB_PER_DURABILITY} mB of Liquid XP per durability point
 *  ({@code = 15}, i.e. 1.5× vanilla mending) — XP is free here, so the cost is the limiter. Energy is a
 *  gentle "powered-on" gate (idle ~1/s, plus a flat cost per repair op), reusing the repulser's
 *  {@link EnergyBuffer}/{@link EnergyNetwork} infra so cables both push power in and are pulled. */
public class MendingStationBlockEntity extends BlockEntity implements EnergyBuffer {

    // ---- Item slots ----
    public static final int SLOT_REPAIR_IN  = 0;   // damaged item being repaired
    public static final int SLOT_REPAIR_OUT = 1;   // fully-repaired item, waiting for extraction
    public static final int SLOT_COUNT = 2;

    // ---- Liquid XP buffer ----
    /** mB of Liquid XP per 1 XP point — must match the XP Drain so production/consumption share a
     *  scale ({@link XpDrainBlockEntity#MB_PER_XP}). */
    public static final int MB_PER_XP = XpDrainBlockEntity.MB_PER_XP;
    /** mB of Liquid XP spent per durability point repaired. 15 mB = 0.75 XP/durability — 1.5× harsher
     *  than vanilla mending (which restores 2 durability per XP, i.e. 0.5 XP/durability = 10 mB here).
     *  XP is effectively free from the beds + mob farms, so this cost is the intended limiter. */
    public static final int MB_PER_DURABILITY = 15;
    public static final int XP_CAPACITY_MB = 12_000;   // 800 durability buffered

    // ---- Energy ----
    public static final int ENERGY_CAPACITY = 2_000;
    /** Flat energy per repair op. */
    private static final int ENERGY_PER_OP = 2;
    /** Standby draw, applied once per second whenever powered. */
    private static final int IDLE_ENERGY = 1;

    // ---- Cadence ----
    private static final int REPAIR_INTERVAL = 10;            // repair op twice a second
    private static final int DURABILITY_PER_OP = 4;           // 8 durability/s
    private static final int IDLE_INTERVAL = 20;             // 1 energy/s standby
    private static final int PULL_INTERVAL = 5;
    private static final int PULL_RATE = 256;
    /** How long the green "repairing" glow lingers past the last repair op, so a steady cadence reads
     *  as a constant glow rather than a strobe. */
    private static final int GLOW_LINGER = 30;

    private int xpStored = 0;
    private int energyStored = 0;
    private long lastRepairTick = Long.MIN_VALUE;
    private boolean lastSyncedActive = false;

    private final EnergyNetwork energyNet = new EnergyNetwork();

    public final SimpleContainer items = new SimpleContainer(SLOT_COUNT) {
        @Override public void setChanged() {
            super.setChanged();
            MendingStationBlockEntity.this.setChanged();
        }
    };

    /** Loader-built capability wrappers (Fabric Storage / NeoForge handlers), one each, cached so
     *  their transaction snapshots stay consistent. */
    private Object energyExposure;
    private Object fluidExposure;

    public MendingStationBlockEntity(BlockPos pos, BlockState state) {
        super(HydrofarmRefs.MENDING_STATION_BE.get(), pos, state);
    }

    @SuppressWarnings("unchecked")
    public <T> T energyExposure(Function<MendingStationBlockEntity, T> factory) {
        if (energyExposure == null) energyExposure = factory.apply(this);
        return (T) energyExposure;
    }

    @SuppressWarnings("unchecked")
    public <T> T fluidExposure(Function<MendingStationBlockEntity, T> factory) {
        if (fluidExposure == null) fluidExposure = factory.apply(this);
        return (T) fluidExposure;
    }


    // ---- EnergyBuffer ----
    @Override public int getEnergyStored()   { return energyStored; }
    @Override public int getEnergyCapacity() { return ENERGY_CAPACITY; }

    @Override public int insertEnergy(int amount) {
        if (amount <= 0) return 0;
        int accepted = Math.min(amount, ENERGY_CAPACITY - energyStored);
        if (accepted > 0) { energyStored += accepted; setChanged(); }
        return accepted;
    }

    @Override public void setEnergyStored(int v) {
        energyStored = Math.max(0, Math.min(ENERGY_CAPACITY, v));
        setChanged();
    }

    // ---- Liquid XP buffer (insert-only fluid capability) ----
    public int getXpStored()   { return xpStored; }
    public int getXpCapacity() { return XP_CAPACITY_MB; }

    /** Accepts up to {@code mb} of Liquid XP; returns the amount accepted. Only Liquid XP is valid. */
    public int insertXp(Fluid fluid, int mb) {
        if (mb <= 0 || fluid != HydrofarmRefs.LIQUID_XP.get()) return 0;
        int accepted = Math.min(mb, XP_CAPACITY_MB - xpStored);
        if (accepted > 0) { xpStored += accepted; setChanged(); }
        return accepted;
    }

    public void setXpStored(int v) {
        xpStored = Math.max(0, Math.min(XP_CAPACITY_MB, v));
        setChanged();
    }

    // ---- Item capability: damaged-in / repaired-out + bottle-in / glass-out ----
    public final class ItemView implements WorldlyContainer {
        private final int[] slots = { SLOT_REPAIR_IN, SLOT_REPAIR_OUT };
        @Override public int getContainerSize() { return SLOT_COUNT; }
        @Override public boolean isEmpty() { return items.isEmpty(); }
        @Override public ItemStack getItem(int i) { return items.getItem(i); }
        @Override public ItemStack removeItem(int i, int n) { return items.removeItem(i, n); }
        @Override public ItemStack removeItemNoUpdate(int i) { return items.removeItemNoUpdate(i); }
        @Override public void setItem(int i, ItemStack s) { items.setItem(i, s); }
        @Override public void setChanged() { MendingStationBlockEntity.this.setChanged(); }
        @Override public boolean stillValid(Player p) { return true; }
        @Override public void clearContent() { items.clearContent(); }
        @Override public int[] getSlotsForFace(Direction side) { return slots; }
        @Override public boolean canPlaceItem(int i, ItemStack s) { return acceptsInsert(i, s); }
        @Override public boolean canPlaceItemThroughFace(int i, ItemStack s, Direction d) { return acceptsInsert(i, s); }
        @Override public boolean canTakeItemThroughFace(int i, ItemStack s, Direction d) {
            return i == SLOT_REPAIR_OUT;
        }
    }

    /** Insert rule: only damaged damageable items, only into the repair slot. */
    private static boolean acceptsInsert(int slot, ItemStack s) {
        return slot == SLOT_REPAIR_IN && s.isDamageableItem() && s.isDamaged();
    }

    private ItemView itemView;
    public ItemView itemView() {
        if (itemView == null) itemView = new ItemView();
        return itemView;
    }

    // ---- Renderer hooks ----
    /** The item to float + spin above the block — whatever's in the mender, repaired or not. */
    public ItemStack getDisplayItem() {
        return items.getItem(SLOT_REPAIR_IN);
    }
    public boolean isPowered() { return energyStored > 0; }

    // ---- Tick ----
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        long now = level.getGameTime();

        if (energyStored < ENERGY_CAPACITY && now % PULL_INTERVAL == 0) {
            energyNet.pull(level, pos, this, PULL_RATE);
        }

        boolean didWork = false;
        if (now % REPAIR_INTERVAL == 0) {
            didWork = repairTick();
            if (didWork) lastRepairTick = now;
        }

        // Standby draw whenever powered and not actively repairing this second.
        if (!didWork && energyStored > 0 && now % IDLE_INTERVAL == 0) {
            energyStored = Math.max(0, energyStored - IDLE_ENERGY);
            setChanged();
        }

        // REPAIRING glow — lingers past the last op so the cadence reads as a steady light.
        boolean glowing = lastRepairTick != Long.MIN_VALUE && (now - lastRepairTick) < GLOW_LINGER;
        if (state.getValue(MendingStationBlock.REPAIRING) != glowing) {
            level.setBlock(pos, state.setValue(MendingStationBlock.REPAIRING, glowing), Block.UPDATE_CLIENTS);
        }

        // Sync the floating-item "powered" gate only on transition (energy crossing zero).
        boolean active = energyStored > 0;
        if (active != lastSyncedActive) {
            lastSyncedActive = active;
            BlockState st = getBlockState();
            level.sendBlockUpdated(getBlockPos(), st, st, Block.UPDATE_CLIENTS);
        }
    }

    /** Repair the item in the repair slot up to {@link #DURABILITY_PER_OP}, gated by XP + power.
     *  Returns true if any durability was restored. When the item reaches full, push it to the
     *  repaired-out slot so the next damaged item can enter. */
    private boolean repairTick() {
        ItemStack tool = items.getItem(SLOT_REPAIR_IN);
        if (tool.isEmpty() || !tool.isDamageableItem() || !tool.isDamaged()) {
            tryEjectRepaired();   // in case something finished but couldn't move last tick
            return false;
        }
        if (energyStored < ENERGY_PER_OP || xpStored < MB_PER_DURABILITY) return false;

        int byXp = xpStored / MB_PER_DURABILITY;
        int repairable = Math.min(Math.min(DURABILITY_PER_OP, tool.getDamageValue()), byXp);
        if (repairable <= 0) return false;

        tool.setDamageValue(tool.getDamageValue() - repairable);
        xpStored -= repairable * MB_PER_DURABILITY;
        energyStored -= ENERGY_PER_OP;
        items.setChanged();

        if (!tool.isDamaged()) tryEjectRepaired();
        setChanged();
        return true;
    }

    /** Move a fully-repaired tool from the repair slot to the output slot when it's free. */
    private void tryEjectRepaired() {
        ItemStack tool = items.getItem(SLOT_REPAIR_IN);
        if (tool.isEmpty() || tool.isDamaged()) return;
        if (!items.getItem(SLOT_REPAIR_OUT).isEmpty()) return;   // output blocked; wait
        items.setItem(SLOT_REPAIR_OUT, tool.copy());
        items.setItem(SLOT_REPAIR_IN, ItemStack.EMPTY);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null && !level.isClientSide()) {
            Containers.dropContents(level, pos, items);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        NonNullList<ItemStack> list = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        for (int i = 0; i < SLOT_COUNT; i++) list.set(i, items.getItem(i));
        ContainerHelper.saveAllItems(out.child("Items"), list);
        out.putInt("Xp", xpStored);
        out.putInt("Energy", energyStored);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        NonNullList<ItemStack> list = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        in.child("Items").ifPresent(c -> ContainerHelper.loadAllItems(c, list));
        for (int i = 0; i < SLOT_COUNT; i++) items.setItem(i, list.get(i));
        xpStored = Math.max(0, Math.min(XP_CAPACITY_MB, in.getIntOr("Xp", 0)));
        energyStored = Math.max(0, Math.min(ENERGY_CAPACITY, in.getIntOr("Energy", 0)));
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return saveCustomOnly(provider);
    }
}
