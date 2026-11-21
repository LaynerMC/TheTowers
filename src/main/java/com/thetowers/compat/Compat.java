package com.thetowers.compat;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Small operations whose wording differs between Bukkit versions. Everything
 * here compiles against spigot-api 1.12.2 and runs on a 1.12.2 server;
 * ViaVersion translates the result for clients of other versions.
 */
public final class Compat {

    private Compat() {
    }

    /**
     * Heals a player back to full.
     *
     * <p>Writing {@code player.setHealth(20)} is wrong: it raises an
     * {@code IllegalArgumentException} as soon as another plugin, an attribute
     * or an effect has changed the player's max health. We therefore always read
     * the real maximum.
     *
     * <p>{@code getMaxHealth()} is deprecated since 1.9 in favor of the
     * Attribute API, but it is still present in 1.12.2 and remains the
     * simplest reliable read of the real maximum, whatever changed it
     * (attribute modifiers, effects, another plugin).
     */
    @SuppressWarnings("deprecation")
    public static void healFully(Player player) {
        player.setHealth(player.getMaxHealth());
    }

    /**
     * Resets a player completely: inventory, armor, health, food, experience,
     * effects and fire. Use on every phase change so a previous game state does
     * not leak into the next.
     */
    public static void resetPlayer(Player player, GameMode mode) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);

        healFully(player);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setExhaustion(0f);

        player.setLevel(0);
        player.setExp(0f);

        player.setFireTicks(0);
        player.setFallDistance(0f);

        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }

        player.setAllowFlight(mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR);
        player.setFlying(false);
        player.setCanPickupItems(true);
        player.setGameMode(mode);
    }

    /**
     * Puts a player into spectator mode: adventure, flying, invisible and
     * picking up nothing. Minecraft's "spectator" mode is not used because it
     * hides the hotbar, hence the spectator's compass and barrier.
     */
    public static void makeSpectator(Player player) {
        resetPlayer(player, GameMode.ADVENTURE);
        // No armor: with the invisibility effect below, worn armor is the only
        // thing that would still float and give the spectator away.
        player.getInventory().setArmorContents(null);
        player.setItemOnCursor(null);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setCanPickupItems(false);
        // Completely invisible, with no potion particles (ambient=true,
        // particles=false): the body, the armor and the nametag are hidden, and
        // the effect gives off no particles. Vanish (hidePlayer, see setHidden)
        // additionally removes the spectator from the player list.
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,
                Integer.MAX_VALUE, 0, true, false));
    }

    /**
     * Hides (or shows) a player to another. {@code hidePlayer(Player)} is
     * deprecated since 1.12 in favor of the {@code (Plugin, Player)} variant,
     * but still present in 1.12.2; we catch the error in case a fork removed
     * it.
     */
    public static void setHidden(Player viewer, Player target, boolean hidden) {
        try {
            if (hidden) {
                viewer.hidePlayer(target);
            } else {
                viewer.showPlayer(target);
            }
        } catch (Throwable ignored) {
            // Without hiding, makeSpectator's invisibility is enough.
        }
    }

    /**
     * Respawns a dead player immediately, bypassing the "You are dead" screen.
     * The Towers is a permanent-respawn game: waiting for a click on every death
     * would break the pace.
     *
     * <p>{@code Player.spigot().respawn()} is a Spigot API, present in 1.12.2.
     * We still catch any error: on a pure Bukkit server or a fork that removed
     * it, the player will simply click "Respawn", which is not blocking. Call
     * one tick AFTER death, never during the event.
     */
    public static void forceRespawn(Player player) {
        if (player == null || !player.isOnline() || !player.isDead()) {
            return;
        }
        try {
            player.spigot().respawn();
        } catch (Throwable ignored) {
            // No Spigot API: manual respawn, harmless.
        }
    }

    /** Translates color codes written with {@code &} into Minecraft codes. */
    public static String color(String message) {
        return message == null ? "" : ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * A hotbar item display name: forced bold, with the {@code &7(Right-click)}
     * hint appended after the name. The raw name uses {@code &} color codes
     * (not yet translated). A leading {@code &X} color code (if present) is
     * kept and followed by {@code &l}; otherwise {@code &f&l} is prepended.
     */
    public static String hotbarName(String raw) {
        String withBold;
        if (raw != null && raw.length() >= 2 && raw.charAt(0) == '&') {
            withBold = raw.substring(0, 2) + "&l" + raw.substring(2);
        } else {
            withBold = "&f&l" + (raw == null ? "" : raw);
        }
        return color(withBold + " &7(Right-click)");
    }
}
