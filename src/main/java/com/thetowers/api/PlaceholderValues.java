package com.thetowers.api;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.game.GameStats;
import com.thetowers.lang.Messages;
import com.thetowers.stats.PlayerStats;
import com.thetowers.task.GameCycle;

/**
 * Values of the {@code %thetowers_...%} placeholders, WITHOUT any dependency
 * on PlaceholderAPI.
 *
 * <p>Separated from {@link Placeholders} (the PAPI expansion) so they serve in
 * two places: the expansion itself, and the configurable scoreboard, which
 * replaces our placeholders itself - they therefore work there even on a
 * server without PlaceholderAPI.
 *
 * <p>Keys: career statistics ({@code kills}, {@code deaths}, {@code wins},
 * {@code losses}, {@code games}, {@code points}, {@code rating}, {@code kd}),
 * current game ({@code arena}, {@code state}, {@code team},
 * {@code team_color}, {@code team_colorcode}, {@code score}, {@code score_<team>}, {@code goal},
 * {@code time}, {@code overtime}, {@code players}, {@code maxplayers},
 * {@code game_kills}, {@code game_deaths}, {@code game_points}), and
 * {@code kit}, {@code date}, {@code ingame}.
 */
public final class PlaceholderValues {

    // Optional "s": the old %thetower_...% prefix (from before the plugin was
    // renamed) is still accepted in scoreboard lines already configured.
    private static final Pattern PLACEHOLDER = Pattern.compile("%thetowers?_([A-Za-z0-9_]+)%");

    private PlaceholderValues() {
    }

    /**
     * Replaces every {@code %thetowers_...%} of a text. An unknown placeholder
     * is left as is (as PlaceholderAPI does).
     */
    public static String replace(Main main, Player player, String text) {
        if (text == null || text.indexOf('%') < 0) {
            return text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        // StringBuffer and not StringBuilder: Matcher.appendReplacement(StringBuilder)
        // only exists from Java 9 on.
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String value = resolve(main, player, matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Value of a placeholder (the part after {@code thetowers_}), or
     * {@code null} if it is unknown.
     */
    public static String resolve(Main main, OfflinePlayer player, String params) {
        if (player == null || params == null) {
            return "";
        }
        String key = params.toLowerCase(Locale.ROOT);

        if ("date".equals(key)) {
            // A new instance on every call: SimpleDateFormat is not
            // thread-safe, and PAPI may call us from an asynchronous thread
            // (tab or scoreboard plugins).
            return new SimpleDateFormat("dd/MM/yy").format(new Date());
        }
        // Long date "Nov 18, 2026" - internal, so the scoreboards do not depend
        // on the PAPI Server expansion (%server_time_...%).
        if ("date_long".equals(key)) {
            return new SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH).format(new Date());
        }

        // --- Server online count (works without the PAPI Server expansion) ---
        if ("online".equals(key)) {
            return String.valueOf(Bukkit.getOnlinePlayers().size());
        }

        // --- Leveling system (separate module) ---
        // Level/EXP/progress variables (%thetowers_level%, %thetowers_exp%,
        // %thetowers_progress_bar% ...). Resolved by the leveling module so
        // this class stays free of leveling logic.
        com.thetowers.leveling.LevelingManager leveling = main.getLevelingManager();
        if (leveling != null) {
            String value = leveling.placeholder(player.getUniqueId(), key);
            if (value != null) {
                return value;
            }
        }

        // --- Career statistics (cached) ---
        PlayerStats stats = main.getStatsManager().getCached(player.getUniqueId());
        if ("kd".equals(key)) {
            return stats == null ? "0.00" : String.format(Locale.US, "%.2f", stats.kd());
        }
        if ("kills".equals(key) || "deaths".equals(key) || "wins".equals(key) || "losses".equals(key)
                || "games".equals(key) || "points".equals(key)
                || "blocks_placed".equals(key) || "blocks_broken".equals(key)
                || "max_kill_streak".equals(key)) {
            return stats == null ? "0" : String.valueOf(stats.value(key));
        }
        if ("damage_dealt".equals(key) || "damage_taken".equals(key)) {
            return stats == null ? "0.00"
                    : String.format(Locale.US, "%.2f", stats.doubleValue(key));
        }

        // --- Coins (Vault economy provided by TheTowers) ---
        if ("coins".equals(key)) {
            if (main.getCoinsManager() == null) {
                return "0";
            }
            return main.getCoinsManager().format(
                    main.getCoinsManager().getCoins(player.getUniqueId()));
        }

        // --- Leaderboards: top_<stat>_<rank>_<period> ---
        // stat = kills|wins|points|money, rank = 1-10, period = alltime|weekly|monthly
        if (key.startsWith("top_")) {
            return resolveTop(main, key);
        }

        // --- Current game (online player) ---
        Player online = player.getPlayer();
        Arena arena = online == null ? null : main.getArenaManager().getArenaOf(online);
        String team = arena == null ? null : arena.getTeam(online);

        if ("kit".equals(key)) {
            return online == null ? "" : ChatColor.stripColor(main.getKitManager().getSelected(online).getDisplayName());
        }
        if ("ingame".equals(key)) {
            return yesNo(arena != null && arena.isRunning());
        }
        if (arena == null) {
            // All the remaining keys concern an arena: empty outside a game,
            // but null if the key does not exist at all.
            return isArenaKey(key) ? "" : null;
        }

        if ("arena".equals(key)) {
            return arena.getName();
        }
        if ("state".equals(key)) {
            return arena.getState().name();
        }
        if ("team".equals(key)) {
            return Arena.displayName(team);
        }
        if ("team_color".equals(key)) {
            return team == null ? "" : Arena.colorOf(team) + Arena.displayName(team);
        }
        if ("team_colorcode".equals(key)) {
            // The raw &-color code of the player's team (&c for red, &9 for
            // blue...). Works in the prelobby once a team is chosen and during
            // the game; empty otherwise.
            return team == null ? "" : "&" + Arena.colorOf(team).getChar();
        }
        if ("score".equals(key)) {
            return team == null ? "" : String.valueOf(arena.getScore(team));
        }
        if (key.startsWith("score_")) {
            String other = Arena.matchTeamName(key.substring("score_".length()));
            return other == null ? null : String.valueOf(arena.getScore(other));
        }
        if ("goal".equals(key)) {
            return String.valueOf(arena.getPointsToWin());
        }
        if ("time".equals(key)) {
            return GameCycle.clock(arena.getTimeLeft());
        }
        if ("overtime".equals(key)) {
            return yesNo(arena.isOvertime());
        }
        if ("players".equals(key)) {
            return String.valueOf(arena.getPlayerCount());
        }
        if ("maxplayers".equals(key)) {
            return String.valueOf(arena.getMaxPlayers());
        }
        if ("game_kills".equals(key) || "game_deaths".equals(key) || "game_points".equals(key)) {
            GameStats game = arena.getGameStats(online);
            if (game == null) {
                return "0";
            }
            int value = "game_kills".equals(key) ? game.getKills()
                    : "game_deaths".equals(key) ? game.getDeaths() : game.getPoints();
            return String.valueOf(value);
        }
        return null;
    }

    /** "yes" / "no" in the server language (keys placeholder.yes / placeholder.no). */
    private static String yesNo(boolean value) {
        return Messages.tr(value ? "placeholder.yes" : "placeholder.no");
    }

    /**
     * Resolves a {@code top_<stat>_<rank>_<period>} placeholder. The output
     * format comes from messages.yml ({@code lobby.top_<stat>} with the
     * variables %rank%, %luckperms_prefix%, %player_name%, %value%); a rank
     * nobody holds yet resolves to {@code lobby.top_empty}.
     */
    private static String resolveTop(Main main, String key) {
        // top_kills_1_alltime -> ["top", "kills", "1", "alltime"]
        String[] parts = key.split("_");
        if (parts.length != 4) {
            return "";
        }
        String stat = parts[1];
        String rankRaw = parts[2];
        String period = parts[3];
        if (!"kills".equals(stat) && !"wins".equals(stat) && !"points".equals(stat)
                && !"money".equals(stat)) {
            return "";
        }
        if (!"alltime".equals(period) && !"weekly".equals(period) && !"monthly".equals(period)) {
            return "";
        }
        int rank;
        try {
            rank = Integer.parseInt(rankRaw);
        } catch (NumberFormatException e) {
            return "";
        }
        if (rank < 1 || rank > 10) {
            return "";
        }
        com.thetowers.tops.TopEntry entry = main.getTopManager() == null
                ? null : main.getTopManager().getEntry(stat, rank, period);
        if (entry == null) {
            return Messages.tr("lobby.top_empty", "rank", Integer.valueOf(rank));
        }
        return Messages.tr("lobby.top_" + stat,
                "rank", Integer.valueOf(rank),
                "luckperms_prefix", entry.getPrefix(),
                "player_name", entry.getName(),
                "value", entry.getValue());
    }

    private static boolean isArenaKey(String key) {
        return "arena".equals(key) || "state".equals(key) || "team".equals(key)
                || "team_color".equals(key) || "team_colorcode".equals(key) || "score".equals(key) || key.startsWith("score_")
                || "goal".equals(key) || "time".equals(key) || "overtime".equals(key)
                || "players".equals(key) || "maxplayers".equals(key) || "game_kills".equals(key)
                || "game_deaths".equals(key) || "game_points".equals(key);
    }
}
