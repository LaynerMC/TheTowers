package com.thetowers.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;

import com.thetowers.api.TTMenuHolder;

/**
 * Protects every TheTowers menu from item theft.
 *
 * <p>Any inventory whose holder is a {@link TTMenuHolder} (config, kits, teams,
 * spectator, stats...) is frozen: clicks, drags and hopper/dropper item moves
 * are all cancelled, so a player can never take a menu icon out or push their
 * own items in.
 *
 * <p>Runs at {@link EventPriority#LOWEST} on click/drag so it cancels FIRST;
 * each menu's own listener (config, kits, spectator...) does not use
 * {@code ignoreCancelled}, so it still runs afterwards and routes the click to
 * its action. The cancel is what keeps the items in place.
 */
public class MenuProtectionListener implements Listener {

    public MenuProtectionListener() {
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getInventory();
        if (top != null && top.getHolder() instanceof TTMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getInventory();
        if (top != null && top.getHolder() instanceof TTMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        // A hopper/dropper pulling from or pushing into a menu inventory:
        // menus are virtual (player-held), but this is cheap insurance.
        if (isMenu(event.getSource()) || isMenu(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    private static boolean isMenu(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof TTMenuHolder;
    }
}
