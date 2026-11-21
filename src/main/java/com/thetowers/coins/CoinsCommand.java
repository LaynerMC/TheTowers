package com.thetowers.coins;

import java.util.Collections;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import com.thetowers.Main;
import com.thetowers.lang.Messages;

/**
 * The {@code /coins} command: shows the player their own coin balance.
 *
 * <p>Tab-completes nothing (no arguments). The admin operations
 * (add/remove/set/reset) live under {@code /tt coins} in
 * {@link com.thetowers.command.CommandTower}.
 */
public class CoinsCommand implements CommandExecutor, TabCompleter {

    private final Main main;

    public CoinsCommand(Main main) {
        this.main = main;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(Messages.tr("command.ingame_only"));
            return true;
        }
        Player player = (Player) sender;
        if (main.getCoinsManager() == null) {
            player.sendMessage(Main.PREFIX + Messages.tr("coins.no_economy"));
            return true;
        }
        long balance = main.getCoinsManager().getCoins(player.getUniqueId());
        String formatted = formatAmount(main, balance);
        player.sendMessage(Main.PREFIX + Messages.tr("coins.balance_self",
                "amount", formatted, "currency", currencyName(balance)));
        return true;
    }

    /**
     * Formats a coin amount as an integer with a thousands separator
     * ("1,730"), no currency symbol. Shared with the admin command feedback.
     */
    public static String formatAmount(Main main, long amount) {
        if (main.getCoinsManager() != null) {
            return main.getCoinsManager().format(amount);
        }
        java.text.DecimalFormat f = new java.text.DecimalFormat("#,##0",
                java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US));
        return f.format(amount);
    }

    private String currencyName(long amount) {
        boolean plural = amount != 1L;
        return main.getConfig().getString(plural ? "coins.currency-plural" : "coins.currency-singular",
                plural ? "Coins" : "Coin");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
