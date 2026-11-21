package com.thetowers.game;

import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * A kit potion effect ({@code effects:} in kits.yml), applied every time the
 * player spawns (game start and respawn): { type: SPEED, amplifier: 1,
 * duration: 60 } - duration in seconds, 0 = infinite (until the player's next
 * reset).
 */
public final class KitEffect {

    private final PotionEffectType type;
    private final int amplifier;
    private final int durationSeconds;

    public KitEffect(PotionEffectType type, int amplifier, int durationSeconds) {
        this.type = type;
        this.amplifier = amplifier;
        this.durationSeconds = durationSeconds;
    }

    public PotionEffectType getType() {
        return type;
    }

    /** 0 = level 1. */
    public int getAmplifier() {
        return Math.max(0, amplifier);
    }

    /** Duration in seconds; 0 or negative = infinite (20 minutes, renewed on each death). */
    public int getDurationSeconds() {
        return durationSeconds;
    }

    /** The Bukkit effect, ready to add to the player. */
    public PotionEffect toPotionEffect() {
        int ticks = durationSeconds <= 0 ? 24000 : Math.min(durationSeconds, 86400) * 20;
        return new PotionEffect(type, ticks, getAmplifier(), true, false);
    }
}
