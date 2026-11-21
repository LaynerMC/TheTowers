package com.thetowers.game;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.inventory.Inventory;

import com.thetowers.api.TTMenuHolder;

/**
 * Marker of a spectator's player menu. As with the other menus, the inventory
 * is recognized by its holder (never by its title); the holder also remembers
 * which player sits in which slot.
 */
public class SpectatorHolder implements TTMenuHolder {

    private final Map<Integer, UUID> playerBySlot = new HashMap<Integer, UUID>();
    private Inventory inventory;

    void bind(int slot, UUID playerId) {
        playerBySlot.put(Integer.valueOf(slot), playerId);
    }

    /** Player in this slot, or {@code null}. */
    public UUID playerAt(int slot) {
        return playerBySlot.get(Integer.valueOf(slot));
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
