package com.thetowers.api;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker implemented by every {@link InventoryHolder} used for a TheTowers
 * menu (config, kits, teams, spectator, stats...).
 *
 * <p>A single {@code MenuProtectionListener} cancels every click, drag and
 * item move on an inventory whose holder is a {@code TTMenuHolder}, so that no
 * menu item can ever be taken out by a player. Each menu's own listener still
 * runs afterwards (it does not use {@code ignoreCancelled}) to route the click
 * to its action.
 */
public interface TTMenuHolder extends InventoryHolder {

    @Override
    Inventory getInventory();
}
