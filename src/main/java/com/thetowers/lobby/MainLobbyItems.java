package com.thetowers.lobby;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import com.thetowers.Main;
import com.thetowers.api.PapiBridge;
import com.thetowers.compat.Compat;
import com.thetowers.compat.TTMaterial;
import com.thetowers.config.CustomConfig;
import com.thetowers.util.MenuItems;

/**
 * Custom hotbar items given to players who are in NO arena (the main server
 * lobby, or anywhere outside a game). Fully configurable in
 * {@code lobby-items.yml} under the {@code main-lobby} section: each item is
 * keyed by its hotbar slot (0-8) and has a material, display name, lore and a
 * list of commands run on right OR left click.
 *
 * <p>A command prefixed with {@code console:} runs as the console (with
 * {@code %player%} replaced by the player's name); otherwise it runs as the
 * player. PlaceholderAPI placeholders are resolved in name, lore and commands.
 */
public final class MainLobbyItems {

    private MainLobbyItems() {
    }

    /** True if the main-lobby section has at least one item configured. */
    public static boolean hasItems(Main main) {
        CustomConfig cfg = main.getLobbyItemsConfig();
        return cfg.isSet("main-lobby");
    }

    /**
     * Clears the player's hotbar and places the main-lobby items at their
     * configured slots. Called when a player is sent to the main lobby (join,
     * arena leave, game end).
     */
    public static void give(Main main, Player player) {
        CustomConfig cfg = main.getLobbyItemsConfig();
        if (!cfg.isSet("main-lobby")) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        // Clear the hotbar (slots 0-8) then place the configured items.
        for (int slot = 0; slot < 9; slot++) {
            inventory.setItem(slot, null);
        }
        for (int slot = 0; slot < 9; slot++) {
            String path = "main-lobby." + slot;
            if (!cfg.isSet(path)) {
                continue;
            }
            ItemStack item = buildItem(main, player, cfg, path);
            if (item != null) {
                inventory.setItem(slot, item);
            }
        }
    }

    /**
     * Handles a click on a main-lobby item: looks up the held slot and runs
     * its commands. Returns true if the slot held a main-lobby item (so the
     * caller can cancel the event).
     */
    public static boolean handleClick(Main main, Player player, int heldSlot) {
        CustomConfig cfg = main.getLobbyItemsConfig();
        String path = "main-lobby." + heldSlot;
        if (!cfg.isSet(path)) {
            return false;
        }
        List<String> commands = cfg.getStringList(path + ".commands");
        if (commands.isEmpty()) {
            // A single string instead of a list is also accepted.
            String single = cfg.getString(path + ".commands", null);
            if (single != null && !single.isEmpty()) {
                commands = new ArrayList<String>();
                commands.add(single);
            }
        }
        for (String raw : commands) {
            runCommand(main, player, raw);
        }
        return true;
    }

    /** Builds one main-lobby item from the config (material, name, lore, PAPI). */
    private static ItemStack buildItem(Main main, Player player, CustomConfig cfg, String path) {
        String materialName = cfg.getString(path + ".material", "STONE");
        ItemStack item = TTMaterial.itemFromName(materialName, 1);
        if (item == null) {
            main.getLogger().warning(path + ".material: unknown material ('" + materialName + "').");
            return null;
        }
        String name = cfg.getString(path + ".name", materialName);
        List<String> lore = cfg.getStringList(path + ".lore");
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Compat.hotbarName(resolve(main, player, name)));
            List<String> loreOut = new ArrayList<String>();
            for (String line : lore) {
                loreOut.add(Compat.color(resolve(main, player, line)));
            }
            if (!loreOut.isEmpty()) {
                meta.setLore(loreOut);
            }
            MenuItems.hideVanillaInfo(meta);
            item.setItemMeta(meta);
        }
        return ItemTagger.mark(item);
    }

    /** Resolves our placeholders, then PlaceholderAPI if present. */
    private static String resolve(Main main, Player player, String text) {
        String out = com.thetowers.api.PlaceholderValues.replace(main, player, text);
        if (main.hasPlaceholderApi() && out.indexOf('%') >= 0) {
            out = PapiBridge.apply(player, out);
        }
        return out;
    }

    private static void runCommand(Main main, Player player, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        String cmd = raw.trim();
        boolean console = cmd.toLowerCase(java.util.Locale.ROOT).startsWith("console:");
        if (console) {
            cmd = cmd.substring("console:".length()).trim();
            cmd = cmd.replace("%player%", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } else {
            // Run as the player (without the leading slash).
            player.performCommand(cmd);
        }
    }
}
