// STUB de WP0: lo reescribe WP9
package net.ederus.lethalworld.hardcore;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * M33 · Horas activas (horas-activas.<uuid>): solo cuenta el segundo si la Huella dice
 * que se ha movido en el ultimo minuto. Esqueleto de WP0: no cuenta.
 */
final class Horas {

    private final Hardcore hc;

    Horas(Hardcore hc) {
        this.hc = hc;
    }

    /** Una vez por segundo por jugador que cuenta, desde Hardcore.contarTiempo. */
    void segundo(Player p) {
    }

    double horasActivas(UUID jugador) {
        return 0;
    }

    void parar() {
    }
}
