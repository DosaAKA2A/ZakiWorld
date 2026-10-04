package net.ederus.edm.superbeacon;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    /** El nombre en español de cada efecto de pocion (el del juego en es_MX). Respaldo de pocion-<clave>. */
    static final Map<String, String> NOMBRES = Map.ofEntries(
            Map.entry("speed", "Velocidad"), Map.entry("slowness", "Lentitud"), Map.entry("haste", "Prisa"),
            Map.entry("mining_fatigue", "Fatiga minera"), Map.entry("strength", "Fuerza"),
            Map.entry("instant_health", "Curación instantánea"), Map.entry("instant_damage", "Daño instantáneo"),
            Map.entry("jump_boost", "Supersalto"), Map.entry("nausea", "Náuseas"),
            Map.entry("regeneration", "Regeneración"), Map.entry("resistance", "Resistencia"),
            Map.entry("fire_resistance", "Resistencia al fuego"), Map.entry("water_breathing", "Respiración acuática"),
            Map.entry("invisibility", "Invisibilidad"), Map.entry("blindness", "Ceguera"),
            Map.entry("night_vision", "Visión nocturna"), Map.entry("hunger", "Hambre"),
            Map.entry("weakness", "Debilidad"), Map.entry("poison", "Veneno"), Map.entry("wither", "Descomposición"),
            Map.entry("health_boost", "Vida extra"), Map.entry("absorption", "Absorción"),
            Map.entry("saturation", "Saturación"), Map.entry("glowing", "Brillo"), Map.entry("levitation", "Levitación"),
            Map.entry("luck", "Suerte"), Map.entry("unluck", "Mala suerte"), Map.entry("slow_falling", "Caída lenta"),
            Map.entry("conduit_power", "Poder del conducto"), Map.entry("dolphins_grace", "Gracia de delfín"),
            Map.entry("bad_omen", "Mal presagio"), Map.entry("hero_of_the_village", "Héroe de la aldea"),
            Map.entry("darkness", "Oscuridad"), Map.entry("trial_omen", "Presagio de desafío"),
            Map.entry("raid_omen", "Presagio de invasión"), Map.entry("wind_charged", "Carga de viento"),
            Map.entry("weaving", "Tejido"), Map.entry("oozing", "Supuración"), Map.entry("infested", "Infestación"));

    /** Lo que se nota con cada uno, para la frase del menu. Respaldo de pocion-que-<clave>. */
    static final Map<String, String> QUE = Map.ofEntries(
            Map.entry("haste", "Picas y talas más rápido"), Map.entry("speed", "Te mueves más rápido"),
            Map.entry("regeneration", "Recuperas vida poco a poco"), Map.entry("resistance", "Recibes menos daño"),
            Map.entry("strength", "Tus golpes hacen más daño"), Map.entry("night_vision", "Ves en la oscuridad"),
            Map.entry("saturation", "No pasas hambre"), Map.entry("fire_resistance", "El fuego y la lava no te queman"),
            Map.entry("water_breathing", "Respiras bajo el agua"), Map.entry("jump_boost", "Saltas más alto"),
            Map.entry("slow_falling", "Caes despacio"), Map.entry("health_boost", "Tienes más vida máxima"),
            Map.entry("absorption", "Tienes corazones extra de absorción"),
            Map.entry("luck", "Mejor botín en cofres y pesca"),
            Map.entry("conduit_power", "Respiras y picas mejor bajo el agua"),
            Map.entry("dolphins_grace", "Nadas más rápido"));

    /** Los que cuidan la vida: van con el corazon en el lore. */
    private static final Set<String> DE_VIDA = Set.of("regeneration", "resistance", "absorption", "health_boost",
            "fire_resistance", "instant_health", "water_breathing", "saturation");

    /** "Prisa III", "Visión nocturna" (el nivel I no se escribe, como en el juego). */
    String nombreConNivel(Pocion x) {
        String porDefecto = NOMBRES.getOrDefault(x.clavePocion,
                Character.toUpperCase(x.clavePocion.charAt(0)) + x.clavePocion.substring(1).replace('_', ' '));
        String n = plugin.textos().crudo("pocion-" + x.clavePocion, porDefecto);
        return x.nivel > 1 ? n + " " + Presentacion.romano(x.nivel) : n;
    }

    @Override
    String que(Efecto e) {
        Pocion x = (Pocion) e;
        String nombre = nombreConNivel(x);
        String que = plugin.textos().crudo("pocion-que-" + x.clavePocion, QUE.get(x.clavePocion));
        if (que == null || que.isBlank()) {
            return plugin.textos().crudo("detalle-pocion-generico", "Tienes %efecto% mientras estés en su alcance.")
                    .replace("%efecto%", nombre);
        }
        return plugin.textos().crudo("detalle-pocion", "%que% mientras estés en su alcance.")
                .replace("%que%", que).replace("%efecto%", nombre);
    }

    @Override
    int seccion(Efecto e) {
        return DE_VIDA.contains(((Pocion) e).clavePocion) ? Presentacion.VIDA : Presentacion.MOVIMIENTO;
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
