package com.thetowers.worldmanager.world;

import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.util.concurrent.CompletableFuture;

/**
 * Default {@link WorldLoader}: delegates to {@link WorldCreator#createWorld()}
 * and completes synchronously.
 */
public class BukkitWorldLoader implements WorldLoader {
    @Override
    public CompletableFuture<World> loadWorld(WorldCreator worldCreator) {
        return CompletableFuture.completedFuture(worldCreator.createWorld());
    }
}
