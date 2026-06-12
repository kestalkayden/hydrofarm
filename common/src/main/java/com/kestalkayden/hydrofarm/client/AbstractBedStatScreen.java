package com.kestalkayden.hydrofarm.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Shared scaffolding for the bed stat panels (hydroponics/tree + husbandry/butcher): the
 *  programmatic gray panel, the gauge-bar drawing, the colour palette, and the compact number
 *  formatting — so every bed GUI keeps the same look from one place. Concrete screens supply their
 *  own {@code extractBackground}/{@code extractLabels} content. */
public abstract class AbstractBedStatScreen<T extends AbstractContainerMenu>
        extends AbstractContainerScreen<T> {

    protected static final int BG_GRAY      = 0xFFC6C6C6;
    protected static final int BG_BORDER    = 0xFF555555;
    protected static final int BAR_BG       = 0xFF373737;
    protected static final int BAR_BG_LIGHT = 0xFFFFFFFF;
    protected static final int BAR_WATER    = 0xFF3D7BD9;
    protected static final int BAR_XP       = 0xFF55D080;
    protected static final int BAR_MILK     = 0xFFEDE6D6;
    protected static final int LABEL_COLOR  = 0xFF404040;
    protected static final int SUBTLE_COLOR = 0xFF707070;
    protected static final int VALUE_COLOR  = 0xFF202020;
    protected static final int READY_COLOR  = 0xFFB07000;
    protected static final int WARN_COLOR   = 0xFFB03030;

    protected static final int BAR_X = 50;
    protected static final int BAR_W = 90;
    protected static final int BAR_H = 8;

    protected AbstractBedStatScreen(T menu, Inventory inv, Component title, int w, int h) {
        super(menu, inv, title, w, h);
        // Suppress the vanilla title + inventory labels — these panels draw their own.
        this.titleLabelY = -9999;
        this.inventoryLabelY = -9999;
    }

    /** Fills the gray panel + 1px border for a w×h box at the screen's top-left. */
    protected void drawPanel(GuiGraphicsExtractor g, int w, int h) {
        int x = leftPos, y = topPos;
        g.fill(x, y, x + w, y + h, BG_GRAY);
        g.fill(x, y, x + w, y + 1, BG_BORDER);
        g.fill(x, y + h - 1, x + w, y + h, BG_BORDER);
        g.fill(x, y, x + 1, y + h, BG_BORDER);
        g.fill(x + w - 1, y, x + w, y + h, BG_BORDER);
    }

    /** Dark-tracked gauge bar with a beveled edge and a clamped colored fill. */
    protected static void drawBar(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                   int value, int cap, int fillColor) {
        g.fill(x, y, x + w, y + h, BAR_BG);
        g.fill(x, y, x + w, y + 1, BAR_BG);
        g.fill(x, y + h - 1, x + w, y + h, BAR_BG_LIGHT);
        if (cap > 0 && value > 0) {
            int filledW = Math.min(w - 2, Math.max(1, (int) ((long) (w - 2) * value / cap)));
            g.fill(x + 1, y + 1, x + 1 + filledW, y + h - 1, fillColor);
        }
    }

    protected static String formatGauge(int value, int cap) {
        return shortNum(value) + " / " + shortNum(cap);
    }

    /** Compact formatter for narrow gauges: under 1000 exact, under 1M as "12k", else "1.2M". */
    protected static String shortNum(int n) {
        if (n < 1000) return Integer.toString(n);
        if (n < 1_000_000) return (n / 1000) + "k";
        return String.format("%.1fM", n / 1_000_000.0);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
