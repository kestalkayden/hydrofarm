package com.kestalkayden.hydrofarm.menu;

import com.kestalkayden.hydrofarm.HydrofarmRefs;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/** Shared menu for the Husbandry and Butcher beds. Both expose the same data shape — cluster
 *  size, water/output-fluid gauges, stall occupancy, top species, food-shortage bitmask, and
 *  top-N output rates. The title (from the BE's getDisplayName) tells the screen which bed
 *  type it is rendering for. */
public class AnimalBedMenu extends AbstractContainerMenu {

    public static final int SPECIES_SLOT_COUNT = 4;
    public static final int OUTPUT_SLOT_COUNT  = 4;

    public static final int DATA_CLUSTER_SIZE      = 0;
    public static final int DATA_WATER_MB          = 1;
    public static final int DATA_WATER_CAP         = 2;
    public static final int DATA_OUT_FLUID_MB      = 3;
    public static final int DATA_OUT_FLUID_CAP     = 4;
    public static final int DATA_OUT_FLUID_ID      = 5;
    public static final int DATA_STALLS_OCCUPIED   = 6;
    public static final int DATA_STALLS_TOTAL      = 7;
    /** Pairs of (entity_type_id, count) — 4 species × 2 = 8 ints. */
    public static final int DATA_SPECIES_BASE      = 8;
    /** Bitmask: bit i set if species i (in the species slots) has no food in the cluster pool. */
    public static final int DATA_FOOD_MISSING_MASK = 16;
    /** Pairs of (item_id, items_per_min) — 4 outputs × 2 = 8 ints. */
    public static final int DATA_OUTPUT_BASE       = 17;
    public static final int DATA_OUTPUT_OVERFLOW   = 25;
    public static final int DATA_WATER_RATE        = 26;
    public static final int DATA_OUT_FLUID_RATE    = 27;
    public static final int DATA_COUNT             = 28;

    private final ContainerData data;
    private final BlockPos pos;

    /** Server-side ctor. */
    public AnimalBedMenu(int containerId, Inventory inv, ContainerData data, BlockPos pos) {
        super(HydrofarmRefs.ANIMAL_BED_MENU.get(), containerId);
        checkContainerDataCount(data, DATA_COUNT);
        this.data = data;
        this.pos = pos;
        addDataSlots(data);
    }

    /** Client-side ctor — placeholder data, server syncs via addDataSlots. */
    public AnimalBedMenu(int containerId, Inventory inv, BlockPos pos) {
        this(containerId, inv, new SimpleContainerData(DATA_COUNT), pos);
    }

    public int getClusterSize()        { return data.get(DATA_CLUSTER_SIZE); }
    public int getWaterMb()            { return data.get(DATA_WATER_MB); }
    public int getWaterCap()           { return data.get(DATA_WATER_CAP); }
    public int getOutFluidMb()         { return data.get(DATA_OUT_FLUID_MB); }
    public int getOutFluidCap()        { return data.get(DATA_OUT_FLUID_CAP); }
    public int getOutFluidId()         { return data.get(DATA_OUT_FLUID_ID); }
    public int getStallsOccupied()     { return data.get(DATA_STALLS_OCCUPIED); }
    public int getStallsTotal()        { return data.get(DATA_STALLS_TOTAL); }
    public int getSpeciesId(int i)     { return data.get(DATA_SPECIES_BASE + i * 2); }
    public int getSpeciesCount(int i)  { return data.get(DATA_SPECIES_BASE + i * 2 + 1); }
    public boolean isFoodMissing(int i){ return (data.get(DATA_FOOD_MISSING_MASK) & (1 << i)) != 0; }
    public int getOutputItemId(int i)  { return data.get(DATA_OUTPUT_BASE + i * 2); }
    public int getOutputRate(int i)    { return data.get(DATA_OUTPUT_BASE + i * 2 + 1); }
    public int getOutputOverflow()     { return data.get(DATA_OUTPUT_OVERFLOW); }
    public int getWaterRateMbPerMin()    { return data.get(DATA_WATER_RATE); }
    public int getOutFluidRateMbPerMin() { return data.get(DATA_OUT_FLUID_RATE); }

    public BlockPos getPos() { return pos; }

    @Override
    public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64.0;
    }
}
