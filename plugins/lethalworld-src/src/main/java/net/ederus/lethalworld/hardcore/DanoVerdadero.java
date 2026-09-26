// STUB de WP0: lo reescribe WP0 (0b)
package net.ederus.lethalworld.hardcore;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Dano verdadero con tope por golpe (ley 5). Esqueleto de 0a: no hace nada.
 */
final class DanoVerdadero {

    static final Set<UUID> enCurso = new HashSet<>();

    private DanoVerdadero() {
    }

    static void aplicar(Player v, double cantidad, double topeFraccion, LivingEntity fuente, String etiqueta) {
    }
}
