package com.kestalkayden.hydrofarm.client;

import com.kestalkayden.hydrofarm.block.AutocrafterBlockEntity;
import com.kestalkayden.hydrofarm.menu.AutocrafterMenu;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Autocrafter GUI screen — solid-colour background drawn programmatically (no PNG). A single 3×3
 *  ghost recipe grid points (→) at one output slot; when that slot is empty the recipe's result is
 *  flagged there as a faded preview. A vertical gauge on the right shows buffered energy (hover for
 *  the exact value); a button below cycles the redstone mode. */
public class AutocrafterScreen extends AbstractContainerScreen<AutocrafterMenu> {

    private static final int GUI_W = 176;
    private static final int GUI_H = 176;

    private static final int BG_GRAY      = 0xFFC6C6C6;
    private static final int BG_BORDER    = 0xFF555555;
    private static final int SLOT_BEVEL_DARK  = 0xFF373737;
    private static final int SLOT_BEVEL_LIGHT = 0xFFFFFFFF;
    private static final int TEMPLATE_INNER   = 0xFF7B8FB0;
    private static final int SLOT_INNER       = 0xFF8B8B8B;
    private static final int LABEL_COLOR      = 0xFF404040;
    private static final int ARROW_COLOR      = 0xFF555555;
    private static final int PREVIEW_FADE      = 0x99C6C6C6;   // translucent panel-gray over the ghost result

    private static final int ENERGY_X = 150;
    private static final int ENERGY_Y = AutocrafterMenu.GRID_Y;
    private static final int ENERGY_W = 14;
    private static final int ENERGY_H = 54;
    private static final int ENERGY_EMPTY = 0xFF3A3A3A;
    private static final int ENERGY_FULL  = 0xFFE03A2E;

    private static final int BTN_X = 8;
    private static final int BTN_Y = 74;
    private static final int BTN_W = 160;
    private static final int BTN_H = 18;

    private Button modeButton;

    public AutocrafterScreen(AutocrafterMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title, GUI_W, GUI_H);
    }

    @Override
    protected void init() {
        super.init();
        this.modeButton = Button.builder(getModeLabel(), b -> {
                if (minecraft != null && minecraft.gameMode != null) {
                    minecraft.gameMode.handleInventoryButtonClick(
                        menu.containerId, AutocrafterMenu.BUTTON_CYCLE_MODE);
                }
            })
            .bounds(leftPos + BTN_X, topPos + BTN_Y, BTN_W, BTN_H)
            .build();
        addRenderableWidget(modeButton);
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
            if (slot.x < 0) continue;   // hidden preview sync-slot
            int sx = x + slot.x - 1;
            int sy = y + slot.y - 1;
            boolean ghost = slot.index < AutocrafterMenu.TEMPLATE_END;
            drawSlotWell(g, sx, sy, ghost ? TEMPLATE_INNER : SLOT_INNER);
        }

        // Energy gauge (fills bottom-up).
        int gx = x + ENERGY_X;
        int gy = y + ENERGY_Y;
        g.fill(gx - 1, gy - 1, gx + ENERGY_W + 1, gy + ENERGY_H + 1, BG_BORDER);
        g.fill(gx, gy, gx + ENERGY_W, gy + ENERGY_H, ENERGY_EMPTY);
        int filled = (int) ((long) ENERGY_H * menu.getEnergyStored() / menu.getEnergyCapacity());
        if (filled > 0) g.fill(gx, gy + (ENERGY_H - filled), gx + ENERGY_W, gy + ENERGY_H, ENERGY_FULL);

        if (mouseX >= gx && mouseX < gx + ENERGY_W && mouseY >= gy && mouseY < gy + ENERGY_H) {
            g.setTooltipForNextFrame(Component.translatable("gui.hydrofarm.autocrafter.energy",
                menu.getEnergyStored(), menu.getEnergyCapacity()), mouseX, mouseY);
        }
    }

    private static void drawSlotWell(GuiGraphicsExtractor g, int wx, int wy, int innerColor) {
        g.fill(wx, wy, wx + 18, wy + 1, SLOT_BEVEL_DARK);
        g.fill(wx, wy + 17, wx + 18, wy + 18, SLOT_BEVEL_LIGHT);
        g.fill(wx, wy + 1, wx + 1, wy + 17, SLOT_BEVEL_DARK);
        g.fill(wx + 17, wy + 1, wx + 18, wy + 17, SLOT_BEVEL_LIGHT);
        g.fill(wx + 1, wy + 1, wx + 17, wy + 17, innerColor);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // Title only — no "Inventory" sub-label (keeps the compact GUI uncluttered).
        g.text(font, title.getString(), 8, 6, LABEL_COLOR, false);

        // Recipe → output arrow.
        drawArrow(g, 80, AutocrafterMenu.OUTPUT_Y + 4);

        // Faded result preview in the output slot, only while it holds no real crafted item.
        ItemStack preview = menu.getPreviewStack();
        if (!preview.isEmpty() && menu.slots.get(AutocrafterMenu.OUTPUT_SLOT).getItem().isEmpty()) {
            int ox = AutocrafterMenu.OUTPUT_X;
            int oy = AutocrafterMenu.OUTPUT_Y;
            g.item(preview, ox, oy);
            g.fill(ox, oy, ox + 16, oy + 16, PREVIEW_FADE);
        }

        // Buffered ingredient counts overlaid on each recipe cell that has stock pushed/pulled in.
        for (int i = 0; i < AutocrafterMenu.GRID_SIZE; i++) {
            int count = menu.getInputStack(i).getCount();
            if (count <= 0) continue;
            int cx = AutocrafterMenu.TEMPLATE_X + (i % 3) * 18;
            int cy = AutocrafterMenu.GRID_Y + (i / 3) * 18;
            String s = Integer.toString(count);
            g.text(font, s, cx + 17 - font.width(s), cy + 9, 0xFFFFFFFF, true);
        }
    }

    /** A small right-pointing arrow: shaft + triangular head, drawn at GUI-relative coords. */
    private static void drawArrow(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x, y + 2, x + 14, y + 6, ARROW_COLOR);
        for (int k = 0; k < 5; k++) {
            g.fill(x + 13 + k, y - 1 + k, x + 14 + k, y + 9 - k, ARROW_COLOR);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (modeButton != null) modeButton.setMessage(getModeLabel());
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private Component getModeLabel() {
        Component name = switch (menu.getRedstoneMode()) {
            case AutocrafterBlockEntity.MODE_REQUIRES_SIGNAL ->
                Component.translatable("gui.hydrofarm.autocrafter.mode_requires_signal").withStyle(ChatFormatting.GREEN);
            case AutocrafterBlockEntity.MODE_DISABLED_BY_SIGNAL ->
                Component.translatable("gui.hydrofarm.autocrafter.mode_disabled_by_signal").withStyle(ChatFormatting.RED);
            default ->
                Component.translatable("gui.hydrofarm.autocrafter.mode_always").withStyle(ChatFormatting.AQUA);
        };
        return Component.translatable("gui.hydrofarm.autocrafter.mode", name);
    }
}
