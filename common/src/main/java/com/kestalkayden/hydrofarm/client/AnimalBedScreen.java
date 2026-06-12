package com.kestalkayden.hydrofarm.client;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.block.ButcherAnimal;
import com.kestalkayden.hydrofarm.block.HusbandryAnimal;
import com.kestalkayden.hydrofarm.menu.AnimalBedMenu;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;

/** Cluster stat panel for husbandry/butcher beds — the same gauge look as the hydroponics bed:
 *  a water gauge (a real input — animals drink) and an output-fluid gauge (milk for husbandry,
 *  liquid XP for butcher), each with its analytical mB/min rate, then installed species (with a
 *  food-shortage warning) and output items per minute. Shared gauge/panel style lives in
 *  {@link AbstractBedStatScreen}. */
public class AnimalBedScreen extends AbstractBedStatScreen<AnimalBedMenu> {

    private static final int GUI_W = 220;
    private static final int GUI_H = 206;

    private static final int Y_WATER_BAR   = 22;
    private static final int Y_WATER_RATE  = 32;
    private static final int Y_OUT_BAR     = 48;
    private static final int Y_OUT_RATE    = 58;
    private static final int Y_STALLS      = 74;
    private static final int Y_SPECIES_HDR = 90;
    private static final int Y_SPECIES_ROW = 100;
    private static final int Y_OUTPUT_HDR  = 144;
    private static final int Y_OUTPUT_ROW  = 154;
    private static final int ROW_H         = 10;
    /** Height of the output-fluid gauge block — everything below shifts up by this and the panel
     *  shrinks by it when the bed's output fluid is config-disabled (server syncs the EMPTY id). */
    private static final int OUT_BLOCK_H   = Y_STALLS - Y_OUT_BAR;

    public AnimalBedScreen(AnimalBedMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, GUI_W, GUI_H);
    }

    /** False when the server reports this bed's output fluid as config-disabled (EMPTY id). */
    private boolean outFluidShown() {
        return menu.getOutFluidId() != fluidId(Fluids.EMPTY);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        boolean out = outFluidShown();
        drawPanel(g, GUI_W, out ? GUI_H : GUI_H - OUT_BLOCK_H);
        drawBar(g, leftPos + BAR_X, topPos + Y_WATER_BAR, BAR_W, BAR_H,
                menu.getWaterMb(), menu.getWaterCap(), BAR_WATER);
        if (out) {
            drawBar(g, leftPos + BAR_X, topPos + Y_OUT_BAR, BAR_W, BAR_H,
                    menu.getOutFluidMb(), menu.getOutFluidCap(), outFluidColor());
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // Title (from the BE's display name + cluster size).
        int clusterSize = menu.getClusterSize();
        String base = title.getString();
        String t = clusterSize <= 1 ? base : base + " (" + clusterSize + " beds)";
        g.text(font, t, 8, 6, LABEL_COLOR, false);

        // Water gauge — real input; value red when dry so a stalled pen is obvious at a glance.
        g.text(font, Component.translatable("gui.hydrofarm.bed.water").getString(),
               8, Y_WATER_BAR + 1, LABEL_COLOR, false);
        String waterVal = formatGauge(menu.getWaterMb(), menu.getWaterCap());
        g.text(font, waterVal, BAR_X + BAR_W + 4, Y_WATER_BAR + 1,
               menu.getWaterMb() <= 0 ? WARN_COLOR : VALUE_COLOR, false);
        g.text(font, "↓ " + menu.getWaterRateMbPerMin() + " mB/min", BAR_X, Y_WATER_RATE, SUBTLE_COLOR, false);

        // Output-fluid gauge — milk (husbandry) or liquid XP (butcher). Hidden entirely (and the
        // rows below shift up) when the server reports it config-disabled.
        boolean out = outFluidShown();
        int dy = out ? 0 : -OUT_BLOCK_H;
        if (out) {
            g.text(font, outFluidLabel(), 8, Y_OUT_BAR + 1, LABEL_COLOR, false);
            String outVal = formatGauge(menu.getOutFluidMb(), menu.getOutFluidCap());
            g.text(font, outVal, BAR_X + BAR_W + 4, Y_OUT_BAR + 1, VALUE_COLOR, false);
            g.text(font, "↑ " + menu.getOutFluidRateMbPerMin() + " mB/min", BAR_X, Y_OUT_RATE, SUBTLE_COLOR, false);
        }

        // Stall occupancy — its own status line, like the hydroponics planter line.
        g.text(font, Component.translatable("gui.hydrofarm.bed.stalls",
                menu.getStallsOccupied(), menu.getStallsTotal()).getString(),
               8, Y_STALLS + dy, LABEL_COLOR, false);

        // Species header + rows
        g.text(font, Component.translatable("gui.hydrofarm.bed.animals").getString(),
               8, Y_SPECIES_HDR + dy, LABEL_COLOR, false);
        int shownSpecies = 0;
        for (int i = 0; i < AnimalBedMenu.SPECIES_SLOT_COUNT; i++) {
            int count = menu.getSpeciesCount(i);
            if (count <= 0) continue;
            int speciesId = menu.getSpeciesId(i);
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.byId(speciesId);
            if (type == null) continue;
            int rowY = Y_SPECIES_ROW + dy + shownSpecies * ROW_H;
            String name = type.getDescription().getString();
            String line = name + " × " + count;
            g.text(font, line, 16, rowY, VALUE_COLOR, false);
            if (menu.isFoodMissing(i)) {
                Item feed = requiredFeed(type);
                String warn = feed != null
                    ? Component.translatable("gui.hydrofarm.bed.needs_feed",
                        new ItemStack(feed).getHoverName()).getString()
                    : Component.translatable("gui.hydrofarm.bed.food_missing").getString();
                g.text(font, "⚠ " + warn, 16 + font.width(line) + 8, rowY, WARN_COLOR, false);
            }
            shownSpecies++;
        }
        if (shownSpecies == 0) {
            g.text(font, Component.translatable("gui.hydrofarm.bed.no_animals")
                    .withStyle(ChatFormatting.ITALIC).getString(),
                   16, Y_SPECIES_ROW + dy, SUBTLE_COLOR, false);
        }

        // Output items header + rows
        g.text(font, Component.translatable("gui.hydrofarm.bed.output").getString(),
               8, Y_OUTPUT_HDR + dy, LABEL_COLOR, false);
        int shownOutputs = 0;
        for (int i = 0; i < AnimalBedMenu.OUTPUT_SLOT_COUNT; i++) {
            int rate = menu.getOutputRate(i);
            if (rate <= 0) continue;
            int itemId = menu.getOutputItemId(i);
            Item item = BuiltInRegistries.ITEM.byId(itemId);
            if (item == Items.AIR) continue;
            int rowY = Y_OUTPUT_ROW + dy + shownOutputs * ROW_H;
            ItemStack stack = new ItemStack(item);
            g.item(stack, 8, rowY - 4);
            g.text(font, stack.getHoverName().getString(), 26, rowY, VALUE_COLOR, false);
            String rateStr = String.valueOf(rate);
            g.text(font, rateStr, GUI_W - 12 - font.width(rateStr), rowY, VALUE_COLOR, false);
            shownOutputs++;
        }
        if (shownOutputs == 0) {
            g.text(font, Component.translatable("gui.hydrofarm.bed.no_output")
                    .withStyle(ChatFormatting.ITALIC).getString(),
                   16, Y_OUTPUT_ROW + dy, SUBTLE_COLOR, false);
        }
        int overflow = menu.getOutputOverflow();
        if (overflow > 0) {
            g.text(font, "+ " + overflow + " more", 16, Y_OUTPUT_ROW + dy + shownOutputs * ROW_H, SUBTLE_COLOR, false);
        }
    }

    /** Output-fluid bar colour keyed to the fluid: cream for milk, green for liquid XP. */
    private int outFluidColor() {
        return menu.getOutFluidId() == fluidId(HydrofarmRefs.FLUID_MILK.get()) ? BAR_MILK : BAR_XP;
    }

    /** Output-fluid gauge label keyed to the fluid. */
    private String outFluidLabel() {
        int id = menu.getOutFluidId();
        if (id == fluidId(HydrofarmRefs.FLUID_MILK.get())) {
            return Component.translatable("gui.hydrofarm.bed.milk").getString();
        }
        if (id == fluidId(HydrofarmRefs.LIQUID_XP.get())) {
            return Component.translatable("gui.hydrofarm.bed.xp").getString();
        }
        return Component.translatable("gui.hydrofarm.bed.output_fluid").getString();
    }

    private static int fluidId(net.minecraft.world.level.material.Fluid fluid) {
        return BuiltInRegistries.FLUID.getId(fluid);
    }

    /** The feed item a species needs, for the "needs X" hint. Sheep/cow/chicken share feed across
     *  both bed types and pig is butcher-only, so checking husbandry then butcher resolves it. */
    private static Item requiredFeed(EntityType<?> type) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (id == null) return null;
        HusbandryAnimal ha = HusbandryAnimal.forEntity(id);
        if (ha != null) return ha.primaryFood;
        ButcherAnimal ba = ButcherAnimal.forEntity(id);
        return ba != null ? ba.primaryFood : null;
    }
}
