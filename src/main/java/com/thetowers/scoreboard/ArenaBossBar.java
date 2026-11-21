package com.thetowers.scoreboard;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import com.thetowers.arena.Arena;
import com.thetowers.Main;

/**
 * Boss bar of one arena: the score of each team, live above the hotbar
 * ("&cRed 7/10 | &9Blue 5/10"), the progress following the leading team. In
 * sudden death the text switches to the dedicated announcement.
 *
 * <p>Uses the plain Bukkit BossBar API (org.bukkit.boss, available since 1.9,
 * so native on a 1.12.2 server); ViaVersion translates it for newer clients.
 * Created on demand at the first refresh, destroyed when the game ends.
 * Spectators see it too.
 */
public class ArenaBossBar {

    private final Main main;
    private BossBar bar;
    /** Who currently sees the bar (participants + spectators). */
    private final Set<UUID> viewers = new HashSet<UUID>();

    public ArenaBossBar(Main main) {
        this.main = main;
    }

    /** Refreshes the bar for this arena; destroys it when the game is over. */
    public void refresh(Arena arena) {
        boolean enabled = main.getScoreboardConfig().getBoolean("bossbar.enabled", true);
        if (!enabled || !arena.isRunning()) {
            destroy();
            return;
        }
        if (bar == null) {
            bar = Bukkit.createBossBar("",
                    colorOf(main.getScoreboardConfig().getString("bossbar.color", "RED")),
                    styleOf(main.getScoreboardConfig().getString("bossbar.style", "SOLID")));
        }

        // Wanted audience = connected participants + spectators; adjust both ways.
        Set<UUID> wanted = new HashSet<UUID>();
        for (Player player : arena.audience()) {
            wanted.add(player.getUniqueId());
        }
        for (UUID id : viewers.toArray(new UUID[0])) {
            if (!wanted.contains(id)) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    bar.removePlayer(player);
                }
                viewers.remove(id);
            }
        }
        for (UUID id : wanted) {
            if (!viewers.contains(id)) {
                Player player = Bukkit.getPlayer(id);
                if (player != null && player.isOnline()) {
                    bar.addPlayer(player);
                    viewers.add(id);
                }
            }
        }

        if (arena.isOvertime()) {
            bar.setTitle(color(main.getScoreboardConfig().getString("bossbar.sudden-death",
                    "&c&lSudden death &7- the next point wins")));
        } else {
            bar.setTitle(titleFor(arena));
        }
        bar.setProgress(progressOf(arena));
    }

    /** Destroys the bar (end of game, arena reset, plugin stop). */
    public void destroy() {
        if (bar != null) {
            bar.removeAll();
            bar = null;
        }
        viewers.clear();
    }

    // ================= Internal =================

    /** BarColor by its config name, with a fallback when the admin mistyped it. */
    private static BarColor colorOf(String name) {
        try {
            return BarColor.valueOf(name == null ? "" : name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BarColor.WHITE;
        }
    }

    /** BarStyle by its config name, with a fallback when the admin mistyped it. */
    private static BarStyle styleOf(String name) {
        try {
            return BarStyle.valueOf(name == null ? "" : name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BarStyle.SOLID;
        }
    }

    /** The score of each team, joined by the config separator. */
    private String titleFor(Arena arena) {
        String separator = color(main.getScoreboardConfig().getString("bossbar.separator", " &8- "));
        StringBuilder sb = new StringBuilder();
        for (String team : arena.activeTeams()) {
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(Arena.colorOf(team)).append(Arena.displayName(team))
                    .append(" &f").append(arena.getScore(team))
                    .append("&7/").append(arena.getPointsToWin());
        }
        return color(sb.toString());
    }

    /** Progress = best team / goal, clamped to [0, 1]. */
    private double progressOf(Arena arena) {
        int best = 0;
        for (String team : arena.activeTeams()) {
            best = Math.max(best, arena.getScore(team));
        }
        return arena.getPointsToWin() <= 0 ? 0d
                : Math.max(0d, Math.min(1d, (double) best / arena.getPointsToWin()));
    }

    private static String color(String text) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&',
                text == null ? "" : text);
    }
}
