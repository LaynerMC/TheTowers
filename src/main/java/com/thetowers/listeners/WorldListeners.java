package com.thetowers.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

import com.thetowers.Main;

/**
 * A world loaded after TheTowers (Multiverse or another world manager): the
 * positions, signs and lobby waiting for it are handed back.
 */
public class WorldListeners implements Listener {

    private final Main main;

    public WorldListeners(Main main) {
        this.main = main;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        main.onWorldLoaded(event.getWorld());
    }
}
