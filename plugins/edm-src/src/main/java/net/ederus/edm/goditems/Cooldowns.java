package net.ederus.edm.goditems;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;

import net.ederus.edm.comun.Estilo;
import net.kyori.adventure.text.Component;

/**
 * Los cooldowns, uno por jugador, item y activador.
 *
 * Viven en memoria y NO sobreviven a un reinicio, a proposito. Un cooldown es
 * un freno de segundos: guardarlo en disco significa escribir en el PDC de cada
 * jugador en cada clic de cada item, y lo unico que se gana es que al volver de
 * un reinicio alguien espere los ocho segundos que le quedaban. No compensa.
 *
 * La cuenta atras se pinta en la actionbar desde una unica tarea del modulo. Con
 * una tarea por cooldown, veinte jugadores con tres items serian sesenta tareas
 * vivas a la vez.
 */
public final class Cooldowns {

    private record Espera(long termina, boolean visible, String etiqueta) { }

    private final Map<UUID, Map<String, Espera>> porJugador = new ConcurrentHashMap<>();

    public void poner(Player j, String itemId, Activador a, int ticks, boolean visible) {
        if (j == null || ticks <= 0) return;
        this.porJugador
                .computeIfAbsent(j.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(clave(itemId, a),
                        new Espera(System.currentTimeMillis() + ticks * 50L, visible, itemId));
    }

    /** Ticks que faltan, o 0 si esta libre. */
    public int quedan(Player j, String itemId, Activador a) {
        if (j == null) return 0;
        Map<String, Espera> m = this.porJugador.get(j.getUniqueId());
        if (m == null) return 0;
        Espera e = m.get(clave(itemId, a));
        if (e == null) return 0;
        long falta = e.termina() - System.currentTimeMillis();
        if (falta <= 0) {
            m.remove(clave(itemId, a));
            return 0;
        }
        return (int) Math.ceil(falta / 50.0);
    }

    public void quitar(Player j, String itemId, Activador a) {
        if (j == null) return;
        Map<String, Espera> m = this.porJugador.get(j.getUniqueId());
        if (m != null) m.remove(clave(itemId, a));
    }

    public void quitarTodo(Player j) {
        if (j != null) this.porJugador.remove(j.getUniqueId());
    }

    public void olvidar(UUID uuid) {
        this.porJugador.remove(uuid);
    }

    public void limpiar() {
        this.porJugador.clear();
    }

    /**
     * Pinta en la actionbar el cooldown mas corto que le quede a cada jugador.
     * El mas corto y no todos: la actionbar es UN renglon, y tres cuentas atras
     * a la vez ahi dentro no se leen.
     */
    public void repasar(GodItemsPlugin modulo) {
        long ahora = System.currentTimeMillis();
        for (Map.Entry<UUID, Map<String, Espera>> e : this.porJugador.entrySet()) {
            Player j = modulo.core().getServer().getPlayer(e.getKey());
            Map<String, Espera> suyos = e.getValue();
            suyos.values().removeIf(x -> x.termina() <= ahora);
            if (suyos.isEmpty()) {
                this.porJugador.remove(e.getKey());
                continue;
            }
            if (j == null || !j.isOnline()) continue;

            Espera corta = null;
            for (Espera x : suyos.values()) {
                if (!x.visible()) continue;
                if (corta == null || x.termina() < corta.termina()) corta = x;
            }
            if (corta == null) continue;
            int ticks = (int) Math.ceil((corta.termina() - ahora) / 50.0);
            GodItem def = modulo.registro().porId(corta.etiqueta());
            String nombre = def == null ? corta.etiqueta() : def.nombreVisible();
            j.sendActionBar(Component.empty()
                    .append(Estilo.texto(nombre + "  ", Estilo.CLARO))
                    .append(Estilo.texto(Numeros.reloj(ticks), Estilo.MARCA)));
        }
    }

    private static String clave(String itemId, Activador a) {
        return itemId + "#" + a.name();
    }
}
