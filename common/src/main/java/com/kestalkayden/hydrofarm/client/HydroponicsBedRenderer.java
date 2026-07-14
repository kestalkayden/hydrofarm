package com.kestalkayden.hydrofarm.client;

import java.util.ArrayList;
import java.util.List;

import com.kestalkayden.hydrofarm.HydrofarmConfig;
import com.kestalkayden.hydrofarm.block.CropAdapter;
import com.kestalkayden.hydrofarm.block.HydroponicsBedBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Scaled crop renderer for the Hydroponics Bed. For each installed quadrant with a
 *  configured seed, resolves the crop's BlockState at the current growth age, fetches its
 *  BlockStateModel from the vanilla model set, and submits it scaled down (~25% XZ, 45% Y)
 *  centered above the planter pot. Light is inherited from the bed BE's render state. */
public class HydroponicsBedRenderer
        implements BlockEntityRenderer<HydroponicsBedBlockEntity, HydroponicsBedRenderer.State> {

    /** Origin (block-local XYZ in fractions) for the bottom-corner of each scaled crop. */
    private static final float[][] ORIGINS = {
        {2f / 16f, 7f / 16f, 2f / 16f},   // NW
        {10f / 16f, 7f / 16f, 2f / 16f},  // NE
        {2f / 16f, 7f / 16f, 10f / 16f},  // SW
        {10f / 16f, 7f / 16f, 10f / 16f}  // SE
    };
    private static final float SCALE_XZ = 0.25f;
    private static final float SCALE_Y  = 0.45f;
    private static final int[] NO_TINT  = {-1};
    /** Melon/pumpkin render as their full-colour fruit block (see {@link CropAdapter#rendersAsScaledFruit()}),
     *  swelling with growth and kept centered in the planter: {@link #FRUIT_FULL_SCALE} at maturity, never
     *  below {@link #FRUIT_MIN_FRAC} of that so a just-planted fruit is still visible. */
    private static final float FRUIT_FULL_SCALE = 0.25f;
    private static final float FRUIT_MIN_FRAC   = 0.30f;

    /** Reused across collectParts() calls (render thread, one renderer instance per BE type, called
     *  sequentially). Safe to share because collectParts consumes it synchronously. */
    private final RandomSource rand = RandomSource.create();

    public HydroponicsBedRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public State createRenderState() {
        return new State();
    }

    /** Crops are tiny (0.25× scale); the config default (48) sits below the 64-block BE default so
     *  distant beds skip the per-frame submit. The bed block still renders with its chunk. */
    @Override
    public int getViewDistance() { return HydrofarmConfig.cropViewDistance(); }

    @Override
    public void extractRenderState(HydroponicsBedBlockEntity be, State state, float partialTick,
                                    Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderState.extractBase(be, state, crumbling);

        if (!HydrofarmConfig.renderCrops()) {
            for (int q = 0; q < HydroponicsBedBlockEntity.QUADRANT_COUNT; q++) {
                state.partsFor[q] = null;
                state.cropParts[q] = null;
            }
            return;
        }

        BlockStateModelSet modelSet = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        // A resource reload swaps in a fresh model set; drop the cached parts so they re-collect
        // against the new models (a quadrant whose growth stage didn't change would otherwise keep
        // submitting stale geometry referencing the old set).
        if (state.builtModelSet != modelSet) {
            state.builtModelSet = modelSet;
            for (int q = 0; q < HydroponicsBedBlockEntity.QUADRANT_COUNT; q++) {
                state.partsFor[q] = null;
                state.cropParts[q] = null;
            }
        }

        for (int q = 0; q < HydroponicsBedBlockEntity.QUADRANT_COUNT; q++) {
            BlockState cropState = resolveCropState(be, q);
            // Scale + the scaled-fruit flag depend on live growth and are cheap, so recompute them
            // every extract (unlike the parts below, which cache by growth stage).
            fillCropScale(be, q, state);
            // Re-collect the model parts only when this quadrant's growth stage actually changes
            // (BlockStates are interned, so identity compares the stage). The per-frame path is then
            // just this check; collectParts + the fresh list — which submitBlockModel RETAINS for
            // deferred rendering — run a handful of times per crop lifetime instead of every frame.
            if (cropState == state.partsFor[q]) continue;
            state.partsFor[q] = cropState;
            state.cropParts[q] = collectParts(modelSet, cropState, q);
        }
    }

    /** The crop's BlockState at its current growth stage, or null when the quadrant is empty/ungrown. */
    private static BlockState resolveCropState(HydroponicsBedBlockEntity be, int q) {
        if (!be.isInstalled(q)) return null;
        Item seedItem = be.getSeed(q);
        CropAdapter ca = CropAdapter.resolve(seedItem);
        if (ca == null) return null;
        int growth = be.getGrowth(q);
        if (growth < 0) return null;
        // growth is normalized progress (0..growthSteps); map it onto the crop's visual stage
        // range (0..maxAge). Integer FLOOR so the ripe stage lands only at growth==growthSteps — the
        // plant looks mature exactly when it harvests, not a few windows early (which a round would do
        // for low-maxAge crops like cocoa). Cadence is decoupled from vanilla growth.
        int steps = ca.growthSteps();
        int visualAge = steps <= 0 ? ca.maxAge()
            : Math.min(ca.maxAge(), (int) ((long) growth * ca.maxAge() / steps));
        return ca.growthState(visualAge);
    }

    /** Per-quadrant render scale + scaled-fruit flag from live growth. Normal crops get scale 1 and the
     *  flag false (submit uses the fixed stalk scale); melon/pumpkin scale their fruit block up with
     *  growth. Writes into the render state directly — no per-frame allocation. */
    private static void fillCropScale(HydroponicsBedBlockEntity be, int q, State state) {
        state.scaledFruit[q] = false;
        state.cropScale[q] = 1f;
        if (!be.isInstalled(q)) return;
        CropAdapter ca = CropAdapter.resolve(be.getSeed(q));
        if (ca == null || !ca.rendersAsScaledFruit()) return;
        int steps = ca.growthSteps();
        int growth = Math.max(0, be.getGrowth(q));
        float frac = steps <= 0 ? 1f : Math.min(1f, (float) growth / steps);
        state.scaledFruit[q] = true;
        state.cropScale[q] = FRUIT_FULL_SCALE * (FRUIT_MIN_FRAC + (1f - FRUIT_MIN_FRAC) * frac);
    }

    /** Collects the crop model's parts into a fresh, thereafter-immutable list (null when there is
     *  nothing to draw). The returned list is handed to submitBlockModel as-is on every frame until
     *  the stage changes, so it must never be mutated after this returns, and each quadrant keeps its
     *  own list — a list submitBlockModel retained must not stand in for another quadrant. */
    private List<BlockStateModelPart> collectParts(BlockStateModelSet modelSet, BlockState cropState, int q) {
        if (cropState == null) return null;
        BlockStateModel model = modelSet.get(cropState);
        if (model == null) return null;
        List<BlockStateModelPart> parts = new ArrayList<>();
        rand.setSeed(42L + q);
        model.collectParts(rand, parts);
        return parts.isEmpty() ? null : parts;
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector,
                       CameraRenderState camera) {
        for (int q = 0; q < HydroponicsBedBlockEntity.QUADRANT_COUNT; q++) {
            List<BlockStateModelPart> parts = state.cropParts[q];
            if (parts == null) continue;

            pose.pushPose();
            if (state.scaledFruit[q]) {
                // Fruit block scaled uniformly, kept centered over the planter as it swells.
                float s = state.cropScale[q];
                float cx = ORIGINS[q][0] + SCALE_XZ / 2f;
                float cz = ORIGINS[q][2] + SCALE_XZ / 2f;
                pose.translate(cx - s / 2f, ORIGINS[q][1], cz - s / 2f);
                pose.scale(s, s, s);
            } else {
                pose.translate(ORIGINS[q][0], ORIGINS[q][1], ORIGINS[q][2]);
                pose.scale(SCALE_XZ, SCALE_Y, SCALE_XZ);
            }

            collector.submitBlockModel(pose, Sheets.cutoutBlockSheet(), parts, NO_TINT,
                state.lightCoords, OverlayTexture.NO_OVERLAY, 0);

            pose.popPose();
        }
    }

    public static class State extends BlockEntityRenderState {
        /** Cached collected model parts per quadrant — rebuilt only when the growth stage changes.
         *  Immutable once built (submitBlockModel retains the reference for deferred rendering); per
         *  quadrant, never shared. null = nothing to draw for that quadrant. */
        @SuppressWarnings("unchecked")
        public final List<BlockStateModelPart>[] cropParts =
            new List[HydroponicsBedBlockEntity.QUADRANT_COUNT];
        /** The crop BlockState each cropParts entry was collected for (the rebuild key). */
        public final BlockState[] partsFor = new BlockState[HydroponicsBedBlockEntity.QUADRANT_COUNT];
        /** Per-quadrant uniform render scale for scaled-fruit crops, and the flag selecting that path.
         *  Recomputed every extract from live growth (cheap), unlike the stage-cached parts above. */
        public final float[] cropScale = new float[HydroponicsBedBlockEntity.QUADRANT_COUNT];
        public final boolean[] scaledFruit = new boolean[HydroponicsBedBlockEntity.QUADRANT_COUNT];
        /** Model set the cache was built against; a change means a resource reload happened. */
        public BlockStateModelSet builtModelSet;
    }
}
