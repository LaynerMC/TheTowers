package com.thetowers.stats;

import org.bukkit.inventory.Inventory;

import com.thetowers.api.TTMenuHolder;

/**
 * Marker of the {@code /tt stats} inventory menu. The menu is display-only
 * (no clickable action), so the holder carries no extra data: its only job is
 * to be recognized by {@link com.thetowers.listeners.MenuProtectionListener}
 * so the stat icons can never be taken out.
 */
public final class StatsHolder implements TTMenuHolder {

    private Inventory inventory;

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
