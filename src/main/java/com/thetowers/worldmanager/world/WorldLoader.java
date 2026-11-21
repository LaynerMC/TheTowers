package com.thetowers.worldmanager.world;

import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.util.concurrent.CompletableFuture;

/**
 * Loads or creates worlds. The default implementation relies on
 * {@link WorldCreator#createWorld()}; a different implementation could be
 * plugged in for server software that does not support it. The future must be
 * completed from a thread on which the loaded world's options can safely be
 * accessed and modified.
 */
@FunctionalInterface
public interface WorldLoader {
    CompletableFuture<World> loadWorld(WorldCreator worldCreator);
}
