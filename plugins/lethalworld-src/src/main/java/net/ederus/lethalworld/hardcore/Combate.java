// STUB de WP0: lo reescribe WP4
package net.ederus.lethalworld.hardcore;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * M5/M12 · Etiqueta de combate, combat log, llegada protegida, Frenesi y Sangre fresca.
 * Esqueleto de WP0: nadie esta en combate ni protegido.
 */
final class Combate {

    private final Hardcore hc;

    Combate(Hardcore hc) {
        this.hc = hc;
        Autotest.pendiente("combate");
    }

    void etiquetar(Player p) {
    }

    boolean enCombate(Player p) {
        return false;
    }

    boolean protegido(Player p) {
        return false;
    }

    void llegada(Player p) {
    }

    void tick(Player p) {
    }

    void alGolpe(EntityDamageByEntityEvent e) {
    }

    void alPvp(EntityDamageByEntityEvent e) {
    }

    void frenesiMob(EntityDamageByEntityEvent e) {
    }

    void alDesconectar(PlayerQuitEvent e) {
    }

    void alSalir(Player p) {
    }

    void parar() {
    }
}
