package com.thetowers.api;

import org.bukkit.entity.Player;

import me.clip.placeholderapi.PlaceholderAPI;

/**
 * Replacement of the placeholders of ALL plugins (%player_name%,
 * %luckperms_prefix%, %vault_eco_balance%...) through PlaceholderAPI, for the
 * configurable scoreboard lines.
 *
 * <p><b>Isolation</b>, same rule as {@link Placeholders}: only this class
 * references the PAPI API in its code, and it is called only after checking
 * that PlaceholderAPI is present ({@code Main#hasPlaceholderApi()}). Since the
 * JVM resolves {@code PlaceholderAPI} only when the call executes, a server
 * without PAPI never loads it.
 */
public final class PapiBridge {

    private PapiBridge() {
    }

    public static String apply(Player player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }
}
