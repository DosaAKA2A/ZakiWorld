package net.ederus.edm.flex;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import net.ederus.edm.comun.Poder;

/**
 * La memoria del Poder: cuanto tiene cada jugador ahora y cuanto es lo mas que
 * se le ha visto.
 *
 * El Poder se calcula sobre el jugador VIVO (armadura, dureza, vida y daño salen
 * de la entidad), asi que sin esto no habria forma de ordenar a los que no estan
 * conectados. Se anota al entrar, al salir, cada minuto y cada vez que alguien
 * abre su ficha.
 *
 * El top se ordena por el MEJOR visto y no por el actual, a proposito: el actual
 * cambia cada vez que alguien se pone el pico para minar, y un top que parpadea
 * no es un top. Se reinicia por temporada con /flex top reset.
 */
public final class RegistroPoder {

    /** Lo que se sabe de uno: su nombre, lo mas que se le ha visto y lo ultimo. */
    public record Marca(UUID uuid, String nombre, long mejor, long actual, long cuando) { }

    private final FlexPlugin plugin;
    private final File fichero;
    private final Map<UUID, Marca> marcas = new ConcurrentHashMap<>();
    private volatile List<Marca> top = List.of();
    private volatile boolean sucio;

    public RegistroPoder(FlexPlugin plugin) {
        this.plugin = plugin;
        this.fichero = new File(plugin.getDataFolder(), "poder.yml");
    }

    public void cargar() {
        marcas.clear();
        if (!fichero.exists()) {
            reordenar();
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichero);
        ConfigurationSection s = yml.getConfigurationSection("jugadores");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(k);
                    ConfigurationSection j = s.getConfigurationSection(k);
                    if (j == null) continue;
                    marcas.put(uuid, new Marca(uuid, j.getString("nombre", "?"),
                            j.getLong("mejor", 0), j.getLong("actual", 0), j.getLong("cuando", 0)));
                } catch (IllegalArgumentException ignored) {
                    // Una clave que no es un UUID: se salta y no tumba el resto.
                }
            }
        }
        reordenar();
    }

    public synchronized void guardar() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "El Poder de cada jugador: lo mas que se le ha visto (mejor) y lo ultimo (actual).",
                "Lo escribe EDM solo; para reiniciar la temporada, /flex top reset."));
        for (Marca m : marcas.values()) {
            String base = "jugadores." + m.uuid() + ".";
            yml.set(base + "nombre", m.nombre());
            yml.set(base + "mejor", m.mejor());
            yml.set(base + "actual", m.actual());
            yml.set(base + "cuando", m.cuando());
        }
        try {
            yml.save(fichero);
            sucio = false;
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar poder.yml: " + e.getMessage());
        }
    }

    public void guardarSiHaceFalta() {
        if (sucio) guardar();
    }

    /** Mide al jugador ahora mismo y lo apunta. Devuelve su Poder actual. */
    public long anotar(Player p) {
        long actual = Math.round(Poder.calcular(plugin, p).total());
        Marca antes = marcas.get(p.getUniqueId());
        long mejor = antes == null ? actual : Math.max(antes.mejor(), actual);
        marcas.put(p.getUniqueId(), new Marca(p.getUniqueId(), p.getName(), mejor, actual,
                System.currentTimeMillis()));
        sucio = true;
        reordenar();
        return actual;
    }

    public Marca de(UUID uuid) {
        return marcas.get(uuid);
    }

    /** Los n primeros por su mejor Poder. */
    public List<Marca> top(int n) {
        List<Marca> t = top;
        return t.size() <= n ? t : t.subList(0, n);
    }

    /** El puesto de uno en el top, desde 1; 0 si no esta apuntado. */
    public int posicion(UUID uuid) {
        List<Marca> t = top;
        for (int i = 0; i < t.size(); i++) {
            if (t.get(i).uuid().equals(uuid)) return i + 1;
        }
        return 0;
    }

    public int cuantos() {
        return marcas.size();
    }

    /** Borra la temporada entera. Los conectados se vuelven a apuntar al minuto. */
    public void reiniciar() {
        marcas.clear();
        sucio = true;
        reordenar();
        guardar();
    }

    private void reordenar() {
        List<Marca> lista = new ArrayList<>(marcas.values());
        lista.sort(Comparator.comparingLong(Marca::mejor).reversed()
                .thenComparing(Marca::nombre, String.CASE_INSENSITIVE_ORDER));
        top = List.copyOf(lista);
    }
}
