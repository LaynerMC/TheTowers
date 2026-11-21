package com.thetowers.coins;

/**
 * One entry of the money leaderboard: the player's name, their saved
 * LuckPerms prefix (so the rank color shows even when they are offline)
 * and their coin balance.
 */
public final class CoinEntry {

    private final String name;
    private final String prefix;
    private final double balance;

    public CoinEntry(String name, String prefix, double balance) {
        this.name = name;
        this.prefix = prefix == null ? "" : prefix;
        this.balance = balance;
    }

    public String getName() {
        return name;
    }

    public String getPrefix() {
        return prefix;
    }

    public double getBalance() {
        return balance;
    }
}
