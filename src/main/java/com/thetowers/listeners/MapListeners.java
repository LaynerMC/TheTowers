package com.thetowers.listeners;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.InventoryHolder;

import com.thetowers.arena.Arena;
import com.thetowers.Main;

/**
 * Feeds the tracking done by {@link com.thetowers.map.MapTracker}: records the
 * ORIGIN state of every block changed during a game, so everything can be
 * restored at the end of the round.
 *
 * <p>Priority {@code MONITOR} + {@code ignoreCancelled}: only changes that
 * actually happen are observed, and the blocks are still at their original
 * state at this point (they are only modified after the event).
 *
 * <p>Two sources of changes:
 * <ul>
 *   <li>a player (break, place, bucket, chest opening): their arena is the
 *       one they are a participant of;</li>
 *   <li>nothing identifiable (explosion, fire, falling sand, flowing water):
 *       the change is attached to the running arena whose play area contains
 *       the block (see {@code Arena#getArea()}).</li>
 * </ul>
 */
public class MapListeners implements Listener {

    private final Main main;

    public MapListeners(Main main) {
        this.main = main;
    }

    // ============ Changes attributed to a player ============

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Arena arena = trackingArena(event.getPlayer());
        if (arena != null) {
            arena.getMapTracker().record(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Arena arena = trackingArena(event.getPlayer());
        if (arena != null) {
            // The state from before the placement: restoring it removes the placed block.
            arena.getMapTracker().record(event.getBlockReplacedState());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Arena arena = trackingArena(event.getPlayer());
        if (arena != null) {
            arena.getMapTracker().record(event.getBlockClicked().getRelative(event.getBlockFace()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Arena arena = trackingArena(event.getPlayer());
        if (arena != null) {
            arena.getMapTracker().record(event.getBlockClicked());
        }
    }

    /**
     * Container contents: remembered at the first opening of the game,
     * restored at the end of the round. A double chest is recorded half by
     * half (see {@code MapTracker#recordContents}).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        Arena arena = trackingArena((Player) event.getPlayer());
        if (arena == null) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof DoubleChest) {
            DoubleChest chest = (DoubleChest) holder;
            recordHalf(arena, chest.getLeftSide());
            recordHalf(arena, chest.getRightSide());
        } else if (holder instanceof BlockState) {
            arena.getMapTracker().recordContents((BlockState) holder);
        }
    }

    private void recordHalf(Arena arena, InventoryHolder half) {
        if (half instanceof BlockState) {
            arena.getMapTracker().recordContents((BlockState) half);
        }
    }

    // ============ Changes with no identifiable player ============

    /** Blocks carried away by an explosion (already filtered by BlocksListeners). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        Arena arena = arenaAt(event.getLocation().getBlock());
        if (arena == null) {
            return;
        }
        for (Block block : event.blockList()) {
            arena.getMapTracker().record(block);
        }
    }

    /** Falling sand or gravel (departure and arrival), Endermen... */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Arena arena = arenaAt(event.getBlock());
        if (arena != null) {
            arena.getMapTracker().record(event.getBlock());
        }
    }

    /** Fire lit (flint and steel, lava, lightning): the air block receiving the fire. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Arena arena = arenaAt(event.getBlock());
        if (arena != null) {
            arena.getMapTracker().record(event.getBlock());
        }
    }

    /** Block consumed by the fire. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        Arena arena = arenaAt(event.getBlock());
        if (arena != null) {
            arena.getMapTracker().record(event.getBlock());
        }
    }

    /** Fire spreading (and grass, mushrooms... everything that extends). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        Arena arena = arenaAt(event.getBlock());
        if (arena != null) {
            arena.getMapTracker().record(event.getBlock());
        }
    }

    /**
     * Flow of the water or lava poured with a bucket: every block into which
     * an ALREADY tracked liquid flows is added to the tracking (chaining).
     * Since the source is already recorded, the play area is not even needed.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent event) {
        if (!main.isMapRegenerationEnabled()) {
            return;
        }
        Block source = event.getBlock();
        for (Arena arena : main.getArenaManager().getArenas()) {
            if (arena.isRunning() && arena.getMapTracker().contains(source)) {
                arena.getMapTracker().record(event.getToBlock());
                return;
            }
        }
    }

    // ============ Utilities ============

    /** Arena to track for this player, or {@code null} (not in a game / regen off). */
    private Arena trackingArena(Player player) {
        if (!main.isMapRegenerationEnabled()) {
            return null;
        }
        Arena arena = main.getArenaManager().getArenaOf(player);
        return arena != null && arena.isParticipant(player) ? arena : null;
    }

    /** Running arena whose play area contains this block (regen on), or {@code null}. */
    private Arena arenaAt(Block block) {
        if (!main.isMapRegenerationEnabled() || block == null) {
            return null;
        }
        return main.getArenaManager().getRunningArenaAt(block.getLocation());
    }
}
