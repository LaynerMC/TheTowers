package com.thetowers.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.compat.TTMaterial;
import com.thetowers.lang.Messages;

/**
 * Rules specific to The Towers: scoring by entering an enemy pool, chests
 * reserved for their team, bows possibly forbidden.
 */
public class GameListeners implements Listener {

    private final Main main;

    public GameListeners(Main main) {
        this.main = main;
    }

    /**
     * The heart of the game. Only BLOCK changes are looked at: a head turn or
     * a tenth-of-a-block move cannot enter a pool, so there is no point in
     * looping over the teams for so little (the event fires dozens of times
     * per second per player).
     */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return;
        }
        Player player = event.getPlayer();
        Arena arena = main.getArenaManager().getArenaOf(player);
        if (arena != null && arena.isParticipant(player)) {
            arena.tryMark(player, to);
        }
    }

    /**
     * Team chests: inside a team's chest zone, only its members open the
     * containers.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        Arena arena = main.getArenaManager().getArenaOf(player);
        if (arena == null || !arena.isParticipant(player)) {
            return;
        }
        Block block = event.getClickedBlock();
        String owner = arena.chestZoneOwner(block);
        if (owner == null || owner.equals(arena.getTeam(player))) {
            return;
        }
        // Another team's zone: only containers are blocked (chests, furnaces...),
        // not doors or buttons.
        if (block.getState() instanceof InventoryHolder) {
            event.setCancelled(true);
            player.sendMessage(Main.PREFIX + Messages.tr("game.chest_locked",
                    "color", Arena.colorOf(owner), "team", Arena.displayName(owner)));
        }
    }

    /** Arena without bows: no bow or arrow crafting. */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (recipe == null || recipe.getResult() == null) {
            return;
        }
        Material result = recipe.getResult().getType();
        if (!TTMaterial.BOW.is(result) && !TTMaterial.ARROW.is(result)) {
            return;
        }
        for (HumanEntity viewer : event.getViewers()) {
            if (!(viewer instanceof Player)) {
                continue;
            }
            Arena arena = main.getArenaManager().getArenaOf((Player) viewer);
            if (arena != null && arena.isRunning() && !arena.isAllowBows()) {
                event.getInventory().setResult(new ItemStack(Material.AIR));
                return;
            }
        }
    }
}
