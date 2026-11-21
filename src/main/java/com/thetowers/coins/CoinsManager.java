package com.thetowers.coins;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicesManager;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

import com.thetowers.Main;
import com.thetowers.stats.PlayerStats;
import com.thetowers.stats.StatsManager;

/**
 * TheTowers' own coin economy, registered with Vault as an
 * {@link Economy} provider so any plugin that uses Vault
 * ({@code %vault_eco_balance%}, shop plugins, ...) reads and writes these
 * balances.
 *
 * <p>Coins are INTEGERS (never fractional) and are stored with the rest of a
 * player's data in {@code stats.yml} / the stats table (no separate
 * coins.yml): the balance is a field of {@link PlayerStats}, persisted by
 * {@link StatsManager}. This manager is only the Vault-facing adapter on top
 * of that cache.
 *
 * <p>Formatting: plain integer with a thousands separator ("1,730"), no
 * currency symbol (the symbol is not used anymore, to avoid doubling it with
 * the one some messages already include). The balance is clamped to
 * {@code coins.max-balance} (1,000,000 by default) on every deposit.
 *
 * <p>If Vault is NOT installed, the manager still works as an internal
 * economy (kit purchases, /coins, placeholders) but is not registered as a
 * service.
 */
public class CoinsManager implements Economy {

    private final Main main;
    /** "1,730" grouping, no decimal, US grouping separator (comma). */
    private final DecimalFormat grouped = new DecimalFormat("#,##0",
            DecimalFormatSymbols.getInstance(Locale.US));

    public CoinsManager(Main main) {
        this.main = main;
        registerWithVault();
    }

    /** Registers this economy as a Vault service, if Vault is present. */
    private void registerWithVault() {
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            main.getLogger().info("Vault not present: coins economy stays internal (no Vault hook).");
            return;
        }
        ServicesManager services = Bukkit.getServicesManager();
        services.register(Economy.class, this, main, org.bukkit.plugin.ServicePriority.Normal);
        main.getLogger().info("Coins economy registered with Vault as: " + getName() + ".");
    }

    private StatsManager stats() {
        return main.getStatsManager();
    }

    /** Max coin balance a player can hold (config.yml, coins.max-balance). */
    public long maxBalance() {
        return main.getConfig().getLong("coins.max-balance", 1000000L);
    }

    // ================= Balance access =================

    /** Balance of an online player (cached), or a sync load for offline. */
    public long getCoins(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            PlayerStats cached = stats().getCached(uuid);
            if (cached != null) {
                return cached.getCoins();
            }
        }
        // Offline or not yet loaded: sync load (rare).
        PlayerStats offline = stats().loadOffline(uuid, "?");
        return offline.getCoins();
    }

    /** Adds coins (clamped to max). Returns the new balance. */
    public long add(OfflinePlayer player, long amount) {
        Player online = player.getPlayer();
        if (online != null) {
            return stats().addCoins(online, amount);
        }
        return stats().setCoinsOffline(player.getUniqueId(), player.getName(),
                getCoins(player.getUniqueId()) + amount);
    }

    /** Removes coins (clamped at 0). Returns the new balance. */
    public long remove(OfflinePlayer player, long amount) {
        Player online = player.getPlayer();
        if (online != null) {
            return stats().removeCoins(online, amount);
        }
        long current = getCoins(player.getUniqueId());
        return stats().setCoinsOffline(player.getUniqueId(), player.getName(),
                Math.max(0L, current - amount));
    }

    /** Sets the balance directly (clamped 0..max). */
    public void setBalance(OfflinePlayer player, long amount) {
        Player online = player.getPlayer();
        if (online != null) {
            stats().setCoins(online, amount);
        } else {
            stats().setCoinsOffline(player.getUniqueId(), player.getName(), amount);
        }
    }

    /** Resets the balance to the configured starting balance. */
    public long reset(OfflinePlayer player) {
        long starting = main.getConfig().getLong("coins.starting-balance", 0L);
        setBalance(player, starting);
        return starting;
    }

    // ================= Formatting =================

    /** Formats a coin amount as an integer with a thousands separator: "1,730". */
    public String format(long amount) {
        return grouped.format(amount);
    }

    /**
     * Abbreviates a coin amount for compact display: "1.5K", "1M", "2.3B".
     * Used for the max-balance hint and large balances.
     */
    public String abbreviate(long amount) {
        if (amount >= 1000000000L) {
            return trim(amount / 1000000000.0) + "B";
        }
        if (amount >= 1000000L) {
            return trim(amount / 1000000.0) + "M";
        }
        if (amount >= 1000L) {
            return trim(amount / 1000.0) + "K";
        }
        return String.valueOf(amount);
    }

    private static String trim(double value) {
        if (value >= 100) {
            return String.valueOf(Math.round(value));
        }
        if (value >= 10) {
            return String.format(Locale.US, "%.1f", value);
        }
        return String.format(Locale.US, "%.2f", value);
    }

    // ================= Vault Economy implementation =================

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "TheTowers";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 0; // coins are integers
    }

    @Override
    public String format(double amount) {
        return format(Math.round(amount));
    }

    @Override
    public String currencyNamePlural() {
        return main.getConfig().getString("coins.currency-plural", "Coins");
    }

    @Override
    public String currencyNameSingular() {
        return main.getConfig().getString("coins.currency-singular", "Coin");
    }

    @Override
    public boolean hasAccount(String playerName) {
        return true;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public double getBalance(String playerName) {
        Player player = Bukkit.getPlayerExact(playerName);
        return player == null ? 0d : (double) getCoins(player.getUniqueId());
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return (double) getCoins(player.getUniqueId());
    }

    @Override
    public double getBalance(String playerName, String worldName) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String worldName) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null) {
            return failure("Player not online.");
        }
        return withdraw(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return withdraw(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null) {
            return failure("Player not online.");
        }
        return deposit(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return deposit(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return true;
    }

    // ================= Banks (not supported) =================

    @Override
    public EconomyResponse createBank(String name, String player) {
        return failure("TheTowers does not support banks.");
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return failure("TheTowers does not support banks.");
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return failure("TheTowers does not support banks.");
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return failure("TheTowers does not support banks.");
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return failure("TheTowers does not support banks.");
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return failure("TheTowers does not support banks.");
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return failure("TheTowers does not support banks.");
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return failure("TheTowers does not support banks.");
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return failure("TheTowers does not support banks.");
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return failure("TheTowers does not support banks.");
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return failure("TheTowers does not support banks.");
    }

    @Override
    public List<String> getBanks() {
        return new ArrayList<String>();
    }

    // ================= Internal =================

    private EconomyResponse deposit(OfflinePlayer player, double amount) {
        if (amount < 0d) {
            return failure("Cannot deposit a negative amount.");
        }
        long before = getCoins(player.getUniqueId());
        long after = add(player, (long) amount);
        long added = after - before;
        return new EconomyResponse((double) added, (double) after,
                EconomyResponse.ResponseType.SUCCESS, null);
    }

    private EconomyResponse withdraw(OfflinePlayer player, double amount) {
        if (amount < 0d) {
            return failure("Cannot withdraw a negative amount.");
        }
        long before = getCoins(player.getUniqueId());
        if (before < (long) amount) {
            return failure("Insufficient funds.");
        }
        long after = remove(player, (long) amount);
        return new EconomyResponse(amount, (double) after,
                EconomyResponse.ResponseType.SUCCESS, null);
    }

    private static EconomyResponse failure(String message) {
        return new EconomyResponse(0d, 0d, EconomyResponse.ResponseType.FAILURE, message);
    }

    // ================= Shutdown =================

    public void shutdown() {
        // Nothing to close: the stats storage is owned by StatsManager.
    }
}
