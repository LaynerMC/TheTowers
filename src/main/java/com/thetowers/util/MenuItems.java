package com.thetowers.util;

import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

/**
 * Hides the "vanilla" information of the items used in menus and in the lobby
 * hotbar (sword damage, armor protection, potion type and effects,
 * enchantments, "unbreakable"...): only the custom name and the lore set by
 * the plugin remain in the tooltip.
 *
 * <p>It is applied with the classic {@link ItemFlag}s, present in the API
 * since 1.8 (the plugin compiles against spigot-api 1.12.2, see the pom); on
 * a 1.12.2 server they hide the corresponding tooltip info directly. The
 * glint of an enchanted item is kept: {@code HIDE_ENCHANTS} removes only the
 * tooltip text, not the visual effect.
 *
 * <p>1.20.5+ peculiarity, harmless dead weight on 1.12.2: the
 * {@code HIDE_ATTRIBUTES} flag only hides the tooltip if the attribute
 * modifiers component EXISTS on the item; on a 1.20.5+ server one must also
 * write an empty attribute map (SPIGOT-7836). This is done through reflection
 * (the method is not in the compiled 1.12.2 API) and only when the flag was
 * applied correctly; on 1.12.2 the flag is enough and the empty map changes
 * nothing visible.
 *
 * <p>Everything is in try/catch: a fork that moved the class must not break
 * the opening of a menu (like the rest of the {@code compat} package).
 */
public final class MenuItems {

    private MenuItems() {
    }

    /**
     * Returns the same item with all vanilla information hidden. Null if
     * {@code item} is null; unchanged if the material has no meta.
     */
    public static ItemStack hideVanillaInfo(ItemStack item) {
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            hideVanillaInfo(meta);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Applies the hiding flags to a meta already in hand. */
    public static void hideVanillaInfo(ItemMeta meta) {
        if (meta == null) {
            return;
        }
        try {
            meta.addItemFlags(
                    ItemFlag.HIDE_ATTRIBUTES,
                    ItemFlag.HIDE_ENCHANTS,
                    ItemFlag.HIDE_POTION_EFFECTS,
                    ItemFlag.HIDE_UNBREAKABLE,
                    ItemFlag.HIDE_DESTROYS,
                    ItemFlag.HIDE_PLACED_ON);
        } catch (Throwable ignored) {
            // Exotic version missing some of the flags: the vanilla information
            // is shown, but the menu keeps working.
            return;
        }
        // 1.20.5+: write an empty attribute map so that the component exists
        // and the "show_in_tooltip" of the flag is truly applied (on 1.12.2 it
        // is a harmless no-op: menu icons carry no attributes of their own).
        // Full reflection: the method does not exist in the compiled 1.12.2
        // API, hence no direct call.
        try {
            Multimap<Object, Object> empty = HashMultimap.create();
            meta.getClass().getMethod("setAttributeModifiers", Multimap.class).invoke(meta, empty);
        } catch (Throwable quiet) {
            // Method absent (1.12.2) or different signature: the flag is enough there.
        }
    }
}
