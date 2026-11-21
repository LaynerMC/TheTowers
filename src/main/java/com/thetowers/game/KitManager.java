package com.thetowers.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Color;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import com.cryptomorin.xseries.XPotion;
import com.cryptomorin.xseries.XSound;

import com.thetowers.Main;
import com.thetowers.api.PapiBridge;
import com.thetowers.api.PlaceholderValues;
import com.thetowers.compat.Compat;
import com.thetowers.compat.Enchants;
import com.thetowers.compat.Sounds;
import com.thetowers.compat.TTMaterial;
import com.thetowers.compat.Yaml;
import com.thetowers.stats.PlayerStats;
import com.thetowers.stats.StatsStorage;

/**
 * Kits: definitions (kits.yml), unlock rules and each player's choice.
 *
 * <p>A kit is unlocked when ALL of its conditions are met:
 * <ul>
 *   <li>the optional permission ({@code permission:}),</li>
 *   <li>career statistics ({@code requires:} - games, wins, kills,
 *       points...),</li>
 *   <li>PlaceholderAPI comparisons ({@code conditions:} - e.g.
 *       {@code %player_level% >= 15}; without PlaceholderAPI, a kit with
 *       conditions stays locked),</li>
 *   <li>the Vault price ({@code price:}) paid ONCE via
 *       {@code /tt kit buy <kit>} - 0 = free.</li>
 * </ul>
 *
 * <p>The choice is stored on the player ({@link PlayerStats}, stats.yml or the
 * {@code kit} column of MySQL) and kept from one game to the next. Purchases
 * (the kit IDs bought) are also stored in {@link PlayerStats} (the
 * {@code purchasedKits} field / {@code purchased_kits} column): there is no
 * separate kit-purchases.yml anymore.
 */
public class KitManager {

    /** Unlocks every kit (testing, administrators). */
    public static final String ALL_KITS_PERMISSION = "thetowers.kits.all";

    /** Operators accepted in a PlaceholderAPI condition, longest first. */
    private static final String[] OPERATORS = { "==", "!=", ">=", "<=", ">", "<" };

    private final Main main;
    private final Map<String, KitDefinition> kits = new LinkedHashMap<String, KitDefinition>();
    private String defaultKitId;
    private boolean teamArmor;

    // Sound played when a player clicks a kit they cannot select yet (locked or
    // unaffordable). Loaded from kits.yml (locked-click-sound); null = disabled.
    private XSound lockedSound = XSound.BLOCK_ANVIL_USE;
    private float lockedVolume = 0.4f;
    private float lockedPitch = 0.0f;

    public KitManager(Main main) {
        this.main = main;
        load();
    }

    // ================= Kit definitions (kits.yml) =================

    /** (Re)reads kits.yml. Called on startup and by /tt reload. */
    public void load() {
        kits.clear();
        teamArmor = main.getConfig().getBoolean("kit.team-armor", true);

        java.io.File file = com.thetowers.config.CustomConfig.ensureModuleFile(main, "modules/kits.yml", true);
        YamlConfiguration yml = Yaml.load(file);
        loadLockedSound(yml);
        ConfigurationSection section = yml.getConfigurationSection("kits");
        if (section != null) {
            int autoId = 1;
            for (String key : section.getKeys(false)) {
                ConfigurationSection kitSection = section.getConfigurationSection(key);
                if (kitSection != null) {
                    // Numeric id: read from kits.yml ("id:") or auto-assign 1, 2, 3...
                    int kitId = kitSection.getInt("id", autoId);
                    autoId = Math.max(autoId + 1, kitId + 1);
                    KitDefinition kit = parse(key.toLowerCase(), kitId, kitSection);
                    kits.put(kit.getId(), kit);
                }
            }
        }

        if (kits.isEmpty()) {
            // File missing or empty: a minimal kit so the game stays playable.
            main.getLogger().warning("No kit in kits.yml: fallback kit used.");
            List<ItemStack> items = new ArrayList<ItemStack>();
            items.add(TTMaterial.BAKED_POTATO.item(16));
            kits.put("default", new KitDefinition("default", "Default", TTMaterial.LEATHER_CHESTPLATE.item(),
                    new ArrayList<String>(), new LinkedHashMap<String, Integer>(), null, items));
        }
        defaultKitId = yml.getString("default-kit", "default").toLowerCase();
        if (!kits.containsKey(defaultKitId)) {
            String first = kits.keySet().iterator().next();
            main.getLogger().warning("Default kit '" + defaultKitId + "' not found, '" + first
                    + "' used instead.");
            defaultKitId = first;
        }
        main.getLogger().info(kits.size() + " kit(s) loaded: " + kits.keySet() + ".");
    }

    /**
     * Reads the {@code locked-click-sound} block from kits.yml: the sound played
     * when a player clicks a kit they cannot select yet. {@code sound: none}
     * disables it; an unknown name falls back to the default anvil strike.
     */
    private void loadLockedSound(YamlConfiguration yml) {
        ConfigurationSection ls = yml.getConfigurationSection("locked-click-sound");
        if (ls == null) {
            // Defaults (anvil, quiet, lowest pitch) stay in place.
            return;
        }
        String name = ls.getString("sound", "BLOCK_ANVIL_USE");
        if (name == null || name.trim().isEmpty() || "none".equalsIgnoreCase(name.trim())) {
            lockedSound = null; // disabled
            return;
        }
        lockedSound = XSound.matchXSound(name).orElse(XSound.BLOCK_ANVIL_USE);
        lockedVolume = (float) Math.max(0d, Math.min(1d, ls.getDouble("volume", 0.4d)));
        lockedPitch = (float) Math.max(0d, Math.min(2d, ls.getDouble("pitch", 0.0d)));
    }

    /**
     * Plays the configured locked-click sound to the player (a soft anvil strike
     * by default). Does nothing if the sound was disabled ({@code sound: none}).
     */
    public void playLockedSound(Player player) {
        if (lockedSound == null || player == null || !player.isOnline()) {
            return;
        }
        lockedSound.record()
                .withVolume(lockedVolume)
                .withPitch(lockedPitch)
                .soundPlayer()
                .forPlayers(player)
                .play();
    }

    private KitDefinition parse(String id, int kitId, ConfigurationSection s) {
        ItemStack icon = TTMaterial.itemFromName(s.getString("icon", "CHEST"), 1);
        if (icon == null) {
            main.getLogger().warning("Kit " + id + ": unknown icon, chest used.");
            icon = TTMaterial.CHEST.item();
        }

        List<String> description = new ArrayList<String>();
        for (String line : s.getStringList("description")) {
            description.add(Compat.color(line));
        }

        Map<String, Integer> requirements = new LinkedHashMap<String, Integer>();
        ConfigurationSection requires = s.getConfigurationSection("requires");
        // `requires` is STATS ONLY (games, wins, losses, kills, deaths, points).
        // The coin cost is a separate top-level field (`cost:`, parallel to
        // `level:`), paid LAST - only once the stats and the level gate are met
        // (see KitMenu.choose and KitManager.nonCostRequirementsMet). A legacy
        // top-level `price:` and a leftover `coins:` entry inside `requires`
        // still work as fallbacks for old kits.yml files, but should be migrated
        // to `cost:`.
        double legacyRequiresCoins = 0d;
        if (requires != null) {
            for (String column : requires.getKeys(false)) {
                String key = column.toLowerCase();
                if ("coins".equals(key)) {
                    legacyRequiresCoins = Math.max(0d, requires.getDouble(column, 0d));
                    main.getLogger().warning("Kit " + id + ": `coins` no longer belongs in "
                            + "`requires` (stats only). Move it to a top-level `cost:` field.");
                } else if (StatsStorage.isColumn(key)) {
                    requirements.put(key, Integer.valueOf(Math.max(0, requires.getInt(column))));
                } else {
                    main.getLogger().warning("Kit " + id + ": unknown requirement '" + column
                            + "' ignored (valid values: games, wins, losses, kills, deaths, points).");
                }
            }
        }

        String permission = s.getString("permission", "");
        List<String> conditions = new ArrayList<String>();
        for (String condition : s.getStringList("conditions")) {
            if (condition != null && !condition.trim().isEmpty()
                    && findOperator(condition.trim()) != null) {
                conditions.add(condition.trim());
            } else if (condition != null && !condition.trim().isEmpty()) {
                main.getLogger().warning("Kit " + id + ": condition '" + condition
                        + "' ignored (expected: %placeholder% OPERATOR value).");
            }
        }
        // Cost: top-level `cost:` (canonical) -> legacy `price:` -> a leftover
        // `coins:` pulled out of `requires` above. 0 = free kit.
        double price = Math.max(0d, s.getDouble("cost", 0d));
        if (price <= 0d) {
            price = Math.max(0d, s.getDouble("price", 0d));
        }
        if (price <= 0d) {
            price = legacyRequiresCoins;
        }
        int level = Math.max(0, s.getInt("level", 0));
        List<ItemStack> items = new ArrayList<ItemStack>();
        for (Map<?, ?> entry : s.getMapList("items")) {
            ItemStack item = parseItem(id, entry);
            if (item != null) {
                items.add(item);
            }
        }
        List<KitEffect> effects = parseEffects(id, s);

        return new KitDefinition(id, kitId, Compat.color(s.getString("name", id)), icon, description,
                requirements, permission == null || permission.trim().isEmpty() ? null : permission.trim(),
                conditions, price, level, items, effects);
    }

    /** Kit potion effects (applied on every spawn). */
    private List<KitEffect> parseEffects(String kitId, ConfigurationSection s) {
        List<KitEffect> effects = new ArrayList<KitEffect>();
        for (Map<?, ?> entry : s.getMapList("effects")) {
            Object type = entry.get("type");
            if (type == null) {
                continue;
            }
            // XPotion knows every alias across versions (STRENGTH is named
            // INCREASE_DAMAGE in 1.8, JUMP became JUMP_BOOST...).
            XPotion xpotion;
            try {
                xpotion = XPotion.matchXPotion(String.valueOf(type).toUpperCase(Locale.ROOT)).orElse(null);
            } catch (Throwable unknownApi) {
                xpotion = null;
            }
            if (xpotion == null || xpotion.getPotionEffectType() == null) {
                main.getLogger().warning("Kit " + kitId + ": unknown potion effect ('" + type + "'), ignored.");
                continue;
            }
            int amplifier = entry.get("amplifier") instanceof Number ? ((Number) entry.get("amplifier")).intValue() : 0;
            int duration = entry.get("duration") instanceof Number ? ((Number) entry.get("duration")).intValue() : 0;
            effects.add(new KitEffect(xpotion.getPotionEffectType(), amplifier, duration));
        }
        return effects;
    }

    private ItemStack parseItem(String kitId, Map<?, ?> entry) {
        Object material = entry.get("material");
        Object amount = entry.get("amount");
        int count = amount instanceof Number ? ((Number) amount).intValue() : 1;
        ItemStack item = TTMaterial.itemFromName(material == null ? null : material.toString(), count);
        if (item == null) {
            main.getLogger().warning("Kit " + kitId + ": item ignored, unknown material (" + material + ").");
            return null;
        }
        Object enchantments = entry.get("enchantments");
        if (enchantments instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) enchantments).entrySet()) {
                int level = e.getValue() instanceof Number ? ((Number) e.getValue()).intValue() : 1;
                if (!Enchants.apply(item, String.valueOf(e.getKey()), level)) {
                    main.getLogger().warning("Kit " + kitId + ": unknown enchantment (" + e.getKey() + ").");
                }
            }
        }
        return item;
    }

    public List<KitDefinition> getKits() {
        return new ArrayList<KitDefinition>(kits.values());
    }

    /** Kit by id (case-insensitive), or {@code null}. */
    public KitDefinition getKit(String id) {
        return id == null ? null : kits.get(id.toLowerCase());
    }

    public KitDefinition getDefaultKit() {
        return kits.get(defaultKitId);
    }

    /**
     * Resolves a kit by its id (the kits.yml key, case-insensitive), by its
     * numeric {@link KitDefinition#getKitId()}, or by its display name
     * (color-stripped, case-insensitive). Lets admin commands accept a "kit
     * name or id". Returns {@code null} if nothing matches.
     */
    public KitDefinition resolveKit(String nameOrId) {
        if (nameOrId == null) {
            return null;
        }
        KitDefinition byKey = getKit(nameOrId);
        if (byKey != null) {
            return byKey;
        }
        // Numeric kit id?
        try {
            int numeric = Integer.parseInt(nameOrId.trim());
            for (KitDefinition kit : kits.values()) {
                if (kit.getKitId() == numeric) {
                    return kit;
                }
            }
        } catch (NumberFormatException ignored) {
            // Not a number: fall through to display-name matching.
        }
        // Display name (color-stripped, case-insensitive).
        String wanted = org.bukkit.ChatColor.stripColor(nameOrId).trim();
        for (KitDefinition kit : kits.values()) {
            String shown = org.bukkit.ChatColor.stripColor(kit.getDisplayName()).trim();
            if (wanted.equalsIgnoreCase(shown)) {
                return kit;
            }
        }
        return null;
    }

    // ================= Unlock =================

    /**
     * True if this player can use this kit now - i.e. the kit is REVEALED
     * (unlocked): default kit, admin bypass, or every requirement met,
     * INCLUDING the one-time coin cost. A revealed kit is only ever selected
     * (never charged): see {@link com.thetowers.game.KitMenu#choose}.
     */
    public boolean isUnlocked(Player player, KitDefinition kit) {
        return requirementsMet(player, kit);
    }

    /**
     * The reveal gate: default kit, admin bypass, or every requirement met
     * (permission, stats, level, conditions) AND the one-time coin cost paid
     * (or free). A kit that fails this is LOCKED (red pane); clicking a locked
     * kit whose only missing requirement is the cost buys it once (see
     * {@link com.thetowers.game.KitMenu#choose}).
     */
    public boolean requirementsMet(Player player, KitDefinition kit) {
        if (kit.getId().equals(defaultKitId) || player.hasPermission(ALL_KITS_PERMISSION)) {
            return true;
        }
        return nonCostRequirementsMet(player, kit) && isPaidFor(player, kit);
    }

    /**
     * True if every NON-cost requirement is met: permission, stats, native
     * level and PlaceholderAPI conditions. Used to tell a locked kit that is
     * "ready to buy" (only the cost is missing) from one that still has earned
     * requirements to fulfill.
     */
    public boolean nonCostRequirementsMet(Player player, KitDefinition kit) {
        if (kit.getId().equals(defaultKitId) || player.hasPermission(ALL_KITS_PERMISSION)) {
            return true;
        }
        return hasPermissionFor(player, kit) && hasStatsFor(player, kit)
                && hasLevelFor(player, kit) && conditionsMet(player, kit);
    }

    /**
     * True if this player bypasses every kit requirement: the
     * {@code thetowers.kits.all} permission (held by operators by default). Such
     * a player sees every kit revealed, never locked, and never pays the coin
     * cost - the cost is a requirement, and a bypassed requirement is not shown.
     */
    public boolean bypassesRequirements(Player player) {
        return player.hasPermission(ALL_KITS_PERMISSION);
    }

    /** True if the player's TheTowers level meets the kit's level gate (0 = ok). */
    public boolean hasLevelFor(Player player, KitDefinition kit) {
        int required = kit.getLevel();
        if (required <= 0) {
            return true;
        }
        return main.getLevelingManager().getLevel(player.getUniqueId()) >= required;
    }

    /** The player's current TheTowers level (for the kit lore progress line). */
    public long currentLevel(Player player) {
        return main.getLevelingManager().getLevel(player.getUniqueId());
    }

    /** True if the kit's optional permission is satisfied (absent = ok). */
    public boolean hasPermissionFor(Player player, KitDefinition kit) {
        return kit.getPermission() == null || player.hasPermission(kit.getPermission());
    }

    /** True if every required statistic is reached. */
    public boolean hasStatsFor(Player player, KitDefinition kit) {
        for (Map.Entry<String, Integer> requirement : kit.getRequirements().entrySet()) {
            if (progress(player, requirement.getKey()) < requirement.getValue().intValue()) {
                return false;
            }
        }
        return true;
    }

    /** True if the kit is free or already bought. */
    public boolean isPaidFor(Player player, KitDefinition kit) {
        return kit.getPrice() <= 0d
                || main.getStatsManager().hasPurchasedKit(player, kit.getKitId());
    }

    /**
     * True if the kit's price can be paid RIGHT NOW: not bought yet, positive
     * price, Vault economy available and every other condition (permission,
     * stats, placeholders) met.
     */
    public boolean canPurchase(Player player, KitDefinition kit) {
        return kit.getPrice() > 0d
                && !main.getStatsManager().hasPurchasedKit(player, kit.getKitId())
                && main.getEconomyHook() != null && main.getEconomyHook().available()
                && nonCostRequirementsMet(player, kit);
    }

    /**
     * Buys the kit with Vault money.
     *
     * @return {@code null} if the purchase succeeded, otherwise the error message key
     */
    public String purchase(Player player, KitDefinition kit) {
        if (kit.getPrice() <= 0d || main.getStatsManager().hasPurchasedKit(player, kit.getKitId())) {
            return "kits.buy_already";
        }
        if (main.getEconomyHook() == null || !main.getEconomyHook().available()) {
            return "kits.buy_no_economy";
        }
        if (!nonCostRequirementsMet(player, kit)) {
            return "kits.buy_locked";
        }
        double price = kit.getPrice();
        if (main.getEconomyHook().balance(player) < price) {
            return "kits.buy_poor";
        }
        if (!main.getEconomyHook().withdraw(player, price)) {
            return "kits.buy_poor";
        }
        main.getStatsManager().addPurchasedKit(player, kit.getKitId());
        Sounds.kitPurchase(player);
        return null;
    }

    /**
     * Admin grant ({@code /tt givekit <kit|id> <player>}): pays the one-time
     * coin cost of a kit on behalf of the player, so the kit is revealed
     * (unlocked) without charging them. Has no effect on a free kit (already
     * available) or an already-owned one.
     *
     * @return {@code null} on success, otherwise the error message key
     */
    public String giveKit(Player player, KitDefinition kit) {
        if (kit.getPrice() <= 0d) {
            return "kits.givekit_free";
        }
        if (main.getStatsManager().hasPurchasedKit(player, kit.getKitId())) {
            return "kits.givekit_already";
        }
        main.getStatsManager().addPurchasedKit(player, kit.getKitId());
        Sounds.kitPurchase(player);
        return null;
    }

    /**
     * Admin revoke ({@code /tt removekit <kit|id> <player>}): strips a
     * player's kit unlock, as if they had never bought it. Only a PAID kit can
     * be revoked (a free / requirement-only kit has no purchase record to
     * remove). If the revoked kit is the player's currently selected one, the
     * saved choice is reset to the default kit so they are not left on a kit
     * they no longer own. After a revoke the player can obtain the kit again
     * through the normal process (pay again, or meet the requirements).
     *
     * @return {@code null} on success, otherwise the error message key
     */
    public String removeKit(Player player, KitDefinition kit) {
        if (kit.getPrice() <= 0d) {
            return "kits.removekit_free";
        }
        if (!main.getStatsManager().hasPurchasedKit(player, kit.getKitId())) {
            return "kits.removekit_not_owned";
        }
        main.getStatsManager().removePurchasedKit(player, kit.getKitId());
        // If this was the player's selected kit, they no longer qualify for it
        // (isPaidFor is now false): reset the saved choice so getSelected does
        // not hand them a kit they no longer own.
        com.thetowers.stats.PlayerStats stats = main.getStatsManager().getCached(player.getUniqueId());
        if (stats != null && kit.getId().equals(stats.getKit())) {
            stats.setKit(null);
            main.getStatsManager().save(player.getUniqueId());
        }
        return null;
    }

    // ================= PlaceholderAPI conditions =================

    /** True if every PlaceholderAPI condition of the kit is true. */
    public boolean conditionsMet(Player player, KitDefinition kit) {
        if (kit.getConditions().isEmpty()) {
            return true;
        }
        for (String condition : kit.getConditions()) {
            if (!evaluateCondition(player, condition)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares two numbers with the given operator.
     *
     * @return -1, 0 or 1, like a comparator
     */
    private static int compare(double a, double b) {
        return a < b ? -1 : a > b ? 1 : 0;
    }

    /**
     * Evaluates a condition such as {@code %player_level% >= 15}. The
     * left-hand side is resolved through PlaceholderAPI (our placeholders
     * too), then compared to the right-hand side: numerically if both are
     * numbers, otherwise textually for {@code ==} and {@code !=}. Without
     * PlaceholderAPI, or if the placeholder returns nothing, the condition is
     * FALSE.
     */
    public boolean evaluateCondition(Player player, String expression) {
        String op = findOperator(expression);
        if (op == null) {
            return false;
        }
        int at = expression.indexOf(op);
        String left = resolveForCondition(player, expression.substring(0, at).trim());
        String right = resolveForCondition(player, expression.substring(at + op.length()).trim());
        if (left == null || right == null || left.indexOf('%') >= 0 || right.indexOf('%') >= 0) {
            // Unresolved placeholder: no PAPI, or unknown placeholder.
            return false;
        }
        Double a = toNumber(left);
        Double b = toNumber(right);
        int cmp;
        if (a != null && b != null) {
            cmp = compare(a.doubleValue(), b.doubleValue());
        } else if ("==".equals(op) || "!=".equals(op)) {
            cmp = left.equalsIgnoreCase(right) ? 0 : (left.compareToIgnoreCase(right) < 0 ? -1 : 1);
        } else {
            // Ordering comparison on text: refused.
            return false;
        }
        if ("==".equals(op)) {
            return cmp == 0;
        }
        if ("!=".equals(op)) {
            return cmp != 0;
        }
        if (">=".equals(op)) {
            return cmp >= 0;
        }
        if ("<=".equals(op)) {
            return cmp <= 0;
        }
        if (">".equals(op)) {
            return cmp > 0;
        }
        return cmp < 0; // "<"
    }

    /** Resolves a condition side: our placeholders first, then PAPI. */
    private String resolveForCondition(Player player, String side) {
        String text = PlaceholderValues.replace(main, player, side);
        if (main.hasPlaceholderApi() && text.indexOf('%') >= 0) {
            text = PapiBridge.apply(player, text);
        }
        return text;
    }

    /**
     * The condition side resolved for DISPLAY (kit menu): our placeholders,
     * then PAPI's if present, without failing if a placeholder stays raw.
     */
    public String resolveConditionDisplay(Player player, String side) {
        String text = PlaceholderValues.replace(main, player, side);
        if (main.hasPlaceholderApi() && text.indexOf('%') >= 0) {
            text = PapiBridge.apply(player, text);
        }
        return text;
    }

    /** Lines of the UNMET conditions, resolved for the menu display. */
    public List<String> unmetConditions(Player player, KitDefinition kit) {
        List<String> unmet = new ArrayList<String>();
        for (String condition : kit.getConditions()) {
            if (!evaluateCondition(player, condition)) {
                unmet.add(resolveConditionDisplay(player, condition));
            }
        }
        return unmet;
    }

    /** The first operator found in the expression, or {@code null}. */
    private static String findOperator(String expression) {
        for (int i = 0; i < expression.length(); i++) {
            for (String op : OPERATORS) {
                if (expression.regionMatches(i, op, 0, op.length())) {
                    return op;
                }
            }
        }
        return null;
    }

    /** Parses a number, or {@code null} (spaces and % are tolerated around it). */
    private static Double toNumber(String text) {
        try {
            return Double.valueOf(Double.parseDouble(text.trim()));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** Current value of one of the player's stats (0 until they are loaded). */
    public int progress(Player player, String column) {
        PlayerStats stats = main.getStatsManager().getCached(player.getUniqueId());
        return stats == null ? 0 : stats.value(column);
    }

    /** Ids of the kits currently unlocked by this player. */
    public Set<String> unlockedIds(Player player) {
        Set<String> ids = new LinkedHashSet<String>();
        for (KitDefinition kit : kits.values()) {
            if (isUnlocked(player, kit)) {
                ids.add(kit.getId());
            }
        }
        return ids;
    }

    // ================= Player's choice =================

    /**
     * The kit the player will use: their saved choice if they still qualify for
     * it (a lost rank, a kit removed from kits.yml or not paid falls back to the
     * default kit).
     */
    public KitDefinition getSelected(Player player) {
        PlayerStats stats = main.getStatsManager().getCached(player.getUniqueId());
        String saved = stats == null ? null : stats.getKit();
        KitDefinition kit = getKit(saved);
        if (kit != null && isUnlocked(player, kit)) {
            return kit;
        }
        return getDefaultKit();
    }

    /** Remembers the player's choice. False if the kit is not unlocked. */
    public boolean select(Player player, KitDefinition kit) {
        if (!isUnlocked(player, kit)) {
            return false;
        }
        PlayerStats stats = main.getStatsManager().getCached(player.getUniqueId());
        if (stats == null) {
            // Stats not loaded yet (async): leave the live cache alone;
            // StatsManager will pick the saved kit up on next load. Rare path
            // right after join.
            return false;
        }
        stats.setKit(kit.getId());
        main.getStatsManager().save(player.getUniqueId());
        return true;
    }

    /**
     * No-op now: the saved kit is part of stats.yml and is loaded together with
     * the player's stats by {@link com.thetowers.stats.StatsManager#load}.
     * Kept only so existing call sites compile.
     */
    public void loadSelection(Player player) {
        // intentionally empty
    }

    /**
     * No-op now (see {@link #loadSelection}). The saved kit persists via stats
     * save on disconnect.
     */
    public void unloadSelection(Player player) {
        // intentionally empty
    }

    /** No-op now: there is no dedicated kit I/O thread to shut down. */
    public void shutdown() {
        // intentionally empty
    }

    // ================= Distribution =================

    /**
     * Equips the player (inventory already empty): leather armor dyed in the
     * team's color, then the content of their kit, then the kit's potion
     * effects. A piece of armor from the kit is worn directly, replacing the
     * leather piece.
     */
    public void give(Player player, Color teamColor, boolean allowBows) {
        PlayerInventory inventory = player.getInventory();
        if (teamArmor) {
            inventory.setHelmet(dyed(TTMaterial.LEATHER_HELMET, teamColor));
            inventory.setChestplate(dyed(TTMaterial.LEATHER_CHESTPLATE, teamColor));
            inventory.setLeggings(dyed(TTMaterial.LEATHER_LEGGINGS, teamColor));
            inventory.setBoots(dyed(TTMaterial.LEATHER_BOOTS, teamColor));
        }
        for (ItemStack item : getSelected(player).itemsFor(allowBows)) {
            if (!equipArmor(inventory, item)) {
                inventory.addItem(item);
            }
        }
        applyEffects(player);
    }

    /** Re-applies the kit's potion effects (on every spawn). */
    public void applyEffects(Player player) {
        KitDefinition kit = getSelected(player);
        for (KitEffect effect : kit.getEffects()) {
            try {
                player.addPotionEffect(effect.toPotionEffect());
            } catch (Throwable ignored) {
                // Effect refused by the server: no big deal.
            }
        }
    }

    /**
     * Wears a piece of armor. Recognized by the END of the material name, stable
     * across versions (IRON_CHESTPLATE, CHAINMAIL_BOOTS...), rather than a list
     * of constants that would have changed in 1.13.
     */
    private static boolean equipArmor(PlayerInventory inventory, ItemStack item) {
        String name = item.getType().name();
        if (name.endsWith("_HELMET")) {
            inventory.setHelmet(item);
        } else if (name.endsWith("_CHESTPLATE")) {
            inventory.setChestplate(item);
        } else if (name.endsWith("_LEGGINGS")) {
            inventory.setLeggings(item);
        } else if (name.endsWith("_BOOTS")) {
            inventory.setBoots(item);
        } else {
            return false;
        }
        return true;
    }

    private static ItemStack dyed(TTMaterial piece, Color color) {
        ItemStack item = piece.item();
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof LeatherArmorMeta) {
            ((LeatherArmorMeta) meta).setColor(color);
            item.setItemMeta(meta);
        }
        return item;
    }
}
