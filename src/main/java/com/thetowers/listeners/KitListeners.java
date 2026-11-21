package com.thetowers.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

import com.thetowers.Main;
import com.thetowers.game.KitHolder;
import com.thetowers.game.TeamHolder;

/**
 * Clicks in the selection menus (kits and teams).
 *
 * <p>Priority HIGH and <b>without</b> {@code ignoreCancelled}: in the waiting
 * lobby, {@link LobbyListeners} already cancels every inventory click (to
 * freeze the hotbar); this listener must still handle the kit or team choice.
 */
public class KitListeners implements Listener {

    private final Main main;

    public KitListeners(Main main) {
        this.main = main;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getInventory();
        if (top == null || !(top.getHolder() instanceof KitHolder
                || top.getHolder() instanceof TeamHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= top.getSize()) {
            return;
        }
        if (top.getHolder() instanceof KitHolder) {
            main.getKitMenu().handleClick((Player) event.getWhoClicked(),
                    (KitHolder) top.getHolder(), rawSlot);
        } else {
            main.getTeamMenu().handleClick((Player) event.getWhoClicked(),
                    (TeamHolder) top.getHolder(), rawSlot);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getInventory();
        if (top != null && (top.getHolder() instanceof KitHolder
                || top.getHolder() instanceof TeamHolder)) {
            event.setCancelled(true);
        }
    }
}
