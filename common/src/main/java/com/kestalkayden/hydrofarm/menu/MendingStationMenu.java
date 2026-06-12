package com.kestalkayden.hydrofarm.menu;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.block.MendingStationBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Mending Station GUI: a repair-in slot (damaged gear), a repaired-out slot, a Bottle o' Enchanting
 *  fuel slot + glass-bottle output, and synced XP / energy levels for the gauges. */
public class MendingStationMenu extends AbstractContainerMenu {

    public static final int SLOT_COUNT = MendingStationBlockEntity.SLOT_COUNT;

    public static final int DATA_XP     = 0;
    public static final int DATA_ENERGY = 1;
    public static final int DATA_SIZE   = 2;

    public static final int XP_CAPACITY     = MendingStationBlockEntity.XP_CAPACITY_MB;
    public static final int ENERGY_CAPACITY = MendingStationBlockEntity.ENERGY_CAPACITY;

    private final Container container;
    private final ContainerData data;
    private final BlockPos pos;

    /** Server-side constructor. */
    public MendingStationMenu(int containerId, Inventory playerInv, MendingStationBlockEntity be) {
        this(containerId, playerInv, be.items, be.getBlockPos(), buildServerData(be));
    }

    /** Client-side constructor. */
    public MendingStationMenu(int containerId, Inventory playerInv, BlockPos pos) {
        this(containerId, playerInv, new SimpleContainer(SLOT_COUNT), pos, new SimpleContainerData(DATA_SIZE));
    }

    private MendingStationMenu(int containerId, Inventory playerInv, Container container, BlockPos pos, ContainerData data) {
        super(HydrofarmRefs.MENDING_STATION_MENU.get(), containerId);
        this.container = container;
        this.pos = pos;
        this.data = data;

        addSlot(new RepairInSlot(container, MendingStationBlockEntity.SLOT_REPAIR_IN, 44, 35));
        addSlot(new OutputSlot(container, MendingStationBlockEntity.SLOT_REPAIR_OUT, 116, 35));

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 142));
        }

        addDataSlots(data);
    }

    private static ContainerData buildServerData(MendingStationBlockEntity be) {
        return new ContainerData() {
            @Override public int get(int i) {
                return switch (i) {
                    case DATA_XP -> be.getXpStored();
                    case DATA_ENERGY -> be.getEnergyStored();
                    default -> 0;
                };
            }
            @Override public void set(int i, int v) { }
            @Override public int getCount() { return DATA_SIZE; }
        };
    }

    public int getXpStored()     { return data.get(DATA_XP); }
    public int getEnergyStored() { return data.get(DATA_ENERGY); }
    public BlockPos getPos()     { return pos; }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        int invStart = SLOT_COUNT;
        int invEnd = SLOT_COUNT + 36;
        if (index < invStart) {
            // Station slot → player inventory.
            if (!moveItemStackTo(stack, invStart, invEnd, true)) return ItemStack.EMPTY;
        } else {
            // Player inventory → the repair slot (damaged gear only), else nothing.
            if (!moveItemStackTo(stack, MendingStationBlockEntity.SLOT_REPAIR_IN, MendingStationBlockEntity.SLOT_REPAIR_IN + 1, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        return copy;
    }

    private static class RepairInSlot extends Slot {
        RepairInSlot(Container c, int i, int x, int y) { super(c, i, x, y); }
        @Override public boolean mayPlace(ItemStack s) { return s.isDamageableItem() && s.isDamaged(); }
        @Override public int getMaxStackSize() { return 1; }
    }

    private static class OutputSlot extends Slot {
        OutputSlot(Container c, int i, int x, int y) { super(c, i, x, y); }
        @Override public boolean mayPlace(ItemStack s) { return false; }
    }
}
