// STUB de WP0: lo reescribe WP1
package net.ederus.lethalworld.hardcore;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * M31 · /lw hardcore dar: entrega unica de premios, ligados, con topes (llaves, libros).
 * Esqueleto de WP0: no entrega nada.
 */
final class Entregas {

    private final Hardcore hc;

    Entregas(Hardcore hc) {
        this.hc = hc;
    }

    boolean dar(CommandSender quien, String objeto, OfflinePlayer a, int n, String origen) {
        return false;
    }

    /** Llaves del Caos entregadas de verdad (con tope, lo que sobra se paga en Esencias). */
    int llave(OfflinePlayer p, int n, String origen, boolean conTope) {
        return 0;
    }

    ItemStack ligar(ItemStack item, UUID dueno) {
        return item;
    }

    void pendientes(Player p) {
    }

    void parar() {
    }
}
