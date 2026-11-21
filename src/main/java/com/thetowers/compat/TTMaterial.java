package com.thetowers.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.cryptomorin.xseries.XMaterial;

/**
 * Every material used by TheTowers, in a form that survives the 1.13 renaming
 * ("The Flattening") and handles the data values of 1.12.
 *
 * <p>The problem solved here: writing {@code Material.EXP_BOTTLE} in the code
 * compiles a direct reference to the enum field. That field no longer exists
 * from 1.13 on (it becomes {@code EXPERIENCE_BOTTLE}), and the JVM would then
 * raise a {@code NoSuchFieldError} as soon as the class loads, killing the
 * plugin at startup.
 *
 * <p>The resolution is therefore delegated to XMaterial (the XSeries
 * library), which knows the name mapping of every version and handles the
 * 1.8-1.12 data values along the way (colored wool and glass panes notably).
 * XMaterial is kept on purpose: on our 1.12.2 server the data values
 * ({@code WOOL:14}...) resolve natively, and ViaVersion translates the items
 * for clients of other versions.
 *
 * <p>This class is, with {@code Sounds} and {@code Enchants}, one of the three
 * points of contact between TheTowers and XSeries: switching libraries would
 * only require touching those three.
 */
public enum TTMaterial {

    // --- Identical on every version ---
    CHEST(XMaterial.CHEST),
    TRAPPED_CHEST(XMaterial.TRAPPED_CHEST),
    ENDER_CHEST(XMaterial.ENDER_CHEST),
    BOW(XMaterial.BOW),
    ARROW(XMaterial.ARROW),
    PAPER(XMaterial.PAPER),
    BARRIER(XMaterial.BARRIER),
    NETHER_STAR(XMaterial.NETHER_STAR),
    IRON_SWORD(XMaterial.IRON_SWORD),
    IRON_INGOT(XMaterial.IRON_INGOT),
    GOLD_INGOT(XMaterial.GOLD_INGOT),
    DIAMOND(XMaterial.DIAMOND),
    BAKED_POTATO(XMaterial.BAKED_POTATO),
    TNT(XMaterial.TNT),
    BEACON(XMaterial.BEACON),
    HOPPER(XMaterial.HOPPER),
    COMPASS(XMaterial.COMPASS),
    ENDER_EYE(XMaterial.ENDER_EYE),   // EYE_OF_ENDER
    WATER_BUCKET(XMaterial.WATER_BUCKET),
    LEATHER_HELMET(XMaterial.LEATHER_HELMET),
    LEATHER_CHESTPLATE(XMaterial.LEATHER_CHESTPLATE),
    LEATHER_LEGGINGS(XMaterial.LEATHER_LEGGINGS),
    LEATHER_BOOTS(XMaterial.LEATHER_BOOTS),
    CHAINMAIL_CHESTPLATE(XMaterial.CHAINMAIL_CHESTPLATE),

    // --- Renamed in 1.13 (old names in parentheses) ---
    EXPERIENCE_BOTTLE(XMaterial.EXPERIENCE_BOTTLE),   // EXP_BOTTLE
    CLOCK(XMaterial.CLOCK),                           // WATCH
    IRON_BARS(XMaterial.IRON_BARS),                   // IRON_FENCE

    // --- Renamed AND recolored in 1.13 (wool:data and glass-pane:data before) ---
    GRAY_STAINED_GLASS_PANE(XMaterial.GRAY_STAINED_GLASS_PANE),   // STAINED_GLASS_PANE:7
    GREEN_STAINED_GLASS_PANE(XMaterial.GREEN_STAINED_GLASS_PANE), // STAINED_GLASS_PANE:5
    RED_STAINED_GLASS_PANE(XMaterial.RED_STAINED_GLASS_PANE),     // STAINED_GLASS_PANE:14
    RED_WOOL(XMaterial.RED_WOOL),                                 // WOOL:14
    BLUE_WOOL(XMaterial.BLUE_WOOL),                               // WOOL:11
    YELLOW_WOOL(XMaterial.YELLOW_WOOL),                           // WOOL:4
    LIME_WOOL(XMaterial.LIME_WOOL);                               // WOOL:5

    private final XMaterial delegate;

    /** Material resolved at runtime, cached. */
    private Material resolved;

    TTMaterial(XMaterial delegate) {
        this.delegate = delegate;
    }

    /**
     * The matching material on the current server, or {@code null} if it does
     * not exist.
     */
    public Material material() {
        if (resolved == null) {
            resolved = delegate.parseMaterial();
        }
        return resolved;
    }

    /** True if {@code other} is this material on the current server. */
    public boolean is(Material other) {
        return other != null && other == material();
    }

    /** One copy of this item. */
    public ItemStack item() {
        return item(1);
    }

    /**
     * {@code amount} copies of this item. It is {@code parseItem()} that
     * applies the data value expected by the 1.8-1.12 versions.
     */
    public ItemStack item(int amount) {
        ItemStack item = delegate.parseItem();
        if (item == null) {
            throw new IllegalStateException("Material not found for " + name());
        }
        item.setAmount(amount);
        return item;
    }

    /**
     * Resolves every material at once at startup, to discover a potential
     * compatibility problem right away instead of in the middle of a game.
     *
     * @return the list of materials that could not be found, empty if all is
     *         well
     */
    public static List<String> resolveAll(Logger logger) {
        List<String> missing = new ArrayList<String>();
        for (TTMaterial value : values()) {
            if (!value.delegate.isSupported() || value.material() == null) {
                missing.add(value.name());
            }
        }
        if (!missing.isEmpty()) {
            logger.warning("Materials not found on this server: " + missing);
        }
        return missing;
    }

    /**
     * Item of <b>any</b> named material (modern name or pre-1.13 name), for
     * everything the admin picks in the config: starting kit, resource
     * generators. XSeries remains the sole judge.
     *
     * @return the item in the wanted amount, or {@code null} if the name
     *         matches no material existing on this version
     */
    public static ItemStack itemFromName(String name, int amount) {
        if (name == null) {
            return null;
        }
        Optional<XMaterial> match = XMaterial.matchXMaterial(name.trim());
        if (!match.isPresent() || !match.get().isSupported()) {
            return null;
        }
        ItemStack item = match.get().parseItem();
        if (item == null) {
            return null;
        }
        item.setAmount(Math.max(1, amount));
        return item;
    }

    /** True if this material is a chest (regular, trapped or Ender). */
    public static boolean isChest(Material material) {
        return CHEST.is(material) || TRAPPED_CHEST.is(material) || ENDER_CHEST.is(material);
    }

    /**
     * True for pistons (regular, sticky, head...). Test by NAME and not by
     * constant: the names changed in 1.13 (PISTON_BASE -> PISTON...), but all
     * of them contain "PISTON" on every version.
     */
    public static boolean isPiston(Material material) {
        return material != null && material.name().contains("PISTON");
    }

    /** True for a piece of leather armor (the kit ones, dyed by team). */
    public static boolean isLeatherArmor(Material material) {
        return LEATHER_HELMET.is(material) || LEATHER_CHESTPLATE.is(material)
                || LEATHER_LEGGINGS.is(material) || LEATHER_BOOTS.is(material);
    }
}
