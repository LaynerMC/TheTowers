package com.thetowers;

import com.thetowers.api.TheTowersAPI;
import com.thetowers.arena.Arena;
import com.thetowers.arena.ArenaManager;
import com.thetowers.arena.RewardManager;
import com.thetowers.command.CommandTower;
import com.thetowers.compat.Proxy;
import com.thetowers.compat.TTMaterial;
import com.thetowers.config.ArenaNaming;
import com.thetowers.config.ConfigMenu;
import com.thetowers.config.CustomConfig;
import com.thetowers.config.Placements;
import com.thetowers.economy.EconomyHook;
import com.thetowers.game.GeneratorType;
import com.thetowers.game.KitManager;
import com.thetowers.game.KitMenu;
import com.thetowers.game.SpectatorMenu;
import com.thetowers.game.TeamMenu;
import com.thetowers.lang.Menus;
import com.thetowers.lang.Messages;
import com.thetowers.listeners.BlocksListeners;
import com.thetowers.listeners.ChatListeners;
import com.thetowers.listeners.ConfigListeners;
import com.thetowers.listeners.DamageListeners;
import com.thetowers.listeners.GameListeners;
import com.thetowers.listeners.KitListeners;
import com.thetowers.listeners.LobbyItemProtectionListener;
import com.thetowers.listeners.LobbyListeners;
import com.thetowers.listeners.LobbyProtectionListener;
import com.thetowers.listeners.MapListeners;
import com.thetowers.listeners.PlayerListeners;
import com.thetowers.listeners.SignListeners;
import com.thetowers.listeners.SpectatorListeners;
import com.thetowers.listeners.WorldListeners;
import com.thetowers.scoreboard.ScoreboardManager;
import com.thetowers.sign.JoinSignManager;
import com.thetowers.stats.StatsManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Entry point of the TheTowers plugin.
 *
 * <p>Self-contained minigame with no required dependency: several arenas
 * per server, in-game configuration, BungeeCord mode.
 *
 * <p>Targets ONLY Minecraft server 1.12.2 (compiled against spigot-api
 * 1.12.2, Java 8 bytecode). Clients of other versions (1.8.9 up to the
 * latest) connect through ViaVersion / ViaBackwards / ViaRewind, which
 * translate packets, items and effects; everything version-specific lives
 * in the {@code com.thetowers.compat} package.
 */
public class Main extends JavaPlugin {

    /** Prefix of every message sent by the plugin. */
    public static final String PREFIX = "";

    private ArenaManager arenaManager;
    private Messages messages;
    private Menus menus;
    private CustomConfig scoreboardConfig;
    private CustomConfig lobbyItemsConfig;
    private StatsManager statsManager;
    private JoinSignManager joinSignManager;
    private ConfigMenu configMenu;
    private Placements placements;
    private ArenaNaming arenaNaming;
    private SpectatorMenu spectatorMenu;
    private ChatListeners chatListeners;
    private ScoreboardManager scoreboardManager;
    private Proxy proxy;
    private EconomyHook economyHook;
    private KitManager kitManager;
    private KitMenu kitMenu;
    private TeamMenu teamMenu;
    private Map<String, GeneratorType> generatorTypes;
    private Location serverLobby;
    private boolean placeholderApi;
    private RewardManager rewardManager;
    private com.thetowers.coins.CoinsManager coinsManager;
    private com.thetowers.tops.TopManager topManager;
    private com.thetowers.leveling.LevelingManager levelingManager;
    private com.thetowers.party.PartyManager partyManager;
    private com.thetowers.worldmanager.WorldManager worldManager;
    private com.thetowers.stats.StatsMenu statsMenu;

    @Override
    public void onEnable() {
        // Before any file is read: recover the data of a server that used
        // the plugin under its old name.
        migrateOldDataFolder();
        getLogger().info("Server version: " + Bukkit.getBukkitVersion()
                + " (TheTowers targets 1.12.2).");

        List<String> missing = TTMaterial.resolveAll(getLogger());
        if (!missing.isEmpty()) {
            getLogger().severe("This Minecraft version does not provide the required materials: "
                    + missing + ". The plugin is disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        rewardManager = new RewardManager(this);
        messages = new Messages(this);
        menus = new Menus(this);
        scoreboardConfig = new CustomConfig(this, "modules/scoreboard.yml", true);
        lobbyItemsConfig = new CustomConfig(this, "modules/lobby-items.yml", true);
        loadServerLobby();
        loadGameContent();
        proxy = new Proxy(this);
        // Vault is optional: the hook only becomes usable if the Vault plugin
        // AND an economy provider are present.
        economyHook = new EconomyHook(this);

        // World manager module: loads its saved worlds BEFORE ArenaManager reads
        // arenas.yml, so the arena worlds already exist. Standalone, /bmv-driven.
        worldManager = new com.thetowers.worldmanager.WorldManager(this);

        arenaManager = new ArenaManager(this);
        statsManager = new StatsManager(this);
        // Separate leveling module: own leveling.yml, driven only by commands.
        levelingManager = new com.thetowers.leveling.LevelingManager(this);
        // Separate party module: own parties.yml, driven only by /party and the
        // chat listener. The game calls into it only through /party join.
        partyManager = new com.thetowers.party.PartyManager(this);
        // Coins economy (registers with Vault if present) and the tops cache.
        coinsManager = new com.thetowers.coins.CoinsManager(this);
        topManager = new com.thetowers.tops.TopManager(this);
        statsMenu = new com.thetowers.stats.StatsMenu(this);
        joinSignManager = new JoinSignManager(this);
        configMenu = new ConfigMenu(this);
        placements = new Placements(this);
        arenaNaming = new ArenaNaming(this);
        spectatorMenu = new SpectatorMenu(this);
        teamMenu = new TeamMenu(this);
        chatListeners = new ChatListeners(this);
        scoreboardManager = new ScoreboardManager(this);
        // Entry point for other plugins (softdepend: [TheTowers]).
        TheTowersAPI.init(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerListeners(this), this);
        pm.registerEvents(new DamageListeners(this), this);
        pm.registerEvents(new BlocksListeners(this), this);
        pm.registerEvents(new GameListeners(this), this);
        pm.registerEvents(new MapListeners(this), this);
        pm.registerEvents(new LobbyListeners(this), this);
        pm.registerEvents(new LobbyItemProtectionListener(this), this);
        pm.registerEvents(new LobbyProtectionListener(this), this);
        pm.registerEvents(new SignListeners(this), this);
        pm.registerEvents(new ConfigListeners(this), this);
        pm.registerEvents(new KitListeners(this), this);
        pm.registerEvents(new WorldListeners(this), this);
        pm.registerEvents(new SpectatorListeners(this), this);
        pm.registerEvents(new com.thetowers.listeners.MenuProtectionListener(), this);
        pm.registerEvents(chatListeners, this);
        // Party chat intercept (NORMAL, before the game's HIGHEST chat) + quit cleanup.
        pm.registerEvents(new com.thetowers.listeners.PartyListener(this, partyManager), this);

        // Refresh the join signs (state + players), every second.
        new BukkitRunnable() {
            @Override
            public void run() {
                joinSignManager.updateAll();
            }
        }.runTaskTimer(this, 40L, 20L);

        // Refresh the scoreboards, every second.
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    scoreboardManager.updateAll();
                } catch (Throwable e) {
                    getLogger().warning("Scoreboard refresh failed: " + e);
                }
            }
        }.runTaskTimer(this, 20L, 20L);

        PluginCommand command = getCommand("tt");
        if (command == null) {
            getLogger().severe("The /tt command is missing from plugin.yml. The plugin is disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        CommandTower executor = new CommandTower(this, arenaManager);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        // /coins: user command showing the player's coin balance (Vault).
        PluginCommand coinsCommand = getCommand("coins");
        if (coinsCommand != null) {
            com.thetowers.coins.CoinsCommand coinsExecutor = new com.thetowers.coins.CoinsCommand(this);
            coinsCommand.setExecutor(coinsExecutor);
            coinsCommand.setTabCompleter(coinsExecutor);
        }

        // /leveling: the separate leveling system (info, addexp, setexp, ...).
        // Driven only by commands; the game fires it through rewards.yml.
        PluginCommand levelingCommand = getCommand("leveling");
        if (levelingCommand != null) {
            com.thetowers.leveling.LevelingCommand levelingExecutor =
                    new com.thetowers.leveling.LevelingCommand(this, levelingManager);
            levelingCommand.setExecutor(levelingExecutor);
            levelingCommand.setTabCompleter(levelingExecutor);
        }

        // /bmv: the standalone world-manager module (create / load / unload /
        // teleport / info / list / set / flags). Driven only by commands; every
        // sub-command checks thetowers.admin. The game never calls into it.
        PluginCommand bmvCommand = getCommand("bmv");
        if (bmvCommand != null) {
            com.thetowers.worldmanager.WorldManagerCommand bmvExecutor =
                    new com.thetowers.worldmanager.WorldManagerCommand(this, worldManager);
            bmvCommand.setExecutor(bmvExecutor);
            bmvCommand.setTabCompleter(bmvExecutor);
        }

        // /party: the party system (invite, accept, deny, ignore, disband,
        // promote, chat, list, leave, kick, join). Self-contained: own
        // parties.yml, driven only by commands and the PartyListener.
        PluginCommand partyCommand = getCommand("party");
        if (partyCommand != null) {
            com.thetowers.party.PartyCommand partyExecutor =
                    new com.thetowers.party.PartyCommand(this, partyManager);
            partyCommand.setExecutor(partyExecutor);
            partyCommand.setTabCompleter(partyExecutor);
        }

        // Hot reload (/reload): players are already connected, so PlayerJoinEvent
        // will not fire for them. Load their stats now, otherwise a first counter
        // would create a zero entry that would overwrite their real stats on save.
        // (Kit selections are part of stats now, so they load together.)
        for (Player player : Bukkit.getOnlinePlayers()) {
            statsManager.load(player);
        }

        // %thetowers_...% placeholders: only if PlaceholderAPI is present.
        // The "new Placeholders" is only reached inside this branch, so the
        // JVM never loads that class (or the PAPI API) on a server without PAPI.
        placeholderApi = getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (placeholderApi) {
            new com.thetowers.api.Placeholders(this, "thetowers").register();
            // Old prefix, from before the rename: scoreboards and tabs already
            // configured with %thetower_...% keep working.
            new com.thetowers.api.Placeholders(this, "thetower").register();
            getLogger().info("PlaceholderAPI detected: %thetowers_...% placeholders registered"
                    + " (old prefix %thetower_...% still accepted).");
        }
    }

    /**
     * The plugin was called "TheTower" before being renamed "TheTowers": its
     * data folder (arenas, stats, kits, languages...) was therefore
     * plugins/TheTower. On the first start under the new name, that folder
     * is renamed so the server finds everything back, with no action from
     * the admin. If both folders exist, nothing is touched (the choice is
     * left to the admin).
     */
    private void migrateOldDataFolder() {
        File current = getDataFolder();
        File old = new File(current.getParentFile(), "TheTower");
        if (current.exists() || !old.isDirectory()) {
            return;
        }
        if (old.renameTo(current)) {
            getLogger().info("Data folder renamed: plugins/TheTower -> plugins/" + current.getName()
                    + " (old plugin name).");
        } else {
            getLogger().warning("Could not rename plugins/TheTower to plugins/" + current.getName()
                    + ": move its contents by hand to recover your arenas and stats.");
        }
    }

    /**
     * True if PlaceholderAPI is present: mandatory guard before any call to
     * {@code api.PapiBridge} (see the isolation rule in this class).
     */
    public boolean hasPlaceholderApi() {
        return placeholderApi;
    }

    public RewardManager getRewardManager() {
        return rewardManager;
    }

    /** The separate leveling module (level/EXP, level-up rewards, placeholders). */
    public com.thetowers.leveling.LevelingManager getLevelingManager() {
        return levelingManager;
    }

    /** The party module (/party, parties.yml, party chat, party arena join). */
    public com.thetowers.party.PartyManager getPartyManager() {
        return partyManager;
    }

    /** The standalone world-manager module (/bmv, world-manager.yml). */
    public com.thetowers.worldmanager.WorldManager getWorldManager() {
        return worldManager;
    }

    @Override
    public void onDisable() {
        // Without this, reloading the plugin would leave players stuck
        // mid-game, with the map left unrestored.
        if (arenaManager != null) {
            arenaManager.resetAll();
            arenaManager.save();
        }
        if (scoreboardManager != null) {
            scoreboardManager.clearAll();
        }
        if (topManager != null) {
            topManager.shutdown();
        }
        // After resetting arenas: save the stats cache and wait for the writes
        // to finish before giving control back to the server.
        if (statsManager != null) {
            statsManager.shutdown();
        }
        if (coinsManager != null) {
            coinsManager.shutdown();
        }
        if (kitManager != null) {
            kitManager.shutdown();
        }
        if (worldManager != null) {
            worldManager.shutdown();
        }
    }

    /**
     * (Re)reads the game content: kits (kits.yml) and generator types
     * (config.yml). Called at startup and by /tt reload.
     */
    public void loadGameContent() {
        if (kitManager == null) {
            kitManager = new KitManager(this);
            kitMenu = new KitMenu(this);
        } else {
            kitManager.load();
        }
        generatorTypes = GeneratorType.load(getConfig().getConfigurationSection("generators"), getLogger());
        getLogger().info(generatorTypes.size() + " generator type(s) loaded: "
                + generatorTypes.keySet() + ".");
    }

    // ================= Component accessors =================

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public StatsManager getStatsManager() {
        return statsManager;
    }

    public Messages getMessages() {
        return messages;
    }

    public JoinSignManager getJoinSignManager() {
        return joinSignManager;
    }

    public ConfigMenu getConfigMenu() {
        return configMenu;
    }

    public Placements getPlacements() {
        return placements;
    }

    public ArenaNaming getArenaNaming() {
        return arenaNaming;
    }

    public SpectatorMenu getSpectatorMenu() {
        return spectatorMenu;
    }

    public ChatListeners getChatListeners() {
        return chatListeners;
    }

    public ScoreboardManager getScoreboardManager() {
        return scoreboardManager;
    }

    /** Vault economy for kit purchases (unusable if Vault is absent). */
    public EconomyHook getEconomyHook() {
        return economyHook;
    }

    public KitManager getKitManager() {
        return kitManager;
    }

    public KitMenu getKitMenu() {
        return kitMenu;
    }

    /** Team choice menu (wool block in the waiting lobby). */
    public TeamMenu getTeamMenu() {
        return teamMenu;
    }

    /** Generator types from config.yml, keyed by lowercase name. */
    public Map<String, GeneratorType> getGeneratorTypes() {
        return generatorTypes;
    }

    /** True if the map must be regenerated (terrain reset) at the end of a game. */
    public boolean isMapRegenerationEnabled() {
        return getConfig().getBoolean("map-regeneration", true);
    }

    /** True if the server is in arena mode (auto-join on connect). */
    public boolean isArenaModeEnabled() {
        return getConfig().getBoolean("arena-mode.enabled", false);
    }

    /** Arena to join automatically in arena mode (may be empty). */
    public String getArenaModeArena() {
        return getConfig().getString("arena-mode.arena", "");
    }

    // ================= Main lobby =================

    public Location getServerLobby() {
        return serverLobby;
    }

    /**
     * Sends a player "home" at the end of a game or on /tt leave: to the
     * proxy's hub server if BungeeCord mode is active, otherwise to the
     * local lobby. The player must have been reset beforehand.
     */
    public void returnToLobby(Player player) {
        if (proxy != null && proxy.isEnabled()) {
            proxy.sendToFallback(player);
            return;
        }
        sendToLocalLobby(player);
    }

    /**
     * Teleports to THIS server's lobby, never through the proxy: useful at
     * login, where sending a message to the proxy would be premature.
     */
    public void sendToLocalLobby(Player player) {
        if (serverLobby != null && serverLobby.getWorld() != null) {
            player.teleport(serverLobby);
            com.thetowers.lobby.MainLobbyItems.give(this, player);
            return;
        }
        // Fallback: no server lobby defined (/tt setlobby never used) or its
        // world is not loaded. The player is sent to the main world's spawn
        // as a last resort, rather than being left inside the arena.
        List<World> worlds = Bukkit.getWorlds();
        if (!worlds.isEmpty()) {
            player.teleport(worlds.get(0).getSpawnLocation());
            com.thetowers.lobby.MainLobbyItems.give(this, player);
        }
    }

    public void setServerLobby(Location location) {
        this.serverLobby = location;
        if (location == null || location.getWorld() == null) {
            return;
        }
        FileConfiguration config = getConfig();
        config.set("server-lobby.world", location.getWorld().getName());
        config.set("server-lobby.x", location.getX());
        config.set("server-lobby.y", location.getY());
        config.set("server-lobby.z", location.getZ());
        config.set("server-lobby.yaw", location.getYaw());
        config.set("server-lobby.pitch", location.getPitch());
        saveConfig();
    }

    /**
     * A world has just been loaded (often by a world manager, after
     * TheTowers): hand back to it whatever was waiting for it.
     */
    public void onWorldLoaded(World world) {
        if (arenaManager != null) {
            arenaManager.resolvePending(world);
        }
        if (joinSignManager != null) {
            joinSignManager.resolvePending(world);
        }
        if (serverLobby == null && world.getName().equals(getConfig().getString("server-lobby.world"))) {
            loadServerLobby();
        }
    }

    public void loadServerLobby() {
        serverLobby = null;
        ConfigurationSection section = getConfig().getConfigurationSection("server-lobby");
        if (section == null) {
            return;
        }
        String worldName = section.getString("world");
        if (worldName == null || worldName.isEmpty()) {
            return;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            // The position stays in config.yml: it will be picked up again by
            // onWorldLoaded() if the world loads after the plugin.
            getLogger().warning("The main lobby's world (" + worldName
                    + ") is not loaded yet: lobby will be picked up when it loads.");
            return;
        }
        serverLobby = new Location(world,
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw"),
                (float) section.getDouble("pitch"));
    }

    // ================= Game settings =================

    /** Duration of the countdown before the game starts, in seconds. */
    public int getCountdown() {
        return Math.max(3, getConfig().getInt("countdown", 30));
    }

    /**
     * Default maximum duration of a game, in seconds. Initial value of new
     * arenas; each one can then change it (menu or /tt setduration).
     */
    public int getGameDuration() {
        return Math.max(60, getConfig().getInt("game-duration", 2400));
    }

    /** Default points needed to win (10). */
    public int getDefaultPointsToWin() {
        return Math.max(1, getConfig().getInt("points-to-win", 10));
    }

    /** Minimum delay between two points scored by the same player, in milliseconds. */
    public long getMarkCooldownMillis() {
        return Math.max(0L, getConfig().getLong("mark-cooldown", 3L)) * 1000L;
    }

    /** Seconds between the end of the game and the send-back to the lobby. */
    public int getEndDelay() {
        return Math.max(1, getConfig().getInt("end-delay", 10));
    }

    /** Seconds of invulnerability after a spawn; 0 = none. */
    public int getRespawnProtection() {
        return Math.max(0, Math.min(30, getConfig().getInt("respawn-protection", 3)));
    }

    /** True if a spectator must stay inside the play area. */
    public boolean isSpectatorBounded() {
        return getConfig().getBoolean("spectator.stay-in-area", true);
    }

    /** Master switch for spectators (on top of each arena's own setting). */
    public boolean isSpectatorEnabled() {
        return getConfig().getBoolean("spectator.enabled", true);
    }

    /** Maximum number of spectators per arena. */
    public int getSpectatorMax() {
        return Math.max(1, getConfig().getInt("spectator.max-per-arena", 5));
    }

    /** True if the plugin handles game chat (team tags, filtering). */
    public boolean isChatEnabled() {
        return getConfig().getBoolean("chat.enabled", true);
    }

    /** Scope of the everyone chat: {@code arena} (the game) or {@code world} (the world). */
    public String getChatScope() {
        return "world".equalsIgnoreCase(getConfig().getString("chat.scope", "arena")) ? "world" : "arena";
    }

    /** Message prefix that sends to everyone instead of the team. */
    public String getChatAllPrefix() {
        String prefix = getConfig().getString("chat.all-prefix", "!");
        return prefix == null || prefix.isEmpty() ? "!" : prefix;
    }

    /** Chat format in the main server lobby (LOBBY, outside any arena); empty = default. */
    public String getChatLobbyFormat() {
        String format = getConfig().getString("chat.lobby-format", "");
        return format == null ? "" : format;
    }

    /**
     * Chat format in the waiting room of an arena (PRELOBBY, before the game
     * starts); empty = fall back to the lobby (main server lobby) format.
     */
    public String getChatPrelobbyFormat() {
        String format = getConfig().getString("chat.prelobby-format", "");
        return format == null ? "" : format;
    }

    /** Chat format during a running game; empty = default. */
    public String getChatGameFormat() {
        String format = getConfig().getString("chat.game-format", "");
        return format == null ? "" : format;
    }

    /** Tag before a team-only message (variables %color% and %team% replaced by the caller). */
    public String getChatTagTeam() {
        return chatText("chat.tag-team", "%color%[Team] &r");
    }

    /** Tag before an everyone message (variables %color% and %team% replaced by the caller). */
    public String getChatTagAll() {
        return chatText("chat.tag-all", "&7[All] %color%[%team%] &r");
    }

    /** Tag before a spectator message. */
    public String getChatTagSpectator() {
        return chatText("chat.tag-spectator", "&8[Spectator] &r");
    }

    /** Confirmation shown by /tt chat team (variable %prefix%). */
    public String getChatModeTeam() {
        return chatText("chat.mode-team", "&aYour chat is your team chat. &7Start with %prefix% to talk to everyone.");
    }

    /** Confirmation shown by /tt chat all (variable %prefix%). */
    public String getChatModeAll() {
        return chatText("chat.mode-all", "&aYour chat is the everyone chat. &7Start with %prefix% to talk to your team.");
    }

    /** A config.yml chat text with colors; empty/absent falls back to the default. */
    private String chatText(String path, String fallback) {
        String text = getConfig().getString(path, fallback);
        return text == null || text.isEmpty() ? fallback : text;
    }

    // ================= Game start message =================

    /** True if the centered game start message is enabled (messages.yml). */
    public boolean isGameStartMessageEnabled() {
        return messages.getBoolean("game-start.enabled", true);
    }

    /** True if the start message must be centered in the chat (messages.yml). */
    public boolean isGameStartMessageCentered() {
        return messages.getBoolean("game-start.centered", true);
    }

    /**
     * The lines of the start message (accepted variables: %arena%, %goal%,
     * %minutes%). Empty list = no message. Read from messages.yml
     * (game-start.message), raw so the caller applies colors itself.
     */
    public List<String> getGameStartLines() {
        return messages.rawStringList("game-start.message");
    }

    /** True if a player who disconnects mid-game keeps their slot to come back. */
    public boolean isReconnectionEnabled() {
        return getConfig().getBoolean("reconnection.enabled", true);
    }

    /** Delay to come back after a disconnect, in ms; 0 = until the end of the game. */
    public long getReconnectTimeoutMillis() {
        return Math.max(0L, getConfig().getLong("reconnection.timeout", 180L)) * 1000L;
    }

    /** Leave-item countdown (seconds) in the lobby; 0 = leave instantly. */
    public int getLobbyLeaveCountdown() {
        return Math.max(0, getConfig().getInt("lobby-items.leave-countdown", 5));
    }

    /** True if the hub scoreboard (scoreboard-lobby, main server lobby) is enabled. */
    public boolean isScoreboardLobbyEnabled() {
        return scoreboardConfig.getBoolean("scoreboard-lobby.enabled", true);
    }

    /** True if the waiting-room scoreboard (scoreboard-prelobby) is enabled. */
    public boolean isScoreboardPrelobbyEnabled() {
        return scoreboardConfig.getBoolean("scoreboard-prelobby.enabled", true);
    }

    // ================= Secondary configs =================

    /** Scoreboards / boss bar / nametags / action bar (scoreboard.yml). */
    public CustomConfig getScoreboardConfig() {
        return scoreboardConfig;
    }

    /** Lobby items (arena + main lobby) configuration (lobby-items.yml). */
    public CustomConfig getLobbyItemsConfig() {
        return lobbyItemsConfig;
    }

    /** Menu texts (menus.yml): team, stats, spectator, setup, lobby items. */
    public Menus getMenus() {
        return menus;
    }

    /** TheTowers coin economy (Vault provider). */
    public com.thetowers.coins.CoinsManager getCoinsManager() {
        return coinsManager;
    }

    /** Leaderboard caches for the %thetowers_top_...% placeholders. */
    public com.thetowers.tops.TopManager getTopManager() {
        return topManager;
    }

    /** Configurable /tt stats inventory menu. */
    public com.thetowers.stats.StatsMenu getStatsMenu() {
        return statsMenu;
    }
}
