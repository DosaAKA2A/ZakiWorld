package net.ederus.edm.goditems;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Un golpe puede ser critico por dos caminos a la vez (el de MMOItems y el de
 * caer saltando). Los dos llegan en el mismo tick; este guardia deja pasar solo
 * el primero para que el sonido o el efecto no salgan dobles.
 */
final class Criticos {

    private static final Map<UUID, Integer> ULTIMO = new HashMap<>();
    /**
     * El guardia del que RECIBE, en su propio mapa. En un duelo el mismo jugador
     * puede asestar y llevarse un critico en el mismo tick; con un solo mapa uno
     * de los dos se perderia.
     */
    private static final Map<UUID, Integer> ULTIMO_RECIBIDO = new HashMap<>();

    private Criticos() {
    }

    /** true si es el primer critico de ese jugador en este tick. */
    static boolean primero(Player j) {
        return primero(ULTIMO, j);
    }

    /** Igual, pero para el que se lo lleva. */
    static boolean primeroRecibido(Player j) {
        return primero(ULTIMO_RECIBIDO, j);
    }

    private static boolean primero(Map<UUID, Integer> donde, Player j) {
        int ahora = Bukkit.getCurrentTick();
        Integer antes = donde.put(j.getUniqueId(), ahora);
        return antes == null || antes != ahora;
    }
}
