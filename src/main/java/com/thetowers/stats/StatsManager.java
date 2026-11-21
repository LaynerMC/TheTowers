package com.thetowers.stats;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.thetowers.Main;
import com.thetowers.game.GameStats;

/**
 * Orchestration of the statistics: in-memory cache + asynchronous persistence.
 *
 * <p><b>Threading model</b>, the sensitive point:
 * <ul>
 *   <li>the counters are only modified on the <b>main thread</b> (the game
 *       events);</li>
 *   <li>every I/O (file or database) goes through a <b>single I/O thread</b>,
 *       which serializes the accesses and avoids any server freeze;</li>
 *   <li>what crosses over to that thread is always a {@link PlayerStats#copy()
 *       copy}, never the live object: no mutable data is shared between the
 *       two threads.</li>
 * </ul>
 *
 * <p>Stats load at join and save at disconnect, at the end of a game, and at
 * plugin shutdown. Game events (kill, death, win) happen well after the join,
 * so the asynchronous load has always had the time to complete.
 */
public class StatsManager {

    private final Main main;
    private final StatsStorage storage;
    private final ExecutorService io;
    private final ConcurrentHashMap<UUID, PlayerStats> cache =
            new ConcurrentHashMap<UUID, PlayerStats>();

    public StatsManager(Main main) {
        this.main = main;
        this.storage = createStorage();
        this.io = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "TheTowers-Stats");
                thread.setDaemon(true);
                return thread;
            }
        });
        io.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    storage.init();
                } catch (Exception e) {
                    main.getLogger().severe("Stats: storage initialization failed: "
                            + e.getMessage() + ". Les stats ne seront pas enregistrees.");
                }
            }
        });
    }

    private StatsStorage createStorage() {
        String type = main.getConfig().getString("storage.type", "local");
        // new MySqlStatsStorage is only reached here: a server in local mode
        // therefore never loads that class nor the MariaDB driver.
        if ("mysql".equalsIgnoreCase(type)) {
            return new MySqlStatsStorage(main);
        }
        return new YamlStatsStorage(main);
    }

    // ================= Load / unload =================

    /** Loads a player's stats at their join (asynchronous). */
    public void load(Player player) {
        final UUID uuid = player.getUniqueId();
        final String name = player.getName();
        io.submit(new Runnable() {
            @Override
            public void run() {
                cache.put(uuid, storage.load(uuid, name));
            }
        });
    }

    /** Saves then forgets a player at their disconnect. */
    public void unload(Player player) {
        save(player.getUniqueId());
        cache.remove(player.getUniqueId());
    }

    // ================= LuckPerms prefix (for leaderboards) =================

    /**
     * Fetches the player's LuckPerms prefix through PlaceholderAPI and saves
     * it (in the cache AND in the storage) so the top placeholders can show
     * it even when the player is offline. Called on join and periodically.
     * No-op without PlaceholderAPI.
     */
    public void refreshPrefix(final Player player) {
        if (!main.hasPlaceholderApi()) {
            return;
        }
        final String prefix = com.thetowers.api.PapiBridge.apply(player, "%luckperms_prefix%");
        if (prefix == null) {
            return;
        }
        PlayerStats stats = cache.get(player.getUniqueId());
        if (stats != null) {
            stats.setLuckpermsPrefix(prefix);
        }
        final UUID uuid = player.getUniqueId();
        final String name = player.getName();
        io.submit(new Runnable() {
            @Override
            public void run() {
                storage.updatePrefix(uuid, name, prefix);
            }
        });
    }

    // ================= Coins (stored with the stats) =================

    /** Max coin balance a player can hold (from config.yml, coins.max-balance). */
    public long maxCoins() {
        return main.getConfig().getLong("coins.max-balance", 1000000L);
    }

    /** Coin balance of an online player (cached), or 0 if not loaded. */
    public long getCoins(UUID uuid) {
        PlayerStats stats = cache.get(uuid);
        return stats == null ? 0L : stats.getCoins();
    }

    /**
     * Adds coins to the player (clamped to the max balance). The caller is
     * responsible for the player being online (cached); offline writes go
     * through {@link #setCoinsOffline}.
     */
    public long addCoins(Player player, long amount) {
        PlayerStats stats = live(player);
        stats.addCoins(amount, maxCoins());
        save(player.getUniqueId());
        return stats.getCoins();
    }

    /** Removes coins (clamped at 0). Returns the new balance. */
    public long removeCoins(Player player, long amount) {
        PlayerStats stats = live(player);
        stats.removeCoins(amount);
        save(player.getUniqueId());
        return stats.getCoins();
    }

    /** Sets the balance directly (clamped 0..max). Returns the new balance. */
    public long setCoins(Player player, long amount) {
        PlayerStats stats = live(player);
        stats.setCoins(Math.min(maxCoins(), amount));
        save(player.getUniqueId());
        return stats.getCoins();
    }

    /**
     * Sets the balance of an OFFLINE player (admin command): loads their stats
     * synchronously, modifies and saves. May briefly block on MySQL.
     */
    public long setCoinsOffline(UUID uuid, String name, long amount) {
        PlayerStats stats = cache.get(uuid);
        if (stats == null) {
            stats = storage.load(uuid, name);
        }
        stats.setCoins(Math.min(maxCoins(), amount));
        final PlayerStats copy = stats.copy();
        io.submit(new Runnable() {
            @Override
            public void run() {
                storage.save(copy);
            }
        });
        return stats.getCoins();
    }

    /**
     * Applies an arbitrary modification to a player's stats (online or
     * offline), then saves. Used by the leveling system's admin commands,
     * which target a player who may not be connected: a cached entry is
     * modified in place and saved the normal way; an offline player is loaded
     * synchronously (may briefly block on MySQL), modified, and a copy is
     * handed to the I/O thread for saving. Returns the modified stats.
     */
    public PlayerStats modifyOffline(UUID uuid, String name, Consumer<PlayerStats> modifier) {
        PlayerStats stats = cache.get(uuid);
        if (stats != null) {
            modifier.accept(stats);
            save(uuid);
            return stats;
        }
        stats = storage.load(uuid, name);
        modifier.accept(stats);
        final PlayerStats copy = stats.copy();
        io.submit(new Runnable() {
            @Override
            public void run() {
                storage.save(copy);
            }
        });
        return stats;
    }

    // ================= Kit purchases (stored with the stats) =================

    /** True if the player already bought the kit with this numeric id. */
    public boolean hasPurchasedKit(Player player, int kitId) {
        PlayerStats stats = cache.get(player.getUniqueId());
        return stats != null && stats.hasPurchasedKit(kitId);
    }

    /** Records a kit purchase and saves. */
    public void addPurchasedKit(Player player, int kitId) {
        PlayerStats stats = live(player);
        stats.addPurchasedKit(kitId);
        save(player.getUniqueId());
    }

    /** Removes a kit purchase (admin revoke) and saves. */
    public void removePurchasedKit(Player player, int kitId) {
        PlayerStats stats = live(player);
        stats.removePurchasedKit(kitId);
        save(player.getUniqueId());
    }

    /** Coin balance / kit IDs of an offline player, loaded synchronously. */
    public PlayerStats loadOffline(UUID uuid, String name) {
        PlayerStats cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }
        return storage.load(uuid, name);
    }

    // ================= Counters (main thread) =================

    private PlayerStats live(Player player) {
        final UUID uuid = player.getUniqueId();
        final String name = player.getName();
        PlayerStats stats = cache.get(uuid);
        if (stats == null) {
            stats = new PlayerStats(uuid, name);
            cache.put(uuid, stats);
        }
        return stats;
    }

    public void addGame(Player player) {
        live(player).addGame();
        periodic(player.getUniqueId(), player.getName(), "games", 1);
    }

    public void addWin(Player player) {
        live(player).addWin();
        periodic(player.getUniqueId(), player.getName(), "wins", 1);
    }

    public void addLoss(Player player) {
        live(player).addLoss();
        periodic(player.getUniqueId(), player.getName(), "losses", 1);
    }

    public void addKill(Player player) {
        live(player).addKill();
        periodic(player.getUniqueId(), player.getName(), "kills", 1);
    }

    public void addDeath(Player player) {
        live(player).addDeath();
        periodic(player.getUniqueId(), player.getName(), "deaths", 1);
    }

    /** A point scored in an enemy pool. */
    public void addPoint(Player player) {
        live(player).addPoint();
        periodic(player.getUniqueId(), player.getName(), "points", 1);
    }

    // ================= New in-game stats (career only) =================

    /** Blocks placed during a game (career only, no per-period tracking). */
    public void addBlocksPlaced(Player player, int count) {
        live(player).addBlocksPlaced(count);
    }

    /** Blocks mined/broken during a game. */
    public void addBlocksBroken(Player player, int count) {
        live(player).addBlocksBroken(count);
    }

    /** Records the current kill streak length, keeping the all-time best. */
    public void recordKillStreak(Player player, int streak) {
        live(player).recordKillStreak(streak);
    }

    /** Damage dealt to an enemy (career only). */
    public void addDamageDealt(Player player, double amount) {
        live(player).addDamageDealt(amount);
    }

    /** Damage taken from an enemy (career only). */
    public void addDamageTaken(Player player, double amount) {
        live(player).addDamageTaken(amount);
    }

    /** Cached stats of an online player (live values), or {@code null}. */
    public PlayerStats getCached(UUID uuid) {
        return cache.get(uuid);
    }

    // ================= Per-period counters =================

    /**
     * Each career counter is doubled by a per-period counter (ISO day and
     * week), identically: this feeds {@code /tt top <stat> day|week}. The
     * period key is computed at the moment of the event: a game crossing
     * midnight splits its points between the two days, which is the expected
     * behavior.
     */
    private void periodic(UUID uuid, String name, String column, int delta) {
        PlayerStats stats = cache.get(uuid);
        final String prefix = stats == null ? "" : stats.getLuckpermsPrefix();
        final Map<String, Integer> deltas = Collections.singletonMap(column, Integer.valueOf(delta));
        io.submit(new Runnable() {
            @Override
            public void run() {
                storage.addPeriodic("week", weekKey(), uuid, name, prefix, deltas);
                storage.addPeriodic("month", monthKey(), uuid, name, prefix, deltas);
            }
        });
    }

    /** Key of the current week, ISO: "2026-W46". */
    public static String weekKey() {
        java.time.LocalDate now = java.time.LocalDate.now();
        java.time.temporal.WeekFields iso = java.time.temporal.WeekFields.ISO;
        return String.format(Locale.US, "%04d-W%02d",
                now.get(iso.weekBasedYear()), now.get(iso.weekOfWeekBasedYear()));
    }

    /** Key of the current month: "2026-11". */
    public static String monthKey() {
        return new SimpleDateFormat("yyyy-MM").format(new Date());
    }

    /**
     * Leaderboard of a period ("week" or "month"), rendered on the main thread.
     * The period key is the current one.
     */
    public void topPeriodic(final String periodType, final String column, final int limit,
            final Consumer<List<PlayerStats>> callback) {
        final String key = "month".equals(periodType) ? monthKey() : weekKey();
        io.submit(new Runnable() {
            @Override
            public void run() {
                final List<PlayerStats> list = storage.topPeriodic(periodType, key, column, limit);
                Bukkit.getScheduler().runTask(main, new Runnable() {
                    @Override
                    public void run() {
                        callback.accept(list);
                    }
                });
            }
        });
    }

    /** Current period key, for display (the /tt top header). */
    public static String periodKeyOf(String periodType) {
        return "month".equals(periodType) ? monthKey() : weekKey();
    }

    // ================= Rollback of an aborted game =================

    /**
     * Cancels the counters recorded by a game that will never finish
     * properly: server stopped or {@code /tt reload} mid-game. The career AND
     * the periods go back to their pre-game state, "as if it had never
     * happened". Wins/losses are not touched: they are only counted at the
     * legitimate end of a game.
     *
     * @param game the current game's counters for this player
     */
    public void rollbackGame(UUID uuid, String name, GameStats game) {
        PlayerStats stats = cache.get(uuid);
        if (stats != null) {
            stats.subtractGame();
            stats.subtractKills(game.getKills());
            stats.subtractDeaths(game.getDeaths());
            stats.subtractPoints(game.getPoints());
            stats.subtractBlocksPlaced(game.getBlocksPlaced());
            stats.subtractBlocksBroken(game.getBlocksBroken());
            stats.subtractDamageDealt(game.getDamage());
            stats.subtractDamageTaken(game.getDamageTaken());
            save(uuid);
        }
        // Per-period compensation: negative deltas of what the game recorded.
        final String prefix = stats == null ? "" : stats.getLuckpermsPrefix();
        final Map<String, Integer> deltas = new HashMap<String, Integer>();
        deltas.put("games", Integer.valueOf(-1));
        if (game.getKills() > 0) {
            deltas.put("kills", Integer.valueOf(-game.getKills()));
        }
        if (game.getDeaths() > 0) {
            deltas.put("deaths", Integer.valueOf(-game.getDeaths()));
        }
        if (game.getPoints() > 0) {
            deltas.put("points", Integer.valueOf(-game.getPoints()));
        }
        final String playerName = name;
        io.submit(new Runnable() {
            @Override
            public void run() {
                storage.addPeriodic("week", weekKey(), uuid, playerName, prefix, deltas);
                storage.addPeriodic("month", monthKey(), uuid, playerName, prefix, deltas);
            }
        });
    }

    // ================= Persistence =================

    /** Schedules a player's save (a copy handed to the I/O thread). */
    public void save(UUID uuid) {
        PlayerStats stats = cache.get(uuid);
        if (stats == null) {
            return;
        }
        final PlayerStats copy = stats.copy();
        io.submit(new Runnable() {
            @Override
            public void run() {
                storage.save(copy);
            }
        });
    }

    // ================= Asynchronous reads (commands) =================

    /** Finds stats by name and hands the result back on the main thread. */
    public void lookupByName(final String name, final Consumer<PlayerStats> callback) {
        io.submit(new Runnable() {
            @Override
            public void run() {
                final PlayerStats stats = storage.loadByName(name);
                Bukkit.getScheduler().runTask(main, new Runnable() {
                    @Override
                    public void run() {
                        callback.accept(stats);
                    }
                });
            }
        });
    }

    /** Leaderboard for a stat, rendered on the main thread. */
    public void top(final String column, final int limit, final Consumer<List<PlayerStats>> callback) {
        io.submit(new Runnable() {
            @Override
            public void run() {
                final List<PlayerStats> list = storage.top(column, limit);
                Bukkit.getScheduler().runTask(main, new Runnable() {
                    @Override
                    public void run() {
                        callback.accept(list);
                    }
                });
            }
        });
    }

    /** Coin-balance leaderboard (money top), rendered on the main thread. */
    public void topCoins(final int limit, final Consumer<List<PlayerStats>> callback) {
        io.submit(new Runnable() {
            @Override
            public void run() {
                final List<PlayerStats> list = storage.topCoins(limit);
                Bukkit.getScheduler().runTask(main, new Runnable() {
                    @Override
                    public void run() {
                        callback.accept(list);
                    }
                });
            }
        });
    }

    // ================= Shutdown =================

    /**
     * Saves the whole cache then closes the storage. Called at plugin
     * disable: we wait for the writes to finish before handing back control.
     */
    public void shutdown() {
        for (PlayerStats stats : cache.values()) {
            final PlayerStats copy = stats.copy();
            io.submit(new Runnable() {
                @Override
                public void run() {
                    storage.save(copy);
                }
            });
        }
        io.submit(new Runnable() {
            @Override
            public void run() {
                storage.close();
            }
        });
        io.shutdown();
        try {
            io.awaitTermination(10L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
