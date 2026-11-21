package com.thetowers.listeners;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.arena.State;
import com.thetowers.compat.Sounds;
import com.thetowers.lang.Messages;
import com.thetowers.lobby.LobbyItems;

/**
 * Hotbar items in the waiting lobby: the wool block (choose your team), the
 * sword (choose a kit) and the barrier (or bed, or any configured material) to
 * leave.
 *
 * <p>During the wait, dropping or moving these items is also prevented so they
 * stay in place.
 *
 * <p>The materials of the items are read from the {@code lobby-items:} section
 * of config.yml (see {@link LobbyItems}). Thus, if the admin replaces the sword
 * with a nether star, the right kit-menu click still opens.
 *
 * <p>The leave item has a countdown: the first right click announces the
 * total time ({@code lobby-items.leave-countdown} in config.yml, in seconds)
 * and plays a low note pling; the last five seconds are counted in chat
 * (5, 4, 3, 2, 1 - one message per second); a second right click before it
 * ends cancels the leave. 0 = leaves instantly.
 */
public class LobbyListeners implements Listener {

    private final Main main;
    /** Pending leave countdowns: player id -> task. A second click cancels it. */
    private final Map<UUID, BukkitRunnable> pendingLeaves = new HashMap<UUID, BukkitRunnable>();

    public LobbyListeners(Main main) {
        this.main = main;
    }

    // No ignoreCancelled here: in adventure mode, clicking a block (the
    // barrier) with a block-item makes the event "cancelled" by Bukkit, which
    // would skip it. We want to handle it anyway.
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!waiting(event.getPlayer())) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        // Only genuine (tagged) lobby items trigger actions, so a player who
        // refilled the hotbar with random items cannot trigger them.
        if (!com.thetowers.lobby.ItemTagger.isProtected(item)) {
            return;
        }
        Arena arena = main.getArenaManager().getArenaOf(event.getPlayer());
        Material type = item.getType();
        // Materials are read from config.yml: a click on the item whose
        // material matches "kits" opens the kit menu, etc. The team selector
        // is recognized by its display name (its wool color varies with the
        // chosen team). If two items share a material, the first branch wins.
        if (LobbyItems.isTeamItem(main, item)) {
            event.setCancelled(true);
            main.getTeamMenu().open(event.getPlayer(), arena);
        } else if (matches(type, LobbyItems.kitsMaterial(main))) {
            event.setCancelled(true);
            main.getKitMenu().open(event.getPlayer());
        } else if (matches(type, LobbyItems.leaveMaterial(main))) {
            event.setCancelled(true);
            toggleLeave(event.getPlayer(), arena);
        }
    }

    /**
     * Starts or cancels the leave countdown. The first click announces the
     * total delay and plays a note pling (pitch 0); the last five seconds are
     * counted in chat (5, 4, 3, 2, 1). A second click within that delay
     * cancels it. A 0 delay leaves instantly.
     */
    private void toggleLeave(final Player player, final Arena arena) {
        int delay = main.getLobbyLeaveCountdown();
        UUID id = player.getUniqueId();

        // A pending countdown? Cancel it instead of leaving.
        BukkitRunnable pending = pendingLeaves.remove(id);
        if (pending != null) {
            pending.cancel();
            player.sendMessage(Main.PREFIX + Messages.tr("lobby.leave_countdown_cancel"));
            return;
        }

        if (delay <= 0) {
            arena.leave(player, true);
            return;
        }

        // One announcement with the TOTAL time, then the last five seconds
        // are counted in chat (5, 4, 3, 2, 1 - one message per second). A very
        // low note pling accompanies the countdown start.
        player.sendMessage(Main.PREFIX + Messages.tr("lobby.leave_countdown_start",
                "time", Integer.toString(delay)));
        Sounds.leavePing(player);
        final BukkitRunnable task = new BukkitRunnable() {
            private int left = delay;

            @Override
            public void run() {
                left--;
                if (left <= 0) {
                    pendingLeaves.remove(id);
                    if (player.isOnline()) {
                        arena.leave(player, true);
                    }
                    cancel();
                    return;
                }
                if (left <= 5) {
                    player.sendMessage(Main.PREFIX + Messages.tr("lobby.leave_countdown_number",
                            "n", left));
                }
            }
        };
        pendingLeaves.put(id, task);
        task.runTaskTimer(main, 20L, 20L);
    }

    /** True if {@code clicked} is the expected material ({@code null} safe). */
    private static boolean matches(Material clicked, Material expected) {
        return expected != null && expected == clicked;
    }

    /** Lobby items are not dropped. */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (waiting(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * Lobby items are not moved in the inventory. The whole inventory is frozen
     * (no click, no shift-click, no swap) both in an arena waiting room and in
     * the main-lobby world, exactly like a frozen hotbar. This applies to
     * EVERYONE, admins included: the admin bypass is only for building and
     * breaking blocks in the lobby (see {@code LobbyProtectionListener}), not
     * for rearranging the lobby hotbar items. Menus are still usable: their
     * clicks are cancelled first by {@code MenuProtectionListener} (LOWEST),
     * and since this handler uses {@code ignoreCancelled} it then lets the
     * menu's own listener route the click.
     *
     * <p>The freeze triggers on any of: the player is in a waiting arena, the
     * player is in the main-lobby world, or the player simply carries a tagged
     * lobby/spectator hotbar item (the last is a fallback that catches the
     * lobby even if the world check fails; only lobby/pre-lobby/spectator
     * items are tagged, never kit items, so active players are unaffected).
     */
    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (shouldFreeze(player)) {
            event.setCancelled(true);
        }
    }

    /** Same freeze for drags: no rearranging lobby items by dragging. */
    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (shouldFreeze(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * Whether this player's inventory must be frozen right now.
     *
     * <p>The freeze is on unless {@code lobby-protection.inventory-protection}
     * is {@code false}; admins ({@code thetowers.admin}) always keep full
     * inventory access in the lobby (the admin bypass covers building/breaking
     * and the hotbar alike, so an admin can manage their inventory while
     * setting up). Menus are unaffected: their clicks are cancelled first by
     * {@code MenuProtectionListener} (LOWEST), and this handler uses
     * {@code ignoreCancelled}, so it then lets the menu's own listener route
     * the click.
     */
    private boolean shouldFreeze(Player player) {
        if (!main.getConfig().getBoolean("lobby-protection.inventory-protection", true)) {
            return false;
        }
        if (player.hasPermission("thetowers.admin")) {
            return false;
        }
        return waiting(player) || inMainLobby(player) || hasProtectedHotbar(player);
    }

    /** True if the player is in the main-lobby world and not in an arena. */
    private boolean inMainLobby(Player player) {
        if (main.getArenaManager().getArenaOf(player) != null) {
            return false;
        }
        Location lobby = main.getServerLobby();
        return lobby != null && player.getWorld().equals(lobby.getWorld());
    }

    /**
     * True if the player carries any tagged lobby/pre-lobby/spectator hotbar
     * item. Kit items are never tagged, so an active player is never frozen by
     * this fallback.
     */
    private boolean hasProtectedHotbar(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (com.thetowers.lobby.ItemTagger.isProtected(item)) {
                return true;
            }
        }
        return false;
    }

    /** Clear any pending leave countdown when the player quits. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        BukkitRunnable pending = pendingLeaves.remove(event.getPlayer().getUniqueId());
        if (pending != null) {
            pending.cancel();
        }
    }

    /** True if the player is in an arena in the waiting phase. */
    /**
     * Main-lobby custom items: when the player is in NO arena and clicks
     * (left or right) an item in their hotbar, the commands of the matching
     * main-lobby slot (lobby-items.yml) are run.
     */
    @EventHandler
    public void onMainLobbyInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action == Action.PHYSICAL) {
            return;
        }
        Player player = event.getPlayer();
        if (main.getArenaManager().getArenaOf(player) != null) {
            return; // in an arena: the arena-lobby handler takes over
        }
        // Main-lobby items only act in the main-lobby world, and only when the
        // held item is actually one of our tagged lobby items: otherwise a
        // player who cleared their inventory and refilled the same slots in
        // another world would still trigger the commands.
        Location lobby = main.getServerLobby();
        if (lobby == null || !player.getWorld().equals(lobby.getWorld())) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        if (!com.thetowers.lobby.ItemTagger.isProtected(item)) {
            return;
        }
        int heldSlot = player.getInventory().getHeldItemSlot();
        if (com.thetowers.lobby.MainLobbyItems.handleClick(main, player, heldSlot)) {
            event.setCancelled(true);
        }
    }

    private boolean waiting(Player player) {
        Arena arena = main.getArenaManager().getArenaOf(player);
        return arena != null
                && (arena.isState(State.WAITING) || arena.isState(State.STARTING));
    }
}
