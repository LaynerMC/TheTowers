package com.thetowers.config;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import com.thetowers.arena.ArenaManager;
import com.thetowers.Main;
import com.thetowers.lang.Messages;

/**
 * Arena creation from the menu: the "Create an arena" button closes the menu
 * and the admin's next chat message becomes the arena's name (it is not
 * broadcast). The arena is then created and its edit menu opens.
 *
 * <p>Why chat rather than an anvil or a sign: it is the only text input that
 * works the same for every client version (1.8.9 to the latest, through
 * ViaVersion) without any version-specific code.
 *
 * <p>Chat arrives on an asynchronous thread: the table of in-progress inputs
 * is therefore concurrent, and the name processing is handed back to the main
 * thread.
 */
public class ArenaNaming {

    /** Input timeout before giving up: 60 seconds. */
    private static final long TIMEOUT_TICKS = 60L * 20L;

    private final Main main;
    /** In-progress inputs, with the task that abandons them after the timeout. */
    private final Map<UUID, BukkitRunnable> waiting = new ConcurrentHashMap<UUID, BukkitRunnable>();

    public ArenaNaming(Main main) {
        this.main = main;
    }

    public boolean isNaming(Player player) {
        return waiting.containsKey(player.getUniqueId());
    }

    /** Closes the menu and asks for the new arena's name in the chat. */
    public void start(Player player) {
        main.getPlacements().clear(player);
        player.closeInventory();
        armTimeout(player);
        player.sendMessage(Main.PREFIX + Messages.tr("config.naming.prompt"));
    }

    /** Cancels the input, with a message. Returns false if there was none. */
    public boolean cancel(Player player) {
        if (!stop(player)) {
            return false;
        }
        player.sendMessage(Main.PREFIX + Messages.tr("config.naming.cancelled"));
        return true;
    }

    /** Removes an input without a message (disconnect). */
    public void clear(Player player) {
        stop(player);
    }

    /**
     * Handles the message typed by the admin. To be called on the main
     * thread. A rejected name leaves the input open: the admin can type
     * another one.
     */
    public void handleInput(Player player, String input) {
        if (!isNaming(player)) {
            return;
        }
        String name = input.trim();
        if ("cancel".equalsIgnoreCase(name)) {
            cancel(player);
            main.getConfigMenu().openList(player);
            return;
        }
        if (!ArenaManager.isValidName(name)) {
            player.sendMessage(Main.PREFIX + Messages.tr("command.arena_invalid_name"));
            armTimeout(player);
            return;
        }
        if (!main.getArenaManager().createArena(name)) {
            player.sendMessage(Main.PREFIX + Messages.tr("command.arena_exists"));
            armTimeout(player);
            return;
        }
        stop(player);
        player.sendMessage(Main.PREFIX + Messages.tr("config.naming.created", "name", name));
        main.getConfigMenu().openEdit(player, name);
    }

    /** (Re)arms the input timeout: every failed attempt grants 60 more seconds. */
    private void armTimeout(final Player player) {
        final UUID id = player.getUniqueId();
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                // remove(id, this): gives up only if it is still this task
                // that holds the input (not one re-armed in the meantime).
                if (waiting.remove(id, this) && player.isOnline()) {
                    player.sendMessage(Main.PREFIX + Messages.tr("config.naming.timeout"));
                }
            }
        };
        BukkitRunnable previous = waiting.put(id, task);
        if (previous != null) {
            previous.cancel();
        }
        task.runTaskLater(main, TIMEOUT_TICKS);
    }

    private boolean stop(Player player) {
        BukkitRunnable task = waiting.remove(player.getUniqueId());
        if (task == null) {
            return false;
        }
        task.cancel();
        return true;
    }
}
