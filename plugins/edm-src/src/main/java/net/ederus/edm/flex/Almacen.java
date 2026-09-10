package net.ederus.edm.flex;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * Donde viven las vitrinas.
 *
 * Hoy es un yml y se lee entero al arrancar, porque una vitrina son 27 objetos
 * por jugador y eso cabe de sobra en memoria. Manana puede ser MariaDB: por eso
 * todo lo que toca disco esta detras de cargar()/guardar(una) y nadie mas fuera
 * de esta clase sabe si hay un fichero o una tabla debajo.
 *
 * Se escribe VITRINA A VITRINA y no el fichero entero en cada cambio, para que
 * el dia que esto sea un UPDATE de SQL la forma de llamarlo ya sea la correcta.
 */
public final class Almacen {

    private final FlexPlugin plugin;
    private final Map<UUID, Vitrina> vitrinas = new HashMap<>();
    /** nombre en minusculas -> uuid, para responder a /flex <jugador> sin conectarse a Mojang. */
    private final Map<String, UUID> porNombre = new HashMap<>();

    private File fichero;
    private boolean sucio;

    public Almacen(FlexPlugin plugin) {
        this.plugin = plugin;
    }

    /* --------------------------------------------------------------- consultas */

    public Vitrina de(UUID uuid, String nombre) {
        Vitrina v = vitrinas.computeIfAbsent(uuid, u -> new Vitrina(u, nombre));
        v.nombre(nombre);
        porNombre.put(v.nombre().toLowerCase(Locale.ROOT), uuid);
        return v;
    }

    /** La vitrina de alguien por su nombre, este conectado o no. null si no tiene. */
    public Vitrina porNombre(String nombre) {
        UUID uuid = porNombre.get(nombre.toLowerCase(Locale.ROOT));
        return uuid == null ? null : vitrinas.get(uuid);
    }

    public Vitrina existente(UUID uuid) {
        return vitrinas.get(uuid);
    }

    public List<Vitrina> todas() {
        return new ArrayList<>(vitrinas.values());
    }

    public int cuantas() {
        return vitrinas.size();
    }

    /* ------------------------------------------------------------------ disco */

    public void cargar() {
        vitrinas.clear();
        porNombre.clear();
        fichero = new File(plugin.getDataFolder(), "vitrinas.yml");
        if (!fichero.exists()) return;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichero);
        ConfigurationSection root = yml.getConfigurationSection("vitrinas");
        if (root == null) return;

        for (String clave : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(clave);
            if (s == null) continue;
            UUID uuid;
            try {
                uuid = UUID.fromString(clave);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Vitrina con un uuid ilegible: " + clave);
                continue;
            }
            Vitrina v = new Vitrina(uuid, s.getString("nombre", "?"));
            v.actualizada(s.getLong("actualizada", 0));
            ConfigurationSection objetos = s.getConfigurationSection("objetos");
            if (objetos != null) {
                for (String i : objetos.getKeys(false)) {
                    ItemStack item = objetos.getItemStack(i);
                    if (item == null) continue;
                    try {
                        v.poner(Integer.parseInt(i), item);
                    } catch (NumberFormatException ignored) {
                        // casilla ilegible: se pierde ese objeto y no la vitrina entera
                    }
                }
            }
            v.actualizada(s.getLong("actualizada", 0));
            vitrinas.put(uuid, v);
            porNombre.put(v.nombre().toLowerCase(Locale.ROOT), uuid);
        }
        plugin.getLogger().info("Vitrinas cargadas: " + vitrinas.size() + ".");
    }

    /**
     * Marca que hay cambios. El volcado va a disco cada pocos segundos y al
     * apagar, no en cada clic: una vitrina se edita a rafagas de veinte clics y
     * escribir el fichero veinte veces seguidas no aporta nada.
     */
    public void guardar(Vitrina v) {
        sucio = true;
    }

    public void volcarSiHaceFalta() {
        if (!sucio) return;
        volcar();
    }

    public void volcar() {
        sucio = false;
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "Las vitrinas de /flex.",
                "",
                "Cada objeto es una COPIA de lo que su dueño puso a la vista: de aqui no",
                "sale nunca nada, ni al dueño. Borrar una vitrina a mano no le quita nada",
                "a nadie, solo deja de enseñarse."));
        for (Vitrina v : vitrinas.values()) {
            String base = "vitrinas." + v.uuid();
            yml.set(base + ".nombre", v.nombre());
            yml.set(base + ".actualizada", v.actualizada());
            for (int i = 0; i < Vitrina.CASILLAS; i++) {
                ItemStack item = v.objeto(i);
                if (item != null) yml.set(base + ".objetos." + i, item);
            }
        }
        try {
            yml.save(fichero);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "No se pudo guardar vitrinas.yml", e);
        }
    }
}
