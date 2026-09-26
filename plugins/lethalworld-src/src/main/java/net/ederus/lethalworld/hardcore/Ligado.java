// STUB de WP0: lo reescribe WP4
package net.ederus.lethalworld.hardcore;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * M30 · Objetos ligados (lethal_world:ligado): no se venden, no se tiran, no se pasan.
 * Esqueleto de WP0: nada esta ligado.
 */
final class Ligado {

    private final Hardcore hc;

    Ligado(Hardcore hc) {
        this.hc = hc;
        Autotest.pendiente("ligado");
    }

    UUID dueno(ItemStack item) {
        return null;
    }

    boolean ligado(ItemStack item) {
        return false;
    }

    void parar() {
    }
}
