// STUB de WP0: lo reescribe WP1
package net.ederus.lethalworld.hardcore;

import org.bukkit.entity.Player;

import java.util.function.Consumer;

/**
 * M35 · Monedero: saldo y cobro de MobCoins por config (monedero.*). EDM solo sabe
 * DAR MobCoins; cobrar va por placeholder y comando. Esqueleto de WP0: no disponible.
 */
final class Monedero {

    private final Hardcore hc;

    Monedero(Hardcore hc) {
        this.hc = hc;
        Autotest.pendiente("monedero");
    }

    boolean disponible() {
        return false;
    }

    long saldo(Player p) {
        return 0;
    }

    /** El resultado llega por el consumer (puede ser asincrono); aqui siempre false. */
    void cobrar(Player p, long n, Consumer<Boolean> hecho) {
        if (hecho != null) hecho.accept(false);
    }

    void parar() {
    }
}
