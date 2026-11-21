package com.thetowers.economy;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import net.milkbowl.vault.economy.Economy;

import com.thetowers.Main;

/**
 * Access to the Vault economy (Essentials Economy, CMI, ...) for kit
 * purchases ({@code price} in kits.yml).
 *
 * <p><b>Isolation</b>, same rule as {@code api.PapiBridge}: this class is the
 * only one referencing Vault classes, and it is only INSTANTIATED if the Vault
 * plugin is present ({@code Main}). A server without Vault therefore never
 * loads this class, and the jar does not bundle Vault (provided scope).
 *
 * <p>The economy provider (EssentialsX, ...) may register AFTER TheTowers: the
 * lookup is therefore retried on every call until it succeeds, instead of
 * being frozen at startup.
 */
public final class EconomyHook {

    private final Main main;
    private Economy economy;
    private boolean searched;

    public EconomyHook(Main main) {
        this.main = main;
    }

    /** True if Vault AND an economy provider are available. */
    public boolean available() {
        return economy() != null;
    }

    /** Player's balance (0 if the economy is absent). */
    public double balance(Player player) {
        Economy eco = economy();
        return eco == null ? 0d : eco.getBalance(player);
    }

    /**
     * Withdraws {@code amount} from the player.
     *
     * @return {@code true} if the payment succeeded
     */
    public boolean withdraw(Player player, double amount) {
        Economy eco = economy();
        if (eco == null || amount <= 0d) {
            return false;
        }
        return eco.withdrawPlayer(player, amount).transactionSuccess();
    }

    /**
     * Deposits {@code amount} into the player's account (e.g. a refund when a
     * kit selection could not be saved after the coin cost was taken).
     *
     * @return {@code true} if the deposit succeeded
     */
    public boolean deposit(Player player, double amount) {
        Economy eco = economy();
        if (eco == null || amount <= 0d) {
            return false;
        }
        return eco.depositPlayer(player, amount).transactionSuccess();
    }

    /** The formatted price ("500.0" or the provider's format). */
    public String format(double amount) {
        Economy eco = economy();
        if (eco == null) {
            return String.valueOf(amount);
        }
        try {
            return eco.format(amount);
        } catch (Throwable unknownMethod) {
            return String.valueOf(amount);
        }
    }

    /** Looks up (and re-looks-up) the economy provider. Main thread. */
    private Economy economy() {
        if (economy != null) {
            return economy;
        }
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            return null;
        }
        // One real attempt per tick is enough: the lookup is cheap, but we
        // avoid looping it once it has failed during this session.
        if (searched) {
            return null;
        }
        searched = true;
        RegisteredServiceProvider<Economy> provider =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            return null;
        }
        economy = provider.getProvider();
        main.getLogger().info("Vault economy hooked: " + economy.getName() + ".");
        return economy;
    }
}
