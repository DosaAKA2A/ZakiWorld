// STUB de WP0: lo reescribe WP7
package net.ederus.lethalworld.hardcore;

import org.bukkit.entity.Player;

/**
 * M19 · Encuesta y Voto del Botin: como mucho un menu tras tasar. Esqueleto de WP0: nada.
 */
final class Encuesta {

    private final Hardcore hc;

    Encuesta(Hardcore hc) {
        this.hc = hc;
        Autotest.pendiente("encuesta");
    }

    void trasTasar(Player p) {
    }

    void parar() {
    }
}
