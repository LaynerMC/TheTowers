package com.thetowers.stats;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A player's career statistics: games, wins, losses, kills, deaths, points
 * scored, the new in-game stats (blocks placed/broken, best kill streak,
 * damage dealt/taken), their coin balance, the kit IDs they have purchased,
 * their saved kit selection and LuckPerms prefix.
 *
 * <p>Everything about a player lives here and is persisted together in
 * stats.yml (local) or the stats table (MySQL): there is no separate
 * coins.yml or kit-purchases.yml anymore.
 *
 * <p>Threading rule (see {@link StatsManager}): this object is only mutated on
 * the main thread (game events). What crosses to the I/O thread is never the
 * live object but a {@link #copy() copy}, which avoids any concurrent access
 * without synchronization.
 */
public class PlayerStats {

    private final UUID uuid;
    private String name;

    private int games;
    private int wins;
    private int losses;
    private int kills;
    private int deaths;
    /** Points scored in an enemy pool. */
    private int points;
    /** Blocks placed during games. */
    private int blocksPlaced;
    /** Blocks mined/broken during games. */
    private int blocksBroken;
    /** Highest kill streak ever reached by this player. */
    private int maxKillStreak;
    /** Total damage dealt to enemies (in half-hearts, Minecraft's damage unit). */
    private double damageDealt;
    /** Total damage taken from enemies. */
    private double damageTaken;
    /** Coin balance (integer: coins are never fractional). */
    private long coins;
    /** IDs of the kits this player has purchased (paid once, kept forever). */
    private Set<Integer> purchasedKits = new LinkedHashSet<Integer>();
    /** Saved kit id (lowercase, key of kits.yml), or {@code null} = default kit. */
    private String kit;
    /** Saved LuckPerms prefix (for the leaderboards), or empty if none. */
    private String luckpermsPrefix;
    /** Leveling system: the player's current level (0 = not yet initialized). */
    private long level;
    /** Leveling system: the player's current EXP toward the next level. */
    private long exp;

    public PlayerStats(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public PlayerStats(UUID uuid, String name, int games, int wins, int losses,
            int kills, int deaths, int points, int blocksPlaced, int blocksBroken,
            int maxKillStreak, double damageDealt, double damageTaken,
            long coins, java.util.Collection<Integer> purchasedKits,
            String kit, String luckpermsPrefix) {
        this.uuid = uuid;
        this.name = name;
        this.games = games;
        this.wins = wins;
        this.losses = losses;
        this.kills = kills;
        this.deaths = deaths;
        this.points = points;
        this.blocksPlaced = blocksPlaced;
        this.blocksBroken = blocksBroken;
        this.maxKillStreak = maxKillStreak;
        this.damageDealt = damageDealt;
        this.damageTaken = damageTaken;
        this.coins = coins;
        if (purchasedKits != null) {
            this.purchasedKits = new LinkedHashSet<Integer>(purchasedKits);
        }
        this.kit = kit;
        this.luckpermsPrefix = luckpermsPrefix == null ? "" : luckpermsPrefix;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getGames() {
        return games;
    }

    public int getWins() {
        return wins;
    }

    public int getLosses() {
        return losses;
    }

    public int getKills() {
        return kills;
    }

    public int getDeaths() {
        return deaths;
    }

    public int getPoints() {
        return points;
    }

    public int getBlocksPlaced() {
        return blocksPlaced;
    }

    public int getBlocksBroken() {
        return blocksBroken;
    }

    public int getMaxKillStreak() {
        return maxKillStreak;
    }

    public double getDamageDealt() {
        return damageDealt;
    }

    public double getDamageTaken() {
        return damageTaken;
    }

    /** Coin balance (integer). */
    public long getCoins() {
        return coins;
    }

    public void setCoins(long coins) {
        this.coins = Math.max(0L, coins);
    }

    /** Adds coins, clamped to the server's max balance. */
    public void addCoins(long amount, long max) {
        if (amount <= 0L) {
            return;
        }
        this.coins = Math.min(max, this.coins + amount);
    }

    /** Removes coins, clamped at 0. Returns the amount actually removed. */
    public long removeCoins(long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        long removed = Math.min(this.coins, amount);
        this.coins -= removed;
        return removed;
    }

    /** IDs of the kits this player has purchased (unmodifiable copy). */
    public Set<Integer> getPurchasedKits() {
        return Collections.unmodifiableSet(purchasedKits);
    }

    /** True if the player already bought the kit with this numeric id. */
    public boolean hasPurchasedKit(int kitId) {
        return purchasedKits.contains(Integer.valueOf(kitId));
    }

    /** Records a kit purchase. */
    public void addPurchasedKit(int kitId) {
        purchasedKits.add(Integer.valueOf(kitId));
    }

    /** Removes a kit purchase (admin revoke), as if it was never bought. */
    public void removePurchasedKit(int kitId) {
        purchasedKits.remove(Integer.valueOf(kitId));
    }

    /** Saved kit id, or {@code null} if the player uses the default kit. */
    public String getKit() {
        return kit;
    }

    public void setKit(String kit) {
        this.kit = kit;
    }

    /** Saved LuckPerms prefix (raw, color codes already translated). */
    public String getLuckpermsPrefix() {
        return luckpermsPrefix == null ? "" : luckpermsPrefix;
    }

    public void setLuckpermsPrefix(String prefix) {
        this.luckpermsPrefix = prefix == null ? "" : prefix;
    }

    /** Leveling system: current level (0 means the player has not been initialized yet). */
    public long getLevel() {
        return level;
    }

    public void setLevel(long level) {
        this.level = Math.max(0L, level);
    }

    /** Leveling system: current EXP toward the next level (never negative). */
    public long getExp() {
        return exp;
    }

    public void setExp(long exp) {
        this.exp = Math.max(0L, exp);
    }

    public void addGame() {
        games++;
    }

    public void addWin() {
        wins++;
    }

    public void addLoss() {
        losses++;
    }

    public void addKill() {
        kills++;
    }

    public void addDeath() {
        deaths++;
    }

    public void addPoint() {
        points++;
    }

    public void addBlocksPlaced(int count) {
        if (count > 0) {
            blocksPlaced += count;
        }
    }

    public void addBlocksBroken(int count) {
        if (count > 0) {
            blocksBroken += count;
        }
    }

    /** Records a kill streak; keeps the highest ever seen. */
    public void recordKillStreak(int streak) {
        if (streak > maxKillStreak) {
            maxKillStreak = streak;
        }
    }

    public void addDamageDealt(double amount) {
        if (amount > 0d) {
            damageDealt += amount;
        }
    }

    public void addDamageTaken(double amount) {
        if (amount > 0d) {
            damageTaken += amount;
        }
    }

    // ================= Rollback (aborted game) =================

    public void subtractGame() {
        games = Math.max(0, games - 1);
    }

    public void subtractKills(int count) {
        kills = Math.max(0, kills - count);
    }

    public void subtractDeaths(int count) {
        deaths = Math.max(0, deaths - count);
    }

    public void subtractPoints(int count) {
        points = Math.max(0, points - count);
    }

    public void subtractBlocksPlaced(int count) {
        blocksPlaced = Math.max(0, blocksPlaced - count);
    }

    public void subtractBlocksBroken(int count) {
        blocksBroken = Math.max(0, blocksBroken - count);
    }

    public void subtractDamageDealt(double amount) {
        damageDealt = Math.max(0d, damageDealt - amount);
    }

    public void subtractDamageTaken(double amount) {
        damageTaken = Math.max(0d, damageTaken - amount);
    }

    /** Kill/death ratio; zero deaths counts as one to avoid division by zero. */
    public double kd() {
        return deaths == 0 ? kills : (double) kills / (double) deaths;
    }

    /**
     * Value of an int {@link StatsStorage#COLUMNS} or
     * {@link StatsStorage#EXTENDED_INT_COLUMNS} column (for leaderboards and
     * the /tt stats menu). Returns {@code 0} for unknown columns.
     */
    public int value(String column) {
        if ("wins".equals(column)) {
            return wins;
        }
        if ("losses".equals(column)) {
            return losses;
        }
        if ("kills".equals(column)) {
            return kills;
        }
        if ("deaths".equals(column)) {
            return deaths;
        }
        if ("points".equals(column)) {
            return points;
        }
        if ("blocks_placed".equals(column)) {
            return blocksPlaced;
        }
        if ("blocks_broken".equals(column)) {
            return blocksBroken;
        }
        if ("max_kill_streak".equals(column)) {
            return maxKillStreak;
        }
        return games;
    }

    /** Value of a double column (damage_dealt, damage_taken), or 0. */
    public double doubleValue(String column) {
        if ("damage_dealt".equals(column)) {
            return damageDealt;
        }
        if ("damage_taken".equals(column)) {
            return damageTaken;
        }
        return 0d;
    }

    /** True if the column is one of the double stat columns. */
    public static boolean isDoubleColumn(String column) {
        return "damage_dealt".equals(column) || "damage_taken".equals(column);
    }

    /** Independent copy, handed to the I/O thread for saving. */
    public PlayerStats copy() {
        PlayerStats copy = new PlayerStats(uuid, name, games, wins, losses, kills, deaths, points,
                blocksPlaced, blocksBroken, maxKillStreak, damageDealt, damageTaken,
                coins, purchasedKits, kit, luckpermsPrefix);
        copy.level = this.level;
        copy.exp = this.exp;
        return copy;
    }
}
