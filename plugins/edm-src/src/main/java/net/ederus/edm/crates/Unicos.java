package net.ederus.edm.crates;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Quien ya saco cada premio de una calidad de una sola vez.
 *
 * Un fichero, crates/unicos.yml, con una lista por jugador de "caja:premio".
 * Se escribe en el acto cada vez que alguien saca uno: son pocos y perder una
 * linea en un apagon dejaria que alguien lo sacara dos veces.
 *
 * Se anota al ELEGIR el premio, no al entregarlo: en una apertura de varias
 * llaves PhoenixCrates sortea todas antes de entregar ninguna, y anotar al
 * final dejaria que el mismo premio saliera dos veces en esa tanda.
 */
final class Unicos {

    private final File fichero;
    private final Logger log;
    private final Map<UUID, Set<String>> datos = new HashMap<>();

    Unicos(File fichero, Logger log) {
        this.fichero = fichero;
        this.log = log;
        cargar();
    }

    private static String clave(String caja, String premio) {
        return caja + ":" + premio;
    }

    synchronized void cargar() {
        this.datos.clear();
        if (!this.fichero.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(this.fichero);
        for (String k : y.getKeys(false)) {
            try {
                this.datos.put(UUID.fromString(k), new LinkedHashSet<>(y.getStringList(k)));
            } catch (IllegalArgumentException e) {
                this.log.warning("[crates] unicos.yml: '" + k + "' no es un UUID; se ignora.");
            }
        }
    }

    synchronized boolean tiene(UUID jugador, String caja, String premio) {
        Set<String> s = this.datos.get(jugador);
        return s != null && s.contains(clave(caja, premio));
    }

    synchronized void anotar(UUID jugador, String caja, String premio) {
        if (this.datos.computeIfAbsent(jugador, u -> new LinkedHashSet<>()).add(clave(caja, premio))) {
            guardar();
        }
    }

    synchronized boolean quitar(UUID jugador, String caja, String premio) {
        Set<String> s = this.datos.get(jugador);
        if (s == null || !s.remove(clave(caja, premio))) return false;
        if (s.isEmpty()) this.datos.remove(jugador);
        guardar();
        return true;
    }

    synchronized List<String> de(UUID jugador) {
        Set<String> s = this.datos.get(jugador);
        return s == null ? List.of() : new ArrayList<>(s);
    }

    private void guardar() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "Premios de calidad 'una-vez' que ya saco cada jugador (caja:premio).",
                "Lo escribe EDM. Para devolverle a alguien la posibilidad: /edm crates uniques <jugador> remove <caja> <premio>"));
        for (Map.Entry<UUID, Set<String>> e : this.datos.entrySet()) {
            y.set(e.getKey().toString(), new ArrayList<>(e.getValue()));
        }
        try {
            this.fichero.getParentFile().mkdirs();
            y.save(this.fichero);
        } catch (IOException e) {
            this.log.severe("[crates] No se pudo guardar unicos.yml: " + e.getMessage()
                    + ". Lo sacado desde el arranque sigue en memoria hasta reiniciar.");
        }
    }
}
