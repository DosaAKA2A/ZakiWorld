// STUB de WP0: lo reescribe WP0 (0b)
package net.ederus.lethalworld.hardcore;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Listener unico de las amenazas (PARCA, planideras, Eco). Esqueleto de 0a: no invoca.
 */
final class Amenazas {

    private final Hardcore hc;

    Amenazas(Hardcore hc) {
        this.hc = hc;
    }

    <T extends LivingEntity> T invocar(Class<T> tipo, Location sitio, String amenaza, int nivel,
                                       Component nombre, Consumer<T> extra) {
        return null;
    }

    void vidaLogica(LivingEntity e, double vidaLogica) {
    }

    double escala(LivingEntity e) {
        return 1.0;
    }

    Map<UUID, Double> danoLogico(LivingEntity e) {
        return Map.of();
    }

    void registrarPelea(Runnable tickCada2) {
    }

    void quitarPelea(Runnable tickCada2) {
    }

    int contarVivas() {
        return 0;
    }

    void parar() {
    }
}
