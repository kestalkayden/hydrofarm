package com.kestalkayden.hydrofarm.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Client-bound open payload for a pipe-terminal GUI: which terminal ({@link #pos}) and which of its
 *  faces ({@link #face}) is being configured. Unlike the other menus (which ship only a BlockPos), a
 *  terminal is per-face, so the face must travel too. Fabric registers {@link #STREAM_CODEC} on the
 *  ExtendedMenuType; NeoForge writes/reads the same two values through the menu buffer. */
public record TerminalMenuData(BlockPos pos, Direction face) {

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalMenuData> STREAM_CODEC =
        StreamCodec.of(
            (buf, data) -> { buf.writeBlockPos(data.pos()); buf.writeEnum(data.face()); },
            buf -> new TerminalMenuData(buf.readBlockPos(), buf.readEnum(Direction.class)));
}
