package com.thetowers.leveling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import net.md_5.bungee.api.ChatMessageType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.md_5.bungee.api.chat.TextComponent;

import com.thetowers.Main;
import com.thetowers.compat.Sounds;
import com.thetowers.stats.PlayerStats;

import com.cryptomorin.xseries.XSound;

/**
 * The leveling engine's single entry point.
 *
 * <p>Everything level/EXP flows through here: reads, the add/set/remove
 * operations, automatic level-ups, level-up rewards, and the placeholder
 * values. The module is deliberately self-contained: it owns its config
 * ({@link LevelingConfig}, loaded from {@code leveling.yml}), its formula
 * evaluator and its formatting, and it touches the rest of TheTowers only
 * through {@code StatsManager} (level and EXP are stored as two fields of the
 * player's stats record in {@code stats.yml}, so they persist with everything
 * else) and by dispatching commands as the console (the level-up rewards and
 * the {@code /leveling} command itself).
 *
 * <p>The game never calls into this module directly. It is driven entirely by
 * commands: {@code rewards.yml}'s {@code on-kill}/{@code on-death} sections
 * fire {@code /leveling addexp} commands, and players/admins use
 * {@code /leveling}. That keeps the leveling logic free of any tie to the
 * towers game loop, so it can evolve on its own.
 *
 * <p>Threading: the mutating operations run on the main thread (commands and
 * console-dispatched commands both arrive there), exactly where stats are
 * mutated and rewards (command dispatch) must run.
 */
public final class LevelingManager {

    private final Main main;
    private LevelingConfig config;

    public LevelingManager(Main main) {
        this.main = main;
        this.config = new LevelingConfig(main);
    }

    public LevelingConfig getConfig() {
        return config;
    }

    /** (Re)reads {@code leveling.yml}. */
    public void reload() {
        config.load();
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    // ================= Reads =================

    /** Current level of an online (cached) player, or the start level if unknown. */
    public long getLevel(UUID uuid) {
        PlayerStats stats = main.getStatsManager().getCached(uuid);
        if (stats == null) {
            return config.startLevel();
        }
        ensureInitialized(stats);
        return stats.getLevel();
    }

    /** Current EXP of an online (cached) player, or the start EXP if unknown. */
    public long getExp(UUID uuid) {
        PlayerStats stats = main.getStatsManager().getCached(uuid);
        if (stats == null) {
            return config.startExp();
        }
        ensureInitialized(stats);
        return stats.getExp();
    }

    /** EXP required for the player's current level to advance to the next. */
    public long getRequired(UUID uuid) {
        return config.requiredExp(getLevel(uuid));
    }

    // ================= Mutations (online player) =================

    /** Adds EXP, leveling up as many times as needed. Returns the new level. */
    public long addExp(Player player, long amount) {
        return mutate(player.getUniqueId(), player.getName(), player, stats -> applyAddExp(stats, amount));
    }

    /** Sets EXP to an exact value, then resolves any level-ups. Returns the new level. */
    public long setExp(Player player, long amount) {
        return mutate(player.getUniqueId(), player.getName(), player, stats -> applySetExp(stats, amount));
    }

    /** Removes EXP (never de-levels). Returns the new level. */
    public long removeExp(Player player, long amount) {
        return mutate(player.getUniqueId(), player.getName(), player, stats -> applyRemoveExp(stats, amount));
    }

    /** Adds levels, running each crossed level's rewards. Returns the new level. */
    public long addLevel(Player player, long amount) {
        return mutate(player.getUniqueId(), player.getName(), player, stats -> applyAddLevel(stats, amount));
    }

    /** Sets the level directly, running rewards for any levels gained. Returns the new level. */
    public long setLevel(Player player, long amount) {
        return mutate(player.getUniqueId(), player.getName(), player, stats -> applySetLevel(stats, amount));
    }

    /** Removes levels (down to the start level). Returns the new level. */
    public long removeLevel(Player player, long amount) {
        return mutate(player.getUniqueId(), player.getName(), player, stats -> applyRemoveLevel(stats, amount));
    }

    // ================= Mutations (offline player, by command) =================

    /**
     * Same operations for a player who may be offline. Loads their stats
     * synchronously (may briefly block on MySQL), modifies and saves. Returns
     * the new level, or {@code -1} if the player could not be loaded (online
     * but stats still loading, or truly unknown).
     */
    public long addExpOffline(UUID uuid, String name, long amount) {
        return mutate(uuid, name, Bukkit.getPlayer(uuid), stats -> applyAddExp(stats, amount));
    }

    public long setExpOffline(UUID uuid, String name, long amount) {
        return mutate(uuid, name, Bukkit.getPlayer(uuid), stats -> applySetExp(stats, amount));
    }

    public long removeExpOffline(UUID uuid, String name, long amount) {
        return mutate(uuid, name, Bukkit.getPlayer(uuid), stats -> applyRemoveExp(stats, amount));
    }

    public long addLevelOffline(UUID uuid, String name, long amount) {
        return mutate(uuid, name, Bukkit.getPlayer(uuid), stats -> applyAddLevel(stats, amount));
    }

    public long setLevelOffline(UUID uuid, String name, long amount) {
        return mutate(uuid, name, Bukkit.getPlayer(uuid), stats -> applySetLevel(stats, amount));
    }

    public long removeLevelOffline(UUID uuid, String name, long amount) {
        return mutate(uuid, name, Bukkit.getPlayer(uuid), stats -> applyRemoveLevel(stats, amount));
    }

    // ================= Placeholder values =================

    /**
     * Value of a {@code %thetowers_<key>%} placeholder for a player, or
     * {@code null} if the key is not a leveling key.
     */
    public String placeholder(UUID uuid, String key) {
        if (!config.isEnabled()) {
            // Known leveling keys collapse to empty when disabled; unknown -> null.
            return isLevelingKey(key) ? "" : null;
        }
        long level = getLevel(uuid);
        long exp = getExp(uuid);
        long required = config.requiredExp(level);
        switch (key) {
            case "level": return Long.toString(level);
            case "level_format": return config.levelFormat(level);
            case "level_formatted": return config.formatter().grouped(level);
            case "exp": return Long.toString(exp);
            case "exp_formatted": return config.formatter().grouped(exp);
            case "exp_formatted_short": return config.formatter().shortForm(exp);
            case "exp_required": return Long.toString(required);
            case "exp_required_formatted": return config.formatter().grouped(required);
            case "exp_required_formatted_short": return config.formatter().shortForm(required);
            case "progress_bar":
            case "exp_progress_bar": return config.progressBar(exp, required);
            case "progress_percent":
            case "exp_progress_percent":
            case "exp_progress_percentage": return config.percentFormatted(exp, required);
            case "max_level": return Long.toString(config.maxLevel());
            default: return null;
        }
    }

    private static boolean isLevelingKey(String key) {
        return "level".equals(key) || "level_format".equals(key) || "level_formatted".equals(key)
                || "exp".equals(key) || "exp_formatted".equals(key) || "exp_formatted_short".equals(key)
                || "exp_required".equals(key) || "exp_required_formatted".equals(key)
                || "exp_required_formatted_short".equals(key) || "progress_bar".equals(key)
                || "exp_progress_bar".equals(key) || "progress_percent".equals(key)
                || "exp_progress_percent".equals(key) || "exp_progress_percentage".equals(key)
                || "max_level".equals(key);
    }

    // ================= Internals =================

    /**
     * Brings a freshly loaded player up to the configured starting level/EXP.
     * A new stats record has level {@code 0} (the "uninitialized" marker); the
     * first access bumps it to {@code startLevel} with {@code startExp}.
     */
    private void ensureInitialized(PlayerStats stats) {
        if (stats.getLevel() < config.startLevel()) {
            stats.setLevel(config.startLevel());
            stats.setExp(Math.max(stats.getExp(), config.startExp()));
        }
    }

    /** Applies a mutation to the player's stats (cached if online, else loaded), saves, runs rewards. */
    private long mutate(UUID uuid, String name, Player player, Mutator mutator) {
        PlayerStats stats = main.getStatsManager().getCached(uuid);
        List<Long> rewarded;
        if (stats != null) {
            ensureInitialized(stats);
            rewarded = mutator.apply(stats);
            main.getStatsManager().save(uuid);
        } else if (Bukkit.getPlayer(uuid) == null) {
            // Offline: load synchronously, modify, save asynchronously.
            final List<Long> held = new ArrayList<Long>();
            stats = main.getStatsManager().modifyOffline(uuid, name, new java.util.function.Consumer<PlayerStats>() {
                @Override
                public void accept(PlayerStats s) {
                    ensureInitialized(s);
                    held.addAll(mutator.apply(s));
                }
            });
            rewarded = held;
        } else {
            // Online but stats still loading (right at join): skip to avoid racing the load.
            return -1L;
        }
        if (rewarded != null && !rewarded.isEmpty() && config.rewardsEnabled()) {
            runRewards(player, name, rewarded);
        }
        if (stats != null) {
            ensureInitialized(stats);
            return stats.getLevel();
        }
        // Offline: the level is in the object modifyOffline loaded; re-read via getLevel fallback.
        return config.startLevel();
    }

    private List<Long> applyAddExp(PlayerStats stats, long amount) {
        long exp = Math.max(0L, stats.getExp() + amount);
        return resolveLevelUps(stats, exp, 0L);
    }

    private List<Long> applySetExp(PlayerStats stats, long amount) {
        long exp = Math.max(0L, amount);
        return resolveLevelUps(stats, exp, 0L);
    }

    private List<Long> applyRemoveExp(PlayerStats stats, long amount) {
        stats.setExp(Math.max(0L, stats.getExp() - amount));
        return Collections.emptyList();
    }

    private List<Long> applyAddLevel(PlayerStats stats, long amount) {
        if (amount <= 0L) {
            return Collections.emptyList();
        }
        long max = config.maxLevel();
        long current = stats.getLevel();
        long target = max > 0 ? Math.min(max, current + amount) : current + amount;
        List<Long> rewarded = new ArrayList<Long>();
        for (long lv = current + 1; lv <= target; lv++) {
            rewarded.add(Long.valueOf(lv));
        }
        stats.setLevel(target);
        stats.setExp(0L);
        return rewarded;
    }

    private List<Long> applySetLevel(PlayerStats stats, long amount) {
        long start = config.startLevel();
        long max = config.maxLevel();
        long target = amount;
        if (target < start) {
            target = start;
        }
        if (max > 0 && target > max) {
            target = max;
        }
        long current = stats.getLevel();
        List<Long> rewarded = new ArrayList<Long>();
        if (target > current) {
            for (long lv = current + 1; lv <= target; lv++) {
                rewarded.add(Long.valueOf(lv));
            }
        }
        stats.setLevel(target);
        stats.setExp(0L);
        return rewarded;
    }

    private List<Long> applyRemoveLevel(PlayerStats stats, long amount) {
        if (amount <= 0L) {
            return Collections.emptyList();
        }
        long start = config.startLevel();
        long target = Math.max(start, stats.getLevel() - amount);
        stats.setLevel(target);
        stats.setExp(0L);
        return Collections.emptyList();
    }

    /**
     * Absorbs EXP into the player's level, looping level-ups while enough EXP
     * is banked. Sets the final level/EXP and returns every level reached.
     */
    private List<Long> resolveLevelUps(PlayerStats stats, long exp, long unused) {
        long level = stats.getLevel();
        long max = config.maxLevel();
        List<Long> rewarded = new ArrayList<Long>();
        while ((max <= 0 || level < max) && config.requiredExp(level) > 0L && exp >= config.requiredExp(level)) {
            exp -= config.requiredExp(level);
            level++;
            rewarded.add(Long.valueOf(level));
        }
        if (max > 0 && level >= max) {
            // At the cap: no further accumulation.
            exp = 0L;
        }
        stats.setLevel(level);
        stats.setExp(exp);
        return rewarded;
    }

    /** Runs the level-up rewards (commands + messages) for each reached level. */
    private void runRewards(Player player, String name, List<Long> rewarded) {
        for (Long levelBox : rewarded) {
            long level = levelBox.longValue();
            for (LevelingConfig.LevelReward reward : config.rewards()) {
                if (!reward.levels.contains(level)) {
                    continue;
                }
                executeCommands(reward.commands, player, name, level);
                executeMessages(reward.messages, player, name, level);
            }
        }
    }

    private void executeCommands(List<String> commands, Player player, String name, long level) {
        for (String raw : commands) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            String line = colorize(raw).replace("{player}", name).replace("{level}", Long.toString(level));
            String body;
            CommandSender executor;
            String lower = line.trim().toLowerCase();
            if (lower.startsWith("[player]")) {
                body = line.trim().substring("[player]".length()).trim();
                if (player == null) {
                    continue; // Offline: cannot run as the player.
                }
                executor = player;
            } else if (lower.startsWith("[console]")) {
                body = line.trim().substring("[console]".length()).trim();
                executor = Bukkit.getConsoleSender();
            } else {
                body = line.trim();
                executor = Bukkit.getConsoleSender();
            }
            if (!body.isEmpty()) {
                Bukkit.dispatchCommand(executor, body);
            }
        }
    }

    private void executeMessages(List<String> messages, Player player, String name, long level) {
        if (player == null) {
            return; // Offline: nowhere to send messages.
        }
        for (String raw : messages) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            String line = colorize(raw).replace("{player}", name).replace("{level}", Long.toString(level));
            String lower = line.trim().toLowerCase();
            if (lower.startsWith("[sound]")) {
                playSound(player, line.trim().substring("[sound]".length()).trim());
            } else if (lower.startsWith("[actionbar]")) {
                sendActionBar(player, line.trim().substring("[actionbar]".length()).trim());
            } else if (lower.startsWith("[title]")) {
                sendTitle(player, line.trim().substring("[title]".length()).trim());
            } else if (lower.startsWith("[player]")) {
                player.sendMessage(line.trim().substring("[player]".length()).trim());
            } else {
                player.sendMessage(line.trim());
            }
        }
    }

    private void sendActionBar(Player player, String text) {
        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
        } catch (Throwable ignored) {
            // Very old server without the spigot action-bar API: fall back to chat.
            player.sendMessage(text);
        }
    }

    private void sendTitle(Player player, String text) {
        String title = text;
        String subtitle = "";
        int newline = text.indexOf("<n>");
        if (newline >= 0) {
            title = text.substring(0, newline);
            subtitle = text.substring(newline + 3);
        }
        try {
            // Short title (0.25s in / 2s stay / 0.5s out): a level-up flash,
            // not a banner that hides the action.
            player.sendTitle(title, subtitle, 5, 40, 10);
        } catch (Throwable ignored) {
            player.sendMessage(title + (subtitle.isEmpty() ? "" : " " + subtitle));
        }
    }

    /**
     * Plays a sound described by a {@code [sound] NAME pitch volume} reward
     * line. {@code NAME} is an {@link XSound} constant (case/spaces ignored);
     * {@code pitch} and {@code volume} default to 1. Unknown sounds are skipped
     * silently so a bad name never breaks a level-up.
     */
    private void playSound(Player player, String spec) {
        if (player == null || spec == null || spec.isEmpty()) {
            return;
        }
        String[] parts = spec.split("\\s+");
        XSound sound = parseXSound(parts[0]);
        if (sound == null) {
            return;
        }
        float pitch = 1f;
        float volume = 1f;
        try {
            if (parts.length > 1) {
                pitch = Float.parseFloat(parts[1]);
            }
        } catch (NumberFormatException ignored) {
        }
        try {
            if (parts.length > 2) {
                volume = Float.parseFloat(parts[2]);
            }
        } catch (NumberFormatException ignored) {
        }
        Sounds.play(sound, player, volume, pitch);
    }

    private static XSound parseXSound(String name) {
        if (name == null) {
            return null;
        }
        String norm = name.trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return XSound.valueOf(norm);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String colorize(String text) {
        return text == null ? "" : org.bukkit.ChatColor.translateAlternateColorCodes('&', text);
    }

    /** A mutation that modifies a stats record and returns the levels it just reached. */
    private interface Mutator {
        List<Long> apply(PlayerStats stats);
    }
}
