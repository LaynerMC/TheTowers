package com.thetowers.region;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Block-aligned rectangular zone, defined by two corners: a team's pool, the
 * protected spawn area, a team's chest zone.
 *
 * <p>Only the world NAME is kept, never the {@link World} object: a world can
 * be unloaded then reloaded (the reference would become stale), and the name
 * is enough to compare with a player's position. Bounds are inclusive on both
 * sides: a cuboid defined on a single block contains that block.
 *
 * <p>Immutable class: it can be shared without precaution.
 */
public final class Cuboid {

    private final String world;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public Cuboid(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.world = world;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    /**
     * Cuboid between two positions, or {@code null} if they are not in the
     * same world (which would make no sense for a zone).
     */
    public static Cuboid of(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null
                || !a.getWorld().getName().equals(b.getWorld().getName())) {
            return null;
        }
        return new Cuboid(a.getWorld().getName(),
                a.getBlockX(), a.getBlockY(), a.getBlockZ(),
                b.getBlockX(), b.getBlockY(), b.getBlockZ());
    }

    public String getWorldName() {
        return world;
    }

    /** World of the zone if it is loaded, otherwise {@code null}. */
    public World getWorld() {
        return Bukkit.getWorld(world);
    }

    public int getMinX() {
        return minX;
    }

    public int getMinY() {
        return minY;
    }

    public int getMinZ() {
        return minZ;
    }

    public int getMaxX() {
        return maxX;
    }

    public int getMaxY() {
        return maxY;
    }

    public int getMaxZ() {
        return maxZ;
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean contains(Location location) {
        return location != null && location.getWorld() != null
                && contains(location.getWorld().getName(),
                        location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public boolean contains(Block block) {
        return block != null
                && contains(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /** Enlarged copy by {@code horizontal} blocks in X/Z and {@code vertical} in Y. */
    public Cuboid expand(int horizontal, int vertical) {
        return new Cuboid(world, minX - horizontal, minY - vertical, minZ - horizontal,
                maxX + horizontal, maxY + vertical, maxZ + horizontal);
    }

    /**
     * Smallest zone containing both, or {@code this} if the other is in
     * another world (two worlds are never mixed).
     */
    public Cuboid union(Cuboid other) {
        if (other == null || !world.equals(other.world)) {
            return this;
        }
        return new Cuboid(world,
                Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }

    /** Number of blocks in the zone (long: a badly defined zone can be huge). */
    public long volume() {
        return (long) (maxX - minX + 1) * (long) (maxY - minY + 1) * (long) (maxZ - minZ + 1);
    }

    /** Readable summary for messages: "x,y,z -> x,y,z". */
    public String describe() {
        return minX + "," + minY + "," + minZ + " -> " + maxX + "," + maxY + "," + maxZ;
    }
}
