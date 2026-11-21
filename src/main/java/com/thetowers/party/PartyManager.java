package com.thetowers.party;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

import com.thetowers.Main;
import com.thetowers.arena.Arena;
import com.thetowers.arena.ArenaManager;
import com.thetowers.arena.State;

/**
 * The party system's single entry point.
 *
 * <p>Owns every party, the pending invites (with expiry), the reject cooldowns
 * and the per-player party-chat toggle. Nothing is persisted: a restart wipes
 * every party (by design).
 *
 * <p>The party system is external to the game: it never touches combat or
 * scoring. Its only influence on the game is the join flow, implemented
 * together with {@link Arena#join} and the join entry points: a party member
 * who is not the leader cannot join a game on their own (they get the
 * "Only the leader can join a new game!" message), and when the leader joins a
 * game (by any path: /tt join, /tt randomjoin, a join sign, the lobby plate),
 * the whole party is moved into the SAME arena and put on the SAME team. The
 * resolver ({@link #findPartyTarget}) never splits the party: it finds an arena
 * whose team has room for everyone, trying the arena the leader asked for first
 * and falling back to another joinable arena if that one cannot fit the party.
 *
 * <p>Threading: commands and the arena join run on the main thread. The chat
 * toggle is read from the {@link org.bukkit.event.player.AsyncPlayerChatEvent}
 * handler, which may run async, so the toggle set uses a concurrent structure.
 */
public final class PartyManager {

    private final Main main;
    private PartyConfig config;

    /** Index: any member (leader or not) -> the party they belong to. */
    private final Map<UUID, Party> parties = new HashMap<UUID, Party>();

    /** Pending invites: target -> (inviter -> expire epoch ms). */
    private final Map<UUID, Map<UUID, Long>> invites = new HashMap<UUID, Map<UUID, Long>>();

    /** Reject cooldowns: target -> (inviter -> cooldown-until epoch ms). Memory only. */
    private final Map<UUID, Map<UUID, Long>> rejectCooldowns = new HashMap<UUID, Map<UUID, Long>>();

    /** Players whose normal chat currently goes to their party. */
    private final Set<UUID> partyChatOn = ConcurrentHashMap.newKeySet();

    public PartyManager(Main main) {
        this.main = main;
        this.config = new PartyConfig(main);
    }

    public PartyConfig getConfig() {
        return config;
    }

    /** (Re)reads modules/parties.yml. */
    public void reload() {
        config = new PartyConfig(main);
    }

    // =========================================================================
    //  Reads
    // =========================================================================

    /** The party a player belongs to, or {@code null}. */
    public Party getParty(UUID id) {
        return parties.get(id);
    }

    public boolean isInParty(UUID id) {
        return parties.containsKey(id);
    }

    public boolean isPartyChatOn(UUID id) {
        return partyChatOn.contains(id);
    }

    // =========================================================================
    //  Join gate (used by Arena.join)
    // =========================================================================

    /**
     * The deny message shown to a party member (not the leader) who tries to
     * join a game, or {@code null} if the player may join (no party, or is the
     * leader of their party).
     */
    public String joinGateMessage(Player player) {
        Party party = parties.get(player.getUniqueId());
        if (party == null || party.isLeader(player.getUniqueId())) {
            return null;
        }
        return config.msg("join-blocked-non-leader");
    }

    /** True if {@code player} leads the party they are in (false if no party). */
    public boolean isPartyLeader(Player player) {
        Party party = parties.get(player.getUniqueId());
        return party != null && party.isLeader(player.getUniqueId());
    }

    /** A target arena + team that can fit the whole party on the same team. */
    public static final class PartyTarget {
        public final Arena arena;
        public final String team;
        public PartyTarget(Arena arena, String team) {
            this.arena = arena;
            this.team = team;
        }
    }

    /**
     * Number of online party members who can follow the leader into a game:
     * the leader plus every online member who is NOT mid-game (a member in a
     * PLAYING arena is left alone and not counted, so the target only has to
     * fit the players who will actually move).
     */
    private int partyFollowSize(Player leader, Party party) {
        ArenaManager am = main.getArenaManager();
        int n = 1; // the leader
        for (Player member : party.onlineMembers()) {
            if (member.getUniqueId().equals(leader.getUniqueId())) {
                continue;
            }
            Arena cur = am.getArenaOf(member);
            if (cur != null && cur.getState() == State.PLAYING) {
                continue;
            }
            n++;
        }
        return n;
    }

    /**
     * A team of {@code arena} that has room for {@code size} players, picked at
     * random among the fitting active teams (so parties do not always stack on
     * the same team), or {@code null} if no active team fits or the arena itself
     * is too full.
     */
    private String teamFitting(Arena arena, int size) {
        if (!arena.isJoinable()) {
            return null;
        }
        if (arena.getMaxPlayers() - arena.getPlayerCount() < size) {
            return null;
        }
        List<String> fitting = new ArrayList<String>();
        for (String team : arena.activeTeams()) {
            int free = arena.getPlayersPerTeam() - arena.teamSize(team);
            if (free >= size) {
                fitting.add(team);
            }
        }
        if (fitting.isEmpty()) {
            return null;
        }
        return fitting.get(ThreadLocalRandom.current().nextInt(fitting.size()));
    }

    /**
     * Resolves where a party leader should join so the WHOLE party fits on the
     * SAME team.
     * <ul>
     *   <li>If {@code preferred} is given (the leader ran {@code /tt join X} or
     *       clicked a specific sign), it is tried first: if one of its teams
     *       fits the party, that team is returned.
     *   <li>Otherwise (or if the preferred arena does not fit) every joinable
     *       arena is scanned, fullest first, for one whose team fits the party.
     * </ul>
     * The party is NEVER split: if no joinable arena can fit everyone on one
     * team, {@code null} is returned and the leader is told to try later.
     *
     * @param preferred the arena the leader asked for, or {@code null} for a
     *                  random join (randomjoin / random sign)
     */
    public PartyTarget findPartyTarget(Player leader, Arena preferred) {
        Party party = parties.get(leader.getUniqueId());
        if (party == null) {
            return null;
        }
        int size = partyFollowSize(leader, party);
        if (preferred != null) {
            String team = teamFitting(preferred, size);
            if (team != null) {
                return new PartyTarget(preferred, team);
            }
        }
        Arena best = null;
        String bestTeam = null;
        for (Arena arena : main.getArenaManager().getArenas()) {
            String team = teamFitting(arena, size);
            if (team == null) {
                continue;
            }
            if (best == null || arena.getPlayerCount() > best.getPlayerCount()) {
                best = arena;
                bestTeam = team;
            }
        }
        return best == null ? null : new PartyTarget(best, bestTeam);
    }

    /**
     * Called by the join entry points right after a party leader has joined the
     * resolved arena: assigns the leader (and moves in every other ONLINE
     * member) to {@code team}, so the whole party plays on the SAME team. The
     * choice is a lobby team preference ({@link Arena#chooseTeam}), honored at
     * game start within the per-team capacity (which the resolver already
     * verified fits the whole party), so the party stays together when the game
     * begins. Members mid-game (PLAYING) are left alone; members in another
     * arena are moved out of it first. Uses {@link Arena#joinInternal} directly
     * so the party gate does not block the move (the move is driven by the
     * leader, not by the members).
     */
    public void onLeaderJoinedArena(Player leader, Arena arena, String team) {
        Party party = parties.get(leader.getUniqueId());
        if (party == null) {
            return;
        }
        ArenaManager am = main.getArenaManager();
        // Leader: already in the arena (joinInternal done by the entry point);
        // put them on the chosen team. setTeam (no checks) is used because the
        // capacity was already verified by findPartyTarget and the move is
        // driven by the leader, so chooseTeam's per-member party rule must not
        // block it.
        if (team != null) {
            arena.setTeam(leader, team);
        }
        for (Player member : party.onlineMembers()) {
            if (member.getUniqueId().equals(leader.getUniqueId())) {
                continue;
            }
            Arena cur = am.getArenaOf(member);
            if (cur != null && cur.getState() == State.PLAYING) {
                // Do not pull a member out of a running game.
                continue;
            }
            if (cur != null && cur.getName().equals(arena.getName())) {
                // Already in the target arena (e.g. waiting there too).
            } else {
                if (cur != null) {
                    am.leaveArena(member, false);
                }
                String problem = arena.joinInternal(member);
                if (problem != null) {
                    member.sendMessage(config.prefix() + config.msg("join-follow-failed",
                            "arena", arena.getName(), "reason", problem));
                    continue;
                }
            }
            if (team != null) {
                arena.setTeam(member, team);
            }
        }
    }

    // =========================================================================
    //  Party lifecycle
    // =========================================================================

    /** Creates a one-person party led by {@code leader}. Caller checked no prior party. */
    private Party createParty(UUID leader) {
        Party p = new Party(leader);
        parties.put(leader, p);
        return p;
    }

    /** Ensures the sender has a party (creates one if needed). */
    private Party ensureParty(Player sender) {
        Party existing = parties.get(sender.getUniqueId());
        if (existing != null) {
            return existing;
        }
        return createParty(sender.getUniqueId());
    }

    // =========================================================================
    //  Invite
    // =========================================================================

    /**
     * {@code inviter} invites {@code target} to their party. Creates a party
     * for the inviter if they do not have one yet. Enforces every guard (self,
     * online, already member, target in a party, reject cooldown, party full)
     * and, when everything passes, sends the target the clickable
     * [ACCEPT] / [REJECT] invite.
     */
    public void invite(Player inviter, Player target) {
        if (target.getUniqueId().equals(inviter.getUniqueId())) {
            inviter.sendMessage(config.prefix() + config.msg("target-in-party", "player", target.getName()));
            return;
        }
        // Reject cooldown: the target recently rejected THIS inviter.
        Long until = cooldownUntil(target.getUniqueId(), inviter.getUniqueId());
        if (until != null && until > System.currentTimeMillis()) {
            long secs = (until - System.currentTimeMillis()) / 1000L;
            inviter.sendMessage(config.prefix() + config.msg("invite-cooldown",
                    "player", target.getName(), "seconds", secs));
            return;
        }

        Party party = ensureParty(inviter);
        if (party.isMember(target.getUniqueId())) {
            inviter.sendMessage(config.prefix() + config.msg("already-member", "player", target.getName()));
            return;
        }
        if (isInParty(target.getUniqueId())) {
            inviter.sendMessage(config.prefix() + config.msg("target-in-party", "player", target.getName()));
            return;
        }
        if (party.size() >= config.maxSize()) {
            inviter.sendMessage(config.prefix() + config.msg("party-full", "max", config.maxSize()));
            return;
        }

        // Record the pending invite (overwrites any previous one from the same inviter).
        Map<UUID, Long> fromInviters = invites.get(target.getUniqueId());
        if (fromInviters == null) {
            fromInviters = new HashMap<UUID, Long>();
            invites.put(target.getUniqueId(), fromInviters);
        }
        long expire = System.currentTimeMillis() + config.inviteExpireSeconds() * 1000L;
        fromInviters.put(inviter.getUniqueId(), expire);

        inviter.sendMessage(config.prefix() + config.msg("invite-sent", "player", target.getName()));
        sendInviteMessage(target, inviter, config.inviteExpireSeconds());
    }

    /** Sends the target the chat line plus the two clickable buttons. */
    private void sendInviteMessage(Player target, Player inviter, int seconds) {
        // The chat line above the buttons.
        String line = config.prefix() + config.msg("invite-received",
                "player", inviter.getName(), "seconds", seconds);
        target.sendMessage(line);

        // [ACCEPT] (green, bold) -> /party accept <inviter>
        TextComponent accept = new TextComponent("[ACCEPT]");
        accept.setColor(net.md_5.bungee.api.ChatColor.GREEN);
        accept.setBold(true);
        accept.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/party accept " + inviter.getName()));
        accept.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(ChatColor.translateAlternateColorCodes('&',
                        "&aClick to accept the party invite.")).create()));

        TextComponent spacer = new TextComponent("  ");

        // [REJECT] (red, bold) -> /party deny <inviter>
        TextComponent reject = new TextComponent("[REJECT]");
        reject.setColor(net.md_5.bungee.api.ChatColor.RED);
        reject.setBold(true);
        reject.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/party deny " + inviter.getName()));
        reject.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(ChatColor.translateAlternateColorCodes('&',
                        "&cClick to reject the party invite.")).create()));

        target.spigot().sendMessage(accept, spacer, reject);
    }

    // =========================================================================
    //  Accept / Deny
    // =========================================================================

    /** {@code target} accepts the invite from {@code inviter}. */
    public void accept(Player target, Player inviter) {
        UUID inviterId = inviter.getUniqueId();
        if (!hasValidInvite(target.getUniqueId(), inviterId)) {
            target.sendMessage(config.prefix() + config.msg("no-invite-from", "player", inviter.getName()));
            return;
        }
        Party party = parties.get(inviterId);
        if (party == null) {
            target.sendMessage(config.prefix() + config.msg("invite-expired"));
            clearInvite(target.getUniqueId(), inviterId);
            return;
        }
        if (isInParty(target.getUniqueId())) {
            target.sendMessage(config.prefix() + config.msg("already-in-party"));
            clearInvite(target.getUniqueId(), inviterId);
            return;
        }
        if (party.size() >= config.maxSize()) {
            target.sendMessage(config.prefix() + config.msg("party-full", "max", config.maxSize()));
            clearInvite(target.getUniqueId(), inviterId);
            return;
        }

        party.addMember(target.getUniqueId());
        parties.put(target.getUniqueId(), party);
        clearInvite(target.getUniqueId(), inviterId);

        target.sendMessage(config.prefix() + config.msg("accept-success", "player", inviter.getName()));
        broadcast(party, config.msg("accept-broadcast", "player", target.getName()), target.getUniqueId());
    }

    /**
     * {@code target} rejects the invite from {@code inviter}. Starts the
     * reject cooldown so the same inviter cannot re-invite them during it.
     */
    public void deny(Player target, Player inviter) {
        UUID inviterId = inviter.getUniqueId();
        if (!hasValidInvite(target.getUniqueId(), inviterId)) {
            target.sendMessage(config.prefix() + config.msg("no-invite-from", "player", inviter.getName()));
            return;
        }
        clearInvite(target.getUniqueId(), inviterId);

        long until = System.currentTimeMillis() + config.inviteCooldownSeconds() * 1000L;
        Map<UUID, Long> map = rejectCooldowns.get(target.getUniqueId());
        if (map == null) {
            map = new HashMap<UUID, Long>();
            rejectCooldowns.put(target.getUniqueId(), map);
        }
        map.put(inviterId, until);

        target.sendMessage(config.prefix() + config.msg("deny-success", "player", inviter.getName()));
        if (inviter.isOnline()) {
            inviter.sendMessage(config.prefix() + config.msg("deny-broadcast", "player", target.getName()));
        }
    }

    // =========================================================================
    //  Promote / Kick / Leave / Disband
    // =========================================================================

    public void promote(Player leader, Player target) {
        Party party = parties.get(leader.getUniqueId());
        if (party == null || !party.isLeader(leader.getUniqueId())) {
            leader.sendMessage(config.prefix() + config.msg("not-leader"));
            return;
        }
        if (!party.isMember(target.getUniqueId())) {
            leader.sendMessage(config.prefix() + config.msg("promote-not-member", "player", target.getName()));
            return;
        }
        if (party.isLeader(target.getUniqueId())) {
            leader.sendMessage(config.prefix() + config.msg("promote-already-leader", "player", target.getName()));
            return;
        }
        party.promote(target.getUniqueId());
        broadcast(party, config.prefix() + config.msg("promote-success", "player", target.getName()), null);
    }

    public void kick(Player leader, Player target) {
        Party party = parties.get(leader.getUniqueId());
        if (party == null || !party.isLeader(leader.getUniqueId())) {
            leader.sendMessage(config.prefix() + config.msg("not-leader"));
            return;
        }
        if (!party.isMember(target.getUniqueId())) {
            leader.sendMessage(config.prefix() + config.msg("promote-not-member", "player", target.getName()));
            return;
        }
        if (party.isLeader(target.getUniqueId())) {
            // The leader cannot kick themselves; they must /party leave or disband.
            leader.sendMessage(config.prefix() + config.msg("not-leader"));
            return;
        }
        party.removeMember(target.getUniqueId());
        parties.remove(target.getUniqueId());
        partyChatOn.remove(target.getUniqueId());

        leader.sendMessage(config.prefix() + config.msg("kick-success", "player", target.getName()));
        broadcast(party, config.msg("kick-broadcast", "player", target.getName()), target.getUniqueId());
        if (target.isOnline()) {
            target.sendMessage(config.prefix() + config.msg("kick-success", "player", target.getName()));
        }
        // A party of one is pointless: disband if only the leader is left.
        disbandIfTooSmall(party);
    }

    public void leave(Player player) {
        Party party = parties.get(player.getUniqueId());
        if (party == null) {
            player.sendMessage(config.prefix() + config.msg("not-in-party"));
            return;
        }
        boolean wasLeader = party.isLeader(player.getUniqueId());
        party.removeMember(player.getUniqueId());
        parties.remove(player.getUniqueId());
        partyChatOn.remove(player.getUniqueId());

        player.sendMessage(config.prefix() + config.msg("leave-success"));
        afterDeparture(party, player, wasLeader);
    }

    /**
     * Tidy-up after a member has just been removed from a party (leave, kick or
     * quit): if the party is now empty there is nothing to do; if only ONE
     * member remains the party is disbanded (a party needs at least 2);
     * otherwise, if the leader was the one who left, a RANDOM remaining member
     * is promoted so the party is never leader-less, and the departure is
     * broadcast. {@code departed} is skipped in the broadcasts.
     */
    private void afterDeparture(Party party, Player departed, boolean wasLeader) {
        if (party.size() <= 0) {
            // No one left: nothing to broadcast.
            return;
        }
        if (wasLeader && party.size() >= 2) {
            // Promote a RANDOM remaining member (online first) so the party is
            // never leader-less.
            UUID newLeader = pickNewLeader(party);
            if (newLeader != null) {
                party.promote(newLeader);
            }
            broadcast(party, config.msg("promote-success",
                    "player", nameOf(party.getLeader())), departed.getUniqueId());
        }
        broadcast(party, config.msg("leave-broadcast", "player", departed.getName()),
                departed.getUniqueId());
        disbandIfTooSmall(party);
    }

    /**
     * Picks a random remaining member to lead, preferring online ones (an
     * offline leader would leave the party unable to act until they rejoin).
     * Returns {@code null} only if the party is empty.
     */
    private UUID pickNewLeader(Party party) {
        List<Player> online = party.onlineMembers();
        if (!online.isEmpty()) {
            return online.get(ThreadLocalRandom.current().nextInt(online.size())).getUniqueId();
        }
        List<UUID> all = party.getMembers();
        if (all.isEmpty()) {
            return null;
        }
        return all.get(ThreadLocalRandom.current().nextInt(all.size()));
    }

    /**
     * If the party has shrunk to a single member, disbands it (a party needs at
     * least 2) and tells that last member. Returns {@code true} if disbanded.
     */
    private boolean disbandIfTooSmall(Party party) {
        if (party.size() != 1) {
            return false;
        }
        UUID lastId = party.getLeader();
        parties.remove(lastId);
        partyChatOn.remove(lastId);
        Player last = Bukkit.getPlayer(lastId);
        if (last != null) {
            last.sendMessage(config.prefix() + config.msg("disband-too-small"));
        }
        return true;
    }

    public void disband(Player leader) {
        Party party = parties.get(leader.getUniqueId());
        if (party == null || !party.isLeader(leader.getUniqueId())) {
            leader.sendMessage(config.prefix() + config.msg("not-leader"));
            return;
        }
        String disbanded = config.msg("disband-broadcast");
        String removed = config.prefix() + config.msg("removed-notify");
        for (UUID id : party.getMembers()) {
            parties.remove(id);
            partyChatOn.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(id.equals(leader.getUniqueId()) ? disbanded : removed);
            }
        }
    }

    // =========================================================================
    //  Chat toggle
    // =========================================================================

    public boolean toggleChat(Player player) {
        Party party = parties.get(player.getUniqueId());
        if (party == null) {
            player.sendMessage(config.prefix() + config.msg("not-in-party"));
            return false;
        }
        if (partyChatOn.add(player.getUniqueId())) {
            player.sendMessage(config.prefix() + config.msg("chat-on"));
            return true;
        }
        partyChatOn.remove(player.getUniqueId());
        player.sendMessage(config.prefix() + config.msg("chat-off"));
        return false;
    }

    // =========================================================================
    //  Quit / reconnect
    // =========================================================================

    /**
     * Called by {@link com.thetowers.listeners.PartyListener} on PlayerQuitEvent.
     *
     * <p>The member is KEPT in the party (by UUID) across the disconnect, so a
     * reconnect restores them automatically; only the party-chat toggle is
     * cleared. The player is now offline, so {@link Party#onlineMembers()} and
     * the chat delivery skip them until they rejoin. A disconnect is NOT a
     * leave: the party is not disbanded and the leadership is not moved.
     */
    public void onQuit(Player player) {
        partyChatOn.remove(player.getUniqueId());
        Party party = parties.get(player.getUniqueId());
        if (party == null) {
            return;
        }
        broadcast(party, config.msg("disconnect-notice", "player", player.getName()),
                player.getUniqueId());
    }

    /**
     * Called by {@link com.thetowers.listeners.PartyListener} on PlayerJoinEvent.
     * A player who was in a party when they disconnected still is (they were kept
     * by UUID): tell them they are back, and tell the others they reconnected.
     */
    public void onRejoin(Player player) {
        Party party = parties.get(player.getUniqueId());
        if (party == null) {
            return;
        }
        player.sendMessage(config.prefix() + config.msg("reconnect-self"));
        broadcast(party, config.msg("reconnect-notice", "player", player.getName()),
                player.getUniqueId());
    }

    // =========================================================================
    //  Chat delivery
    // =========================================================================

    /**
     * Sends a party-chat message from {@code sender} to every online member.
     * Returns the formatted line (so the listener can confirm it was handled)
     * or {@code null} if the sender is not in a party.
     */
    public String sendPartyChat(Player sender, String message) {
        Party party = parties.get(sender.getUniqueId());
        if (party == null) {
            return null;
        }
        // %leader% = the leader tag (color-coded) when the sender leads the
        // party, otherwise nothing - so members and the leader are easy to tell
        // apart in party chat.
        String leaderTag = party.isLeader(sender.getUniqueId())
                ? config.msg("chat-leader-tag") : "";
        String line = config.msg("chat-format", "player", sender.getName(),
                "message", message, "leader", leaderTag);
        for (Player member : party.onlineMembers()) {
            member.sendMessage(line);
        }
        return line;
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /** Sends a message to every online member, optionally skipping one. */
    private void broadcast(Party party, String message, UUID skip) {
        for (Player member : party.onlineMembers()) {
            if (skip != null && skip.equals(member.getUniqueId())) {
                continue;
            }
            member.sendMessage(message);
        }
    }

    private boolean hasValidInvite(UUID target, UUID inviter) {
        Map<UUID, Long> fromInviters = invites.get(target);
        if (fromInviters == null) {
            return false;
        }
        Long expire = fromInviters.get(inviter);
        if (expire == null) {
            return false;
        }
        if (expire <= System.currentTimeMillis()) {
            fromInviters.remove(inviter);
            if (fromInviters.isEmpty()) {
                invites.remove(target);
            }
            return false;
        }
        return true;
    }

    private void clearInvite(UUID target, UUID inviter) {
        Map<UUID, Long> fromInviters = invites.get(target);
        if (fromInviters == null) {
            return;
        }
        fromInviters.remove(inviter);
        if (fromInviters.isEmpty()) {
            invites.remove(target);
        }
    }

    private Long cooldownUntil(UUID target, UUID inviter) {
        Map<UUID, Long> map = rejectCooldowns.get(target);
        if (map == null) {
            return null;
        }
        Long until = map.get(inviter);
        if (until == null) {
            return null;
        }
        if (until <= System.currentTimeMillis()) {
            map.remove(inviter);
            if (map.isEmpty()) {
                rejectCooldowns.remove(target);
            }
            return null;
        }
        return until;
    }

    private static String nameOf(UUID id) {
        if (id == null) {
            return "?";
        }
        Player p = Bukkit.getPlayer(id);
        return p != null ? p.getName() : id.toString().substring(0, 8);
    }
}
