package com.thetowers.stats;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import com.thetowers.Main;

/**
 * Local stats storage in {@code stats.yml}, modeled on arenas.yml: zero
 * dependencies, works everywhere. Structure:
 *
 * <pre>
 * stats:
 *   &lt;uuid&gt;:
 *     name: ...
 *     games: 0
 *     wins: 0
 *     ...
 *     blocks_placed: 0
 *     blocks_broken: 0
 *     max_kill_streak: 0
 *     damage_dealt: 0.0
 *     damage_taken: 0.0
 *     luckperms_prefix: ""
 *     kit: ...
 * </pre>
 *
 * <p>All the operations run on the I/O thread of {@link StatsManager}: the
 * in-memory file is therefore touched only by that single thread.
 */
public class YamlStatsStorage implements StatsStorage {

    private static final String ROOT = "stats";
    /** Root of the per-period counters: periodic.<type>.<key>.<uuid>. */
    private static final String ROOT_PERIODIC = "periodic";

    private final Main main;
    private File file;
    private FileConfiguration config;

    public YamlStatsStorage(Main main) {
        this.main = main;
    }

    @Override
    public void init() {
        file = new File(main.getDataFolder(), "stats.yml");
        if (!file.exists()) {
            file.getParentFile().mkdirs();
        }
        config = YamlConfiguration.loadConfiguration(file);
        main.getLogger().info("Stats: local storage (stats.yml).");
    }

    @Override
    public PlayerStats load(UUID uuid, String name) {
        PlayerStats stats = read(uuid.toString());
        if (stats == null) {
            return new PlayerStats(uuid, name);
        }
        // The name may have changed since the last game.
        stats.setName(name);
        return stats;
    }

    @Override
    public PlayerStats loadByName(String name) {
        ConfigurationSection root = config.getConfigurationSection(ROOT);
        if (root == null) {
            return null;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section != null && name.equalsIgnoreCase(section.getString("name"))) {
                return read(key);
            }
        }
        return null;
    }

    @Override
    public void save(PlayerStats stats) {
        String path = ROOT + "." + stats.getUuid().toString();
        config.set(path + ".name", stats.getName());
        config.set(path + ".games", stats.getGames());
        config.set(path + ".wins", stats.getWins());
        config.set(path + ".losses", stats.getLosses());
        config.set(path + ".kills", stats.getKills());
        config.set(path + ".deaths", stats.getDeaths());
        config.set(path + ".points", stats.getPoints());
        config.set(path + ".blocks_placed", stats.getBlocksPlaced());
        config.set(path + ".blocks_broken", stats.getBlocksBroken());
        config.set(path + ".max_kill_streak", stats.getMaxKillStreak());
        config.set(path + ".damage_dealt", stats.getDamageDealt());
        config.set(path + ".damage_taken", stats.getDamageTaken());
        config.set(path + ".coins", stats.getCoins());
        config.set(path + ".purchased_kits", encodeKits(stats.getPurchasedKits()));
        config.set(path + ".luckperms_prefix", stats.getLuckpermsPrefix());
        config.set(path + ".kit", stats.getKit());
        config.set(path + ".level", stats.getLevel());
        config.set(path + ".exp", stats.getExp());
        flush();
    }

    @Override
    public void addPeriodic(String periodType, String periodKey, UUID uuid, String name,
            String prefix, Map<String, Integer> deltas) {
        if (deltas == null || deltas.isEmpty()) {
            return;
        }
        String path = ROOT_PERIODIC + "." + periodType + "." + periodKey + "." + uuid.toString();
        config.set(path + ".name", name);
        config.set(path + ".luckperms_prefix", prefix == null ? "" : prefix);
        for (Map.Entry<String, Integer> delta : deltas.entrySet()) {
            if (!StatsStorage.isColumn(delta.getKey()) || delta.getValue().intValue() == 0) {
                continue;
            }
            int current = config.getInt(path + "." + delta.getKey(), 0);
            config.set(path + "." + delta.getKey(), Math.max(0, current + delta.getValue().intValue()));
        }
        flush();
    }

    @Override
    public List<PlayerStats> topPeriodic(String periodType, String periodKey,
            final String column, int limit) {
        List<PlayerStats> all = new ArrayList<PlayerStats>();
        ConfigurationSection period = config.getConfigurationSection(
                ROOT_PERIODIC + "." + periodType + "." + periodKey);
        if (period != null) {
            for (String key : period.getKeys(false)) {
                PlayerStats stats = readPeriodic(period, key);
                if (stats != null) {
                    all.add(stats);
                }
            }
        }
        Collections.sort(all, new Comparator<PlayerStats>() {
            @Override
            public int compare(PlayerStats a, PlayerStats b) {
                return Integer.compare(b.value(column), a.value(column));
            }
        });
        return all.subList(0, Math.min(limit, all.size()));
    }

    /** Re-reads a period entry (same columns as the career ones, without the kit). */
    private PlayerStats readPeriodic(ConfigurationSection period, String uuidKey) {
        ConfigurationSection section = period.getConfigurationSection(uuidKey);
        if (section == null) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidKey);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new PlayerStats(uuid, section.getString("name", uuidKey),
                section.getInt("games"), section.getInt("wins"), section.getInt("losses"),
                section.getInt("kills"), section.getInt("deaths"), section.getInt("points"),
                0, 0, 0, 0d, 0d, 0L, null, null, section.getString("luckperms_prefix", ""));
    }

    @Override
    public List<PlayerStats> top(final String column, int limit) {
        List<PlayerStats> all = new ArrayList<PlayerStats>();
        ConfigurationSection root = config.getConfigurationSection(ROOT);
        if (root != null) {
            for (String key : root.getKeys(false)) {
                PlayerStats stats = read(key);
                if (stats != null) {
                    all.add(stats);
                }
            }
        }
        // Damage columns are doubles: compared differently.
        if (PlayerStats.isDoubleColumn(column)) {
            final String col = column;
            Collections.sort(all, new Comparator<PlayerStats>() {
                @Override
                public int compare(PlayerStats a, PlayerStats b) {
                    return Double.compare(b.doubleValue(col), a.doubleValue(col));
                }
            });
        } else {
            Collections.sort(all, new Comparator<PlayerStats>() {
                @Override
                public int compare(PlayerStats a, PlayerStats b) {
                    return Integer.compare(b.value(column), a.value(column));
                }
            });
        }
        return all.subList(0, Math.min(limit, all.size()));
    }

    @Override
    public List<PlayerStats> topCoins(int limit) {
        List<PlayerStats> all = new ArrayList<PlayerStats>();
        ConfigurationSection root = config.getConfigurationSection(ROOT);
        if (root != null) {
            for (String key : root.getKeys(false)) {
                PlayerStats stats = read(key);
                if (stats != null) {
                    all.add(stats);
                }
            }
        }
        Collections.sort(all, new Comparator<PlayerStats>() {
            @Override
            public int compare(PlayerStats a, PlayerStats b) {
                return Long.compare(b.getCoins(), a.getCoins());
            }
        });
        return all.subList(0, Math.min(limit, all.size()));
    }

    @Override
    public void updatePrefix(UUID uuid, String name, String prefix) {
        String path = ROOT + "." + uuid.toString();
        // Keep the name in sync too (cheap), do not touch the stats.
        config.set(path + ".name", name);
        config.set(path + ".luckperms_prefix", prefix == null ? "" : prefix);
        flush();
    }

    @Override
    public void close() {
        flush();
    }

    // ================= Internal =================

    private PlayerStats read(String uuidKey) {
        ConfigurationSection section = config.getConfigurationSection(ROOT + "." + uuidKey);
        if (section == null) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidKey);
        } catch (IllegalArgumentException e) {
            return null;
        }
        PlayerStats stats = new PlayerStats(uuid, section.getString("name", uuidKey),
                section.getInt("games"), section.getInt("wins"), section.getInt("losses"),
                section.getInt("kills"), section.getInt("deaths"), section.getInt("points"),
                section.getInt("blocks_placed"), section.getInt("blocks_broken"),
                section.getInt("max_kill_streak"), section.getDouble("damage_dealt"),
                section.getDouble("damage_taken"), section.getLong("coins", 0L),
                decodeKits(section.getString("purchased_kits", "")),
                section.getString("kit", null),
                section.getString("luckperms_prefix", ""));
        // Leveling system fields (default to the "uninitialized" marker when absent).
        stats.setLevel(section.getLong("level", 0L));
        stats.setExp(section.getLong("exp", 0L));
        return stats;
    }

    /** Comma-separated kit ids, e.g. "1,3,5"; empty string for none. */
    private static String encodeKits(java.util.Set<Integer> kits) {
        if (kits == null || kits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Integer id : kits) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id.intValue());
        }
        return sb.toString();
    }

    private static java.util.List<Integer> decodeKits(String raw) {
        java.util.List<Integer> list = new ArrayList<Integer>();
        if (raw == null || raw.isEmpty()) {
            return list;
        }
        for (String part : raw.split(",")) {
            try {
                list.add(Integer.valueOf(Integer.parseInt(part.trim())));
            } catch (NumberFormatException ignored) {
                // Corrupted entry: ignored.
            }
        }
        return list;
    }

    private void flush() {
        try {
            config.save(file);
        } catch (IOException e) {
            main.getLogger().severe("Could not write stats.yml: " + e.getMessage());
        }
    }
}
