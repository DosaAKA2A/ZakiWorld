// STUB de WP0: lo reescribe WP9
package net.ederus.lethalworld.hardcore;

import org.bukkit.entity.Player;

/**
 * M15 · Kit de Expedicion (lethal_world:prestado). Esqueleto de WP0: no presta nada.
 */
final class Kit {

    private final Hardcore hc;

    Kit(Hardcore hc) {
        this.hc = hc;
        Autotest.pendiente("kit");
    }

    void borrarPrestado(Player p) {
    }

    void parar() {
    }
}
