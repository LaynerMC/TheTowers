package com.thetowers.config;

import org.bukkit.inventory.Inventory;

import com.thetowers.api.TTMenuHolder;

/**
 * Marker placed on the inventories of the arena configuration menu.
 *
 * <p>A config menu is recognized by its {@link com.thetowers.api.TTMenuHolder}
 * (stable since 1.8), never by its title. The holder also remembers WHICH menu
 * it is ({@link Type}), on which arena, and for which team if applicable: this
 * is what lets a single listener route every click to the right action.
 */
public class ConfigHolder implements TTMenuHolder {

    /** The different screens of the configuration menu. */
    public enum Type {
        /** List of all the arenas. */
        LIST,
        /** Edition of one specific arena. */
        EDIT,
        /** Settings of a team (spawn, pool, zones). */
        TEAM,
        /** Resource generators of the arena. */
        GENERATORS,
        /** Confirmation of an arena's deletion. */
        DELETE
    }

    private final Type type;
    private final String arena;
    private final String team;
    private Inventory inventory;

    public ConfigHolder(Type type, String arena, String team) {
        this.type = type;
        this.arena = arena;
        this.team = team;
    }

    public Type getType() {
        return type;
    }

    /** Name of the arena being edited, or {@code null} for the list. */
    public String getArena() {
        return arena;
    }

    /** Team concerned (the {@link Type#TEAM} menu), or {@code null}. */
    public String getTeam() {
        return team;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
