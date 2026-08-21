package net.ederus.edm.tienda;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Como se llaman los objetos para el jugador.
 *
 * Bukkit solo sabe el nombre del material (IRON_INGOT), y ponerlo bonito da
 * "Iron ingot": la tienda entera se veia en ingles aunque todos sus textos
 * estuvieran en espanol. La tabla sale del fichero de idioma es_mx.json del
 * propio Minecraft, asi que el jugador lee exactamente el mismo nombre que ve
 * en su inventario.
 *
 * ES ESTATICA A PROPOSITO. Los nombres se piden desde metodos estaticos del
 * Motor que se llaman desde media docena de sitios (menu, pantalla de cantidad,
 * mensajes de chat, buscador, comandos), y pasar una instancia por todos ellos
 * solo para leer un texto no compensa. Hay una tienda por servidor.
 *
 * Si falta el fichero o falta una linea, se cae al nombre de siempre: nunca
 * deja un hueco en blanco.
 */
public final class Nombres {

    private static final Map<String, String> MATERIALES = new HashMap<>();
    private static final Map<String, String> MOBS = new HashMap<>();

    private Nombres() { }

    /** Cuantos nombres hay cargados, para el log de arranque. */
    public static int cuantos() { return MATERIALES.size(); }

    public static void cargar(File fichero) {
        MATERIALES.clear();
        MOBS.clear();
        if (fichero == null || !fichero.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichero);
        leer(yml.getConfigurationSection("materiales"), MATERIALES);
        leer(yml.getConfigurationSection("mobs"), MOBS);
    }

    private static void leer(ConfigurationSection sec, Map<String, String> destino) {
        if (sec == null) return;
        for (String clave : sec.getKeys(false)) {
            String valor = sec.getString(clave);
            if (valor != null && !valor.isBlank()) {
                destino.put(clave.toUpperCase(Locale.ROOT), valor);
            }
        }
    }

    /** El nombre del material, o el de siempre si no esta en la tabla. */
    public static String de(Material material) {
        if (material == null) return "";
        String n = MATERIALES.get(material.name());
        return n != null ? n : porDefecto(material.name());
    }

    /** El nombre del bicho de un spawner. */
    public static String deMob(EntityType tipo) {
        if (tipo == null) return "";
        String n = MOBS.get(tipo.name());
        return n != null ? n : porDefecto(tipo.name());
    }

    /**
     * El respaldo de toda la vida: IRON_INGOT -> "Iron ingot". Sale en ingles,
     * pero es mejor que un hueco, y solo aparece si el material no esta en la
     * tabla (un plugin que anada materiales suyos, o una version mas nueva).
     */
    public static String porDefecto(String crudo) {
        if (crudo == null || crudo.isEmpty()) return "";
        String s = crudo.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
