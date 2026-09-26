// STUB de WP0: lo reescribe WP1
package net.ederus.lethalworld.hardcore;

import java.util.UUID;

/**
 * M32 · Creditos por jugador (creditos.<uuid>.<tipo>): sello:<minijefe>, sello-errante,
 * fragmento, marca. Esqueleto de WP0: nadie tiene nada y nada se gasta.
 */
final class Creditos {

    private final Hardcore hc;

    Creditos(Hardcore hc) {
        this.hc = hc;
    }

    int de(UUID jugador, String tipo) {
        return 0;
    }

    void sumar(UUID jugador, String tipo, int n, String origen, boolean deCaja) {
    }

    boolean gastar(UUID jugador, String tipo, int n) {
        return false;
    }

    boolean canjeable(UUID jugador, String tipo) {
        return false;
    }

    void parar() {
    }
}
