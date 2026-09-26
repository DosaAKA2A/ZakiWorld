// STUB de WP0: lo reescribe WP2
package net.ederus.lethalworld.hardcore;

import org.bukkit.event.world.LootGenerateEvent;

/**
 * M6 · Botin de los cofres de estructura (cofres.*): filtro vanilla y lo que se anade.
 * Esqueleto de WP0: el cofre sale como sale hoy.
 */
final class Cofres {

    private final Hardcore hc;

    Cofres(Hardcore hc) {
        this.hc = hc;
        Autotest.pendiente("cofres");
    }

    void alGenerar(LootGenerateEvent e) {
    }

    void parar() {
    }
}
