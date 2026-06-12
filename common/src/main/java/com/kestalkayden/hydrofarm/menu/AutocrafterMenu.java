package com.kestalkayden.hydrofarm.menu;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.block.AutocrafterBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Autocrafter GUI: a single 3×3 ghost recipe grid → one output slot, plus an energy gauge and a
 *  redstone-mode button. The ghost slots are count-1 markers (the item-pump pattern). Ingredients
 *  are pulled just-in-time from an adjacent inventory, so there's no visible input grid. The output
 *  slot shows the real crafted result, or — when empty — a faded preview of what the recipe yields,
 *  fed by a hidden preview slot the server keeps in sync. */
public class AutocrafterMenu extends AbstractContainerMenu {

    public static final int GRID_SIZE = AutocrafterBlockEntity.GRID_SIZE;

    public static final int TEMPLATE_START = 0;
    public static final int TEMPLATE_END   = TEMPLATE_START + GRID_SIZE; // 9
    public static final int OUTPUT_SLOT      = TEMPLATE_END;               // 9
    public static final int PREVIEW_SLOT     = OUTPUT_SLOT + 1;            // 10
    public static final int INPUT_SYNC_START = PREVIEW_SLOT + 1;           // 11
    public static final int INPUT_SYNC_END   = INPUT_SYNC_START + GRID_SIZE; // 20
    public static final int PLAYER_START     = INPUT_SYNC_END;             // 20

    public static final int BUTTON_CYCLE_MODE = 0;

    public static final int DATA_ENERGY   = 0;
    public static final int DATA_CAPACITY = 1;
    public static final int DATA_MODE     = 2;
    public static final int DATA_SIZE     = 3;

    // Slot geometry (shared with the screen).
    public static final int TEMPLATE_X = 18;
    public static final int GRID_Y     = 18;
    public static final int OUTPUT_X   = 112;
    public static final int OUTPUT_Y   = 36;

    private final Container template;
    private final Container output;
    private final ContainerData data;
    private final BlockPos pos;
    /** Server-side BE reference (null on the client); button/craft mutations run server-side. */
    private final AutocrafterBlockEntity be;

    /** Server-side constructor. */
    public AutocrafterMenu(int containerId, Inventory playerInv, AutocrafterBlockEntity be) {
        this(containerId, playerInv, be, be.templateContainer, be.outputContainer, be.previewContainer,
             be.inputContainer, be.getBlockPos(), buildServerData(be));
    }

    /** Client-side constructor (the menu type ships us the BlockPos). */
    public AutocrafterMenu(int containerId, Inventory playerInv, BlockPos pos) {
        this(containerId, playerInv, null, new SimpleContainer(GRID_SIZE), new SimpleContainer(1),
             new SimpleContainer(1), new SimpleContainer(GRID_SIZE), pos, new SimpleContainerData(DATA_SIZE));
    }

    private AutocrafterMenu(int containerId, Inventory playerInv, AutocrafterBlockEntity be,
                            Container template, Container output, Container preview, Container input,
                            BlockPos pos, ContainerData data) {
        super(HydrofarmRefs.AUTOCRAFTER_MENU.get(), containerId);
        this.be = be;
        this.template = template;
        this.output = output;
        this.pos = pos;
        this.data = data;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                addSlot(new GhostSlot(template, col + row * 3, TEMPLATE_X + col * 18, GRID_Y + row * 18));
            }
        }
        addSlot(new OutputSlot(output, 0, OUTPUT_X, OUTPUT_Y));
        // Hidden, off-screen sync channels: the faded preview, then the buffered input counts the
        // screen overlays on the recipe grid. Never rendered or clickable.
        addSlot(new GhostSlot(preview, 0, -9000, -9000));
        for (int i = 0; i < GRID_SIZE; i++) {
            addSlot(new GhostSlot(input, i, -9000, -9000));
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 96 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 156));
        }

        addDataSlots(data);
    }

    private static ContainerData buildServerData(AutocrafterBlockEntity be) {
        return new ContainerData() {
            @Override public int get(int i) {
                return switch (i) {
                    case DATA_ENERGY -> be.getEnergyStored();
                    case DATA_CAPACITY -> be.getEnergyCapacity();
                    case DATA_MODE -> be.getRedstoneMode();
                    default -> 0;
                };
            }
            @Override public void set(int i, int v) {}
            @Override public int getCount() { return DATA_SIZE; }
        };
    }

    public int getEnergyStored()      { return data.get(DATA_ENERGY); }
    public int getEnergyCapacity()    { return Math.max(1, data.get(DATA_CAPACITY)); }
    public int getRedstoneMode()      { return data.get(DATA_MODE); }
    public BlockPos getPos()          { return pos; }
    /** The synced faded-preview stack (empty when no valid recipe). */
    public ItemStack getPreviewStack() { return slots.get(PREVIEW_SLOT).getItem(); }
    /** Buffered ingredient stack in input slot {@code i} (synced) — the screen overlays its count. */
    public ItemStack getInputStack(int i) { return slots.get(INPUT_SYNC_START + i).getItem(); }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (be == null) return super.clickMenuButton(player, id);
        if (id == BUTTON_CYCLE_MODE) { be.cycleRedstoneMode(); return true; }
        return super.clickMenuButton(player, id);
    }

    @Override
    public boolean stillValid(Player player) {
        return template.stillValid(player);
    }

    @Override
    public void clicked(int slotId, int dragType, ContainerInput inputType, Player player) {
        if (slotId >= TEMPLATE_START && slotId < TEMPLATE_END
                && (inputType == ContainerInput.PICKUP || inputType == ContainerInput.QUICK_MOVE)) {
            Slot slot = slots.get(slotId);
            ItemStack carried = getCarried();
            slot.set(carried.isEmpty() ? ItemStack.EMPTY : carried.copyWithCount(1));
            broadcastChanges();
            return;
        }
        super.clicked(slotId, dragType, inputType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Ghost template: shift-click clears the marker.
        if (index >= TEMPLATE_START && index < TEMPLATE_END) {
            slots.get(index).set(ItemStack.EMPTY);
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

        // Output → player inventory. (Player inventory has nowhere to shift to — no visible input.)
        if (index == OUTPUT_SLOT) {
            ItemStack stack = slot.getItem();
            ItemStack original = stack.copy();
            if (!moveItemStackTo(stack, PLAYER_START, slots.size(), true)) return ItemStack.EMPTY;
            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();
            if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
            slot.onTake(player, stack);
            return original;
        }
        return ItemStack.EMPTY;
    }

    /** Ghost slot: count-1 markers; vanilla input handlers do nothing (custom handling in clicked). */
    private static class GhostSlot extends Slot {
        GhostSlot(Container container, int slot, int x, int y) { super(container, slot, x, y); }
        @Override public boolean mayPlace(ItemStack stack) { return false; }
        @Override public boolean mayPickup(Player player) { return false; }
    }

    /** Output slot: extract-only — the autocrafter fills it; the player/automation only pulls. */
    private static class OutputSlot extends Slot {
        OutputSlot(Container container, int slot, int x, int y) { super(container, slot, x, y); }
        @Override public boolean mayPlace(ItemStack stack) { return false; }
    }
}
