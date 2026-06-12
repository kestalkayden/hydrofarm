package com.kestalkayden.hydrofarm.menu;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.block.LiquidPipeTerminalBlock;
import com.kestalkayden.hydrofarm.block.LiquidPipeTerminalBlockEntity;
import com.kestalkayden.hydrofarm.block.PipeFace;
import com.kestalkayden.hydrofarm.block.TransportNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/** Liquid pipe terminal GUI — the fluid twin of {@link ItemTerminalMenu}: a per-face fluid filter
 *  editor bound to one {@link Direction} of a {@link LiquidPipeTerminalBlockEntity}. */
public class LiquidTerminalMenu extends AbstractContainerMenu {

    public static final int FILTER_SIZE = LiquidPipeTerminalBlockEntity.FILTER_SIZE;
    public static final int FILTER_SLOT_START = 0;
    public static final int FILTER_SLOT_END   = FILTER_SIZE;

    public static final int BUTTON_TOGGLE_WHITELIST = 0;
    public static final int BUTTON_TOGGLE_MODE      = 1;

    public static final int DATA_WHITELIST = 0;
    public static final int DATA_MODE      = 1;   // 0 = EXTRACT, 1 = INSERT
    public static final int DATA_SIZE      = 2;

    public static final int ROW_X_START   = 26;
    public static final int ROW_FILTER_Y  = 30;

    private final Container filterContainer;
    private final ContainerData data;
    private final BlockPos pos;
    private final Direction face;
    private final LiquidPipeTerminalBlockEntity termBe;

    public LiquidTerminalMenu(int containerId, Inventory playerInv, LiquidPipeTerminalBlockEntity be, Direction face) {
        this(containerId, playerInv, be, face, be.filterContainer(face), be.getBlockPos(), buildServerData(be, face));
    }

    public LiquidTerminalMenu(int containerId, Inventory playerInv, TerminalMenuData data) {
        this(containerId, playerInv, null, data.face(),
            new SimpleContainer(FILTER_SIZE), data.pos(), new SimpleContainerData(DATA_SIZE));
    }

    private LiquidTerminalMenu(int containerId, Inventory playerInv, LiquidPipeTerminalBlockEntity be, Direction face,
                               Container filterContainer, BlockPos pos, ContainerData data) {
        super(HydrofarmRefs.LIQUID_TERMINAL_MENU.get(), containerId);
        this.termBe = be;
        this.face = face;
        this.filterContainer = filterContainer;
        this.pos = pos;
        this.data = data;

        for (int i = 0; i < FILTER_SIZE; i++) {
            addSlot(new FilterSlot(filterContainer, i, ROW_X_START + i * 18, ROW_FILTER_Y));
        }
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

    private static ContainerData buildServerData(LiquidPipeTerminalBlockEntity be, Direction face) {
        return new ContainerData() {
            @Override public int get(int i) {
                return switch (i) {
                    case DATA_WHITELIST -> be.isWhitelist(face) ? 1 : 0;
                    case DATA_MODE -> be.mode(face) == PipeFace.INSERT ? 1 : 0;
                    default -> 0;
                };
            }
            @Override public void set(int i, int v) {
                if (i == DATA_WHITELIST) be.setWhitelist(face, v == 1);
            }
            @Override public int getCount() { return DATA_SIZE; }
        };
    }

    public boolean isWhitelist() { return data.get(DATA_WHITELIST) == 1; }
    public boolean isInsertMode() { return data.get(DATA_MODE) == 1; }
    public BlockPos getPos()     { return pos; }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (termBe == null) return super.clickMenuButton(player, id);
        switch (id) {
            case BUTTON_TOGGLE_WHITELIST -> {
                termBe.setWhitelist(face, !termBe.isWhitelist(face));
                return true;
            }
            case BUTTON_TOGGLE_MODE -> {
                Level level = termBe.getLevel();
                if (level != null) {
                    BlockState state = level.getBlockState(pos);
                    if (state.is(HydrofarmRefs.LIQUID_PIPE_TERMINAL.get())) {
                        EnumProperty<PipeFace> prop = LiquidPipeTerminalBlock.faceProp(face);
                        PipeFace cur = state.getValue(prop);
                        PipeFace next = cur == PipeFace.INSERT ? PipeFace.EXTRACT : PipeFace.INSERT;
                        level.setBlock(pos, state.setValue(prop, next), Block.UPDATE_ALL);
                        TransportNetwork.bump();
                    }
                }
                return true;
            }
            default -> { return super.clickMenuButton(player, id); }
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return filterContainer.stillValid(player);
    }

    @Override
    public void clicked(int slotId, int dragType, ContainerInput input, Player player) {
        if (slotId >= FILTER_SLOT_START && slotId < FILTER_SLOT_END
                && (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE)) {
            Slot slot = slots.get(slotId);
            ItemStack carried = getCarried();
            slot.set(carried.isEmpty() ? ItemStack.EMPTY : carried.copyWithCount(1));
            broadcastChanges();
            return;
        }
        super.clicked(slotId, dragType, input, player);
    }

    private static class FilterSlot extends Slot {
        FilterSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }
        @Override public boolean mayPlace(ItemStack stack) { return false; }
        @Override public boolean mayPickup(Player player) { return false; }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        if (index < FILTER_SLOT_END) slot.set(ItemStack.EMPTY);
        return ItemStack.EMPTY;
    }
}
