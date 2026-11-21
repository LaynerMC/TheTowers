package com.thetowers.worldmanager;

import com.thetowers.Main;
import com.thetowers.worldmanager.world.BukkitWorldLoader;
import com.thetowers.worldmanager.world.WorldDataManager;
import com.thetowers.worldmanager.world.WorldLoader;
import com.thetowers.worldmanager.world.WorldOptionManager;

/**
 * The world-manager module: a self-contained world manager merged into
 * TheTowers, mirroring the leveling module. It manages worlds through its own
 * {@code /bmv} commands and the single {@code world-manager.yml} file
 * (config + messages + worlds). The towers GAME never calls into it.
 *
 * <p>Wiring in {@link Main}: instantiated in {@code onEnable} BEFORE
 * {@code ArenaManager} (so saved worlds are loaded before arenas.yml resolves
 * them), and its {@code /bmv} command is registered right after {@code /leveling}.
 */
public final class WorldManager {

    private static WorldManager instance;

    private final Main main;
    private final WorldManagerConfig config;
    private final WorldDataManager worldDataManager;
    private final WorldOptionManager worldOptionManager;
    private final WorldLoader worldLoader = new BukkitWorldLoader();

    public WorldManager(Main main) {
        this.main = main;
        instance = this;

        // Single world-manager.yml: config + messages + worlds.
        config = new WorldManagerConfig(main);
        Message.init(config);

        worldDataManager = new WorldDataManager();
        worldOptionManager = new WorldOptionManager();
        // Constructs the 6 options, which register themselves with the manager.
        worldOptionManager.loadDefaultOption();

        // Load the worlds saved in world-manager.yml (synchronous: BukkitWorldLoader
        // completes on the current thread, so the worlds exist before arenas.yml).
        config.extractWorldsFromFile();

        main.getLogger().info("World manager module enabled (/bmv).");
    }

    /** Reloads {@code world-manager.yml} from disk (does not re-extract worlds). */
    public void reload() {
        config.load();
    }

    /** No persistent resources to close; worlds are persisted by the server. */
    public void shutdown() {
        main.getLogger().info("World manager module disabled.");
    }

    public WorldManagerConfig getConfig() {
        return config;
    }

    // Static accessors used by the internal world/ and options/ classes.

    public static WorldManager get() {
        return instance;
    }

    public static WorldOptionManager getWorldOptionManager() {
        return instance.worldOptionManager;
    }

    public static WorldDataManager getWorldDataManager() {
        return instance.worldDataManager;
    }

    public static WorldLoader getWorldLoader() {
        return instance.worldLoader;
    }
}
