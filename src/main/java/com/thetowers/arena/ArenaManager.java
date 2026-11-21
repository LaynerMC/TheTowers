package com.thetowers.arena;

import com.thetowers.Main;
import com.thetowers.game.GeneratorSpot;
import com.thetowers.region.Cuboid;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Loading, saving and lookup of the arenas ({@code arenas.yml}).
 *
 * <p>Structure of an arena: simple settings at the root, then a
 * {@code teams:} section (spawn, pool, zones of each team) and a
 * {@code generators:} list. See the bundled template file for an example.
 */
public class ArenaManager {

    private static final String ROOT = "arenas";

    private final Main main;
    private final Map<String, Arena> arenas = new LinkedHashMap<String, Arena>();
    private final Set<UUID> stranded = new HashSet<UUID>();
    /**
     * Positions read from arenas.yml whose world was not loaded yet (a
     * world managed by Multiverse or another loader, loaded after
     * TheTowers). They are kept as-is: rewritten at every save, and applied
     * to the arena as soon as their world loads. Without this, the first
     * save (at the latest at server shutdown) wiped them from the file.
     */
    private final List<PendingLocation> pending = new ArrayList<PendingLocation>();

    private File arenasFile;
    private FileConfiguration arenasConfig;

    public ArenaManager(Main main) {
        this.main = main;
        load();
    }

    // ================= Loading and saving =================

    /**
     * One-time migration from the pre-1.3.0 files: arenes.yml (root key
     * "arenes", French team names Rouge/Bleu/Jaune/Vert, "nom" key) becomes
     * arenas.yml (root "arenas", English team names, "name"). The old file is
     * only removed once the conversion has been written successfully.
     */
    private void migrateOldFile() {
        File old = new File(main.getDataFolder(), "arenes.yml");
        if (!old.isFile() || arenasFile.exists()) {
            return;
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(old);
        ConfigurationSection oldRoot = cfg.getConfigurationSection("arenes");
        if (oldRoot != null) {
            for (String key : oldRoot.getKeys(false)) {
                cfg.set(ROOT + "." + key, oldRoot.get(key));
            }
            cfg.set("arenes", null);
        }
        String[] oldTeams = { "Rouge", "Bleu", "Jaune", "Vert" };
        for (int i = 0; i < oldTeams.length; i++) {
            String english = Arena.TEAM_NAMES[i];
            ConfigurationSection arenas = cfg.getConfigurationSection(ROOT);
            if (arenas == null) {
                break;
            }
            for (String arenaKey : arenas.getKeys(false)) {
                String oldPath = ROOT + "." + arenaKey + ".teams." + oldTeams[i];
                ConfigurationSection team = cfg.getConfigurationSection(oldPath);
                if (team == null) {
                    continue;
                }
                String newPath = ROOT + "." + arenaKey + ".teams." + english;
                for (String part : new String[] { "spawn", "pool", "spawnZone", "chestZone" }) {
                    cfg.set(newPath + "." + part, team.get(part));
                }
                cfg.set(oldPath, null);
            }
        }
        ConfigurationSection arenas = cfg.getConfigurationSection(ROOT);
        if (arenas != null) {
            for (String arenaKey : arenas.getKeys(false)) {
                String nom = arenas.getString(arenaKey + ".nom");
                if (nom != null) {
                    cfg.set(ROOT + "." + arenaKey + ".name", nom);
                    cfg.set(ROOT + "." + arenaKey + ".nom", null);
                }
            }
        }
        try {
            arenasFile.getParentFile().mkdirs();
            cfg.save(arenasFile);
            if (!old.delete()) {
                old.deleteOnExit();
            }
            main.getLogger().info("Migrated arenes.yml to arenas.yml (root key \"arenas\","
                    + " English team names, \"name\" key).");
        } catch (IOException e) {
            main.getLogger().severe("Could not migrate arenes.yml to arenas.yml: " + e.getMessage());
        }
    }

    public void load() {
        arenas.clear();
        pending.clear();

        arenasFile = new File(main.getDataFolder(), "arenas.yml");
        migrateOldFile();
        if (!arenasFile.exists()) {
            arenasFile.getParentFile().mkdirs();
            try {
                main.saveResource("arenas.yml", false);
            } catch (IllegalArgumentException e) {
                main.getLogger().warning("arenas.yml not found in the jar, creating an empty file.");
            }
        }
        arenasConfig = YamlConfiguration.loadConfiguration(arenasFile);

        ConfigurationSection root = arenasConfig.getConfigurationSection(ROOT);
        if (root == null) {
            return;
        }

        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            String name = section.getString("name", key);
            Arena arena = new Arena(main, name);
            arena.setMinPlayers(section.getInt("minPlayers", 2));
            arena.setMaxPlayers(section.getInt("maxPlayers", 8));
            arena.setTeamCount(section.getInt("teamCount", 2));
            arena.setPlayersPerTeam(section.getInt("playersPerTeam", 4));
            arena.setPointsToWin(section.getInt("points", main.getDefaultPointsToWin()));
            arena.setDuration(section.getInt("duration", main.getGameDuration()));
            arena.setAllowBows(section.getBoolean("allowBows", true));
            arena.setAllowSpectators(section.getBoolean("allowSpectators", false));
            arena.setLobby(readLocation(section.getConfigurationSection("lobby"),
                    name, PendingLocation.LOBBY, null, null));

            ConfigurationSection teams = section.getConfigurationSection("teams");
            if (teams != null) {
                for (String team : Arena.TEAM_NAMES) {
                    ConfigurationSection t = teams.getConfigurationSection(team);
                    if (t == null) {
                        continue;
                    }
                    Location spawn = readLocation(t.getConfigurationSection("spawn"),
                            name, PendingLocation.SPAWN, team, null);
                    if (spawn != null) {
                        arena.setSpawn(team, spawn);
                    }
                    arena.setPool(team, readCuboid(t.getConfigurationSection("pool")));
                    arena.setSpawnZone(team, readCuboid(t.getConfigurationSection("spawnZone")));
                    arena.setChestZone(team, readCuboid(t.getConfigurationSection("chestZone")));
                }
            }

            ConfigurationSection gens = section.getConfigurationSection("generators");
            if (gens != null) {
                for (String index : gens.getKeys(false)) {
                    ConfigurationSection g = gens.getConfigurationSection(index);
                    if (g == null) {
                        continue;
                    }
                    String type = g.getString("type", "");
                    if (type.isEmpty()) {
                        continue;
                    }
                    Location location = readLocation(g, name, PendingLocation.GENERATOR, null, type);
                    if (location != null) {
                        arena.addGenerator(type, location);
                    }
                }
            }

            arenas.put(name.toLowerCase(), arena);
        }

        main.getLogger().info(arenas.size() + " arena(s) loaded.");
        if (!pending.isEmpty()) {
            Set<String> worlds = new HashSet<String>();
            for (PendingLocation p : pending) {
                worlds.add(p.world);
            }
            main.getLogger().warning("World(s) not loaded yet: " + worlds + ". " + pending.size()
                    + " position(s) kept, resumed as soon as the world loads.");
        }
    }

    /**
     * Applies the pending positions of a world that has just loaded. A
     * position redefined meanwhile by the admin keeps priority.
     */
    public void resolvePending(World world) {
        int resolved = 0;
        for (Iterator<PendingLocation> it = pending.iterator(); it.hasNext();) {
            PendingLocation p = it.next();
            if (!p.world.equals(world.getName())) {
                continue;
            }
            it.remove();
            Arena arena = getArena(p.arena);
            if (arena == null) {
                continue;
            }
            Location location = p.toLocation(world);
            if (PendingLocation.LOBBY.equals(p.kind)) {
                if (arena.getLobby() == null) {
                    arena.setLobby(location);
                }
            } else if (PendingLocation.SPAWN.equals(p.kind)) {
                if (!arena.getSpawns().containsKey(p.team)) {
                    arena.setSpawn(p.team, location);
                }
            } else {
                arena.addGenerator(p.type, location);
            }
            resolved++;
        }
        if (resolved > 0) {
            main.getLogger().info(resolved + " position(s) resumed in world " + world.getName() + ".");
        }
    }

    public void save() {
        if (arenasConfig == null || arenasFile == null) {
            return;
        }
        // Start from a blank section so that deleting an arena, a zone or a
        // generator is actually reflected on disk.
        arenasConfig.set(ROOT, null);

        for (Arena arena : arenas.values()) {
            String path = ROOT + "." + arena.getName();
            arenasConfig.set(path + ".name", arena.getName());
            arenasConfig.set(path + ".minPlayers", arena.getMinPlayers());
            arenasConfig.set(path + ".maxPlayers", arena.getMaxPlayers());
            arenasConfig.set(path + ".teamCount", arena.getTeamCount());
            arenasConfig.set(path + ".playersPerTeam", arena.getPlayersPerTeam());
            arenasConfig.set(path + ".points", arena.getPointsToWin());
            arenasConfig.set(path + ".duration", arena.getDuration());
            arenasConfig.set(path + ".allowBows", arena.isAllowBows());
            arenasConfig.set(path + ".allowSpectators", arena.isAllowSpectators());
            writeLocation(path + ".lobby", arena.getLobby());

            for (String team : Arena.TEAM_NAMES) {
                String teamPath = path + ".teams." + team;
                writeLocation(teamPath + ".spawn", arena.getSpawns().get(team));
                writeCuboid(teamPath + ".pool", arena.getPools().get(team));
                writeCuboid(teamPath + ".spawnZone", arena.getSpawnZones().get(team));
                writeCuboid(teamPath + ".chestZone", arena.getChestZones().get(team));
            }

            int index = 0;
            for (GeneratorSpot spot : arena.getGenerators()) {
                String genPath = path + ".generators." + index;
                arenasConfig.set(genPath + ".type", spot.getType());
                writeLocation(genPath, spot.getLocation());
                index++;
            }

            // Positions whose world is not loaded: copied over as-is.
            for (PendingLocation p : pending) {
                if (!p.arena.equalsIgnoreCase(arena.getName())) {
                    continue;
                }
                if (PendingLocation.LOBBY.equals(p.kind)) {
                    if (arena.getLobby() == null) {
                        p.write(arenasConfig, path + ".lobby");
                    }
                } else if (PendingLocation.SPAWN.equals(p.kind)) {
                    if (!arena.getSpawns().containsKey(p.team)) {
                        p.write(arenasConfig, path + ".teams." + p.team + ".spawn");
                    }
                } else {
                    String genPath = path + ".generators." + index;
                    arenasConfig.set(genPath + ".type", p.type);
                    p.write(arenasConfig, genPath);
                    index++;
                }
            }
        }

        try {
            arenasConfig.save(arenasFile);
        } catch (IOException e) {
            main.getLogger().severe("Could not write arenas.yml: " + e.getMessage());
        }
    }

    // ================= Access =================

    public Arena getArena(String name) {
        return name == null ? null : arenas.get(name.toLowerCase());
    }

    public Collection<Arena> getArenas() {
        return arenas.values();
    }

    public List<String> getArenaNames() {
        List<String> names = new ArrayList<String>();
        for (Arena arena : arenas.values()) {
            names.add(arena.getName());
        }
        return names;
    }

    /** Arena a player is currently in, or {@code null}. */
    public Arena getArenaOf(Player player) {
        for (Arena arena : arenas.values()) {
            if (arena.contains(player)) {
                return arena;
            }
        }
        return null;
    }

    /**
     * Running arena whose play area contains this location, or
     * {@code null}. Used to attach to a game whatever arrives with no
     * player (explosion, fire, falling sand) for the map regeneration.
     */
    public Arena getRunningArenaAt(Location location) {
        for (Arena arena : arenas.values()) {
            if (!arena.isRunning()) {
                continue;
            }
            Cuboid area = arena.getArea();
            if (area != null && area.contains(location)) {
                return arena;
            }
        }
        return null;
    }

    /** Running arena where this disconnected player can reclaim their slot, or {@code null}. */
    public Arena getRejoinableArena(UUID id) {
        for (Arena arena : arenas.values()) {
            if (arena.canRejoin(id)) {
                return arena;
            }
        }
        return null;
    }

    /**
     * Joinable arena (WAITING or STARTING, configured, not full) with the
     * MOST players: randomjoin and the random sign send the player there
     * so they always land in the game about to start, instead of being
     * teleported alone into an empty arena while another waiting lobby
     * fills up. {@code null} if no arena is joinable.
     */
    public Arena bestJoinableArena() {
        Arena best = null;
        for (Arena arena : arenas.values()) {
            if (!arena.isJoinable()) {
                continue;
            }
            if (best == null || arena.getPlayerCount() > best.getPlayerCount()) {
                best = arena;
            }
        }
        return best;
    }

    /**
     * Flags a disconnected player who could not return in time: they will
     * reconnect in the middle of a game map and must be sent back to the
     * lobby. Memory only (lost on restart, no big deal).
     */
    public void markStranded(UUID id) {
        stranded.add(id);
    }

    /** True (once only) if this player must be sent to the lobby at login. */
    public boolean consumeStranded(UUID id) {
        return stranded.remove(id);
    }

    /**
     * Allowed arena name: letters, digits, {@code _} and {@code -}, 16
     * characters maximum, so it fits on a sign and in a scoreboard.
     */
    public static boolean isValidName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,16}");
    }

    public boolean createArena(String name) {
        if (getArena(name) != null) {
            return false;
        }
        // Defaults: 2 teams of 4.
        Arena arena = new Arena(main, name);
        arenas.put(name.toLowerCase(), arena);
        save();
        return true;
    }

    public boolean deleteArena(String name) {
        Arena arena = getArena(name);
        if (arena == null) {
            return false;
        }
        arena.abort();
        arenas.remove(name.toLowerCase());
        for (Iterator<PendingLocation> it = pending.iterator(); it.hasNext();) {
            if (it.next().arena.equalsIgnoreCase(name)) {
                it.remove();
            }
        }
        save();
        return true;
    }

    /** Removes a player from the arena they are in, if any. */
    public void leaveArena(Player player, boolean teleportToLobby) {
        Arena arena = getArenaOf(player);
        if (arena != null) {
            arena.leave(player, teleportToLobby);
        }
    }

    /**
     * Resets every arena without honoring the game in progress: typically
     * server shutdown or {@code /tt reload}. Each running game is silently
     * aborted ({@link Arena#abort}): no announced winner, no reward, no
     * webhook, and the stat counters recorded since the start are rolled
     * back - after a restart, everything is as if nothing had happened.
     */
    public void resetAll() {
        for (Arena arena : arenas.values()) {
            arena.abort();
        }
    }

    // ================= Serialization =================

    /**
     * Reads a location. If its world is not (yet) loaded, it is parked in
     * {@link #pending} and the method returns {@code null}.
     */
    private Location readLocation(ConfigurationSection section, String arena, String kind,
            String team, String type) {
        if (section == null) {
            return null;
        }
        String worldName = section.getString("world");
        if (worldName == null || worldName.isEmpty()) {
            return null;
        }
        PendingLocation raw = new PendingLocation(arena, kind, team, type, worldName,
                section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            pending.add(raw);
            return null;
        }
        return raw.toLocation(world);
    }

    private void writeLocation(String path, Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        arenasConfig.set(path + ".world", location.getWorld().getName());
        arenasConfig.set(path + ".x", location.getX());
        arenasConfig.set(path + ".y", location.getY());
        arenasConfig.set(path + ".z", location.getZ());
        arenasConfig.set(path + ".yaw", location.getYaw());
        arenasConfig.set(path + ".pitch", location.getPitch());
    }

    /**
     * A zone only stores the NAME of its world: unlike a location, it can
     * therefore be read back even while the world is not (yet) loaded; it
     * becomes active again as soon as it is.
     */
    private Cuboid readCuboid(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        String worldName = section.getString("world");
        if (worldName == null || worldName.isEmpty()) {
            return null;
        }
        return new Cuboid(worldName,
                section.getInt("x1"), section.getInt("y1"), section.getInt("z1"),
                section.getInt("x2"), section.getInt("y2"), section.getInt("z2"));
    }

    private void writeCuboid(String path, Cuboid cuboid) {
        if (cuboid == null) {
            return;
        }
        arenasConfig.set(path + ".world", cuboid.getWorldName());
        arenasConfig.set(path + ".x1", cuboid.getMinX());
        arenasConfig.set(path + ".y1", cuboid.getMinY());
        arenasConfig.set(path + ".z1", cuboid.getMinZ());
        arenasConfig.set(path + ".x2", cuboid.getMaxX());
        arenasConfig.set(path + ".y2", cuboid.getMaxY());
        arenasConfig.set(path + ".z2", cuboid.getMaxZ());
    }

    /** Raw position (world name + coordinates) waiting for its world. */
    private static final class PendingLocation {
        static final String LOBBY = "lobby";
        static final String SPAWN = "spawn";
        static final String GENERATOR = "generator";

        final String arena;
        final String kind;
        final String team;
        final String type;
        final String world;
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;

        PendingLocation(String arena, String kind, String team, String type, String world,
                double x, double y, double z, float yaw, float pitch) {
            this.arena = arena;
            this.kind = kind;
            this.team = team;
            this.type = type;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        Location toLocation(World w) {
            return new Location(w, x, y, z, yaw, pitch);
        }

        void write(FileConfiguration config, String path) {
            config.set(path + ".world", world);
            config.set(path + ".x", x);
            config.set(path + ".y", y);
            config.set(path + ".z", z);
            config.set(path + ".yaw", yaw);
            config.set(path + ".pitch", pitch);
        }
    }
}
