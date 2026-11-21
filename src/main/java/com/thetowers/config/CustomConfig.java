package com.thetowers.config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import com.thetowers.Main;
import com.thetowers.compat.Yaml;

/**
 * Loader for the plugin's secondary YAML files.
 *
 * <p>The plugin's tunable files now live under the {@code modules/} subfolder of
 * the data folder (one file per subsystem: {@code modules/leveling.yml},
 * {@code modules/kits.yml}, {@code modules/scoreboard.yml}, ...). They are still
 * bundled at the ROOT of the jar (e.g. {@code kits.yml}), so {@link #load()}
 * extracts the bundled copy (looked up by its basename) into the {@code modules/}
 * path on first run, and {@link #fromJar()} reads the bundled fallback the same
 * way. A server upgrading from an older version that had the file at the data
 * folder root gets it moved into {@code modules/} automatically (migration).
 *
 * <p>Modeled on {@link com.thetowers.lang.Messages}: the server's file wins; a
 * key missing from it falls back to the bundled copy in the jar, so an update
 * that adds keys does not require regenerating the file.
 */
public class CustomConfig {

    private final Main main;
    private final String fileName;
    private final boolean copyDefaults;
    private FileConfiguration config;
    private FileConfiguration bundled;

    /**
     * @param fileName       disk path of the file, e.g.
     *                       {@code "modules/scoreboard.yml"} (or a plain name)
     * @param copyDefaults   true to extract the bundled file (jar root, by
     *                       basename) into the data folder on first run
     */
    public CustomConfig(Main main, String fileName, boolean copyDefaults) {
        this.main = main;
        this.fileName = fileName;
        this.copyDefaults = copyDefaults;
        load();
    }

    public void load() {
        File file = ensureModuleFile(main, fileName, copyDefaults);
        config = Yaml.load(file);
        bundled = fromJar();
        main.getLogger().info("Loaded " + fileName + ".");
    }

    /**
     * Makes sure the file exists on disk at {@code diskPath} (e.g.
     * {@code "modules/kits.yml"}):
     * <ol>
     *   <li>if it already exists, leave it (the admin may have edited it);
     *   <li>else if an older root-level copy exists, MOVE it into
     *       {@code modules/} (migration from a previous version);
     *   <li>else (when {@code copyDefaults}) extract the bundled copy from the
     *       jar root, looked up by basename ({@code "kits.yml"}).
     * </ol>
     * Also used by the loaders that do not go through CustomConfig
     * (KitManager, Menus, RewardManager).
     *
     * @return the file (now guaranteed to exist when {@code copyDefaults} or
     *         when a root copy was migrated; otherwise possibly absent)
     */
    public static File ensureModuleFile(Main main, String diskPath, boolean copyDefaults) {
        File file = new File(main.getDataFolder(), diskPath);
        if (file.exists()) {
            return file;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        // Migration: an older version kept the file at the data-folder root.
        String base = file.getName();
        File rootFile = new File(main.getDataFolder(), base);
        if (rootFile.exists() && !rootFile.getAbsolutePath().equals(file.getAbsolutePath())) {
            if (rootFile.renameTo(file)) {
                main.getLogger().info("Migrated " + base + " -> " + diskPath + ".");
                return file;
            }
        }
        if (copyDefaults) {
            InputStream in = main.getResource(base);
            if (in == null) {
                main.getLogger().warning(base + " is missing from the jar.");
                return file;
            }
            OutputStream out = null;
            try {
                out = new FileOutputStream(file);
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } catch (IOException e) {
                main.getLogger().warning("Could not extract " + base + " to " + diskPath + ": " + e.getMessage());
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // best effort
                }
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
        }
        return file;
    }

    /** The server's editable config (falls back to the bundled one transparently). */
    public FileConfiguration get() {
        return config;
    }

    /**
     * Reads a key from the server file first, then from the bundled copy,
     * with the given default if absent from both.
     */
    public String getString(String path, String def) {
        String value = config.getString(path);
        if (value == null && bundled != null) {
            value = bundled.getString(path);
        }
        return value == null ? def : value;
    }

    public boolean getBoolean(String path, boolean def) {
        if (config.isSet(path)) {
            return config.getBoolean(path, def);
        }
        if (bundled != null && bundled.isSet(path)) {
            return bundled.getBoolean(path, def);
        }
        return def;
    }

    public int getInt(String path, int def) {
        if (config.isSet(path)) {
            return config.getInt(path, def);
        }
        if (bundled != null && bundled.isSet(path)) {
            return bundled.getInt(path, def);
        }
        return def;
    }

    public double getDouble(String path, double def) {
        if (config.isSet(path)) {
            return config.getDouble(path, def);
        }
        if (bundled != null && bundled.isSet(path)) {
            return bundled.getDouble(path, def);
        }
        return def;
    }

    public boolean isSet(String path) {
        return config.isSet(path) || (bundled != null && bundled.isSet(path));
    }

    /** String list from the server file, then the bundled copy; empty if none. */
    public java.util.List<String> getStringList(String path) {
        java.util.List<String> list = config.getStringList(path);
        if ((list == null || list.isEmpty()) && bundled != null) {
            list = bundled.getStringList(path);
        }
        return list == null ? new java.util.ArrayList<String>() : list;
    }

    private FileConfiguration fromJar() {
        // The bundled resource is at the jar root, by basename.
        String base = new File(fileName).getName();
        InputStream in = main.getResource(base);
        if (in == null) {
            return new YamlConfiguration();
        }
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, Charset.forName("UTF-8")));
    }
}
