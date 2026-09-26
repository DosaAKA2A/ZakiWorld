// STUB de WP0: lo reescribe WP2
package net.ederus.lethalworld.hardcore;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * M2 · El grifo de los mobs de Calamity: cosecha de la PARCA, cierre de spawners, cuota
 * de jugador, MobCoins por la Aduana, Esencias y Reliquias.
 *
 * Es publica porque la llama MobsLethal (otro paquete). Esqueleto de WP0: paga como hoy
 * (MobsLethal.pagarComoHoy), para que entre el commit 0a y la mezcla de WP2 matar en
 * Calamity siga dando lo mismo que antes de la actualizacion.
 */
public final class Grifo {

    private final Hardcore hc;

    Grifo(Hardcore hc) {
        this.hc = hc;
        Autotest.pendiente("grifo");
    }

    /** Muerte de un mob con la marca edm:lethal_world_mob dentro de un mundo hardcore. killer puede ser null. */
    public void alMorir(EntityDeathEvent e, Player killer, String marca) {
        if (killer == null || hc.plugin().mobs() == null) return;
        hc.plugin().mobs().pagarComoHoy(e, killer, marca);
    }

    void parar() {
    }
}
