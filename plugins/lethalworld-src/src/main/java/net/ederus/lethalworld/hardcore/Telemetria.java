// STUB de WP0: lo reescribe WP7
package net.ederus.lethalworld.hardcore;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * M10 · Telemetria: una linea JSON por suceso en telemetria/AAAA-MM.jsonl, con cola
 * propia. Esqueleto de WP0: no escribe nada.
 */
final class Telemetria {

    private final Hardcore hc;

    Telemetria(Hardcore hc) {
        this.hc = hc;
        Autotest.pendiente("telemetria");
        Autotest.pendiente("censo");
    }

    void suceso(String ev, OfflinePlayer quien, Map<String, Object> campos) {
    }

    void entra(Player p) {
    }

    void sale(Player p, String motivo, Map<String, Object> tasado) {
    }

    void muere(Player p, FotoMuerte foto) {
    }

    void parar() {
    }
}
