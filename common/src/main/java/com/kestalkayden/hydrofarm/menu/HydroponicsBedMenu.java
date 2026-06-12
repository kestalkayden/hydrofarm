package com.kestalkayden.hydrofarm.menu;

import com.kestalkayden.hydrofarm.HydrofarmRefs;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/** Hydroponics cluster stats menu. No slots, no player inventory — pure cluster stat panel.
 *  Hopper/pump access to the bed inventory goes through the separately-registered cluster
 *  ItemStorage on the BE, not through this menu. */
public class HydroponicsBedMenu extends AbstractContainerMenu {

    /** Number of crops shown in the "Output" panel. Beyond this count is collapsed into
     *  CROP_OVERFLOW. */
    public static final int CROP_SLOT_COUNT = 5;

    public static final int DATA_WATER_MB             = 0;
    public static final int DATA_WATER_CAP            = 1;
    public static final int DATA_XP_MB                = 2;
    public static final int DATA_XP_CAP               = 3;
    public static final int DATA_CLUSTER_SIZE         = 4;
    public static final int DATA_PLANTERS_INSTALLED   = 5;
    public static final int DATA_PLANTERS_CONFIGURED  = 6;
    public static final int DATA_PLANTERS_GROWING     = 7;
    public static final int DATA_PLANTERS_READY       = 8;
    public static final int DATA_WATER_RATE           = 9;
    public static final int DATA_XP_RATE              = 10;
    /** Pairs of (item registry id, items/min) — 5 crops × 2 ints = 10 slots starting here. */
    public static final int DATA_CROP_ITEM_BASE       = 11;
    public static final int DATA_CROP_OVERFLOW        = 21;
    /** 1 when the bed type's XP output is config-enabled; 0 hides the XP gauge (server decides). */
    public static final int DATA_XP_ENABLED           = 22;

    public static final int DATA_COUNT = 23;

    private final ContainerData data;
    private final BlockPos pos;

    /** Server-side ctor — wired with the real ContainerData snapshot. */
    public HydroponicsBedMenu(int containerId, Inventory inv, ContainerData data, BlockPos pos) {
        super(HydrofarmRefs.HYDROPONICS_BED_MENU.get(), containerId);
        checkContainerDataCount(data, DATA_COUNT);
        this.data = data;
        this.pos = pos;
        addDataSlots(data);
    }

    /** Client-side ctor — uses a placeholder ContainerData; server syncs values via addDataSlots. */
    public HydroponicsBedMenu(int containerId, Inventory inv, BlockPos pos) {
        this(containerId, inv, new SimpleContainerData(DATA_COUNT), pos);
    }

    public int getWaterMb()            { return data.get(DATA_WATER_MB); }
    public int getWaterCap()           { return data.get(DATA_WATER_CAP); }
    public int getXpMb()               { return data.get(DATA_XP_MB); }
    public int getXpCap()              { return data.get(DATA_XP_CAP); }
    public int getClusterSize()        { return data.get(DATA_CLUSTER_SIZE); }
    public int getPlantersInstalled()  { return data.get(DATA_PLANTERS_INSTALLED); }
    public int getPlantersConfigured() { return data.get(DATA_PLANTERS_CONFIGURED); }
    public int getPlantersGrowing()    { return data.get(DATA_PLANTERS_GROWING); }
    public int getPlantersReady()      { return data.get(DATA_PLANTERS_READY); }
    public int getWaterRateMbPerMin()  { return data.get(DATA_WATER_RATE); }
    public int getXpRateMbPerMin()     { return data.get(DATA_XP_RATE); }
    public int getCropItemId(int i)    { return data.get(DATA_CROP_ITEM_BASE + i * 2); }
    public int getCropRate(int i)      { return data.get(DATA_CROP_ITEM_BASE + i * 2 + 1); }
    public int getCropOverflow()       { return data.get(DATA_CROP_OVERFLOW); }
    public boolean isXpEnabled()       { return data.get(DATA_XP_ENABLED) != 0; }

    public BlockPos getPos() { return pos; }

    @Override
    public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64.0;
    }
}
