package com.thetowers.listeners;

import org.bukkit.Material;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;

import com.thetowers.Main;
import com.thetowers.lobby.ItemTagger;

/**
 * Makes the lobby hotbar items (arena waiting room, main lobby and spectator)
 * immovable: they cannot be dropped, moved into a container, used in a craft,
 * placed in an item frame or worn/combined in an anvil.
 *
 * <p>An item is recognized by its hidden NBT tag
 * ({@link ItemTagger#isProtected}). Every event that would move or consume a
 * tagged item is cancelled. The player can still LEFT-click to use the item
 * (open a menu, run a command) and right-click interactable blocks.
 */
public class LobbyItemProtectionListener implements Listener {

    private final Main main;

    public LobbyItemProtectionListener(Main main) {
        this.main = main;
    }

    /** No dropping protected items. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (ItemTagger.isProtected(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /** No moving protected items into containers, crafting, anvil, furnace... */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        // The item the player is about to place (cursor), and the one under it.
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        // Shift-click moves the clicked stack: protect it if tagged.
        if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT
                || event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP) {
            if (ItemTagger.isProtected(current)) {
                event.setCancelled(true);
                return;
            }
        }
        if (ItemTagger.isProtected(cursor) || ItemTagger.isProtected(current)) {
            // Allow clicking within the player's OWN inventory only if the item
            // is not being moved into the top (container) inventory.
            InventoryType topType = event.getView().getTopInventory().getType();
            boolean playerOwn = topType == InventoryType.CRAFTING
                    || topType == InventoryType.PLAYER;
            if (!playerOwn) {
                event.setCancelled(true);
                return;
            }
            // In the player's own inventory: block anvil/craft result slots and
            // any move that would consume the protected item, but allow simple
            // selection clicks inside the hotbar.
            if (event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.RESULT
                    || event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.CRAFTING) {
                event.setCancelled(true);
            }
        }
    }

    /** No dragging protected items across slots of a container. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        ItemStack dragged = event.getOldCursor();
        if (ItemTagger.isProtected(dragged) || ItemTagger.isProtected(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    /** No inserting protected items via hoppers/funnels either. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (ItemTagger.isProtected(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /** No crafting with a protected item. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        for (ItemStack matrix : event.getInventory().getMatrix()) {
            if (ItemTagger.isProtected(matrix)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** No placing protected items in item frames. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame)) {
            return;
        }
        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            return;
        }
        if (ItemTagger.isProtected(hand)) {
            event.setCancelled(true);
        }
    }
}
