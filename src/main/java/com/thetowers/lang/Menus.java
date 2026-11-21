package com.thetowers.lang;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import com.thetowers.Main;
import com.thetowers.compat.Yaml;

/**
 * Menu texts (titles, lore lines, item names) stored in
 * {@code menus.yml}, next to messages.yml.
 *
 * <p>Same model as {@link Messages}: the file is bundled in the jar and
 * copied to the data folder on first run so it can be edited; a key
 * missing from the server's file falls back to the bundled copy; missing
 * everywhere, the key itself is returned so a forgotten entry is obvious.
 *
 * <p>Only the menu LORE lines and titles live here. Kit-purchase
 * feedback, gameplay messages and chat feedback stay in messages.yml.
 * The kit selection menu keeps its own contents in kits.yml.
 */
public class Menus {

    private static Menus instance;

    private final Main main;
    private FileConfiguration menus;
    private FileConfiguration bundled;

    public Menus(Main main) {
        this.main = main;
        instance = this;
        load();
    }

    /** Translated menu text, or the key if the system is not ready yet. */
    public static String tr(String key, Object... placeholders) {
        return instance == null ? key : instance.get(key, placeholders);
    }

    /**
     * List of translated lore lines with {@code %name%} variable replacement.
     * If the YAML key is a simple string (with possible \n) it is split per
     * line; if it is a YAML list, each element is a line.
     */
    public static List<String> trLines(String key, Object... placeholders) {
        return instance == null ? Collections.singletonList(key) : instance.getLines(key, placeholders);
    }

    public List<String> getLines(String key, Object... placeholders) {
        List<String> rawLines = new ArrayList<String>();
        for (FileConfiguration source : sources()) {
            if (source == null) {
                continue;
            }
            if (source.isList(key)) {
                List<String> list = source.getStringList(key);
                if (!list.isEmpty()) {
                    rawLines.addAll(list);
                    break;
                }
            } else {
                Object value = source.get(key);
                if (value != null && !(value instanceof ConfigurationSection)) {
                    String[] split = value.toString().split("\\r?\\n");
                    for (String s : split) {
                        rawLines.add(s);
                    }
                    break;
                }
            }
        }
        if (rawLines.isEmpty()) {
            rawLines.add(key);
        }
        List<String> result = new ArrayList<String>(rawLines.size());
        for (String line : rawLines) {
            String processed = line;
            for (int i = 0; i + 1 < placeholders.length; i += 2) {
                processed = processed.replace("%" + placeholders[i] + "%", String.valueOf(placeholders[i + 1]));
            }
            result.add(ChatColor.translateAlternateColorCodes('&', processed));
        }
        return result;
    }

    /** Search order: the server's file (editable), then the bundled copy. */
    private FileConfiguration[] sources() {
        return new FileConfiguration[] { menus, bundled };
    }

    /** (Re)loads menus.yml. Called on startup and on reload. */
    public void load() {
        File file = com.thetowers.config.CustomConfig.ensureModuleFile(main, "modules/menus.yml", true);
        menus = Yaml.load(file);
        bundled = fromJar();
        main.getLogger().info("Menus loaded (menus.yml).");
    }

    /**
     * Translated menu text: {@code &} colors applied and {@code %name%}
     * variables replaced.
     */
    public String get(String key, Object... placeholders) {
        String raw = null;
        for (FileConfiguration source : sources()) {
            Object value = source == null ? null : source.get(key);
            if (value != null && !(value instanceof ConfigurationSection) && !(value instanceof List)) {
                raw = value.toString();
                break;
            }
        }
        if (raw == null) {
            return key;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace("%" + placeholders[i] + "%", String.valueOf(placeholders[i + 1]));
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    /** Direct access to the underlying config (for stats-menu items, etc.). */
    public FileConfiguration config() {
        return menus;
    }

    private FileConfiguration fromJar() {
        InputStream in = main.getResource("menus.yml");
        if (in == null) {
            return new YamlConfiguration();
        }
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, Charset.forName("UTF-8")));
    }
}
