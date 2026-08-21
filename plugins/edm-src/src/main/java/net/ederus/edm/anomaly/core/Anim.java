package net.ederus.edm.anomaly.core;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.IntConsumer;
import java.util.logging.Level;

/**
 * Motor de animaciones. Cada habilidad es una funcion que recibe el tick actual.
 *
 * Dos garantias que costaron un bug en Rip y no se repiten aqui:
 *  - una excepcion en un tick NO aborta la limpieza; onEnd se ejecuta siempre
 *  - si una animacion falla demasiadas veces se corta sola en vez de escupir
 *    una excepcion por tick durante minutos
 */
public final class Anim {

    private static final int MAX_ERRORS = 5;
    /* Sincronizado y con las entradas debiles: si el scheduler ya solto una
     * tarea terminada, aqui desaparece sola y no se acumulan handles muertos.
     * El candado hace falta porque cancelAll() copia el conjunto entero. */
    private static final Set<BukkitTask> RUNNING =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private Anim() {
    }

    public static BukkitTask run(Plugin plugin, int durationTicks, IntConsumer perTick, Runnable onEnd) {
        return run(plugin, durationTicks, 0, 1, perTick, onEnd);
    }

    public static BukkitTask run(Plugin plugin, int durationTicks, int delay, int period,
                                 IntConsumer perTick, Runnable onEnd) {
        BukkitRunnable task = new BukkitRunnable() {
            int tick = 0;
            int errors = 0;
            boolean finished = false;

            @Override
            public void run() {
                if (finished) return;
                if (tick > durationTicks) {
                    finish();
                    return;
                }
                try {
                    if (perTick != null) perTick.accept(tick);
                } catch (Stop stop) {
                    // la habilidad ha decidido cortar; es una salida normal
                    finish();
                    return;
                } catch (Throwable t) {
                    errors++;
                    plugin.getLogger().log(Level.WARNING,
                            "Animacion con error en el tick " + tick + " (" + errors + "/" + MAX_ERRORS + ")", t);
                    if (errors >= MAX_ERRORS) {
                        finish();
                        return;
                    }
                }
                tick += period;
            }

            private void finish() {
                finished = true;
                try {
                    if (onEnd != null) onEnd.run();
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "Fallo al cerrar una animacion", t);
                } finally {
                    cancel();
                }
            }
        };
        /* A nombre del nucleo: los modulos no son plugins registrados y el
         * scheduler no cancelaria sus tareas al apagar EDM. */
        BukkitTask handle = task.runTaskTimer(net.ederus.edm.Module.dueno(plugin), delay, period);
        RUNNING.add(handle);
        return handle;
    }

    /** Ejecuta algo una sola vez tras un retardo, con las mismas garantias de no reventar. */
    public static BukkitTask later(Plugin plugin, int delayTicks, Runnable action) {
        return plugin.getServer().getScheduler().runTaskLater(net.ederus.edm.Module.dueno(plugin), () -> {
            try {
                action.run();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Fallo en una accion diferida", t);
            }
        }, Math.max(0, delayTicks));
    }

    public static void cancelAll() {
        java.util.List<BukkitTask> copia;
        synchronized (RUNNING) {
            /* ArrayList y no Set.copyOf: una entrada debil puede quedarse en
             * null justo aqui y copyOf revienta con NPE. */
            copia = new java.util.ArrayList<>(RUNNING);
            RUNNING.clear();
        }
        for (BukkitTask t : copia) {
            if (t == null) continue;
            try {
                t.cancel();
            } catch (Throwable ignored) {
            }
        }
    }
}
