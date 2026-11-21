package com.thetowers.stats;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Statistics storage, abstract so it stays interchangeable between the local
 * file (YAML) and a MySQL database. All these methods are called exclusively
 * on the I/O thread of {@link StatsManager}: one implementation, one thread,
 * hence no synchronization to plan here.
 *
 * <p>Coins and kit purchases are part of {@link PlayerStats} and are saved
 * with the rest: there is no separate coins or kit-purchases storage.
 */
public interface StatsStorage {

    /** The tracked int columns that are also tracked per-period, stable order. */
    String[] COLUMNS = { "games", "wins", "losses", "kills", "deaths", "points" };

    /** Career-only int columns (no per-period tracking): the in-game stats. */
    String[] EXTENDED_INT_COLUMNS = { "blocks_placed", "blocks_broken", "max_kill_streak" };

    /** Career-only double columns (no per-period tracking). */
    String[] EXTENDED_DOUBLE_COLUMNS = { "damage_dealt", "damage_taken" };

    /** True if {@code column} is a known int stat column (anti-injection). */
    static boolean isColumn(String column) {
        for (String c : COLUMNS) {
            if (c.equals(column)) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code column} is any int stat column (basic or extended). */
    static boolean isAnyIntColumn(String column) {
        if (isColumn(column)) {
            return true;
        }
        for (String c : EXTENDED_INT_COLUMNS) {
            if (c.equals(column)) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code column} is a double stat column. */
    static boolean isDoubleColumn(String column) {
        for (String c : EXTENDED_DOUBLE_COLUMNS) {
            if (c.equals(column)) {
                return true;
            }
        }
        return false;
    }

    /** Prepares the storage (file or table). May throw on a problem. */
    void init() throws Exception;

    /** A player's stats, or zeroed stats if the player is unknown. */
    PlayerStats load(UUID uuid, String name);

    /** A player's stats found by name, or {@code null} if unknown. */
    PlayerStats loadByName(String name);

    /** Writes (inserts or updates) a player's stats (including coins + kits). */
    void save(PlayerStats stats);

    /**
     * Best players for a given column, descending order.
     *
     * @param column one of {@link #COLUMNS} or {@link #EXTENDED_INT_COLUMNS}
     *               (already validated by the caller)
     */
    List<PlayerStats> top(String column, int limit);

    /**
     * Best players by coin balance, descending order. Each entry carries the
     * saved LuckPerms prefix so the money leaderboard shows the rank color.
     */
    List<PlayerStats> topCoins(int limit);

    /**
     * Adds deltas to a player's counters FOR A PERIOD ("week" or "month"), one
     * entry per (player, type, key). Negative deltas are allowed: they cancel
     * an aborted game (server stop). The luckperms prefix is saved alongside
     * so every period's leaderboard shows it.
     *
     * @param periodType "week" or "month"
     * @param periodKey  key of the period (e.g. "2026-W46", "2026-11")
     * @param prefix     the player's saved LuckPerms prefix (may be empty)
     * @param deltas     columns of {@link #COLUMNS} -> delta (zero excluded)
     */
    void addPeriodic(String periodType, String periodKey, UUID uuid, String name,
            String prefix, Map<String, Integer> deltas);

    /**
     * Best players OF A PERIOD for a given column, descending order; empty
     * list if nobody played during the period. The saved prefix is carried so
     * the weekly/monthly leaderboards show the rank color too.
     */
    List<PlayerStats> topPeriodic(String periodType, String periodKey, String column, int limit);

    /**
     * Updates the saved LuckPerms prefix of a player (called when their prefix
     * is fetched on join or refreshed). The prefix is used by the top
     * placeholders so offline players still show their rank color.
     */
    void updatePrefix(UUID uuid, String name, String prefix);

    /** Closes the resources (connection, file). */
    void close();
}
