package com.thetowers.worldmanager;

import com.thetowers.Main;
import com.thetowers.worldmanager.world.WorldData;
import com.thetowers.worldmanager.world.WorldOption;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The {@code /bmv} command (aliases {@code mv}, {@code worldmanager}): a plain
 * Bukkit {@link CommandExecutor} mirroring {@code /leveling}. Every
 * sub-command requires {@code thetowers.admin}. The world manager is fully
 * standalone within TheTowers: the game never calls into it, it is driven only
 * by these commands.
 *
 * <p>Sub-commands: create, load, unload, tp, info, list, set, flags, reload.
 */
public final class WorldManagerCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "thetowers.admin";

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "create", "load", "unload", "tp", "info", "list", "set", "flags", "reload", "help");

    private final WorldManager manager;

    public WorldManagerCommand(Main main, WorldManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            new Message("no_permission").sendMessage(sender);
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help":
                sendHelp(sender);
                break;
            case "create":
                create(sender, args);
                break;
            case "load":
                load(sender, args);
                break;
            case "unload":
                unload(sender, args);
                break;
            case "tp":
            case "teleport":
                teleport(sender, args);
                break;
            case "info":
            case "infos":
                info(sender, args);
                break;
            case "list":
            case "l":
                list(sender);
                break;
            case "set":
                set(sender, args);
                break;
            case "flags":
            case "flag":
                flags(sender);
                break;
            case "reload":
                manager.reload();
                sender.sendMessage("§aWorld manager configuration reloaded.");
                break;
            default:
                sendHelp(sender);
                break;
        }
        return true;
    }

    // ============================== sub-commands ==============================

    private void create(CommandSender sender, String[] args) {
        if (args.length < 2) {
            new Message("no_world_target").sendMessage(sender);
            return;
        }
        String worldName = args[1];
        if (manager.getConfig().isDisableWorldName(worldName)) {
            new Message("forbidden_world_name").sendMessage(sender);
            return;
        }
        if (WorldManager.getWorldDataManager().isWorldAlreadyCreated(worldName)
                || worldName.equalsIgnoreCase("overworld")) {
            new Message("world_already_exist").sendMessage(sender);
            return;
        }

        Map<WorldOption, Object> options = new HashMap<>();
        String[] flags = Arrays.copyOfRange(args, 2, args.length);
        for (int i = 0; i < flags.length - 1; i += 2) {
            String flag = flags[i];
            try {
                options.put(WorldManager.getWorldOptionManager().getOption(flag), flags[i + 1]);
            } catch (Exception exception) {
                new Message("flag_not_found").withPlaceHolder("name", flag).sendMessage(sender);
            }
        }

        WorldData worldData = new WorldData(worldName, options);
        worldData.createWorldAsync(sender).thenAccept(manager.getConfig()::saveWorldDataToFile);
    }

    private void load(CommandSender sender, String[] args) {
        if (args.length < 2) {
            new Message("no_world_target").sendMessage(sender);
            return;
        }
        String targetWorld = args[1];
        if (!manager.getConfig().isWorldFolderExisting(targetWorld)) {
            new Message("world_not_found").withPlaceHolder("name", targetWorld).sendMessage(sender);
            return;
        }

        Map<WorldOption, Object> options = new HashMap<>();
        File file = new File(Bukkit.getWorldContainer(), targetWorld);
        try {
            if (new File(file, "DIM-1").exists()) {
                options.put(WorldManager.getWorldOptionManager().getOption("-e"), "nether");
            } else if (new File(file, "DIM1").exists()) {
                options.put(WorldManager.getWorldOptionManager().getOption("-e"), "the_end");
            }
        } catch (Exception ignored) {
            // -e option always exists; safe to ignore.
        }

        WorldData worldData = new WorldData(targetWorld, options);
        worldData.createWorldAsync(sender).thenAccept(manager.getConfig()::saveWorldDataToFile);
    }

    private void unload(CommandSender sender, String[] args) {
        if (args.length < 2) {
            new Message("no_world_target").sendMessage(sender);
            return;
        }
        String targetWorld = args[1];
        World world = Bukkit.getWorld(targetWorld);
        if (world == null) {
            new Message("world_not_found").withPlaceHolder("name", targetWorld).sendMessage(sender);
            return;
        }
        if (!world.getPlayers().isEmpty()) {
            sender.sendMessage("§cYou can't unload a world who contain players");
            return;
        }
        manager.getConfig().removeWorldFromFile(world.getName());
        WorldManager.getWorldDataManager().removeWorldData(world.getName());
        Bukkit.unloadWorld(world, true);
        new Message("cmd_unload_success").withPlaceHolder("name", targetWorld).sendMessage(sender);
    }

    private void teleport(CommandSender sender, String[] args) {
        if (args.length < 2) {
            new Message("no_world_target").sendMessage(sender);
            return;
        }
        String worldName = args[1];
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            new Message("world_not_found").withPlaceHolder("name", worldName).sendMessage(sender);
            return;
        }
        if (args.length >= 3) {
            Player target = Bukkit.getPlayer(args[2]);
            if (target == null) {
                new Message("no_player_target").sendMessage(sender);
                return;
            }
            target.teleport(world.getSpawnLocation());
            new Message("cmd_teleport_success")
                    .withPlaceHolder("player", target.getName())
                    .withPlaceHolder("name", world.getName())
                    .sendMessage(sender);
            return;
        }
        if (!(sender instanceof Player)) {
            new Message("only_ingame_command").sendMessage(sender);
            return;
        }
        Player player = (Player) sender;
        player.teleport(world.getSpawnLocation());
        new Message("cmd_teleport").withPlaceHolder("name", world.getName()).sendMessage(player);
    }

    private void info(CommandSender sender, String[] args) {
        String worldName;
        if (args.length >= 2) {
            worldName = args[1];
        } else if (sender instanceof Player) {
            worldName = ((Player) sender).getWorld().getName();
        } else {
            new Message("no_world_target").sendMessage(sender);
            return;
        }
        WorldData worldData = WorldManager.getWorldDataManager().getWorldDataFromWorldName(worldName);
        if (worldData == null) {
            new Message("world_not_found").withPlaceHolder("name", worldName).sendMessage(sender);
            return;
        }
        sender.sendMessage("§a" + worldData.getWorldName() + ":");
        worldData.getOptionsAndValue().forEach((key, value) ->
                new Message("infos_pattern")
                        .withPlaceHolder("name", key.getName())
                        .withPlaceHolder("value", value.toString())
                        .sendMessage(sender));
    }

    private void list(CommandSender sender) {
        sender.sendMessage("§aLoaded worlds");
        Set<String> loaded = WorldManager.getWorldDataManager().getWorldsData().stream()
                .map(WorldData::getWorldName)
                .collect(Collectors.toSet());
        Set<File> worldFolders = new LinkedHashSet<>();
        File[] rootFiles = Bukkit.getWorldContainer().listFiles();
        if (rootFiles != null) {
            worldFolders.addAll(Arrays.asList(rootFiles));
        }
        for (World world : Bukkit.getServer().getWorlds()) {
            File parent = world.getWorldFolder().getParentFile();
            if (parent == null) {
                continue;
            }
            File[] dimensionFiles = parent.listFiles();
            if (dimensionFiles != null) {
                worldFolders.addAll(Arrays.asList(dimensionFiles));
            }
        }
        for (File worldFolder : worldFolders) {
            File levelDat = new File(worldFolder, "level.dat");
            File uidDat = new File(worldFolder, "uid.dat");
            File regionFolder = new File(worldFolder, "region");
            if (!levelDat.exists() && !regionFolder.exists() && !uidDat.exists()) {
                continue;
            }
            if (loaded.contains(worldFolder.getName())) {
                sender.sendMessage("§e" + worldFolder.getName());
            } else if (Bukkit.getWorld(worldFolder.getName()) != null) {
                sender.sendMessage("§e" + worldFolder.getName() + " §o§cnot loaded with World Manager");
            } else {
                sender.sendMessage("§e" + worldFolder.getName() + " §o§dnot loaded at all");
            }
        }
    }

    private void set(CommandSender sender, String[] args) {
        if (args.length < 4) {
            new Message("no_world_target").sendMessage(sender);
            return;
        }
        String targetWorld = args[1];
        String flagName = args[2];
        String flagValue = args[3];
        WorldData worldData = WorldManager.getWorldDataManager().getWorldDataFromWorldName(targetWorld);
        if (worldData == null) {
            new Message("world_not_found").withPlaceHolder("name", targetWorld).sendMessage(sender);
            return;
        }
        try {
            WorldOption worldOption = WorldManager.getWorldOptionManager().getOption(flagName);
            worldOption.optionExecutor(flagValue, worldData);
            sender.sendMessage("§e[WorldManager] §aYou set the value §e" + flagName + ": " + flagValue
                    + " §ato the world §e" + worldData.getWorldName());
        } catch (Exception exception) {
            new Message("error_world_creator").sendMessage(sender);
        }
        manager.getConfig().saveWorldDataToFile(worldData);
    }

    private void flags(CommandSender sender) {
        WorldManager.getWorldOptionManager().getAvailableOptionsList().forEach(option ->
                new Message("flags_pattern")
                        .withPlaceHolder("usage", option.getUsage())
                        .withPlaceHolder("description", option.getDescription())
                        .sendMessage(sender));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§e§lWorld Manager commands:");
        String[][] entries = {
                {"/bmv create <name> [flags]", "Create a new world"},
                {"/bmv load <name>", "Load an existing world"},
                {"/bmv unload <name>", "Unload a world"},
                {"/bmv tp <name> [player]", "Teleport to a world"},
                {"/bmv info [name]", "Display world info"},
                {"/bmv list", "List loaded worlds"},
                {"/bmv set <name> <flag> <value>", "Set a flag on a world"},
                {"/bmv flags", "List available flags"},
                {"/bmv reload", "Reload the config"}
        };
        for (String[] entry : entries) {
            new Message("help_pattern")
                    .withPlaceHolder("usage", entry[0])
                    .withPlaceHolder("description", entry[1])
                    .sendMessage(sender);
        }
    }

    // ============================== tab completion ==============================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2) {
            List<String> names = new ArrayList<>();
            for (WorldData wd : WorldManager.getWorldDataManager().getWorldsData()) {
                names.add(wd.getWorldName());
            }
            return filter(names, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            List<String> flagNames = new ArrayList<>();
            for (WorldOption option : WorldManager.getWorldOptionManager().getAvailableOptionsList()) {
                flagNames.add(option.getName());
                flagNames.add(option.getShortName());
            }
            return filter(flagNames, args[2]);
        }
        return Collections.emptyList();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        Collections.sort(out);
        return out;
    }
}
