// STUB de WP0: lo reescribe WP8
package net.ederus.lethalworld.hardcore;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * M7 · Parte de defuncion: los ultimos golpes y el "por que" al morir.
 * Esqueleto de WP0: no apunta nada.
 */
final class ParteDefuncion {

    private final Hardcore hc;

    ParteDefuncion(Hardcore hc) {
        this.hc = hc;
        Autotest.pendiente("parte");
    }

    /** Lo usa DanoVerdadero: setHealth no genera evento de dano y el parte no lo veria. */
    void anotar(Player v, Entity fuente, double cantidad, String etiqueta) {
    }

    void cerrar(PlayerDeathEvent e) {
    }

    void parar() {
    }
}
