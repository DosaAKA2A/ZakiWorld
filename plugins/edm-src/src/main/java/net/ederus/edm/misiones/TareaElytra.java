package net.ederus.edm.misiones;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.leonardobishop.quests.bukkit.BukkitQuestsPlugin;
import com.leonardobishop.quests.bukkit.tasktype.BukkitTaskType;
import com.leonardobishop.quests.bukkit.util.TaskUtils;
import com.leonardobishop.quests.bukkit.util.constraint.TaskConstraintSet;
import com.leonardobishop.quests.common.player.QPlayer;
import com.leonardobishop.quests.common.player.questprogressfile.TaskProgress;
import com.leonardobishop.quests.common.quest.Quest;
import com.leonardobishop.quests.common.quest.Task;

/*
 * Tarea "ederus_elytra": planear una distancia con elytra.
 *
 * Existe porque la tarea "walking" con mode: elytra de Quests 3.16.1 NO cuenta en
 * este servidor. Quests lee mal la version (escribe "running version 1.1" leyendo
 * el 1 de 26.1.2), carga su capa de compatibilidad de 1.8 y ahi isPlayerGliding()
 * es un metodo vacio que siempre devuelve falso. Aqui preguntamos por isGliding()
 * directo a Paper, sin pasar por esa capa.
 *
 * El resto (a quien se le cuenta, mundos permitidos, progreso) lo resuelve la
 * propia API de Quests, igual que sus tareas de fabrica.
 */
public final class TareaElytra extends BukkitTaskType {

    /* PlayerMoveEvent llega muchas veces por segundo y casi siempre con menos de
     * un metro. Si redondearamos cada evento por separado se perderia casi todo
     * el vuelo, asi que se guarda el sobrante de cada jugador y solo se suman
     * metros enteros. */
    private final Map<UUID, Double> sobrante = new HashMap<>();

    private final BukkitQuestsPlugin quests;

    public TareaElytra(BukkitQuestsPlugin quests) {
        super("ederus_elytra", "Iris Studio", "Planea una distancia con elytra.");
        this.quests = quests;
        super.addConfigValidator(TaskUtils.useRequiredConfigValidator(this, "distance"));
        super.addConfigValidator(TaskUtils.useIntegerConfigValidator(this, "distance"));
    }

    @EventHandler
    public void alMoverse(PlayerMoveEvent evento) {
        Player jugador = evento.getPlayer();
        if (!jugador.isGliding()) {
            return;
        }
        Location desde = evento.getFrom();
        Location hasta = evento.getTo();
        if (hasta == null || desde.getWorld() == null || !desde.getWorld().equals(hasta.getWorld())) {
            return;
        }

        double recorrido = desde.distance(hasta);
        if (recorrido <= 0) {
            return;
        }

        UUID uuid = jugador.getUniqueId();
        double acumulado = this.sobrante.getOrDefault(uuid, 0.0) + recorrido;
        int metros = (int) acumulado;
        this.sobrante.put(uuid, acumulado - metros);
        if (metros <= 0) {
            return;
        }

        contar(jugador, metros);
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent evento) {
        this.sobrante.remove(evento.getPlayer().getUniqueId());
    }

    private void contar(Player jugador, int metros) {
        if (jugador.hasMetadata("NPC")) {
            return;
        }
        QPlayer qJugador = this.quests.getPlayerManager().getPlayer(jugador.getUniqueId());
        if (qJugador == null) {
            return;
        }

        for (TaskUtils.PendingTask pendiente : TaskUtils.getApplicableTasks(jugador, qJugador, this, TaskConstraintSet.ALL)) {
            Quest mision = pendiente.quest();
            Task tarea = pendiente.task();
            TaskProgress progreso = pendiente.taskProgress();

            super.debug("Jugador planeando", mision.getId(), tarea.getId(), jugador.getUniqueId());

            Object valor = tarea.getConfigValue("distance");
            if (!(valor instanceof Number distancia)) {
                continue;
            }
            int necesario = distancia.intValue();

            int llevado = TaskUtils.incrementIntegerTaskProgress(progreso, metros);
            if (llevado >= necesario) {
                progreso.setCompleted(true);
            }
            TaskUtils.sendTrackAdvancement(jugador, mision, tarea, pendiente, necesario);
        }
    }
}
