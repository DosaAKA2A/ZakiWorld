// STUB de WP0: lo reescribe WP9
package net.ederus.lethalworld.hardcore;

import java.util.UUID;

/**
 * M16 · Hitos y tags (hitos.<id> en la config, hitos-entregados en datos).
 * Esqueleto de WP0: no entrega nada.
 */
final class Hitos {

    private final Hardcore hc;

    Hitos(Hardcore hc) {
        this.hc = hc;
    }

    /** Lo llama Estadisticas.sumar cada vez que cambia una estadistica. */
    void revisar(UUID jugador, String clave) {
    }

    /** Cada 30 s: el [5] del Manto activo dentro. */
    void tickManto() {
    }

    void parar() {
    }
}
