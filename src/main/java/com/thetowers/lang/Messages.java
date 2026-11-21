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
 * Player messages, all in English, stored in a single {@code messages.yml} at
 * the root of the plugin data folder.
 *
 * <p>The file is bundled in the jar and copied to the data folder on first run
 * so it can be edited. Each key gives a text with {@code &} color codes and
 * {@code %name%} variables.
 *
 * <p>Fallback: a key missing from the server's file is looked up in the bundled
 * copy (keys added by an update). Missing everywhere, the key itself is returned
 * so a forgotten entry is obvious.
 */
public class Messages {

    /**
     * Current instance, for the static access {@link #tr}. Messages are
     * requested from everywhere (lobby items, tasks, enums); a static entry
     * point avoids threading Main through every one of them.
     */
    private static Messages instance;

    private final Main main;
    private FileConfiguration messages;
    /** Bundled copy in the jar, used when a key is missing from the server file. */
    private FileConfiguration bundled;

    public Messages(Main main) {
        this.main = main;
        instance = this;
        load();
    }

    /** Translated message, or the key if the system is not ready yet. */
    public static String tr(String key, Object... placeholders) {
        return instance == null ? key : instance.get(key, placeholders);
    }

    /**
     * List of translated lines with {@code %name%} variable replacement. If the
     * YAML key is a simple string (with possible \n) it is split per line; if it
     * is a YAML list, each element is a line.
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

    /**
     * List of translated lines (colors applied), e.g. the default scoreboard
     * layout. Same fallback as {@link #tr}; missing everywhere -> empty list.
     */
    public static List<String> trList(String key) {
        List<String> result = new ArrayList<String>();
        if (instance == null) {
            return result;
        }
        for (FileConfiguration source : instance.sources()) {
            if (source != null && source.isList(key) && !source.getStringList(key).isEmpty()) {
                for (String line : source.getStringList(key)) {
                    result.add(ChatColor.translateAlternateColorCodes('&', line));
                }
                return result;
            }
        }
        return result;
    }

    /**
     * Search order for a key: the server's file (editable), then the bundled
     * copy in the jar (keys added by an update).
     */
    private FileConfiguration[] sources() {
        return new FileConfiguration[] { messages, bundled };
    }

    /** (Re)loads messages.yml. Called on startup and on reload. */
    public void load() {
        File file = new File(main.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            try {
                main.saveResource("messages.yml", false);
            } catch (IllegalArgumentException e) {
                main.getLogger().warning("messages.yml is missing from the jar.");
            }
        }
        // Forced UTF-8 read: see compat/Yaml (accents broken on Windows + Java 8).
        messages = Yaml.load(file);
        bundled = fromJar();
        main.getLogger().info("Messages loaded (messages.yml).");
    }

    /**
     * Translated message: {@code &} colors applied and {@code %name%} variables
     * replaced. Variables are passed in pairs, name then value:
     * {@code get("arena.joined", "player", name, "count", 3)}.
     */
    public String get(String key, Object... placeholders) {
        String raw = raw(key);
        if (raw == null) {
            return key;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace("%" + placeholders[i] + "%", String.valueOf(placeholders[i + 1]));
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    /** Raw (untranslated, unreplaced) value of a key, or {@code null}. */
    public String raw(String key) {
        for (FileConfiguration source : sources()) {
            Object value = source == null ? null : source.get(key);
            // Any simple value counts (a number written without quotes by the
            // admin too, as with getString); not a section nor a list.
            if (value != null && !(value instanceof ConfigurationSection) && !(value instanceof List)) {
                return value.toString();
            }
        }
        return null;
    }

    /** Boolean value of a key, with a default if absent. */
    public boolean getBoolean(String key, boolean def) {
        for (FileConfiguration source : sources()) {
            if (source != null && source.isSet(key)) {
                return source.getBoolean(key, def);
            }
        }
        return def;
    }

    /** Raw (untranslated, unreplaced) string list of a key, or empty. */
    public List<String> rawStringList(String key) {
        for (FileConfiguration source : sources()) {
            if (source == null) {
                continue;
            }
            if (source.isList(key)) {
                List<String> list = source.getStringList(key);
                if (!list.isEmpty()) {
                    return new ArrayList<String>(list);
                }
            } else {
                Object value = source.get(key);
                if (value != null && !(value instanceof ConfigurationSection)) {
                    String[] split = value.toString().split("\\r?\\n");
                    List<String> list = new ArrayList<String>(split.length);
                    for (String s : split) {
                        list.add(s);
                    }
                    return list;
                }
            }
        }
        return new ArrayList<String>();
    }

    // ================= Internal =================

    private FileConfiguration fromJar() {
        InputStream in = main.getResource("messages.yml");
        if (in == null) {
            return new YamlConfiguration();
        }
        // UTF-8 read so the accents in the file arrive intact.
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, Charset.forName("UTF-8")));
    }
}
