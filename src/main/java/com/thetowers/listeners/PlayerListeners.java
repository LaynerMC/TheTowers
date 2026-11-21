package com.thetowers.listeners;

import java.util.Iterator;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.compat.Compat;
import com.thetowers.compat.TTMaterial;
import com.thetowers.lang.Messages;

/**
 * Joins, quits, deaths and respawns.
 *
 * <p>The Towers is a permanent-respawn game: a participant who dies drops
 * their inventory (steal it!) and immediately returns to their base with the
 * starting kit. Nobody is ever eliminated.
 */
public class PlayerListeners implements Listener {

    /** Holders of this permission are not auto-joined in arena mode. */
    private static final String ARENA_MODE_BYPASS = "thetowers.arenamode.bypass";

    private final Main main;

    public PlayerListeners(Main main) {
        this.main = main;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        main.getStatsManager().load(player);
        main.getKitManager().loadSelection(player);
        // Coin balance and kit purchases are part of the player's stats and are
        // loaded together by StatsManager.load above (no separate files).

        // HUD (scoreboard + TAB) applied right away instead of waiting for the
        // next tick of the one-second refresh timer. The LuckPerms prefix is
        // also refreshed (one tick later, so PlaceholderAPI is ready).
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    main.getScoreboardManager().updateAll();
                    main.getStatsManager().refreshPrefix(player);
                }
            }
        }.runTaskLater(main, 1L);

        // Reconnection: the player still had their spot in a running game.
        // Runs before arena mode, which would refuse them ("game already
        // running").
        if (main.getArenaManager().getRejoinableArena(player.getUniqueId()) != null) {
            scheduleRejoin(player);
            return;
        }
        // Disconnected without coming back in time: they would respawn in the
        // middle of a game map, so bring them back to the lobby.
        boolean stranded = main.getArenaManager().consumeStranded(player.getUniqueId());

        // Arena mode ("one server = one game" network): the player is thrown
        // straight into the game, unless they have the bypass permission.
        if (main.isArenaModeEnabled() && !player.hasPermission(ARENA_MODE_BYPASS)) {
            autoJoinArena(player);
            return;
        }
        if (stranded) {
            Compat.resetPlayer(player, GameMode.ADVENTURE);
            main.sendToLocalLobby(player);
            return;
        }

        if (!main.getConfig().getBoolean("lobby-on-join", true)) {
            return;
        }
        Location lobby = main.getServerLobby();
        if (lobby != null && lobby.getWorld() != null) {
            Compat.resetPlayer(player, GameMode.ADVENTURE);
            player.teleport(lobby);
            com.thetowers.lobby.MainLobbyItems.give(main, player);
        }
    }

    /**
     * Puts the player back into their game one tick after the join (so the
     * join can finish). If the game ended in the meantime, they are treated
     * as a normal join.
     */
    private void scheduleRejoin(final Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    return;
                }
                Arena arena = main.getArenaManager().getRejoinableArena(player.getUniqueId());
                if (arena != null) {
                    arena.rejoin(player);
                    return;
                }
                main.getArenaManager().consumeStranded(player.getUniqueId());
                if (main.isArenaModeEnabled() && !player.hasPermission(ARENA_MODE_BYPASS)) {
                    joinArenaMode(player);
                } else {
                    Compat.resetPlayer(player, GameMode.ADVENTURE);
                    main.sendToLocalLobby(player);
                }
            }
        }.runTaskLater(main, 1L);
    }

    /** Joins the arena-mode arena one tick after the connection. */
    private void autoJoinArena(final Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    joinArenaMode(player);
                }
            }
        }.runTaskLater(main, 1L);
    }

    /**
     * Joins the arena configured for arena mode. If it cannot be found, is
     * full or already running, the player is sent back to the hub.
     */
    private void joinArenaMode(Player player) {
        Arena arena = main.getArenaManager().getArena(main.getArenaModeArena());
        String problem = arena == null ? "no arena" : arena.join(player);
        if (problem != null) {
            player.sendMessage(Main.PREFIX + Messages.tr("player.arena_mode_no_game"));
            Compat.resetPlayer(player, GameMode.ADVENTURE);
            main.returnToLobby(player);
        }
    }

    /**
     * Quit: a participant in a running game keeps their spot to come back
     * (see {@link Arena#disconnect}); otherwise they simply leave the arena.
     * The arena runs BEFORE the stats are unloaded: a quit while in combat
     * counts a death, which must be recorded.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Arena arena = main.getArenaManager().getArenaOf(player);
        if (arena != null) {
            if (main.isReconnectionEnabled() && arena.isParticipant(player)) {
                arena.disconnect(player);
            } else {
                arena.leave(player, false);
            }
        }
        main.getStatsManager().unload(player);
        main.getKitManager().unloadSelection(player);
        // Coin balance and kit purchases are saved with the stats above.
    }

    /**
     * Death of a participant: counters and message to the arena only (otherwise
     * every death of every arena would show to the whole server), then
     * immediate respawn on the next tick.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        final Player player = event.getEntity();
        Arena arena = main.getArenaManager().getArenaOf(player);
        if (arena == null || !arena.isParticipant(player)) {
            return;
        }

        event.setDeathMessage(null);
        // The kit's leather armor does not drop: it is worthless and, dyed in
        // the team colors, would be confusing on an enemy's back.
        Iterator<ItemStack> drops = event.getDrops().iterator();
        while (drops.hasNext()) {
            ItemStack item = drops.next();
            if (item != null && TTMaterial.isLeatherArmor(item.getType())) {
                drops.remove();
            }
        }

        Player killer = player.getKiller();
        arena.handleDeath(player, killer);

        // One tick of waiting: forcing the respawn in the middle of the death
        // event leaves the player in an inconsistent state.
        new BukkitRunnable() {
            @Override
            public void run() {
                Compat.forceRespawn(player);
            }
        }.runTaskLater(main, 1L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        final Arena arena = main.getArenaManager().getArenaOf(player);
        if (arena == null) {
            return;
        }
        Location target = arena.respawnLocation(player);
        if (target != null && target.getWorld() != null) {
            event.setRespawnLocation(target);
        }
        if (!arena.isParticipant(player)) {
            return;
        }
        // The kit is given one tick later: during the event, the player's
        // inventory is not yet in its final state on 1.12.2.
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && arena.isParticipant(player)) {
                    arena.spawnPlayer(player);
                }
            }
        }.runTaskLater(main, 1L);
    }
}
