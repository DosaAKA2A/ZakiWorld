package com.ederus.tienda;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cuanto puede vender cada jugador de cada material antes de que se le corte.
 *
 * La ventana es RODANTE y por item: arranca en la primera venta de ese material
 * y, cuando pasa su duracion, el contador vuelve a cero. No es por reloj — eso
 * haria que todo el servidor vendiera a la misma hora. Es la misma semantica que
 * traia EconomyShopGUI Premium, para que a los jugadores no les cambie nada.
 */
public final class Topes {

    private static final class Ventana {
        long inicio;
        int vendido;
        Ventana(long inicio, int vendido) { this.inicio = inicio; this.vendido = vendido; }
    }

    private final Map<UUID, Map<Material, Ventana>> datos = new ConcurrentHashMap<>();
    private final File fichero;
    private volatile boolean sucio;

    public Topes(File fichero) { this.fichero = fichero; }

    /** Cuanto le queda por vender. Integer.MAX_VALUE si ese articulo no lleva tope. */
    public int restante(UUID jugador, Catalogo.Articulo articulo) {
        if (!articulo.tieneTope()) return Integer.MAX_VALUE;
        Ventana v = ventanaViva(jugador, articulo);
        return v == null ? articulo.topeVenta() : Math.max(0, articulo.topeVenta() - v.vendido);
    }

    /** Milisegundos hasta que se reinicie el contador, o 0 si no hay ventana abierta. */
    public long esperaMs(UUID jugador, Catalogo.Articulo articulo) {
        Ventana v = ventanaViva(jugador, articulo);
        if (v == null) return 0;
        long fin = v.inicio + articulo.ventanaMs();
        return Math.max(0, fin - System.currentTimeMillis());
    }

    private Ventana ventanaViva(UUID jugador, Catalogo.Articulo articulo) {
        Map<Material, Ventana> del = datos.get(jugador);
        if (del == null) return null;
        Ventana v = del.get(articulo.material());
        if (v == null) return null;
        if (System.currentTimeMillis() - v.inicio >= articulo.ventanaMs()) {
            del.remove(articulo.material());   // caducada: se limpia sola
            sucio = true;
            return null;
        }
        return v;
    }

    /** Apunta una venta ya cobrada. Solo se llama DESPUES de pagar. */
    public void anotar(UUID jugador, Catalogo.Articulo articulo, int cantidad) {
        if (!articulo.tieneTope() || cantidad <= 0) return;
        Map<Material, Ventana> del = datos.computeIfAbsent(jugador, k -> new ConcurrentHashMap<>());
        Ventana v = ventanaViva(jugador, articulo);
        if (v == null) del.put(articulo.material(), new Ventana(System.currentTimeMillis(), cantidad));
        else v.vendido += cantidad;
        sucio = true;
    }

    /** Quita del mapa las ventanas ya caducadas, para que el fichero no crezca sin fin. */
    public int limpiar(Catalogo catalogo) {
        long ahora = System.currentTimeMillis();
        int fuera = 0;
        for (Iterator<Map.Entry<UUID, Map<Material, Ventana>>> it = datos.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Map<Material, Ventana>> e = it.next();
            e.getValue().entrySet().removeIf(x -> {
                Catalogo.Articulo a = catalogo.de(x.getKey());
                return a == null || ahora - x.getValue().inicio >= a.ventanaMs();
            });
            if (e.getValue().isEmpty()) { it.remove(); fuera++; }
        }
        if (fuera > 0) sucio = true;
        return fuera;
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
            Map<Material, Ventana> del = new ConcurrentHashMap<>();
            for (String mat : sec.getKeys(false)) {
                Material material = Material.matchMaterial(mat);
                if (material == null) continue;
                long inicio = sec.getLong(mat + ".inicio", 0);
                int vendido = sec.getInt(mat + ".vendido", 0);
                if (inicio > 0 && vendido > 0) del.put(material, new Ventana(inicio, vendido));
            }
            if (!del.isEmpty()) datos.put(id, del);
        }
        sucio = false;
    }

    public void guardar() {
        if (!sucio) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Map<Material, Ventana>> e : datos.entrySet()) {
            for (Map.Entry<Material, Ventana> x : e.getValue().entrySet()) {
                String base = e.getKey() + "." + x.getKey().name();
                yml.set(base + ".inicio", x.getValue().inicio);
                yml.set(base + ".vendido", x.getValue().vendido);
            }
        }
        try {
            File padre = fichero.getParentFile();
            if (padre != null) padre.mkdirs();
            yml.save(fichero);
            sucio = false;
        } catch (IOException e) {
            throw new IllegalStateException("no pude guardar los topes: " + e.getMessage(), e);
        }
    }

    public int jugadoresConVentana() { return datos.size(); }

    /** Para las pruebas y para /etienda topes <jugador> reset. */
    public void olvidar(UUID jugador) {
        if (datos.remove(jugador) != null) sucio = true;
    }

    public Map<Material, int[]> resumen(UUID jugador) {
        Map<Material, int[]> out = new HashMap<>();
        Map<Material, Ventana> del = datos.get(jugador);
        if (del == null) return out;
        del.forEach((m, v) -> out.put(m, new int[]{v.vendido, (int) (v.inicio / 1000L)}));
        return out;
    }
}
