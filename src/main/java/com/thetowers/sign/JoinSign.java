package com.thetowers.sign;

import org.bukkit.Location;

/**
 * A join sign: its position, and its target.
 *
 * <p>Target = the name of a specific arena, or empty for a <b>random</b> sign
 * that sends to the fullest joinable arena.
 */
public class JoinSign {

    private final Location location;
    private final String target;

    public JoinSign(Location location, String target) {
        this.location = location;
        this.target = target == null ? "" : target;
    }

    public Location getLocation() {
        return location;
    }

    /** Name of the targeted arena, or an empty string if the sign is random. */
    public String getTarget() {
        return target;
    }

    public boolean isRandom() {
        return target.isEmpty();
    }
}
