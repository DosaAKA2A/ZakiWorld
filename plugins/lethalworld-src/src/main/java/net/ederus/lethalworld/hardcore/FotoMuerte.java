// STUB de WP0: lo reescribe WP6
package net.ederus.lethalworld.hardcore;

import org.bukkit.entity.Player;

/**
 * sec. 2.3 · La foto de un muerto en Calamity, tomada en onMuerte ANTES de borrar el
 * inventario y de reiniciar la cordura. Esqueleto de WP0: solo el censo (vacio).
 */
final class FotoMuerte {

    private final Censo.Foto censo;

    private FotoMuerte(Censo.Foto censo) {
        this.censo = censo;
    }

    static FotoMuerte de(Player p, Hardcore hc) {
        return new FotoMuerte(Censo.de(p));
    }

    Censo.Foto censo() {
        return censo;
    }
}
