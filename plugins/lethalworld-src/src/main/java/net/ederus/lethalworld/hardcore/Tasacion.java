// STUB de WP0: lo reescribe WP2
package net.ederus.lethalworld.hardcore;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * M3 · Tasacion: al salir vivo (puerta o Cristal) junta en UN Aduana.pagar las Reliquias,
 * los creditos de las IV, las Esencias fisicas, la racha, la primera del dia y los
 * contratos. Esqueleto de WP0: no tasa nada.
 */
final class Tasacion {

    /** Lo que pagaria una tasacion. WP2 puede ampliar el record (solo lo usa el). */
    record Resumen(int esencias, long mobcoins, List<String> lineas) {
    }

    private final Hardcore hc;

    Tasacion(Hardcore hc) {
        this.hc = hc;
        Autotest.pendiente("tasacion");
    }

    void tasar(Player p, String motivo) {
    }

    Resumen simular(OfflinePlayer p, List<ItemStack> reliquias) {
        return new Resumen(0, 0, List.of());
    }

    void parar() {
    }
}
