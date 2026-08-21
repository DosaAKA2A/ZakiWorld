package net.ederus.edm.coinflip;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Cuando alguien se va, sus apuestas se le devuelven.
 *
 * Una mesa cuyo dueño no esta conectado no se puede jugar (hace falta que los
 * dos vean la moneda), asi que dejarla ahi solo sirve para que otro la pulse y
 * se lleve un "ya no esta conectado". Se cancela y se le devuelve el dinero.
 */
public final class Salidas implements Listener {

    private final CoinflipPlugin modulo;

    public Salidas(CoinflipPlugin modulo) {
        this.modulo = modulo;
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        if (!modulo.mesa().cancelarAlSalir()) return;
        Player p = e.getPlayer();
        int n = modulo.mesa().cancelarDe(p.getUniqueId());
        if (n > 0) {
            modulo.core().getLogger().info("Devueltas " + n + " apuestas de " + p.getName() + " al salir.");
        }
    }
}
