package com.thetowers.coins;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import com.thetowers.Main;

/**
 * Local coin balances in {@code coins.yml}, the YAML twin of
 * {@link com.thetowers.stats.YamlStatsStorage}. Structure:
 *
 * <pre>
 * coins:
 *   &lt;uuid&gt;:
 *     name: ...
 *     luckperms_prefix: ""
 *     balance: 0.0
 * </pre>
 *
 * <p>All operations run on the I/O thread of {@link CoinsManager}.
 */
public class YamlCoinsStorage implements CoinsStorage {

    private static final String ROOT = "coins";

    private final Main main;
    private File file;
    private FileConfiguration config;

    public YamlCoinsStorage(Main main) {
        this.main = main;
    }

    @Override
    public void init() {
        file = new File(main.getDataFolder(), "coins.yml");
        if (!file.exists()) {
            file.getParentFile().mkdirs();
        }
        config = YamlConfiguration.loadConfiguration(file);
        main.getLogger().info("Coins: local storage (coins.yml).");
    }

    @Override
    public double load(UUID uuid, String name) {
        return config.getDouble(ROOT + "." + uuid.toString() + ".balance", 0d);
    }

    @Override
    public void createAccount(UUID uuid, String name, double startingBalance) {
        String path = ROOT + "." + uuid.toString();
        if (!config.contains(path)) {
            config.set(path + ".name", name);
            config.set(path + ".balance", startingBalance);
            flush();
        }
    }

    @Override
    public void save(UUID uuid, String name, double balance) {
        String path = ROOT + "." + uuid.toString();
        config.set(path + ".name", name);
        config.set(path + ".balance", balance);
        flush();
    }

    @Override
    public void updatePrefix(UUID uuid, String name, String prefix) {
        String path = ROOT + "." + uuid.toString();
        config.set(path + ".name", name);
        config.set(path + ".luckperms_prefix", prefix == null ? "" : prefix);
        flush();
    }

    @Override
    public List<CoinEntry> top(int limit) {
        List<CoinEntry> all = new ArrayList<CoinEntry>();
        ConfigurationSection root = config.getConfigurationSection(ROOT);
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                all.add(new CoinEntry(section.getString("name", key),
                        section.getString("luckperms_prefix", ""),
                        section.getDouble("balance", 0d)));
            }
        }
        Collections.sort(all, new Comparator<CoinEntry>() {
            @Override
            public int compare(CoinEntry a, CoinEntry b) {
                return Double.compare(b.getBalance(), a.getBalance());
            }
        });
        return all.subList(0, Math.min(limit, all.size()));
    }

    @Override
    public void close() {
        flush();
    }

    private void flush() {
        try {
            config.save(file);
        } catch (IOException e) {
            main.getLogger().severe("Could not write coins.yml: " + e.getMessage());
        }
    }
}
