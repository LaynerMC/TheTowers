package com.thetowers.listeners;

import java.util.Iterator;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.compat.TTMaterial;
import com.thetowers.game.GameStats;
import com.thetowers.lang.Messages;

/**
 * Building rules during a game: the map can be broken and built on freely
 * (it is regenerated at the end of the game), EXCEPT
 * <ul>
 *   <li>in spawn areas and pools (otherwise players would block their own
 *       pool, or fill the opposite one with lava);</li>
 *   <li>chests, which can be neither broken nor placed;</li>
 *   <li>pistons, forbidden (they would move protected blocks).</li>
 * </ul>
 *
 * <p>If the player takes part in no game, the plugin does not interfere:
 * builders and administrators remain free on the rest of the server.
 */
public class BlocksListeners implements Listener {

    private final Main main;

    public BlocksListeners(Main main) {
        this.main = main;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Arena arena = main.getArenaManager().getArenaOf(player);
        if (arena == null) {
            return;
        }
        if (!arena.isParticipant(player)) {
            event.setCancelled(true);
            return;
        }
        Block block = event.getBlock();
        if (TTMaterial.isChest(block.getType())) {
            deny(event, player, "game.chest_place_denied");
        } else if (TTMaterial.isPiston(block.getType())) {
            deny(event, player, "game.piston_denied");
        } else if (arena.isProtected(block)) {
            deny(event, player, "game.protected_zone");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Arena arena = main.getArenaManager().getArenaOf(player);
        if (arena == null) {
            return;
        }
        if (!arena.isParticipant(player)) {
            event.setCancelled(true);
            return;
        }
        Block block = event.getBlock();
        if (TTMaterial.isChest(block.getType())) {
            deny(event, player, "game.chest_break_denied");
        } else if (arena.isProtected(block)) {
            deny(event, player, "game.protected_zone");
        }
    }

    /** No lava or water poured into a protected zone or a pool. */
    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        Arena arena = main.getArenaManager().getArenaOf(player);
        if (arena == null) {
            return;
        }
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        if (!arena.isParticipant(player)) {
            event.setCancelled(true);
        } else if (arena.isProtected(target)) {
            deny(event, player, "game.protected_zone");
        }
    }

    /** No draining of a team's pool with a bucket. */
    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Player player = event.getPlayer();
        Arena arena = main.getArenaManager().getArenaOf(player);
        if (arena == null) {
            return;
        }
        if (!arena.isParticipant(player)) {
            event.setCancelled(true);
        } else if (arena.isProtected(event.getBlockClicked())) {
            deny(event, player, "game.protected_zone");
        }
    }

    /**
     * An explosion (player-crafted TNT...) spares protected zones and
     * chests. High priority: these blocks are removed from the list BEFORE
     * {@link MapListeners} records the rest at MONITOR.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        Arena arena = main.getArenaManager().getRunningArenaAt(event.getLocation());
        if (arena == null) {
            return;
        }
        Iterator<Block> it = event.blockList().iterator();
        while (it.hasNext()) {
            Block block = it.next();
            if (TTMaterial.isChest(block.getType()) || arena.isProtected(block)) {
                it.remove();
            }
        }
    }

    private void deny(Cancellable event, Player player, String messageKey) {
        event.setCancelled(true);
        player.sendMessage(Main.PREFIX + Messages.tr(messageKey));
    }

    // ================= Block placement / mining stats =================
    // MONITOR priority + ignoreCancelled: only counts blocks that were
    // actually placed/broken (not denied by the rules above or by another
    // plugin). The stat goes to the career counters AND the per-game ones.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceStat(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Arena arena = main.getArenaManager().getArenaOf(player);
        if (arena == null || !arena.isParticipant(player)) {
            return;
        }
        main.getStatsManager().addBlocksPlaced(player, 1);
        GameStats game = arena.getGameStats(player);
        if (game != null) {
            game.addBlockPlaced();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakStat(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Arena arena = main.getArenaManager().getArenaOf(player);
        if (arena == null || !arena.isParticipant(player)) {
            return;
        }
        main.getStatsManager().addBlocksBroken(player, 1);
        GameStats game = arena.getGameStats(player);
        if (game != null) {
            game.addBlockBroken();
        }
    }
}
