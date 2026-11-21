package com.thetowers.listeners;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

import com.thetowers.Main;
import com.thetowers.api.PapiBridge;
import com.thetowers.api.PlaceholderValues;
import com.thetowers.compat.Compat;
import com.thetowers.lang.Messages;

/**
 * Main-lobby world protection AND the join messages shown when a player
 * enters the lobby (server join, coming back from a game, or changing into
 * the lobby world). Every protection is individually toggleable in
 * config.yml under {@code lobby-protection}; the join messages under
 * {@code join-messages}; the stone pressure plate -> randomjoin under
 * {@code lobby-pressure-plate}.
 *
 * <p>The "lobby world" is the world of the server lobby set with
 * {@code /tt setlobby}. If no lobby is set, the protections are inactive.
 */
public class LobbyProtectionListener implements Listener {

    private final Main main;
    /** Per-player cooldown (millis) for the pressure-plate command, to avoid spam. */
    private final Map<UUID, Long> plateCooldown = new HashMap<UUID, Long>();
    /** Last time the join message was sent to each player, to dedupe the join +
     * world-change double trigger (see {@link #sendJoinMessage}). */
    private final Map<UUID, Long> lastJoinMessage = new HashMap<UUID, Long>();

    public LobbyProtectionListener(Main main) {
        this.main = main;
    }

    /** The lobby world, or null if no server lobby is set. */
    private World lobbyWorld() {
        Location lobby = main.getServerLobby();
        return lobby == null ? null : lobby.getWorld();
    }

    private boolean inLobby(Player player) {
        World lobby = lobbyWorld();
        return lobby != null && player.getWorld().equals(lobby);
    }

    /**
     * Players with the {@code thetowers.admin} permission bypass the lobby
     * protection entirely: they keep their game mode, can break and place
     * blocks, switch game modes and open containers in the lobby world.
     */
    private boolean bypassesProtection(Player player) {
        return player != null && player.hasPermission("thetowers.admin");
    }

    // ================= Join messages =================

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        // Suppress the vanilla join message in the lobby if we send our own.
        if (inLobby(player) && joinMessagesEnabled()) {
            event.setJoinMessage(null);
        }
        // Apply the lobby protections right away (adventure, etc.), one tick
        // later so the player is fully connected.
        Bukkit.getScheduler().runTask(main, new Runnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    return;
                }
                applyLobbyState(player);
                if (inLobby(player)) {
                    sendJoinMessage(player);
                }
            }
        });
    }

    /** True if either the default or the VIP join message is enabled. */
    private boolean joinMessagesEnabled() {
        return main.getConfig().getBoolean("join-messages.default-enabled", false)
                || main.getConfig().getBoolean("join-messages.vip-enabled", true);
    }

    /** Re-applies on world change (coming back from a game into the lobby). */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        applyLobbyState(player);
        if (inLobby(player)) {
            sendJoinMessage(player);
        }
    }

    /** Clears the join-message dedupe entry when a player leaves. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastJoinMessage.remove(event.getPlayer().getUniqueId());
    }

    /** Sends the join message (vip if allowed, otherwise the default one). */
    private void sendJoinMessage(Player player) {
        if (!joinMessagesEnabled()) {
            return;
        }
        // Dedupe: a server join into the lobby fires BOTH PlayerJoinEvent
        // (handled one tick later below) and PlayerChangedWorldEvent (when the
        // join teleport moves the player into the lobby world). Without this,
        // the message shows twice. A 3-second window per player is enough.
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastJoinMessage.get(id);
        if (last != null && now - last.longValue() < 3000L) {
            return;
        }
        lastJoinMessage.put(id, now);
        boolean vipEnabled = main.getConfig().getBoolean("join-messages.vip-enabled", true);
        boolean defaultEnabled = main.getConfig().getBoolean("join-messages.default-enabled", false);
        String vipPerm = main.getConfig().getString("join-messages.vip-permission", "thetowers.join.vip");
        if (vipEnabled && vipPerm != null && !vipPerm.isEmpty() && player.hasPermission(vipPerm)) {
            broadcast(Messages.tr("join.vip"), player, "join.vip");
        } else if (defaultEnabled) {
            broadcast(Messages.tr("join.default"), player, "join.default");
        }
    }

    /** Resolves placeholders in a message line and broadcasts it. */
    private void broadcast(String raw, Player player, String key) {
        if (raw == null || raw.isEmpty() || raw.equals(key)) {
            return;
        }
        // Player-identity placeholders are resolved here directly, BEFORE
        // PlaceholderAPI, so they work even if the PAPI "player" expansion is
        // not installed (where %player_name% would otherwise stay literal).
        String name = player.getName();
        String out = raw
                .replace("%player%", name)
                .replace("%player_name%", name)
                .replace("%player_displayname%", player.getDisplayName())
                .replace("%player_uuid%", player.getUniqueId().toString());
        // Our own %thetowers_...% placeholders.
        out = PlaceholderValues.replace(main, player, out);
        // Everything else (%luckperms_prefix%, %vault_...%, ...) via PAPI.
        if (main.hasPlaceholderApi() && out.indexOf('%') >= 0) {
            out = PapiBridge.apply(player, out);
        }
        out = Compat.color(out);
        // The main-lobby join message is only shown to players who are
        // actually in the main-lobby world (not broadcast to every world).
        World lobby = lobbyWorld();
        if (lobby == null) {
            player.sendMessage(out);
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getWorld().equals(lobby)) {
                viewer.sendMessage(out);
            }
        }
    }

    // ================= Lobby state (adventure, no hunger/fall/pvp/drops) =================

    /** Adventure mode + full health/hunger when entering the lobby. */
    private void applyLobbyState(Player player) {
        if (!inLobby(player)) {
            return;
        }
        // Admins bypass the lobby state: they keep their own game mode so they
        // can build in the lobby.
        if (bypassesProtection(player)) {
            return;
        }
        if (main.getConfig().getBoolean("lobby-protection.adventure-mode", true)) {
            if (player.getGameMode() != GameMode.ADVENTURE && player.getGameMode() != GameMode.CREATIVE) {
                player.setGameMode(GameMode.ADVENTURE);
            }
        }
        if (main.getConfig().getBoolean("lobby-protection.no-hunger", true)) {
            player.setFoodLevel(20);
            player.setSaturation(20f);
        }
        if (main.getConfig().getBoolean("lobby-protection.no-fall-damage", true)) {
            player.setFallDistance(0f);
        }
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(main, new Runnable() {
            @Override
            public void run() {
                applyLobbyState(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (!inLobby(event.getPlayer())) {
            return;
        }
        if (bypassesProtection(event.getPlayer())) {
            return;
        }
        if (main.getConfig().getBoolean("lobby-protection.adventure-mode", true)
                && event.getNewGameMode() != GameMode.ADVENTURE
                && event.getNewGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        if (inLobby(player) && main.getConfig().getBoolean("lobby-protection.no-hunger", true)) {
            event.setCancelled(true);
            player.setFoodLevel(20);
            player.setSaturation(20f);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        if (!inLobby(player)) {
            return;
        }
        if (main.getConfig().getBoolean("lobby-protection.no-fall-damage", true)
                && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player victim = (Player) event.getEntity();
        if (inLobby(victim) && main.getConfig().getBoolean("lobby-protection.no-pvp", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!inLobby(player)) {
            return;
        }
        if (main.getConfig().getBoolean("lobby-protection.no-death-drops", true)) {
            event.getDrops().clear();
            event.setKeepLevel(true);
        }
    }

    // ================= Block break / place / container interaction =================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (bypassesProtection(event.getPlayer())) {
            return;
        }
        if (inLobby(event.getPlayer())
                && main.getConfig().getBoolean("lobby-protection.prevent-block-break", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (bypassesProtection(event.getPlayer())) {
            return;
        }
        if (inLobby(event.getPlayer())
                && main.getConfig().getBoolean("lobby-protection.prevent-block-place", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!inLobby(event.getPlayer())) {
            return;
        }
        if (bypassesProtection(event.getPlayer())) {
            return;
        }
        if (!main.getConfig().getBoolean("lobby-protection.prevent-interact-containers", true)) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        if (isContainer(block.getType())) {
            event.setCancelled(true);
        }
    }

    /** Blocks with an inventory (chests, furnaces, dispensers, hoppers, etc.). */
    private static boolean isContainer(Material type) {
        if (type == null) {
            return false;
        }
        switch (type.name()) {
            case "CHEST":
            case "TRAPPED_CHEST":
            case "ENDER_CHEST":
            case "FURNACE":
            case "BURNING_FURNACE":
            case "DISPENSER":
            case "DROPPER":
            case "HOPPER":
            case "BREWING_STAND":
            case "BEACON":
            case "ANVIL":
            case "WORKBENCH":
            case "ENCHANTMENT_TABLE":
            case "CRAFTING_TABLE":
            case "SHULKER_BOX":
            case "BLACK_SHULKER_BOX":
            case "BLUE_SHULKER_BOX":
            case "BROWN_SHULKER_BOX":
            case "CYAN_SHULKER_BOX":
            case "GRAY_SHULKER_BOX":
            case "GREEN_SHULKER_BOX":
            case "LIGHT_BLUE_SHULKER_BOX":
            case "LIME_SHULKER_BOX":
            case "MAGENTA_SHULKER_BOX":
            case "ORANGE_SHULKER_BOX":
            case "PINK_SHULKER_BOX":
            case "PURPLE_SHULKER_BOX":
            case "RED_SHULKER_BOX":
            case "SILVER_SHULKER_BOX":
            case "WHITE_SHULKER_BOX":
            case "YELLOW_SHULKER_BOX":
                return true;
            default:
                return type.name().endsWith("DOOR") || type.name().endsWith("BUTTON")
                        || type.name().endsWith("TRAP_DOOR") || type.name().endsWith("FENCE_GATE");
        }
    }

    // ================= Void teleport + pressure plate =================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!inLobby(player)) {
            return;
        }
        // Void teleport: y <= 0 sends the player back to the lobby.
        if (main.getConfig().getBoolean("lobby-protection.void-teleport", true)
                && player.getLocation().getY() <= 0d) {
            Location lobby = main.getServerLobby();
            if (lobby != null) {
                player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                player.setFallDistance(0f);
                player.teleport(lobby);
            }
        }
    }

    /**
     * Pressure plate in the main lobby: stepping on it runs the configured
     * command (default {@code tt randomjoin}). Uses {@link Action#PHYSICAL},
     * which fires exactly when a player activates a pressure plate, instead of
     * guessing the block under the feet; the material is matched by name so it
     * works on every version (1.12 {@code *_PLATE} and 1.13+
     * {@code *_PRESSURE_PLATE}).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPressurePlate(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) {
            return;
        }
        Player player = event.getPlayer();
        if (!inLobby(player)) {
            return;
        }
        if (!main.getConfig().getBoolean("lobby-pressure-plate.enabled", true)) {
            return;
        }
        Block plate = event.getClickedBlock();
        if (plate == null || !isPressurePlate(plate.getType())) {
            return;
        }
        runPlateCommand(player);
    }

    /** True for any pressure plate, on any Bukkit version (matched by name). */
    private static boolean isPressurePlate(Material type) {
        if (type == null) {
            return false;
        }
        String name = type.name();
        // 1.12: STONE_PLATE, WOOD_PLATE, GOLD_PLATE, IRON_PLATE.
        // 1.13+: STONE_PRESSURE_PLATE, OAK_PRESSURE_PLATE, ..._WEIGHTED_PRESSURE_PLATE.
        return name.endsWith("_PLATE") || name.contains("PRESSURE_PLATE");
    }

    /** Runs the pressure-plate command, once per cooldown. */
    private void runPlateCommand(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = plateCooldown.get(id);
        if (last != null && now - last.longValue() < 3000L) {
            return; // 3-second cooldown to avoid spamming the command.
        }
        plateCooldown.put(id, now);
        String cmd = main.getConfig().getString("lobby-pressure-plate.command", "tt randomjoin");
        if (cmd == null || cmd.trim().isEmpty()) {
            return;
        }
        cmd = cmd.trim();
        boolean console = cmd.toLowerCase(java.util.Locale.ROOT).startsWith("console:");
        if (console) {
            cmd = cmd.substring("console:".length()).trim().replace("%player%", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } else {
            player.performCommand(cmd);
        }
    }

    /** Reads {@code lobby-protection.<key>} (true when the whole section is on). */
    private boolean protect(String key) {
        return main.getConfig().getBoolean(key + ".enabled",
                main.getConfig().getBoolean("lobby-protection.enabled", true));
    }
}
