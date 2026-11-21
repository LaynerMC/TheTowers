package com.thetowers.arena;

import com.thetowers.Main;
import com.thetowers.compat.Compat;
import com.thetowers.compat.Sounds;
import com.thetowers.compat.TTMaterial;
import com.thetowers.game.GameStats;
import com.thetowers.game.Generator;
import com.thetowers.game.GeneratorSpot;
import com.thetowers.game.GeneratorType;
import com.thetowers.game.KitDefinition;
import com.thetowers.lang.Messages;
import com.thetowers.lobby.LobbyItems;
import com.thetowers.lobby.SpectatorItems;
import com.thetowers.map.MapTracker;
import com.thetowers.region.Cuboid;
import com.thetowers.task.Countdown;
import com.thetowers.task.GameCycle;
import com.thetowers.util.ChatCenterUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * A TheTowers arena: its configuration, its state and how its game unfolds.
 *
 * <p>Game rule: each team has a base with a <b>pool</b>. Entering an
 * opposing team's pool marks a point for your team and sends you back to
 * your base. The first team to reach {@link #getPointsToWin()} points wins.
 * Nobody is ever eliminated: each death respawns you at your base, with the
 * starting kit.
 *
 * <p>A game cannot last forever: it has a maximum duration. When time is
 * up, the leading team wins; on a tie, <b>sudden death</b> - the next point
 * takes it.
 *
 * <p>Players are referenced by their {@link UUID} and not by {@link Player}
 * objects: holding a reference to a disconnected Player prevents the
 * garbage collector from releasing its entity, which leaks every game.
 */
public class Arena {

    /** The four possible teams, in assignment order. */
    public static final String[] TEAM_NAMES = { "Red", "Blue", "Yellow", "Green" };

    /**
     * Language keys of the teams, in the same order as {@link #TEAM_NAMES}.
     * {@code TEAM_NAMES} stays the internal identifier (the one used in
     * arenas.yml); the name shown to players comes from
     * {@code team.name.<key>}.
     */
    private static final String[] TEAM_KEYS = { "red", "blue", "yellow", "green" };

    /** Time window (ms) within which successive kills form a streak. */
    private static final long STREAK_WINDOW_MS = 8000L;

    /**
     * A player hit by an opponent less than this delay ago (ms) is "in
     * combat": if they disconnect, it counts as a death.
     */
    private static final long COMBAT_TAG_MS = 10000L;

    /**
     * Margin around the configured elements to bound the "play area" (see
     * {@link #getArea()}): enough to cover the bridges and the center of
     * the map between the towers.
     */
    private static final int AREA_MARGIN = 48;

    private final Main main;

    // ----- Persistent configuration -----

    private final String name;
    private int minPlayers = 2;
    private int maxPlayers = 8;
    private int teamCount = 2;
    private int playersPerTeam = 4;
    private int pointsToWin;
    /** Maximum duration of the game, in seconds. */
    private int duration;
    private boolean allowBows = true;
    /** Allows spectators (/tt spectate) during the game. Disabled by default. */
    private boolean allowSpectators;
    private Location lobby;
    private final Map<String, Location> spawns = new LinkedHashMap<String, Location>();
    private final Map<String, Cuboid> pools = new LinkedHashMap<String, Cuboid>();
    // Zone where nobody can place or break blocks (the team's spawn). Optional.
    private final Map<String, Cuboid> spawnZones = new LinkedHashMap<String, Cuboid>();
    // Zone where only the team's members open the chests. Optional.
    private final Map<String, Cuboid> chestZones = new LinkedHashMap<String, Cuboid>();
    private final List<GeneratorSpot> generators = new ArrayList<GeneratorSpot>();

    // ----- State of the running game -----

    private State state = State.WAITING;
    private final List<UUID> players = new ArrayList<UUID>();
    /** Spectators: unlike players, they count neither in headcounts nor in the game. */
    private final Set<UUID> spectators = new LinkedHashSet<UUID>();
    private final Map<UUID, String> teamOf = new HashMap<UUID, String>();
    private final Map<String, Integer> scores = new LinkedHashMap<String, Integer>();
    private final Map<UUID, GameStats> gameStats = new LinkedHashMap<UUID, GameStats>();
    private final Map<UUID, Long> nextMark = new HashMap<UUID, Long>();
    // End (ms) of the protection of a player who just spawned.
    private final Map<UUID, Long> protectedUntil = new HashMap<UUID, Long>();
    // Players disconnected mid-game who can still come back: their team
    // (teamOf) is kept, along with the deadline of their return (ms).
    private final Map<UUID, Long> reconnectDeadlines = new LinkedHashMap<UUID, Long>();
    private final MapTracker mapTracker = new MapTracker();
    private final List<Generator> runningGenerators = new ArrayList<Generator>();
    private BukkitTask countdownTask;
    private BukkitTask cycleTask;
    private int timeLeft;
    private boolean overtime;
    /** Start instant (ms): used for the duration shown at the end of the game. */
    private long startedAt;
    /** Play area frozen at game start (see {@link #getArea()}). */
    private Cuboid area;

    public Arena(Main main, String name) {
        this.main = main;
        this.name = name;
        this.pointsToWin = main.getDefaultPointsToWin();
        this.duration = main.getGameDuration();
    }

    // ================= Configuration =================

    public String getName() {
        return name;
    }

    public int getMinPlayers() {
        return minPlayers;
    }

    public void setMinPlayers(int minPlayers) {
        this.minPlayers = minPlayers;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public int getTeamCount() {
        return teamCount;
    }

    public void setTeamCount(int teamCount) {
        this.teamCount = teamCount;
    }

    public int getPlayersPerTeam() {
        return playersPerTeam;
    }

    public void setPlayersPerTeam(int playersPerTeam) {
        this.playersPerTeam = playersPerTeam;
    }

    /** Points needed to win (10 by default). */
    public int getPointsToWin() {
        return pointsToWin;
    }

    public void setPointsToWin(int pointsToWin) {
        this.pointsToWin = Math.max(1, pointsToWin);
    }

    /** Maximum duration of a game, in seconds. */
    public int getDuration() {
        return duration;
    }

    public void setDuration(int seconds) {
        this.duration = Math.max(60, seconds);
    }

    /** False when the arena bans bows and arrows (no kit, no crafting). */
    public boolean isAllowBows() {
        return allowBows;
    }

    public void setAllowBows(boolean allowBows) {
        this.allowBows = allowBows;
    }

    public boolean isAllowSpectators() {
        return allowSpectators;
    }

    public void setAllowSpectators(boolean allowSpectators) {
        this.allowSpectators = allowSpectators;
    }

    public Location getLobby() {
        return lobby;
    }

    public void setLobby(Location lobby) {
        this.lobby = lobby;
    }

    public Map<String, Location> getSpawns() {
        return Collections.unmodifiableMap(spawns);
    }

    public void setSpawn(String team, Location location) {
        spawns.put(team, location);
    }

    public Map<String, Cuboid> getPools() {
        return Collections.unmodifiableMap(pools);
    }

    public void setPool(String team, Cuboid pool) {
        putOrRemove(pools, team, pool);
    }

    public Map<String, Cuboid> getSpawnZones() {
        return Collections.unmodifiableMap(spawnZones);
    }

    public void setSpawnZone(String team, Cuboid zone) {
        putOrRemove(spawnZones, team, zone);
    }

    public Map<String, Cuboid> getChestZones() {
        return Collections.unmodifiableMap(chestZones);
    }

    public void setChestZone(String team, Cuboid zone) {
        putOrRemove(chestZones, team, zone);
    }

    public List<GeneratorSpot> getGenerators() {
        return Collections.unmodifiableList(generators);
    }

    public void addGenerator(String type, Location location) {
        generators.add(new GeneratorSpot(type, location));
    }

    /**
     * Removes the generators of one type, or all of them if {@code type} is
     * {@code null}.
     *
     * @return the number of removed generators
     */
    public int clearGenerators(String type) {
        int removed = 0;
        Iterator<GeneratorSpot> it = generators.iterator();
        while (it.hasNext()) {
            GeneratorSpot spot = it.next();
            if (type == null || spot.getType().equalsIgnoreCase(type)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Number of generators of a given type. */
    public int countGenerators(String type) {
        int count = 0;
        for (GeneratorSpot spot : generators) {
            if (spot.getType().equalsIgnoreCase(type)) {
                count++;
            }
        }
        return count;
    }

    /** True if this team is among the first {@code teamCount} ones. */
    public boolean isTeamActive(String teamName) {
        return activeTeams().contains(teamName);
    }

    /** The teams used by the current format, in order. */
    public List<String> activeTeams() {
        int count = Math.max(0, Math.min(teamCount, TEAM_NAMES.length));
        return new ArrayList<String>(Arrays.asList(TEAM_NAMES).subList(0, count));
    }

    /**
     * Checks that the arena is playable.
     *
     * @return the reason for the refusal, or {@code null} if the arena is valid
     */
    public String validate() {
        if (lobby == null) {
            return Messages.tr("arena.validate.no_lobby", "arena", name);
        }
        if (teamCount < 2 || teamCount > 4) {
            return Messages.tr("arena.validate.teams_range");
        }
        if (playersPerTeam <= 0) {
            return Messages.tr("arena.validate.per_team_positive");
        }
        for (String team : activeTeams()) {
            if (!spawns.containsKey(team)) {
                return Messages.tr("arena.validate.missing_spawn", "team", displayName(team), "arena", name);
            }
            if (!pools.containsKey(team)) {
                return Messages.tr("arena.validate.missing_pool", "team", displayName(team), "arena", name);
            }
        }
        if (minPlayers < 2) {
            return Messages.tr("arena.validate.min_players");
        }
        if (maxPlayers < minPlayers) {
            return Messages.tr("arena.validate.max_below_min");
        }
        if (maxPlayers > teamCount * playersPerTeam) {
            return Messages.tr("arena.validate.max_exceeds",
                    "max", maxPlayers, "teams", teamCount, "per", playersPerTeam,
                    "capacity", (teamCount * playersPerTeam));
        }
        return null;
    }

    public boolean isConfigured() {
        return validate() == null;
    }

    // ================= Zones =================

    /** True if nothing can be placed or broken here: spawn zone or pool. */
    public boolean isProtected(Block block) {
        for (String team : activeTeams()) {
            Cuboid zone = spawnZones.get(team);
            if (zone != null && zone.contains(block)) {
                return true;
            }
            Cuboid pool = pools.get(team);
            if (pool != null && pool.contains(block)) {
                return true;
            }
        }
        return false;
    }

    /** Team owning the chest zone containing this block, or {@code null}. */
    public String chestZoneOwner(Block block) {
        for (String team : activeTeams()) {
            Cuboid zone = chestZones.get(team);
            if (zone != null && zone.contains(block)) {
                return team;
            }
        }
        return null;
    }

    /**
     * Play area: the smallest box containing the spawns, pools, zones and
     * generators, enlarged by a margin. Used to attach to this arena
     * whatever arrives with no identifiable player (explosions, fire,
     * falling sand) and to clean up ground items between two games.
     *
     * <p>Frozen at game start; outside a game, recomputed on demand.
     *
     * @return the area, or {@code null} if nothing is configured yet
     */
    public Cuboid getArea() {
        return area != null ? area : computeArea();
    }

    private Cuboid computeArea() {
        Cuboid box = null;
        for (Location spawn : spawns.values()) {
            box = extend(box, Cuboid.of(spawn, spawn));
        }
        for (Cuboid pool : pools.values()) {
            box = extend(box, pool);
        }
        for (Cuboid zone : spawnZones.values()) {
            box = extend(box, zone);
        }
        for (Cuboid zone : chestZones.values()) {
            box = extend(box, zone);
        }
        for (GeneratorSpot spot : generators) {
            Location loc = spot.getLocation();
            box = extend(box, Cuboid.of(loc, loc));
        }
        return box == null ? null : box.expand(AREA_MARGIN, AREA_MARGIN);
    }

    private static Cuboid extend(Cuboid box, Cuboid other) {
        if (other == null) {
            return box;
        }
        return box == null ? other : box.union(other);
    }

    // ================= State and players =================

    public State getState() {
        return state;
    }

    public boolean isState(State other) {
        return this.state == other;
    }

    /** True during the game proper. */
    public boolean isRunning() {
        return state == State.PLAYING;
    }

    /** True if a player can still join: waiting, configured, not full. */
    public boolean isJoinable() {
        return (state == State.WAITING || state == State.STARTING)
                && isConfigured() && !isFull();
    }

    public List<UUID> getPlayerIds() {
        return Collections.unmodifiableList(players);
    }

    /** True if the player is in this arena, as a player or as a spectator. */
    public boolean contains(Player player) {
        return players.contains(player.getUniqueId()) || spectators.contains(player.getUniqueId());
    }

    public boolean isSpectator(Player player) {
        return spectators.contains(player.getUniqueId());
    }

    public int getSpectatorCount() {
        return spectators.size();
    }

    /** Spectators currently online. */
    public List<Player> onlineSpectators() {
        List<Player> online = new ArrayList<Player>();
        for (UUID id : spectators) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                online.add(player);
            }
        }
        return online;
    }

    /** Online players and spectators. */
    public List<Player> audience() {
        List<Player> online = onlinePlayers();
        online.addAll(onlineSpectators());
        return online;
    }

    /** True if the player is playing in this running game (they have a team). */
    public boolean isParticipant(Player player) {
        return isRunning() && teamOf.containsKey(player.getUniqueId());
    }

    /**
     * True while a player has just spawned and cannot be hurt
     * ({@code respawn-protection} from config.yml). It expires when the
     * delay runs out, or as soon as they hit someone.
     */
    public boolean hasSpawnProtection(Player player) {
        Long until = protectedUntil.get(player.getUniqueId());
        return until != null && System.currentTimeMillis() < until.longValue();
    }

    public void clearSpawnProtection(Player player) {
        protectedUntil.remove(player.getUniqueId());
    }

    private void protect(Player player) {
        int seconds = main.getRespawnProtection();
        if (seconds > 0) {
            protectedUntil.put(player.getUniqueId(), Long.valueOf(System.currentTimeMillis() + seconds * 1000L));
        }
    }

    public int getPlayerCount() {
        return players.size();
    }

    public boolean isFull() {
        return players.size() >= maxPlayers;
    }

    public String getTeam(Player player) {
        return teamOf.get(player.getUniqueId());
    }

    /** Current score of a team (0 outside a game). */
    public int getScore(String team) {
        Integer score = scores.get(team);
        return score == null ? 0 : score.intValue();
    }

    /** Counters of the running game for this player, or {@code null}. */
    public GameStats getGameStats(Player player) {
        return gameStats.get(player.getUniqueId());
    }

    /** Tracking of modified blocks, for the map regeneration. */
    public MapTracker getMapTracker() {
        return mapTracker;
    }

    /** Seconds left before the end of regulation time. */
    public int getTimeLeft() {
        return timeLeft;
    }

    /** Seconds left before the game starts (STARTING countdown); 0 if not starting. */
    public int getCountdownLeft() {
        if (countdownTask instanceof Countdown) {
            return ((Countdown) countdownTask).getTimer();
        }
        return 0;
    }

    /** True in sudden death: time up on a tie, the next point wins. */
    public boolean isOvertime() {
        return overtime;
    }

    /** Players of the arena currently online. */
    public List<Player> onlinePlayers() {
        List<Player> online = new ArrayList<Player>();
        for (UUID id : players) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                online.add(player);
            }
        }
        return online;
    }

    /** Sends a message to the players of this arena only. */
    public void broadcast(String message) {
        String prefixed = Main.PREFIX + message;
        for (Player player : audience()) {
            player.sendMessage(prefixed);
        }
    }

    /** Sends a self-centered chat message to the arena's players and spectators. */
    public void broadcastCentered(String message) {
        String centered = ChatCenterUtil.center(message);
        for (Player player : audience()) {
            player.sendMessage(centered);
        }
    }

    // ================= Entry and exit =================

    /**
     * Brings a player into the arena.
     *
     * @return the reason for the refusal, or {@code null} on success
     */
    public String join(Player player) {
        // Party gate: a party member who is NOT the leader cannot join a game
        // on their own, by any path (/tt join, /tt randomjoin, a join sign, the
        // lobby pressure plate) - they get the "Only the leader can join a new
        // game!" message. The leader joining is handled by the entry points
        // (CommandTower / JoinSignManager): they pick an arena whose team can
        // fit the WHOLE party (moving to another arena if needed) and then call
        // PartyManager.onLeaderJoinedArena to move the members in and put them
        // on the SAME team as the leader. This method only does the gate and
        // the actual join; member-moving uses joinInternal (no gate) so the
        // members are not re-blocked here.
        com.thetowers.party.PartyManager pm = main.getPartyManager();
        if (pm != null) {
            String blocked = pm.joinGateMessage(player);
            if (blocked != null) {
                return blocked;
            }
        }
        return joinInternal(player);
    }

    /**
     * The actual join logic, with no party gate. Used by {@link #join} and by
     * {@link com.thetowers.party.PartyManager#onLeaderJoinedArena} (to move the
     * leader's party members in, bypassing the gate).
     *
     * @return the reason for the refusal, or {@code null} on success
     */
    public String joinInternal(Player player) {
        if (players.contains(player.getUniqueId())) {
            return Messages.tr("arena.join.already_in");
        }
        if (state != State.WAITING && state != State.STARTING) {
            if (state == State.PLAYING && allowSpectators && main.isSpectatorEnabled()) {
                return Messages.tr("arena.join.in_progress_spectate", "arena", name);
            }
            return Messages.tr("arena.join.in_progress");
        }
        if (isFull()) {
            return Messages.tr("arena.join.full", "current", players.size(), "max", maxPlayers);
        }
        String problem = validate();
        if (problem != null) {
            return Messages.tr("arena.join.misconfigured", "problem", problem);
        }

        players.add(player.getUniqueId());
        Compat.resetPlayer(player, GameMode.ADVENTURE);
        player.teleport(lobby);
        LobbyItems.give(main, this, player);

        broadcast(Messages.tr("arena.broadcast.joined", "player", player.getName(),
                "current", players.size(), "max", maxPlayers));

        if (state == State.WAITING && players.size() >= minPlayers) {
            startCountdown(main.getCountdown());
        }
        return null;
    }

    /**
     * Makes a player watch the game. They are not a player: no counters,
     * no team, no stats.
     *
     * @return the reason for the refusal, or {@code null} on success
     */
    public String spectate(Player player) {
        if (contains(player)) {
            return Messages.tr("arena.join.already_in");
        }
        if (!allowSpectators || !main.isSpectatorEnabled()) {
            return Messages.tr("spectator.disabled");
        }
        Location spot = spectatorSpot();
        if (state != State.PLAYING || spot == null) {
            return Messages.tr("spectator.not_running");
        }
        if (spectators.size() >= main.getSpectatorMax()) {
            return Messages.tr("spectator.full", "max", main.getSpectatorMax());
        }

        // Teleport first, THEN apply the spectator state (flight + invisibility),
        // so the teleport does not reset the flight flag the player just got.
        player.teleport(spot);
        Compat.makeSpectator(player);
        SpectatorItems.give(main, player);

        // Invisible to the others, but they see the other spectators.
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player)) {
                continue;
            }
            if (spectators.contains(other.getUniqueId())) {
                Compat.setHidden(player, other, false);
            } else {
                Compat.setHidden(other, player, true);
            }
        }
        spectators.add(player.getUniqueId());
        return null;
    }

    /** Landing spot of a spectator: a team's base, or the lobby if none. */
    public Location spectatorSpot() {
        for (String team : activeTeams()) {
            Location spawn = spawns.get(team);
            if (spawn != null && spawn.getWorld() != null) {
                return spawn;
            }
        }
        return lobby;
    }

    /** Removes a spectator: visible again, and back to the lobby if requested. */
    private void releaseSpectator(Player player, boolean teleportToLobby) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player)) {
                Compat.setHidden(other, player, false);
            }
        }
        if (teleportToLobby && player.isOnline()) {
            Compat.resetPlayer(player, GameMode.ADVENTURE);
            main.getScoreboardManager().clear(player);
            main.returnToLobby(player);
        }
    }

    /** Removes a player, voluntarily or by disconnection. */
    public void leave(Player player, boolean teleportToLobby) {
        UUID id = player.getUniqueId();
        if (spectators.remove(id)) {
            releaseSpectator(player, teleportToLobby);
            return;
        }
        if (!players.remove(id)) {
            return;
        }
        nextMark.remove(id);
        protectedUntil.remove(id);
        String team = teamOf.remove(id);
        if (teleportToLobby && player.isOnline()) {
            Compat.resetPlayer(player, GameMode.ADVENTURE);
            main.getScoreboardManager().clear(player);
            main.returnToLobby(player);
        }

        broadcast(Messages.tr("arena.broadcast.left", "player", player.getName(),
                "current", players.size(), "max", maxPlayers));

        if (state == State.STARTING && players.size() < minPlayers) {
            cancelCountdown();
            state = State.WAITING;
            broadcast(Messages.tr("arena.countdown_cancelled"));
        } else if (isRunning()) {
            // Last member of a team gone: the team forfeits.
            if (team != null && !teamsInPlay().contains(team)) {
                broadcast(Messages.tr("arena.team_forfeit", "color", colorOf(team), "team", displayName(team)));
            }
            checkWin();
        }
    }

    // ================= Reconnection mid-game =================

    /**
     * A participant disconnects (crash, outage) during the game: their slot
     * stays reserved in their team until the deadline
     * ({@code reconnection.timeout}).
     *
     * <p><b>Anti-logout</b>: ANY disconnect mid-game counts as a death,
     * whether it happens in combat or not. Items drop on the ground where
     * the player stood (the enemy can pick them up), the death is recorded
     * (counter + message to the arena), and the kill goes to the opponent
     * who hit them in the previous 10 seconds. On return, the player
     * respawns at their base with the starting kit, just like after a
     * normal death.
     */
    public void disconnect(Player player) {
        UUID id = player.getUniqueId();
        if (!isParticipant(player)) {
            leave(player, false);
            return;
        }
        long now = System.currentTimeMillis();
        GameStats stats = getGameStats(player);
        UUID attackerId = stats == null ? null : stats.recentAttacker(now, COMBAT_TAG_MS);
        if (attackerId != null) {
            broadcast(Messages.tr("arena.broadcast.combat_log", "player", player.getName()));
        } else {
            broadcast(Messages.tr("arena.broadcast.disconnect_death", "player", player.getName()));
        }
        dropInventory(player);
        handleDeath(player, Bukkit.getPlayer(attackerId));
        // Empties the real inventory: if the player never comes back, nothing
        // from the game must follow them elsewhere on the server.
        Compat.resetPlayer(player, GameMode.ADVENTURE);

        players.remove(id);
        nextMark.remove(id);
        long timeout = main.getReconnectTimeoutMillis();
        reconnectDeadlines.put(id, Long.valueOf(timeout <= 0 ? Long.MAX_VALUE : now + timeout));

        broadcast(timeout <= 0
                ? Messages.tr("arena.broadcast.disconnected_nolimit", "player", player.getName())
                : Messages.tr("arena.broadcast.disconnected", "player", player.getName(),
                        "time", GameCycle.clock((int) (timeout / 1000L))));
        // Nobody is connected anymore: no point keeping the game running empty.
        checkWin();
    }

    /** True if this disconnected player can still take their slot back in this game. */
    public boolean canRejoin(UUID id) {
        Long deadline = reconnectDeadlines.get(id);
        return isRunning() && deadline != null && deadline.longValue() > System.currentTimeMillis();
    }

    /**
     * The player comes back: they take their slot in their team again and
     * respawn at their base with the starting kit (the disconnect counted
     * as a death).
     */
    public void rejoin(Player player) {
        UUID id = player.getUniqueId();
        if (!canRejoin(id)) {
            return;
        }
        reconnectDeadlines.remove(id);
        players.add(id);
        // A spectator present during their absence must stay invisible to them.
        for (Player spectator : onlineSpectators()) {
            Compat.setHidden(player, spectator, true);
        }
        spawnPlayer(player);

        String team = getTeam(player);
        player.sendMessage(Main.PREFIX + Messages.tr("arena.rejoined_self",
                "color", colorOf(team), "team", displayName(team)));
        broadcast(Messages.tr("arena.broadcast.rejoined", "color", colorOf(team), "player", player.getName()));
    }

    /**
     * Called every second by {@link GameCycle}: disconnected players whose
     * delay has run out lose their slot for good; a team left with nobody
     * forfeits.
     */
    public void checkDisconnected() {
        if (reconnectDeadlines.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean expired = false;
        Iterator<Map.Entry<UUID, Long>> it = reconnectDeadlines.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            if (entry.getValue().longValue() > now) {
                continue;
            }
            UUID id = entry.getKey();
            it.remove();
            main.getArenaManager().markStranded(id);
            String team = teamOf.remove(id);
            GameStats stats = gameStats.get(id);
            broadcast(Messages.tr("arena.broadcast.reconnect_expired",
                    "player", stats == null ? "?" : stats.getName()));
            if (team != null && !teamsInPlay().contains(team)) {
                broadcast(Messages.tr("arena.team_forfeit", "color", colorOf(team), "team", displayName(team)));
            }
            expired = true;
        }
        if (expired) {
            checkWin();
        }
    }

    /** Drops a player's inventory like on a death (kit armor excluded). */
    private void dropInventory(Player player) {
        Location location = player.getLocation();
        if (location.getWorld() == null) {
            return;
        }
        List<ItemStack> items = new ArrayList<ItemStack>();
        ItemStack[] contents = player.getInventory().getContents();
        items.addAll(Arrays.asList(contents));
        // On 1.12.2, getContents() already includes armor and the off-hand
        // slot (41 entries): adding getArmorContents() on top would drop
        // the armor twice. The size check is kept as a defensive guard.
        if (contents.length <= 36) {
            items.addAll(Arrays.asList(player.getInventory().getArmorContents()));
        }
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR
                    || TTMaterial.isLeatherArmor(item.getType())) {
                continue;
            }
            location.getWorld().dropItemNaturally(location, item);
        }
    }

    // ================= Countdown =================

    private void startCountdown(int seconds) {
        state = State.STARTING;
        cancelCountdown();
        countdownTask = new Countdown(this, seconds).runTaskTimer(main, 0L, 20L);
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    private void cancelCycle() {
        if (cycleTask != null) {
            cycleTask.cancel();
            cycleTask = null;
        }
    }

    // ================= Game flow =================

    /**
     * Actually starts the game: team assignment, teleport to the bases,
     * kits, generators and game cycle.
     */
    public void start() {
        cancelCountdown();
        state = State.PLAYING;
        mapTracker.clear();
        scores.clear();
        gameStats.clear();
        nextMark.clear();
        reconnectDeadlines.clear();
        overtime = false;
        timeLeft = duration;
        startedAt = System.currentTimeMillis();
        area = computeArea();
        // Items left behind by a game interrupted abruptly (crash): we
        // start again from a clean map.
        clearLooseEntities();

        List<String> teams = activeTeams();
        for (String team : teams) {
            scores.put(team, Integer.valueOf(0));
        }

        // Teams chosen in the menu (waiting lobby) keep priority, within
        // the per-team capacity; players with no choice are spread one by
        // one over the teams that are not full.
        Map<UUID, String> choices = new HashMap<UUID, String>(teamOf);
        List<Player> participants = onlinePlayers();
        teamOf.clear();

        List<Player> unassigned = new ArrayList<Player>();
        for (Player player : participants) {
            String chosen = choices.get(player.getUniqueId());
            if (chosen != null && teams.contains(chosen)
                    && teamSize(chosen) < playersPerTeam) {
                assignTeam(player, chosen);
            } else {
                unassigned.add(player);
            }
        }
        // Spread the unassigned players EQUITABLY: each one goes to the
        // non-full active team that currently has the FEWEST members (ties
        // resolved in activeTeams() order: Red, Blue, ...). This keeps both
        // sides balanced instead of stacking everyone on the first team.
        for (Player player : unassigned) {
            String smallest = null;
            int smallestSize = Integer.MAX_VALUE;
            for (String team : teams) {
                if (teamSize(team) >= playersPerTeam) {
                    continue; // full: skip
                }
                int size = teamSize(team);
                if (size < smallestSize) {
                    smallestSize = size;
                    smallest = team;
                }
            }
            if (smallest == null) {
                // Every team is full (over-filled arena): fall back to the
                // first team so the player still gets one.
                smallest = teams.get(0);
            }
            assignTeam(player, smallest);
        }

        for (Player player : participants) {
            String team = getTeam(player);
            GameStats stats = new GameStats(player.getName());
            stats.setKitsAtStart(main.getKitManager().unlockedIds(player));
            gameStats.put(player.getUniqueId(), stats);
            main.getStatsManager().addGame(player);

            spawnPlayer(player);
            player.sendTitle(Messages.tr("title.game_start"),
                    Messages.tr("title.game_start_sub", "goal", pointsToWin), 10, 60, 10);
            Sounds.gameStart(player);
            player.sendMessage(Main.PREFIX + Messages.tr("arena.your_team",
                    "color", colorOf(team), "team", displayName(team)));
        }

        // Game start message: centered in the chat, multi-line, fully
        // editable in config.yml (game-start: section).
        sendGameStartMessage();

        startGenerators();
        cancelCycle();
        cycleTask = new GameCycle(this).runTaskTimer(main, 20L, 20L);
    }

    /**
     * Message shown when the game starts: several lines (config.yml,
     * {@code game-start.message}), centered in the chat like the end
     * screen, with the variables %arena%, %goal% and %minutes%. Disabled
     * or empty = nothing.
     */
    private void sendGameStartMessage() {
        if (!main.isGameStartMessageEnabled()) {
            return;
        }
        List<String> lines = main.getGameStartLines();
        if (lines.isEmpty()) {
            return;
        }
        for (String raw : lines) {
            String line = raw == null ? "" : raw
                    .replace("%arena%", name)
                    .replace("%goal%", String.valueOf(pointsToWin))
                    .replace("%minutes%", String.valueOf(duration / 60));
            if (line.trim().isEmpty()) {
                broadcast("");
                continue;
            }
            if (main.isGameStartMessageCentered()) {
                broadcastCentered(line);
            } else {
                broadcast(Compat.color(line));
            }
        }
    }

    /** Records a participant's teamOf (start-time assignment). */
    private void assignTeam(Player player, String team) {
        teamOf.put(player.getUniqueId(), team);
    }

    /** Number of (online) players currently in this team. */
    public int teamSize(String team) {
        int count = 0;
        for (UUID id : players) {
            if (team.equals(teamOf.get(id))) {
                count++;
            }
        }
        return count;
    }

    /** Names of this team's (online) players, in join order. */
    public List<String> teamMemberNames(String team) {
        List<String> names = new ArrayList<String>();
        for (UUID id : players) {
            if (!team.equals(teamOf.get(id))) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            names.add(player != null ? player.getName() : "?");
        }
        return names;
    }

    /**
     * Team choice from the waiting lobby (wool menu): records the preference,
     * honored at game start within the remaining slots.
     *
     * <p>Party rule: the whole party always shares ONE team. A party member who
     * is NOT the leader cannot pick a team (the leader chooses for everyone);
     * the leader CAN switch the party's team, but only to a team that still has
     * room for the whole party (the party is never split) - in that case every
     * party member currently in this arena is moved to the target team too.
     *
     * @return {@code null} if the choice is accepted, otherwise the reason
     *         for the refusal
     */
    public String chooseTeam(Player player, String team) {
        if (!players.contains(player.getUniqueId())) {
            return Messages.tr("team.menu_not_in_arena");
        }
        if (state != State.WAITING && state != State.STARTING) {
            return Messages.tr("team.menu_too_late");
        }
        if (!isTeamActive(team)) {
            return Messages.tr("team.menu_unknown");
        }
        if (team.equals(teamOf.get(player.getUniqueId()))) {
            return Messages.tr("team.menu_already", "color", colorOf(team), "team", displayName(team));
        }

        // Party team control: the whole party always shares one team.
        com.thetowers.party.PartyManager pm = main.getPartyManager();
        if (pm != null) {
            com.thetowers.party.Party party = pm.getParty(player.getUniqueId());
            if (party != null) {
                if (!party.isLeader(player.getUniqueId())) {
                    // A member cannot change team on their own.
                    return pm.getConfig().msg("team-change-blocked-member");
                }
                // Count the party members who are in THIS arena (only they
                // move with the leader): the leader plus every online member
                // currently sitting in this arena's lobby.
                int size = 1; // the leader
                for (Player member : party.onlineMembers()) {
                    if (member.getUniqueId().equals(player.getUniqueId())) {
                        continue;
                    }
                    if (players.contains(member.getUniqueId())) {
                        size++;
                    }
                }
                int free = playersPerTeam - teamSize(team);
                if (free < size) {
                    return pm.getConfig().msg("team-change-full",
                            "color", colorOf(team), "team", displayName(team),
                            "size", String.valueOf(size));
                }
                // Move the leader and every party member in this arena onto the
                // target team (driven by the leader: no per-member check).
                setTeam(player, team);
                for (Player member : party.onlineMembers()) {
                    if (member.getUniqueId().equals(player.getUniqueId())) {
                        continue;
                    }
                    if (players.contains(member.getUniqueId())) {
                        setTeam(member, team);
                    }
                }
                return null;
            }
        }

        // Solo player (no party): a single slot.
        if (teamSize(team) >= playersPerTeam) {
            return Messages.tr("team.menu_full", "color", colorOf(team), "team", displayName(team),
                    "max", playersPerTeam);
        }
        teamOf.put(player.getUniqueId(), team);
        return null;
    }

    /**
     * Sets a player's team preference with NO checks (state, capacity, party
     * rules). Used internally when the party LEADER drives the move - the
     * capacity was already verified by {@link com.thetowers.party.PartyManager#findPartyTarget}
     * when joining, or by {@link #chooseTeam}'s party branch when switching -
     * and the members are not asked (the leader chooses for them).
     */
    public void setTeam(Player player, String team) {
        teamOf.put(player.getUniqueId(), team);
    }

    /**
     * (Re)places a participant at their team's base with the starting kit:
     * at game start and at every respawn.
     */
    public void spawnPlayer(Player player) {
        String team = getTeam(player);
        Compat.resetPlayer(player, GameMode.SURVIVAL);
        Location spawn = team == null ? null : spawns.get(team);
        if (spawn != null && spawn.getWorld() != null) {
            player.teleport(spawn);
        }
        main.getKitManager().give(player, armorColorOf(team), allowBows);
        protect(player);
    }

    /** A player's respawn point: their base in game, the lobby otherwise. */
    public Location respawnLocation(Player player) {
        String team = getTeam(player);
        Location spawn = team == null ? null : spawns.get(team);
        if (isRunning() && spawn != null && spawn.getWorld() != null) {
            return spawn;
        }
        return lobby;
    }

    private void startGenerators() {
        stopGenerators();
        Map<String, GeneratorType> types = main.getGeneratorTypes();
        for (GeneratorSpot spot : generators) {
            GeneratorType type = types.get(spot.getType());
            if (type == null) {
                main.getLogger().warning("Arena " + name + ": unknown generator type '"
                        + spot.getType() + "' (missing from config.yml), ignored.");
                continue;
            }
            Generator generator = new Generator(type, spot.getLocation());
            generator.start(main);
            runningGenerators.add(generator);
        }
    }

    private void stopGenerators() {
        for (Generator generator : runningGenerators) {
            generator.stop();
        }
        runningGenerators.clear();
    }

    /**
     * Called on every move of a participant (block change): if they just
     * entered an opposing team's pool, their team scores.
     */
    public void tryMark(Player player, Location to) {
        if (!isRunning()) {
            return;
        }
        String team = getTeam(player);
        if (team == null) {
            return;
        }
        for (String other : activeTeams()) {
            if (other.equals(team)) {
                continue;
            }
            Cuboid pool = pools.get(other);
            if (pool != null && pool.contains(to)) {
                mark(player, team);
                return;
            }
        }
    }

    private void mark(Player player, String team) {
        // Anti double-fire: the player is teleported as soon as they score,
        // but a second move event can arrive before the teleport is
        // effective on the server side.
        long now = System.currentTimeMillis();
        Long next = nextMark.get(player.getUniqueId());
        if (next != null && next.longValue() > now) {
            return;
        }
        nextMark.put(player.getUniqueId(), Long.valueOf(now + main.getMarkCooldownMillis()));

        int score = getScore(team) + 1;
        scores.put(team, Integer.valueOf(score));

        GameStats stats = getGameStats(player);
        if (stats != null) {
            stats.addPoint();
        }
        main.getStatsManager().addPoint(player);

        // Back to the base, fully healed. Dismount first: a player in a
        // saddle cannot be teleported.
        player.leaveVehicle();
        Location spawn = spawns.get(team);
        if (spawn != null && spawn.getWorld() != null) {
            player.teleport(spawn);
        }
        Compat.healFully(player);
        player.setFireTicks(0);
        player.setFallDistance(0f);

        String title = Messages.tr("title.mark", "color", colorOf(team), "score", score, "goal", pointsToWin);
        String subtitle = Messages.tr("title.mark_sub", "color", colorOf(team), "player", player.getName());
        for (Player viewer : onlinePlayers()) {
            viewer.sendTitle(title, subtitle, 5, 40, 10);
            if (team.equals(getTeam(viewer))) {
                Sounds.markAlly(viewer);
            } else {
                Sounds.markEnemy(viewer);
            }
        }
        broadcast(Messages.tr("game.mark", "color", colorOf(team), "player", player.getName(),
                "team", displayName(team), "score", score, "goal", pointsToWin));

        // Per-point reward: runs the commands of the "point:" section of
        // rewards.yml for the player who just scored. Fired before a
        // possible end(team): the winning point counts too.
        if (main.getRewardManager() != null) {
            main.getRewardManager().executePoint(this, player, team, score, pointsToWin);
        }

        // Match point: a team is one point from victory. In sudden death,
        // every point is decisive: no redundant announcement.
        if (score == pointsToWin - 1 && !overtime) {
            broadcast(Messages.tr("game.match_point", "color", colorOf(team), "team", displayName(team)));
            for (Player viewer : onlinePlayers()) {
                viewer.sendTitle(Messages.tr("title.match_point"),
                        Messages.tr("title.match_point_sub", "color", colorOf(team), "team", displayName(team)), 10, 50, 10);
                Sounds.matchPoint(viewer);
            }
        }

        if (score >= pointsToWin) {
            end(team);
        } else if (overtime && team.equals(uniqueLeader())) {
            // Sudden death: this point just broke the tie.
            end(team);
        }
    }

    /**
     * Death of a participant: counters, message to the arena (not to the
     * whole server), kill streaks.
     *
     * @param killer the player who dealt the fatal blow, or {@code null}
     */
    public void handleDeath(Player victim, Player killer) {
        long now = System.currentTimeMillis();
        GameStats victimStats = getGameStats(victim);
        int victimStreak = victimStats == null ? 0 : victimStats.streak(now, STREAK_WINDOW_MS);
        if (victimStats != null) {
            victimStats.addDeath();
            victimStats.resetStreak();
            victimStats.clearAttacker();
        }
        main.getStatsManager().addDeath(victim);
        if (main.getRewardManager() != null) {
            main.getRewardManager().executeDeath(this, victim, killer);
        }

        String victimTeam = getTeam(victim);
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId()) || !isParticipant(killer)) {
            broadcast(Messages.tr("game.died", "color", colorOf(victimTeam), "player", victim.getName()));
            return;
        }

        String killerTeam = getTeam(killer);
        broadcast(Messages.tr("game.killed_by",
                "color", colorOf(victimTeam), "player", victim.getName(),
                "kcolor", colorOf(killerTeam), "killer", killer.getName()));
        main.getStatsManager().addKill(killer);
        if (main.getRewardManager() != null) {
            main.getRewardManager().executeKill(this, killer, victim);
        }

        if (victimStreak >= 3) {
            broadcast(Messages.tr("game.streak.stopped", "killer", killer.getName(),
                    "player", victim.getName(), "count", victimStreak));
        }

        GameStats killerStats = getGameStats(killer);
        if (killerStats == null) {
            return;
        }
        int streak = killerStats.registerKill(now, STREAK_WINDOW_MS);
        // Career: keep the all-time best kill streak.
        main.getStatsManager().recordKillStreak(killer, streak);
        if (streak >= 2) {
            String key = streak == 2 ? "game.streak.double"
                    : streak == 3 ? "game.streak.triple"
                    : streak == 4 ? "game.streak.quadruple"
                    : "game.streak.many";
            broadcast(Messages.tr(key, "player", killer.getName(), "count", streak));
            for (Player viewer : onlinePlayers()) {
                Sounds.killStreak(viewer, streak);
            }
        }
    }

    /**
     * A participant hits another: damage accumulated for the end-of-game
     * summary, the victim is tagged "in combat" (anti combat-logout), and
     * the damage is added to both players' career stats (dealt / taken).
     */
    public void recordHit(Player attacker, Player victim, double amount) {
        GameStats attackerStats = getGameStats(attacker);
        if (attackerStats != null) {
            attackerStats.addDamage(amount);
        }
        GameStats victimStats = getGameStats(victim);
        if (victimStats != null) {
            victimStats.addDamageTaken(amount);
            if (!attacker.getUniqueId().equals(victim.getUniqueId())) {
                victimStats.tagAttacker(attacker.getUniqueId(), System.currentTimeMillis());
            }
        }
        // Career stats: damage dealt by the attacker, taken by the victim.
        main.getStatsManager().addDamageDealt(attacker, amount);
        main.getStatsManager().addDamageTaken(victim, amount);
    }

    /** One second less; returns the time left. Called by {@link GameCycle}. */
    public int tickTimer() {
        if (timeLeft > 0) {
            timeLeft--;
        }
        return timeLeft;
    }

    /**
     * End of regulation time: the leading team wins, otherwise sudden death.
     */
    public void timeUp() {
        if (!isRunning() || overtime) {
            return;
        }
        String leader = uniqueLeader();
        if (leader != null) {
            broadcast(Messages.tr("game.time_up_winner", "color", colorOf(leader), "team", displayName(leader)));
            end(leader);
            return;
        }
        overtime = true;
        broadcast(Messages.tr("game.overtime"));
        for (Player player : onlinePlayers()) {
            player.sendTitle(Messages.tr("title.overtime"), Messages.tr("title.overtime_sub"), 10, 60, 10);
        }
    }

    /** Team alone on top among those still in play, or {@code null} (tie). */
    private String uniqueLeader() {
        String leader = null;
        int best = -1;
        boolean tie = false;
        for (String team : teamsInPlay()) {
            int score = getScore(team);
            if (score > best) {
                best = score;
                leader = team;
                tie = false;
            } else if (score == best) {
                tie = true;
            }
        }
        return tie ? null : leader;
    }

    /** Teams that still have at least one player in the game. */
    private Set<String> teamsInPlay() {
        return new LinkedHashSet<String>(teamOf.values());
    }

    /**
     * Ends the game as soon as only one team is left, or none. A team whose
     * players are disconnected but can still come back stays in play; on
     * the other hand, if NO player is connected anymore, the game stops
     * with no winner (it would run empty, or forever in sudden death).
     */
    public void checkWin() {
        if (!isRunning()) {
            return;
        }
        if (players.isEmpty()) {
            end(null);
            return;
        }
        Set<String> remaining = teamsInPlay();
        if (remaining.size() == 1) {
            end(remaining.iterator().next());
        } else if (remaining.isEmpty()) {
            end(null);
        }
    }

    /** Closes the game and schedules the arena's reset. */
    public void end(String winningTeam) {
        if (state == State.FINISH) {
            return;
        }
        state = State.FINISH;
        cancelCountdown();
        cancelCycle();
        stopGenerators();

        // End announcement, like SkyWars' "victory screen": a centered box
        // in the chat with the winner's name, the score, the duration and
        // the MVP. All the text is editable in messages.yml
        // (arena.end_winner / arena.end_no_winner, line lists).
        GameStats mvp = bestPlayer();
        String mvpName = mvp == null || mvp.getPoints() == 0
                ? Messages.tr("summary.nobody") : mvp.getName();
        if (winningTeam == null) {
            for (String line : Messages.trLines("arena.end_no_winner",
                    "arena", name,
                    "time", GameCycle.clock(elapsedSeconds()))) {
                broadcastCentered(line);
            }
        } else {
            for (String line : Messages.trLines("arena.end_winner",
                    "color", colorOf(winningTeam),
                    "team", displayName(winningTeam),
                    "arena", name,
                    "score", getScore(winningTeam),
                    "goal", pointsToWin,
                    "time", GameCycle.clock(elapsedSeconds()),
                    "mvp", mvpName,
                    "mvp_points", mvp == null ? 0 : mvp.getPoints(),
                    "mvp_kills", mvp == null ? 0 : mvp.getKills())) {
                broadcastCentered(line);
            }
            spawnVictoryFireworks(winningTeam);
        }

        List<Player> participants = onlinePlayers();
        for (Player player : participants) {
            String team = getTeam(player);
            String title;
            if (winningTeam == null) {
                title = Messages.tr("title.draw");
            } else if (winningTeam.equals(team)) {
                title = Messages.tr("title.victory");
            } else {
                title = Messages.tr("title.defeat");
            }
            String subtitle = winningTeam == null ? ""
                    : Messages.tr("title.winner_sub", "color", colorOf(winningTeam), "team", displayName(winningTeam));
            player.sendTitle(title, subtitle, 10, 100, 20);
            Sounds.gameEnd(player);
        }

        // Custom reward commands, run by the console.
        for (Player player : participants) {
            main.getRewardManager().execute(this, player, winningTeam);
        }

        for (Player player : participants) {
            String team = getTeam(player);
            if (winningTeam != null && team != null) {
                if (winningTeam.equals(team)) {
                    main.getStatsManager().addWin(player);
                } else {
                    main.getStatsManager().addLoss(player);
                }
            }
            main.getStatsManager().save(player.getUniqueId());
            announceUnlockedKits(player);
        }

        sendSummary();

        new BukkitRunnable() {
            @Override
            public void run() {
                reset();
            }
        }.runTaskLater(main, 20L * main.getEndDelay());
    }

    /** Time elapsed since the game started, in seconds. */
    private int elapsedSeconds() {
        return startedAt <= 0L ? 0 : (int) Math.min(Integer.MAX_VALUE / 2L,
                (System.currentTimeMillis() - startedAt) / 1000L);
    }

    /**
     * The best player of the game: more points, on a tie more kills, on a
     * tie more damage. {@code null} if no counter exists.
     */
    private GameStats bestPlayer() {
        GameStats best = null;
        for (GameStats stats : gameStats.values()) {
            if (best == null || stats.getPoints() > best.getPoints()
                    || (stats.getPoints() == best.getPoints() && stats.getKills() > best.getKills())
                    || (stats.getPoints() == best.getPoints() && stats.getKills() == best.getKills()
                            && stats.getDamage() > best.getDamage())) {
                best = stats;
            }
        }
        return best;
    }

    /**
     * Kits unlocked during the game (games played, victory, kills, points
     * scored...): the player is notified and can pick them next time.
     * Compared against the list frozen at game start.
     */
    private void announceUnlockedKits(Player player) {
        GameStats stats = getGameStats(player);
        if (stats == null) {
            return;
        }
        for (KitDefinition kit : main.getKitManager().getKits()) {
            if (!stats.getKitsAtStart().contains(kit.getId())
                    && main.getKitManager().isUnlocked(player, kit)) {
                player.sendMessage(Main.PREFIX + Messages.tr("kits.unlocked", "kit", kit.getDisplayName()));
            }
        }
    }

    /**
     * End-of-game summary, SkyWars style: a centered box with each team's
     * score, then the "tops" (most kills, top scorer, most damage). The
     * whole block is editable in messages.yml (key {@code summary.box},
     * list of lines).
     */
    private void sendSummary() {
        Collection<GameStats> all = gameStats.values();
        if (all.isEmpty()) {
            return;
        }
        GameStats topPoints = null;
        GameStats topKills = null;
        GameStats topDamage = null;
        for (GameStats stats : all) {
            if (topPoints == null || stats.getPoints() > topPoints.getPoints()) {
                topPoints = stats;
            }
            if (topKills == null || stats.getKills() > topKills.getKills()) {
                topKills = stats;
            }
            if (topDamage == null || stats.getDamage() > topDamage.getDamage()) {
                topDamage = stats;
            }
        }
        String nobody = Messages.tr("summary.nobody");
        for (String line : Messages.trLines("summary.box",
                "teams", teamScoresLine(),
                "points_player", topPoints.getPoints() > 0 ? topPoints.getName() : nobody,
                "points", topPoints.getPoints(),
                "kills_player", topKills.getKills() > 0 ? topKills.getName() : nobody,
                "kills", topKills.getKills(),
                "damage_player", topDamage.getDamage() > 0 ? topDamage.getName() : nobody,
                "damage", String.format(java.util.Locale.US, "%.1f", topDamage.getDamage() / 2d))) {
            broadcastCentered(line);
        }
    }

    /**
     * Each team's score on one line: "Red 7/10 &8- Blue 5/10", with each
     * team's color.
     */
    private String teamScoresLine() {
        StringBuilder line = new StringBuilder();
        for (String team : activeTeams()) {
            if (line.length() > 0) {
                line.append(" &8- ");
            }
            line.append(colorOf(team)).append(displayName(team))
                    .append(" &f").append(getScore(team))
                    .append("&7/").append(pointsToWin);
        }
        return line.toString();
    }

    /**
     * Small firework on each winner, purely cosmetic. The
     * Firework/FireworkMeta API is the same in 1.12.2 (stable since long
     * before 1.8).
     */
    private void spawnVictoryFireworks(String winningTeam) {
        Color color = armorColorOf(winningTeam);
        for (Player player : onlinePlayers()) {
            if (!winningTeam.equals(getTeam(player)) || player.getWorld() == null) {
                continue;
            }
            Firework firework = player.getWorld().spawn(player.getLocation(), Firework.class);
            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .withColor(color)
                    .with(FireworkEffect.Type.BURST)
                    .withFlicker()
                    .build());
            meta.setPower(1);
            firework.setFireworkMeta(meta);
        }
    }

    /**
     * Silently abandons the game, with no announced winner, no reward and
     * no message: meant for server shutdown and {@code /tt reload}. The
     * game is cancelled "as if it never happened": the career and period
     * counters (game played, kills, deaths, points) recorded since the
     * start are reset to zero.
     *
     * <p>Only while the game is still running: if the end screen has
     * already played ({@link #end(String)} ran, rewards given), the stats
     * are legitimate and must not be rolled back.
     */
    public void abort() {
        if (isRunning()) {
            for (Map.Entry<UUID, GameStats> entry : gameStats.entrySet()) {
                Player online = Bukkit.getPlayer(entry.getKey());
                String name = online != null ? online.getName() : entry.getValue().getName();
                main.getStatsManager().rollbackGame(entry.getKey(), name, entry.getValue());
            }
        }
        reset();
    }

    /**
     * Sends everyone back to the server lobby and makes the arena
     * available again. Also called at plugin shutdown.
     */
    public void reset() {
        cancelCountdown();
        cancelCycle();
        stopGenerators();

        for (Player player : onlinePlayers()) {
            Compat.resetPlayer(player, GameMode.ADVENTURE);
            main.getScoreboardManager().clear(player);
            main.returnToLobby(player);
        }
        for (Player spectator : onlineSpectators()) {
            releaseSpectator(spectator, true);
        }
        spectators.clear();

        // Disconnected players who could not come back will reconnect in
        // the middle of the map: they are flagged so they get sent to the
        // lobby on their return. The one who reconnected during the end
        // screen (too late to rejoin, already there) is taken out of the
        // map right away.
        for (UUID id : reconnectDeadlines.keySet()) {
            Player online = Bukkit.getPlayer(id);
            if (online == null) {
                main.getArenaManager().markStranded(id);
            } else if (main.getArenaManager().getArenaOf(online) == null) {
                Compat.resetPlayer(online, GameMode.ADVENTURE);
                main.sendToLocalLobby(online);
            }
        }
        reconnectDeadlines.clear();

        players.clear();
        teamOf.clear();
        scores.clear();
        gameStats.clear();
        nextMark.clear();
        protectedUntil.clear();
        overtime = false;
        timeLeft = 0;

        // Map regeneration (blocks then chest contents), then cleanup of
        // the items dropped on the ground by deaths and generators. Only
        // if a game took place (frozen area): a /tt reload must not wipe
        // the map of an arena at rest, where an admin may be building.
        mapTracker.restore();
        if (area != null) {
            clearLooseEntities();
        }
        area = null;
        state = State.WAITING;
    }

    /** Removes items, XP orbs and arrows lying around in the play area. */
    private void clearLooseEntities() {
        Cuboid zone = getArea();
        World world = zone == null ? null : zone.getWorld();
        if (world == null) {
            return;
        }
        for (Entity entity : world.getEntities()) {
            if ((entity instanceof Item || entity instanceof ExperienceOrb || entity instanceof Arrow)
                    && zone.contains(entity.getLocation())) {
                entity.remove();
            }
        }
    }

    // ================= Team colors and names =================

    /** Chat color associated with a team. */
    public static ChatColor colorOf(String teamName) {
        if ("Red".equalsIgnoreCase(teamName)) {
            return ChatColor.RED;
        }
        if ("Blue".equalsIgnoreCase(teamName)) {
            return ChatColor.BLUE;
        }
        if ("Yellow".equalsIgnoreCase(teamName)) {
            return ChatColor.YELLOW;
        }
        if ("Green".equalsIgnoreCase(teamName)) {
            return ChatColor.GREEN;
        }
        return ChatColor.WHITE;
    }

    /** Dye color (leather armor, fireworks) of a team. */
    public static Color armorColorOf(String teamName) {
        if ("Red".equalsIgnoreCase(teamName)) {
            return Color.RED;
        }
        if ("Blue".equalsIgnoreCase(teamName)) {
            return Color.BLUE;
        }
        if ("Yellow".equalsIgnoreCase(teamName)) {
            return Color.YELLOW;
        }
        if ("Green".equalsIgnoreCase(teamName)) {
            return Color.GREEN;
        }
        return Color.WHITE;
    }

    /** Colored wool of a team, for the menu icons. */
    public static TTMaterial woolOf(String teamName) {
        if ("Blue".equalsIgnoreCase(teamName)) {
            return TTMaterial.BLUE_WOOL;
        }
        if ("Yellow".equalsIgnoreCase(teamName)) {
            return TTMaterial.YELLOW_WOOL;
        }
        if ("Green".equalsIgnoreCase(teamName)) {
            return TTMaterial.LIME_WOOL;
        }
        return TTMaterial.RED_WOOL;
    }

    /**
     * Canonical team name matching a user input. Accepted: the internal
     * identifier (Red), the lowercase language key (red) and the name shown
     * in the server's language.
     */
    public static String matchTeamName(String input) {
        if (input == null) {
            return null;
        }
        for (int i = 0; i < TEAM_NAMES.length; i++) {
            if (TEAM_NAMES[i].equalsIgnoreCase(input) || TEAM_KEYS[i].equalsIgnoreCase(input)
                    || ChatColor.stripColor(displayName(TEAM_NAMES[i])).equalsIgnoreCase(input)) {
                return TEAM_NAMES[i];
            }
        }
        return null;
    }

    /**
     * Name of a team as shown to players, in the server's language (key
     * {@code team.name.<key>} of the language file).
     *
     * <p>Safety net: if the key exists neither in the server's messages.yml
     * nor in the jar's copy (a messages.yml predating those keys),
     * {@link Messages#tr} returns the key itself ("team.name.red"). The
     * team's internal name ({@code Red}, {@code Blue}...) is returned then,
     * never the raw key. With the keys shipped in the jar, this can only
     * happen on a broken installation.
     */
    public static String displayName(String teamName) {
        if (teamName == null) {
            return "";
        }
        for (int i = 0; i < TEAM_NAMES.length; i++) {
            if (TEAM_NAMES[i].equalsIgnoreCase(teamName)) {
                String key = "team.name." + TEAM_KEYS[i];
                String shown = Messages.tr(key);
                return key.equals(shown) ? TEAM_NAMES[i] : shown;
            }
        }
        return teamName;
    }

    /**
     * Team names to offer in a command: the displayed name, or the internal
     * identifier if the admin set a name containing spaces.
     */
    public static List<String> teamChoices() {
        List<String> choices = new ArrayList<String>();
        for (String teamName : TEAM_NAMES) {
            String shown = ChatColor.stripColor(displayName(teamName));
            choices.add(shown.isEmpty() || shown.indexOf(' ') >= 0 ? teamName : shown);
        }
        return choices;
    }

    private static void putOrRemove(Map<String, Cuboid> map, String team, Cuboid zone) {
        if (zone == null) {
            map.remove(team);
        } else {
            map.put(team, zone);
        }
    }
}
