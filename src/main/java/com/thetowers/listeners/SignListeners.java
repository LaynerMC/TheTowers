package com.thetowers.listeners;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.lang.Messages;
import com.thetowers.sign.JoinSign;

/**
 * Join signs: creation ({@code [thetowers]} + arena name or {@code random}),
 * removal (on break), and right click to join.
 *
 * <p>A join sign is registered (its position is tracked) and its content is
 * rewritten as live display, so it is identified by its position, not by its
 * text.
 */
public class SignListeners implements Listener {

    private static final String SIGN_TAG = "[thetowers]";
    /** Old tag, from before the plugin rename: still accepted at creation. */
    private static final String OLD_SIGN_TAG = "[thetower]";
    private static final String ADMIN = "thetowers.admin";

    private final Main main;

    public SignListeners(Main main) {
        this.main = main;
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        String header = ChatColor.stripColor(event.getLine(0)).trim();
        if (!SIGN_TAG.equalsIgnoreCase(header) && !OLD_SIGN_TAG.equalsIgnoreCase(header)) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission(ADMIN)) {
            player.sendMessage(Main.PREFIX + Messages.tr("command.no_permission", "permission", ADMIN));
            return;
        }

        String arg = ChatColor.stripColor(event.getLine(1)).trim();
        String target;
        if ("random".equalsIgnoreCase(arg)) {
            target = "";
            player.sendMessage(Main.PREFIX + Messages.tr("sign.created_random"));
        } else {
            Arena arena = main.getArenaManager().getArena(arg);
            if (arena == null) {
                player.sendMessage(Main.PREFIX + Messages.tr("command.arena_not_found", "name", arg));
                return;
            }
            target = arena.getName();
            player.sendMessage(Main.PREFIX + Messages.tr("sign.created", "arena", arena.getName()));
        }

        Location loc = event.getBlock().getLocation();
        main.getJoinSignManager().register(loc, target);

        // Immediately show the formatted sign (otherwise it would stay
        // "[thetowers]" until the next refresh).
        String[] lines = main.getJoinSignManager().format(main.getJoinSignManager().get(loc));
        for (int i = 0; i < 4; i++) {
            event.setLine(i, lines[i]);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Location loc = event.getBlock().getLocation();
        if (main.getJoinSignManager().get(loc) != null) {
            main.getJoinSignManager().unregister(loc);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        // An admin in placement mode aiming at a sign places a corner; they do not join.
        if (main.getPlacements().isPlacing(event.getPlayer())) {
            return;
        }
        JoinSign sign = main.getJoinSignManager().get(event.getClickedBlock().getLocation());
        if (sign == null) {
            return;
        }
        event.setCancelled(true);
        main.getJoinSignManager().join(event.getPlayer(), sign);
    }
}
