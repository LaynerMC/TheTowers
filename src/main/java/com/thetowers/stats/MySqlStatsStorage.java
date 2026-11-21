package com.thetowers.stats;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;

import com.thetowers.Main;

/**
 * Stats storage in a MySQL / MariaDB database.
 *
 * <p>The driver is the MariaDB connector bundled in the jar and relocated
 * (see the pom); it is instantiated directly ({@code new Driver()}), which
 * avoids the {@code DriverManager} issues with a plugin's classloader.
 *
 * <p>This class is only loaded if {@code storage.type: mysql}: a server in
 * local mode therefore never touches the driver.
 *
 * <p>All the operations run on the single I/O thread of {@link StatsManager},
 * which alone owns the connection: no pool nor lock to manage.
 *
 * <p>Coins and kit purchases are columns of the main stats table (no separate
 * coins/kit-purchases tables): {@code coins} (BIGINT) and
 * {@code purchased_kits} (comma-separated ids).
 */
public class MySqlStatsStorage implements StatsStorage {

    // All the career columns except uuid and name, in save() parameter order.
    private static final String FIELDS =
            "games,wins,losses,kills,deaths,points,"
            + "blocks_placed,blocks_broken,max_kill_streak,"
            + "damage_dealt,damage_taken,coins,purchased_kits,luckperms_prefix,kit,"
            + "level,exp";

    private final Main main;
    private final org.mariadb.jdbc.Driver driver = new org.mariadb.jdbc.Driver();

    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;
    private final String table;

    private Connection connection;

    public MySqlStatsStorage(Main main) {
        this.main = main;
        ConfigurationSection section = main.getConfig().getConfigurationSection("storage.mysql");
        if (section == null) {
            section = main.getConfig().createSection("storage.mysql");
        }
        this.host = section.getString("host", "localhost");
        this.port = section.getInt("port", 3306);
        this.database = section.getString("database", "thetowers");
        this.user = section.getString("user", "root");
        this.password = section.getString("password", "");
        this.table = safeTable(section.getString("table", "thetowers_stats"));
    }

    @Override
    public void init() throws SQLException {
        Connection conn = connection();
        String sql = "CREATE TABLE IF NOT EXISTS `" + table + "` ("
                + "uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                + "name VARCHAR(16),"
                + "games INT NOT NULL DEFAULT 0,"
                + "wins INT NOT NULL DEFAULT 0,"
                + "losses INT NOT NULL DEFAULT 0,"
                + "kills INT NOT NULL DEFAULT 0,"
                + "deaths INT NOT NULL DEFAULT 0,"
                + "points INT NOT NULL DEFAULT 0,"
                + "blocks_placed INT NOT NULL DEFAULT 0,"
                + "blocks_broken INT NOT NULL DEFAULT 0,"
                + "max_kill_streak INT NOT NULL DEFAULT 0,"
                + "damage_dealt DOUBLE NOT NULL DEFAULT 0,"
                + "damage_taken DOUBLE NOT NULL DEFAULT 0,"
                + "coins BIGINT NOT NULL DEFAULT 0,"
                + "purchased_kits TEXT,"
                + "luckperms_prefix VARCHAR(64),"
                + "kit VARCHAR(32),"
                + "level BIGINT NOT NULL DEFAULT 0,"
                + "exp BIGINT NOT NULL DEFAULT 0)";
        Statement statement = conn.createStatement();
        try {
            statement.executeUpdate(sql);
        } finally {
            statement.close();
        }
        migrateMain(conn);
        // Per-period counters ("week" / "month"), for the
        // %thetowers_top_..._weekly/monthly% placeholders. The name follows the
        // main table's: <table>_periodic.
        String periodicSql = "CREATE TABLE IF NOT EXISTS `" + periodicTable() + "` ("
                + "uuid VARCHAR(36) NOT NULL,"
                + "period VARCHAR(10) NOT NULL,"
                + "pkey VARCHAR(12) NOT NULL,"
                + "name VARCHAR(16),"
                + "luckperms_prefix VARCHAR(64),"
                + "games INT NOT NULL DEFAULT 0,"
                + "wins INT NOT NULL DEFAULT 0,"
                + "losses INT NOT NULL DEFAULT 0,"
                + "kills INT NOT NULL DEFAULT 0,"
                + "deaths INT NOT NULL DEFAULT 0,"
                + "points INT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (uuid, period, pkey))";
        Statement periodicStatement = conn.createStatement();
        try {
            periodicStatement.executeUpdate(periodicSql);
        } finally {
            periodicStatement.close();
        }
        migratePeriodic(conn);
        main.getLogger().info("Stats: MySQL storage (" + host + ":" + port
                + "/" + database + ", table " + table + ").");
    }

    /** Adds the columns that older versions did not have. MariaDB only. */
    private void migrateMain(Connection conn) {
        String[] columns = {
            "ADD COLUMN IF NOT EXISTS blocks_placed INT NOT NULL DEFAULT 0",
            "ADD COLUMN IF NOT EXISTS blocks_broken INT NOT NULL DEFAULT 0",
            "ADD COLUMN IF NOT EXISTS max_kill_streak INT NOT NULL DEFAULT 0",
            "ADD COLUMN IF NOT EXISTS damage_dealt DOUBLE NOT NULL DEFAULT 0",
            "ADD COLUMN IF NOT EXISTS damage_taken DOUBLE NOT NULL DEFAULT 0",
            "ADD COLUMN IF NOT EXISTS coins BIGINT NOT NULL DEFAULT 0",
            "ADD COLUMN IF NOT EXISTS purchased_kits TEXT",
            "ADD COLUMN IF NOT EXISTS luckperms_prefix VARCHAR(64)",
            "ADD COLUMN IF NOT EXISTS level BIGINT NOT NULL DEFAULT 0",
            "ADD COLUMN IF NOT EXISTS exp BIGINT NOT NULL DEFAULT 0"
        };
        for (String col : columns) {
            runIgnoreError(conn, "ALTER TABLE `" + table + "` " + col);
        }
    }

    /** Adds the prefix column to the periodic table if it is missing. */
    private void migratePeriodic(Connection conn) {
        runIgnoreError(conn, "ALTER TABLE `" + periodicTable()
                + "` ADD COLUMN IF NOT EXISTS luckperms_prefix VARCHAR(64)");
    }

    /** Runs a statement and swallows the error (migration: already exists). */
    private void runIgnoreError(Connection conn, String sql) {
        try {
            Statement st = conn.createStatement();
            try {
                st.executeUpdate(sql);
            } finally {
                st.close();
            }
        } catch (SQLException ignored) {
            // Column already there, or unsupported syntax: ignored.
        }
    }

    @Override
    public void addPeriodic(String periodType, String periodKey, UUID uuid, String name,
            String prefix, Map<String, Integer> deltas) {
        if (deltas == null || deltas.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("INSERT INTO `" + periodicTable() + "`"
                + " (uuid,period,pkey,name,luckperms_prefix,games,wins,losses,kills,deaths,points)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE name=VALUES(name),luckperms_prefix=VALUES(luckperms_prefix)");
        for (String column : StatsStorage.COLUMNS) {
            sql.append(",").append(column).append("=").append(column).append("+VALUES(")
                    .append(column).append(")");
        }
        try {
            Connection conn = connection();
            PreparedStatement statement = conn.prepareStatement(sql.toString());
            try {
                statement.setString(1, uuid.toString());
                statement.setString(2, periodType);
                statement.setString(3, periodKey);
                statement.setString(4, name);
                statement.setString(5, prefix == null ? "" : prefix);
                int index = 6;
                for (String column : StatsStorage.COLUMNS) {
                    Integer delta = deltas.get(column);
                    statement.setInt(index++, delta == null ? 0 : delta.intValue());
                }
                statement.executeUpdate();
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            main.getLogger().severe("Stats MySQL (addPeriodic): " + e.getMessage());
        }
    }

    @Override
    public List<PlayerStats> topPeriodic(String periodType, String periodKey,
            String column, int limit) {
        List<PlayerStats> list = new ArrayList<PlayerStats>();
        String sql = "SELECT uuid,name,luckperms_prefix,games,wins,losses,kills,deaths,points FROM `"
                + periodicTable() + "` WHERE period=? AND pkey=? ORDER BY `" + column
                + "` DESC LIMIT ?";
        try {
            Connection conn = connection();
            PreparedStatement statement = conn.prepareStatement(sql);
            try {
                statement.setString(1, periodType);
                statement.setString(2, periodKey);
                statement.setInt(3, limit);
                ResultSet rs = statement.executeQuery();
                try {
                    while (rs.next()) {
                        String prefix = rs.getString("luckperms_prefix");
                        list.add(new PlayerStats(UUID.fromString(rs.getString("uuid")),
                                rs.getString("name"), rs.getInt("games"), rs.getInt("wins"),
                                rs.getInt("losses"), rs.getInt("kills"), rs.getInt("deaths"),
                                rs.getInt("points"), 0, 0, 0, 0d, 0d, 0L, null, null,
                                prefix == null ? "" : prefix));
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            main.getLogger().severe("Stats MySQL (topPeriodic): " + e.getMessage());
        }
        return list;
    }

    /** Name of the periods table, derived from the main table's. */
    private String periodicTable() {
        return table + "_periodic";
    }

    @Override
    public PlayerStats load(UUID uuid, String name) {
        String sql = "SELECT " + FIELDS + " FROM `" + table + "` WHERE uuid=?";
        try {
            Connection conn = connection();
            PreparedStatement statement = conn.prepareStatement(sql);
            try {
                statement.setString(1, uuid.toString());
                ResultSet rs = statement.executeQuery();
                try {
                    if (rs.next()) {
                        return fromRow(rs, uuid, name);
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            main.getLogger().severe("Stats MySQL (load): " + e.getMessage());
        }
        return new PlayerStats(uuid, name);
    }

    @Override
    public PlayerStats loadByName(String name) {
        String sql = "SELECT uuid," + FIELDS + " FROM `" + table + "` WHERE name=? LIMIT 1";
        try {
            Connection conn = connection();
            PreparedStatement statement = conn.prepareStatement(sql);
            try {
                statement.setString(1, name);
                ResultSet rs = statement.executeQuery();
                try {
                    if (rs.next()) {
                        return fromRow(rs, UUID.fromString(rs.getString("uuid")), name);
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            main.getLogger().severe("Stats MySQL (loadByName): " + e.getMessage());
        }
        return null;
    }

    @Override
    public void save(PlayerStats stats) {
        String sql = "INSERT INTO `" + table + "` (uuid,name," + FIELDS + ")"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE name=VALUES(name),games=VALUES(games),"
                + "wins=VALUES(wins),losses=VALUES(losses),kills=VALUES(kills),deaths=VALUES(deaths),"
                + "points=VALUES(points),blocks_placed=VALUES(blocks_placed),"
                + "blocks_broken=VALUES(blocks_broken),max_kill_streak=VALUES(max_kill_streak),"
                + "damage_dealt=VALUES(damage_dealt),damage_taken=VALUES(damage_taken),"
                + "coins=VALUES(coins),purchased_kits=VALUES(purchased_kits),"
                + "luckperms_prefix=VALUES(luckperms_prefix),kit=VALUES(kit),"
                + "level=VALUES(level),exp=VALUES(exp)";
        try {
            Connection conn = connection();
            PreparedStatement statement = conn.prepareStatement(sql);
            try {
                statement.setString(1, stats.getUuid().toString());
                statement.setString(2, stats.getName());
                statement.setInt(3, stats.getGames());
                statement.setInt(4, stats.getWins());
                statement.setInt(5, stats.getLosses());
                statement.setInt(6, stats.getKills());
                statement.setInt(7, stats.getDeaths());
                statement.setInt(8, stats.getPoints());
                statement.setInt(9, stats.getBlocksPlaced());
                statement.setInt(10, stats.getBlocksBroken());
                statement.setInt(11, stats.getMaxKillStreak());
                statement.setDouble(12, stats.getDamageDealt());
                statement.setDouble(13, stats.getDamageTaken());
                statement.setLong(14, stats.getCoins());
                statement.setString(15, encodeKits(stats.getPurchasedKits()));
                statement.setString(16, stats.getLuckpermsPrefix());
                statement.setString(17, stats.getKit());
                statement.setLong(18, stats.getLevel());
                statement.setLong(19, stats.getExp());
                statement.executeUpdate();
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            main.getLogger().severe("Stats MySQL (save): " + e.getMessage());
        }
    }

    @Override
    public List<PlayerStats> top(String column, int limit) {
        List<PlayerStats> list = new ArrayList<PlayerStats>();
        String sql = "SELECT uuid,name," + FIELDS + " FROM `" + table
                + "` ORDER BY `" + column + "` DESC LIMIT ?";
        try {
            Connection conn = connection();
            PreparedStatement statement = conn.prepareStatement(sql);
            try {
                statement.setInt(1, limit);
                ResultSet rs = statement.executeQuery();
                try {
                    while (rs.next()) {
                        list.add(fromRow(rs, UUID.fromString(rs.getString("uuid")), rs.getString("name")));
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            main.getLogger().severe("Stats MySQL (top): " + e.getMessage());
        }
        return list;
    }

    @Override
    public List<PlayerStats> topCoins(int limit) {
        List<PlayerStats> list = new ArrayList<PlayerStats>();
        String sql = "SELECT uuid,name," + FIELDS + " FROM `" + table
                + "` ORDER BY coins DESC LIMIT ?";
        try {
            Connection conn = connection();
            PreparedStatement statement = conn.prepareStatement(sql);
            try {
                statement.setInt(1, limit);
                ResultSet rs = statement.executeQuery();
                try {
                    while (rs.next()) {
                        list.add(fromRow(rs, UUID.fromString(rs.getString("uuid")), rs.getString("name")));
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            main.getLogger().severe("Stats MySQL (topCoins): " + e.getMessage());
        }
        return list;
    }

    @Override
    public void updatePrefix(UUID uuid, String name, String prefix) {
        String sql = "INSERT INTO `" + table + "` (uuid,name,luckperms_prefix)"
                + " VALUES (?,?,?)"
                + " ON DUPLICATE KEY UPDATE name=VALUES(name),luckperms_prefix=VALUES(luckperms_prefix)";
        try {
            Connection conn = connection();
            PreparedStatement statement = conn.prepareStatement(sql);
            try {
                statement.setString(1, uuid.toString());
                statement.setString(2, name);
                statement.setString(3, prefix == null ? "" : prefix);
                statement.executeUpdate();
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            main.getLogger().severe("Stats MySQL (updatePrefix): " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                // Closing: nothing more to do.
            }
            connection = null;
        }
    }

    // ================= Internal =================

    private static PlayerStats fromRow(ResultSet rs, UUID uuid, String name) throws SQLException {
        String prefix = rs.getString("luckperms_prefix");
        PlayerStats stats = new PlayerStats(uuid, name,
                rs.getInt("games"), rs.getInt("wins"), rs.getInt("losses"),
                rs.getInt("kills"), rs.getInt("deaths"), rs.getInt("points"),
                rs.getInt("blocks_placed"), rs.getInt("blocks_broken"),
                rs.getInt("max_kill_streak"), rs.getDouble("damage_dealt"),
                rs.getDouble("damage_taken"), rs.getLong("coins"),
                decodeKits(rs.getString("purchased_kits")), rs.getString("kit"),
                prefix == null ? "" : prefix);
        // Leveling columns are added by migrateMain(); default to 0 if absent.
        try {
            stats.setLevel(rs.getLong("level"));
            stats.setExp(rs.getLong("exp"));
        } catch (SQLException ignored) {
            // Column missing on a not-yet-migrated row: leave at 0.
        }
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

    /** Opens the connection if needed (first time, or after a drop). */
    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            Properties props = new Properties();
            props.setProperty("user", user);
            props.setProperty("password", password);
            String url = "jdbc:mariadb://" + host + ":" + port + "/" + database;
            connection = driver.connect(url, props);
            if (connection == null) {
                throw new SQLException("The MariaDB driver rejected the URL: " + url);
            }
        }
        return connection;
    }

    private static String safeTable(String raw) {
        return raw != null && raw.matches("[A-Za-z0-9_]+") ? raw : "thetowers_stats";
    }
}
