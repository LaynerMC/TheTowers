package com.thetowers.task;

import org.bukkit.scheduler.BukkitRunnable;

import com.thetowers.arena.Arena;
import com.thetowers.lang.Messages;

/**
 * Clock of a game: countdown of the regulation time, once per second.
 *
 * <p>Without this limit, two cautious teams could make a game last forever.
 * At zero, {@link Arena#timeUp()} designates the winner or triggers sudden
 * death; the task then keeps running without doing anything until the end of
 * the game.
 */
public class GameCycle extends BukkitRunnable {

    private final Arena arena;

    public GameCycle(Arena arena) {
        this.arena = arena;
    }

    @Override
    public void run() {
        if (!arena.isRunning()) {
            cancel();
            return;
        }
        // Reconnection grace periods elapsed: can make a team give up, and so
        // end the game; hence the second check right after.
        arena.checkDisconnected();
        if (!arena.isRunning() || arena.isOvertime()) {
            return;
        }

        int left = arena.tickTimer();
        if (left <= 0) {
            arena.timeUp();
            return;
        }

        if (left == 600 || left == 300 || left == 120 || left == 60 || left == 30
                || left == 10 || left <= 5) {
            arena.broadcast(Messages.tr("game.time_left", "time", clock(left)));
        }
    }

    /** Seconds -> M:SS. */
    public static String clock(int totalSeconds) {
        int seconds = Math.max(0, totalSeconds);
        int minutes = seconds / 60;
        int rest = seconds % 60;
        return minutes + ":" + (rest < 10 ? "0" + rest : Integer.toString(rest));
    }
}
