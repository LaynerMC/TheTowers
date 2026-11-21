package com.thetowers.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.ConfigurationSection;

import com.thetowers.Main;
import com.thetowers.compat.TTMaterial;
import com.thetowers.lang.Menus;
import com.thetowers.util.MenuItems;

/**
 * Configurable {@code /tt stats} inventory menu, fully defined in menus.yml
 * under {@code stats-menu}: the title, the inventory size and one item per
 * stat (material, slot, display name, lore), with {@code %value%} replaced by
 * the target's value.
 *
 * <p>Supported stat ids (the item keys in menus.yml):
 * {@code games, wins, losses, kills, deaths, points, kd, blocks_placed,
 * blocks_broken, max_kill_streak, damage_dealt, damage_taken, coins}.
 *
 * <p>If the target is online, their cached (live) stats are used; otherwise a
 * name lookup is done asynchronously and the menu opens when the result
 * arrives.
 */
public class StatsMenu {

    private final Main main;

    public StatsMenu(Main main) {
        this.main = main;
    }

    /** Opens the stats menu of the given target (by name) for the viewer. */
    public void open(Player viewer, String targetName) {
        if (targetName == null) {
            targetName = viewer.getName();
        }
        final Player viewer_ = viewer;
        final String name = targetName;
        // Online and cached: open right away with the live values.
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            PlayerStats cached = main.getStatsManager().getCached(online.getUniqueId());
            if (cached != null) {
                openWith(viewer_, online, cached);
                return;
            }
        }
        // Offline or not yet loaded: async lookup, then open on the main thread.
        main.getStatsManager().lookupByName(name, new Consumer<PlayerStats>() {
            @Override
            public void accept(PlayerStats stats) {
                if (stats == null) {
                    viewer_.sendMessage(com.thetowers.lang.Messages.tr("command.no_stats_player"));
                    return;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(stats.getUuid());
                openWith(viewer_, target, stats);
            }
        });
    }

    private void openWith(Player viewer, OfflinePlayer target, PlayerStats stats) {
        ConfigurationSection items = main.getMenus().config().getConfigurationSection("stats-menu.items");
        int size = main.getMenus().config().getInt("stats-menu.size", 36);
        size = Math.max(9, Math.min(54, (size + 8) / 9 * 9));
        String title = Menus.tr("stats-menu.title", "name", stats.getName());
        StatsHolder holder = new StatsHolder();
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inv);
        fill(inv, size);
        if (items != null) {
            for (String statId : items.getKeys(false)) {
                ConfigurationSection item = items.getConfigurationSection(statId);
                if (item == null) {
                    continue;
                }
                int slot = item.getInt("slot", -1);
                if (slot < 0 || slot >= size) {
                    continue;
                }
                inv.setItem(slot, icon(item, statId, stats, target));
            }
        }
        viewer.openInventory(inv);
    }

    private ItemStack icon(ConfigurationSection item, String statId, PlayerStats stats, OfflinePlayer target) {
        String materialName = item.getString("material", "PAPER");
        ItemStack stack = TTMaterial.itemFromName(materialName, 1);
        if (stack == null) {
            stack = TTMaterial.PAPER.item();
        }
        String value = valueOf(statId, stats, target);
        String name = translate(item.getString("name", "&f%value%"), value);
        List<String> lore = new ArrayList<String>();
        for (String line : item.getStringList("lore")) {
            lore.add(translate(line, value));
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (!lore.isEmpty()) {
                meta.setLore(lore);
            }
            MenuItems.hideVanillaInfo(meta);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * Replaces {@code %value%} in a menu template line and translates
     * {@code &} color codes. Unlike {@code Menus.tr} (which looks up a key in
     * menus.yml), this works on the raw template string read from the item
     * config, so the colors and the value actually show.
     */
    private static String translate(String template, String value) {
        if (template == null) {
            return "";
        }
        String out = template.replace("%value%", value == null ? "" : value);
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', out);
    }

    /** Display value of a stat id for this player. */
    private String valueOf(String statId, PlayerStats stats, OfflinePlayer target) {
        if ("kd".equals(statId)) {
            return String.format(Locale.US, "%.2f", stats.kd());
        }
        if ("coins".equals(statId)) {
            if (target != null && main.getCoinsManager() != null) {
                return main.getCoinsManager().format(main.getCoinsManager().getCoins(target.getUniqueId()));
            }
            return "0";
        }
        if (PlayerStats.isDoubleColumn(statId)) {
            return String.format(Locale.US, "%.2f", stats.doubleValue(statId));
        }
        return String.valueOf(stats.value(statId));
    }

    private void fill(Inventory inv, int size) {
        ItemStack pane = TTMaterial.GRAY_STAINED_GLASS_PANE.item();
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            pane.setItemMeta(meta);
        }
        for (int slot = 0; slot < size; slot++) {
            inv.setItem(slot, pane.clone());
        }
    }
}
