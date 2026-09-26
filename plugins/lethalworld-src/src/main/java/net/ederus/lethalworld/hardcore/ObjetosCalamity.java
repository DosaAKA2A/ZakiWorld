// STUB de WP0: lo reescribe WP3
package net.ederus.lethalworld.hardcore;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * M34 · Talisman de Vigilia, Grabado y Salvoconducto, y el aura del [5] del Manto y de la
 * Guadana. Esqueleto de WP0: sin efecto.
 */
final class ObjetosCalamity {

    private final Hardcore hc;

    ObjetosCalamity(Hardcore hc) {
        this.hc = hc;
        Autotest.pendiente("objetos");
    }

    /** Multiplicador del drenaje de cordura (Talisman: 0,80). */
    double factorDrenaje(Player p) {
        return 1.0;
    }

    void tick(Player p) {
    }

    /** Salvoconducto: aparta la pieza antes de que onMuerte borre el inventario. */
    void alMorir(Player p, PlayerDeathEvent e) {
    }

    void alReaparecer(Player p) {
    }

    void parar() {
    }
}
