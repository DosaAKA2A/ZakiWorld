package net.ederus.edm.goditems;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * El permiso de vuelo prestado unos segundos.
 *
 * Es lo que hace que LEVANTAR y DASH no acaben en expulsion. Cuando a un
 * jugador se le da una velocidad vertical grande, el servidor ve a alguien
 * subiendo sin permiso de vuelo y lo echa por "flying is not enabled on this
 * server". ExecutableItems tropieza exactamente aqui.
 *
 * Reglas, porque prestar vuelo mal es peor que no prestarlo:
 *  - a quien ya podia volar (creativo, espectador, permiso propio) no se le
 *    toca nada, ni al dar ni al quitar;
 *  - si mientras tanto el jugador se pone a volar de verdad, se le deja: el
 *    devolver no lo tira del cielo;
 *  - solo se guarda el estado del PRIMER prestamo, asi que dos empujones
 *    seguidos no dejan el permiso puesto para siempre.
 */
public final class Vuelo {

    private record Prestado(boolean teniaPermiso, GameMode modo) { }

    private final Map<UUID, Prestado> prestados = new ConcurrentHashMap<>();

    public void prestar(GodItemsPlugin modulo, Player j, int ticks) {
        if (j == null || ticks <= 0) return;
        GameMode modo = j.getGameMode();
        if (modo == GameMode.CREATIVE || modo == GameMode.SPECTATOR) return;
        if (j.getAllowFlight()) return;

        this.prestados.putIfAbsent(j.getUniqueId(), new Prestado(false, modo));
        j.setAllowFlight(true);

        modulo.core().getServer().getScheduler().runTaskLater(modulo.core(),
                () -> devolver(j), ticks);
    }

    public void devolver(Player j) {
        if (j == null) return;
        Prestado p = this.prestados.remove(j.getUniqueId());
        if (p == null || p.teniaPermiso()) return;
        if (!j.isOnline()) return;
        GameMode modo = j.getGameMode();
        if (modo == GameMode.CREATIVE || modo == GameMode.SPECTATOR) return;
        /* Si esta volando de verdad en este instante, quitarle el permiso lo
         * deja cayendo desde donde este. Se espera al siguiente prestamo o a
         * que aterrice; no vale la pena mas. */
        if (j.isFlying()) return;
        j.setAllowFlight(false);
    }

    public void olvidar(UUID uuid) {
        this.prestados.remove(uuid);
    }

    public void devolverTodo(GodItemsPlugin modulo) {
        for (UUID u : this.prestados.keySet()) {
            Player j = modulo.core().getServer().getPlayer(u);
            if (j != null) devolver(j);
        }
        this.prestados.clear();
    }
}
