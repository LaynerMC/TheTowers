package com.thetowers.compat;

import java.util.Optional;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.cryptomorin.xseries.XEnchantment;

/**
 * Enchantments of the kit items.
 *
 * <p>Bukkit enchantment names changed in 1.20.5 ({@code DIG_SPEED} became
 * {@code EFFICIENCY}, {@code DAMAGE_ALL} became {@code SHARPNESS}...). On our
 * 1.12.2 server the old names are the real ones, but kits.yml may contain
 * either family. We delegate to {@code XEnchantment} (XSeries), which accepts
 * both and returns the server's real enchantment. XSeries is kept on purpose:
 * the kit files stay portable, and ViaVersion translates the resulting items
 * for clients of other versions.
 *
 * <p>Third point of contact with XSeries, after {@link TTMaterial} and
 * {@link Sounds}.
 */
public final class Enchants {

    private Enchants() {
    }

    /**
     * Adds an enchantment by its name (modern or old), with no level cap and
     * no item-compatibility check.
     *
     * @return false if the name is unknown on this version
     */
    public static boolean apply(ItemStack item, String name, int level) {
        Enchantment enchantment = resolve(name);
        if (item == null || enchantment == null) {
            return false;
        }
        item.addUnsafeEnchantment(enchantment, Math.max(1, level));
        return true;
    }

    /**
     * Makes a menu icon glow (selected kit) without showing an enchantment:
     * any enchantment + the flag that hides the list. {@code ItemFlag}
     * exists in 1.12.2.
     */
    public static void glow(ItemMeta meta) {
        Enchantment any = resolve("UNBREAKING");
        if (meta == null || any == null) {
            return;
        }
        meta.addEnchant(any, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
    }

    private static Enchantment resolve(String name) {
        if (name == null) {
            return null;
        }
        Optional<XEnchantment> match = XEnchantment.matchXEnchantment(name.trim());
        if (!match.isPresent() || !match.get().isSupported()) {
            return null;
        }
        return match.get().getEnchant();
    }
}
