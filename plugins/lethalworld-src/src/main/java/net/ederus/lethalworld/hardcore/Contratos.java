// STUB de WP0: lo reescribe WP9
package net.ederus.lethalworld.hardcore;

import org.bukkit.entity.Player;

import java.util.List;

/**
 * M14 · Contratos del Umbral: tres al dia, uno corto, solo se cobran en la Tasacion.
 * Esqueleto de WP0: ninguno.
 */
final class Contratos {

    private final Hardcore hc;

    Contratos(Hardcore hc) {
        this.hc = hc;
        Autotest.pendiente("contratos");
    }

    void alEntrar(Player p) {
    }

    void progreso(Player p, String evento, int n) {
    }

    /** Ids de los contratos cumplidos que se cobran en esta tasacion. */
    List<String> cobrarEnTasacion(Player p) {
        return List.of();
    }

    void parar() {
    }
}
