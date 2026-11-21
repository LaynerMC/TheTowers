package com.thetowers.arena;

import com.thetowers.Main;
import com.thetowers.lang.Messages;
import java.io.File;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Loading and execution of the reward commands defined in rewards.yml.
 *
 * <p>Each event section ({@code winner}/{@code loser}/{@code draw},
 * {@code point}, {@code on-kill}, {@code on-death}) may now carry, besides the
 * legacy {@code commands:} list, two direct fields handled in-process (so the
 * exact amount granted can be echoed back to the player):
 * <ul>
 *   <li>{@code exp: "MIN-MAX"} or {@code exp: "N"} - EXP granted to
 *       {@code %player%}; deducted for {@code on-death} and {@code loser};</li>
 *   <li>{@code coins: "MIN-MAX"} or {@code coins: "N"} - coins granted to
 *       {@code %player%}. Coins are NEVER deducted.</li>
 * </ul>
 * Each grant is echoed in chat with its reason, e.g.
 * {@code &3+45 exp &7(Player killed)} or {@code &6+125 coins &7(Game Win)} -
 * see messages.yml {@code rewards.exp.gain} / {@code rewards.exp.loss} /
 * {@code rewards.coins.gain} and the {@code rewards.reason.*} keys.
 */
public final class RewardManager {

    private final Main main;
    private FileConfiguration config;

    /** Matches %random:MIN-MAX% tokens in reward commands (replaced with a random int in [min, max]). */
    private static final java.util.regex.Pattern RANDOM =
            java.util.regex.Pattern.compile("%random:(\\d+)-(\\d+)%");

    public RewardManager(Main main) {
        this.main = main;
        load();
    }

    public void load() {
        File file = com.thetowers.config.CustomConfig.ensureModuleFile(main, "modules/rewards.yml", true);
        config = YamlConfiguration.loadConfiguration(file);
    }

    /**
     * Replaces every {@code %random:MIN-MAX%} token in a reward command with a
     * random integer in {@code [min, max]} (inclusive). Lets rewards.yml grant
     * a RANGE of EXP, e.g. {@code leveling addexp %random:3-5% %player%}.
     */
    private String resolveRandom(String command) {
        if (command == null) {
            return null;
        }
        java.util.regex.Matcher m = RANDOM.matcher(command);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int min = Integer.parseInt(m.group(1));
            int max = Integer.parseInt(m.group(2));
            int lo = Math.min(min, max);
            int hi = Math.max(min, max);
            int val = ThreadLocalRandom.current().nextInt(lo, hi + 1);
            m.appendReplacement(sb, Integer.toString(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Resolves an {@code exp}/{@code coins} spec to a single amount: a range
     * {@code "MIN-MAX"} (random in [min,max]) or a fixed {@code "N"}. Returns 0
     * for blank/invalid specs, so an absent field is a clean no-op.
     */
    private int resolveAmount(String spec) {
        if (spec == null) {
            return 0;
        }
        String s = spec.trim();
        if (s.isEmpty()) {
            return 0;
        }
        int dash = s.indexOf('-');
        try {
            if (dash <= 0) {
                // No dash, or a leading dash: treat as a fixed value.
                return Math.max(0, Integer.parseInt(s));
            }
            int min = Integer.parseInt(s.substring(0, dash).trim());
            int max = Integer.parseInt(s.substring(dash + 1).trim());
            int lo = Math.min(min, max);
            int hi = Math.max(min, max);
            return ThreadLocalRandom.current().nextInt(lo, hi + 1);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Grants EXP to the player and sends the matching chat line. The gain line
     * is sent BEFORE the EXP is added, so a level-up message (if any) follows it
     * in chat instead of the reverse.
     *
     * @param spec      the {@code exp:} spec ("MIN-MAX" or "N"); blank = no-op
     * @param deduct    true to remove EXP instead of adding (deaths / losses)
     * @param reasonKey messages.yml key for the parenthesised reason
     */
    private void awardExp(Player player, String spec, boolean deduct, String reasonKey) {
        if (player == null) {
            return;
        }
        int amount = resolveAmount(spec);
        if (amount <= 0) {
            return;
        }
        boolean online = player.isOnline();
        String reason = Messages.tr(reasonKey);
        if (deduct) {
            if (online) {
                player.sendMessage(Messages.tr("rewards.exp.loss", "amount", amount, "reason", reason));
            }
            main.getLevelingManager().removeExp(player, amount);
        } else {
            if (online) {
                player.sendMessage(Messages.tr("rewards.exp.gain", "amount", amount, "reason", reason));
            }
            main.getLevelingManager().addExp(player, amount);
        }
    }

    /**
     * Grants coins to the player and sends the matching chat line. Coins are
     * never deducted: a {@code deduct} death/loss section simply has no coins
     * field, and this method only ever adds.
     */
    private void awardCoins(Player player, String spec, String reasonKey) {
        if (player == null) {
            return;
        }
        int amount = resolveAmount(spec);
        if (amount <= 0) {
            return;
        }
        main.getCoinsManager().add(player, amount);
        if (player.isOnline()) {
            player.sendMessage(Messages.tr("rewards.coins.gain", "amount", amount,
                    "reason", Messages.tr(reasonKey)));
        }
    }

    public void execute(Arena arena, Player player, String winningTeam) {
        String team = arena.getTeam(player);
        String category;

        if (winningTeam == null) {
            category = "draw";
        } else if (winningTeam.equals(team)) {
            category = "winner";
        } else {
            category = "loser";
        }

        List<String> commands = config.getStringList(category + ".commands");
        String teamName = team == null ? "" : Arena.displayName(team);
        String winnerName = winningTeam == null ? "" : Arena.displayName(winningTeam);

        for (String command : commands) {
            if (command == null || command.trim().isEmpty()) {
                continue;
            }

            String parsed = command
                    .replace("%player%", player.getName())
                    .replace("%arena%", arena.getName())
                    .replace("%team%", teamName)
                    .replace("%winner%", winnerName);

            parsed = resolveRandom(parsed);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }

        // Direct EXP + coins (in-process, so the exact amount is shown back).
        // Losers only ever lose EXP; winners/draws gain EXP + coins.
        boolean deduct = "loser".equals(category);
        String reasonKey = "winner".equals(category) ? "rewards.reason.win"
                : "loser".equals(category) ? "rewards.reason.loss"
                : "rewards.reason.draw";
        awardExp(player, config.getString(category + ".exp"), deduct, reasonKey);
        if (!deduct) {
            awardCoins(player, config.getString(category + ".coins"), reasonKey);
        }
    }

    /**
     * Runs the commands of the {@code point:} section of rewards.yml, for
     * the player who just scored a point. Called from
     * {@link Arena#mark(Player, String)} before a possible end of the game.
     *
     * @param arena   running arena
     * @param player  player who just scored
     * @param team    key of their team (e.g. {@code red})
     * @param score   new score of their team (after the point)
     * @param goal    points needed to win in this arena
     */
    public void executePoint(Arena arena, Player player, String team, int score, int goal) {
        List<String> commands = config.getStringList("point.commands");
        String teamName = team == null ? "" : Arena.displayName(team);
        String scoreText = Integer.toString(score);
        String goalText = Integer.toString(goal);

        for (String command : commands) {
            if (command == null || command.trim().isEmpty()) {
                continue;
            }

            String parsed = command
                    .replace("%player%", player.getName())
                    .replace("%arena%", arena.getName())
                    .replace("%team%", teamName)
                    .replace("%score%", scoreText)
                    .replace("%goal%", goalText);

            parsed = resolveRandom(parsed);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }

        awardExp(player, config.getString("point.exp"), false, "rewards.reason.score");
        awardCoins(player, config.getString("point.coins"), "rewards.reason.score");
    }

    /**
     * Runs the {@code on-kill:} section of rewards.yml, for the player who
     * just killed another participant. Called from {@link Arena#handleDeath}
     * right after the killer's kill is recorded.
     *
     * <p>Available placeholders:
     * <ul>
     *   <li>{@code %killer%} - the killer's name</li>
     *   <li>{@code %victim%} - the victim's name</li>
     *   <li>{@code %player%}  - the killer (alias, so the same command works as
     *       a generic "reward this player" line, e.g.
     *       {@code leveling addexp %player% 50})</li>
     *   <li>{@code %arena%}, {@code %team%} - the killer's arena and team</li>
     * </ul>
     */
    public void executeKill(Arena arena, Player killer, Player victim) {
        List<String> commands = config.getStringList("on-kill.commands");
        String team = arena.getTeam(killer);
        String teamName = team == null ? "" : Arena.displayName(team);
        String killerName = killer.getName();
        String victimName = victim == null ? "" : victim.getName();
        for (String command : commands) {
            if (command == null || command.trim().isEmpty()) {
                continue;
            }
            String parsed = command
                    .replace("%player%", killerName)
                    .replace("%killer%", killerName)
                    .replace("%victim%", victimName)
                    .replace("%arena%", arena.getName())
                    .replace("%team%", teamName);
            parsed = resolveRandom(parsed);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }

        awardExp(killer, config.getString("on-kill.exp"), false, "rewards.reason.kill");
        awardCoins(killer, config.getString("on-kill.coins"), "rewards.reason.kill");
    }

    /**
     * Runs the {@code on-death:} section of rewards.yml, for the player who
     * just died. Called from {@link Arena#handleDeath} right after the
     * victim's death is recorded (the killer may be {@code null} for a
     * suicide or environmental death).
     *
     * <p>Placeholders: {@code %player%} and {@code %victim%} (the dead
     * player), {@code %killer%} (empty when there is none), {@code %arena%},
     * {@code %team%} (the victim's team).
     */
    public void executeDeath(Arena arena, Player victim, Player killer) {
        List<String> commands = config.getStringList("on-death.commands");
        String team = arena.getTeam(victim);
        String teamName = team == null ? "" : Arena.displayName(team);
        String victimName = victim.getName();
        String killerName = killer == null ? "" : killer.getName();
        for (String command : commands) {
            if (command == null || command.trim().isEmpty()) {
                continue;
            }
            String parsed = command
                    .replace("%player%", victimName)
                    .replace("%victim%", victimName)
                    .replace("%killer%", killerName)
                    .replace("%arena%", arena.getName())
                    .replace("%team%", teamName);
            parsed = resolveRandom(parsed);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }

        // EXP may be lost on death; coins are NEVER deducted.
        awardExp(victim, config.getString("on-death.exp"), true, "rewards.reason.death");
    }
}
