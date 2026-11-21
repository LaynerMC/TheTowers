package com.thetowers.game;

import org.bukkit.Location;

/**
 * Location of a generator in an arena: a type (config.yml key) and a position.
 * This is the persistent part (arenas.yml); the live part, the one that
 * actually spawns the items, is {@link Generator}.
 */
public final class GeneratorSpot {

    private final String type;
    private final Location location;

    public GeneratorSpot(String type, Location location) {
        this.type = type.toLowerCase();
        this.location = location;
    }

    public String getType() {
        return type;
    }

    public Location getLocation() {
        return location.clone();
    }
}
