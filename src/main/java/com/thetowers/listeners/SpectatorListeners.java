package com.thetowers.listeners;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.compat.TTMaterial;
import com.thetowers.game.SpectatorHolder;
import com.thetowers.lang.Messages;
import com.thetowers.region.Cuboid;

/**
 * Everything a spectator must not be able to do (touch blocks, chests, move or
 * drop their items), and the use of their two items: the compass (player list)
 * and the barrier (leave).
 *
 * <p>They are not in Minecraft's "spectator" mode but in adventure: only their
 * clicks therefore need blocking here. Damage, hunger and pickup are already
 * covered by the other listeners (a spectator does not take part) and by
 * {@code Compat.makeSpectator}.
 */
public class SpectatorListeners implements Listener {

    /** Delay between two "you cannot go further" reminders, in ms. */
    private static final long WARN_INTERVAL_MS = 3000L;

    private final Main main;
    private final Map<UUID, Long> lastWarning = new HashMap<UUID, Long>();

    public SpectatorListeners(Main main) {
        this.main = main;
    }

    /** The arena this player watches, or {@code null} if they are not a spectator. */
    private Arena spectatorArena(Player player) {
        Arena arena = main.getArenaManager().getArenaOf(player);
        return arena != null && arena.isSpectator(player) ? arena : null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Arena arena = spectatorArena(player);
        if (arena == null) {
            return;
        }
        // Nothing is ever activated by a spectator: chest, door, lever,
        // pressure plate.
        event.setCancelled(true);
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        // Only genuine (tagged) spectator items trigger actions.
        if (!com.thetowers.lobby.ItemTagger.isProtected(item)) {
            return;
        }
        if (TTMaterial.COMPASS.is(item.getType())) {
            main.getSpectatorMenu().open(player, arena);
        } else if (TTMaterial.BARRIER.is(item.getType())) {
            arena.leave(player, true);
            player.sendMessage(Main.PREFIX + Messages.tr("command.left_arena", "arena", arena.getName()));
        }
    }

    /**
     * A spectator stays inside the play area of the arena (see
     * {@code Arena#getArea}): they are pushed back at the edge. If they are
     * already outside (teleported by another plugin...), they are brought back
     * to the spectator spawn point.
     */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return;
        }
        if (!main.isSpectatorBounded()) {
            return;
        }
        Player player = event.getPlayer();
        Arena arena = spectatorArena(player);
        if (arena == null) {
            return;
        }
        Cuboid area = arena.getArea();
        if (area == null || area.contains(to)) {
            return;
        }
        if (area.contains(from)) {
            event.setCancelled(true);
        } else {
            Location spot = arena.spectatorSpot();
            if (spot != null && spot.getWorld() != null) {
                player.teleport(spot);
            }
        }
        long now = System.currentTimeMillis();
        Long last = lastWarning.get(player.getUniqueId());
        if (last == null || now - last.longValue() >= WARN_INTERVAL_MS) {
            lastWarning.put(player.getUniqueId(), Long.valueOf(now));
            player.sendMessage(Main.PREFIX + Messages.tr("spectator.out_of_bounds"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastWarning.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (spectatorArena(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    /** The player menu reacts to clicks; everywhere else, no item ever moves. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        Inventory top = event.getInventory();
        if (top != null && top.getHolder() instanceof SpectatorHolder) {
            event.setCancelled(true);
            int rawSlot = event.getRawSlot();
            if (rawSlot >= 0 && rawSlot < top.getSize()) {
                main.getSpectatorMenu().handleClick(player, (SpectatorHolder) top.getHolder(), rawSlot);
            }
            return;
        }
        if (spectatorArena(player) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        Inventory top = event.getInventory();
        if ((top != null && top.getHolder() instanceof SpectatorHolder) || spectatorArena(player) != null) {
            event.setCancelled(true);
        }
    }

    /** No container opens for a spectator, except their own menu. */
    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        Inventory inventory = event.getInventory();
        if (inventory != null && inventory.getHolder() instanceof SpectatorHolder) {
            return;
        }
        if (spectatorArena((Player) event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (spectatorArena(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }
}
