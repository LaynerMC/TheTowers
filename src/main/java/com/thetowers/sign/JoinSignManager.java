package com.thetowers.sign;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.arena.State;
import com.thetowers.lang.Messages;

/**
 * Join signs: creation, persistence ({@code joinsigns.yml}), live display
 * (state + player count) and click to join.
 *
 * <p>A sign targeting a specific arena shows its live state; a <b>random</b>
 * sign sends to the fullest joinable arena, to concentrate players on the
 * games about to start.
 *
 * <p>The "is it a sign" detection goes through {@code instanceof Sign}, never
 * through a material name: it works regardless of the material naming
 * (SIGN/WALL_SIGN on 1.12.2).
 */
public class JoinSignManager {

    /** Fixed header (brand), not translated. */
    private static final String HEADER =
            ChatColor.translateAlternateColorCodes('&', "&1&l[TheTowers]");

    private final Main main;
    private final Map<String, JoinSign> signs = new LinkedHashMap<String, JoinSign>();
    /**
     * Signs whose world was not loaded when reading: kept raw, rewritten at
     * each save and activated as soon as their world loads.
     */
    private final List<Map<String, Object>> pendingSigns = new ArrayList<Map<String, Object>>();

    private File file;
    private FileConfiguration config;

    public JoinSignManager(Main main) {
        this.main = main;
        load();
    }

    // ================= Registration =================

    public void register(Location location, String target) {
        signs.put(key(location), new JoinSign(location, target));
        save();
    }

    public void unregister(Location location) {
        if (signs.remove(key(location)) != null) {
            save();
        }
    }

    /** Join sign at this position, or {@code null}. */
    public JoinSign get(Location location) {
        return signs.get(key(location));
    }

    // ================= Click: join =================

    public void join(Player player, JoinSign sign) {
        Arena current = main.getArenaManager().getArenaOf(player);
        if (current != null) {
            player.sendMessage(Main.PREFIX
                    + Messages.tr("command.already_in_arena", "arena", current.getName()));
            return;
        }
        // Party gate: a party member who is not the leader cannot join a game
        // on their own (the leader joining moves the whole party in).
        if (main.getPartyManager() != null) {
            String partyBlock = main.getPartyManager().joinGateMessage(player);
            if (partyBlock != null) {
                player.sendMessage(Main.PREFIX + partyBlock);
                return;
            }
        }
        com.thetowers.party.PartyManager pm = main.getPartyManager();
        Arena preferred = sign.isRandom() ? null : main.getArenaManager().getArena(sign.getTarget());
        // A specific sign whose arena no longer exists.
        if (!sign.isRandom() && preferred == null) {
            player.sendMessage(Main.PREFIX + Messages.tr("sign.no_game"));
            return;
        }
        Arena target;
        String partyTeam = null;
        if (pm != null && pm.isPartyLeader(player)) {
            // Party leader: the sign's arena (if any) is tried first; if it
            // cannot fit the whole party on one team, fall back to another
            // joinable arena. The party is never split.
            com.thetowers.party.PartyManager.PartyTarget t = pm.findPartyTarget(player, preferred);
            if (t == null) {
                player.sendMessage(Main.PREFIX + Messages.tr("sign.no_game"));
                player.sendMessage(pm.getConfig().prefix() + pm.getConfig().msg("join-no-fit"));
                return;
            }
            target = t.arena;
            partyTeam = t.team;
            if (preferred != null && !target.getName().equals(preferred.getName())) {
                player.sendMessage(pm.getConfig().prefix() + pm.getConfig().msg("join-moved",
                        "chosen", preferred.getName(), "arena", target.getName()));
            }
        } else {
            target = sign.isRandom() ? bestRandom() : preferred;
            if (target == null) {
                player.sendMessage(Main.PREFIX + Messages.tr("sign.no_game"));
                return;
            }
        }
        String problem = target.join(player);
        if (problem != null) {
            player.sendMessage(Main.PREFIX + Messages.tr("command.join_failed", "reason", problem));
        } else if (partyTeam != null) {
            pm.onLeaderJoinedArena(player, target, partyTeam);
        }
    }

    /** Fullest joinable arena (games about to start come first). */
    private Arena bestRandom() {
        return main.getArenaManager().bestJoinableArena();
    }

    // ================= Display =================

    /** Refreshes all signs (called periodically). */
    public void updateAll() {
        List<Location> gone = new ArrayList<Location>();
        for (JoinSign sign : signs.values()) {
            Location loc = sign.getLocation();
            if (loc.getWorld() == null
                    || !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                continue;
            }
            BlockState state = loc.getBlock().getState();
            if (!(state instanceof Sign)) {
                // The sign is gone (broken support block, WorldEdit...): forget it.
                gone.add(loc);
                continue;
            }
            Sign board = (Sign) state;
            String[] lines = format(sign);
            for (int i = 0; i < 4; i++) {
                board.setLine(i, lines[i]);
            }
            board.update(true);
        }
        if (!gone.isEmpty()) {
            for (Location loc : gone) {
                signs.remove(key(loc));
            }
            save();
        }
    }

    /** The four lines to display on a sign. */
    public String[] format(JoinSign sign) {
        String[] lines = new String[4];
        lines[0] = HEADER;
        if (sign.isRandom()) {
            int players = 0;
            int max = 0;
            int count = 0;
            for (Arena arena : main.getArenaManager().getArenas()) {
                if (arena.isJoinable()) {
                    players += arena.getPlayerCount();
                    max += arena.getMaxPlayers();
                    count++;
                }
            }
            lines[1] = ChatColor.LIGHT_PURPLE + Messages.tr("sign.random");
            lines[2] = count > 0
                    ? ChatColor.GREEN + Messages.tr("sign.join_action")
                    : ChatColor.GRAY + Messages.tr("sign.state.unavailable");
            lines[3] = count(players, max);
        } else {
            Arena arena = main.getArenaManager().getArena(sign.getTarget());
            if (arena == null) {
                lines[1] = ChatColor.WHITE + sign.getTarget();
                lines[2] = ChatColor.GRAY + Messages.tr("sign.state.unavailable");
                lines[3] = "";
            } else {
                lines[1] = ChatColor.WHITE + arena.getName();
                lines[2] = stateLine(arena);
                lines[3] = count(arena.getPlayerCount(), arena.getMaxPlayers());
            }
        }
        return lines;
    }

    private String stateLine(Arena arena) {
        if (!arena.isConfigured()) {
            return ChatColor.GRAY + Messages.tr("sign.state.unavailable");
        }
        if (arena.isRunning() || arena.isState(State.FINISH)) {
            return ChatColor.RED + Messages.tr("sign.state.running");
        }
        if (arena.isFull()) {
            return ChatColor.RED + Messages.tr("sign.state.full");
        }
        if (arena.isState(State.STARTING)) {
            return ChatColor.YELLOW + Messages.tr("sign.state.starting");
        }
        return ChatColor.GREEN + Messages.tr("sign.state.waiting");
    }

    private String count(int current, int max) {
        return ChatColor.YELLOW.toString() + current + ChatColor.GRAY + "/" + ChatColor.YELLOW + max;
    }

    // ================= Persistence =================

    public void load() {
        signs.clear();
        pendingSigns.clear();
        file = new File(main.getDataFolder(), "joinsigns.yml");
        config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = config.getConfigurationSection("signs");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            World world = Bukkit.getWorld(section.getString("world", ""));
            if (world == null) {
                // World not loaded yet: keep the sign for later.
                pendingSigns.add(new java.util.LinkedHashMap<String, Object>(section.getValues(false)));
                continue;
            }
            Location loc = new Location(world,
                    section.getInt("x"), section.getInt("y"), section.getInt("z"));
            signs.put(key(loc), new JoinSign(loc, section.getString("target", "")));
        }
    }

    public void save() {
        if (config == null || file == null) {
            return;
        }
        config.set("signs", null);
        int i = 0;
        for (JoinSign sign : signs.values()) {
            Location loc = sign.getLocation();
            String path = "signs." + i;
            config.set(path + ".world", loc.getWorld().getName());
            config.set(path + ".x", loc.getBlockX());
            config.set(path + ".y", loc.getBlockY());
            config.set(path + ".z", loc.getBlockZ());
            config.set(path + ".target", sign.getTarget());
            i++;
        }
        for (Map<String, Object> raw : pendingSigns) {
            config.set("signs." + i, raw);
            i++;
        }
        try {
            config.save(file);
        } catch (IOException e) {
            main.getLogger().severe("Could not write joinsigns.yml: " + e.getMessage());
        }
    }

    /** Activates the signs waiting for a world that has just loaded. */
    public void resolvePending(World world) {
        for (java.util.Iterator<Map<String, Object>> it = pendingSigns.iterator(); it.hasNext();) {
            Map<String, Object> raw = it.next();
            if (!world.getName().equals(String.valueOf(raw.get("world")))) {
                continue;
            }
            it.remove();
            Location loc = new Location(world, number(raw.get("x")), number(raw.get("y")), number(raw.get("z")));
            Object target = raw.get("target");
            signs.put(key(loc), new JoinSign(loc, target == null ? "" : String.valueOf(target)));
        }
    }

    private static int number(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private String key(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":"
                + loc.getBlockY() + ":" + loc.getBlockZ();
    }
}
