package com.thetowers.game;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.inventory.Inventory;

import com.thetowers.api.TTMenuHolder;

/**
 * Marker of the kit selection menu. As with the config menu, the inventory is
 * recognized by its holder (never by its title); the holder also remembers
 * which kit sits in which slot.
 */
public class KitHolder implements TTMenuHolder {

    private final Map<Integer, String> kitBySlot = new HashMap<Integer, String>();
    private Inventory inventory;

    void bind(int slot, String kitId) {
        kitBySlot.put(Integer.valueOf(slot), kitId);
    }

    /** Kit id in this slot, or {@code null}. */
    public String kitAt(int slot) {
        return kitBySlot.get(Integer.valueOf(slot));
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
