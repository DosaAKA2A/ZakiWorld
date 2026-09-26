package net.ederus.lethalworld.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.lethalworld.LethalWorldPlugin;
import org.bukkit.GameMode;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Dano verdadero: el que no recortan la armadura, la Proteccion ni la reduccion de
 * MythicLib (Siega, Campanada, el Juicio). DIS sec. 0.4 y sec. 0.3 "Dano verdadero".
 *
 * Se quita con setHealth, despues de gastar la absorcion, para que no lo toque nadie por
 * el camino. Solo el golpe que mata va por damage(MAGIC) con la fuente puesta: asi la
 * muerte se atribuye a la PARCA o al Eco (mensaje, estadisticas, parte) y no sale "murio"
 * sin mas. Mientras dura ese golpe el jugador esta en enCurso, y Hardcore.onGolpe no le
 * aplica la penetracion encima (ya es exacto).
 *
 * Ley 5: ningun golpe quita mas de topeFraccion de la vida maxima. Con la vida llena,
 * nada te mata de un golpe.
 */
final class DanoVerdadero {

    /** Jugadores recibiendo ahora mismo el golpe letal del dano verdadero. Solo hilo principal. */
    static final Set<UUID> enCurso = new HashSet<>();

    private DanoVerdadero() {
    }

    /**
     * @param cantidad     vida a quitar (puntos, no corazones)
     * @param topeFraccion ley 5: fraccion de la vida maxima que puede quitar como mucho (0 = sin tope)
     * @param fuente       la amenaza que pega (puede ser null)
     * @param etiqueta     nombre de la habilidad para el parte de defuncion ("Siega")
     */
    static void aplicar(Player v, double cantidad, double topeFraccion, LivingEntity fuente, String etiqueta) {
        if (v == null || !v.isValid() || v.isDead() || cantidad <= 0) return;
        if (v.getGameMode() == GameMode.CREATIVE || v.getGameMode() == GameMode.SPECTATOR) return;
        double vidaMax = Compat.getAttribute(v, "max_health", 20);
        double quita = recorte(cantidad, topeFraccion, vidaMax);
        if (quita <= 0) return;
        Hardcore hc = hardcore();

        // El parte antes que el golpe: si este mata, onMuerte cierra el parte en el acto y
        // tiene que encontrarlo ya apuntado (setHealth no genera evento de dano).
        if (hc != null && hc.parte() != null) hc.seguro("parte", () -> hc.parte().anotar(v, fuente, quita, etiqueta));

        double absorcion = v.getAbsorptionAmount();
        double deAbsorcion = Math.min(absorcion, quita);
        if (deAbsorcion > 0) v.setAbsorptionAmount(absorcion - deAbsorcion);
        double resto = quita - deAbsorcion;
        if (resto > 0) {
            if (v.getHealth() - resto > 0) {
                v.setHealth(v.getHealth() - resto);
                v.playHurtAnimation(0f);
                Compat.soundPlayers(v.getWorld(), v.getLocation(), "entity.player.hurt", 1f, 1f);
            } else {
                matar(v, fuente);
            }
        }

        if (hc != null) {
            if (hc.combate() != null) hc.seguro("combate", () -> hc.combate().etiquetar(v));
            if (hc.huella() != null) hc.seguro("huella", () -> hc.huella().congelar(v));
        }
    }

    /** El golpe letal: por damage(MAGIC) con la fuente, para que la muerte sea suya. */
    private static void matar(Player v, LivingEntity fuente) {
        UUID id = v.getUniqueId();
        enCurso.add(id);
        try {
            DamageSource.Builder b = DamageSource.builder(DamageType.MAGIC);
            if (fuente != null) b = b.withCausingEntity(fuente).withDirectEntity(fuente);
            v.damage(v.getHealth() + 1000, b.build());
        } finally {
            enCurso.remove(id);
        }
    }

    /** Lo que quita de verdad: la cantidad con el tope de la ley 5 (fraccion de la vida maxima). */
    static double recorte(double cantidad, double topeFraccion, double vidaMax) {
        double tope = topeFraccion > 0 ? topeFraccion * vidaMax : Double.MAX_VALUE;
        return Math.max(0, Math.min(cantidad, tope));
    }

    private static Hardcore hardcore() {
        try {
            return JavaPlugin.getPlugin(LethalWorldPlugin.class).hardcore();
        } catch (Throwable t) {
            return null;
        }
    }
}
