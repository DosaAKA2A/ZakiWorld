package net.ederus.edm.goditems.equipo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.potion.PotionEffectType;

import net.ederus.edm.goditems.equipo.Definicion.Atributo;
import net.ederus.edm.goditems.equipo.Definicion.Clave;
import net.ederus.edm.goditems.equipo.Definicion.Config;
import net.ederus.edm.goditems.equipo.Definicion.Conjunto;
import net.ederus.edm.goditems.equipo.Definicion.Efectos;
import net.ederus.edm.goditems.equipo.Definicion.Escalon;
import net.ederus.edm.goditems.equipo.Definicion.Formato;
import net.ederus.edm.goditems.equipo.Definicion.Pieza;
import net.ederus.edm.goditems.equipo.Definicion.Pocion;

/**
 * Como se escribe lo que hace el equipo: las lineas del lore que GodItems le
 * pone a cada pieza y las cifras de /gi equipo.
 *
 * El lore sale del MISMO sitio que la cuenta (los YAML de equipo/), asi que no
 * puede prometer una cifra que no se aplique: si se cambia un 0.08 por un 0.10,
 * la linea dice 10 % en la siguiente construccion del item. Es la regla de Dosa
 * de que el lore de un item tiene que cumplirse.
 *
 * Todo es texto plano con codigos &: se lee igual en Bedrock.
 */
public final class Redaccion {

    /* Los textos de serie. Se pueden cambiar en el config.yml del modulo, en `equipo:`. */
    private static final String CABECERA_EFECTOS = "&3Efectos";
    private static final String CABECERA_SET = "<#5FB8FF>Set {nombre} ({n}/{total})";
    private static final String LINEA_POCION = "&7· &f{nombre} {nivel}";
    private static final String LINEA_ATRIBUTO = "&7· &f{valor} &7{nombre}";

    private static final Map<String, String> POCIONES = new LinkedHashMap<>();
    private static final Map<String, String> ATRIBUTOS = new LinkedHashMap<>();

    static {
        POCIONES.put("night_vision", "Visión nocturna");
        POCIONES.put("water_breathing", "Respiración acuática");
        POCIONES.put("speed", "Velocidad");
        POCIONES.put("haste", "Prisa minera");
        POCIONES.put("strength", "Fuerza");
        POCIONES.put("resistance", "Resistencia");
        POCIONES.put("fire_resistance", "Resistencia al fuego");
        POCIONES.put("regeneration", "Regeneración");
        POCIONES.put("jump_boost", "Salto");
        POCIONES.put("slow_falling", "Caída lenta");
        POCIONES.put("dolphins_grace", "Gracia de delfín");
        POCIONES.put("conduit_power", "Poder del conducto");
        POCIONES.put("luck", "Suerte");
        POCIONES.put("saturation", "Saturación");
        POCIONES.put("absorption", "Absorción");
        POCIONES.put("health_boost", "Vida extra");
        POCIONES.put("invisibility", "Invisibilidad");
        POCIONES.put("hero_of_the_village", "Héroe de la aldea");

        ATRIBUTOS.put("max_health", "vida máxima");
        ATRIBUTOS.put("movement_speed", "velocidad");
        ATRIBUTOS.put("armor", "armadura");
        ATRIBUTOS.put("armor_toughness", "dureza");
        ATRIBUTOS.put("attack_damage", "daño");
        ATRIBUTOS.put("attack_speed", "velocidad de ataque");
        ATRIBUTOS.put("knockback_resistance", "resistencia al empuje");
        ATRIBUTOS.put("luck", "suerte");
        ATRIBUTOS.put("max_absorption", "absorción máxima");
        ATRIBUTOS.put("oxygen_bonus", "aire bajo el agua");
        ATRIBUTOS.put("water_movement_efficiency", "nado");
        ATRIBUTOS.put("mining_efficiency", "velocidad de minado");
        ATRIBUTOS.put("block_break_speed", "velocidad de picar");
        ATRIBUTOS.put("safe_fall_distance", "caída sin daño");
        ATRIBUTOS.put("fall_damage_multiplier", "daño por caída");
        ATRIBUTOS.put("jump_strength", "salto");
        ATRIBUTOS.put("scale", "tamaño");
        ATRIBUTOS.put("step_height", "altura de paso");
        ATRIBUTOS.put("sneaking_speed", "velocidad agachado");
        ATRIBUTOS.put("burning_time", "tiempo ardiendo");
        ATRIBUTOS.put("entity_interaction_range", "alcance");
        ATRIBUTOS.put("block_interaction_range", "alcance de bloques");
    }

    private final String cabeceraEfectos;
    private final String cabeceraSet;
    private final String lineaPocion;
    private final String lineaAtributo;
    private final Map<String, String> pociones;
    private final Map<String, String> atributos;

    public static final Redaccion DE_SERIE = new Redaccion(null);

    /** s es la seccion `equipo:` del config.yml del modulo (puede ser null). */
    public Redaccion(ConfigurationSection s) {
        ConfigurationSection lore = s == null ? null : s.getConfigurationSection("lore");
        this.cabeceraEfectos = lore == null ? CABECERA_EFECTOS : lore.getString("cabecera-efectos", CABECERA_EFECTOS);
        this.cabeceraSet = lore == null ? CABECERA_SET : lore.getString("cabecera-set", CABECERA_SET);
        this.lineaPocion = lore == null ? LINEA_POCION : lore.getString("pocion", LINEA_POCION);
        this.lineaAtributo = lore == null ? LINEA_ATRIBUTO : lore.getString("atributo", LINEA_ATRIBUTO);
        this.pociones = new LinkedHashMap<>(POCIONES);
        this.atributos = new LinkedHashMap<>(ATRIBUTOS);
        if (s != null) {
            ConfigurationSection p = s.getConfigurationSection("nombres-pociones");
            if (p != null) for (String k : p.getKeys(false)) this.pociones.put(k.toLowerCase(Locale.ROOT), p.getString(k));
            ConfigurationSection a = s.getConfigurationSection("nombres-atributos");
            if (a != null) for (String k : a.getKeys(false)) this.atributos.put(k.toLowerCase(Locale.ROOT), a.getString(k));
        }
    }

    /* ------------------------------------------------------------- cifras */

    /** 1.5 -> "1,5"; 10.0 -> "10". Una cifra decimal como mucho, con coma. */
    public static String numero(double v) {
        double r = Math.round(v * 10) / 10.0;
        return (r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r)).replace('.', ',');
    }

    /** La cifra sin signo, en su formato: "8%", "1,5%", "2". */
    public static String cifra(Clave k, double v) {
        Formato f = k == null ? Formato.NUMERO : k.formato();
        return switch (f) {
            case PORCENTAJE -> numero(Math.abs(v) * 100) + "%";
            case PUNTOS -> numero(Math.abs(v)) + "%";
            case NUMERO -> numero(Math.abs(v));
        };
    }

    /**
     * La cifra con el signo de lo que nota el jugador. En una clave "menos"
     * (drenaje, percances) un valor positivo QUITA, y se escribe con "-".
     */
    public static String valor(Clave k, double v) {
        boolean baja = k != null && k.menos() ? v > 0 : v < 0;
        return (baja ? "-" : "+") + cifra(k, v);
    }

    /** "-8% peligro en la pesca extrema": para /gi equipo, con el nombre de la clave. */
    public static String efecto(Config c, String clave, double v) {
        Clave k = c.claves().get(clave);
        return valor(k, v) + " " + (k == null ? clave : k.nombre());
    }

    /* ---------------------------------------------------------------- lore */

    /** Una linea de lore de una clave, o null si la clave no tiene texto. */
    public static String linea(Clave k, double v) {
        if (k == null || k.lore() == null || k.lore().isBlank()) return null;
        return k.lore().replace("{valor}", valor(k, v)).replace("{cifra}", cifra(k, v));
    }

    /** La linea de lore de una clave por su id, con los colores `<#RRGGBB>` ya en `&#RRGGBB`; "" si no sale. */
    public static String lineaClave(Config c, String clave, double v) {
        String l = c == null ? null : linea(c.claves().get(clave), v);
        return l == null ? "" : colores(l);
    }

    public String linea(Pocion p) {
        String n = this.pociones.getOrDefault(p.tipo().getKey().getKey(), bonito(p.tipo().getKey().getKey()));
        return this.lineaPocion.replace("{nombre}", n).replace("{nivel}", romano(p.nivel() + 1)).trim();
    }

    public String linea(Atributo a) {
        String n = this.atributos.getOrDefault(a.clave(), bonito(a.clave()));
        String v = a.operacion() == AttributeModifier.Operation.ADD_NUMBER
                ? (a.valor() < 0 ? "-" : "+") + numero(Math.abs(a.valor()))
                : (a.valor() < 0 ? "-" : "+") + numero(Math.abs(a.valor()) * 100) + "%";
        return this.lineaAtributo.replace("{nombre}", n).replace("{valor}", v);
    }

    /** Las lineas de unos efectos, agrupadas por su grupo (grupo -> lineas), en orden. */
    private Map<String, List<String>> porGrupo(Config c, Efectos ef) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : ef.claves().entrySet()) {
            Clave k = c.claves().get(e.getKey());
            String l = linea(k, e.getValue());
            if (l == null) continue;
            out.computeIfAbsent(k.grupo(), g -> new ArrayList<>()).add(l);
        }
        for (Pocion p : ef.pociones()) out.computeIfAbsent("efectos", g -> new ArrayList<>()).add(linea(p));
        for (Atributo a : ef.atributos()) out.computeIfAbsent("efectos", g -> new ArrayList<>()).add(linea(a));
        return out;
    }

    /**
     * Todo el lore que GodItems le pone a una pieza: sus efectos por grupos y,
     * despues, un bloque por cada escalon de cada set al que pertenece.
     *
     * @param setMmo    la etiqueta de set de MMOItems del item (para los sets por `set-mmoitems`)
     * @param tamanoMmo cuantas piezas tiene un set de MMOItems (para el "(2/5)")
     */
    public List<String> lore(Config c, String id, String setMmo, java.util.function.ToIntFunction<String> tamanoMmo) {
        List<String> out = new ArrayList<>();
        if (c == null || id == null) return out;
        Pieza p = c.piezas().get(id);
        if (p != null && p.lore() && !p.efectos().vacio()) {
            for (Map.Entry<String, List<String>> g : porGrupo(c, p.efectos()).entrySet()) {
                out.add("");
                String cab = g.getKey().equals("efectos") ? c.grupos().getOrDefault("efectos", this.cabeceraEfectos)
                        : c.grupos().getOrDefault(g.getKey(), this.cabeceraEfectos);
                if (cab != null && !cab.isBlank()) out.add(cab);
                out.addAll(g.getValue());
            }
        }
        for (Conjunto s : c.sets()) {
            if (!s.lore() || !s.tiene(id, setMmo)) continue;
            int total = s.total() > 0 ? s.total()
                    : (s.setMmo() != null && tamanoMmo != null ? tamanoMmo.applyAsInt(s.setMmo()) : s.piezas().size());
            for (Escalon e : s.escalones()) {
                List<String> lineas = new ArrayList<>();
                for (List<String> l : porGrupo(c, e.efectos()).values()) lineas.addAll(l);
                if (lineas.isEmpty()) continue;
                out.add("");
                String cab = s.cabecera() == null || s.cabecera().isBlank() ? this.cabeceraSet : s.cabecera();
                out.add(cab.replace("{nombre}", s.nombre()).replace("{n}", String.valueOf(e.necesita()))
                        .replace("{total}", String.valueOf(Math.max(total, e.necesita()))));
                out.addAll(lineas);
            }
        }
        return out;
    }

    /* -------------------------------------------------------------- ayudas */

    /** Los colores `<#RRGGBB>` de MMOItems pasados al `&#RRGGBB` que entiende Estilo.legado. */
    public static String colores(String s) {
        return s == null ? "" : s.replaceAll("<#([0-9a-fA-F]{6})>", "&#$1");
    }

    static String romano(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 1 && n <= 10 ? r[n] : String.valueOf(n);
    }

    /** "water_breathing" -> "Water breathing": para lo que no tiene nombre en español. */
    private static String bonito(String k) {
        String s = k.replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public String nombrePocion(PotionEffectType t) {
        return this.pociones.getOrDefault(t.getKey().getKey(), bonito(t.getKey().getKey()));
    }

    public String nombreAtributo(String clave) {
        return this.atributos.getOrDefault(clave, bonito(clave));
    }
}
