package com.kestalkayden.hydrofarm.client;

import com.kestalkayden.hydrofarm.block.MendingStationBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Brightness;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;

/** Floats the tool being repaired above the station, slowly spinning — same hologram treatment as
 *  the autocrafter's result, gated on the station being powered. */
public class MendingStationRenderer implements BlockEntityRenderer<MendingStationBlockEntity, MendingStationRenderer.State> {

    private static final float SPIN_DEG_PER_TICK = 2.0f;
    private static final float BOB_SPEED = 0.10f;
    private static final float BOB_AMP = 0.04f;
    private static final float FLOAT_HEIGHT = 1.3f;
    /** Fullbright packed light — computed once instead of allocating a Brightness every frame. */
    private static final int FULLBRIGHT = new Brightness(15, 15).pack();
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private final ItemModelResolver itemModelResolver;

    public MendingStationRenderer(BlockEntityRendererProvider.Context ctx) {
        this.itemModelResolver = ctx.itemModelResolver();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(MendingStationBlockEntity be, State state, float partialTick,
                                    Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderState.extractBase(be, state, crumbling);
        ItemStack tool = be.getDisplayItem();
        state.show = !tool.isEmpty() && be.isPowered() && be.getLevel() != null;
        if (state.show) {
            long t = be.getLevel().getGameTime();
            state.spin = (t + partialTick) * SPIN_DEG_PER_TICK;
            state.bob = (float) Math.sin((t + partialTick) * BOB_SPEED) * BOB_AMP;
            state.spinQuat.rotationY(state.spin * DEG_TO_RAD);
            // Re-resolve the floating tool model only when the displayed tool changes — wasteful to
            // walk the whole item-model definition every frame for a hologram that is only spinning.
            if (!ItemStack.matches(state.lastTool, tool)) {
                state.lastTool = tool.copy();
                itemModelResolver.updateForTopItem(state.item, tool, ItemDisplayContext.GROUND,
                    be.getLevel(), null, (int) be.getBlockPos().asLong());
            }
        }
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.show || state.item.isEmpty()) return;
        pose.pushPose();
        pose.translate(0.5, FLOAT_HEIGHT + state.bob, 0.5);
        pose.mulPose(state.spinQuat);
        state.item.submit(pose, collector, FULLBRIGHT, OverlayTexture.NO_OVERLAY, 0);
        pose.popPose();
    }

    public static class State extends BlockEntityRenderState {
        public boolean show;
        public float spin;
        public float bob;
        public final ItemStackRenderState item = new ItemStackRenderState();
        /** Last tool a model was resolved for — re-resolve only when it changes. */
        public ItemStack lastTool = ItemStack.EMPTY;
        /** Reused per-BE spin rotation, set in extract so submit allocates nothing. */
        public final Quaternionf spinQuat = new Quaternionf();
    }
}
