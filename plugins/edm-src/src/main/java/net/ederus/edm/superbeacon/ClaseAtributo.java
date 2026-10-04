package net.ederus.edm.superbeacon;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;

import net.ederus.edm.comun.Compat;

/**
 * atributo: un modificador de atributo (suma) mientras se esta en el alcance.
 *
 * Cada atributo lleva SIEMPRE la misma clave, superbeacon:<atributo>. Eso hace tres cosas:
 *   - dos balizas de +4 de vida no dan +8: la segunda pisa la primera con la misma clave,
 *     y el motor ya entrega solo la mayor;
 *   - quitarlo es quitar esa clave, sin llevar la cuenta de UUIDs;
 *   - cualquier resto se reconoce por el namespace "superbeacon" y se barre al entrar, al
 *     arrancar y al parar (lo mismo que hace Lethal World con los "bracken:").
 *
 * Se ponen como TRANSITORIOS: no se guardan con el jugador, asi que un apagon no deja a
 * nadie con +6 corazones para siempre. El barrido al entrar queda como red por si acaso.
 *
 * Si baja la vida maxima (sale del alcance de un +4 corazones) se recorta la vida actual:
 * si no, el jugador se quedaria con mas vida que su maximo hasta que le peguen.
 */
final class ClaseAtributo extends ClaseEfecto {

    static final String NS = "superbeacon";

    static final class Mod extends Efecto {
        private final ClaseAtributo clase;
        final String atributo;
        /** null si el atributo no existe en esta version: el efecto sale como no disponible. */
        final Attribute attr;
        final double valor;

        Mod(ClaseAtributo clase, String clave, String nombre, Material icono, String atributo, Attribute attr,
            double valor) {
            super(clave, nombre, icono);
            this.clase = clase;
            this.atributo = atributo;
            this.attr = attr;
            this.valor = valor;
        }

        @Override
        ClaseEfecto clase() {
            return clase;
        }

        @Override
        String grupo() {
            return "atributo:" + atributo;
        }

        @Override
        double fuerza() {
            return valor;
        }
    }

    /** Lo que le puse a cada uno: atributo -> valor. Para no tocar nada si no cambia. */
    private final Map<UUID, Map<String, Double>> puestos = new HashMap<>();

    ClaseAtributo(SuperBeaconPlugin plugin) {
        super(plugin, "atributo");
    }

    @Override
    Efecto leer(String clave, String nombre, Material icono, ConfigurationSection s, Consumer<String> error) {
        String atributo = normalizar(s.getString("atributo", ""));
        if (atributo.isEmpty()) {
            error.accept("atributo: falta el atributo (max_health, armor, luck...); se salta");
            return null;
        }
        double valor = s.getDouble("valor", 0);
        if (valor == 0 || Double.isNaN(valor) || Double.isInfinite(valor)) {
            error.accept("valor: tiene que ser un numero distinto de 0; se salta");
            return null;
        }
        Attribute attr = atributo.matches("[a-z0-9._/-]+") ? Compat.attribute(atributo) : null;
        if (attr == null) {
            error.accept("atributo: '" + atributo + "' no existe en esta version; sale como no disponible");
        }
        return new Mod(this, clave, nombre, icono, atributo, attr, valor);
    }

    /** max_health, MAX_HEALTH, minecraft:max_health y generic.max_health son lo mismo. */
    static String normalizar(String texto) {
        if (texto == null) return "";
        String k = texto.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (k.startsWith("minecraft:")) k = k.substring("minecraft:".length());
        if (k.startsWith("generic.")) k = k.substring("generic.".length());
        if (k.startsWith("player.")) k = k.substring("player.".length());
        if (k.startsWith("generic_")) k = k.substring("generic_".length());
        if (k.startsWith("player_")) k = k.substring("player_".length());
        return k;
    }

    @Override
    Material icono() {
        return Material.GOLDEN_APPLE;
    }

    @Override
    boolean porJugador() {
        return true;
    }

    @Override
    String falta(Efecto e) {
        return ((Mod) e).attr == null ? plugin.textos().crudo("falta-atributo", "atributo desconocido") : null;
    }

    /** La frase de cada atributo conocido. Respaldo de detalle-atributo-<atributo>. */
    static final Map<String, String> QUE = Map.of(
            "max_health", "Tienes %corazones% %mas% de vida máxima mientras estés en su alcance.",
            "movement_speed", "Te mueves un %porcentaje% %mas% rápido mientras estés en su alcance.",
            "attack_damage", "Tus golpes quitan %corazones% %mas% mientras estés en su alcance.",
            "block_interaction_range", "Llegas %valor% bloques %mas% lejos al picar y construir en su alcance.",
            "entity_interaction_range", "Llegas %valor% bloques %mas% lejos al golpear en su alcance.",
            "safe_fall_distance", "Caes %valor% bloques %mas% sin hacerte daño en su alcance.");

    /** Velocidad base de un jugador: +0,02 es un 20 % mas. */
    static final double VELOCIDAD_BASE = 0.1;

    private static final java.util.Set<String> DE_VIDA = java.util.Set.of("max_health", "armor", "armor_toughness",
            "knockback_resistance", "safe_fall_distance", "oxygen_bonus");

    @Override
    String que(Efecto e) {
        Mod m = (Mod) e;
        String nombre = plugin.textos().crudo("atributo-" + m.atributo, m.atributo.replace('_', ' '));
        String mas = m.valor > 0 ? plugin.textos().crudo("palabra-mas", "más")
                : plugin.textos().crudo("palabra-menos", "menos");
        double abs = Math.abs(m.valor);
        String plantilla = plugin.textos().crudo("detalle-atributo-" + m.atributo, QUE.get(m.atributo));
        if (plantilla == null || plantilla.isBlank()) {
            plantilla = plugin.textos().crudo("detalle-atributo",
                    "Tienes %signo%%valor% de %atributo% mientras estés en su alcance.");
        }
        return plantilla.replace("%signo%", m.valor > 0 ? "+" : "-")
                .replace("%valor%", Numeros.decimal(abs))
                .replace("%mas%", mas)
                .replace("%corazones%", Presentacion.corazones(abs))
                .replace("%porcentaje%", Numeros.decimal(abs / VELOCIDAD_BASE * 100.0) + " %")
                .replace("%atributo%", nombre);
    }

    @Override
    int seccion(Efecto e) {
        return DE_VIDA.contains(((Mod) e).atributo) ? Presentacion.VIDA : Presentacion.MOVIMIENTO;
    }

    NamespacedKey clave(String atributo) {
        return new NamespacedKey(plugin, atributo);
    }

    @Override
    void aplicar(Player p, Efecto e) {
        Mod m = (Mod) e;
        if (m.attr == null) return;
        AttributeInstance inst = p.getAttribute(m.attr);
        if (inst == null) return;
        NamespacedKey k = clave(m.atributo);
        Map<String, Double> suyos = puestos.computeIfAbsent(p.getUniqueId(), x -> new HashMap<>());
        Double antes = suyos.get(m.atributo);
        // Mismo valor y el modificador sigue ahi: nada que hacer. Si no esta (reaparecio,
        // otro plugin limpio los atributos), se vuelve a poner aunque el valor no cambie.
        if (antes != null && antes == m.valor && inst.getModifier(k) != null) return;
        inst.removeModifier(k);
        inst.addTransientModifier(new AttributeModifier(k, m.valor, AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.ANY));
        suyos.put(m.atributo, m.valor);
        recortarVida(p);
    }

    @Override
    void quitar(Player p, Efecto e) {
        Mod m = (Mod) e;
        Map<String, Double> suyos = puestos.get(p.getUniqueId());
        if (suyos != null) {
            suyos.remove(m.atributo);
            if (suyos.isEmpty()) puestos.remove(p.getUniqueId());
        }
        if (m.attr == null) return;
        AttributeInstance inst = p.getAttribute(m.attr);
        if (inst == null) return;
        inst.removeModifier(clave(m.atributo));
        recortarVida(p);
    }

    @Override
    void alSalir(Player p) {
        barrer(p);
        puestos.remove(p.getUniqueId());
    }

    /** Al reaparecer se pierde el rastro de lo puesto: el siguiente ciclo lo vuelve a poner. */
    void olvidar(UUID jugador) {
        puestos.remove(jugador);
    }

    @Override
    void parar() {
        for (Player p : Bukkit.getOnlinePlayers()) barrer(p);
        puestos.clear();
    }

    /**
     * Quita TODO modificador superbeacon: de ese jugador, en cualquier atributo. Recorre el
     * registro entero porque un resto puede estar en un atributo que ya no esta en ningun
     * tipo del config.
     */
    int barrer(Player p) {
        int quitados = 0;
        for (Attribute a : Registry.ATTRIBUTE) {
            AttributeInstance inst = p.getAttribute(a);
            if (inst == null) continue;
            for (AttributeModifier m : List.copyOf(inst.getModifiers())) {
                if (m.getKey() == null || !NS.equals(m.getKey().getNamespace())) continue;
                inst.removeModifier(m);
                quitados++;
            }
        }
        if (quitados > 0) recortarVida(p);
        return quitados;
    }

    /** Si la vida maxima bajo por debajo de la actual, la actual baja con ella. */
    static void recortarVida(Player p) {
        Attribute vida = Compat.attribute("max_health");
        if (vida == null) return;
        AttributeInstance inst = p.getAttribute(vida);
        if (inst == null) return;
        double max = inst.getValue();
        if (!p.isDead() && p.getHealth() > max) p.setHealth(Math.max(0.5, max));
    }
}
