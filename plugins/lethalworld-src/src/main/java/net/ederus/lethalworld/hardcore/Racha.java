// STUB de WP0: lo reescribe WP2
package net.ederus.lethalworld.hardcore;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * M11 · Racha de Codicia: salidas seguidas sin morir. Esqueleto de WP0: siempre 0.
 */
final class Racha {

    private final Hardcore hc;

    Racha(Hardcore hc) {
        this.hc = hc;
    }

    int de(UUID jugador) {
        return 0;
    }

    /** Niveles de mas para los mobs de ese jugador (se suman en Hardcore.bonusNivel). */
    int niveles(Player p) {
        return 0;
    }

    /** Multiplicador del botin de la tasacion; salida = censo del equipo al salir. */
    double factor(Player p, Censo.Foto salida) {
        return 1.0;
    }

    void alMorir(Player p) {
    }

    void parar() {
    }
}
