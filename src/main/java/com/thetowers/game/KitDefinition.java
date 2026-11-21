package com.thetowers.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.bukkit.inventory.ItemStack;

import com.thetowers.compat.TTMaterial;

/**
 * A kit as defined in kits.yml: its name, icon, description, what it takes to
 * unlock it and its contents.
 *
 * <p>"The more you play" unlocking: {@link #getRequirements()} maps a stats
 * column (games, wins, kills, points...) to the value to reach; all of them
 * must be met. A permission may additionally reserve the kit for a rank (VIP
 * kit).
 *
 * <p>Two more conditions, combinable with each other:
 * <ul>
 *   <li>{@link #getConditions()}: freely written comparisons using the
 *       PlaceholderAPI placeholders ({@code %player_level% >= 15}); all of
 *       them must be true;</li>
 *   <li>{@link #getPrice()}: a price in Vault money, paid once and for all
 *       ({@code /tt kit buy <kit>}).</li>
 * </ul>
 */
public final class KitDefinition {

    private final String id;
    /** Numeric id (from kits.yml "id:", or auto-assigned 1, 2, 3...). Used for purchases. */
    private final int kitId;
    private final String displayName;
    private final ItemStack icon;
    private final List<String> description;
    private final Map<String, Integer> requirements;
    private final String permission;
    /** PlaceholderAPI comparisons ("{placeholder} {operator} {value}"), empty = none. */
    private final List<String> conditions;
    /** Price in Vault money; 0 = free. */
    private final double price;
    /** Required TheTowers level (native, no PlaceholderAPI needed); 0 = no level gate. */
    private final int level;
    private final List<ItemStack> items;
    private final List<KitEffect> effects;

    public KitDefinition(String id, String displayName, ItemStack icon, List<String> description,
            Map<String, Integer> requirements, String permission, List<ItemStack> items) {
        this(id, 0, displayName, icon, description, requirements, permission,
                new ArrayList<String>(), 0d, 0, items, new ArrayList<KitEffect>());
    }

    public KitDefinition(String id, int kitId, String displayName, ItemStack icon, List<String> description,
            Map<String, Integer> requirements, String permission, List<String> conditions,
            double price, int level, List<ItemStack> items, List<KitEffect> effects) {
        this.id = id;
        this.kitId = kitId;
        this.displayName = displayName;
        this.icon = icon;
        this.description = description;
        this.requirements = requirements;
        this.permission = permission;
        this.conditions = conditions;
        this.price = price;
        this.level = level;
        this.items = items;
        this.effects = effects;
    }

    /** Id (kits.yml key, lowercase). */
    public String getId() {
        return id;
    }

    /** Numeric id (1, 2, 3...), used to record purchases in the player's stats. */
    public int getKitId() {
        return kitId;
    }

    /** Display name, colors already applied. */
    public String getDisplayName() {
        return displayName;
    }

    /** A copy of the menu icon. */
    public ItemStack createIcon() {
        return icon.clone();
    }

    public List<String> getDescription() {
        return Collections.unmodifiableList(description);
    }

    /** Stats column -> minimum value. Empty = unlocked right away. */
    public Map<String, Integer> getRequirements() {
        return Collections.unmodifiableMap(requirements);
    }

    /** Required permission, or {@code null}. */
    public String getPermission() {
        return permission;
    }

    /** PlaceholderAPI comparisons to satisfy, in file order. */
    public List<String> getConditions() {
        return Collections.unmodifiableList(conditions);
    }

    /** Price in Vault money (0 = free). */
    public double getPrice() {
        return price;
    }

    /** Required TheTowers level (0 = no level gate). */
    public int getLevel() {
        return level;
    }

    public List<ItemStack> getItems() {
        return Collections.unmodifiableList(items);
    }

    /** Potion effects applied on every spawn. */
    public List<KitEffect> getEffects() {
        return Collections.unmodifiableList(effects);
    }

    /** Contents to give, without bows or arrows if the arena forbids them. */
    public List<ItemStack> itemsFor(boolean allowBows) {
        List<ItemStack> result = new ArrayList<ItemStack>();
        for (ItemStack item : items) {
            if (!allowBows && (TTMaterial.BOW.is(item.getType()) || TTMaterial.ARROW.is(item.getType()))) {
                continue;
            }
            result.add(item.clone());
        }
        return result;
    }
}
