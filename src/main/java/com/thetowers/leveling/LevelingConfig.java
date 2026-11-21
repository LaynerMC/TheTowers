package com.thetowers.leveling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;

import com.thetowers.Main;
import com.thetowers.config.CustomConfig;

/**
 * Configuration and translations for the leveling system, all loaded from
 * {@code leveling.yml} (settings, formula, per-level overrides, level display
 * formats, progress bar, number formatting, level-up rewards and every
 * message the module sends).
 *
 * <p>The module keeps its own message table here on purpose: leveling text
 * never goes into {@code messages.yml}, so the whole subsystem can be tuned
 * or rewritten without touching the central TheTowers code.
 */
public final class LevelingConfig {

    private final Main main;
    private CustomConfig file;

    // ----- settings -----
    private boolean enabled;
    private long startLevel;
    private long startExp;
    private long maxLevel;
    private String generalFormula;
    private Map<Long, String> perLevelFormulas;

    // ----- display -----
    private List<LevelFormat> levelFormats;
    private String defaultLevelFormat;
    private ProgressBarStyle progressBar;
    private LevelingFormatter formatter;

    // ----- rewards -----
    private boolean rewardsEnabled;
    private List<LevelReward> rewards;

    public LevelingConfig(Main main) {
        this.main = main;
        load();
    }

    /** (Re)reads leveling.yml from disk. */
    public void load() {
        file = new CustomConfig(main, "modules/leveling.yml", true);
        ConfigurationSection root = file.get();

        enabled = root.getBoolean("enabled", true);
        startLevel = root.getLong("starting.level", 1L);
        startExp = root.getLong("starting.experience", 0L);
        maxLevel = root.getLong("maximum.level", 100L);

        ConfigurationSection exp = root.getConfigurationSection("experience");
        generalFormula = exp == null ? "100" : exp.getString("general-formula", "100");
        perLevelFormulas = new HashMap<Long, String>();
        ConfigurationSection perLevel = exp == null ? null : exp.getConfigurationSection("per-level");
        if (perLevel != null) {
            for (String key : perLevel.getKeys(false)) {
                try {
                    long level = Long.parseLong(key);
                    Object value = perLevel.get(key);
                    perLevelFormulas.put(Long.valueOf(level), value == null ? null : String.valueOf(value));
                } catch (NumberFormatException ignored) {
                    // A non-numeric key under per-level: ignored.
                }
            }
        }

        levelFormats = new ArrayList<LevelFormat>();
        ConfigurationSection levelFormatsSection = root.getConfigurationSection("level-formats");
        if (levelFormatsSection != null) {
            defaultLevelFormat = levelFormatsSection.getString("default", "&7[%level%]");
            for (Map<?, ?> entry : levelFormatsSection.getMapList("ranges")) {
                LevelFormat lf = LevelFormat.fromMap(entry);
                if (lf != null) {
                    levelFormats.add(lf);
                }
            }
        } else {
            defaultLevelFormat = "&7[%level%]";
        }

        progressBar = ProgressBarStyle.fromConfig(root.getConfigurationSection("progress-bar"));
        formatter = buildFormatter(root.getConfigurationSection("formatting"));

        rewardsEnabled = root.getBoolean("rewards.enabled", true);
        rewards = new ArrayList<LevelReward>();
        ConfigurationSection rewardList = root.getConfigurationSection("rewards.list");
        if (rewardList != null) {
            for (String name : rewardList.getKeys(false)) {
                ConfigurationSection section = rewardList.getConfigurationSection(name);
                if (section != null) {
                    rewards.add(LevelReward.fromConfig(section));
                }
            }
        }
    }

    // ================= Accessors =================

    public boolean isEnabled() {
        return enabled;
    }

    public long startLevel() {
        return Math.max(1L, startLevel);
    }

    public long startExp() {
        return Math.max(0L, startExp);
    }

    public long maxLevel() {
        return maxLevel;
    }

    public LevelingFormatter formatter() {
        return formatter;
    }

    public boolean rewardsEnabled() {
        return rewardsEnabled;
    }

    public List<LevelReward> rewards() {
        return rewards;
    }

    /**
     * EXP required to advance from {@code level} to {@code level + 1}.
     * Returns {@code 0} at or above the maximum level (no further progress).
     */
    public long requiredExp(long level) {
        if (maxLevel > 0 && level >= maxLevel) {
            return 0L;
        }
        String formula = perLevelFormulas.get(Long.valueOf(level));
        if (formula == null || formula.trim().isEmpty()) {
            formula = generalFormula;
        }
        try {
            Map<String, Double> vars = new HashMap<String, Double>();
            vars.put("level", (double) level);
            vars.put("max_level", (double) maxLevel);
            vars.put("min_level", (double) startLevel());
            vars.put("min_exp", (double) startExp());
            double result = FormulaEvaluator.evaluate(formula, vars);
            long rounded = Math.round(result);
            return Math.max(0L, rounded);
        } catch (RuntimeException e) {
            main.getLogger().warning("Leveling: invalid formula for level " + level
                    + " (\"" + formula + "\"): " + e.getMessage());
            return 0L;
        }
    }

    /** The matched display format for a level, with {@code %level%} filled and colors translated. */
    public String levelFormat(long level) {
        for (LevelFormat lf : levelFormats) {
            if (lf.contains(level)) {
                return color(lf.format.replace("%level%", Long.toString(level)));
            }
        }
        return color(defaultLevelFormat.replace("%level%", Long.toString(level)));
    }

    /** The progress bar for a current/required EXP pair, colors translated. */
    public String progressBar(long exp, long required) {
        int length = Math.max(1, progressBar.length);
        long safeExp = Math.max(0L, exp);
        long safeReq = required <= 0L ? 1L : required;
        double ratio = Math.max(0d, Math.min(1d, (double) safeExp / (double) safeReq));
        int filled = (int) Math.round(ratio * length);
        if (filled > length) {
            filled = length;
        }
        int empty = length - filled;
        StringBuilder bar = new StringBuilder();
        bar.append(color(progressBar.bracketOpen));
        bar.append(color(progressBar.filledColor));
        for (int i = 0; i < filled; i++) {
            bar.append(progressBar.filledChar);
        }
        bar.append(color(progressBar.emptyColor));
        for (int i = 0; i < empty; i++) {
            bar.append(progressBar.emptyChar);
        }
        bar.append(color(progressBar.bracketClose));
        return bar.toString();
    }

    /** Whole-number percentage of progress toward the next level, clamped 0..100. */
    public String percent(long exp, long required) {
        if (required <= 0L) {
            return "100";
        }
        long safeExp = Math.max(0L, exp);
        double ratio = Math.max(0d, Math.min(1d, (double) safeExp / (double) required));
        return Long.toString((long) Math.round(ratio * 100d));
    }

    /**
     * The formatted percentage string (percent-format with {percent} filled and
     * colors translated), or empty when show-percent is false. Use this for the
     * separate %thetowers_exp_progress_percentage% placeholder placed after the
     * bar in the scoreboard - the bar itself never carries the percentage.
     */
    public String percentFormatted(long exp, long required) {
        if (!progressBar.showPercent) {
            return "";
        }
        return color(progressBar.percentFormat.replace("{percent}", percent(exp, required)));
    }

    // ================= Messages (translations live here, not in messages.yml) =================

    /** A single message line (or the first line of a list), with {var} replaced and colors translated. */
    public String tr(String key, Object... vars) {
        Object raw = file.get().get("messages." + key);
        String text;
        if (raw instanceof List) {
            List<?> list = (List<?>) raw;
            text = list.isEmpty() ? "" : String.valueOf(list.get(0));
        } else if (raw == null) {
            return "";
        } else {
            text = String.valueOf(raw);
        }
        return color(replaceVars(text, vars));
    }

    /** A message that is a list of lines, with {var} replaced and colors translated. */
    public List<String> trList(String key, Object... vars) {
        List<String> out = new ArrayList<String>();
        List<?> raw = file.get().getStringList("messages." + key);
        if (raw.isEmpty()) {
            String single = file.get().getString("messages." + key);
            if (single != null) {
                out.add(color(replaceVars(single, vars)));
            }
            return out;
        }
        for (Object line : raw) {
            out.add(color(replaceVars(String.valueOf(line), vars)));
        }
        return out;
    }

    /** Replaces {@code {name}} tokens with the matching var value (vars come in name/value pairs). */
    private static String replaceVars(String text, Object... vars) {
        if (text == null || text.indexOf('{') < 0 || vars == null || vars.length == 0) {
            return text;
        }
        String result = text;
        for (int i = 0; i + 1 < vars.length; i += 2) {
            result = result.replace("{" + String.valueOf(vars[i]) + "}", String.valueOf(vars[i + 1]));
        }
        return result;
    }

    private static String color(String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }

    // ================= Internal holders =================

    private LevelingFormatter buildFormatter(ConfigurationSection section) {
        String thousands = ",";
        String shortDecimal = ",";
        int shortDecimals = 1;
        List<LevelingFormatter.Abbreviation> abbreviations = new ArrayList<LevelingFormatter.Abbreviation>();
        if (section != null) {
            thousands = section.getString("thousands-separator", ",");
            shortDecimal = section.getString("short-decimal-separator", ",");
            shortDecimals = section.getInt("short-decimals", 1);
            List<?> raw = section.getList("abbreviations");
            if (raw != null) {
                for (Object entry : raw) {
                    if (entry instanceof Map) {
                        Map<?, ?> map = (Map<?, ?>) entry;
                        Object thresholdValue = map.get("threshold");
                        Object suffixValue = map.get("suffix");
                        if (thresholdValue != null && suffixValue != null) {
                            try {
                                long threshold = Long.parseLong(String.valueOf(thresholdValue));
                                abbreviations.add(new LevelingFormatter.Abbreviation(threshold, String.valueOf(suffixValue)));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
        }
        if (abbreviations.isEmpty()) {
            abbreviations.add(new LevelingFormatter.Abbreviation(1_000_000_000L, "B"));
            abbreviations.add(new LevelingFormatter.Abbreviation(1_000_000L, "M"));
            abbreviations.add(new LevelingFormatter.Abbreviation(1_000L, "k"));
        }
        return new LevelingFormatter(thousands, shortDecimal, shortDecimals, abbreviations);
    }

    /** A level display format bound to a range, e.g. {@code 1-25 -> "&8[&a%level%&2Ω&8]"}. */
    private static final class LevelFormat {
        final long min;
        final long max;
        final String format;

        private LevelFormat(long min, long max, String format) {
            this.min = min;
            this.max = max;
            this.format = format;
        }

        boolean contains(long level) {
            return level >= min && level <= max;
        }

        static LevelFormat fromMap(Map<?, ?> map) {
            Object range = map.get("range");
            Object format = map.get("format");
            if (range == null || format == null) {
                return null;
            }
            long[] bounds = parseRange(String.valueOf(range));
            if (bounds == null) {
                return null;
            }
            return new LevelFormat(bounds[0], bounds[1], String.valueOf(format));
        }
    }

    /** Parses a range string ("1-25", "1", "25") into [min, max]; {@code null} if invalid. */
    private static long[] parseRange(String range) {
        if (range == null || range.trim().isEmpty()) {
            return null;
        }
        String trimmed = range.trim();
        int dash = trimmed.indexOf('-');
        try {
            if (dash < 0) {
                long v = Long.parseLong(trimmed);
                return new long[] { v, v };
            }
            long min = Long.parseLong(trimmed.substring(0, dash).trim());
            long max = Long.parseLong(trimmed.substring(dash + 1).trim());
            return new long[] { Math.min(min, max), Math.max(min, max) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Progress bar visual settings. */
    private static final class ProgressBarStyle {
        final int length;
        final String filledColor;
        final String emptyColor;
        final String bracketOpen;
        final String bracketClose;
        final char filledChar;
        final char emptyChar;
        final boolean showPercent;
        final String percentFormat;

        private ProgressBarStyle(int length, String filledColor, String emptyColor,
                String bracketOpen, String bracketClose, char filledChar, char emptyChar,
                boolean showPercent, String percentFormat) {
            this.length = length;
            this.filledColor = filledColor;
            this.emptyColor = emptyColor;
            this.bracketOpen = bracketOpen;
            this.bracketClose = bracketClose;
            this.filledChar = filledChar;
            this.emptyChar = emptyChar;
            this.showPercent = showPercent;
            this.percentFormat = percentFormat;
        }

        static ProgressBarStyle fromConfig(ConfigurationSection section) {
            if (section == null) {
                return new ProgressBarStyle(8, "&2", "&7", "&8[", "&8]", '=', '=', true, "&7{percent}%");
            }
            return new ProgressBarStyle(
                    section.getInt("length", 8),
                    section.getString("filled-color", "&2"),
                    section.getString("empty-color", "&7"),
                    section.getString("bracket-open", "&8["),
                    section.getString("bracket-close", "&8]"),
                    section.getString("filled-char", "=").charAt(0),
                    section.getString("empty-char", "=").charAt(0),
                    section.getBoolean("show-percent", true),
                    section.getString("percent-format", "&7{percent}%"));
        }
    }

    /** A level-up reward: the levels it covers and the commands/messages to run. */
    static final class LevelReward {
        final Set<Long> levels;
        final List<String> commands;
        final List<String> messages;

        private LevelReward(Set<Long> levels, List<String> commands, List<String> messages) {
            this.levels = levels;
            this.commands = commands == null ? Collections.<String>emptyList() : commands;
            this.messages = messages == null ? Collections.<String>emptyList() : messages;
        }

        static LevelReward fromConfig(ConfigurationSection section) {
            Set<Long> levels = parseLevels(section.get("levels"));
            List<String> commands = section.getStringList("commands");
            List<String> messages = section.getStringList("messages");
            return new LevelReward(levels, commands, messages);
        }
    }

    /** Parses a {@code levels} value: a range "1-8", a comma list "1, 8", a list [9,10,11], or a single number. */
    @SuppressWarnings("unchecked")
    private static Set<Long> parseLevels(Object value) {
        Set<Long> out = new HashSet<Long>();
        if (value == null) {
            return out;
        }
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                addRange(out, String.valueOf(item));
            }
        } else {
            addRange(out, String.valueOf(value));
        }
        return out;
    }

    private static void addRange(Set<Long> out, String text) {
        if (text == null) {
            return;
        }
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int dash = trimmed.indexOf('-');
            try {
                if (dash < 0) {
                    out.add(Long.parseLong(trimmed));
                } else {
                    long min = Long.parseLong(trimmed.substring(0, dash).trim());
                    long max = Long.parseLong(trimmed.substring(dash + 1).trim());
                    for (long v = Math.min(min, max); v <= Math.max(min, max); v++) {
                        out.add(v);
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }
}
