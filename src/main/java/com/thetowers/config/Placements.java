package com.thetowers.config;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.lang.Messages;
import com.thetowers.region.Cuboid;

/**
 * Handling of "placement mode": when the admin asks for a position (lobby,
 * spawn, generator) or a zone (pool, spawn zone, chest zone), the menu closes
 * and what they are placing is remembered. Their next right clicks capture
 * the position(s).
 *
 * <p>For a zone, each corner is the <b>targeted block</b> of the right click
 * (the clicked block), or by default the block under the admin's feet (click
 * into the void). Aiming at the block is far more precise to delimit a pool
 * than standing inside it.
 *
 * <p>Why this detour instead of setting everything in the menu: an inventory
 * cannot say "go stand somewhere". We therefore leave the menu, the admin
 * walks, and their right click acts as the validation.
 */
public class Placements {

    /**
     * Anti double-trigger: since 1.9 a right click raises PlayerInteractEvent
     * twice (main hand + off hand). The time check below absorbs both fires,
     * whatever the hand, without any version-sensitive filtering.
     */
    private static final long DEBOUNCE_MS = 250L;

    /** Safety guard: beyond that, it is almost surely a corner mistake. */
    private static final long MAX_ZONE_VOLUME = 2000000L;

    private final Main main;
    private final Map<UUID, PlacementSession> sessions = new HashMap<UUID, PlacementSession>();
    private final Map<UUID, Long> lastClick = new HashMap<UUID, Long>();

    public Placements(Main main) {
        this.main = main;
    }

    public boolean isPlacing(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    /** Starts a placement session: closes the menu and announces what to place. */
    public void start(Player player, PlacementSession session) {
        main.getArenaNaming().clear(player);
        sessions.put(player.getUniqueId(), session);
        lastClick.remove(player.getUniqueId());
        player.closeInventory();
        prompt(player, session);
    }

    public void cancel(Player player) {
        if (sessions.remove(player.getUniqueId()) != null) {
            player.sendMessage(Main.PREFIX + Messages.tr("config.place.cancelled"));
        }
    }

    /** Removes a session without a message (disconnect). */
    public void clear(Player player) {
        sessions.remove(player.getUniqueId());
        lastClick.remove(player.getUniqueId());
    }

    private void prompt(Player player, PlacementSession session) {
        String what = label(session);
        if (session.getKind().isZone()) {
            player.sendMessage(Main.PREFIX + Messages.tr("config.place.zone_first", "what", what));
        } else {
            player.sendMessage(Main.PREFIX + Messages.tr("config.place.point", "what", what));
        }
    }

    /** Label of what is being placed, for the messages ("the Red team's pool"...). */
    private String label(PlacementSession session) {
        String team = session.getTeam() == null ? "" : session.getTeam();
        String color = Arena.colorOf(team).toString();
        switch (session.getKind()) {
            case LOBBY:
                return Messages.tr("config.place.what.lobby");
            case SPAWN:
                return Messages.tr("config.place.what.spawn", "color", color, "team", Arena.displayName(team));
            case GENERATOR:
                return Messages.tr("config.place.what.generator", "type", session.getGeneratorType());
            case POOL:
                return Messages.tr("config.place.what.pool", "color", color, "team", Arena.displayName(team));
            case SPAWN_ZONE:
                return Messages.tr("config.place.what.spawn_zone", "color", color, "team", Arena.displayName(team));
            case CHEST_ZONE:
                return Messages.tr("config.place.what.chest_zone", "color", color, "team", Arena.displayName(team));
            default:
                return "";
        }
    }

    /**
     * Handles the right click of a player in a placement session. Returns
     * {@code true} if the click was consumed (the listener must then cancel
     * the event).
     *
     * @param clicked the targeted block, or {@code null} for a click into the
     *                void
     */
    public boolean handleInteract(Player player, Block clicked) {
        PlacementSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long previous = lastClick.get(player.getUniqueId());
        if (previous != null && now - previous.longValue() < DEBOUNCE_MS) {
            return true;
        }
        lastClick.put(player.getUniqueId(), Long.valueOf(now));

        Arena arena = main.getArenaManager().getArena(session.getArena());
        if (arena == null) {
            sessions.remove(player.getUniqueId());
            player.sendMessage(Main.PREFIX + Messages.tr("config.place.arena_gone"));
            return true;
        }

        if (session.getKind().isZone()) {
            captureCorner(player, session, arena, clicked);
            return true;
        }

        Location here = player.getLocation();
        switch (session.getKind()) {
            case LOBBY:
                arena.setLobby(here);
                break;
            case SPAWN:
                arena.setSpawn(session.getTeam(), here);
                break;
            case GENERATOR:
                arena.addGenerator(session.getGeneratorType(), here);
                break;
            default:
                break;
        }
        finish(player, arena, session, Messages.tr("config.place.point_done", "what", label(session)));
        return true;
    }

    private void captureCorner(Player player, PlacementSession session, Arena arena, Block clicked) {
        Location corner = clicked != null
                ? clicked.getLocation()
                : player.getLocation().getBlock().getLocation();

        if (session.getFirstCorner() == null) {
            session.setFirstCorner(corner);
            player.sendMessage(Main.PREFIX + Messages.tr("config.place.zone_second",
                    "pos", format(corner)));
            return;
        }

        Cuboid zone = Cuboid.of(session.getFirstCorner(), corner);
        if (zone == null) {
            player.sendMessage(Main.PREFIX + Messages.tr("config.place.zone_other_world"));
            return;
        }
        if (zone.volume() > MAX_ZONE_VOLUME) {
            // The session is not advanced: the admin re-clicks a second corner.
            player.sendMessage(Main.PREFIX + Messages.tr("config.place.zone_too_big",
                    "volume", zone.volume()));
            return;
        }

        switch (session.getKind()) {
            case POOL:
                arena.setPool(session.getTeam(), zone);
                break;
            case SPAWN_ZONE:
                arena.setSpawnZone(session.getTeam(), zone);
                break;
            case CHEST_ZONE:
                arena.setChestZone(session.getTeam(), zone);
                break;
            default:
                break;
        }
        finish(player, arena, session, Messages.tr("config.place.zone_done",
                "what", label(session), "zone", zone.describe()));
    }

    /** Ends a session: saves, informs, and reopens the fitting menu. */
    private void finish(Player player, Arena arena, PlacementSession session, String message) {
        sessions.remove(player.getUniqueId());
        main.getArenaManager().save();
        player.sendMessage(Main.PREFIX + message);
        if (session.getKind() == PlacementSession.Kind.GENERATOR) {
            main.getConfigMenu().openGenerators(player, arena.getName());
        } else if (session.getTeam() != null) {
            main.getConfigMenu().openTeam(player, arena.getName(), session.getTeam());
        } else {
            main.getConfigMenu().openEdit(player, arena.getName());
        }
    }

    private static String format(Location location) {
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }
}
