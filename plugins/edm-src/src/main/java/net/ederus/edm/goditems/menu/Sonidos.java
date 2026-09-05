package net.ederus.edm.goditems.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;

/**
 * El catalogo de sonidos del servidor, ordenado para poder navegarlo.
 *
 * Son mas de mil quinientos y con un solo listado paginado no hay quien
 * encuentre nada, asi que se parte por los dos primeros trozos de la clave:
 *
 *   entity.warden.roar  ->  familia "entity"  ->  rama "warden"  ->  "roar"
 *
 * Se lee del registro vivo (`Registry.SOUND_EVENT`), no de una lista: en cuanto
 * el servidor sube de version, los sonidos nuevos ya estan aqui.
 */
public final class Sonidos {

    private Sonidos() { }

    private static final Map<String, Map<String, List<String>>> ARBOL = new LinkedHashMap<>();
    private static int total;

    static {
        List<String> claves = new ArrayList<>();
        try {
            Registry.SOUND_EVENT.keyStream()
                    .filter(k -> NamespacedKey.MINECRAFT.equals(k.getNamespace()))
                    .map(NamespacedKey::getKey)
                    .forEach(claves::add);
        } catch (Throwable ignored) {
            // si el registro no se puede recorrer, el catalogo queda vacio y el
            // editor cae a escribir la clave por chat, que sigue funcionando
        }
        Collections.sort(claves);
        total = claves.size();

        for (String c : claves) {
            String[] p = c.split("\\.", 3);
            String familia = p[0];
            String rama = p.length > 1 ? p[1] : "(sueltos)";
            ARBOL.computeIfAbsent(familia, k -> new LinkedHashMap<>())
                 .computeIfAbsent(rama, k -> new ArrayList<>())
                 .add(c);
        }
    }

    public static int cuantos() {
        return total;
    }

    public static List<String> familias() {
        return new ArrayList<>(ARBOL.keySet());
    }

    public static List<String> ramas(String familia) {
        Map<String, List<String>> m = ARBOL.get(familia);
        return m == null ? List.of() : new ArrayList<>(m.keySet());
    }

    public static List<String> sonidos(String familia, String rama) {
        Map<String, List<String>> m = ARBOL.get(familia);
        if (m == null) return List.of();
        List<String> l = m.get(rama);
        return l == null ? List.of() : Collections.unmodifiableList(l);
    }

    public static int cuantosEn(String familia) {
        Map<String, List<String>> m = ARBOL.get(familia);
        if (m == null) return 0;
        int n = 0;
        for (List<String> l : m.values()) n += l.size();
        return n;
    }

    /** Busca por trozo de clave. Se corta pronto: el menu no pinta mas. */
    public static List<String> buscar(String q, int tope) {
        String v = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Map<String, List<String>> ramas : ARBOL.values()) {
            for (List<String> l : ramas.values()) {
                for (String s : l) {
                    if (s.contains(v)) {
                        out.add(s);
                        if (out.size() >= tope) return out;
                    }
                }
            }
        }
        return out;
    }

    /** Un icono reconocible por familia, para que la rejilla no sea todo discos. */
    public static Material icono(String familia) {
        return switch (familia) {
            case "ambient" -> Material.GLASS_BOTTLE;
            case "block" -> Material.STONE;
            case "entity" -> Material.ZOMBIE_HEAD;
            case "item" -> Material.IRON_SWORD;
            case "music", "music_disc" -> Material.MUSIC_DISC_CAT;
            case "ui" -> Material.OAK_BUTTON;
            case "weather" -> Material.WATER_BUCKET;
            case "particle" -> Material.FIREWORK_STAR;
            case "event" -> Material.BELL;
            case "enchant" -> Material.ENCHANTING_TABLE;
            default -> Material.NOTE_BLOCK;
        };
    }
}
