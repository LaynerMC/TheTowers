package com.thetowers.listeners;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.thetowers.Main;
import com.thetowers.party.PartyManager;

/**
 * Party chat and disconnect handling.
 *
 * <p><b>Chat.</b> Runs at {@link EventPriority#NORMAL} so it runs BEFORE the
 * game's own {@link ChatListeners} (HIGHEST, {@code ignoreCancelled = true}).
 * When a player has toggled party chat on and belongs to a party, the message
 * is delivered only to the online party members (skipping any who ignore the
 * sender) and the original event is cancelled, so the game chat handler never
 * touches it. The decision is taken on the main thread (the party data is not
 * concurrency-safe), exactly the way {@code ChatListeners} does.
 *
 * <p><b>Quit / reconnect.</b> A player who disconnects is KEPT in their party
 * (by UUID), so a reconnect drops them back in automatically; only the
 * party-chat toggle is cleared on quit. A disconnect is not a leave: the party
 * is not disbanded and the leadership is not moved. The online members are just
 * told who went offline / came back.
 */
public class PartyListener implements Listener {

    /** Max wait for the main-thread decision; beyond it, the message passes through. */
    private static final long DECISION_TIMEOUT_MS = 2000L;

    private final Main main;
    private final PartyManager parties;

    public PartyListener(Main main, PartyManager parties) {
        this.main = main;
        this.parties = parties;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(final AsyncPlayerChatEvent event) {
        if (!main.isChatEnabled()) {
            return;
        }
        final Player player = event.getPlayer();
        // Cheap concurrent check first: avoid a sync hop for the common case.
        if (!parties.isPartyChatOn(player.getUniqueId())) {
            return;
        }

        final String message = event.getMessage();
        Boolean handled;
        if (Bukkit.isPrimaryThread()) {
            handled = deliver(player, message);
        } else {
            try {
                handled = Bukkit.getScheduler().callSyncMethod(main, new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        return deliver(player, message);
                    }
                }).get(DECISION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // Server overloaded or stopping: let the message pass through.
                return;
            }
        }
        if (handled != null && handled) {
            event.setCancelled(true);
        }
    }

    /**
     * Sends the party-chat line to every online member (skipping those who
     * ignore the sender). Main-thread only. Returns true if the message was
     * handled as party chat (so the caller cancels the event).
     */
    private Boolean deliver(Player sender, String message) {
        String line = parties.sendPartyChat(sender, message);
        return line != null;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        parties.onQuit(player);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        // A reconnecting player who was kept in their party is dropped back in;
        // this just announces it. Players joining fresh (no party) are ignored.
        parties.onRejoin(event.getPlayer());
    }
}
