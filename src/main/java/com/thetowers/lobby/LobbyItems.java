package com.thetowers.lobby;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.compat.Compat;
import com.thetowers.compat.Enchants;
import com.thetowers.compat.TTMaterial;
import com.thetowers.config.CustomConfig;
import com.thetowers.util.MenuItems;

/**
 * Hotbar items given to players in an arena's waiting lobby: the kit selector,
 * the team selector (an enchanted book) and a leave barrier.
 *
 * <p>Everything about these items (material, slot, display name, lore) is
 * configured in {@code lobby-items.yml} under the {@code arena-lobby} section.
 * The leave countdown length stays in {@code config.yml}
 * ({@code lobby-items.leave-countdown}). If a configured material does not
 * exist on this version, we fall back to the default value and log a warning.
 * Slots are clamped to 0-8.
 *
 * <p>These items disappear when the game starts, the inventory being wiped at
 * that point. The MAIN server-lobby items (custom command items) are handled
 * by {@link MainLobbyItems}.
 */
public final class LobbyItems {

    // Default materials, used if the arena-lobby section is missing or if the
    // configured material does not exist on this version.
    private static final String DEFAULT_KITS = "EMERALD";
    private static final String DEFAULT_LEAVE = "BARRIER";

    // Default slots, used if arena-lobby.<key>.slot is missing or invalid.
    public static final int DEFAULT_SLOT_KITS = 0;
    public static final int DEFAULT_SLOT_TEAM = 4;
    public static final int DEFAULT_SLOT_LEAVE = 8;

    private LobbyItems() {
    }

    /** Places the lobby items in the player's hotbar, at their configured slots. */
    public static void give(Main main, Arena arena, Player player) {
        PlayerInventory inventory = player.getInventory();
        setSlot(inventory, slot(main, "kits", DEFAULT_SLOT_KITS), kitsItem(main));
        setSlot(inventory, slot(main, "team", DEFAULT_SLOT_TEAM), teamItem(main));
        setSlot(inventory, slot(main, "leave", DEFAULT_SLOT_LEAVE), leaveItem(main));
    }

    private static void setSlot(PlayerInventory inventory, int slot, ItemStack item) {
        if (item == null) {
            return;
        }
        inventory.setItem(slot, item);
    }

    /** Configured slot for the given item key, clamped to 0-8. */
    public static int slot(Main main, String key, int fallback) {
        int s = main.getLobbyItemsConfig().getInt("arena-lobby." + key + ".slot", fallback);
        if (s < 0 || s > 8) {
            return fallback;
        }
        return s;
    }

    public static int kitsSlot(Main main) {
        return slot(main, "kits", DEFAULT_SLOT_KITS);
    }

    public static int teamSlot(Main main) {
        return slot(main, "team", DEFAULT_SLOT_TEAM);
    }

    public static int leaveSlot(Main main) {
        return slot(main, "leave", DEFAULT_SLOT_LEAVE);
    }

    /** Configured material for the "kits" item (with default fallback). */
    public static Material kitsMaterial(Main main) {
        return resolveMaterial(main, "kits", DEFAULT_KITS);
    }

    /** Configured material for the "leave" item (with default fallback). */
    public static Material leaveMaterial(Main main) {
        return resolveMaterial(main, "leave", DEFAULT_LEAVE);
    }

    private static Material resolveMaterial(Main main, String key, String fallback) {
        ItemStack item = itemFromConfig(main, key, fallback);
        return item == null ? null : item.getType();
    }

    /**
     * Reads the material from {@code arena-lobby.<key>.material} in
     * lobby-items.yml, falling back to {@code fallback} if the key is missing
     * or the name does not match any material on this version.
     */
    private static ItemStack itemFromConfig(Main main, String key, String fallback) {
        String name = main.getLobbyItemsConfig().getString("arena-lobby." + key + ".material", fallback);
        if (name == null || name.trim().isEmpty()) {
            name = fallback;
        }
        ItemStack item = TTMaterial.itemFromName(name, 1);
        if (item != null) {
            return item;
        }
        main.getLogger().warning("arena-lobby." + key + ".material: unknown material ('" + name
                + "'), default value used (" + fallback + ").");
        return TTMaterial.itemFromName(fallback, 1);
    }

    private static ItemStack kitsItem(Main main) {
        ItemStack item = itemFromConfig(main, "kits", DEFAULT_KITS);
        return item == null ? null : named(main, item, "kits");
    }

    private static ItemStack leaveItem(Main main) {
        ItemStack item = itemFromConfig(main, "leave", DEFAULT_LEAVE);
        return item == null ? null : named(main, item, "leave");
    }

    /**
     * The team selector: a normal enchanted book (with the enchantment glint).
     * The menu it opens lets the player pick a team; the menu itself shows
     * every team, each with its own colored wool.
     */
    private static ItemStack teamItem(Main main) {
        ItemStack item = TTMaterial.itemFromName("ENCHANTED_BOOK", 1);
        if (item == null) {
            item = new ItemStack(Material.BOOK);
        }
        return named(main, item, "team", true);
    }

    /**
     * True if this item is the team selector. Recognized by its display name
     * (the material is a fixed enchanted book, but matching the name stays
     * consistent with how the other hotbar items are identified).
     */
    public static boolean isTeamItem(Main main, ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && nameOf(main, "team").equals(meta.getDisplayName());
    }

    /** Display name of an arena-lobby item (bold + &7(Right-click) hint, & colors applied). */
    private static String nameOf(Main main, String key) {
        String raw = main.getLobbyItemsConfig().getString("arena-lobby." + key + ".name", key);
        return Compat.hotbarName(raw);
    }

    /** Lore lines of an arena-lobby item (& colors applied). */
    private static List<String> loreOf(Main main, String key) {
        List<String> raw = main.getLobbyItemsConfig().getStringList("arena-lobby." + key + ".lore");
        List<String> out = new ArrayList<String>();
        for (String line : raw) {
            out.add(Compat.color(line));
        }
        return out;
    }

    private static ItemStack named(Main main, ItemStack item, String key) {
        return named(main, item, key, false);
    }

    /**
     * Same as {@link #named(Main, ItemStack, String)} but, when {@code glow},
     * adds the enchantment glint (a hidden enchant list) so the item looks
     * enchanted without showing an enchantment in its tooltip.
     */
    private static ItemStack named(Main main, ItemStack item, String key, boolean glow) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(nameOf(main, key));
            List<String> lore = loreOf(main, key);
            if (!lore.isEmpty()) {
                meta.setLore(lore);
            }
            MenuItems.hideVanillaInfo(meta);
            if (glow) {
                Enchants.glow(meta);
            }
            item.setItemMeta(meta);
        }
        return ItemTagger.mark(item);
    }
}
