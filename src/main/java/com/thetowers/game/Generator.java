package com.thetowers.game;

import java.util.Iterator;
import java.util.LinkedList;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Generator alive during a game: spawns its item at a fixed location on a
 * regular interval.
 *
 * <p>Items left on the ground do not pile up forever. Beyond
 * {@code max-on-ground}, the oldest ones are removed BEFORE spawning the new
 * one - otherwise the new item would merge with the old one on the next tick
 * (vanilla merging of nearby items) and the stack would grow without limit.
 *
 * <p>Velocity is zeroed on spawn: the item stays exactly where it was meant to
 * appear instead of bouncing off at random.
 */
public class Generator extends BukkitRunnable {

    private final GeneratorType type;
    private final Location location;
    private final LinkedList<Item> items = new LinkedList<Item>();
    private boolean scheduled;

    public Generator(GeneratorType type, Location location) {
        this.type = type;
        this.location = location;
    }

    public void start(Plugin plugin) {
        runTaskTimer(plugin, type.getIntervalTicks(), type.getIntervalTicks());
        scheduled = true;
    }

    @Override
    public void run() {
        World world = location.getWorld();
        if (world == null
                || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return; // nobody nearby: no point loading the chunk.
        }

        prune();
        // Room for the new item: remove the oldest ones first.
        while (!items.isEmpty() && countOnGround() + type.createItem().getAmount() > type.getMaxOnGround()) {
            items.removeFirst().remove();
        }

        Item item = world.dropItem(location, type.createItem());
        item.setVelocity(new Vector(0, 0, 0));
        items.add(item);
    }

    /** Stops the generator and removes everything it produced from the world. */
    public void stop() {
        if (scheduled) {
            try {
                cancel();
            } catch (IllegalStateException ignored) {
                // Already cancelled: nothing to do.
            }
            scheduled = false;
        }
        for (Item item : items) {
            if (item.isValid()) {
                item.remove();
            }
        }
        items.clear();
    }

    /** Forgets items that were picked up, merged or despawned. */
    private void prune() {
        Iterator<Item> it = items.iterator();
        while (it.hasNext()) {
            if (!it.next().isValid()) {
                it.remove();
            }
        }
    }

    /** Number of items (not entities) currently on the ground for this generator. */
    private int countOnGround() {
        int count = 0;
        for (Item item : items) {
            count += item.getItemStack().getAmount();
        }
        return count;
    }
}
