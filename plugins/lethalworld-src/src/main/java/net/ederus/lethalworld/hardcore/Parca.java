// STUB de WP0: lo reescribe WP5
package net.ederus.lethalworld.hardcore;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * sec. 1 · La PARCA: vigila la Huella, avisa, invoca, guarda lo pendiente y lleva el tope
 * global. Esqueleto de WP0: no hay ninguna.
 */
final class Parca {

    private final Hardcore hc;

    Parca(Hardcore hc) {
        this.hc = hc;
        Autotest.pendiente("parca");
    }

    void tick() {
    }

    /** Si hay una PARCA a presencia-radio de ese jugador. */
    boolean cerca(Player p) {
        return false;
    }

    /** Si ese jugador es la presa de una PARCA viva (ley 6: sin minijefe de cordura). */
    boolean persigue(Player p) {
        return false;
    }

    /** Si en ese sitio una PARCA se esta llevando la cosecha (los mobs no sueltan nada). */
    boolean cosechando(Location donde) {
        return false;
    }

    double factorDrenaje(Player p) {
        return 1.0;
    }

    /** Segundos del Cristal: def, o cristal-segundos-marcado con ella cerca. */
    int segundosCristal(Player p, int def) {
        return def;
    }

    void alMorirPresa(Player p) {
    }

    void alSalir(Player p, String motivo) {
    }

    void alEntrar(Player p) {
    }

    void alDesconectar(Player p) {
    }

    void alVolver(Player p) {
    }

    void parar() {
    }
}
