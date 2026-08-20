package net.ederus.edm.tienda;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cuantas unidades lleva compradas cada jugador de cada articulo.
 *
 * Es un tope DE POR VIDA, no una ventana: los spawners de Ederus llevan
 * "Limite personal: 6" y eso significa seis en toda la partida, no seis al dia.
 * Por eso no caduca nunca y el fichero solo crece; a razon de ocho articulos
 * con limite, da igual.
 */
public final class Compras {

    private final Map<UUID, Map<String, Integer>> datos = new ConcurrentHashMap<>();
    private final File fichero;
    private volatile boolean sucio;

    public Compras(File fichero) { this.fichero = fichero; }

    public int llevaComprados(UUID jugador, String clave) {
        Map<String, Integer> del = datos.get(jugador);
        return del == null ? 0 : del.getOrDefault(clave, 0);
    }

    /** Lo que le queda por comprar, o MAX_VALUE si ese articulo no tiene tope. */
    public int restante(UUID jugador, Catalogo.Articulo art) {
        if (!art.tieneLimiteJugador()) return Integer.MAX_VALUE;
        return Math.max(0, art.limiteJugador() - llevaComprados(jugador, art.clave()));
    }

    /** Se llama DESPUES de cobrar y entregar. */
    public void anotar(UUID jugador, Catalogo.Articulo art, int cantidad) {
        if (!art.tieneLimiteJugador() || cantidad <= 0) return;
        datos.computeIfAbsent(jugador, k -> new ConcurrentHashMap<>())
             .merge(art.clave(), cantidad, Integer::sum);
        sucio = true;
    }

    public void cargar() {
        datos.clear();
        if (!fichero.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichero);
        for (String uuid : yml.getKeys(false)) {
            ConfigurationSection sec = yml.getConfigurationSection(uuid);
            if (sec == null) continue;
            UUID id;
            try { id = UUID.fromString(uuid); } catch (IllegalArgumentException e) { continue; }
            Map<String, Integer> del = new ConcurrentHashMap<>();
            for (String clave : sec.getKeys(false)) {
                int n = sec.getInt(clave, 0);
                if (n > 0) del.put(clave, n);
            }
            if (!del.isEmpty()) datos.put(id, del);
        }
        sucio = false;
    }

    public void guardar() {
        if (!sucio) return;
        YamlConfiguration yml = new YamlConfiguration();
        datos.forEach((id, del) -> del.forEach((clave, n) -> yml.set(id + "." + clave, n)));
        try {
            File padre = fichero.getParentFile();
            if (padre != null) padre.mkdirs();
            yml.save(fichero);
            sucio = false;
        } catch (IOException e) {
            throw new IllegalStateException("no pude guardar las compras: " + e.getMessage(), e);
        }
    }
}
