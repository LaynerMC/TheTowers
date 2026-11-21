package com.thetowers.map;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Map regeneration by change tracking: the original state of every block
 * changed during the game is remembered, then replayed at the end of the
 * round to put the terrain back to new.
 *
 * <p>One {@link BlockState} snapshot per block, restored through
 * {@link BlockState#update(boolean, boolean)}. Zero dependencies, only plain
 * Bukkit 1.12.2 API.
 *
 * <p><b>Container contents.</b> Specific to The Towers, whose bases have
 * chests: a block snapshot does NOT restore what is inside (the content lives
 * in the block entity, not in its state). The original content of every
 * container opened during the game is therefore remembered too, restored
 * after the blocks.
 *
 * <p><b>First write wins</b>: if a block changes several times, only its very
 * first state counts - that is the true original state.
 */
public class MapTracker {

    private final Map<String, BlockState> originals = new LinkedHashMap<String, BlockState>();
    private final Map<String, ItemStack[]> contents = new LinkedHashMap<String, ItemStack[]>();
    private final Map<String, Location> contentLocations = new LinkedHashMap<String, Location>();

    /** Remembers a block's current state (call it BEFORE it is modified). */
    public void record(Block block) {
        if (block != null) {
            record(block.getState());
        }
    }

    /**
     * Remembers an already captured block state, for example
     * {@code BlockPlaceEvent.getBlockReplacedState()} (the state from before
     * the placement).
     */
    public void record(BlockState state) {
        if (state == null || state.getWorld() == null) {
            return;
        }
        String key = key(state.getWorld().getName(), state.getX(), state.getY(), state.getZ());
        if (!originals.containsKey(key)) {
            originals.put(key, state);
        }
    }

    /**
     * Remembers the original content of a container (chest, furnace,
     * hopper...) at its first opening of the game.
     *
     * <p>For a chest, {@link Chest#getBlockInventory()} is read (the half
     * specific to this block, 27 slots), available throughout 1.12.2: a double
     * chest is thus handled as two independent chests, which removes any
     * ambiguity about "which half owns the inventory".
     */
    public void recordContents(BlockState state) {
        if (state == null || state.getWorld() == null) {
            return;
        }
        Inventory inventory = inventoryOf(state);
        if (inventory == null) {
            return;
        }
        String key = key(state.getWorld().getName(), state.getX(), state.getY(), state.getZ());
        if (contents.containsKey(key)) {
            return;
        }
        contents.put(key, copy(inventory.getContents()));
        contentLocations.put(key, state.getLocation());
    }

    /** True if this block is already tracked (chaining of liquid flow). */
    public boolean contains(Block block) {
        return block != null && originals.containsKey(
                key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
    }

    public int size() {
        return originals.size();
    }

    public void clear() {
        originals.clear();
        contents.clear();
        contentLocations.clear();
    }

    /**
     * Puts every tracked block back to its original state, then the container
     * contents.
     *
     * <p>Physics disabled ({@code update(true, false)}): no cascading effects
     * during the restore. Synchronous restore: the arena is empty at that
     * moment, a brief spike is acceptable.
     */
    public void restore() {
        for (BlockState state : originals.values()) {
            if (state != null && state.getWorld() != null) {
                state.update(true, false);
            }
        }
        for (Map.Entry<String, ItemStack[]> entry : contents.entrySet()) {
            Location location = contentLocations.get(entry.getKey());
            if (location == null || location.getWorld() == null) {
                continue;
            }
            Inventory inventory = inventoryOf(location.getBlock().getState());
            ItemStack[] saved = entry.getValue();
            // Different size = no longer the same container (block changed by
            // hand in the meantime): nothing is forced.
            if (inventory != null && inventory.getSize() == saved.length) {
                inventory.setContents(copy(saved));
            }
        }
        clear();
    }

    private static Inventory inventoryOf(BlockState state) {
        if (state instanceof Chest) {
            return ((Chest) state).getBlockInventory();
        }
        if (state instanceof InventoryHolder) {
            return ((InventoryHolder) state).getInventory();
        }
        return null;
    }

    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] == null ? null : items[i].clone();
        }
        return copy;
    }

    private static String key(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }
}
