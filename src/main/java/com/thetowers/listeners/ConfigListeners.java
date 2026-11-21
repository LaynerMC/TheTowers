package com.thetowers.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import com.thetowers.Main;
import com.thetowers.config.ConfigHolder;

/**
 * Clicks in configuration menus and position capture in "placement mode".
 *
 * <p>A config menu is recognized by its {@link ConfigHolder}, and top/bottom
 * is distinguished by {@code getRawSlot() < getInventory().getSize()}
 * (InventoryView is never used). No item movement is ever allowed in a
 * config menu: every click there is cancelled.
 */
public class ConfigListeners implements Listener {

    private final Main main;

    public ConfigListeners(Main main) {
        this.main = main;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getInventory();
        if (top == null || !(top.getHolder() instanceof ConfigHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= top.getSize()) {
            return; // click in the player's own inventory or outside the window: no effect.
        }
        main.getConfigMenu().handleClick(player, (ConfigHolder) top.getHolder(),
                rawSlot, event.isRightClick());
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getInventory();
        if (top != null && top.getHolder() instanceof ConfigHolder) {
            event.setCancelled(true);
        }
    }

    /**
     * Placement mode: a right click in the world captures a position or a
     * zone corner (the targeted block). Priority LOWEST: in Bukkit the lowest
     * priorities run FIRST. This consumes the click before any other use
     * (chest opening, join sign...).
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!main.getPlacements().isPlacing(player)) {
            return;
        }
        if (main.getPlacements().handleInteract(player,
                action == Action.RIGHT_CLICK_BLOCK ? event.getClickedBlock() : null)) {
            event.setCancelled(true);
        }
    }

    /**
     * Name of an arena created from the menu: the admin's message is captured
     * (never broadcast) then processed on the main thread, since chat arrives
     * on an async thread. Priority LOWEST: removed before chat plugins see it.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        final Player player = event.getPlayer();
        if (!main.getArenaNaming().isNaming(player)) {
            return;
        }
        event.setCancelled(true);
        final String message = event.getMessage();
        Bukkit.getScheduler().runTask(main, new Runnable() {
            @Override
            public void run() {
                main.getArenaNaming().handleInput(player, message);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        main.getPlacements().clear(event.getPlayer());
        main.getArenaNaming().clear(event.getPlayer());
    }
}
