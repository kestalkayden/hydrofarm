package com.kestalkayden.hydrofarm.platform;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;

import com.kestalkayden.hydrofarm.menu.TerminalMenuData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Fabric implementation of {@link MenuApi}. Wraps the server factory in an ExtendedMenuProvider
 *  so the block's BlockPos is sent to the client via the menu type's registered stream codec. */
public final class FabricMenuApi implements MenuApi {

    @Override
    public void open(Player player, Component title, MenuFactory factory, BlockPos pos) {
        player.openMenu(new ExtendedMenuProvider<BlockPos>() {
            @Override
            public Component getDisplayName() {
                return title;
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory inv, Player p) {
                return factory.create(containerId, inv, p);
            }

            @Override
            public BlockPos getScreenOpeningData(ServerPlayer p) {
                return pos;
            }
        });
    }

    @Override
    public void openTerminal(Player player, Component title, MenuFactory factory, BlockPos pos, Direction face) {
        player.openMenu(new ExtendedMenuProvider<TerminalMenuData>() {
            @Override
            public Component getDisplayName() {
                return title;
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory inv, Player p) {
                return factory.create(containerId, inv, p);
            }

            @Override
            public TerminalMenuData getScreenOpeningData(ServerPlayer p) {
                return new TerminalMenuData(pos, face);
            }
        });
    }
}
