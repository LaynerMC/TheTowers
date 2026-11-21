package com.thetowers.api;

import org.bukkit.OfflinePlayer;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import com.thetowers.Main;

/**
 * PlaceholderAPI expansion: exposes the stats and the game state as
 * {@code %thetowers_...%} placeholders, usable by any display plugin
 * (holograms, chat, third-party scoreboards).
 *
 * <p>The values are computed by {@link PlaceholderValues}, which does not
 * depend on PAPI (TheTowers' scoreboard also uses it without PAPI). See that
 * class for the list of keys.
 *
 * <p><b>Isolation</b>: this class extends a PlaceholderAPI class. It is
 * instantiated by {@link Main} <b>only after checking that the PlaceholderAPI
 * plugin is present</b>; the JVM therefore never resolves it on a server
 * without PAPI. Never reference it elsewhere without that guard.
 */
public class Placeholders extends PlaceholderExpansion {

    private final Main main;
    private final String identifier;

    /**
     * @param identifier prefix of the placeholders: {@code thetowers}, or
     *                   {@code thetower} for the compatibility expansion with
     *                   the plugin's old name (same values)
     */
    public Placeholders(Main main, String identifier) {
        this.main = main;
        this.identifier = identifier;
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String getAuthor() {
        return "xLaugh";
    }

    @Override
    public String getVersion() {
        return main.getDescription().getVersion();
    }

    /** Stays registered when PlaceholderAPI reloads. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        // null = unknown placeholder: PlaceholderAPI leaves the text as is.
        return PlaceholderValues.resolve(main, player, params);
    }
}
