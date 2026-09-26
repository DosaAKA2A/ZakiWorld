// STUB de WP0: lo reescribe WP2
package net.ederus.lethalworld.hardcore;

import org.bukkit.OfflinePlayer;

import java.util.LinkedHashMap;

/**
 * M2/M3 · Reparto de un minijefe: dano por participante, Sello al mejor danador con
 * piedad, Esencias a cada uno con >= 10 %. Esqueleto de WP0: no reparte.
 */
final class Minijefes {

    private final Hardcore hc;

    Minijefes(Hardcore hc) {
        this.hc = hc;
    }

    /** Para /lw hardcore minijefe muerte: el primero de fracciones es el asesino. */
    void simularMuerte(String tipo, int nivel, LinkedHashMap<OfflinePlayer, Double> fracciones) {
    }

    void parar() {
    }
}
