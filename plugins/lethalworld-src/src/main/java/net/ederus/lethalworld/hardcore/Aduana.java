// STUB de WP0: lo reescribe WP1
package net.ederus.lethalworld.hardcore;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * M1 · Aduana: el unico grifo de Calamity (DIS M1, regla 7). Validez entre cuentas,
 * huellas de IP, tramos y topes diarios, Fusible, entrega y registro de todo pago.
 *
 * Esqueleto de WP0: firmas definitivas (PLAN-IMPLEMENTACION sec. 5) y cuerpos que no pagan
 * nada. Mientras sea stub, cualquier pago sale a cero: mejor no pagar que pagar sin topes.
 */
final class Aduana {

    /** Lo que de verdad se entrego en un pago, despues de tramos, topes y Fusible. */
    record Pago(int esencias, long mc, long mcNoPagadas, double recorte, boolean topado) {
    }

    private final Hardcore hc;

    Aduana(Hardcore hc) {
        this.hc = hc;
        Autotest.pendiente("aduana");
    }

    Pago pagar(OfflinePlayer p, String tipo, int esencias, long mobcoins, List<ItemStack> reliquias, String motivo) {
        return new Pago(0, 0, Math.max(0, mobcoins), 0, true);
    }

    boolean valida(OfflinePlayer a, OfflinePlayer b) {
        return false;
    }

    String motivoInvalida(OfflinePlayer a, OfflinePlayer b) {
        return "pendiente";
    }

    String huella(Player p) {
        return "";
    }

    void alEntrar(Player p) {
    }

    void tick() {
    }

    void parar() {
    }
}
