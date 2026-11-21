package com.thetowers.listeners;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.api.PapiBridge;

/**
 * Game chat, with separate formats for the lobby and the in-game phase.
 *
 * <p>During a game, a normal message is read only by the author's team; a
 * message starting with {@code chat.all-prefix} ({@code !}) is read by
 * everyone. Before the start and on the end screen, chat is arena-wide. A
 * spectator reads everything, but their messages are read only by other
 * spectators.
 *
 * <p>"Everyone" depends on {@code chat.scope}: {@code arena} (the players and
 * spectators of the game; in-game players no longer receive hub chat, and vice
 * versa) or {@code world} (the players in the author's world).
 *
 * <p>Three chat formats are configurable in config.yml: {@code chat.lobby-format}
 * (outside any arena: the main server lobby set with /tt setlobby),
 * {@code chat.prelobby-format} (an arena's WAITING room, before the game
 * starts) and {@code chat.game-format} (running game). All
 * accept & colors and any PlaceholderAPI placeholder
 * ({@code %player_name%}, {@code %luckperms_prefix%}...). {@code %message%} is
 * replaced with what the player typed. A format left empty uses the next one
 * down (prelobby -> lobby -> server default); the plugin still filters
 * recipients and adds the team tag. PlaceholderAPI placeholders resolve only if
 * PAPI is installed; {@code %player_name%} resolves even without it.
 *
 * <p>The event arrives on an async thread while the arena state belongs to the
 * main thread: the decision is taken there (see {@link #decide}), the event
 * only applies the result.
 */
public class ChatListeners implements Listener {

    /** Max wait for the decision; beyond it, the message passes through as is. */
    private static final long DECISION_TIMEOUT_MS = 2000L;

    private final Main main;
    /** Players whose default chat is "everyone" (/tt chat all). */
    private final Set<UUID> defaultAll = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

    public ChatListeners(Main main) {
        this.main = main;
    }

    /** Chooses a player's default chat: team or everyone. */
    public void setDefaultAll(Player player, boolean all) {
        if (all) {
            defaultAll.add(player.getUniqueId());
        } else {
            defaultAll.remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!main.isChatEnabled()) {
            return;
        }
        final Player player = event.getPlayer();
        final String message = event.getMessage();

        Decision decision;
        if (Bukkit.isPrimaryThread()) {
            decision = decide(player, message);
        } else {
            try {
                decision = Bukkit.getScheduler().callSyncMethod(main, new Callable<Decision>() {
                    @Override
                    public Decision call() {
                        return decide(player, message);
                    }
                }).get(DECISION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                return; // server overloaded or stopping: the message passes through.
            }
        }
        if (decision == null) {
            return;
        }

        if (decision.cancel) {
            event.setCancelled(true);
            return;
        }
        if (!decision.message.equals(message)) {
            event.setMessage(decision.message);
        }
        if (decision.recipients != null) {
            Iterator<Player> it = event.getRecipients().iterator();
            while (it.hasNext()) {
                if (!decision.recipients.contains(it.next().getUniqueId())) {
                    it.remove();
                }
            }
        }
        event.setFormat(buildFormat(player, decision));
    }

    /**
     * Builds the chat format string for this message. Uses the configured
     * lobby format (outside any arena: main server lobby; falls back to the
     * prelobby format when empty), the prelobby format (waiting room) or the
     * game format (running game, with PlaceholderAPI resolved on the sender);
     * falls back to the
     * server's default format (just the tag prepended) when the relevant
     * format is empty.
     */
    private String buildFormat(Player sender, Decision decision) {
        String template;
        if (decision.hub) {
            // LOBBY (main server lobby): dedicated format; empty -> waiting format.
            template = main.getChatLobbyFormat();
            if (template == null || template.isEmpty()) {
                template = main.getChatPrelobbyFormat();
            }
        } else if (!decision.gameFormat) {
            // PRELOBBY (waiting room): dedicated format; empty -> lobby format.
            template = main.getChatPrelobbyFormat();
            if (template == null || template.isEmpty()) {
                template = main.getChatLobbyFormat();
            }
        } else {
            template = main.getChatGameFormat();
        }
        String tag = decision.tag == null ? "" : decision.tag;

        if (template == null || template.isEmpty()) {
            // No custom format: keep the server's default, just prepend the tag.
            // The existing format uses %1$s/%2$s: a % in the tag must be doubled.
            return tag.replace("%", "%%") + eventFormatPlaceholder();
        }

        // %player_name% resolves even without PAPI; other placeholders via PAPI.
        String resolved = template.replace("%player_name%", sender.getName());
        if (main.hasPlaceholderApi() && resolved.indexOf('%') >= 0) {
            resolved = PapiBridge.apply(sender, resolved);
        }
        // %message% becomes the String.format slot for argument 2 (the message).
        resolved = resolved.replace("%message%", "%2$s");
        // Escape any other stray % so String.format does not choke, keeping %2$s.
        resolved = resolved.replace("%2$s", "\u0001").replace("%", "%%").replace("\u0001", "%2$s");
        resolved = ChatColor.translateAlternateColorCodes('&', resolved);
        // The tag already carries section-sign colors (from Messages.tr); prepend
        // it as is.
        return tag + resolved;
    }

    /** The server's default format slot, kept for the empty-format fallback. */
    private static String eventFormatPlaceholder() {
        return "%1$s %2$s";
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        defaultAll.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Who reads this message, with which tag. Call on the main thread.
     * {@code null} = chat is not touched.
     */
    private Decision decide(Player sender, String message) {
        boolean world = "world".equals(main.getChatScope());
        Arena arena = main.getArenaManager().getArenaOf(sender);
        Decision decision = new Decision(message);

        if (arena == null) {
            if (world) {
                return null;
            }
            // Outside a game: neither read by those playing, nor reading them.
            Set<UUID> outside = new HashSet<UUID>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (main.getArenaManager().getArenaOf(online) == null) {
                    outside.add(online.getUniqueId());
                }
            }
            decision.recipients = outside;
            // LOBBY (main server lobby): dedicated chat format.
            decision.hub = true;
            return decision;
        }

        if (arena.isSpectator(sender)) {
            decision.recipients = ids(arena.onlineSpectators(), sender);
            decision.tag = ChatColor.translateAlternateColorCodes('&', main.getChatTagSpectator());
            return decision;
        }

        String team = arena.getTeam(sender);
        if (team == null || !arena.isRunning()) {
            // Waiting, countdown, end screen: arena-wide chat.
            decision.recipients = everyone(arena, sender, world);
            decision.tag = team == null ? "" : allTag(team);
            return decision;
        }

        // Running game with a team: in-game format.
        decision.gameFormat = true;
        String prefix = main.getChatAllPrefix();
        boolean prefixed = message.startsWith(prefix);
        if (prefixed) {
            String stripped = message.substring(prefix.length()).trim();
            if (stripped.isEmpty()) {
                decision.cancel = true;
                return decision;
            }
            decision.message = stripped;
        }
        // The prefix inverts the player's default chat.
        boolean toAll = prefixed != defaultAll.contains(sender.getUniqueId());
        if (toAll) {
            decision.recipients = everyone(arena, sender, world);
            decision.tag = allTag(team);
        } else {
            Set<UUID> readers = new HashSet<UUID>();
            for (Player player : arena.onlinePlayers()) {
                if (team.equals(arena.getTeam(player))) {
                    readers.add(player.getUniqueId());
                }
            }
            for (Player spectator : arena.onlineSpectators()) {
                readers.add(spectator.getUniqueId());
            }
            readers.add(sender.getUniqueId());
            decision.recipients = readers;
            decision.tag = tag(main.getChatTagTeam(), Arena.colorOf(team),
                    Arena.displayName(team));
        }
        return decision;
    }

    private String allTag(String team) {
        return tag(main.getChatTagAll(), Arena.colorOf(team), Arena.displayName(team));
    }

    /**
     * A config.yml text ({@code chat.tag-*}, {@code chat.mode-*}) with its
     * %color% / %team% / %prefix% variables filled in and its & codes
     * translated.
     */
    private String tag(String template, ChatColor color, String team) {
        return ChatColor.translateAlternateColorCodes('&', template
                .replace("%color%", color == null ? "" : color.toString())
                .replace("%team%", team == null ? "" : team)
                .replace("%prefix%", main.getChatAllPrefix()));
    }

    /** Recipients of an "everyone" message, per {@code chat.scope}. */
    private Set<UUID> everyone(Arena arena, Player sender, boolean world) {
        if (world) {
            return ids(sender.getWorld().getPlayers(), sender);
        }
        return ids(arena.audience(), sender);
    }

    private static Set<UUID> ids(Iterable<Player> players, Player sender) {
        Set<UUID> ids = new HashSet<UUID>();
        for (Player player : players) {
            ids.add(player.getUniqueId());
        }
        ids.add(sender.getUniqueId());
        return ids;
    }

    /** Result of the decision, computed on the main thread. */
    private static final class Decision {
        String message;
        /** {@code null} = all original recipients. */
        Set<UUID> recipients;
        String tag;
        /** True when the sender is in a running game (uses game-format). */
        boolean gameFormat;
        /** True when the sender is outside any arena (uses lobby-format). */
        boolean hub;
        boolean cancel;

        Decision(String message) {
            this.message = message;
        }
    }
}
