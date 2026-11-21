package com.thetowers.game;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import com.thetowers.Main;
import com.thetowers.compat.Yaml;

/**
 * Kit purchases with Vault money, player by player, stored in
 * {@code kit-purchases.yml}.
 *
 * <p>A kit with a {@code price} in kits.yml must be bought ONCE: afterwards
 * the player keeps it forever (refunds are manual, by removing the entry from
 * the file). The kit must still meet its other conditions (stats, permission,
 * placeholders) to be unlocked and to be purchasable.
 *
 * <p>Simple file, written only on the main thread: no synchronization needed.
 */
public class KitPurchases {

    private final Main main;
    /** UUID -> ids of bought kits. Purchase order preserved. */
    private final Map<UUID, List<String>> purchases = new LinkedHashMap<UUID, List<String>>();
    /** UUID -> last known name, for the file's readability. */
    private final Map<UUID, String> names = new LinkedHashMap<UUID, String>();

    private File file;

    public KitPurchases(Main main) {
        this.main = main;
        load();
    }

    /** (Re)reads kit-purchases.yml. Called at startup and by /tt reload. */
    public void load() {
        purchases.clear();
        names.clear();
        file = new File(main.getDataFolder(), "kit-purchases.yml");
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = Yaml.load(file);
        ConfigurationSection root = yml.getConfigurationSection("players");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            try {
                UUID id = UUID.fromString(key);
                purchases.put(id, new ArrayList<String>(section.getStringList("kits")));
                String name = section.getString("name", "");
                if (!name.isEmpty()) {
                    names.put(id, name);
                }
            } catch (IllegalArgumentException ignored) {
                // Key corrupted by hand: ignored.
            }
        }
    }

    /** True if this player already bought this kit. */
    public boolean has(UUID id, String kitId) {
        List<String> bought = purchases.get(id);
        return bought != null && bought.contains(kitId.toLowerCase(java.util.Locale.ROOT));
    }

    /** Records a purchase. */
    public void add(UUID id, String name, String kitId) {
        List<String> bought = purchases.get(id);
        if (bought == null) {
            bought = new ArrayList<String>();
            purchases.put(id, bought);
        }
        if (!bought.contains(kitId.toLowerCase(java.util.Locale.ROOT))) {
            bought.add(kitId.toLowerCase(java.util.Locale.ROOT));
        }
        if (name != null && !name.isEmpty()) {
            names.put(id, name);
        }
        save();
    }

    /** Remembers the current name (called on join). */
    public void rememberName(UUID id, String name) {
        if (purchases.containsKey(id) && name != null && !name.equals(names.get(id))) {
            names.put(id, name);
            save();
        }
    }

    private void save() {
        if (file == null) {
            file = new File(main.getDataFolder(), "kit-purchases.yml");
        }
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, List<String>> entry : purchases.entrySet()) {
            String path = "players." + entry.getKey();
            yml.set(path + ".name", names.get(entry.getKey()));
            yml.set(path + ".kits", entry.getValue());
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            main.getLogger().warning("Could not write kit-purchases.yml: " + e.getMessage());
        }
    }
}
