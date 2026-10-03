package net.ederus.edm.superbeacon;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import net.ederus.edm.comun.Compat;

/**
 * pocion: un efecto de pocion mientras se esta en el alcance.
 *
 * Se renueva cada 2 s con una duracion corta (5 s), ambiente, sin particulas y con su
 * icono en pantalla, como el de un faro. Al salir no se quita: se deja caducar solo, que
 * es lo que hace un faro de verdad y evita parpadeos al pisar el borde.
 *
 * Nunca pisa algo mas fuerte que el jugador ya tenga por otra via (una pocion de Prisa V,
 * un faro vanilla, otro plugin): si el suyo es de mas nivel, o del mismo y dura mas, no se
 * toca. La vision nocturna va con 15 s porque por debajo de 10 la pantalla parpadea.
 */
final class ClasePocion extends ClaseEfecto {

    static final int DURACION = 100;
    static final int DURACION_VISION = 300;

    static final class Pocion extends Efecto {
        private final ClasePocion clase;
        final PotionEffectType tipo;
        final String clavePocion;
        /** El visible: 1 es I. */
        final int nivel;

        Pocion(ClasePocion clase, String clave, String nombre, Material icono, PotionEffectType tipo, int nivel) {
            super(clave, nombre, icono);
            this.clase = clase;
            this.tipo = tipo;
            this.clavePocion = tipo.getKey().getKey();
            this.nivel = nivel;
        }

        @Override
        ClaseEfecto clase() {
            return clase;
        }

        @Override
        String grupo() {
            return "pocion:" + clavePocion;
        }

        @Override
        double fuerza() {
            return nivel;
        }
    }

    ClasePocion(SuperBeaconPlugin plugin) {
        super(plugin, "pocion");
    }

    @Override
    Efecto leer(String clave, String nombre, Material icono, ConfigurationSection s, Consumer<String> error) {
        String texto = s.getString("efecto", "").trim();
        PotionEffectType tipo = resolver(texto);
        if (tipo == null) {
            error.accept("efecto: '" + texto + "' no es un efecto de pocion; se salta");
            return null;
        }
        int nivel = s.getInt("nivel", 1);
        if (nivel < 1 || nivel > 10) {
            int arreglado = Math.max(1, Math.min(10, nivel));
            error.accept("nivel: " + nivel + " esta fuera de 1..10; se usa " + arreglado);
            nivel = arreglado;
        }
        return new Pocion(this, clave, nombre, icono, tipo, nivel);
    }

    /** Por clave del registro (haste) y, si no, por el nombre viejo (FAST_DIGGING). */
    static PotionEffectType resolver(String texto) {
        if (texto == null || texto.isBlank()) return null;
        String k = texto.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (k.startsWith("minecraft:")) k = k.substring("minecraft:".length());
        return Compat.effect(k);
    }

    @Override
    Material icono() {
        return Material.POTION;
    }

    @Override
    boolean porJugador() {
        return true;
    }

    @Override
    List<String> detalle(Efecto e) {
        return List.of(plugin.textos().crudo("detalle-pocion", "&#8A8A8AEfecto de poción mientras estés en su alcance."));
    }

    @Override
    void aplicar(Player p, Efecto e) {
        Pocion x = (Pocion) e;
        int amp = x.nivel - 1;
        int dur = PotionEffectType.NIGHT_VISION.equals(x.tipo) ? DURACION_VISION : DURACION;
        PotionEffect actual = p.getPotionEffect(x.tipo);
        if (actual != null && (actual.getAmplifier() > amp
                || (actual.getAmplifier() == amp && (actual.isInfinite() || actual.getDuration() > dur)))) {
            return;
        }
        p.addPotionEffect(new PotionEffect(x.tipo, dur, amp, true, false, true));
    }
}
