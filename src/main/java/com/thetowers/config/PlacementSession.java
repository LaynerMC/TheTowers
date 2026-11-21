package com.thetowers.config;

import org.bukkit.Location;

/**
 * A "placement mode" session: the admin clicked a menu button (or ran a
 * command) that asks for a position in the world. The menu closes and they
 * place it while walking, by right click.
 *
 * <p>Two families:
 * <ul>
 *   <li><b>points</b> (lobby, spawn, generator): a single click, the player's
 *       position is captured;</li>
 *   <li><b>zones</b> (pool, spawn zone, chest zone): two clicks, one per
 *       opposite corner. The first corner is kept here while waiting for the
 *       second one.</li>
 * </ul>
 */
public class PlacementSession {

    /** What the admin is currently placing. */
    public enum Kind {
        LOBBY(false),
        SPAWN(false),
        GENERATOR(false),
        POOL(true),
        SPAWN_ZONE(true),
        CHEST_ZONE(true);

        private final boolean zone;

        Kind(boolean zone) {
            this.zone = zone;
        }

        /** True if this placement needs two corners. */
        public boolean isZone() {
            return zone;
        }
    }

    private final String arena;
    private final Kind kind;
    /** Team concerned (spawn and zones), or {@code null}. */
    private final String team;
    /** Generator type (config.yml key), or {@code null}. */
    private final String generatorType;
    private Location firstCorner;

    public PlacementSession(String arena, Kind kind, String team, String generatorType) {
        this.arena = arena;
        this.kind = kind;
        this.team = team;
        this.generatorType = generatorType;
    }

    public String getArena() {
        return arena;
    }

    public Kind getKind() {
        return kind;
    }

    public String getTeam() {
        return team;
    }

    public String getGeneratorType() {
        return generatorType;
    }

    /** First corner already captured for a zone, or {@code null}. */
    public Location getFirstCorner() {
        return firstCorner;
    }

    public void setFirstCorner(Location firstCorner) {
        this.firstCorner = firstCorner;
    }
}
