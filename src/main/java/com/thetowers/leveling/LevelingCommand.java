package com.thetowers.leveling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import com.thetowers.Main;
import com.thetowers.stats.PlayerStats;

/**
 * The {@code /leveling} command: the only way the outside world mutates the
 * leveling system. Every operation is a command so the towers game can drive
 * XP gains purely through {@code rewards.yml} ({@code on-kill}/{@code on-death}
 * fire {@code /leveling addexp} as the console), with no internal hook into
 * the game loop.
 *
 * <p>Sub-commands:
 * <ul>
 *   <li>{@code info [player]} - view level progress (everyone: own, admins: any)</li>
 *   <li>{@code addexp|setexp|removeexp <amount> [player]} - EXP operations (admin)</li>
 *   <li>{@code addlevel|setlevel|removelevel <amount> [player]} - level operations (admin)</li>
 *   <li>{@code reload} - reload {@code leveling.yml} (admin)</li>
 *   <li>{@code help} - show the help</li>
 * </ul>
 *
 * <p>Works from the console too: a target player name is then required for
 * the operations that would otherwise default to the sender.
 */
public final class LevelingCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "thetowers.admin";

    private final Main main;
    private final LevelingManager manager;

    public LevelingCommand(Main main, LevelingManager manager) {
        this.main = main;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            for (String line : manager.getConfig().trList("help")) {
                sender.sendMessage(line);
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        if ("reload".equals(sub)) {
            if (!checkAdmin(sender)) {
                return true;
            }
            manager.reload();
            sender.sendMessage(manager.getConfig().tr("reloaded"));
            return true;
        }

        if ("info".equals(sub)) {
            return handleInfo(sender, args);
        }

        // The six mutation sub-commands.
        if (isMutation(sub)) {
            return handleMutation(sender, sub, args);
        }

        sender.sendMessage(manager.getConfig().tr("unknown-command", "command", args[0]));
        for (String line : manager.getConfig().trList("help")) {
            sender.sendMessage(line);
        }
        return true;
    }

    // ================= info =================

    private boolean handleInfo(CommandSender sender, String[] args) {
        LevelingConfig config = manager.getConfig();
        if (!config.isEnabled()) {
            sender.sendMessage(config.tr("disabled"));
            return true;
        }
        String targetName;
        UUID targetUuid;
        if (args.length >= 2) {
            if (!checkAdmin(sender)) {
                return true;
            }
            OfflinePlayer offline = resolveByName(args[1]);
            if (offline == null) {
                sender.sendMessage(config.tr("player-not-found", "player", args[1]));
                return true;
            }
            targetName = offline.getName() == null ? args[1] : offline.getName();
            targetUuid = offline.getUniqueId();
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(config.tr("only-players"));
                return true;
            }
            Player player = (Player) sender;
            targetName = player.getName();
            targetUuid = player.getUniqueId();
        }

        long level;
        long exp;
        long required;
        PlayerStats cached = main.getStatsManager().getCached(targetUuid);
        if (cached != null) {
            level = manager.getLevel(targetUuid);
            exp = manager.getExp(targetUuid);
        } else {
            // Offline (or not yet loaded): read straight from storage.
            PlayerStats loaded = main.getStatsManager().loadOffline(targetUuid, targetName);
            level = loaded.getLevel();
            if (level < config.startLevel()) {
                level = config.startLevel();
                exp = config.startExp();
            } else {
                exp = loaded.getExp();
            }
        }
        required = config.requiredExp(level);

        for (String line : config.trList("info",
                "player", targetName,
                "level", Long.toString(level),
                "max", Long.toString(config.maxLevel()),
                "exp", Long.toString(exp),
                "exp_formatted", config.formatter().grouped(exp),
                "exp_required", Long.toString(required),
                "exp_required_formatted", config.formatter().grouped(required),
                "percent", config.percent(exp, required),
                "progress_bar", config.progressBar(exp, required))) {
            sender.sendMessage(line);
        }
        return true;
    }

    // ================= mutations =================

    private boolean handleMutation(CommandSender sender, String sub, String[] args) {
        LevelingConfig config = manager.getConfig();
        if (!checkAdmin(sender)) {
            return true;
        }
        if (!config.isEnabled()) {
            sender.sendMessage(config.tr("disabled"));
            return true;
        }
        if (args.length < 2) {
            for (String line : config.trList("help")) {
                sender.sendMessage(line);
            }
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(config.tr("not-a-number", "input", args[1]));
            return true;
        }

        // Resolve the target: explicit arg, else the sender (must be a player).
        Player onlineTarget = null;
        UUID uuid;
        String name;
        if (args.length >= 3) {
            Player match = Bukkit.getPlayerExact(args[2]);
            if (match != null) {
                onlineTarget = match;
                uuid = match.getUniqueId();
                name = match.getName();
            } else {
                OfflinePlayer offline = resolveByName(args[2]);
                if (offline == null) {
                    sender.sendMessage(config.tr("player-not-found", "player", args[2]));
                    return true;
                }
                uuid = offline.getUniqueId();
                name = offline.getName() == null ? args[2] : offline.getName();
            }
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(config.tr("only-players"));
                return true;
            }
            onlineTarget = (Player) sender;
            uuid = onlineTarget.getUniqueId();
            name = onlineTarget.getName();
        }

        long newLevel;
        boolean negativeAllowed = sub.startsWith("remove");
        if (negativeAllowed && amount < 0) {
            amount = -amount;
        }
        if (!negativeAllowed && amount < 0) {
            amount = 0;
        }

        if (onlineTarget != null) {
            newLevel = mutateOnline(sub, onlineTarget, amount);
        } else {
            newLevel = mutateOffline(sub, uuid, name, amount);
        }
        if (newLevel < 0) {
            sender.sendMessage(config.tr("player-not-found", "player", name));
            return true;
        }

        long exp = manager.getExp(uuid);
        long required = config.requiredExp(newLevel);
        String key = messageKey(sub);
        sender.sendMessage(config.tr(key,
                "player", name,
                "amount", Long.toString(amount),
                "level", Long.toString(newLevel),
                "exp", Long.toString(exp),
                "exp_formatted", config.formatter().grouped(exp),
                "required", Long.toString(required),
                "max", Long.toString(config.maxLevel())));
        return true;
    }

    private long mutateOnline(String sub, Player target, long amount) {
        switch (sub) {
            case "addexp": return manager.addExp(target, amount);
            case "setexp": return manager.setExp(target, amount);
            case "removeexp": return manager.removeExp(target, amount);
            case "addlevel": return manager.addLevel(target, amount);
            case "setlevel": return manager.setLevel(target, amount);
            case "removelevel": return manager.removeLevel(target, amount);
            default: return -1L;
        }
    }

    private long mutateOffline(String sub, UUID uuid, String name, long amount) {
        switch (sub) {
            case "addexp": return manager.addExpOffline(uuid, name, amount);
            case "setexp": return manager.setExpOffline(uuid, name, amount);
            case "removeexp": return manager.removeExpOffline(uuid, name, amount);
            case "addlevel": return manager.addLevelOffline(uuid, name, amount);
            case "setlevel": return manager.setLevelOffline(uuid, name, amount);
            case "removelevel": return manager.removeLevelOffline(uuid, name, amount);
            default: return -1L;
        }
    }

    // ================= tab complete =================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : SUBS) {
                if (sub.startsWith(prefix)) {
                    out.add(sub);
                }
            }
            return out;
        }
        if (args.length == 2 && isMutation(args[0])) {
            // The amount: no useful completion.
            return out;
        }
        if (args.length == 3 && (isMutation(args[0]) || "info".equalsIgnoreCase(args[0]))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(player.getName());
                }
            }
            return out;
        }
        return out;
    }

    // ================= helpers =================

    private boolean checkAdmin(CommandSender sender) {
        if (sender.hasPermission(ADMIN)) {
            return true;
        }
        sender.sendMessage(manager.getConfig().tr("no-permission"));
        return false;
    }

    private static boolean isMutation(String sub) {
        return "addexp".equals(sub) || "setexp".equals(sub) || "removeexp".equals(sub)
                || "addlevel".equals(sub) || "setlevel".equals(sub) || "removelevel".equals(sub);
    }

    private static String messageKey(String sub) {
        switch (sub) {
            case "addexp": return "added-exp";
            case "setexp": return "set-exp";
            case "removeexp": return "removed-exp";
            case "addlevel": return "added-levels";
            case "setlevel": return "set-level";
            case "removelevel": return "removed-levels";
            default: return "added-exp";
        }
    }

    /** Resolves a name to an offline player (covers online players too). */
    private static OfflinePlayer resolveByName(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        try {
            return Bukkit.getOfflinePlayer(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final List<String> SUBS = Arrays.asList(
            "info", "addexp", "setexp", "removeexp",
            "addlevel", "setlevel", "removelevel", "reload", "help");
}
