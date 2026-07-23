package com.kestalkayden.hydrofarm.client;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.block.LiquidTankBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Brightness;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

/** Per-tank cluster-aware fluid renderer. Each tank emits its own fluid box; bounds and wall
 *  flags depend on whether each neighbor is also in the cluster. Walls toward cluster members
 *  are culled and fluid extends to the block boundary on that side. Sprites come from the shared
 *  vanilla fluid-model set (see {@link #extractRenderState}), so any fluid a tank can hold —
 *  vanilla, ours, or a third party's — draws with its own registered texture; tint is a small
 *  lookup per known fluid, falling back to white so mod fluids that bake color into their texture
 *  still render correctly. */
public class LiquidTankRenderer implements BlockEntityRenderer<LiquidTankBlockEntity, LiquidTankRenderer.State> {

    private static final float INSET = 2.01f / 16f;
    /** Default fluid alpha (matches vanilla water). Per-fluid overrides via {@link #alphaForFluid}. */
    private static final int FLUID_ALPHA = 0xE0;
    /** Block/sky light level (0-15) applied to glowing fluids. 15 = lava-equivalent fullbright. */
    private static final int GLOW_LEVEL = 12;
    /** Packed glow light, computed once instead of allocating a {@link Brightness} every frame. */
    private static final int GLOW_LIGHT = new Brightness(GLOW_LEVEL, GLOW_LEVEL).pack();

    public LiquidTankRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(LiquidTankBlockEntity be, State state, float partialTick,
                                    Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderState.extractBase(be, state, crumbling);
        state.fillRatio = 0f;
        state.spriteStill = null;
        state.spriteFlow  = null;

        LiquidTankBlockEntity.Cluster cluster = be.findCluster();
        if (cluster == null) return;

        // Cluster total + fluid, cached per tick on the BE. findCluster() (the BFS) and this
        // aggregate were both an uncached per-frame O(N) walk — O(N^2) across a multi-tank cluster
        // in view. Both now run at most once per tick.
        be.ensureClusterTotals(cluster);
        long totalAmount = be.clusterTotalMb();
        Fluid clusterFluid = be.clusterFluidCached();
        if (totalAmount <= 0 || clusterFluid == Fluids.EMPTY) return;

        BlockPos selfPos = be.getBlockPos();
        int capMb = be.getCapacityMb();

        int myY = selfPos.getY();
        long capBelow = cluster.capacityBelow(myY, capMb);
        int layerSize = cluster.layerSize(myY);
        long layerCap = (long) layerSize * capMb;
        long layerFill = Math.max(0, Math.min(totalAmount - capBelow, layerCap));
        if (layerFill <= 0 || layerCap <= 0) return;

        float fillFraction = (float) layerFill / layerCap;
        if (fillFraction <= 0f) return;

        boolean memberWest  = cluster.contains(selfPos.west());
        boolean memberEast  = cluster.contains(selfPos.east());
        boolean memberNorth = cluster.contains(selfPos.north());
        boolean memberSouth = cluster.contains(selfPos.south());
        boolean memberAbove = cluster.contains(selfPos.above());
        boolean memberBelow = cluster.contains(selfPos.below());

        long aboveCapBelow = capBelow + layerCap;
        int aboveLayerSize = cluster.layerSize(myY + 1);
        long aboveLayerFill = Math.max(0,
            Math.min(totalAmount - aboveCapBelow, (long) aboveLayerSize * capMb));

        state.minX = memberWest  ? 0f : INSET;
        state.maxX = memberEast  ? 1f : 1f - INSET;
        state.minZ = memberNorth ? 0f : INSET;
        state.maxZ = memberSouth ? 1f : 1f - INSET;
        state.minY = memberBelow ? 0f : INSET;
        float yMax = memberAbove ? 1f : 1f - INSET;
        state.yTop = state.minY + fillFraction * (yMax - state.minY);
        state.fillRatio = fillFraction;

        state.drawTopQuad = !(fillFraction >= 0.999f && memberAbove && aboveLayerFill > 0);
        state.drawWallNorth = !memberNorth;
        state.drawWallSouth = !memberSouth;
        state.drawWallEast  = !memberEast;
        state.drawWallWest  = !memberWest;

        // Sprites come from the vanilla FluidStateModelSet, the shared registry every loader
        // populates with every fluid's registered still/flow textures — vanilla's, ours (via the
        // FluidRenderingRegistry / RegisterFluidModelsEvent calls the client init makes), and any
        // third party's. The old code instead guessed "<namespace>:block/<path>_still", which
        // happened to match water/lava/our own fluids but produced the missing-texture magenta for
        // any fluid whose sprite isn't literally named "<path>_still" — e.g. Sophisticated
        // Backpacks' "sophisticatedcore:xp_still" resolved to the nonexistent "xp_still_still".
        // A fluid absent from the set still degrades to the missing sprite rather than crashing.
        FluidModel fluidModel = Minecraft.getInstance().getModelManager()
            .getFluidStateModelSet().get(clusterFluid.defaultFluidState());
        state.spriteStill = fluidModel.stillMaterial().sprite();
        state.spriteFlow  = fluidModel.flowingMaterial().sprite();
        int rgb = tintForFluid(clusterFluid);
        state.colorR = (rgb >> 16) & 0xFF;
        state.colorG = (rgb >> 8) & 0xFF;
        state.colorB = rgb & 0xFF;
        state.colorA = alphaForFluid(clusterFluid);

        // Glowing fluids (e.g. Liquid XP) ignore ambient lighting — same trick lava uses on its
        // own surface. The block itself doesn't emit world light here; this is render-only.
        if (glowsForFluid(clusterFluid)) {
            state.lightCoords = GLOW_LIGHT;
        }
    }

    private static boolean glowsForFluid(Fluid fluid) {
        return fluid == HydrofarmRefs.LIQUID_XP.get();
    }

    /** Per-fluid alpha so glowing/ethereal fluids (e.g. Liquid XP) read as more translucent
     *  than thick fluids (water, lava). Falls back to the default vanilla-water alpha. */
    private static int alphaForFluid(Fluid fluid) {
        if (fluid == HydrofarmRefs.LIQUID_XP.get()) return 0x60;
        if (fluid == HydrofarmRefs.FLUID_MILK.get()) return 0xF8;  // Milk is opaque — bump above water's alpha.
        return FLUID_ALPHA;
    }

    /** Fluid -> tint RGB (alpha applied separately). Lava and most mod fluids have color baked
     *  into the texture, so default to 0xFFFFFF (no tint). Vanilla water's texture is grayscale
     *  and needs the blue tint applied at draw time. */
    private static int tintForFluid(Fluid fluid) {
        if (fluid == Fluids.WATER) return 0x3F76E4;
        return 0xFFFFFF;
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector,
                       CameraRenderState camera) {
        if (state.fillRatio <= 0f || state.spriteStill == null || state.spriteFlow == null) return;

        final float yTop = state.yTop;
        if (yTop <= state.minY) return;

        // Top quad uses the still sprite (non-directional motion); walls use the flow sprite
        // (directional rising motion). Vanilla water/lava follow the same split.
        final float tu0 = state.spriteStill.getU0();
        final float tv0 = state.spriteStill.getV0();
        final float tu1 = state.spriteStill.getU1();
        final float tv1 = state.spriteStill.getV1();
        final float fu0 = state.spriteFlow.getU0();
        final float fv0 = state.spriteFlow.getV0();
        final float fu1 = state.spriteFlow.getU1();
        final float fv1 = state.spriteFlow.getV1();
        final int light = state.lightCoords;
        final int overlay = OverlayTexture.NO_OVERLAY;
        final float minX = state.minX, maxX = state.maxX;
        final float minZ = state.minZ, maxZ = state.maxZ;
        final float minY = state.minY;
        final int r = state.colorR, g = state.colorG, b = state.colorB;
        final int a = state.colorA;

        collector.submitCustomGeometry(pose, Sheets.translucentBlockSheet(), (poseEntry, vc) -> {
            Matrix4f mat = poseEntry.pose();

            if (state.drawTopQuad) {
                quad(vc, mat, minX, yTop, minZ,  minX, yTop, maxZ,  maxX, yTop, maxZ,  maxX, yTop, minZ,
                     0, 1, 0, tu0, tv0, tu1, tv1, r, g, b, a, light, overlay);
            }
            if (state.drawWallNorth) {
                quad(vc, mat, maxX, yTop, minZ,  maxX, minY, minZ,  minX, minY, minZ,  minX, yTop, minZ,
                     0, 0, -1, fu0, fv0, fu1, fv1, r, g, b, a, light, overlay);
            }
            if (state.drawWallSouth) {
                quad(vc, mat, minX, yTop, maxZ,  minX, minY, maxZ,  maxX, minY, maxZ,  maxX, yTop, maxZ,
                     0, 0, 1, fu0, fv0, fu1, fv1, r, g, b, a, light, overlay);
            }
            if (state.drawWallWest) {
                quad(vc, mat, minX, yTop, minZ,  minX, minY, minZ,  minX, minY, maxZ,  minX, yTop, maxZ,
                     -1, 0, 0, fu0, fv0, fu1, fv1, r, g, b, a, light, overlay);
            }
            if (state.drawWallEast) {
                quad(vc, mat, maxX, yTop, maxZ,  maxX, minY, maxZ,  maxX, minY, minZ,  maxX, yTop, minZ,
                     1, 0, 0, fu0, fv0, fu1, fv1, r, g, b, a, light, overlay);
            }
        });
    }

    private static void quad(VertexConsumer vc, Matrix4f mat,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float nx, float ny, float nz,
                             float u0, float v0, float u1, float v1,
                             int r, int g, int b, int a,
                             int light, int overlay) {
        vc.addVertex(mat, x0, y0, z0).setColor(r, g, b, a).setUv(u0, v0).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
        vc.addVertex(mat, x1, y1, z1).setColor(r, g, b, a).setUv(u0, v1).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
        vc.addVertex(mat, x2, y2, z2).setColor(r, g, b, a).setUv(u1, v1).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
        vc.addVertex(mat, x3, y3, z3).setColor(r, g, b, a).setUv(u1, v0).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
    }

    public static class State extends BlockEntityRenderState {
        public float fillRatio;
        public float minX = INSET, maxX = 1f - INSET;
        public float minZ = INSET, maxZ = 1f - INSET;
        public float minY = INSET;
        public float yTop = INSET;
        public boolean drawTopQuad;
        public boolean drawWallNorth, drawWallSouth, drawWallEast, drawWallWest;
        public TextureAtlasSprite spriteStill;  // top quad
        public TextureAtlasSprite spriteFlow;   // side walls
        public int colorR = 0xFF, colorG = 0xFF, colorB = 0xFF, colorA = FLUID_ALPHA;
    }
}
