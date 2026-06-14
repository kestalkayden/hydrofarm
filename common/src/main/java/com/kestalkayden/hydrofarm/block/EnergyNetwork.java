package com.kestalkayden.hydrofarm.block;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.platform.EnergyApi;
import com.kestalkayden.hydrofarm.platform.EnergyBuffer;
import com.kestalkayden.hydrofarm.platform.HydrofarmPlatform;
import com.kestalkayden.hydrofarm.util.Backoff;

/** Per-block helper that pulls energy from the connected {@link EnergyPipeBlock} network into an
 *  {@link EnergyBuffer} (the sink-pull model — cables are passive). Walks the cable network from the
 *  block's neighbours, collects the boundary blocks that expose an energy endpoint (generators, tech
 *  machines), and moves energy from each into the buffer's own (insert-only) endpoint.
 *
 *  <p>Holds the source-set cache (epoch + TTL, like the pumps), so it's one instance per BE. */
public final class EnergyNetwork {

    private static final long NET_CACHE_TTL = 40;
    /** Pull back-off: when a pull moves nothing (sources dry/absent), skip an exponentially growing
     *  number of subsequent pulls so a powered sink on a dead grid stops re-resolving every endpoint
     *  every interval. Cleared on a full buffer, a successful pull, or a topology change. */
    private static final int PULL_RAMP_CAP = 4;   // max skip = 1<<4 = 16 pulls (~4 s at a 5-tick cadence)

    private List<Source> cachedSources;
    private long cachedEpoch = Long.MIN_VALUE;
    private long cachedTick = Long.MIN_VALUE;

    /** Loader-backed capability caches (NeoForge BlockCapabilityCache / Fabric BlockApiCache):
     *  one per cached source, carried across walk refreshes while the source persists, plus one
     *  for this block's own endpoint — so steady-state pulls/pushes resolve ZERO capabilities
     *  instead of re-resolving every boundary endpoint every interval. */
    private Map<Source, EnergyApi.EndpointCache> endpointCaches = new HashMap<>();
    private EnergyApi.EndpointCache selfCache;

    private final Backoff pullBackoff = new Backoff(1, 1 << PULL_RAMP_CAP, PULL_RAMP_CAP);
    private long pullEpoch = Long.MIN_VALUE;

    private record Source(BlockPos pos, Direction side) {}

    /** The cached resolver for this block's own endpoint (insert side for pull, extract for push). */
    private EnergyApi.Endpoint self(ServerLevel level, BlockPos pos) {
        if (selfCache == null) {
            selfCache = HydrofarmPlatform.energy().cache(level, pos, Direction.UP);
        }
        return selfCache.get();
    }

    /** The cached resolver for one boundary source; falls back to a direct find for a source that
     *  somehow isn't in the map (defensive — sources() always populates it). */
    private EnergyApi.Endpoint endpointOf(ServerLevel level, Source src) {
        EnergyApi.EndpointCache c = endpointCaches.get(src);
        return c != null ? c.get() : HydrofarmPlatform.energy().find(level, src.pos(), src.side());
    }

    /** Top up {@code buffer} from the network, moving at most {@code maxPerPull} this call. Backs off
     *  (see {@link #PULL_RAMP_CAP}) when a call moves nothing, so a powered sink on a dead/dry grid
     *  doesn't re-resolve every boundary endpoint every interval — symmetric with the generator's push. */
    public void pull(ServerLevel level, BlockPos pos, EnergyBuffer buffer, int maxPerPull) {
        int room = buffer.getEnergyCapacity() - buffer.getEnergyStored();
        if (room <= 0) {            // buffer full — well supplied; clear back-off so a fresh drain re-probes at once
            pullBackoff.reset();
            return;
        }

        // Topology changed (a generator/cable may have just been wired in) → drop any back-off and
        // re-probe now, mirroring how the pipe terminals wake their backed-off faces on topo change.
        long epoch = TransportNetwork.epoch();
        if (epoch != pullEpoch) {
            pullEpoch = epoch;
            pullBackoff.reset();
        }
        if (!pullBackoff.ready()) return;

        int moved = 0;
        List<Source> sources = sources(level, pos);
        if (!sources.isEmpty()) {
            EnergyApi.Endpoint self = self(level, pos);
            if (self != null) {   // our own insert capability — should always resolve
                int budget = Math.min(maxPerPull, room);
                for (Source src : sources) {
                    if (budget <= 0) break;
                    EnergyApi.Endpoint endpoint = endpointOf(level, src);
                    if (endpoint == null || endpoint.extractableEnergy() <= 0) continue;
                    int m = endpoint.moveTo(self, budget);
                    budget -= m;
                    moved += m;
                }
            }
        }

        if (moved > 0) {
            pullBackoff.recordMoved();
        } else {
            pullBackoff.recordFutile();
        }
    }

    /** Push this block's own (extract-capable) energy out to every insert-capable endpoint on the
     *  connected cable network — so a Hydrofarm source powers tech-mod machines (TechReborn, AE2
     *  acceptors, …) and Hydrofarm sinks alike. Boundary blocks that can't take energy simply
     *  accept 0, so the same walk serves both sources and sinks. */
    public void push(ServerLevel level, BlockPos pos, int maxPerPush) {
        EnergyApi.Endpoint self = self(level, pos);
        if (self == null || self.extractableEnergy() <= 0) return;
        List<Source> endpoints = sources(level, pos);
        if (endpoints.isEmpty()) return;

        int budget = Math.min(maxPerPush, self.extractableEnergy());
        for (Source s : endpoints) {
            if (budget <= 0) break;
            EnergyApi.Endpoint endpoint = endpointOf(level, s);
            if (endpoint == null) continue;
            budget -= self.moveTo(endpoint, budget);
        }
    }

    private List<Source> sources(ServerLevel level, BlockPos pos) {
        long epoch = TransportNetwork.epoch();
        long now = level.getGameTime();
        if (cachedSources != null && cachedEpoch == epoch
                && now - cachedTick < NET_CACHE_TTL + TransportNetwork.cacheTtlJitter(pos)) {
            return cachedSources;
        }
        cachedSources = findSources(level, pos);
        cachedEpoch = epoch;
        cachedTick = now;
        // Refresh the per-source capability caches, carrying over entries for sources still present
        // (so the loader-side caches live as long as the source does) and dropping vanished ones.
        Map<Source, EnergyApi.EndpointCache> next = new HashMap<>(Math.max(8, cachedSources.size() * 2));
        EnergyApi api = HydrofarmPlatform.energy();
        for (Source s : cachedSources) {
            EnergyApi.EndpointCache c = endpointCaches.get(s);
            next.put(s, c != null ? c : api.cache(level, s.pos(), s.side()));
        }
        endpointCaches = next;
        return cachedSources;
    }

    private List<Source> findSources(ServerLevel level, BlockPos start) {
        List<Source> result = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> recorded = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        visited.add(start);
        for (Direction d : Direction.values()) {
            seed(level, start, d, start, queue, visited, recorded, result);
        }
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (!level.hasChunkAt(current)) continue;   // never force-load an edge chunk from a tick
            BlockState s = level.getBlockState(current);
            if (!s.is(HydrofarmRefs.ENERGY_PIPE.get())) continue;
            for (Direction d : Direction.values()) {
                seed(level, current, d, start, queue, visited, recorded, result);
            }
        }
        return result;
    }

    private void seed(ServerLevel level, BlockPos from, Direction d, BlockPos start, Deque<BlockPos> queue,
                      Set<BlockPos> visited, Set<BlockPos> recorded, List<Source> result) {
        BlockPos next = from.relative(d);
        if (next.equals(start)) return;
        if (!level.hasChunkAt(next)) return;
        BlockState s = level.getBlockState(next);
        if (s.is(HydrofarmRefs.ENERGY_PIPE.get())) {
            if (visited.add(next)) queue.add(next);
        } else if (recorded.add(next)) {
            result.add(new Source(next, d.getOpposite()));
        }
    }
}
