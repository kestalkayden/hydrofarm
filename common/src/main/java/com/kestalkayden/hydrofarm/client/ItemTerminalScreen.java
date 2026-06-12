package com.kestalkayden.hydrofarm.client;

import com.kestalkayden.hydrofarm.menu.ItemTerminalMenu;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** Item pipe terminal GUI screen — the per-face filter editor. Same programmatic background as the
 *  old item pump screen; the mode button now toggles EXTRACT/INSERT for the configured face. */
public class ItemTerminalScreen extends AbstractContainerScreen<ItemTerminalMenu> {

    private static final int GUI_W = 176;
    private static final int GUI_H = 166;

    private static final int LABEL_FILTER_Y = ItemTerminalMenu.ROW_FILTER_Y - 10;

    private static final int BG_GRAY      = 0xFFC6C6C6;
    private static final int BG_BORDER    = 0xFF555555;
    private static final int SLOT_BEVEL_DARK  = 0xFF373737;
    private static final int SLOT_BEVEL_LIGHT = 0xFFFFFFFF;
    private static final int FILTER_INNER     = 0xFF7B8FB0;
    private static final int LABEL_COLOR      = 0xFF404040;

    private static final int BTN_W = 50;
    private static final int BTN_H = 20;
    private static final int BTN_X = 120;
    private static final int BTN_MODE_Y = ItemTerminalMenu.ROW_FILTER_Y - 1;
    private static final int BTN_WL_Y   = BTN_MODE_Y + 22;

    private Button modeButton;
    private Button whitelistButton;

    public ItemTerminalScreen(ItemTerminalMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title, GUI_W, GUI_H);
        this.inventoryLabelY = GUI_H - 94 + 2;
    }

    @Override
    protected void init() {
        super.init();
        this.modeButton = Button.builder(getModeLabel(), b -> {
                if (minecraft != null && minecraft.gameMode != null) {
                    minecraft.gameMode.handleInventoryButtonClick(
                        menu.containerId, ItemTerminalMenu.BUTTON_TOGGLE_MODE);
                }
            })
            .bounds(leftPos + BTN_X, topPos + BTN_MODE_Y, BTN_W, BTN_H)
            .build();
        addRenderableWidget(modeButton);

        this.whitelistButton = Button.builder(getWhitelistLabel(), b -> {
                if (minecraft != null && minecraft.gameMode != null) {
                    minecraft.gameMode.handleInventoryButtonClick(
                        menu.containerId, ItemTerminalMenu.BUTTON_TOGGLE_WHITELIST);
                }
            })
            .bounds(leftPos + BTN_X, topPos + BTN_WL_Y, BTN_W, BTN_H)
            .build();
        addRenderableWidget(whitelistButton);
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
            int sx = x + slot.x - 1;
            int sy = y + slot.y - 1;
            boolean isFilter = slot.index < ItemTerminalMenu.FILTER_SLOT_END;
            drawSlotWell(g, sx, sy, 18, 18, isFilter ? FILTER_INNER : 0xFF8B8B8B);
        }
    }

    private static void drawSlotWell(GuiGraphicsExtractor g, int wx, int wy, int ww, int wh, int innerColor) {
        g.fill(wx, wy, wx + ww, wy + 1, SLOT_BEVEL_DARK);
        g.fill(wx, wy + wh - 1, wx + ww, wy + wh, SLOT_BEVEL_LIGHT);
        g.fill(wx, wy + 1, wx + 1, wy + wh - 1, SLOT_BEVEL_DARK);
        g.fill(wx + ww - 1, wy + 1, wx + ww, wy + wh - 1, SLOT_BEVEL_LIGHT);
        g.fill(wx + 1, wy + 1, wx + ww - 1, wy + wh - 1, innerColor);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        g.text(font, Component.translatable("gui.hydrofarm.terminal.filter").getString(),
               8, LABEL_FILTER_Y, LABEL_COLOR, false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (modeButton != null) modeButton.setMessage(getModeLabel());
        if (whitelistButton != null) whitelistButton.setMessage(getWhitelistLabel());
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private Component getModeLabel() {
        // Color matches the nozzle: orange-ish (gold) = extract (pulling), aqua = insert (pushing).
        if (menu.isInsertMode()) {
            return Component.translatable("gui.hydrofarm.terminal.insert").withStyle(ChatFormatting.AQUA);
        }
        return Component.translatable("gui.hydrofarm.terminal.extract").withStyle(ChatFormatting.GOLD);
    }

    private Component getWhitelistLabel() {
        if (menu.isWhitelist()) {
            return Component.translatable("gui.hydrofarm.terminal.whitelist").withStyle(ChatFormatting.GREEN);
        }
        return Component.translatable("gui.hydrofarm.terminal.blacklist").withStyle(ChatFormatting.RED);
    }
}
