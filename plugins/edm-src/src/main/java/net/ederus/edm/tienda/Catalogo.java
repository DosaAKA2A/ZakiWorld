package net.ederus.edm.tienda;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Los precios, tal cual salen de la migracion de EconomyShopGUI.
 *
 * La clave NO es solo el material: los 8 spawners de Ederus son todos SPAWNER y
 * se distinguen por el mob que llevan dentro, asi que la clave es
 * "MATERIAL" o "MATERIAL:VARIANTE" (SPAWNER:PIG).
 *
 * Una clave aparece UNA sola vez en todo el catalogo: si estuviera en dos
 * categorias con precios distintos se podria comprar barato en una y vender caro
 * en otra, que es dinero infinito. La carga aborta si eso pasa.
 */
public final class Catalogo {

    /** Precio y limites de un articulo. Un precio a 0 significa "no se puede". */
    public record Articulo(String clave, Material material, EntityType spawner, String categoria,
                           double compra, double venta, int topeVenta, long ventanaMs,
                           java.util.List<String> lore, int limiteJugador,
                           String permiso, String mensajePermiso) {

        public boolean seCompra() { return compra > 0; }
        public boolean seVende() { return venta > 0; }
        public boolean tieneTope() { return topeVenta > 0; }
        public boolean esVariante() { return spawner != null; }
        public boolean tieneLore() { return lore != null && !lore.isEmpty(); }
        public boolean tieneLimiteJugador() { return limiteJugador > 0; }
        public boolean pideePermiso() { return permiso != null && !permiso.isBlank(); }
    }

    private final Map<String, Articulo> porClave = new LinkedHashMap<>();
    /** Atajo para el camino normal: material a pelo, sin variante. */
    private final Map<Material, Articulo> planos = new LinkedHashMap<>();
    private final Map<String, Integer> porCategoria = new LinkedHashMap<>();

    public void cargar(File fichero) throws IllegalStateException {
        Map<String, Articulo> nuevoPorClave = new LinkedHashMap<>();
        Map<Material, Articulo> nuevoPlanos = new LinkedHashMap<>();
        Map<String, Integer> nuevoCategorias = new LinkedHashMap<>();

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichero);
        ConfigurationSection categorias = yml.getConfigurationSection("categorias");
        if (categorias == null) throw new IllegalStateException("precios.yml no tiene 'categorias'");

        StringBuilder problemas = new StringBuilder();
        for (String categoria : categorias.getKeys(false)) {
            ConfigurationSection items = categorias.getConfigurationSection(categoria + ".items");
            if (items == null) continue;
            int n = 0;
            for (String clave : items.getKeys(false)) {
                String texto = clave.trim().toUpperCase(Locale.ROOT);
                String nombreMat = texto;
                EntityType spawner = null;

                int dosPuntos = texto.indexOf(':');
                if (dosPuntos > 0) {
                    nombreMat = texto.substring(0, dosPuntos);
                    String var = texto.substring(dosPuntos + 1);
                    try {
                        spawner = EntityType.valueOf(var);
                    } catch (IllegalArgumentException e) {
                        problemas.append("\n  variante desconocida: ").append(texto)
                                 .append(" (").append(categoria).append(')');
                        continue;
                    }
                }

                Material material = Material.matchMaterial(nombreMat);
                if (material == null) {
                    problemas.append("\n  material desconocido: ").append(texto)
                             .append(" (").append(categoria).append(')');
                    continue;
                }
                if (spawner != null && material != Material.SPAWNER) {
                    problemas.append("\n  solo SPAWNER admite variante, no ").append(material);
                    continue;
                }

                ConfigurationSection it = items.getConfigurationSection(clave);
                if (it == null) continue;

                double compra = it.getDouble("compra", 0);
                double venta = it.getDouble("venta", 0);
                int tope = it.getInt("tope", 0);
                long ventana = leerDuracion(it.getString("ventana", "24h"));

                if (venta > 0 && compra > 0 && venta >= compra) {
                    problemas.append("\n  BUCLE: ").append(texto)
                             .append(" se vende a ").append(venta)
                             .append(" y se compra a ").append(compra);
                    continue;
                }
                /* Un item con variante no se puede vender: al entrar al inventario
                 * lleva datos dentro y el motor solo acepta items a pelo. Si algun
                 * dia hiciera falta, hay que tocar el motor, no colarlo aqui. */
                if (spawner != null && venta > 0) {
                    problemas.append("\n  ").append(texto)
                             .append(" tiene precio de venta, y las variantes no se pueden vender");
                    continue;
                }

                Articulo previo = nuevoPorClave.get(texto);
                if (previo != null) {
                    problemas.append("\n  DUPLICADO: ").append(texto)
                             .append(" en '").append(previo.categoria())
                             .append("' y en '").append(categoria).append('\'');
                    continue;
                }

                Articulo art = new Articulo(texto, material, spawner, categoria, compra, venta, tope, ventana,
                        it.getStringList("lore"), it.getInt("limite-jugador", 0),
                        it.getString("permiso"), it.getString("mensaje-permiso"));
                nuevoPorClave.put(texto, art);
                if (spawner == null) nuevoPlanos.put(material, art);
                n++;
            }
            nuevoCategorias.put(categoria, n);
        }

        if (problemas.length() > 0) throw new IllegalStateException("catalogo con problemas:" + problemas);

        /* Solo se pisa el catalogo bueno si el nuevo esta sano: asi un /etienda
         * recargar con el fichero roto deja la tienda funcionando como estaba. */
        porClave.clear(); porClave.putAll(nuevoPorClave);
        planos.clear(); planos.putAll(nuevoPlanos);
        porCategoria.clear(); porCategoria.putAll(nuevoCategorias);
    }

    /** Acepta 30m, 4h, 24h, 7d o un numero suelto (horas). */
    static long leerDuracion(String texto) {
        if (texto == null || texto.isBlank()) return 24L * 3600_000L;
        String t = texto.trim().toLowerCase(Locale.ROOT);
        long factor = 3600_000L;
        if (t.endsWith("m")) { factor = 60_000L; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("h")) { factor = 3600_000L; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("d")) { factor = 86_400_000L; t = t.substring(0, t.length() - 1); }
        try {
            return Math.max(1, Long.parseLong(t.trim())) * factor;
        } catch (NumberFormatException e) {
            return 24L * 3600_000L;
        }
    }

    /** El material a pelo (lo que se puede vender). */
    public Articulo de(Material material) { return planos.get(material); }

    /** Por clave completa, con o sin variante: DIAMOND o SPAWNER:PIG. */
    public Articulo de(String clave) {
        return clave == null ? null : porClave.get(clave.trim().toUpperCase(Locale.ROOT));
    }

    public int total() { return porClave.size(); }
    public int variantes() { return (int) porClave.values().stream().filter(Articulo::esVariante).count(); }
    public Map<String, Integer> categorias() { return porCategoria; }
    public Iterable<String> claves() { return porClave.keySet(); }

    /** Los articulos de una categoria, en el orden en que estaban en el fichero. */
    public java.util.List<Articulo> deCategoria(String categoria) {
        java.util.List<Articulo> out = new java.util.ArrayList<>();
        for (Articulo a : porClave.values()) if (a.categoria().equals(categoria)) out.add(a);
        return out;
    }

    /**
     * El icono de una categoria en el menu: el primero de sus articulos.
     * No se saca de las secciones de EconomyShopGUI a proposito — este catalogo
     * tiene que valer por si solo cuando ESGUI ya no este.
     */
    public Material iconoDe(String categoria) {
        for (Articulo a : porClave.values()) if (a.categoria().equals(categoria)) return a.material();
        return Material.CHEST;
    }
}
