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
     * Al reves: de lo que escribe una persona a los materiales que pueden ser.
     *
     * Lo usa el "anadir articulo" del editor. Quien quiere meter la cubeta de
     * agua escribe "cubeta de agua", no WATER_BUCKET, asi que se busca sobre el
     * nombre en espanol ADEMAS de sobre el identificador. Se comparan los dos
     * sin acentos y sin guiones bajos: "lapislazuli" tiene que encontrar el
     * Lapislazuli igual que "lapis lazuli" encuentra LAPIS_LAZULI.
     *
     * El orden importa mas que la lista: primero lo que es exactamente eso,
     * despues lo que empieza por eso y al final lo que solo lo contiene. Sin
     * esto, "cubeta" enseñaba veinte cubetas antes que la de agua.
     */
    public static java.util.List<Material> buscar(String texto, int tope) {
        String t = plano(texto);
        if (t.isEmpty()) return java.util.List.of();
        java.util.List<Material> exactos = new java.util.ArrayList<>();
        java.util.List<Material> empiezan = new java.util.ArrayList<>();
        java.util.List<Material> contienen = new java.util.ArrayList<>();
        for (Material m : Material.values()) {
            /* Solo lo que puede estar en un cofre: los bloques que no son item
             * (el fuego, el agua colocada) no se pueden ni comprar ni vender. */
            if (m.isLegacy() || m.isAir() || !m.isItem()) continue;
            String id = plano(m.name());
            String nombre = plano(de(m));
            if (id.equals(t) || nombre.equals(t)) exactos.add(m);
            else if (id.startsWith(t) || nombre.startsWith(t)) empiezan.add(m);
            else if (id.contains(t) || nombre.contains(t)) contienen.add(m);
        }
        java.util.List<Material> out = new java.util.ArrayList<>(exactos);
        out.addAll(empiezan);
        out.addAll(contienen);
        return out.size() > tope ? out.subList(0, tope) : out;
    }

    /** Minusculas, sin acentos y sin guiones bajos, para comparar a ciegas. */
    private static String plano(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace('_', ' ');
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
