// STUB de WP0: lo reescribe WP5
package net.ederus.lethalworld.hardcore;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * sec. 1.2 · La Huella: celdas distintas pisadas moviendote tu (anti-AFK de la PARCA).
 * Esqueleto de WP0: nadie esta quieto y todos estan activos (las horas activas cuentan
 * como hoy hasta que WP5 lo reescriba).
 */
final class Huella {

    private final Hardcore hc;

    Huella(Hardcore hc) {
        this.hc = hc;
        Autotest.pendiente("huella");
    }

    /** Una vez por segundo por jugador que cuenta, desde Hardcore.tick. */
    void segundo(Player p) {
    }

    void reiniciar(Player p) {
    }

    /** Pausa el reloj congelar-segundos (golpe de amenaza o de rival valido). */
    void congelar(Player p) {
    }

    void alGolpe(EntityDamageByEntityEvent e) {
    }

    boolean activoEnUltimoMinuto(Player p) {
        return true;
    }

    /** Al desconectarse: la huella se aparca en memoria reconexion-minutos. */
    void aparcar(Player p) {
    }

    void restaurar(Player p) {
    }

    /** Segundos de quietud segun la Huella. */
    int quieto(Player p) {
        return 0;
    }

    void parar() {
    }
}
