package com.kestalkayden.hydrofarm.client;

import com.kestalkayden.hydrofarm.menu.HydroponicsBedMenu;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Hydroponics cluster stat panel — no slots, no inventory, programmatic background.
 *  Layout: title row → water gauge + analytical consumption rate → XP gauge + rate →
 *  planter aggregate counts → top-5 actual harvest rates from the rolling 60s window.
 *  Shared gauge/panel style lives in {@link AbstractBedStatScreen}. */
public class HydroponicsBedScreen extends AbstractBedStatScreen<HydroponicsBedMenu> {

    private static final int GUI_W = 220;
    private static final int GUI_H = 168;

    private static final int Y_WATER_BAR  = 22;
    private static final int Y_WATER_RATE = 32;
    private static final int Y_XP_BAR     = 48;
    private static final int Y_XP_RATE    = 58;
    private static final int Y_PLANTERS_1 = 74;
    private static final int Y_PLANTERS_2 = 84;
    private static final int Y_OUTPUT_HDR = 100;
    private static final int Y_OUTPUT_ROW = 110;
    private static final int OUTPUT_ROW_H = 10;
    /** Height of the XP gauge block (bar + rate row) — everything below shifts up by this and the
     *  panel shrinks by it when the bed's XP output is config-disabled (synced from the server). */
    private static final int XP_BLOCK_H   = Y_PLANTERS_1 - Y_XP_BAR;

    public HydroponicsBedScreen(HydroponicsBedMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, GUI_W, GUI_H);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        boolean xp = menu.isXpEnabled();
        drawPanel(g, GUI_W, xp ? GUI_H : GUI_H - XP_BLOCK_H);
        drawBar(g, leftPos + BAR_X, topPos + Y_WATER_BAR, BAR_W, BAR_H, menu.getWaterMb(), menu.getWaterCap(), BAR_WATER);
        if (xp) {
            drawBar(g, leftPos + BAR_X, topPos + Y_XP_BAR, BAR_W, BAR_H, menu.getXpMb(), menu.getXpCap(), BAR_XP);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // Title — "Hydroponics Cluster (N beds)"
        int clusterSize = menu.getClusterSize();
        String title = clusterSize <= 1
            ? Component.translatable("block.hydrofarm.hydroponics_bed").getString()
            : Component.translatable("gui.hydrofarm.bed.cluster_title", clusterSize).getString();
        g.text(font, title, 8, 6, LABEL_COLOR, false);

        // Water row
        g.text(font, Component.translatable("gui.hydrofarm.bed.water").getString(),
               8, Y_WATER_BAR + 1, LABEL_COLOR, false);
        String waterValue = formatGauge(menu.getWaterMb(), menu.getWaterCap());
        g.text(font, waterValue, BAR_X + BAR_W + 4, Y_WATER_BAR + 1, VALUE_COLOR, false);
        String waterRate = "↓ " + menu.getWaterRateMbPerMin() + " mB/min";
        g.text(font, waterRate, BAR_X, Y_WATER_RATE, SUBTLE_COLOR, false);

        // XP row — hidden entirely (and the rows below shift up) when the server reports the
        // bed type's XP output as config-disabled.
        boolean xp = menu.isXpEnabled();
        int dy = xp ? 0 : -XP_BLOCK_H;
        if (xp) {
            g.text(font, Component.translatable("gui.hydrofarm.bed.xp").getString(),
                   8, Y_XP_BAR + 1, LABEL_COLOR, false);
            String xpValue = formatGauge(menu.getXpMb(), menu.getXpCap());
            g.text(font, xpValue, BAR_X + BAR_W + 4, Y_XP_BAR + 1, VALUE_COLOR, false);
            String xpRate = "↑ " + menu.getXpRateMbPerMin() + " mB/min";
            g.text(font, xpRate, BAR_X, Y_XP_RATE, SUBTLE_COLOR, false);
        }

        // Planter row 1
        int installed = menu.getPlantersInstalled();
        int configured = menu.getPlantersConfigured();
        g.text(font,
               Component.translatable("gui.hydrofarm.bed.planters",
                   configured + "/" + installed).getString(),
               8, Y_PLANTERS_1 + dy, LABEL_COLOR, false);

        // Planter row 2 — growing + ready (ready in highlight if any)
        int growing = menu.getPlantersGrowing();
        int ready = menu.getPlantersReady();
        String growingStr = growing + " growing";
        g.text(font, growingStr, 16, Y_PLANTERS_2 + dy, SUBTLE_COLOR, false);
        int growingW = font.width(growingStr);
        g.text(font, " • ", 16 + growingW, Y_PLANTERS_2 + dy, SUBTLE_COLOR, false);
        int sepW = font.width(" • ");
        g.text(font, ready + " ready", 16 + growingW + sepW, Y_PLANTERS_2 + dy,
               ready > 0 ? READY_COLOR : SUBTLE_COLOR, false);

        // Output header
        g.text(font, Component.translatable("gui.hydrofarm.bed.output").getString(),
               8, Y_OUTPUT_HDR + dy, LABEL_COLOR, false);

        // Top crops
        int shownRows = 0;
        for (int i = 0; i < HydroponicsBedMenu.CROP_SLOT_COUNT; i++) {
            int itemId = menu.getCropItemId(i);
            int rate = menu.getCropRate(i);
            if (itemId == 0 || rate <= 0) continue;
            Item item = BuiltInRegistries.ITEM.byId(itemId);
            if (item == Items.AIR) continue;
            int rowY = Y_OUTPUT_ROW + dy + shownRows * OUTPUT_ROW_H;
            // Icon
            ItemStack stack = new ItemStack(item);
            g.item(stack, 8, rowY - 4);
            // Name
            String name = stack.getHoverName().getString();
            g.text(font, name, 26, rowY, VALUE_COLOR, false);
            // Right-aligned rate
            String rateStr = String.valueOf(rate);
            int rateX = GUI_W - 12 - font.width(rateStr);
            g.text(font, rateStr, rateX, rowY, VALUE_COLOR, false);
            shownRows++;
        }
        if (shownRows == 0) {
            g.text(font, Component.translatable("gui.hydrofarm.bed.no_output")
                            .withStyle(ChatFormatting.ITALIC).getString(),
                   16, Y_OUTPUT_ROW + dy, SUBTLE_COLOR, false);
        }
        int overflow = menu.getCropOverflow();
        if (overflow > 0) {
            int rowY = Y_OUTPUT_ROW + dy + shownRows * OUTPUT_ROW_H;
            g.text(font, "+ " + overflow + " more", 16, rowY, SUBTLE_COLOR, false);
        }
    }
}
