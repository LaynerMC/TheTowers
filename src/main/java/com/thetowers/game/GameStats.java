package com.thetowers.game;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.UUID;

/**
 * A player's counters for THE current game (not their career stats, which live
 * in {@code stats/}). Used for the end-of-game summary (top scorer, most
 * kills, most damage) and for kill streaks.
 *
 * <p>The name is kept here: the summary must be able to mention a player who
 * disconnected in the meantime.
 */
public final class GameStats {

    private final String name;
    private int kills;
    private int deaths;
    private int points;
    private double damage;
    /** Blocks placed during this game. */
    private int blocksPlaced;
    /** Blocks mined/broken during this game. */
    private int blocksBroken;
    /** Damage taken from enemies during this game. */
    private double damageTaken;
    /** Highest kill streak reached during this game. */
    private int maxKillStreak;
    /** Timestamps (ms) of the last kills, used to detect streaks. */
    private final LinkedList<Long> recentKills = new LinkedList<Long>();
    /** Last opponent to hit this player, and when (combat-log protection). */
    private UUID lastAttacker;
    private long lastAttackTime;
    /** Kits already unlocked at game start, to announce new ones at the end. */
    private Set<String> kitsAtStart = Collections.emptySet();

    public GameStats(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
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

    public double getDamage() {
        return damage;
    }

    public int getBlocksPlaced() {
        return blocksPlaced;
    }

    public int getBlocksBroken() {
        return blocksBroken;
    }

    public double getDamageTaken() {
        return damageTaken;
    }

    public int getMaxKillStreak() {
        return maxKillStreak;
    }

    public void addPoint() {
        points++;
    }

    public void addDeath() {
        deaths++;
    }

    public void addDamage(double amount) {
        if (amount > 0) {
            damage += amount;
        }
    }

    public void addDamageTaken(double amount) {
        if (amount > 0) {
            damageTaken += amount;
        }
    }

    public void addBlockPlaced() {
        blocksPlaced++;
    }

    public void addBlockBroken() {
        blocksBroken++;
    }

    /**
     * Records a kill and returns the length of the current streak: the number
     * of kills made within the last {@code windowMs} milliseconds (2 = double
     * kill, 3 = triple...).
     */
    public int registerKill(long now, long windowMs) {
        kills++;
        recentKills.add(Long.valueOf(now));
        int current = streak(now, windowMs);
        if (current > maxKillStreak) {
            maxKillStreak = current;
        }
        return current;
    }

    /** Length of the current streak without recording anything. */
    public int streak(long now, long windowMs) {
        Iterator<Long> it = recentKills.iterator();
        while (it.hasNext()) {
            if (now - it.next().longValue() > windowMs) {
                it.remove();
            }
        }
        return recentKills.size();
    }

    /** Death interrupts the streak. */
    public void resetStreak() {
        recentKills.clear();
    }

    /** This player was just hit by an opponent. */
    public void tagAttacker(UUID attacker, long now) {
        lastAttacker = attacker;
        lastAttackTime = now;
    }

    /**
     * Opponent who hit this player within the last {@code windowMs}
     * milliseconds, or {@code null}: is the player "in combat"?
     */
    public UUID recentAttacker(long now, long windowMs) {
        return lastAttacker != null && now - lastAttackTime <= windowMs ? lastAttacker : null;
    }

    /** Forgets the last attacker (on death: the fight is over). */
    public void clearAttacker() {
        lastAttacker = null;
    }

    public Set<String> getKitsAtStart() {
        return kitsAtStart;
    }

    public void setKitsAtStart(Set<String> kitsAtStart) {
        this.kitsAtStart = kitsAtStart == null ? Collections.<String>emptySet() : kitsAtStart;
    }
}
