package com.thetowers.scoreboard;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;


/**
 * A per-player sidebar scoreboard, updated without flicker.
 *
 * <p>1.12 constraint: a score line (the "fake player") is limited to 16
 * characters, and two identical lines (e.g. two empty lines) would merge.
 * The technique used here avoids both: each line is a distinct {@link Team}
 * whose <b>entry</b> is a unique, invisible color code (so no collision, even
 * between empty lines), and the visible text is carried by the team's
 * <b>prefix</b> + <b>suffix</b>, the trailing color of the prefix being
 * carried over to the suffix: up to 32 characters displayed (16 + 16), the
 * hard limits of 1.12.
 *
 * <p>Scoreboard signatures <b>deprecated only in later versions</b> (like
 * {@code Sign.getLines} elsewhere in the project): on 1.12.2 the
 * {@code registerNewObjective(String, String)} signature is the only one (the
 * {@code Criteria}-based variant does not exist yet), and the {@code String}
 * flavors of {@code setDisplayName/setPrefix/setSuffix} are the standard API
 * (Paper later replaced them with {@code Component} variants). Everything is
 * isolated here: one single place to fix if one of them disappears.
 *
 * <p>Accepted limitation without NMS: the small red numbers on the right (the
 * score value) remain visible. Hiding them would require version-specific
 * packets (forbidden here).
 */
public class Sidebar {

    private static final int MAX_LINES = 15;

    private final Scoreboard board;
    private final Objective objective;
    private final String[] entries = new String[MAX_LINES];
    private int shownLines;

    @SuppressWarnings("deprecation")
    public Sidebar(String title) {
        board = Bukkit.getScoreboardManager().getNewScoreboard();
        objective = board.registerNewObjective("thetowers", "dummy");
        objective.setDisplayName(cut(title, titleLimit()));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        // One team + one unique invisible entry per line, created up front.
        for (int i = 0; i < MAX_LINES; i++) {
            String entry = ChatColor.values()[i].toString() + ChatColor.RESET;
            entries[i] = entry;
            Team team = board.registerNewTeam("line" + i);
            team.addEntry(entry);
        }
    }

    /** Applies this scoreboard to the player. */
    public void apply(Player player) {
        player.setScoreboard(board);
    }

    /** Updates the title and the lines (top to bottom). */
    @SuppressWarnings("deprecation")
    public void update(String title, List<String> lines) {
        objective.setDisplayName(cut(title, titleLimit()));

        int count = Math.min(lines.size(), MAX_LINES);
        for (int i = 0; i < count; i++) {
            Team team = board.getTeam("line" + i);
            if (team == null) {
                continue;
            }
            setLine(team, lines.get(i));
            // Descending scores: the first line (i=0) sits at the very top.
            objective.getScore(entries[i]).setScore(count - i);
        }
        // Lines left over from the previous render: removed from the display.
        for (int i = count; i < shownLines; i++) {
            board.resetScores(entries[i]);
        }
        shownLines = count;
    }

    /**
     * Splits a line across prefix and suffix (16 characters each, the hard
     * 1.12 limit) without cutting a color code in two, and carrying the
     * current color over to the suffix.
     */
    @SuppressWarnings("deprecation")
    private void setLine(Team team, String line) {
        if (line == null) {
            line = "";
        }
        String prefix;
        String suffix;
        int part = partLimit();
        if (line.length() <= part) {
            prefix = line;
            suffix = "";
        } else {
            int at = part;
            // Do not split in the middle of a color code (a section character
            // must never end a prefix alone).
            if (line.charAt(part - 1) == ChatColor.COLOR_CHAR) {
                at = part - 1;
            }
            prefix = line.substring(0, at);
            suffix = ChatColor.getLastColors(prefix) + line.substring(at);
            if (suffix.length() > part) {
                suffix = cut(suffix, part);
            }
        }
        team.setPrefix(prefix);
        team.setSuffix(suffix);
    }

    /**
     * Maximum length of a team prefix or suffix: the hard 1.12 limit of 16
     * characters, so a line displays up to 32 characters (16 + 16). Longer
     * lines (e.g. filled by other plugins' placeholders) are truncated.
     */
    private static int partLimit() {
        return 16;
    }

    /** Maximum title length: the hard 1.12 limit of 32 characters. */
    private static int titleLimit() {
        return 32;
    }

    /** Cuts at {@code max} characters, leaving no orphan color code at the end. */
    private String cut(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        String cut = text.substring(0, max);
        return cut.charAt(cut.length() - 1) == ChatColor.COLOR_CHAR ? cut.substring(0, cut.length() - 1) : cut;
    }
}
