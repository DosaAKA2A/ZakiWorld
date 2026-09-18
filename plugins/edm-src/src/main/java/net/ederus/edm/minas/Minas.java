package net.ederus.edm.minas;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Todas las minas y el fichero donde viven, plugins/EDM/minas/minas.yml.
 *
 * Es un fichero pensado para leerse: cada mina con su nombre, su caja, su mezcla
 * en partes y sus ajustes. Se puede editar a mano y recargar con /mina reload,
 * pero la idea es no tener que hacerlo: todo sale del menu de /mine.
 */
public final class Minas {

    private final MinasPlugin plugin;
    private final File fichero;
    private final Map<String, Mina> minas = new LinkedHashMap<>();

    public Minas(MinasPlugin plugin) {
        this.plugin = plugin;
        this.fichero = new File(plugin.getDataFolder(), "minas.yml");
    }

    public void cargar() {
        minas.clear();
        if (!fichero.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichero);
        ConfigurationSection s = yml.getConfigurationSection("minas");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection m = s.getConfigurationSection(id);
            if (m == null) continue;
            minas.put(id.toLowerCase(Locale.ROOT), Mina.leerDe(id.toLowerCase(Locale.ROOT), m));
        }
    }

    public void guardar() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "Las minas de prision. Cada una: su nombre, su caja (zona), la mezcla de bloques",
                "en PARTES (el porcentaje es partes / total), el intervalo en segundos (0 = sin",
                "temporizador), el umbral de picado que adelanta el reinicio (0 = apagado), el",
                "permiso de entrada (sin clave = todos) y el punto de salida.",
                "Lo normal es editarlo desde /mine; si se toca a mano, /mine reload."));
        for (Mina m : minas.values()) {
            m.guardarEn(yml.createSection("minas." + m.id()));
        }
        try {
            yml.save(fichero);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar minas.yml: " + e.getMessage());
        }
    }

    public Mina de(String id) {
        return id == null ? null : minas.get(id.toLowerCase(Locale.ROOT));
    }

    public List<Mina> todas() {
        return new ArrayList<>(minas.values());
    }

    public int cuantas() {
        return minas.size();
    }

    /** El identificador que sale de un nombre escrito: minusculas, sin espacios ni rarezas. */
    public static String idDe(String nombre) {
        String id = nombre.trim().toLowerCase(Locale.ROOT)
                .replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u')
                .replace('ñ', 'n').replaceAll("[\\s]+", "_").replaceAll("[^a-z0-9_-]", "");
        return id.isEmpty() ? "mina" : id;
    }

    public Mina crear(String nombre) {
        String base = idDe(nombre);
        String id = base;
        int n = 2;
        while (minas.containsKey(id)) id = base + n++;
        Mina m = new Mina(id, nombre.trim());
        minas.put(id, m);
        return m;
    }

    public boolean borrar(String id) {
        return minas.remove(id.toLowerCase(Locale.ROOT)) != null;
    }

    /** La mina que contiene ese bloque, o null. */
    public Mina en(String mundo, int x, int y, int z) {
        for (Mina m : minas.values()) {
            if (m.contiene(mundo, x, y, z)) return m;
        }
        return null;
    }

    public Mina en(Location l) {
        if (l == null || l.getWorld() == null) return null;
        return en(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }
}
