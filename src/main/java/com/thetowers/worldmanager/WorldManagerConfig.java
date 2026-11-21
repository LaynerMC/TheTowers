package com.thetowers.worldmanager;

import com.thetowers.Main;
import com.thetowers.config.CustomConfig;
import com.thetowers.worldmanager.world.WorldData;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;

/**
 * The single configuration file of the world-manager module:
 * {@code world-manager.yml}. It holds the {@code config} section (module
 * options), the {@code messages} section (translations used by /bmv) and the
 * {@code worlds} section (the loaded worlds with their saved options).
 *
 * <p>Loaded through {@link CustomConfig} (server file wins, bundled jar used as
 * fallback for missing keys), so {@code /bmv reload} picks up edits live.
 */
public final class WorldManagerConfig {

    private final Main main;
    private CustomConfig file;

    public WorldManagerConfig(Main main) {
        this.main = main;
        load();
    }

    /** (Re)loads {@code world-manager.yml} from disk. */
    public void load() {
        file = new CustomConfig(main, "modules/world-manager.yml", true);
        if (file.get().getConfigurationSection("worlds") == null) {
            loadDefaultWorld();
        }
    }

    /** The raw backing configuration (config + messages + worlds). */
    public FileConfiguration get() {
        return file.get();
    }

    // --------------------------- config ---------------------------

    /** True if the world name is in {@code config.world_disable_name}. */
    public boolean isDisableWorldName(String worldName) {
        List<?> disabled = file.get().getList("config.world_disable_name");
        return disabled != null && disabled.contains(worldName);
    }

    // -------------------------- messages --------------------------

    /** A raw message line from {@code messages.<path>} (with {@code &} codes, "" if missing). */
    public String getMessage(String path) {
        String raw = file.getString("messages." + path, "");
        return raw == null ? "" : raw;
    }

    // --------------------------- worlds ---------------------------

    public void saveWorldDataToFile(WorldData worldData) {
        file.get().createSection("worlds." + worldData.getWorldName(), worldData.dumpsWorldDataForSave());
        saveFile();
    }

    public void removeWorldFromFile(String worldName) {
        file.get().set("worlds." + worldName, null);
        saveFile();
    }

    private void saveFile() {
        try {
            file.get().save(new File(main.getDataFolder(), "modules/world-manager.yml"));
        } catch (IOException e) {
            Bukkit.getLogger().severe("Error when trying to save world-manager.yml");
        }
    }

    /** Loads every world listed under {@code worlds} (called on enable, after the managers are ready). */
    public void extractWorldsFromFile() {
        ConfigurationSection worldsSection = file.get().getConfigurationSection("worlds");
        if (worldsSection == null) {
            return;
        }
        for (String worldName : worldsSection.getKeys(false)) {
            if (!isWorldFolderExisting(worldName)) {
                Bukkit.getLogger().info("The world " + worldName
                        + " folder don't exist, but is in world-manager.yml. Remove from world-manager.yml");
                removeWorldFromFile(worldName);
                continue;
            }
            WorldData worldData = new WorldData(file.get().getConfigurationSection("worlds." + worldName));
            worldData.createWorldAsync(Bukkit.getConsoleSender()).thenAccept(this::saveWorldDataToFile);
        }
    }

    public boolean isWorldFolderExisting(String worldName) {
        File worldFolder = new File(Bukkit.getServer().getWorldContainer(), worldName);
        if (worldFolder.exists() && worldFolder.isDirectory()) {
            return true;
        }
        // For minecraft versions where worlds are in the folder 'dimensions'.
        for (World world : Bukkit.getServer().getWorlds()) {
            File parent = world.getWorldFolder().getParentFile();
            if (parent == null) {
                continue;
            }
            File nestedWorldFolder = new File(parent, worldName);
            if (nestedWorldFolder.exists() && nestedWorldFolder.isDirectory()) {
                return true;
            }
        }
        return false;
    }

    private void loadDefaultWorld() {
        World first = Bukkit.getWorlds().get(0);
        saveWorldDataToFile(new WorldData(first.getName(), new HashMap<>()));
    }
}
