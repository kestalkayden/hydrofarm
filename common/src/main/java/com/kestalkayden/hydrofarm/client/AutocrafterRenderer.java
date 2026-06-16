package com.kestalkayden.hydrofarm.client;

import com.kestalkayden.hydrofarm.block.AutocrafterBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.Brightness;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Lights the prepped recipe cells on the autocrafter's four side grids. Reads the synced
 *  {@link AutocrafterBlockEntity#getRecipeMask() recipe mask} (one bit per template cell) and draws
 *  a small fullbright amber pip, slightly proud of each side face, over every lit cell — so the
 *  block shows its recipe shape at a glance. The emblem cell layout mirrors the side texture
 *  (3×3 at texel offset 2,4 with a 3-texel pitch). */
public class AutocrafterRenderer implements BlockEntityRenderer<AutocrafterBlockEntity, AutocrafterRenderer.State> {

    private static final int GRID = 3;
    private static final float CELL_ORIGIN_X = 2f;   // texels
    private static final float CELL_ORIGIN_Y = 4f;
    private static final float CELL_PITCH = 3f;
    private static final float CELL_SIZE = 2f;
    private static final float PROUD = 0.002f;        // push the pip just outside the face

    private static final int PIP_R = 255, PIP_G = 232, PIP_B = 150, PIP_A = 255;  // warm "lit" amber
    /** Fullbright packed light — computed once instead of allocating a Brightness every frame. */
    private static final int FULLBRIGHT = new Brightness(15, 15).pack();

    /** Floating-result motion. */
    private static final float SPIN_DEG_PER_TICK = 2.0f;   // ~9 s per revolution
    private static final float BOB_SPEED = 0.10f;
    private static final float BOB_AMP = 0.04f;
    private static final float FLOAT_HEIGHT = 1.3f;        // above the block top (y = 1)
    private static final float RESULT_SCALE = 1.0f;        // on top of the GROUND context's baked scale
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private final TextureAtlasSprite sprite;
    private final ItemModelResolver itemModelResolver;

    public AutocrafterRenderer(BlockEntityRendererProvider.Context ctx) {
        // A near-white, uniform vanilla sprite so the amber tint + fullbright reads as a clean lit
        // cell (sampling our own busy side texture would muddy the colour). BLOCKS_MAPPER targets
        // the blocks atlas + "block/" prefix (replaces the deprecated TextureAtlas.LOCATION_BLOCKS).
        this.sprite = Minecraft.getInstance().getAtlasManager()
            .get(Sheets.BLOCKS_MAPPER.defaultNamespaceApply("white_concrete"));
        this.itemModelResolver = ctx.itemModelResolver();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(AutocrafterBlockEntity be, State state, float partialTick,
                                    Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderState.extractBase(be, state, crumbling);
        state.mask = be.getRecipeMask();

        ItemStack result = be.getDisplayResult();
        state.showResult = !result.isEmpty() && be.isPowered() && be.getLevel() != null;
        if (state.showResult) {
            long t = be.getLevel().getGameTime();
            state.spin = (t + partialTick) * SPIN_DEG_PER_TICK;
            state.bob = (float) Math.sin((t + partialTick) * BOB_SPEED) * BOB_AMP;
            state.spinQuat.rotationY(state.spin * DEG_TO_RAD);
            // Re-resolve the floating item model only when the displayed result changes —
            // updateForTopItem walks the whole item-model definition, wasteful to redo every frame
            // for a hologram that is only spinning.
            if (!ItemStack.matches(state.lastResult, result)) {
                state.lastResult = result.copy();
                itemModelResolver.updateForTopItem(state.result, result, ItemDisplayContext.GROUND,
                    be.getLevel(), null, (int) be.getBlockPos().asLong());
            }
        }
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.mask != 0) {
            final int light = FULLBRIGHT;
            final float u0 = sprite.getU0(), v0 = sprite.getV0(), u1 = sprite.getU1(), v1 = sprite.getV1();
            collector.submitCustomGeometry(pose, net.minecraft.client.renderer.rendertype.RenderTypes.translucentMovingBlock(), (poseEntry, vc) -> {
                Matrix4f mat = poseEntry.pose();
                for (int slot = 0; slot < GRID * GRID; slot++) {
                    if ((state.mask & (1 << slot)) == 0) continue;
                    int col = slot % GRID, row = slot / GRID;
                    float tx0 = CELL_ORIGIN_X + col * CELL_PITCH;
                    float ty0 = CELL_ORIGIN_Y + row * CELL_PITCH;
                    for (Direction face : Direction.Plane.HORIZONTAL) {
                        pip(vc, mat, face, tx0, ty0, tx0 + CELL_SIZE, ty0 + CELL_SIZE, u0, v0, u1, v1, light);
                    }
                }
            });
        }

        // Floating, slowly-spinning result above a powered, recipe-matched crafter.
        if (state.showResult && !state.result.isEmpty()) {
            pose.pushPose();
            pose.translate(0.5, FLOAT_HEIGHT + state.bob, 0.5);
            pose.mulPose(state.spinQuat);
            pose.scale(RESULT_SCALE, RESULT_SCALE, RESULT_SCALE);
            state.result.submit(pose, collector, FULLBRIGHT, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
    }

    /** Draws one lit cell on {@code face}. Double-sided (both windings) so it shows regardless of
     *  back-face culling. Cell bounds are in texels; {@link #worldX}/{@link #worldY}/{@link #worldZ}
     *  map a texel point on the face to world space, pushed {@link #PROUD} outward. */
    private void pip(VertexConsumer vc, Matrix4f mat, Direction face,
                     float tx0, float ty0, float tx1, float ty1,
                     float u0, float v0, float u1, float v1, int light) {
        float nx = normalX(face), nz = normalZ(face);
        // Corners (texel space, y down): TL, BL, BR, TR — kept as primitive locals (no per-pip
        // array allocation; this runs for every lit cell on every visible face every frame).
        float ax = worldX(face, tx0), ay = worldY(ty0), az = worldZ(face, tx0);  // TL
        float bx = worldX(face, tx0), by = worldY(ty1), bz = worldZ(face, tx0);  // BL
        float cx = worldX(face, tx1), cy = worldY(ty1), cz = worldZ(face, tx1);  // BR
        float dx = worldX(face, tx1), dy = worldY(ty0), dz = worldZ(face, tx1);  // TR
        quad(vc, mat, ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, nx, 0f, nz, u0, v0, u1, v1, light);
        quad(vc, mat, dx, dy, dz, cx, cy, cz, bx, by, bz, ax, ay, az, -nx, 0f, -nz, u0, v0, u1, v1, light);
    }

    private static float normalX(Direction face) {
        return switch (face) {
            case WEST -> -1f;
            case EAST -> 1f;
            default   -> 0f;   // NORTH / SOUTH
        };
    }

    private static float normalZ(Direction face) {
        return switch (face) {
            case NORTH -> -1f;
            case SOUTH -> 1f;
            default    -> 0f;  // EAST / WEST
        };
    }

    private static float worldY(float ty) { return 1f - ty / 16f; }

    private static float worldX(Direction face, float tx) {
        return switch (face) {
            case NORTH -> 1f - tx / 16f;
            case SOUTH -> tx / 16f;
            case WEST  -> -PROUD;
            default    -> 1f + PROUD;            // EAST
        };
    }

    private static float worldZ(Direction face, float tx) {
        return switch (face) {
            case NORTH -> -PROUD;
            case SOUTH -> 1f + PROUD;
            case WEST  -> tx / 16f;
            default    -> 1f - tx / 16f;         // EAST
        };
    }

    private void quad(VertexConsumer vc, Matrix4f mat,
                      float x0, float y0, float z0, float x1, float y1, float z1,
                      float x2, float y2, float z2, float x3, float y3, float z3,
                      float nx, float ny, float nz, float u0, float v0, float u1, float v1, int light) {
        int o = OverlayTexture.NO_OVERLAY;
        vc.addVertex(mat, x0, y0, z0).setColor(PIP_R, PIP_G, PIP_B, PIP_A).setUv(u0, v0).setOverlay(o).setLight(light).setNormal(nx, ny, nz);
        vc.addVertex(mat, x1, y1, z1).setColor(PIP_R, PIP_G, PIP_B, PIP_A).setUv(u0, v1).setOverlay(o).setLight(light).setNormal(nx, ny, nz);
        vc.addVertex(mat, x2, y2, z2).setColor(PIP_R, PIP_G, PIP_B, PIP_A).setUv(u1, v1).setOverlay(o).setLight(light).setNormal(nx, ny, nz);
        vc.addVertex(mat, x3, y3, z3).setColor(PIP_R, PIP_G, PIP_B, PIP_A).setUv(u1, v0).setOverlay(o).setLight(light).setNormal(nx, ny, nz);
    }

    public static class State extends BlockEntityRenderState {
        public int mask;
        public boolean showResult;
        public float spin;
        public float bob;
        public final ItemStackRenderState result = new ItemStackRenderState();
        /** Last result a model was resolved for — re-resolve only when it changes. */
        public ItemStack lastResult = ItemStack.EMPTY;
        /** Reused per-BE spin rotation, set in extract so submit allocates nothing. */
        public final Quaternionf spinQuat = new Quaternionf();
    }
}
