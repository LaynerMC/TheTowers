package com.thetowers.coins;

import java.util.List;
import java.util.UUID;

/**
 * Coin balance storage, abstract like {@link com.thetowers.stats.StatsStorage}:
 * interchangeable between a local file (coins.yml) and a MySQL database.
 *
 * <p>TheTowers registers itself as a Vault Economy provider, so any plugin
 * using Vault reads/writes these balances. The storage also keeps the saved
 * LuckPerms prefix of each player so the money leaderboard can show it.
 */
public interface CoinsStorage {

    /** Prepares the storage (file or table). May throw on a problem. */
    void init() throws Exception;

    /** A player's balance, or 0 if they have no account yet. */
    double load(UUID uuid, String name);

    /** Creates an account with the starting balance if it does not exist. */
    void createAccount(UUID uuid, String name, double startingBalance);

    /** Writes the balance (inserts or updates). */
    void save(UUID uuid, String name, double balance);

    /** Updates the saved LuckPerms prefix of a player. */
    void updatePrefix(UUID uuid, String name, String prefix);

    /**
     * Best players by balance, descending order. Each entry carries the name,
     * the saved prefix and the balance, for the money leaderboard.
     */
    List<CoinEntry> top(int limit);

    /** Closes the resources (connection, file). */
    void close();
}
