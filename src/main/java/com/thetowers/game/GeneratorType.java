package com.thetowers.game;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import com.thetowers.compat.TTMaterial;

/**
 * A resource generator type, as defined in the {@code generators:} section of
 * config.yml: which item, at which rate, and how many may lie on the ground at
 * most.
 *
 * <p>Defaults shipped in the file: iron (3 s), XP bottles (1.5 s) and diamond
 * (40 s). The admin can add as many as they want (gold, emerald, apples...).
 */
public final class GeneratorType {

    private final String key;
    private final ItemStack template;
    private final int intervalTicks;
    private final int maxOnGround;

    private GeneratorType(String key, ItemStack template, int intervalTicks, int maxOnGround) {
        this.key = key;
        this.template = template;
        this.intervalTicks = intervalTicks;
        this.maxOnGround = maxOnGround;
    }

    /** Type id (config.yml key, e.g. {@code iron}). */
    public String getKey() {
        return key;
    }

    /** A fresh copy of the produced item. */
    public ItemStack createItem() {
        return template.clone();
    }

    public Material getMaterial() {
        return template.getType();
    }

    /** Interval between two spawns, in ticks (20 ticks = 1 second). */
    public int getIntervalTicks() {
        return intervalTicks;
    }

    /** Max number of this generator's items on the ground at the same time. */
    public int getMaxOnGround() {
        return maxOnGround;
    }

    /**
     * Reads the {@code generators:} section of config.yml. A type whose
     * material is unknown on 1.12.2 is skipped with a warning, rather than
     * failing the startup.
     */
    public static Map<String, GeneratorType> load(ConfigurationSection section, Logger logger) {
        Map<String, GeneratorType> types = new LinkedHashMap<String, GeneratorType>();
        if (section == null) {
            return types;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            String materialName = entry.getString("material", "");
            ItemStack item = TTMaterial.itemFromName(materialName, Math.max(1, entry.getInt("amount", 1)));
            if (item == null) {
                logger.warning("Generator '" + key + "' ignored: unknown material (" + materialName + ").");
                continue;
            }
            int interval = Math.max(1, entry.getInt("interval", 60));
            int max = Math.max(1, entry.getInt("max-on-ground", 1));
            types.put(key.toLowerCase(), new GeneratorType(key.toLowerCase(), item, interval, max));
        }
        return types;
    }
}
