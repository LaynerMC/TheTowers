package com.thetowers.scoreboard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import com.thetowers.arena.Arena;
import com.thetowers.Main;

/**
 * Team color ABOVE the players' heads (the "nametag"), not only in the TAB list.
 *
 * <p>Universal technique, valid for every client version from 1.8.9 to the
 * latest (the server itself is 1.12.2 and ViaVersion translates scoreboard
 * teams): one scoreboard {@link Team} per team, whose prefix is the team color
 * code. Any player added as an "entry" carries the prefix above their head and
 * in the death messages, on all client versions.
 *
 * <p>The teams are registered on the server's MAIN scoreboard, not on the
 * individual sidebars: that way everyone (participants, spectators, players in
 * the main lobby) sees the same colors, and there is only one state to
 * maintain. The team names prefixed with "tt_" avoid collisions with other
 * plugins' teams; ours are non-intrusive (prefix = the color code alone,
 * nothing else).
 *
 * <p>Called once per second from {@link ScoreboardManager#updateAll()}: each
 * team is recomputed on every pass (additions + removals of players who are no
 * longer participants), which makes the state self-repairing after a
 * disconnect or an arena change.
 */
public final class Nametags {

    /** Common prefix of the team names on the main scoreboard. */
    private static final String PREFIX = "tt_";

    private Nametags() {
    }

    /** Refreshes the colored teams on the main scoreboard. */
    public static void update(Main main) {
        if (!main.getScoreboardConfig().getBoolean("nametags.enabled", true)) {
            return;
        }
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();

        // Census: for each team, the participating (online) players of ALL
        // arenas. Two running arenas share the same logical teams (Red, Blue,
        // ...), hence the same colors: no conflict on the main scoreboard.
        List<Set<String>> wanted = new ArrayList<Set<String>>();
        for (int i = 0; i < Arena.TEAM_NAMES.length; i++) {
            wanted.add(new HashSet<String>());
        }
        for (Arena arena : main.getArenaManager().getArenas()) {
            if (!arena.isRunning()) {
                continue;
            }
            for (UUID id : arena.getPlayerIds()) {
                Player player = Bukkit.getPlayer(id);
                if (player == null || !player.isOnline()) {
                    continue;
                }
                String team = arena.getTeam(player);
                if (team == null) {
                    continue;
                }
                for (int i = 0; i < Arena.TEAM_NAMES.length; i++) {
                    if (Arena.TEAM_NAMES[i].equalsIgnoreCase(team)) {
                        wanted.get(i).add(player.getName());
                        break;
                    }
                }
            }
        }

        for (int i = 0; i < Arena.TEAM_NAMES.length; i++) {
            Team team = board.getTeam(PREFIX + Arena.TEAM_NAMES[i].toLowerCase());
            if (team == null) {
                team = board.registerNewTeam(PREFIX + Arena.TEAM_NAMES[i].toLowerCase());
            }
            team.setPrefix(Arena.colorOf(Arena.TEAM_NAMES[i]).toString());

            // Remove the ones no longer in this team...
            Set<String> current = new HashSet<String>(team.getEntries());
            current.removeAll(wanted.get(i));
            for (String name : current) {
                team.removeEntry(name);
            }
            // ...then add the new ones.
            for (String name : wanted.get(i)) {
                if (!team.hasEntry(name)) {
                    try {
                        team.addEntry(name);
                    } catch (IllegalArgumentException ignored) {
                        // Never connected this session (reconnect case):
                        // nothing to color.
                    }
                }
            }
        }
    }

    /** Removes our teams from the main scoreboard (plugin shutdown). */
    public static void clearAll() {
        if (Bukkit.getScoreboardManager() == null) {
            return;
        }
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (String teamName : Arena.TEAM_NAMES) {
            Team team = board.getTeam(PREFIX + teamName.toLowerCase());
            if (team != null) {
                try {
                    team.unregister();
                } catch (IllegalStateException ignored) {
                    // Already removed.
                }
            }
        }
    }
}
