// STUB de WP0: lo reescribe WP3
package net.ederus.lethalworld.hardcore;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * M2/M32 · Altar del Umbral: recarga del Frasco, trueques de las paginas Umbral y Forja,
 * y el destello de "Tu camino" al entrar. Esqueleto de WP0: no hace nada.
 */
final class Altar {

    private final Hardcore hc;

    Altar(Hardcore hc) {
        this.hc = hc;
    }

    void alEntrar(Player p) {
    }

    /** Trueque contra el saldo sin menu (/lw hardcore altar probar). */
    boolean probar(OfflinePlayer p, String trueque) {
        return false;
    }

    void parar() {
    }
}
