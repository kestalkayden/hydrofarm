package com.kestalkayden.hydrofarm.client;

import com.kestalkayden.hydrofarm.menu.MendingStationMenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** Mending Station GUI. Programmatic background (no PNG), four slot wells (repair in/out + bottle
 *  in/out), and two vertical gauges — green Liquid XP and orange energy — with hover tooltips. */
public class MendingStationScreen extends AbstractContainerScreen<MendingStationMenu> {

    private static final int GUI_W = 176;
    private static final int GUI_H = 166;

    private static final int BG_GRAY      = 0xFFC6C6C6;
    private static final int BG_BORDER    = 0xFF555555;
    private static final int SLOT_BEVEL_DARK  = 0xFF373737;
    private static final int SLOT_BEVEL_LIGHT = 0xFFFFFFFF;
    private static final int SLOT_INNER       = 0xFF8B8B8B;
    private static final int LABEL_COLOR      = 0xFF404040;

    private static final int GAUGE_W = 12;
    private static final int GAUGE_H = 48;
    private static final int GAUGE_Y = 26;
    private static final int XP_GAUGE_X     = 74;
    private static final int ENERGY_GAUGE_X = 90;
    private static final int GAUGE_BG    = 0xFF2A2A2A;
    private static final int XP_FILL     = 0xFF59C24B;   // green
    private static final int ENERGY_FILL = 0xFFE0A030;   // orange

    public MendingStationScreen(MendingStationMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title, GUI_W, GUI_H);
        this.inventoryLabelY = GUI_H - 94 + 2;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = leftPos;
        int y = topPos;
        g.fill(x, y, x + GUI_W, y + GUI_H, BG_GRAY);
        g.fill(x, y, x + GUI_W, y + 1, BG_BORDER);
        g.fill(x, y + GUI_H - 1, x + GUI_W, y + GUI_H, BG_BORDER);
        g.fill(x, y, x + 1, y + GUI_H, BG_BORDER);
        g.fill(x + GUI_W - 1, y, x + GUI_W, y + GUI_H, BG_BORDER);

        for (Slot slot : menu.slots) {
            drawSlotWell(g, x + slot.x - 1, y + slot.y - 1);
        }

        drawGauge(g, x + XP_GAUGE_X, y + GAUGE_Y, menu.getXpStored(), MendingStationMenu.XP_CAPACITY, XP_FILL);
        drawGauge(g, x + ENERGY_GAUGE_X, y + GAUGE_Y, menu.getEnergyStored(), MendingStationMenu.ENERGY_CAPACITY, ENERGY_FILL);

        // Hover tooltips on the two gauges.
        if (overGauge(mouseX, mouseY, x + XP_GAUGE_X, y + GAUGE_Y)) {
            g.setTooltipForNextFrame(Component.translatable("gui.hydrofarm.mending.xp",
                menu.getXpStored(), MendingStationMenu.XP_CAPACITY), mouseX, mouseY);
        } else if (overGauge(mouseX, mouseY, x + ENERGY_GAUGE_X, y + GAUGE_Y)) {
            g.setTooltipForNextFrame(Component.translatable("gui.hydrofarm.mending.energy",
                menu.getEnergyStored(), MendingStationMenu.ENERGY_CAPACITY), mouseX, mouseY);
        }
    }

    private static boolean overGauge(int mx, int my, int gx, int gy) {
        return mx >= gx && mx < gx + GAUGE_W && my >= gy && my < gy + GAUGE_H;
    }

    private static void drawGauge(GuiGraphicsExtractor g, int gx, int gy, int value, int cap, int fill) {
        g.fill(gx, gy, gx + GAUGE_W, gy + GAUGE_H, GAUGE_BG);
        int h = cap <= 0 ? 0 : Math.round((float) GAUGE_H * Math.min(value, cap) / cap);
        if (h > 0) g.fill(gx, gy + GAUGE_H - h, gx + GAUGE_W, gy + GAUGE_H, fill);
    }

    private static void drawSlotWell(GuiGraphicsExtractor g, int wx, int wy) {
        int ww = 18, wh = 18;
        g.fill(wx, wy, wx + ww, wy + 1, SLOT_BEVEL_DARK);
        g.fill(wx, wy + wh - 1, wx + ww, wy + wh, SLOT_BEVEL_LIGHT);
        g.fill(wx, wy + 1, wx + 1, wy + wh - 1, SLOT_BEVEL_DARK);
        g.fill(wx + ww - 1, wy + 1, wx + ww, wy + wh - 1, SLOT_BEVEL_LIGHT);
        g.fill(wx + 1, wy + 1, wx + ww - 1, wy + wh - 1, SLOT_INNER);
    }
}
