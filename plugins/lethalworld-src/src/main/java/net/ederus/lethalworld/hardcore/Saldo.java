// STUB de WP0: lo reescribe WP1
package net.ederus.lethalworld.hardcore;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * M31 · Saldo de Esencias fuera de Calamity (esencias.<uuid> en hardcore-datos.yml).
 * Esqueleto de WP0: saldo cero y ningun movimiento.
 */
final class Saldo {

    private final Hardcore hc;

    Saldo(Hardcore hc) {
        this.hc = hc;
    }

    long de(UUID jugador) {
        return 0;
    }

    void sumar(UUID jugador, long n, String motivo) {
    }

    /** False si no le llega: nunca deja el saldo en negativo. */
    boolean restar(UUID jugador, long n, String motivo) {
        return false;
    }

    /** Pasa al saldo las Esencias fisicas que lleve encima. Devuelve cuantas. */
    int depositarFisicas(Player p) {
        return 0;
    }

    void parar() {
    }
}
