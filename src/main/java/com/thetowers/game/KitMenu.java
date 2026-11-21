package com.thetowers.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.compat.Enchants;
import com.thetowers.compat.Sounds;
import com.thetowers.compat.TTMaterial;
import com.thetowers.lang.Messages;
import com.thetowers.util.MenuItems;

/**
 * Kit selection menu: one icon per kit, with its description, contents and
 * state for this player (selected, available, or what remains to unlock it,
 * with the progress shown).
 *
 * <p>Kits are laid out in a 7-column grid surrounded by a border (up to 28
 * kits). No call to any InventoryView method.
 *
 * <p>Hypixel-style rendering: a kit whose non-price requirements are NOT met
 * shows as a red stained-glass pane named {@code ???} with only its unlock
 * requirements listed - the real name and contents stay hidden until the
 * player qualifies. Once qualified, the real icon, name and contents show,
 * with the price and a {@code Click to purchase} line separated by an empty
 * line.
 */
public class KitMenu {

    private static final int COLUMNS = 7;
    private static final int MAX_ROWS = 6;

    private final Main main;

    public KitMenu(Main main) {
        this.main = main;
    }

    public void open(Player player) {
        KitManager manager = main.getKitManager();
        List<KitDefinition> kits = manager.getKits();
        int rows = Math.min(MAX_ROWS, 2 + (kits.size() + COLUMNS - 1) / COLUMNS);
        int capacity = (rows - 2) * COLUMNS;

        KitHolder holder = new KitHolder();
        Inventory inv = Bukkit.createInventory(holder, rows * 9, Messages.tr("kits.title"));
        holder.setInventory(inv);
        fill(inv);

        KitDefinition selected = manager.getSelected(player);
        for (int i = 0; i < kits.size() && i < capacity; i++) {
            KitDefinition kit = kits.get(i);
            int slot = 9 * (1 + i / COLUMNS) + 1 + i % COLUMNS;
            holder.bind(slot, kit.getId());
            inv.setItem(slot, icon(player, kit, kit.getId().equals(selected.getId())));
        }
        player.openInventory(inv);
    }

    /** Click in the menu: selects the kit if it is unlocked. */
    public void handleClick(Player player, KitHolder holder, int slot) {
        KitDefinition kit = main.getKitManager().getKit(holder.kitAt(slot));
        if (kit == null) {
            return;
        }
        choose(player, kit);
        player.closeInventory();
    }

    /**
     * Selects a kit (menu or /tt kit <kit>) and notifies the player.
     *
     * <p>A kit is either REVEALED (every requirement met, including the one-time
     * coin cost - or an admin bypass / the default kit) or LOCKED. A revealed kit
     * is only ever selected, for free. A locked kit can only be bought when the
     * coin cost is the SOLE missing requirement (the rest - permission, stats,
     * level, conditions - is met): clicking it then pays the cost ONCE, records
     * the purchase (so it is never charged again) and selects the kit - that
     * purchase is exactly why the kit was locked. If any earned requirement is
     * still missing the kit stays locked and nothing is charged. The chosen kit
     * then persists and is given every respawn for free, until the player
     * switches.
     */
    public void choose(Player player, KitDefinition kit) {
        KitManager manager = main.getKitManager();
        Arena arena = main.getArenaManager().getArenaOf(player);
        boolean inGame = arena != null && arena.isParticipant(player);

        if (manager.requirementsMet(player, kit)) {
            // Revealed (paid / admin / free / default): only ever select, free.
            if (!manager.select(player, kit)) {
                manager.playLockedSound(player);
                player.sendMessage(Main.PREFIX + Messages.tr("kits.locked_notice"));
                return;
            }
            player.sendMessage(Main.PREFIX + Messages.tr(inGame ? "kits.selected_next_life" : "kits.selected",
                    "kit", kit.getDisplayName()));
            return;
        }
        // Locked: the only way forward is to BUY the one-time cost, and only when
        // every non-cost requirement is already met (otherwise the kit stays
        // locked and nothing is charged). A free kit is never bought here.
        if (!manager.nonCostRequirementsMet(player, kit) || kit.getPrice() <= 0d) {
            manager.playLockedSound(player);
            player.sendMessage(Main.PREFIX + Messages.tr("kits.locked_notice"));
            return;
        }
        // Stats must be loaded (cached) before we charge: addPurchasedKit and
        // select both operate on the live cache, and we never want to charge a
        // profile that is still loading async (right after join). The player
        // can retry a moment later, nothing charged.
        if (main.getStatsManager().getCached(player.getUniqueId()) == null) {
            manager.playLockedSound(player);
            player.sendMessage(Main.PREFIX + Messages.tr("kits.locked_notice"));
            return;
        }
        com.thetowers.economy.EconomyHook eco = main.getEconomyHook();
        if (eco == null || !eco.available()) {
            manager.playLockedSound(player);
            player.sendMessage(Main.PREFIX + Messages.tr("kits.buy_no_economy"));
            return;
        }
        double price = kit.getPrice();
        double balance = eco.balance(player);
        if (balance < price || !eco.withdraw(player, price)) {
            manager.playLockedSound(player);
            player.sendMessage(Main.PREFIX + Messages.tr("kits.coins_requirement_missing",
                    "price", eco.format(price), "current", eco.format(Math.max(0d, balance))));
            return;
        }
        // Mark the kit OWNED first (addPurchasedKit updates the live cache, so
        // isPaidFor flips true), THEN select. select() starts by checking
        // isUnlocked -> requirementsMet -> isPaidFor; recording the purchase
        // AFTER select (the old order) made select refuse the not-yet-paid kit,
        // so the buy never completed and the player was refunded to "not
        // unlocked yet". Stats are confirmed loaded above, so select will not
        // hit its stats-not-loaded branch.
        main.getStatsManager().addPurchasedKit(player, kit.getKitId());
        manager.select(player, kit);
        Sounds.kitPurchase(player);
        player.sendMessage(Main.PREFIX + Messages.tr(inGame ? "kits.purchased_next_life" : "kits.purchased",
                "kit", kit.getDisplayName()));
    }

    private ItemStack icon(Player player, KitDefinition kit, boolean selected) {
        KitManager manager = main.getKitManager();
        // "Revealed" = every requirement is met, INCLUDING the one-time coin cost
        // (or an admin bypass / the default kit): the real name/icon show and the
        // kit is only ever selected. A kit missing any requirement (stats, level,
        // conditions, permission, OR the cost not yet paid) is LOCKED - a red
        // pane showing what it takes to reveal (and, if the cost is the only
        // thing missing, a "purchase" action).
        boolean revealed = manager.requirementsMet(player, kit);

        ItemStack item = revealed ? kit.createIcon() : TTMaterial.RED_STAINED_GLASS_PANE.item();
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(revealed ? kit.getDisplayName()
                : ChatColor.RED + ChatColor.stripColor(kit.getDisplayName()));

        List<String> lore = new ArrayList<String>();
        if (revealed) {
            lore.addAll(kit.getDescription());
            lore.add("");
            lore.add(Messages.tr("kits.contents"));
            for (ItemStack content : kit.getItems()) {
                lore.add(Messages.tr("kits.content_line", "amount", content.getAmount(),
                        "item", pretty(content), "ench",
                        content.getEnchantments().isEmpty() ? "" : Messages.tr("kits.enchanted")));
            }
            // Revealed = already unlocked (paid / admin-given / free / default):
            // there is nothing left to pay or to fulfill, so the kit is only ever
            // selected. No cost, no requirements are shown here.
            lore.add("");
            if (selected) {
                lore.add(Messages.tr("kits.status.selected"));
                Enchants.glow(meta);
            } else {
                lore.add(Messages.tr("kits.status.click"));
            }
        } else {
            // Locked: some requirement is not met. Show what it takes to reveal
            // (the cost included, since it is a requirement), then either a
            // "purchase" action (the cost is the only thing missing) or a plain
            // "locked" hint.
            lore.add("");
            lore.add(Messages.tr("kits.locked.label"));
            if (kit.getPermission() != null && !player.hasPermission(kit.getPermission())) {
                lore.add("");
                lore.add(Messages.tr("kits.status.permission"));
            } else {
                lore.add("");
                lore.add(Messages.tr("kits.locked.requirements"));
                for (Map.Entry<String, Integer> requirement : kit.getRequirements().entrySet()) {
                    int current = manager.progress(player, requirement.getKey());
                    int needed = requirement.getValue().intValue();
                    lore.add(Messages.tr("kits.requirement",
                            "color", current >= needed ? ChatColor.GREEN : ChatColor.RED,
                            "label", Messages.tr("kits.stat." + requirement.getKey()),
                            "current", Math.min(current, needed), "needed", needed));
                }
                if (kit.getLevel() > 0) {
                    long cur = manager.currentLevel(player);
                    int needed = kit.getLevel();
                    lore.add(Messages.tr("kits.level_requirement",
                            "color", cur >= needed ? ChatColor.GREEN : ChatColor.RED,
                            "current", Math.min(cur, (long) needed), "needed", needed));
                }
                for (String condition : manager.unmetConditions(player, kit)) {
                    lore.add(Messages.tr("kits.condition",
                            "color", ChatColor.RED, "condition", condition));
                }
                if (kit.getPrice() > 0d) {
                    // The one-time coin cost, as a single "Coins: N" line. Green
                    // once paid or affordable, red while still owed and out of
                    // reach.
                    com.thetowers.economy.EconomyHook eco = main.getEconomyHook();
                    long price = (long) Math.round(kit.getPrice());
                    boolean paid = manager.isPaidFor(player, kit);
                    boolean canAfford = eco != null && eco.available()
                            && eco.balance(player) >= kit.getPrice();
                    lore.add(Messages.tr("kits.coins_cost",
                            "color", (paid || canAfford) ? ChatColor.GREEN : ChatColor.RED,
                            "label", Messages.tr("kits.stat.coins"),
                            "price", String.format("%,d", price)));
                }
            }
            lore.add("");
            // If the cost is the only thing between the player and the kit,
            // clicking buys it (the purchase runs while the kit is locked);
            // otherwise it stays locked.
            boolean onlyCostMissing = manager.nonCostRequirementsMet(player, kit)
                    && kit.getPrice() > 0d && !manager.isPaidFor(player, kit);
            lore.add(Messages.tr(onlyCostMissing ? "kits.status.buy" : "kits.locked.hint"));
        }
        meta.setLore(lore);
        // Damage, protection, enchantments, potions... hidden: the tooltip
        // only shows the kit's name and its lore.
        MenuItems.hideVanillaInfo(meta);
        item.setItemMeta(meta);
        return item;
    }

    /** "BAKED_POTATO" -> "Baked potato": readable without an item translation table. */
    private static String pretty(ItemStack item) {
        String name = item.getType().name().toLowerCase().replace('_', ' ');
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static void fill(Inventory inv) {
        ItemStack pane = TTMaterial.GRAY_STAINED_GLASS_PANE.item();
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            pane.setItemMeta(meta);
        }
        for (int slot = 0; slot < inv.getSize(); slot++) {
            inv.setItem(slot, pane.clone());
        }
    }
}
