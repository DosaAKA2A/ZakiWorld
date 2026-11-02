package net.ederus.edm.comun;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Los golpes en NORMAL que tienen que ir por delante de los modulos de EDM.
 *
 * Dentro de una prioridad Bukkit llama por orden de registro. Anomaly es un plugin
 * aparte que arranca despues de EDM, asi que sus escuchas de golpes en NORMAL
 * quedarian detras de las de GodItems. Pero el guardian de la arena tiene que
 * devolver el golpe ANTES de que los sets disparen sus efectos, y los esbirros
 * escalan el daño antes de que nadie lo mire, como cuando Anomaly vivia dentro.
 *
 * EDM registra esta escucha antes que sus modulos, y Anomaly apunta aqui las suyas
 * en su orden. Corre detras de WorldGuard (que cancela en NORMAL y va antes que EDM).
 */
public final class PrimeraFila implements Listener {

    private record Turno(Object dueno, boolean ignorarCancelados, Consumer<EntityDamageByEntityEvent> accion) {
    }

    private static final List<Turno> TURNOS = new CopyOnWriteArrayList<>();

    private final Logger log;

    public PrimeraFila(Logger log) {
        this.log = log;
    }

    /** Apunta una escucha; se llaman en el orden en que se apuntan. */
    public static void apuntar(Object dueno, boolean ignorarCancelados, Consumer<EntityDamageByEntityEvent> accion) {
        TURNOS.add(new Turno(dueno, ignorarCancelados, accion));
    }

    /** Quita todas las escuchas de ese dueño (al apagarse). */
    public static void soltar(Object dueno) {
        TURNOS.removeIf(t -> t.dueno() == dueno);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onHit(EntityDamageByEntityEvent e) {
        for (Turno t : TURNOS) {
            if (t.ignorarCancelados() && e.isCancelled()) continue;
            try {
                t.accion().accept(e);
            } catch (Throwable x) {
                log.log(Level.WARNING, "Fallo una escucha de golpes de " + t.dueno(), x);
            }
        }
    }
}
