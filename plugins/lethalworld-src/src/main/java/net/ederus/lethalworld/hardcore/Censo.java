// STUB de WP0: lo reescribe WP7
package net.ederus.lethalworld.hardcore;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MED sec. 5 · Censo del equipo: escalon por pieza (tier de MMOItems o material vanilla) y
 * agregados de las seis casillas. Lo usan Telemetria, Eco, Racha y Forja.
 * Esqueleto de WP0: todo a cero.
 */
final class Censo {

    record Pieza(String casilla, String material, String mmo, String tier, int escalon, int encantamientos,
                 Set<String> marcas) {
    }

    record Foto(List<Pieza> piezas, double escalonMedio, int escalonMax, int piezasMmo, int piezasCalamity) {

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("escalon_medio", escalonMedio);
            m.put("escalon_max", escalonMax);
            m.put("piezas_mmo", piezasMmo);
            m.put("piezas_calamity", piezasCalamity);
            return m;
        }
    }

    private static final Foto VACIA = new Foto(List.of(), 0, 0, 0, 0);

    private Censo() {
    }

    static Foto de(Player p) {
        return VACIA;
    }

    /** Casco, pechera, grebas, botas, mano y mano secundaria. */
    static Foto de(ItemStack[] seisCasillas) {
        return VACIA;
    }

    static int escalon(ItemStack item) {
        return 0;
    }
}
