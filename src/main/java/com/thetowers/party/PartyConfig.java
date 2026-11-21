package com.thetowers.party;

import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;

import com.thetowers.Main;
import com.thetowers.config.CustomConfig;

/**
 * Configuration and messages of the party system, all loaded from a single
 * file: {@code modules/parties.yml}.
 *
 * <p>The party module is deliberately self-contained, like the leveling one
 * ({@link com.thetowers.leveling.LevelingConfig}): its settings and message
 * table never go into {@code messages.yml}, so the whole subsystem can be
 * tuned or rewritten without touching the central TheTowers code.
 *
 * <p>Nothing is persisted: parties, pending invites, reject cooldowns and the
 * chat toggle all live in memory and are wiped on restart (by design).
 */
public final class PartyConfig {

    private final Main main;
    private CustomConfig file;

    // ----- settings -----
    private int maxSize;
    private int inviteCooldownSeconds;
    private int inviteExpireSeconds;

    public PartyConfig(Main main) {
        this.main = main;
        load();
    }

    /** (Re)reads modules/parties.yml from disk. */
    public void load() {
        file = new CustomConfig(main, "modules/parties.yml", true);
        ConfigurationSection root = file.get();

        ConfigurationSection s = root.getConfigurationSection("settings");
        if (s == null) {
            s = root.createSection("settings");
        }
        maxSize = s.getInt("max-size", 8);
        inviteCooldownSeconds = s.getInt("invite-cooldown-seconds", 300);
        inviteExpireSeconds = s.getInt("invite-expire-seconds", 60);

        main.getLogger().info("Loaded modules/parties.yml (party system).");
    }

    // ----- settings accessors -----
    public int maxSize() {
        return maxSize;
    }

    public int inviteCooldownSeconds() {
        return inviteCooldownSeconds;
    }

    public int inviteExpireSeconds() {
        return inviteExpireSeconds;
    }

    // ----- messages -----
    /**
     * Translated message from the {@code messages:} section: {@code &} colors
     * applied and {@code %name%} variables replaced (passed in pairs, name
     * then value). Falls back to the bundled copy if the key is missing from
     * the server file.
     */
    public String msg(String key, Object... placeholders) {
        String raw = file.getString("messages." + key, null);
        if (raw == null) {
            return "messages." + key;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace("%" + placeholders[i] + "%", String.valueOf(placeholders[i + 1]));
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    /** Message list (e.g. the help screen). */
    public java.util.List<String> msgList(String key, Object... placeholders) {
        List<String> list = file.getStringList("messages." + key);
        java.util.List<String> out = new java.util.ArrayList<String>(list.size());
        for (String line : list) {
            String processed = line;
            for (int i = 0; i + 1 < placeholders.length; i += 2) {
                processed = processed.replace("%" + placeholders[i] + "%", String.valueOf(placeholders[i + 1]));
            }
            out.add(ChatColor.translateAlternateColorCodes('&', processed));
        }
        return out;
    }

    /** The configured prefix (already colorized). */
    public String prefix() {
        return msg("prefix");
    }
}
