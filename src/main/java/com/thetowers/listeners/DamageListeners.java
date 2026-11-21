package com.thetowers.listeners;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.lang.Messages;

/**
 * Damage handling.
 *
 * <p>The friendly-fire check is registered only once (cancelling an event at
 * MONITOR is forbidden by Bukkit): once, at normal priority, and the MONITOR
 * handler only reads (damage counter).
 */
public class DamageListeners implements Listener {

    /** "Infinite" damage applied to a fall into the void: instant death. */
    private static final double VOID_DAMAGE = 1000d;

    private final Main main;

    public DamageListeners(Main main) {
        this.main = main;
    }

    /** Generic damage: fall, fire, void... */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        Arena arena = main.getArenaManager().getArenaOf(player);
        if (arena == null) {
            return;
        }

        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            if (arena.isParticipant(player)) {
                // Instant death rather than several seconds of falling. Going through the damage
                // event instead of a fixed height suits 1.12.2 fine, where the void starts at Y=0.
                event.setDamage(VOID_DAMAGE);
            } else {
                // In the waiting lobby or on the end screen: catch the player.
                event.setCancelled(true);
                Location lobby = arena.isSpectator(player) ? arena.spectatorSpot() : arena.getLobby();
                if (lobby != null && lobby.getWorld() != null) {
                    player.setFallDistance(0f);
                    player.teleport(lobby);
                }
            }
            return;
        }

        // Outside the game (waiting, countdown, end screen), protect players.
        if (!arena.isParticipant(player)) {
            event.setCancelled(true);
            return;
        }

        // Respawn protection: no damage at all (the void is handled above,
        // and /kill still goes through), so nobody dies right at spawn.
        if (arena.hasSpawnProtection(player)
                && event.getCause() != EntityDamageEvent.DamageCause.SUICIDE) {
            event.setCancelled(true);
        }
    }

    /** Damage dealt by another entity: this is where PvP is decided. */
    @EventHandler(ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player victim = (Player) event.getEntity();
        Player attacker = resolveAttacker(event);
        if (attacker == null) {
            return;
        }
        Arena arena = main.getArenaManager().getArenaOf(victim);
        if (arena == null) {
            return;
        }
        // An outside player cannot hit a participant, and vice versa.
        if (!arena.contains(attacker) || !arena.isParticipant(victim) || !arena.isParticipant(attacker)) {
            event.setCancelled(true);
            return;
        }
        // No friendly fire.
        String victimTeam = arena.getTeam(victim);
        if (victimTeam != null && victimTeam.equals(arena.getTeam(attacker))) {
            event.setCancelled(true);
            if (!attacker.getUniqueId().equals(victim.getUniqueId())) {
                attacker.sendMessage(Main.PREFIX + Messages.tr("game.no_friendly_fire"));
            }
        }
    }

    /**
     * Read-only: accumulates the damage actually dealt (end-of-game summary)
     * and marks the victim as "in combat" (combat-log protection).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageDealt(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player attacker = resolveAttacker(event);
        if (attacker == null) {
            return;
        }
        Player victim = (Player) event.getEntity();
        Arena arena = main.getArenaManager().getArenaOf(attacker);
        if (arena != null && arena.isParticipant(attacker) && arena.isParticipant(victim)) {
            arena.recordHit(attacker, victim, event.getFinalDamage());
            // Hitting ends one's own protection: no free hits.
            arena.clearSpawnProtection(attacker);
        }
    }

    /** No hunger in the waiting lobby nor on the end screen. */
    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        Arena arena = main.getArenaManager().getArenaOf(player);
        if (arena != null && !arena.isParticipant(player)) {
            event.setCancelled(true);
        }
    }

    /** Finds the player behind the damage, arrows included. */
    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            return (Player) event.getDamager();
        }
        if (event.getDamager() instanceof Projectile) {
            Projectile projectile = (Projectile) event.getDamager();
            if (projectile.getShooter() instanceof Player) {
                return (Player) projectile.getShooter();
            }
        }
        return null;
    }
}
