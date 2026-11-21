package com.thetowers.game;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.inventory.Inventory;

import com.thetowers.api.TTMenuHolder;

/**
 * Marker of the team selection menu. As with the other menus, the inventory
 * is recognized by its holder (never by its title); the holder also remembers
 * which team sits in which slot.
 */
public class TeamHolder implements TTMenuHolder {

    private final Map<Integer, String> teamBySlot = new HashMap<Integer, String>();
    private Inventory inventory;

    void bind(int slot, String team) {
        teamBySlot.put(Integer.valueOf(slot), team);
    }

    /** Internal team id in this slot ("Red", "Blue", ...), or {@code null}. */
    public String teamAt(int slot) {
        return teamBySlot.get(Integer.valueOf(slot));
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
