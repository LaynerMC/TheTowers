package com.thetowers.party;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import com.thetowers.Main;

/**
 * The {@code /party} command and its tab completion.
 *
 * <p>Permissions: {@code thetowers.party.use} (default true) gates every
 * player subcommand; {@code thetowers.party.reload} (default op) gates
 * {@code /party reload}. There is intentionally no permission node on the
 * command itself in plugin.yml, so {@code /party help} always works.
 */
public class PartyCommand implements CommandExecutor, TabCompleter {

    private static final String USE = "thetowers.party.use";
    private static final String RELOAD = "thetowers.party.reload";

    /** Subcommands that take no argument. */
    private static final List<String> NO_ARG =
            Arrays.asList("disband", "chat", "list", "leave", "help", "reload");

    /** Subcommands that take a player name. */
    private static final List<String> PLAYER_ARG =
            Arrays.asList("invite", "accept", "deny", "promote", "kick");

    private static final List<String> ALL_SUBS = new ArrayList<String>();
    static {
        ALL_SUBS.addAll(NO_ARG);
        ALL_SUBS.addAll(PLAYER_ARG);
        Collections.sort(ALL_SUBS);
    }

    private final Main main;
    private final PartyManager manager;

    public PartyCommand(Main main, PartyManager manager) {
        this.main = main;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(manager.getConfig().msg("ingame-only"));
            return true;
        }
        Player player = (Player) sender;
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);

        // help needs no permission.
        if ("help".equals(sub)) {
            for (String line : manager.getConfig().msgList("help")) {
                player.sendMessage(line);
            }
            return true;
        }
        if ("reload".equals(sub)) {
            if (!player.hasPermission(RELOAD)) {
                player.sendMessage(manager.getConfig().prefix() + manager.getConfig().msg("no-permission"));
                return true;
            }
            manager.reload();
            player.sendMessage(manager.getConfig().prefix() + "&aParties reloaded.");
            return true;
        }

        if (!player.hasPermission(USE)) {
            player.sendMessage(manager.getConfig().prefix() + manager.getConfig().msg("no-permission"));
            return true;
        }

        switch (sub) {
            case "invite":
                invite(player, args);
                break;
            case "accept":
                acceptDeny(player, args, true);
                break;
            case "deny":
                acceptDeny(player, args, false);
                break;
            case "disband":
                manager.disband(player);
                break;
            case "promote":
                promote(player, args);
                break;
            case "chat":
                manager.toggleChat(player);
                break;
            case "list":
                list(player);
                break;
            case "leave":
                manager.leave(player);
                break;
            case "kick":
                kick(player, args);
                break;
            default:
                player.sendMessage(manager.getConfig().prefix()
                        + manager.getConfig().msg("unknown-subcommand"));
                break;
        }
        return true;
    }

    // ----- subcommand handlers -----

    private void invite(Player player, String[] args) {
        Player target = requirePlayer(player, args, "invite");
        if (target == null) {
            return;
        }
        manager.invite(player, target);
    }

    private void acceptDeny(Player player, String[] args, boolean accept) {
        Player inviter = requirePlayer(player, args, accept ? "accept" : "deny");
        if (inviter == null) {
            return;
        }
        if (accept) {
            manager.accept(player, inviter);
        } else {
            manager.deny(player, inviter);
        }
    }

    private void promote(Player player, String[] args) {
        Player target = requirePlayer(player, args, "promote");
        if (target == null) {
            return;
        }
        manager.promote(player, target);
    }

    private void kick(Player player, String[] args) {
        Player target = requirePlayer(player, args, "kick");
        if (target == null) {
            return;
        }
        manager.kick(player, target);
    }

    private void list(Player player) {
        Party party = manager.getParty(player.getUniqueId());
        PartyConfig cfg = manager.getConfig();
        if (party == null) {
            player.sendMessage(cfg.prefix() + cfg.msg("not-in-party"));
            return;
        }
        player.sendMessage(cfg.msg("list-header", "size", party.size(), "max", cfg.maxSize()));
        if (party.size() == 0) {
            player.sendMessage(cfg.prefix() + cfg.msg("list-empty"));
            return;
        }
        for (UUID id : party.getMembers()) {
            boolean leader = party.isLeader(id);
            Player online = Bukkit.getPlayer(id);
            String name = online != null ? online.getName() : nameOf(id);
            if (leader) {
                player.sendMessage(cfg.msg("list-leader", "player", name));
            } else {
                player.sendMessage(cfg.msg("list-member", "player", name));
            }
        }
    }

    // ----- helpers -----

    /** Resolves args[1] to an online player, or sends the usage/error message. */
    private Player requirePlayer(Player sender, String[] args, String sub) {
        if (args.length < 2) {
            sender.sendMessage(manager.getConfig().prefix()
                    + manager.getConfig().msg("player-not-specified", "sub", sub));
            return null;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(manager.getConfig().prefix()
                    + manager.getConfig().msg("player-not-found"));
            return null;
        }
        return target;
    }

    private static String nameOf(UUID id) {
        if (id == null) {
            return "?";
        }
        Player p = Bukkit.getPlayer(id);
        return p != null ? p.getName() : id.toString().substring(0, 8);
    }

    // ----- tab completion -----

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length <= 1) {
            List<String> out = new ArrayList<String>();
            for (String s : ALL_SUBS) {
                if (args.length == 0 || s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
            return out;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (PLAYER_ARG.contains(sub)) {
            if (args.length == 2) {
                List<String> out = new ArrayList<String>();
                String prefix = args[1].toLowerCase(Locale.ROOT);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        out.add(p.getName());
                    }
                }
                return out;
            }
        }
        return Collections.emptyList();
    }
}
