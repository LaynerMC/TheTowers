package com.thetowers.coins;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;

import com.thetowers.Main;

/**
 * Coin balances in a MySQL / MariaDB database, in a table named
 * {@code <stats_table>_coins}. Uses the same credentials as the stats storage
 * (section {@code storage.mysql} of config.yml) but its own connection, owned
 * by the single I/O thread of {@link CoinsManager}.
 *
 * <p>Loaded only when {@code storage.type: mysql}.
 */
public class MySqlCoinsStorage implements CoinsStorage {

    private final Main main;
    private final org.mariadb.jdbc.Driver driver = new org.mariadb.jdbc.Driver();

    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;
    private final String table;

    private Connection connection;

    public MySqlCoinsStorage(Main main) {
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
        String statsTable = section.getString("table", "thetowers_stats");
        this.table = safeTable(statsTable) + "_coins";
    }

    @Override
    public void init() throws SQLException {
        Connection conn = connection();
        String sql = "CREATE TABLE IF NOT EXISTS `" + table + "` ("
                + "uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                + "name VARCHAR(16),"
                + "luckperms_prefix VARCHAR(64),"
                + "balance DOUBLE NOT NULL DEFAULT 0)";
        Statement statement = conn.createStatement();
        try {
            statement.executeUpdate(sql);
        } finally {
            statement.close();
        }
        // Migration of older tables.
        runIgnoreError(conn, "ALTER TABLE `" + table
                + "` ADD COLUMN IF NOT EXISTS luckperms_prefix VARCHAR(64)");
        main.getLogger().info("Coins: MySQL storage (table " + table + ").");
    }

    @Override
    public double load(UUID uuid, String name) {
        String sql = "SELECT balance FROM `" + table + "` WHERE uuid=?";
        try {
            Connection conn = connection();
            PreparedStatement statement = conn.prepareStatement(sql);
            try {
                statement.setString(1, uuid.toString());
                ResultSet rs = statement.executeQuery();
                try {
                    if (rs.next()) {
                        return rs.getDouble("balance");
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            main.getLogger().severe("Coins MySQL (load): " + e.getMessage());
        }
        return 0d;
    }

    @Override
    public void createAccount(UUID uuid, String name, double startingBalance) {
        String sql = "INSERT IGNORE INTO `" + table + "` (uuid,name,balance)"
                + " VALUES (?,?,?)";
        try {
            Connection conn = connection();
            PreparedStatement statement = conn.prepareStatement(sql);
            try {
                statement.setString(1, uuid.toString());
                statement.setString(2, name);
                statement.setDouble(3, startingBalance);
                statement.executeUpdate();
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            main.getLogger().severe("Coins MySQL (createAccount): " + e.getMessage());
        }
    }

    @Override
    public void save(UUID uuid, String name, double balance) {
        String sql = "INSERT INTO `" + table + "` (uuid,name,balance)"
                + " VALUES (?,?,?)"
                + " ON DUPLICATE KEY UPDATE name=VALUES(name),balance=VALUES(balance)";
        try {
            Connection conn = connection();
            PreparedStatement statement = conn.prepareStatement(sql);
            try {
                statement.setString(1, uuid.toString());
                statement.setString(2, name);
                statement.setDouble(3, balance);
                statement.executeUpdate();
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            main.getLogger().severe("Coins MySQL (save): " + e.getMessage());
        }
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
            main.getLogger().severe("Coins MySQL (updatePrefix): " + e.getMessage());
        }
    }

    @Override
    public List<CoinEntry> top(int limit) {
        List<CoinEntry> list = new ArrayList<CoinEntry>();
        String sql = "SELECT name,luckperms_prefix,balance FROM `" + table
                + "` ORDER BY balance DESC LIMIT ?";
        try {
            Connection conn = connection();
            PreparedStatement statement = conn.prepareStatement(sql);
            try {
                statement.setInt(1, limit);
                ResultSet rs = statement.executeQuery();
                try {
                    while (rs.next()) {
                        String prefix = rs.getString("luckperms_prefix");
                        list.add(new CoinEntry(rs.getString("name"),
                                prefix == null ? "" : prefix, rs.getDouble("balance")));
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            main.getLogger().severe("Coins MySQL (top): " + e.getMessage());
        }
        return list;
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

    private void runIgnoreError(Connection conn, String sql) {
        try {
            Statement st = conn.createStatement();
            try {
                st.executeUpdate(sql);
            } finally {
                st.close();
            }
        } catch (SQLException ignored) {
            // Column already there, or unsupported syntax.
        }
    }

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
