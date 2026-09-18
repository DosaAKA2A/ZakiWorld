package net.ederus.edm.comun;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * El PODER de un jugador: un solo numero que dice lo fuerte que es ahora mismo.
 *
 * Hasta hoy el nivel de los mobs de Lethal World salia solo del rango de rankup y del
 * poder de AuraSkills, o sea del TIEMPO jugado. Eso deja fuera lo que de verdad decide
 * una pelea: el equipo que llevas puesto. Un jugador de rango 3 con el Manto de
 * Calamidad aplasta a uno de rango 9 con hierro, y los mobs no se enteraban.
 *
 * Aqui se suma todo: rango, habilidades y lo que llevas encima. Y se lee del jugador
 * por ATRIBUTOS de vanilla (armadura, dureza, vida, dano), no por la API de MMOItems:
 * asi cuenta igual una pieza de MMOItems, una vanilla encantada o lo que venga manana,
 * porque todas acaban moviendo los mismos atributos.
 *
 * Los pesos estan en la config para poder afinarlo sin recompilar.
 */
public final class Poder {

    /** El desglose, para poder ENSENAR de donde sale cada punto y no solo el total. */
    public record Desglose(int rango, double auraskills, double armadura, double dureza,
                           double vida, double dano, double total) {

        public String linea(String etiqueta, double valor, double peso) {
            return String.format(Locale.US, "%s %.1f x%.1f = %.0f", etiqueta, valor, peso, valor * peso);
        }
    }

    private Poder() {
    }

    /**
     * Rango de rankup por PlaceholderAPI. 0 si no se puede leer.
     *
     * Vive aqui y no en el modulo que lo use: el Poder es del JUGADOR, no de un
     * mundo ni de una vitrina, y cualquier modulo tiene que poder preguntarlo sin
     * depender de que otro este cargado.
     */
    public static int rango(org.bukkit.plugin.Plugin plugin, Player p) {
        try {
            String marcador = cfg(plugin).getString("placeholder-rango", "%notranks_rank_number%");
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Object r = papi.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class)
                    .invoke(null, p, marcador);
            return Integer.parseInt(String.valueOf(r).replaceAll("[^0-9]", ""));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Poder de AuraSkills (suma de sus habilidades) por su API. 0 si no esta. */
    public static int auraskills(Player p) {
        try {
            Class<?> api = Class.forName("dev.aurelium.auraskills.api.AuraSkillsApi");
            Object inst = api.getMethod("get").invoke(null);
            Object user = inst.getClass().getMethod("getUser", java.util.UUID.class)
                    .invoke(inst, p.getUniqueId());
            if (user == null) return 0;
            return (int) user.getClass().getMethod("getPowerLevel").invoke(user);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** El poder de un jugador leyendo el rango y AuraSkills por su cuenta. */
    public static Desglose calcular(org.bukkit.plugin.Plugin plugin, Player p) {
        return calcular(plugin, p, rango(plugin, p), auraskills(p));
    }

    /** El peso de una parte del Poder, el mismo que usa calcular(). */
    public static double peso(org.bukkit.plugin.Plugin plugin, String clave, double def) {
        return cfg(plugin).getDouble(clave, def);
    }

    private static ConfigurationSection cfg(org.bukkit.plugin.Plugin plugin) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("poder");
        return s == null ? new YamlConfiguration() : s;
    }

    /**
     * Calcula el poder de un jugador.
     *
     * De la vida y del dano se cuenta solo lo que pasa de lo normal (20 de vida, 1 de
     * dano a mano vacia): si no, todo el mundo empezaria con puntos de regalo y el
     * numero no distinguiria a nadie.
     */
    public static Desglose calcular(org.bukkit.plugin.Plugin plugin, Player p,
                                    int rango, double auraskills) {
        ConfigurationSection c = cfg(plugin);

        double armadura = Compat.getAttribute(p, "armor", 0);
        double dureza = Compat.getAttribute(p, "armor_toughness", 0);
        double vida = Math.max(0, Compat.getAttribute(p, "max_health", 20) - 20);
        double dano = Math.max(0, Compat.getAttribute(p, "attack_damage", 1) - 1);

        /* Los mismos numeros que el config de serie, A PROPOSITO: en un servidor que
         * ya tenia flex/config.yml (el Survival) el bloque poder: no se escribe solo,
         * y con los pesos viejos aqui la cifra saldria veinte veces mas baja sin que
         * nadie entendiera por que. */
        double total = rango * c.getDouble("por-rango", 200)
                + auraskills * c.getDouble("por-auraskills", 20)
                + armadura * c.getDouble("por-armadura", 80)
                + dureza * c.getDouble("por-dureza", 120)
                + vida * c.getDouble("por-vida", 40)
                + dano * c.getDouble("por-dano", 60);

        return new Desglose(rango, auraskills, armadura, dureza, vida, dano, total);
    }

}
