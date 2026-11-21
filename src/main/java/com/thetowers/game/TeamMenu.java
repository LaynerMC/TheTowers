package com.thetowers.game;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.arena.State;
import com.thetowers.lang.Menus;
import com.thetowers.lang.Messages;
import com.thetowers.util.MenuItems;

/**
 * Team selection menu, opened from the waiting lobby's wool block: one wool
 * per team, dyed in its color, with its head count and its player list (one
 * name per lore line, your own marked as "you").
 *
 * <p>The choice is only a PREFERENCE taken into account at game start, within
 * the per-team capacity ({@code /tt setplayersperteam}): players without a
 * choice are spread automatically across the non-full teams. The click
 * already checks the capacity, so the menu stays honest.
 */
public class TeamMenu {

    private final Main main;

    public TeamMenu(Main main) {
        this.main = main;
    }

    /**
     * Opens the menu for a player in the waiting lobby. If the arena is in a
     * full game, the menu is simply not opened: choosing is over.
     */
    public void open(Player player, Arena arena) {
        if (arena == null || !(arena.isState(State.WAITING) || arena.isState(State.STARTING))) {
            return;
        }
        TeamHolder holder = new TeamHolder();
        Inventory inv = Bukkit.createInventory(holder, 9,
                Menus.tr("team-menu.title", "arena", arena.getName()));
        holder.setInventory(inv);

        int[] slots = slotsFor(arena.getTeamCount());
        int i = 0;
        for (String team : arena.activeTeams()) {
            int slot = slots[i++];
            holder.bind(slot, team);
            inv.setItem(slot, icon(arena, team, player));
        }
        player.openInventory(inv);
    }

    /**
     * Click in the menu: records the preference if the team is not full, then
     * reopens the menu to refresh the lists.
     */
    public void handleClick(Player player, TeamHolder holder, int slot) {
        String team = holder.teamAt(slot);
        if (team == null) {
            return;
        }
        Arena arena = main.getArenaManager().getArenaOf(player);
        if (arena == null) {
            player.closeInventory();
            return;
        }
        String problem = arena.chooseTeam(player, team);
        if (problem != null) {
            player.sendMessage(Main.PREFIX + problem);
        } else {
            player.sendMessage(Main.PREFIX + Messages.tr("team.menu_chosen",
                    "color", Arena.colorOf(team), "team", Arena.displayName(team)));
        }
        open(player, arena);
    }

    /** Team wool: colored name, head count, one player per lore line. */
    private ItemStack icon(Arena arena, String team, Player viewer) {
        ItemStack item = Arena.woolOf(team).item();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(Arena.colorOf(team) + Arena.displayName(team));

        List<String> lore = new ArrayList<String>();
        lore.add(Menus.tr("team-menu.players-line",
                "current", arena.teamSize(team), "max", arena.getPlayersPerTeam()));
        lore.add("");
        List<String> members = arena.teamMemberNames(team);
        if (members.isEmpty()) {
            lore.add(Menus.tr("team-menu.empty-line", "color", Arena.colorOf(team)));
        } else {
            for (String name : members) {
                lore.add(Menus.tr(name.equals(viewer.getName()) ? "team-menu.member-you-line"
                        : "team-menu.member-line", "color", Arena.colorOf(team), "player", name));
            }
        }
        lore.add("");
        String chosen = arena.getTeam(viewer);
        if (team.equals(chosen)) {
            lore.add(Menus.tr("team-menu.status-chosen"));
        } else if (members.size() >= arena.getPlayersPerTeam()) {
            lore.add(Menus.tr("team-menu.status-full"));
        } else {
            lore.add(Menus.tr("team-menu.status-click"));
        }
        meta.setLore(lore);
        MenuItems.hideVanillaInfo(meta);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Slots of a 9-slot row for the team count, spaced around the center:
     * 2 teams - 3 and 5; 3 teams - 2, 4 and 6; 4 - 1, 3, 5 and 7.
     */
    private static int[] slotsFor(int teamCount) {
        switch (Math.max(2, Math.min(4, teamCount))) {
            case 2:
                return new int[] { 3, 5 };
            case 3:
                return new int[] { 2, 4, 6 };
            default:
                return new int[] { 1, 3, 5, 7 };
        }
    }
}
