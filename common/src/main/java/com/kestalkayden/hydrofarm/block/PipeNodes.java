package com.kestalkayden.hydrofarm.block;

import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.platform.HydrofarmPlatform;

/** Keeps a pipe node on the correct block type for what it touches: a plain pipe while it only links
 *  to pipes, or the BE-backed terminal as soon as it touches any inventory (so even an unconfigured
 *  gray PORT reads as a terminal — e.g. in Jade — and carries a block entity ready to configure).
 *  Runs on a scheduled tick, recomputing every face and swapping the block in place if needed. */
final class PipeNodes {
    private PipeNodes() {}

    static void normalizeItem(ServerLevel level, BlockPos pos) {
        normalize(level, pos,
            HydrofarmRefs.ITEM_PIPE.get(), HydrofarmRefs.ITEM_PIPE_TERMINAL.get(),
            ItemPipeBlock::faceProp, ItemPipeTerminalBlock::faceProp,
            ItemPipeBlock::isPipe,
            (np, dir) -> HydrofarmPlatform.items().find(level, np, dir.getOpposite()) != null);
    }

    static void normalizeFluid(ServerLevel level, BlockPos pos) {
        normalize(level, pos,
            HydrofarmRefs.LIQUID_PIPE.get(), HydrofarmRefs.LIQUID_PIPE_TERMINAL.get(),
            LiquidPipeBlock::faceProp, LiquidPipeTerminalBlock::faceProp,
            LiquidPipeBlock::isPipe,
            (np, dir) -> HydrofarmPlatform.fluids().find(level, np, dir.getOpposite()) != null);
    }

    private static void normalize(ServerLevel level, BlockPos pos, Block pipeBlock, Block terminalBlock,
                                   Function<Direction, EnumProperty<PipeFace>> pipeFace,
                                   Function<Direction, EnumProperty<PipeFace>> termFace,
                                   Predicate<BlockState> isPipeNeighbor,
                                   BiPredicate<BlockPos, Direction> hasInventory) {
        BlockState state = level.getBlockState(pos);
        boolean isTerminal = state.is(terminalBlock);
        if (!isTerminal && !state.is(pipeBlock)) return;   // not ours (shouldn't happen)
        Function<Direction, EnumProperty<PipeFace>> faceOf = isTerminal ? termFace : pipeFace;

        PipeFace[] desired = new PipeFace[Direction.values().length];
        boolean anyInventory = false;
        for (Direction d : Direction.values()) {
            BlockPos np = pos.relative(d);
            BlockState nb = level.getBlockState(np);
            PipeFace next;
            if (isPipeNeighbor.test(nb)) {
                next = PipeFace.PIPE;
            } else if (hasInventory.test(np, d)) {
                PipeFace cur = state.getValue(faceOf.apply(d));
                next = cur.isPort() ? cur : PipeFace.PORT;   // keep EXTRACT/INSERT, else gray PORT
                anyInventory = true;
            } else {
                next = PipeFace.NONE;
            }
            desired[d.ordinal()] = next;
        }

        Block target = anyInventory ? terminalBlock : pipeBlock;
        Function<Direction, EnumProperty<PipeFace>> targetFace = anyInventory ? termFace : pipeFace;
        BlockState result = target.defaultBlockState();
        for (Direction d : Direction.values()) {
            result = result.setValue(targetFace.apply(d), desired[d.ordinal()]);
        }
        if (!result.equals(state)) {
            level.setBlock(pos, result, Block.UPDATE_ALL);
            TransportNetwork.bump();
        }
    }
}
