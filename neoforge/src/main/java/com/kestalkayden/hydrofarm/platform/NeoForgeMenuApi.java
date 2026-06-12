package com.kestalkayden.hydrofarm.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** NeoForge implementation of {@link MenuApi}: a SimpleMenuProvider plus a buffer writer that
 *  ships the BlockPos to the client (read back in the menu's client constructor, which the
 *  loader's menu-type registration decodes). */
public final class NeoForgeMenuApi implements MenuApi {

    @Override
    public void open(Player player, Component title, MenuFactory factory, BlockPos pos) {
        if (!(player instanceof ServerPlayer sp)) return;
        sp.openMenu(
            new SimpleMenuProvider((containerId, inv, p) -> factory.create(containerId, inv, p), title),
            buf -> buf.writeBlockPos(pos));
    }

    @Override
    public void openTerminal(Player player, Component title, MenuFactory factory, BlockPos pos, Direction face) {
        if (!(player instanceof ServerPlayer sp)) return;
        sp.openMenu(
            new SimpleMenuProvider((containerId, inv, p) -> factory.create(containerId, inv, p), title),
            buf -> { buf.writeBlockPos(pos); buf.writeEnum(face); });
    }
}
