package com.kestalkayden.hydrofarm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluid;

import com.kestalkayden.hydrofarm.platform.FluidApi;
import com.kestalkayden.hydrofarm.platform.HydrofarmPlatform;

/** Direct face-to-face fluid transfer between a machine and its six immediate neighbours, layered
 *  <em>on top of</em> the liquid-pipe network. A machine calls {@link #pull}/{@link #push} on its own
 *  tick to draw fuel from — or offload product into — a directly-touching storage block (a Hydrofarm
 *  tank or siphon, or any other mod's tank) with no pipe required.
 *
 *  <p>Capability-pure: only the requested {@code fluid} is moved, and only through the standard
 *  {@link FluidApi} endpoint, so each block's own insert-only / extract-only rules decide what
 *  actually moves. Consequences worth noting:
 *  <ul>
 *    <li>Liquid pipes and terminals expose no fluid capability, so {@code find} returns {@code null}
 *        for them and they're skipped — adjacency never fights the pipe network.</li>
 *    <li>Beds and the sprinkler expose water as insert-only, so a {@link #pull} can never drain a
 *        running farm; only tanks and siphons surrender water.</li>
 *    <li>A {@link #push} of water is refused by anything that doesn't take water (e.g. the Mending
 *        Station, which only accepts Liquid XP), so nothing gets the wrong fluid dumped into it.</li>
 *  </ul>
 */
public final class FluidNeighbors {

    private FluidNeighbors() {}

    /** Fill this block's own (insert-capable) endpoint with up to {@code maxMb} of {@code fluid}
     *  pulled from directly-adjacent storage. <b>Cap {@code maxMb} to your free space</b>: the hop is
     *  atomic/all-or-nothing, so an over-large request against a near-full buffer would move nothing.
     *  Returns mB actually moved. */
    public static int pull(ServerLevel level, BlockPos pos, Fluid fluid, int maxMb) {
        if (maxMb <= 0) return 0;
        FluidApi api = HydrofarmPlatform.fluids();
        FluidApi.Endpoint self = api.find(level, pos, Direction.UP);
        if (self == null) return 0;   // our own insert capability — should always resolve
        int budget = maxMb;
        for (Direction d : Direction.values()) {
            if (budget <= 0) break;
            BlockPos np = pos.relative(d);
            if (!level.hasChunkAt(np)) continue;      // never force-load an edge chunk from a tick
            FluidApi.Endpoint nb = api.find(level, np, d.getOpposite());
            if (nb == null) continue;                 // pipes/terminals + non-fluid blocks: skipped
            // budget never exceeds our remaining room (caller capped it, and each move shrinks both
            // budget and room by the same amount), so the all-or-nothing insert side always commits.
            budget -= nb.moveTo(self, fluid, budget);
        }
        return maxMb - budget;
    }

    /** Offload up to {@code maxMb} of {@code fluid} from this block's own (extract-capable) endpoint
     *  into directly-adjacent acceptors. <b>Cap {@code maxMb} to your stored amount.</b> Unlike a
     *  raw {@code moveTo}, this inserts into the neighbour first and then extracts exactly what it
     *  accepted, so it's partial-friendly — a near-full or small tank still takes what it can rather
     *  than rejecting the whole hop — and stays leak-free (the source is guaranteed to hold what we
     *  remove, so no duplication). Returns mB actually moved. */
    public static int push(ServerLevel level, BlockPos pos, Fluid fluid, int maxMb) {
        if (maxMb <= 0) return 0;
        FluidApi api = HydrofarmPlatform.fluids();
        FluidApi.Endpoint self = api.find(level, pos, Direction.UP);
        if (self == null) return 0;   // our own extract capability — should always resolve
        int budget = maxMb;
        for (Direction d : Direction.values()) {
            if (budget <= 0) break;
            BlockPos np = pos.relative(d);
            if (!level.hasChunkAt(np)) continue;
            FluidApi.Endpoint nb = api.find(level, np, d.getOpposite());
            if (nb == null) continue;
            int accepted = nb.insert(fluid, budget);
            if (accepted > 0) {
                self.extract(fluid, accepted);        // budget <= our stored amount, so this removes exactly `accepted`
                budget -= accepted;
            }
        }
        return maxMb - budget;
    }
}
