package com.thetowers.tops;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.scheduler.BukkitRunnable;

import com.thetowers.Main;
import com.thetowers.stats.PlayerStats;

/**
 * Cache of the leaderboards backing the
 * {@code %thetowers_top_<stat>_<rank>_<period>%} placeholders.
 *
 * <p>Four stats ({@code kills}, {@code wins}, {@code points}, {@code money})
 * and three periods ({@code alltime}, {@code weekly}, {@code monthly}) are
 * kept in memory, each a list of at most {@code tops.cache-size} (10) entries.
 * The cache is rebuilt every {@code tops.refresh-seconds} seconds by an async
 * task that queries the stats storage and hands the results back to the main
 * thread.
 *
 * <p>The LuckPerms prefix is saved for EVERY period (alltime, weekly and
 * monthly): the weekly/monthly periodic table stores it alongside the deltas,
 * so the leaderboard shows the rank color even for those periods.
 *
 * <p>Money is a cumulative balance stored in the stats table: its
 * {@code weekly} and {@code monthly} lists are the SAME as alltime (a balance
 * is not a per-period counter), so every period shows the current top.
 *
 * <p>Thread safety: the cache is a {@link ConcurrentHashMap} of immutable
 * lists. PlaceholderAPI may call {@link #getEntry} from an asynchronous
 * thread; the read never blocks and always sees a consistent list.
 */
public class TopManager {

    private static final List<String> INT_STATS = Arrays.asList("kills", "wins", "points");
    private static final List<String> PERIODS = Arrays.asList("alltime", "weekly", "monthly");
    private static final String MONEY = "money";

    private final Main main;
    private final ConcurrentHashMap<String, List<TopEntry>> cache =
            new ConcurrentHashMap<String, List<TopEntry>>();
    private BukkitRunnable task;

    public TopManager(Main main) {
        this.main = main;
        // Seed every list as empty so the placeholders resolve immediately.
        for (String stat : allStats()) {
            for (String period : PERIODS) {
                cache.put(key(stat, period), Collections.<TopEntry>emptyList());
            }
        }
        start();
        // First refresh right away (async), so the placeholders are not empty
        // for the whole first interval.
        refresh();
    }

    /** (Re)starts the refresh task and runs one refresh immediately. */
    public void reload() {
        start();
        refresh();
    }

    private void start() {
        if (task != null) {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {
                // Already cancelled.
            }
        }
        int seconds = Math.max(5, main.getConfig().getInt("tops.refresh-seconds", 30));
        task = new BukkitRunnable() {
            @Override
            public void run() {
                refresh();
            }
        };
        task.runTaskTimer(main, seconds * 20L, seconds * 20L);
    }

    /** Rebuilds every leaderboard. Queries run async; the cache is updated on the main thread. */
    private void refresh() {
        final int limit = Math.max(1, Math.min(10, main.getConfig().getInt("tops.cache-size", 10)));
        for (final String stat : INT_STATS) {
            final String column = stat;
            main.getStatsManager().top(column, limit, new Consumer<List<PlayerStats>>() {
                @Override
                public void accept(List<PlayerStats> list) {
                    put(stat, "alltime", toEntries(list, column));
                }
            });
            main.getStatsManager().topPeriodic("week", column, limit, new Consumer<List<PlayerStats>>() {
                @Override
                public void accept(List<PlayerStats> list) {
                    put(stat, "weekly", toEntries(list, column));
                }
            });
            main.getStatsManager().topPeriodic("month", column, limit, new Consumer<List<PlayerStats>>() {
                @Override
                public void accept(List<PlayerStats> list) {
                    put(stat, "monthly", toEntries(list, column));
                }
            });
        }
        // Money: the balance is cumulative (not per-period), so every period
        // shows the same current top. The prefix comes from the stats table.
        main.getStatsManager().topCoins(limit, new Consumer<List<PlayerStats>>() {
            @Override
            public void accept(List<PlayerStats> list) {
                List<TopEntry> entries = toCoinEntries(list);
                put(MONEY, "alltime", entries);
                put(MONEY, "weekly", entries);
                put(MONEY, "monthly", entries);
            }
        });
    }

    private List<TopEntry> toEntries(List<PlayerStats> list, String column) {
        List<TopEntry> entries = new ArrayList<TopEntry>();
        for (PlayerStats stats : list) {
            String value = String.valueOf(stats.value(column));
            entries.add(new TopEntry(stats.getName(), stats.getLuckpermsPrefix(), value));
        }
        return Collections.unmodifiableList(entries);
    }

    private List<TopEntry> toCoinEntries(List<PlayerStats> list) {
        List<TopEntry> entries = new ArrayList<TopEntry>();
        for (PlayerStats stats : list) {
            String value;
            if (main.getCoinsManager() != null) {
                value = main.getCoinsManager().format(stats.getCoins());
            } else {
                value = String.valueOf(stats.getCoins());
            }
            entries.add(new TopEntry(stats.getName(), stats.getLuckpermsPrefix(), value));
        }
        return Collections.unmodifiableList(entries);
    }

    private void put(String stat, String period, List<TopEntry> entries) {
        cache.put(key(stat, period), entries);
    }

    /**
     * Entry at the given rank (1-based), or {@code null} if nobody holds it.
     * Safe to call from any thread.
     */
    public TopEntry getEntry(String stat, int rank, String period) {
        if (rank < 1 || rank > 10) {
            return null;
        }
        List<TopEntry> list = cache.get(key(stat, period));
        if (list == null || rank > list.size()) {
            return null;
        }
        return list.get(rank - 1);
    }

    private static String key(String stat, String period) {
        return stat + "_" + period;
    }

    private static List<String> allStats() {
        List<String> all = new ArrayList<String>(INT_STATS);
        all.add(MONEY);
        return all;
    }

    /** Stops the refresh task and clears the cache (plugin shutdown). */
    public void shutdown() {
        if (task != null) {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {
                // Already cancelled.
            }
        }
        cache.clear();
    }
}
