package com.thetowers.scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.arena.State;
import com.thetowers.api.PapiBridge;
import com.thetowers.api.PlaceholderValues;
import com.thetowers.compat.ActionBars;
import com.thetowers.task.GameCycle;

/**
 * One-second recurring display: sidebars, boss bar, team nametags and action
 * bar.
 *
 * <p>Two sidebar phases, BOTH fully defined in
 * config.yml (plus rien dans messages.yml) :
 * <ul>
 *   <li><b>Waiting room (prelobby)</b> ({@code scoreboard-prelobby}): shown while the arena
 *       is WAITING or STARTING (countdown). Shows the map name, the state, the
 *       current/max players and the start countdown. As soon as the game
 *       starts, the in-game scoreboard takes over.</li>
 *   <li><b>In-game</b> ({@code scoreboard}): shown while the arena is RUNNING.
 *       Title, lines and the content of the special lines ({@code {timer}},
 *       {@code {teams}}, {@code {footer}}) all come from config.yml.</li>
 *   <li><b>Hub (lobby)</b> ({@code scoreboard-lobby}): shown to players who are
 *       in NO arena - the main server lobby set with {@code /tt setlobby}, or
 *       anywhere outside a game. Accepts {@code %online%}/{@code %max%}, the
 *       career placeholders {@code %thetowers_...%}, PAPI placeholders and
 *       {@code {footer}} (same footer text as the other scoreboards).</li>
 * </ul>
 *
 * <p>Each line can contain:
 * <ul>
 *   <li>our {@code %thetowers_...%} placeholders, replaced here - they work
 *       without PlaceholderAPI (see {@link PlaceholderValues});</li>
 *   <li>those of any other plugin, if PlaceholderAPI is present;</li>
 *   <li>special lines, alone on their line. In-game: {@code {teams}} (one line
 *       per team with its score), {@code {timer}} (time left or sudden death)
 *       and {@code {footer}}. Lobby: {@code {state}} (Waiting/Starting),
 *       {@code {players}} (current/max), {@code {countdown}} (seconds left or
 *       "waiting for players") and {@code {footer}}.</li>
 * </ul>
 *
 * <p>Attach/detach is lazy: every tick we (re)create the scoreboard of an
 * arena's players and give their normal scoreboard back to anyone who left. See
 * {@link Sidebar} for the line mechanics (the 1.12 length limits).
 */
public class ScoreboardManager {

    private static final String TEAMS = "{teams}";
    private static final String TIMER = "{timer}";
    private static final String FOOTER = "{footer}";
    private static final String STATE = "{state}";
    private static final String PLAYERS = "{players}";
    private static final String COUNTDOWN = "{countdown}";
    private static final String TEAM = "{team}";
    private static final String MAP = "{map}";

    /** Default in-game layout, used if scoreboard.lines is missing/empty. */
    private static final List<String> DEFAULT_GAME_LINES = defaultGameLines();
    /** Default waiting-room layout, used if scoreboard-prelobby.lines is missing/empty. */
    private static final List<String> DEFAULT_WAITING_LINES = defaultWaitingLines();
    /** Default main-lobby (hub) layout, used if scoreboard-lobby.lines is missing/empty. */
    private static final List<String> DEFAULT_HUB_LINES = defaultHubLines();

    private final Main main;
    private final Map<UUID, Sidebar> sidebars = new HashMap<UUID, Sidebar>();
    /** One boss bar per arena (key = arena name in lower case). */
    private final Map<String, ArenaBossBar> bossBars = new HashMap<String, ArenaBossBar>();

    public ScoreboardManager(Main main) {
        this.main = main;
    }

    /** Refreshes every HUD element (called every second). */
    public void updateAll() {
        if (Bukkit.getScoreboardManager() == null) {
            return; // no world loaded: nothing to display.
        }
        // These three run even when the scoreboards are disabled: they have
        // their own switches (bossbar.enabled, nametags.enabled,
        // actionbar.enabled) in config.yml.
        updateBossBars();
        Nametags.update(main);
        updateActionBars();

        boolean gameOn = main.getScoreboardConfig().getBoolean("scoreboard.enabled", true);
        boolean waitingOn = main.isScoreboardPrelobbyEnabled();
        boolean hubOn = main.isScoreboardLobbyEnabled();
        if (!gameOn && !waitingOn && !hubOn) {
            clearAll();
            return;
        }

        // Templates re-read on every refresh: a /tt reload applies immediately.
        String gameTitle = gameOn ? gameTitleTemplate() : null;
        List<String> gameLines = gameOn ? gameLinesTemplate() : null;
        String waitingTitle = waitingOn ? waitingTitleTemplate() : null;
        List<String> waitingLines = waitingOn ? waitingLinesTemplate() : null;
        String hubTitle = hubOn ? hubTitleTemplate() : null;
        List<String> hubLines = hubOn ? hubLinesTemplate() : null;

        Set<UUID> seen = new HashSet<UUID>();

        for (Arena arena : main.getArenaManager().getArenas()) {
            if (arena.isRunning()) {
                if (!gameOn) {
                    continue;
                }
                for (Player player : arena.audience()) {
                    render(seen, player, arena, gameTitle, gameLines, false);
                }
            } else if (arena.isState(State.WAITING) || arena.isState(State.STARTING)) {
                if (!waitingOn) {
                    continue;
                }
                for (Player player : arena.onlinePlayers()) {
                    render(seen, player, arena, waitingTitle, waitingLines, true);
                }
            }
        }

        // Hub: everyone who is in NO arena (main server lobby set with
        // /tt setlobby, or anywhere outside a game). Rendered AFTER the arena
        // loop so a player already covered by their arena is not overwritten,
        // and BEFORE the cleanup below so their sidebar survives it.
        if (hubOn) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (seen.contains(player.getUniqueId())) {
                    continue;
                }
                if (main.getArenaManager().getArenaOf(player) != null) {
                    continue; // arena in a phase not covered above (ending...): keep cleanup.
                }
                renderHub(seen, player, hubTitle, hubLines);
            }
        }

        // Cleanup: give their normal scoreboard back to anyone no longer in an
        // arena (waiting/starting/running) and without the hub scoreboard.
        Iterator<Map.Entry<UUID, Sidebar>> it = sidebars.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Sidebar> entry = it.next();
            if (!seen.contains(entry.getKey())) {
                resetBoard(Bukkit.getPlayer(entry.getKey()));
                it.remove();
            }
        }
    }

    private void render(Set<UUID> seen, Player player, Arena arena,
            String titleTemplate, List<String> lineTemplate, boolean waiting) {
        seen.add(player.getUniqueId());
        String title = resolve(player, titleTemplate);
        Sidebar sidebar = sidebars.get(player.getUniqueId());
        if (sidebar == null) {
            sidebar = new Sidebar(title);
            sidebars.put(player.getUniqueId(), sidebar);
            sidebar.apply(player);
        }
        sidebar.update(title, waiting ? renderWaiting(arena, player, lineTemplate)
                : renderGame(arena, player, lineTemplate));
    }

    /**
     * Sidebar of a player in NO arena: the main server lobby (set with
     * {@code /tt setlobby}) or anywhere outside a game. Same rendering as the
     * waiting-room one, minus the arena-specific markers; {@code {footer}}
     * works the same way (alone on its line, or inline).
     */
    private void renderHub(Set<UUID> seen, Player player,
            String titleTemplate, List<String> lineTemplate) {
        seen.add(player.getUniqueId());
        String title = resolve(player, titleTemplate);
        Sidebar sidebar = sidebars.get(player.getUniqueId());
        if (sidebar == null) {
            sidebar = new Sidebar(title);
            sidebars.put(player.getUniqueId(), sidebar);
            sidebar.apply(player);
        }
        sidebar.update(title, renderHub(player, lineTemplate));
    }

    private List<String> renderHub(Player viewer, List<String> template) {
        // The footer is edited directly in scoreboard.footer (its own & colors);
        // there is no separate footer-format anymore.
        String footerValue = footerValue();

        List<String> lines = new ArrayList<String>();
        for (String raw : template) {
            if (raw == null) {
                continue;
            }
            String marker = raw.trim();
            if (FOOTER.equalsIgnoreCase(marker)) {
                // Footer hidden (empty text): the whole line disappears.
                if (!footerValue.isEmpty()) {
                    lines.add(footerValue);
                }
                continue;
            }
            String line = raw;
            if (containsIgnoreCase(line, FOOTER)) {
                line = replaceIgnoreCase(line, FOOTER, footerValue);
            }
            lines.add(resolve(viewer, line));
        }
        while (!lines.isEmpty() && ChatColor.stripColor(lines.get(lines.size() - 1)).trim().isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /** Removes a player's scoreboard immediately (end of game, /tt leave). */
    public void clear(Player player) {
        if (player == null) {
            return;
        }
        if (sidebars.remove(player.getUniqueId()) != null) {
            resetBoard(player);
        }
    }

    /** Removes every scoreboard and boss bar (plugin stop, scoreboards disabled). */
    public void clearAll() {
        for (UUID id : sidebars.keySet()) {
            resetBoard(Bukkit.getPlayer(id));
        }
        sidebars.clear();
        for (ArenaBossBar bar : bossBars.values()) {
            bar.destroy();
        }
        bossBars.clear();
    }

    private void resetBoard(Player player) {
        if (player != null && player.isOnline() && Bukkit.getScoreboardManager() != null) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    // ================= Boss bar =================

    /** One boss bar per running arena; destroyed as soon as the game ends. */
    private void updateBossBars() {
        Set<String> alive = new HashSet<String>();
        for (Arena arena : main.getArenaManager().getArenas()) {
            if (arena.isRunning()) {
                alive.add(arena.getName().toLowerCase());
                barOf(arena).refresh(arena);
            }
        }
        // Cleanup of arenas that disappeared (delete, reload).
        for (String name : bossBars.keySet().toArray(new String[0])) {
            if (!alive.contains(name)) {
                bossBars.remove(name).destroy();
            }
        }
    }

    private ArenaBossBar barOf(Arena arena) {
        String key = arena.getName().toLowerCase();
        ArenaBossBar bar = bossBars.get(key);
        if (bar == null) {
            bar = new ArenaBossBar(main);
            bossBars.put(key, bar);
        }
        return bar;
    }

    // ================= Action bar (timer) =================

    /** The time left (or sudden death) above the hotbar, once per second. */
    private void updateActionBars() {
        if (!main.getScoreboardConfig().getBoolean("actionbar.enabled", true)) {
            return;
        }
        for (Arena arena : main.getArenaManager().getArenas()) {
            if (!arena.isRunning()) {
                continue;
            }
            String template = arena.isOvertime()
                    ? main.getScoreboardConfig().getString("actionbar.format-sudden-death",
                            "&c&lSudden death &7- the next point wins!")
                    : main.getScoreboardConfig().getString("actionbar.format",
                            "&7Ends in &f%thetowers_time%");
            if (template == null || template.trim().isEmpty()) {
                continue;
            }
            for (Player player : arena.audience()) {
                ActionBars.send(player, resolve(player, template));
            }
        }
    }

    // ================= Templates (config.yml only) =================

    /** In-game title from config.yml. */
    private String gameTitleTemplate() {
        return main.getScoreboardConfig().getString("scoreboard.title", "&e&lTHE TOWERS");
    }

    /** In-game lines from config.yml (default layout if the list is empty). */
    private List<String> gameLinesTemplate() {
        List<String> custom = main.getScoreboardConfig().getStringList("scoreboard.lines");
        return custom == null || custom.isEmpty() ? DEFAULT_GAME_LINES : custom;
    }

    /** Waiting-room (prelobby) title from config.yml. */
    private String waitingTitleTemplate() {
        return main.getScoreboardConfig().getString("scoreboard-prelobby.title", "&e&lTHE TOWERS");
    }

    /** Waiting-room (prelobby) lines from config.yml (default layout if the list is empty). */
    private List<String> waitingLinesTemplate() {
        List<String> custom = main.getScoreboardConfig().getStringList("scoreboard-prelobby.lines");
        return custom == null || custom.isEmpty() ? DEFAULT_WAITING_LINES : custom;
    }

    /** Hub (main server lobby) title from config.yml. */
    private String hubTitleTemplate() {
        return main.getScoreboardConfig().getString("scoreboard-lobby.title", "&e&lTHE TOWERS");
    }

    /** Hub (main server lobby) lines from config.yml (default layout if the list is empty). */
    private List<String> hubLinesTemplate() {
        List<String> custom = main.getScoreboardConfig().getStringList("scoreboard-lobby.lines");
        return custom == null || custom.isEmpty() ? DEFAULT_HUB_LINES : custom;
    }

    private static List<String> defaultGameLines() {
        List<String> lines = new ArrayList<String>();
        lines.add("&7         %thetowers_date_long%");
        lines.add("&f");
        lines.add("{timer}");
        lines.add("&fGoal: &e%thetowers_goal% pts");
        lines.add("&f");
        lines.add("{teams}");
        lines.add("&f");
        lines.add("&fKills: &a%thetowers_game_kills%");
        lines.add("&fDeaths: &c%thetowers_game_deaths%");
        lines.add("&fPoints: &a%thetowers_game_points%");
        lines.add("&f");
        lines.add("&6    &nplay.yourserver.net&r");
        return lines;
    }

    private static List<String> defaultWaitingLines() {
        List<String> lines = new ArrayList<String>();
        lines.add("&7         %thetowers_date_long%");
        lines.add("&f");
        lines.add("&fStatus: {state}");
        lines.add("&f");
        lines.add("&fYour team: {team}");
        lines.add("&f");
        lines.add("&fChoose your team");
        lines.add("&fthrough the item");
        lines.add("&fin your inventory!");
        lines.add("&f");
        lines.add("&fMap: &a{map}");
        lines.add("&fPlayers: &a{players}");
        lines.add("&f");
        lines.add("&6    &nplay.yourserver.net&r");
        return lines;
    }

    private static List<String> defaultHubLines() {
        List<String> lines = new ArrayList<String>();
        lines.add("&7         %thetowers_date_long%");
        lines.add("&f");
        lines.add("&fRank: %luckperms_prefix%");
        lines.add("&fCoins: &6%thetowers_coins%");
        lines.add("&f");
        lines.add("&fLevel: %thetowers_level_format%");
        lines.add("&f");
        lines.add("&fProgress: &b%thetowers_exp_formatted_short%&7/&a%thetowers_exp_required_formatted_short%");
        lines.add("%thetowers_exp_progress_bar% %thetowers_exp_progress_percentage%");
        lines.add("&f");
        lines.add("&fYour ping: &a%player_ping%ms");
        lines.add("&fOnline: &a%thetowers_online%");
        lines.add("&f");
        lines.add("&6    &nplay.yourserver.net&r");
        return lines;
    }

    // ================= Line building =================

    private List<String> renderGame(Arena arena, Player viewer, List<String> template) {
        List<String> lines = new ArrayList<String>();
        for (String raw : template) {
            String marker = raw == null ? "" : raw.trim();
            if (TEAMS.equalsIgnoreCase(marker)) {
                addTeamLines(lines, arena, viewer);
            } else if (TIMER.equalsIgnoreCase(marker)) {
                lines.add(arena.isOvertime()
                        ? cfgText("scoreboard.overtime", "&c&lSudden death!")
                        : cfgText("scoreboard.time_left", "&fEnds in &a%time%",
                                "time", GameCycle.clock(arena.getTimeLeft())));
            } else if (FOOTER.equalsIgnoreCase(marker)) {
                String footer = footerValue();
                if (!footer.isEmpty()) {
                    lines.add(footer);
                }
            } else {
                lines.add(resolve(viewer, raw));
            }
        }
        // Trailing empty lines (typically: footer hidden) are removed so the
        // scoreboard does not end with a blank gap.
        while (!lines.isEmpty() && ChatColor.stripColor(lines.get(lines.size() - 1)).trim().isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private List<String> renderWaiting(Arena arena, Player viewer, List<String> template) {
        // Values of the special lines, computed once for all the lines.
        // {state} reads its own scoreboard-prelobby.state-* text (Hypixel-style
        // "Waiting..." / "Starting..."), separate from the plain sign labels.
        String stateValue = arena.isState(State.STARTING)
                ? cfgText("scoreboard-prelobby.state-starting", "&6Starting...")
                : cfgText("scoreboard-prelobby.state-waiting", "&eWaiting...");
        String playersValue = arena.getPlayerCount() + "/" + arena.getMaxPlayers();
        String team = arena.getTeam(viewer);
        String teamValue = team == null
                ? cfgText("scoreboard-prelobby.no-team", "&7None")
                : Arena.colorOf(team) + Arena.displayName(team);
        int left = arena.getCountdownLeft();
        String countdownValue = arena.isState(State.STARTING) && left > 0
                ? cfgText("scoreboard-prelobby.countdown", "&fStarts in &e%time%",
                        "time", GameCycle.clock(left))
                : cfgText("scoreboard-prelobby.waiting", "&7Waiting for players...");
        String footerValue = footerValue();
        // {map}: the arena's name (the map the player is waiting on).
        String mapValue = arena.getName();

        List<String> lines = new ArrayList<String>();
        for (String raw : template) {
            if (raw == null) {
                continue;
            }
            String marker = raw.trim();
            // Special line alone: its value, nothing else.
            if (STATE.equalsIgnoreCase(marker)) {
                lines.add(stateValue);
                continue;
            }
            if (PLAYERS.equalsIgnoreCase(marker)) {
                lines.add(playersValue);
                continue;
            }
            if (TEAM.equalsIgnoreCase(marker)) {
                lines.add(teamValue);
                continue;
            }
            if (MAP.equalsIgnoreCase(marker)) {
                lines.add(mapValue);
                continue;
            }
            if (COUNTDOWN.equalsIgnoreCase(marker)) {
                lines.add(countdownValue);
                continue;
            }
            if (FOOTER.equalsIgnoreCase(marker)) {
                // Footer hidden (empty text): the whole line disappears.
                if (!footerValue.isEmpty()) {
                    lines.add(footerValue);
                }
                continue;
            }
            // Markers IN THE MIDDLE of a line ("&fState: &e{state}"): replaced
            // in place, the rest of the line is kept.
            String line = raw;
            if (containsIgnoreCase(line, STATE)) {
                line = replaceIgnoreCase(line, STATE, stateValue);
            }
            if (containsIgnoreCase(line, PLAYERS)) {
                line = replaceIgnoreCase(line, PLAYERS, playersValue);
            }
            if (containsIgnoreCase(line, TEAM)) {
                line = replaceIgnoreCase(line, TEAM, teamValue);
            }
            if (containsIgnoreCase(line, MAP)) {
                line = replaceIgnoreCase(line, MAP, mapValue);
            }
            if (containsIgnoreCase(line, COUNTDOWN)) {
                line = replaceIgnoreCase(line, COUNTDOWN, countdownValue);
            }
            if (containsIgnoreCase(line, FOOTER)) {
                line = replaceIgnoreCase(line, FOOTER, footerValue);
            }
            lines.add(resolve(viewer, line));
        }
        while (!lines.isEmpty() && ChatColor.stripColor(lines.get(lines.size() - 1)).trim().isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /** True if {@code text} contains {@code marker}, ignoring case. */
    private static boolean containsIgnoreCase(String text, String marker) {
        return text.toLowerCase(java.util.Locale.ROOT).indexOf(marker.toLowerCase(java.util.Locale.ROOT)) >= 0;
    }

    /** Replaces the FIRST occurrence of {@code marker} (case-insensitive). */
    private static String replaceIgnoreCase(String text, String marker, String value) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        int at = lower.indexOf(marker.toLowerCase(java.util.Locale.ROOT));
        if (at < 0) {
            return text;
        }
        return text.substring(0, at) + value + text.substring(at + marker.length());
    }

    /** Initial of a team's display name (R, B... or R, B, Y, G). */
    private static String letterOf(String team) {
        String shown = ChatColor.stripColor(Arena.displayName(team));
        return shown.isEmpty() ? "" : shown.substring(0, 1);
    }

    private void addTeamLines(List<String> lines, Arena arena, Player viewer) {
        String myTeam = arena.getTeam(viewer);
        for (String team : arena.activeTeams()) {
            String you = team.equals(myTeam)
                    ? cfgText("scoreboard.you_suffix", " &eYOU") : "";
            lines.add(cfgText("scoreboard.team_line",
                    "%color%%letter% &f%team% &a%score%&7/%goal%%you%",
                    "color", Arena.colorOf(team), "letter", letterOf(team),
                    "team", Arena.displayName(team), "score", arena.getScore(team),
                    "goal", arena.getPointsToWin(), "you", you));
        }
    }

    /**
     * Fills a free line: our placeholders first (without PAPI), then those of
     * other plugins if PlaceholderAPI is present, finally the {@code &} colors
     * (last, to also color what the placeholders returned).
     */
    private String resolve(Player viewer, String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        // %online% / %max%: useful for the prelobby scoreboard (the career
        // %thetowers_...% stats go through PlaceholderValues right after).
        String text = line
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%max%", String.valueOf(Bukkit.getMaxPlayers()));
        text = PlaceholderValues.replace(main, viewer, text);
        if (main.hasPlaceholderApi() && text.indexOf('%') >= 0) {
            // Isolation guard: PapiBridge is never called without PAPI.
            text = PapiBridge.apply(viewer, text);
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /**
     * The footer text, edited directly in {@code scoreboard.footer} (its own
     * {@code &} colors). No {@code footer-format} wrapping anymore: empty
     * means the footer line is hidden.
     */
    private String footerValue() {
        String footer = main.getScoreboardConfig().getString("scoreboard.footer", "");
        if (footer == null || footer.isEmpty()) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', footer);
    }

    /**
     * A config.yml text with %name% variables and & colors - the small
     * equivalents of what Messages.tr did for the scoreboard keys, now that
     * they all live in config.yml.
     */
    private String cfgText(String path, String def, Object... placeholders) {
        String raw = main.getScoreboardConfig().getString(path, def);
        if (raw == null) {
            raw = def;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace("%" + placeholders[i] + "%", String.valueOf(placeholders[i + 1]));
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }
}
