package com.thetowers.game;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.lang.Messages;

/**
 * Mode "seguir" de un espectador: tras elegir un jugador en el menu, el
 * observador puede quedar enganchado a el y la camara lo sigue
 * automaticamente (teleport rapido cada pocos ticks) en lugar de ir
 * teleportandose a mano.
 *
 * <p>La tarea se cancela sola en cuanto la situacion ya no tiene sentido:
 * espectador fuera de la arena, objetivo desconectado o fuera de la partida,
 * partida terminada. Sin eso, no hay nada que limpiar manualmente; solo se
 * vacian los mapas en la salida del espectador y en la parada del plugin.
 */
public class FollowManager {

    /** Intervalo entre dos teleports de seguimiento, en ticks (5 = 0,25 s). */
    private static final long PERIOD_TICKS = 5L;

    private final Main main;
    /** Espectador -> objetivo retenido (el ultimo jugador del menu). */
    private final Map<UUID, UUID> remembered = new HashMap<UUID, UUID>();
    /** Espectador -> tarea de seguimiento activa. */
    private final Map<UUID, BukkitTask> tasks = new HashMap<UUID, BukkitTask>();

    public FollowManager(Main main) {
        this.main = main;
    }

    /** El espectador eligio a este jugador en el menu: queda retenido. */
    public void remember(Player spectator, Player target) {
        remembered.put(spectator.getUniqueId(), target.getUniqueId());
    }

    /** Activa o desactiva el seguimiento del objetivo retenido. */
    public void toggle(Player spectator, Arena arena) {
        UUID id = spectator.getUniqueId();
        if (tasks.containsKey(id)) {
            stop(spectator);
            spectator.sendMessage(Main.PREFIX + Messages.tr("spectator.follow_off"));
            return;
        }
        UUID targetId = remembered.get(id);
        Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
        if (target == null || !arena.isParticipant(target)) {
            // Ningun objetivo retenido todavia: hay que elegir uno en el menu.
            spectator.sendMessage(Main.PREFIX + Messages.tr("spectator.follow_pick"));
            return;
        }
        start(spectator, arena, target);
        spectator.sendMessage(Main.PREFIX + Messages.tr("spectator.follow_on",
                "color", Arena.colorOf(arena.getTeam(target)), "player", target.getName()));
    }

    /** Detiene el seguimiento de un espectador (salida, desconexion, fin). */
    public void stop(Player spectator) {
        cancelTask(spectator.getUniqueId());
        remembered.remove(spectator.getUniqueId());
    }

    /** Cancela todas las tareas de seguimiento (parada del plugin). */
    public void shutdown() {
        for (UUID id : tasks.keySet().toArray(new UUID[0])) {
            cancelTask(id);
        }
        tasks.clear();
        remembered.clear();
    }

    // ================= Interno =================

    private void start(final Player spectator, final Arena arena, final Player target) {
        final UUID id = spectator.getUniqueId();
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!spectator.isOnline() || !arena.isSpectator(spectator)
                        || !target.isOnline() || !arena.isParticipant(target)) {
                    cancelTask(id);
                    // Aviso solo si el objetivo se fue mientras la partida sigue.
                    if (spectator.isOnline() && arena.isRunning()
                            && (!target.isOnline() || !arena.isParticipant(target))) {
                        spectator.sendMessage(Main.PREFIX + Messages.tr("spectator.follow_cancelled",
                                "player", target.getName()));
                    }
                    return;
                }
                spectator.teleport(target.getLocation());
            }
        }.runTaskTimer(main, 0L, PERIOD_TICKS);
        tasks.put(id, task);
    }

    private void cancelTask(UUID id) {
        BukkitTask task = tasks.remove(id);
        if (task != null) {
            task.cancel();
        }
    }
}
