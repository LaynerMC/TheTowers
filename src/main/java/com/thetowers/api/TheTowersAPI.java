package com.thetowers.api;

import java.util.UUID;

import org.bukkit.entity.Player;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.arena.State;
import com.thetowers.stats.PlayerStats;

/**
 * Public entry point of TheTowers for other plugins.
 *
 * <p>Usage from a third-party plugin (with a {@code softdepend: [TheTowers]}):
 * <pre>
 * PlayerStats s = TheTowersAPI.get().getStats(uuid);
 * boolean playing = TheTowersAPI.get().isInGame(player);
 * </pre>
 *
 * <p>Stability contract: we never hand out our live internal objects. The
 * statistics come out as a {@link PlayerStats#copy() copy}, the arena state
 * comes out as simple values. Nothing here lets a third-party plugin break a
 * game.
 *
 * <p>The stats returned are those of the <b>cache</b> (online players): the
 * load being asynchronous, an offline player returns {@code null}.
 */
public final class TheTowersAPI {

    private static TheTowersAPI instance;

    private final Main main;

    private TheTowersAPI(Main main) {
        this.main = main;
    }

    /** Called once by TheTowers at its startup. */
    public static void init(Main main) {
        instance = new TheTowersAPI(main);
    }

    /** The API instance, or {@code null} if TheTowers is not enabled yet. */
    public static TheTowersAPI get() {
        return instance;
    }

    // ================= Statistics =================

    /** Copy of an online player's stats, or {@code null} if they are offline. */
    public PlayerStats getStats(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        PlayerStats stats = main.getStatsManager().getCached(uuid);
        return stats == null ? null : stats.copy();
    }

    public PlayerStats getStats(Player player) {
        return player == null ? null : getStats(player.getUniqueId());
    }

    // ================= Game state =================

    /** True if the player is in an arena whose game is running. */
    public boolean isInGame(Player player) {
        Arena arena = arenaOf(player);
        return arena != null && arena.isRunning();
    }

    /** Name of the player's arena, or {@code null} if they are in none. */
    public String getArenaName(Player player) {
        Arena arena = arenaOf(player);
        return arena == null ? null : arena.getName();
    }

    /** State of the player's arena, or {@code null} if they are in none. */
    public State getArenaState(Player player) {
        Arena arena = arenaOf(player);
        return arena == null ? null : arena.getState();
    }

    /** The player's team in their game ({@code Red}...), or {@code null}. */
    public String getTeam(Player player) {
        Arena arena = arenaOf(player);
        return arena == null ? null : arena.getTeam(player);
    }

    /** Current score of the player's team, or -1 if not in a game. */
    public int getTeamScore(Player player) {
        Arena arena = arenaOf(player);
        String team = arena == null ? null : arena.getTeam(player);
        return team == null ? -1 : arena.getScore(team);
    }

    private Arena arenaOf(Player player) {
        return player == null ? null : main.getArenaManager().getArenaOf(player);
    }
}
