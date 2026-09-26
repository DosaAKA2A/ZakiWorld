// STUB de WP0: lo reescribe WP8
package net.ederus.lethalworld.hardcore;

import org.bukkit.entity.Player;

/**
 * M9/M21 · Latido y vineta de cordura (y las alucinaciones de M20, clase aparte de WP8).
 * Esqueleto de WP0: silencio.
 */
final class Sentidos {

    private final Hardcore hc;

    Sentidos(Hardcore hc) {
        this.hc = hc;
        Autotest.pendiente("sentidos");
        Autotest.pendiente("alucinaciones");
    }

    /** Una vez por segundo por jugador que cuenta, desde Hardcore.tick. */
    void latido(Player p) {
    }

    void parar() {
    }
}
