package net.ederus.edm.glow;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Quien brilla y como, en glow/jugadores.yml. Se guarda al momento de cada
 * cambio: son unas decenas de lineas y perder la eleccion de alguien en un
 * reinicio seria justo lo que se le reprochaba a FancyGlow.
 */
final class Almacen {

    /** color: una clave de Brillo o "rainbow". parpadeo: encender y apagar. */
    record Eleccion(String color, boolean parpadeo) {
        boolean arcoiris() {
            return "rainbow".equals(color);
        }

        Brillo brillo() {
            return Brillo.de(color);
        }
    }

    private final File fichero;
    private final Logger log;
    private final Map<UUID, Eleccion> datos = new ConcurrentHashMap<>();

    Almacen(File fichero, Logger log) {
        this.fichero = fichero;
        this.log = log;
        cargar();
    }

    void cargar() {
        datos.clear();
        if (!fichero.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(fichero);
        for (String k : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(k);
            if (s == null) continue;
            try {
                datos.put(UUID.fromString(k), new Eleccion(s.getString("color", "white"), s.getBoolean("parpadeo", false)));
            } catch (IllegalArgumentException e) {
                log.warning("glow/jugadores.yml: '" + k + "' no es un UUID, se salta.");
            }
        }
    }

    Eleccion de(UUID id) {
        return datos.get(id);
    }

    Map<UUID, Eleccion> todos() {
        return datos;
    }

    void poner(UUID id, Eleccion e) {
        if (e == null) datos.remove(id);
        else datos.put(id, e);
        guardar();
    }

    private synchronized void guardar() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Eleccion> e : datos.entrySet()) {
            y.set(e.getKey() + ".color", e.getValue().color());
            if (e.getValue().parpadeo()) y.set(e.getKey() + ".parpadeo", true);
        }
        try {
            y.save(fichero);
        } catch (IOException ex) {
            log.warning("No se pudo guardar glow/jugadores.yml: " + ex.getMessage());
        }
    }
}
