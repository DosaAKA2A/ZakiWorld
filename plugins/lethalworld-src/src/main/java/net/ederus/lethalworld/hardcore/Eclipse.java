// STUB de WP0: lo reescribe WP11
package net.ederus.lethalworld.hardcore;

import org.bukkit.entity.Player;

/**
 * M13 · Eclipse de Calamidad. Esqueleto de WP0: nunca hay eclipse y todo multiplica x1.
 */
final class Eclipse {

    private final Hardcore hc;

    Eclipse(Hardcore hc) {
        this.hc = hc;
        Autotest.pendiente("eclipse");
    }

    boolean activo() {
        return false;
    }

    double factorCordura() {
        return 1.0;
    }

    int nivelesExtra() {
        return 0;
    }

    double factorBotin() {
        return 1.0;
    }

    double factorPvp() {
        return 1.0;
    }

    /** Minutos entre minijefes de cordura cero; def fuera del eclipse. */
    int minutosMinijefe(int def) {
        return def;
    }

    void alEntrar(Player p) {
    }

    void tick() {
    }

    void parar() {
    }
}
