package com.kestalkayden.hydrofarm.block;

import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Shared collision geometry for pipes and pipe terminals, indexed by {@link Direction#ordinal()}.
 *  {@link #arms()} are the 4×4 tubes that link to a neighbouring pipe; {@link #nozzles()} are the
 *  compact 6×6×6 port stubs that poke at an inventory (gray PORT or coloured EXTRACT/INSERT). */
final class PipeShapes {
    private PipeShapes() {}

    /** Number of distinct pipe geometries: each of the 6 faces is arm / nozzle / none = 3^6. */
    static final int SHAPE_COUNT = 729;

    /** Packs the 6 faces' shape roles (arm=1, nozzle=2, none=0) into a base-3 index in
     *  [0, {@link #SHAPE_COUNT}). Lets each pipe block memoize its combined collision/outline shape
     *  per distinct geometry instead of rebuilding it via {@code Shapes.or} on every
     *  getShape/getCollisionShape — the blockstate shape cache built at registration calls those
     *  thousands of times per block (a pipe carries one EnumProperty&lt;PipeFace&gt; per face, so it
     *  has many states, but only 3^6 distinct shapes). */
    static int shapeIndex(Function<Direction, PipeFace> face) {
        int idx = 0;
        for (Direction d : Direction.values()) {
            PipeFace f = face.apply(d);
            idx = idx * 3 + (f == PipeFace.PIPE ? 1 : f.isInventory() ? 2 : 0);
        }
        return idx;
    }

    /** Resolves which face a right-click targets among the {@code eligible} faces. Prefers the face
     *  the ray's normal points along; otherwise picks the eligible face whose outer plane is closest
     *  to the hit point — so on a multi-nozzle node, clicking near a given chest selects that chest's
     *  nozzle regardless of which sub-face the crosshair technically struck. Null if none eligible. */
    static Direction nearestFace(BlockPos pos, BlockHitResult hit, Predicate<Direction> eligible) {
        Direction clicked = hit.getDirection();
        if (eligible.test(clicked)) return clicked;
        Vec3 loc = hit.getLocation();
        double lx = (loc.x - pos.getX()) * 16.0;
        double ly = (loc.y - pos.getY()) * 16.0;
        double lz = (loc.z - pos.getZ()) * 16.0;
        Direction best = null;
        double bestDepth = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            if (!eligible.test(d)) continue;
            double depth = switch (d) {
                case NORTH -> lz;
                case SOUTH -> 16.0 - lz;
                case WEST  -> lx;
                case EAST  -> 16.0 - lx;
                case DOWN  -> ly;
                case UP    -> 16.0 - ly;
            };
            if (depth < bestDepth) { bestDepth = depth; best = d; }
        }
        return best;
    }

    static VoxelShape[] arms() {
        VoxelShape[] a = new VoxelShape[Direction.values().length];
        a[Direction.NORTH.ordinal()] = Block.box(6, 6, 0, 10, 10, 6);
        a[Direction.SOUTH.ordinal()] = Block.box(6, 6, 10, 10, 10, 16);
        a[Direction.WEST.ordinal()]  = Block.box(0, 6, 6, 6, 10, 10);
        a[Direction.EAST.ordinal()]  = Block.box(10, 6, 6, 16, 10, 10);
        a[Direction.DOWN.ordinal()]  = Block.box(6, 0, 6, 10, 6, 10);
        a[Direction.UP.ordinal()]    = Block.box(6, 10, 6, 10, 16, 10);
        return a;
    }

    static VoxelShape[] nozzles() {
        VoxelShape[] n = new VoxelShape[Direction.values().length];
        n[Direction.NORTH.ordinal()] = Block.box(5, 5, 0, 11, 11, 6);
        n[Direction.SOUTH.ordinal()] = Block.box(5, 5, 10, 11, 11, 16);
        n[Direction.WEST.ordinal()]  = Block.box(0, 5, 5, 6, 11, 11);
        n[Direction.EAST.ordinal()]  = Block.box(10, 5, 5, 16, 11, 11);
        n[Direction.DOWN.ordinal()]  = Block.box(5, 0, 5, 11, 6, 11);
        n[Direction.UP.ordinal()]    = Block.box(5, 10, 5, 11, 16, 11);
        return n;
    }
}
