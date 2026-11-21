package com.thetowers.lobby;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import com.thetowers.Main;
import com.thetowers.compat.Compat;
import com.thetowers.compat.TTMaterial;
import com.thetowers.util.MenuItems;

/**
 * A spectator's hotbar items: a compass that opens the player list (slot 0)
 * and a barrier to leave (slot 8). Their display names are configured in
 * {@code lobby-items.yml} under the {@code spectator-items} section.
 *
 * <p>The spectator is not in Minecraft's "spectator" mode, which hides the
 * hotbar: they are in adventure, flying and invisible (see
 * {@code Compat.makeSpectator}), which leaves their items usable.
 */
public final class SpectatorItems {

    public static final int SLOT_PLAYERS = 0;
    public static final int SLOT_LEAVE = 8;

    private SpectatorItems() {
    }

    /** Places the spectator items in the player's hotbar. */
    public static void give(Main main, Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.setItem(SLOT_PLAYERS, named(main, TTMaterial.COMPASS.item(), "spectator-items.players-name"));
        inventory.setItem(SLOT_LEAVE, named(main, TTMaterial.BARRIER.item(), "spectator-items.leave-name"));
    }

    private static ItemStack named(Main main, ItemStack item, String nameKey) {
        // Bold name + the &7(Right-click) hint appended (see Compat.hotbarName).
        String name = Compat.hotbarName(main.getLobbyItemsConfig().getString(nameKey, nameKey));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            MenuItems.hideVanillaInfo(meta);
            item.setItemMeta(meta);
        }
        return ItemTagger.mark(item);
    }
}
