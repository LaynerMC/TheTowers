package com.thetowers.party;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * A party: an ordered list of players (the leader first) who can talk together
 * in a private chat channel and join a game as a block.
 *
 * <p>The leader is always {@code members.get(0)}. Membership is tracked by
 * {@link UUID} so an offline member is still listed (the chat and join logic
 * only consider online members).
 *
 * <p>Instances are not thread-safe and are only touched from the main thread
 * (commands, the chat decision and the arena join all run there).
 */
public final class Party {

    /** Ordered members: leader at index 0. */
    private final List<UUID> members = new ArrayList<UUID>();
    private final Set<UUID> memberSet = new LinkedHashSet<UUID>();

    public Party(UUID leader) {
        members.add(leader);
        memberSet.add(leader);
    }

    public UUID getLeader() {
        return members.isEmpty() ? null : members.get(0);
    }

    /** Unmodifiable view of the ordered members (leader first). */
    public List<UUID> getMembers() {
        return Collections.unmodifiableList(members);
    }

    public int size() {
        return members.size();
    }

    public boolean isLeader(UUID id) {
        return !members.isEmpty() && members.get(0).equals(id);
    }

    public boolean isMember(UUID id) {
        return memberSet.contains(id);
    }

    /** Appends a member. Returns false if already present. */
    public boolean addMember(UUID id) {
        if (memberSet.contains(id)) {
            return false;
        }
        members.add(id);
        memberSet.add(id);
        return true;
    }

    /** Removes a member. The leader cannot be removed with this (use promote first). */
    public boolean removeMember(UUID id) {
        if (!memberSet.contains(id)) {
            return false;
        }
        members.remove(id);
        memberSet.remove(id);
        return true;
    }

    /** Hands the leadership to an existing member. Returns false if not a member. */
    public boolean promote(UUID id) {
        if (!memberSet.contains(id) || isLeader(id)) {
            return false;
        }
        members.remove(id);
        members.add(0, id);
        return true;
    }

    /** Online members (leader first). */
    public List<Player> onlineMembers() {
        List<Player> out = new ArrayList<Player>(members.size());
        for (UUID id : members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    /** Count of members currently online. */
    public int onlineCount() {
        int n = 0;
        for (UUID id : members) {
            if (Bukkit.getPlayer(id) != null) {
                n++;
            }
        }
        return n;
    }
}
