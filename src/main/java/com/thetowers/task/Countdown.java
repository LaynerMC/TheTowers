package com.thetowers.task;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import com.thetowers.arena.Arena;
import com.thetowers.arena.State;
import com.thetowers.compat.Sounds;
import com.thetowers.lang.Messages;

/**
 * Countdown before a game starts.
 *
 * <p>Started by {@link Arena} as soon as the minimum number of players is
 * reached. The last three seconds are shown large in the center of the screen,
 * then {@link Arena#start()} teleports the teams.
 *
 * <p>The XP level is a visible timer ONLY during the wait: in-game, XP is the
 * game's enchantment currency and must never be touched (hence the absence of
 * such a timer in {@link GameCycle}).
 */
public class Countdown extends BukkitRunnable {

    private final Arena arena;
    private int timer;

    public Countdown(Arena arena, int seconds) {
        this.arena = arena;
        this.timer = seconds;
    }

    /** Seconds left before the game starts (0 if the countdown just ended). */
    public int getTimer() {
        return timer;
    }

    @Override
    public void run() {
        // Safety: if the arena changed state in the meantime (players left,
        // arena reset), the task stops by itself.
        if (!arena.isState(State.STARTING)) {
            cancel();
            return;
        }

        if (timer <= 0) {
            // start() cancels this task along the way.
            arena.start();
            return;
        }

        for (Player player : arena.onlinePlayers()) {
            player.setLevel(timer);
        }

        // The countdown is shown through three channels: the Minecraft XP bar
        // (always, above), a center title, and a chat broadcast. The last five
        // seconds use all three; the chat is fired alongside the title so it
        // never goes silent below 4 (the old else-if let the title branch
        // swallow the chat for 3/2/1).
        if (timer > 0 && timer <= 5) {
            for (Player player : arena.onlinePlayers()) {
                player.sendTitle(Messages.tr("title.countdown", "n", timer), "", 5, 25, 5);
                Sounds.countdownTick(player, timer);
            }
            arena.broadcast(Messages.tr("game.countdown_broadcast", "n", timer));
        } else if (timer == 60 || timer == 30 || timer == 15 || timer == 10) {
            // Milestone announcements: chat only (the XP bar already ticks).
            arena.broadcast(Messages.tr("game.countdown_broadcast", "n", timer));
        }

        timer--;
    }
}
