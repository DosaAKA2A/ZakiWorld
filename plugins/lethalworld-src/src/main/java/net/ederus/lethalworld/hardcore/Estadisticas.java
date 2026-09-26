// STUB de WP0: lo reescribe WP0 (0b)
package net.ederus.lethalworld.hardcore;

import java.util.UUID;

/**
 * Contadores por jugador (stats.<uuid>.<clave> y stats-semana). Esqueleto de 0a: nada.
 */
final class Estadisticas {

    private final Hardcore hc;

    Estadisticas(Hardcore hc) {
        this.hc = hc;
    }

    void sumar(UUID jugador, String clave, long n) {
    }

    long de(UUID jugador, String clave) {
        return 0;
    }

    long semana(UUID jugador, String clave) {
        return 0;
    }

    void maximo(UUID jugador, String clave, long valor) {
    }

    void parar() {
    }
}
