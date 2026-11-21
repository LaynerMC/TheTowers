package com.thetowers.compat;

import org.bukkit.entity.Player;

import com.cryptomorin.xseries.XSound;

/**
 * Sounds played during the game: countdown, game start, points scored, kill
 * streaks.
 *
 * <p>Sound names changed as much between versions as the materials: the 1.8
 * {@code Sound.FUSE} and {@code Sound.BLAZE_DEATH} became
 * {@code ENTITY_CREEPER_PRIMED} and {@code ENTITY_BLAZE_DEATH} from 1.9 on.
 * On our 1.12.2 server the modern names are the real ones, but writing
 * {@code Sound.XXX} in hard code would lock us to one name family.
 *
 * <p>We therefore delegate to {@code XSound} (XSeries), which has its OWN
 * constants and knows how to match them to the real server name at runtime.
 * XSeries is kept on purpose: ViaVersion translates the sounds for clients of
 * other versions. Second point of contact with XSeries, after
 * {@link TTMaterial}.
 *
 * <p>{@code record()/soundPlayer()} form: {@code XSound.play(Entity, float,
 * float)} is deprecated since XSeries 9.x in favor of this one.
 */
public final class Sounds {

    private Sounds() {
    }

    /** Countdown tick (3, 2, 1): a pitch that rises as zero approaches. */
    public static void countdownTick(Player player, int secondsLeft) {
        int step = 3 - Math.max(0, Math.min(3, secondsLeft));
        play(XSound.BLOCK_NOTE_BLOCK_PLING, player, 1f + step * 0.2f);
    }

    /** Fanfare at the actual start of the game. */
    public static void gameStart(Player player) {
        play(XSound.ENTITY_PLAYER_LEVELUP, player, 1f);
    }

    /** A teammate just scored (the creeper fuse). */
    public static void markAlly(Player player) {
        play(XSound.ENTITY_CREEPER_PRIMED, player, 1f);
    }

    /** An opponent just scored (the blaze death cry). */
    public static void markEnemy(Player player) {
        play(XSound.ENTITY_BLAZE_DEATH, player, 1f);
    }

    /** Match point: a team is one point away from victory. */
    public static void matchPoint(Player player) {
        play(XSound.ENTITY_WITHER_SPAWN, player, 1.5f);
    }

    /** Kill streak: the longer the streak, the deeper and more menacing the sound. */
    public static void killStreak(Player player, int streak) {
        if (streak >= 5) {
            play(XSound.ENTITY_ENDERMAN_SCREAM, player, 1f);
        } else if (streak >= 3) {
            play(XSound.ENTITY_ENDER_DRAGON_GROWL, player, 1.5f);
        } else {
            play(XSound.ENTITY_ZOMBIE_AMBIENT, player, 1f);
        }
    }

    /** End of game: an explosion to mark the moment. */
    public static void gameEnd(Player player) {
        play(XSound.ENTITY_GENERIC_EXPLODE, player, 1f);
    }

    /** Start of the leave-lobby countdown: a very deep pling note (pitch 0). */
    public static void leavePing(Player player) {
        play(XSound.BLOCK_NOTE_BLOCK_PLING, player, 0f);
    }

    /** Pleasant chime played on a successful kit purchase. */
    public static void kitPurchase(Player player) {
        play(XSound.ENTITY_EXPERIENCE_ORB_PICKUP, player, 1f, 1.5f);
    }

    /** Low bass note played when a locked or unaffordable kit is clicked. */
    public static void kitDeny(Player player) {
        play(XSound.BLOCK_NOTE_BLOCK_BASS, player, 1f, 0.6f);
    }

    /**
     * Plays the given sound to a player with a custom volume and pitch. Public
     * so the leveling module can honor {@code [sound] NAME pitch volume}
     * reward lines.
     */
    public static void play(XSound sound, Player player, float volume, float pitch) {
        if (player == null || !player.isOnline() || sound == null) {
            return;
        }
        sound.record()
                .withVolume(volume)
                .withPitch(pitch)
                .soundPlayer()
                .forPlayers(player)
                .play();
    }

    private static void play(XSound sound, Player player, float pitch) {
        play(sound, player, 1f, pitch);
    }
}
