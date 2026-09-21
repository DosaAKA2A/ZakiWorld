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

    private Criticos() {
    }

    /** true si es el primer critico de ese jugador en este tick. */
    static boolean primero(Player j) {
        int ahora = Bukkit.getCurrentTick();
        Integer antes = ULTIMO.put(j.getUniqueId(), ahora);
        return antes == null || antes != ahora;
    }
}
