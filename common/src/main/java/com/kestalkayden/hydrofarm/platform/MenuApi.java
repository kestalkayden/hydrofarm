package com.kestalkayden.hydrofarm.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Platform seam for opening a block's container menu and shipping its {@link BlockPos} to the
 *  client. Fabric uses an ExtendedMenuProvider + the menu type's stream codec; NeoForge uses a
 *  SimpleMenuProvider + a buffer writer. Shared code calls {@link #open} with a server-side menu
 *  factory and the block position; the loader handles the rest. */
public interface MenuApi {

    /** Opens {@code factory}'s menu for {@code player}, titling it {@code title} and sending
     *  {@code pos} to the client so its menu can rebind to the block. No-op for non-server
     *  players. */
    void open(Player player, Component title, MenuFactory factory, BlockPos pos);

    /** Like {@link #open}, but ships a {@code (pos, face)} pair instead of just a position — for
     *  pipe-terminal menus, which are configured per face. The client payload is a
     *  {@link com.kestalkayden.hydrofarm.menu.TerminalMenuData}. */
    void openTerminal(Player player, Component title, MenuFactory factory, BlockPos pos, Direction face);

    /** Server-side menu construction (sees the BE/world); the loader invokes it when the screen
     *  actually opens. */
    @FunctionalInterface
    interface MenuFactory {
        AbstractContainerMenu create(int containerId, Inventory inv, Player player);
    }
}
