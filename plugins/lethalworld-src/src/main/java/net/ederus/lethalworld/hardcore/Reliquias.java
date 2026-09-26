// STUB de WP0: lo reescribe WP2
package net.ederus.lethalworld.hardcore;

import org.bukkit.inventory.ItemStack;

/**
 * M3 · Reliquias: crear, reconocer, registro reliquias.log y bloqueos de uso.
 * Esqueleto de WP0: no crea ninguna y no reconoce ninguna.
 */
final class Reliquias {

    private final Hardcore hc;

    Reliquias(Hardcore hc) {
        this.hc = hc;
    }

    /** Null mientras sea stub. */
    ItemStack crear(int grado, String origen, String especial, int nivel, String minijefe, boolean valida) {
        return null;
    }

    boolean es(ItemStack item) {
        return false;
    }

    int grado(ItemStack item) {
        return 0;
    }

    String especial(ItemStack item) {
        return null;
    }

    void parar() {
    }
}
