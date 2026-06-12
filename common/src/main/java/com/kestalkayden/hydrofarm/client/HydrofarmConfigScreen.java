package com.kestalkayden.hydrofarm.client;

import com.kestalkayden.hydrofarm.HydrofarmConfig;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** In-game editor for {@code config/hydrofarm.properties}, reached from ModMenu (Fabric) or the
 *  Mods screen's config button (NeoForge). Plain vanilla widgets — no config-library dependency.
 *  Toggles apply live; number fields apply (clamped) on Done, and everything is saved to disk on
 *  close. Render options affect only this client; gameplay toggles affect worlds this client
 *  hosts (singleplayer/LAN) — a dedicated server reads its own config file. */
public class HydrofarmConfigScreen extends Screen {

    private static final int WIDGET_W = 150;
    private static final int WIDGET_H = 20;
    private static final int COL_GAP = 10;
    private static final int ROW_H = 33;          // widget + label headroom
    private static final int LABEL_COLOR = 0xFFA0A0A0;

    private final Screen parent;

    /** Number fields, parsed + clamped on Done. Labels draw above each box. */
    private EditBox cropViewDistance;
    private EditBox animalViewDistance;
    private EditBox animalPuppetCap;
    private EditBox particleCount;
    private EditBox particleInterval;

    public HydrofarmConfigScreen(Screen parent) {
        super(Component.translatable("config.hydrofarm.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int leftX = width / 2 - WIDGET_W - COL_GAP / 2;
        int rightX = width / 2 + COL_GAP / 2;
        int y = 40;

        // Row 0 — render toggles (cycle buttons embed their own label).
        addRenderableWidget(CycleButton.onOffBuilder(HydrofarmConfig.renderCrops())
            .create(leftX, y, WIDGET_W, WIDGET_H, label("renderCrops"),
                (b, v) -> HydrofarmConfig.setRenderCrops(v)));
        addRenderableWidget(CycleButton.onOffBuilder(HydrofarmConfig.renderAnimalPuppets())
            .create(rightX, y, WIDGET_W, WIDGET_H, label("renderAnimalPuppets"),
                (b, v) -> HydrofarmConfig.setRenderAnimalPuppets(v)));
        y += ROW_H;

        // Row 1 — view distances.
        cropViewDistance = numberBox(leftX, y, "cropViewDistance", HydrofarmConfig.cropViewDistance());
        animalViewDistance = numberBox(rightX, y, "animalViewDistance", HydrofarmConfig.animalViewDistance());
        y += ROW_H;

        // Row 2 — puppet cap + sprinkler particle count.
        animalPuppetCap = numberBox(leftX, y, "animalPuppetCap", HydrofarmConfig.animalPuppetCap());
        particleCount = numberBox(rightX, y, "sprinklerParticleCount", HydrofarmConfig.sprinklerParticleCount());
        y += ROW_H;

        // Row 3 — particle interval + first XP toggle.
        particleInterval = numberBox(leftX, y, "sprinklerParticleInterval", HydrofarmConfig.sprinklerParticleInterval());
        addRenderableWidget(CycleButton.onOffBuilder(HydrofarmConfig.hydroponicsBedXp())
            .create(rightX, y, WIDGET_W, WIDGET_H, label("hydroponicsBedXp"),
                (b, v) -> HydrofarmConfig.setHydroponicsBedXp(v)));
        y += ROW_H;

        // Row 4 — remaining XP toggles.
        addRenderableWidget(CycleButton.onOffBuilder(HydrofarmConfig.treeFarmBedXp())
            .create(leftX, y, WIDGET_W, WIDGET_H, label("treeFarmBedXp"),
                (b, v) -> HydrofarmConfig.setTreeFarmBedXp(v)));
        addRenderableWidget(CycleButton.onOffBuilder(HydrofarmConfig.butcherBedXp())
            .create(rightX, y, WIDGET_W, WIDGET_H, label("butcherBedXp"),
                (b, v) -> HydrofarmConfig.setButcherBedXp(v)));

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(width / 2 - 100, height - 27, 200, WIDGET_H).build());
    }

    private EditBox numberBox(int x, int y, String key, int value) {
        EditBox box = new EditBox(font, x, y, WIDGET_W, WIDGET_H, label(key));
        box.setValue(Integer.toString(value));
        return addRenderableWidget(box);
    }

    private static Component label(String key) {
        return Component.translatable("config.hydrofarm." + key);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.text(font, title, (width - font.width(title)) / 2, 15, 0xFFFFFFFF);
        // Cycle buttons carry their own label; number boxes get one drawn just above.
        labelAbove(g, cropViewDistance, "cropViewDistance");
        labelAbove(g, animalViewDistance, "animalViewDistance");
        labelAbove(g, animalPuppetCap, "animalPuppetCap");
        labelAbove(g, particleCount, "sprinklerParticleCount");
        labelAbove(g, particleInterval, "sprinklerParticleInterval");
    }

    private void labelAbove(GuiGraphicsExtractor g, EditBox box, String key) {
        if (box != null) g.text(font, label(key), box.getX(), box.getY() - 10, LABEL_COLOR);
    }

    /** Apply the number fields (invalid text keeps the old value, setters clamp), persist, exit. */
    @Override
    public void onClose() {
        applyInt(cropViewDistance, HydrofarmConfig::setCropViewDistance);
        applyInt(animalViewDistance, HydrofarmConfig::setAnimalViewDistance);
        applyInt(animalPuppetCap, HydrofarmConfig::setAnimalPuppetCap);
        applyInt(particleCount, HydrofarmConfig::setSprinklerParticleCount);
        applyInt(particleInterval, HydrofarmConfig::setSprinklerParticleInterval);
        HydrofarmConfig.save();
        minecraft.setScreen(parent);
    }

    private static void applyInt(EditBox box, java.util.function.IntConsumer setter) {
        if (box == null) return;
        try {
            setter.accept(Integer.parseInt(box.getValue().trim()));
        } catch (NumberFormatException ignored) {
            // Unparseable — keep the previous value.
        }
    }
}
