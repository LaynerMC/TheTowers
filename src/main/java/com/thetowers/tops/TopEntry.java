package com.thetowers.tops;

/**
 * One entry of a leaderboard: the player's name, their saved LuckPerms
 * prefix (color codes already translated) and the stat value as a display
 * string. The value is pre-formatted (an integer for kills/wins/points, a
 * 2-decimal string for money) so the placeholder output only has to
 * substitute it.
 */
public final class TopEntry {

    private final String name;
    private final String prefix;
    private final String value;

    public TopEntry(String name, String prefix, String value) {
        this.name = name == null ? "" : name;
        this.prefix = prefix == null ? "" : prefix;
        this.value = value == null ? "0" : value;
    }

    public String getName() {
        return name;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getValue() {
        return value;
    }
}
