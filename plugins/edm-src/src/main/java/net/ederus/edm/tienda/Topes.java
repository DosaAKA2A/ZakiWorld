package net.ederus.edm.tienda;

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
 * Cuanto puede vender cada jugador de cada articulo antes de que se le corte.
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

    private final Map<UUID, Map<String, Ventana>> datos = new ConcurrentHashMap<>();
    private final File fichero;
    private volatile boolean sucio;

    /**
     * Apagados por defecto. El freno de la economia es el precio dinamico: si
     * el servidor vende mucho de algo, ese algo pierde valor. Un tope ademas
     * seria un muro, que es justo lo que se quiso quitar. Los valores siguen en
     * precios.yml por si algun dia hay que volver a encenderlos.
     */
    private boolean activo = false;

    public Topes(File fichero) { this.fichero = fichero; }

    public void configurar(org.bukkit.configuration.ConfigurationSection sec) {
        if (sec != null) activo = sec.getBoolean("activos", false);
    }

    public boolean activo() { return activo; }

    /** Cuanto le queda por vender. Integer.MAX_VALUE si ese articulo no lleva tope. */
    public int restante(UUID jugador, Catalogo.Articulo articulo) {
        if (!activo || !articulo.tieneTope()) return Integer.MAX_VALUE;
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
        Map<String, Ventana> del = datos.get(jugador);
        if (del == null) return null;
        Ventana v = del.get(articulo.clave());
        if (v == null) return null;
        if (System.currentTimeMillis() - v.inicio >= articulo.ventanaMs()) {
            del.remove(articulo.clave());   // caducada: se limpia sola
            sucio = true;
            return null;
        }
        return v;
    }

    /** Apunta una venta ya cobrada. Solo se llama DESPUES de pagar. */
    public void anotar(UUID jugador, Catalogo.Articulo articulo, int cantidad) {
        if (!activo || !articulo.tieneTope() || cantidad <= 0) return;
        Map<String, Ventana> del = datos.computeIfAbsent(jugador, k -> new ConcurrentHashMap<>());
        Ventana v = ventanaViva(jugador, articulo);
        if (v == null) del.put(articulo.clave(), new Ventana(System.currentTimeMillis(), cantidad));
        else v.vendido += cantidad;
        sucio = true;
    }

    /** Quita del mapa las ventanas ya caducadas, para que el fichero no crezca sin fin. */
    public int limpiar(Catalogo catalogo) {
        long ahora = System.currentTimeMillis();
        int fuera = 0;
        for (Iterator<Map.Entry<UUID, Map<String, Ventana>>> it = datos.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Map<String, Ventana>> e = it.next();
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
            Map<String, Ventana> del = new ConcurrentHashMap<>();
            for (String mat : sec.getKeys(false)) {
                long inicio = sec.getLong(mat + ".inicio", 0);
                int vendido = sec.getInt(mat + ".vendido", 0);
                if (inicio > 0 && vendido > 0) del.put(mat, new Ventana(inicio, vendido));
            }
            if (!del.isEmpty()) datos.put(id, del);
        }
        sucio = false;
    }

    public void guardar() {
        if (!sucio) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Ventana>> e : datos.entrySet()) {
            for (Map.Entry<String, Ventana> x : e.getValue().entrySet()) {
                String base = e.getKey() + "." + x.getKey();
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

    public Map<String, int[]> resumen(UUID jugador) {
        Map<String, int[]> out = new HashMap<>();
        Map<String, Ventana> del = datos.get(jugador);
        if (del == null) return out;
        del.forEach((m, v) -> out.put(m, new int[]{v.vendido, (int) (v.inicio / 1000L)}));
        return out;
    }
}
