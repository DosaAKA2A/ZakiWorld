package com.ederus.tienda;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Los precios, tal cual salen de la migracion de EconomyShopGUI.
 *
 * Un material aparece UNA sola vez en todo el catalogo: si estuviera en dos
 * categorias con precios distintos se podria comprar barato en una y vender caro
 * en otra, que es dinero infinito. La carga aborta si eso pasa.
 */
public final class Catalogo {

    /** Precio y limites de un articulo. Un precio a 0 significa "no se puede". */
    public record Articulo(Material material, String categoria,
                           double compra, double venta,
                           int topeVenta, long ventanaMs) {

        public boolean seCompra() { return compra > 0; }
        public boolean seVende() { return venta > 0; }
        public boolean tieneTope() { return topeVenta > 0; }
    }

    private final Map<Material, Articulo> porMaterial = new LinkedHashMap<>();
    private final Map<String, Integer> porCategoria = new LinkedHashMap<>();
    private int duplicados;

    public void cargar(File fichero) throws IllegalStateException {
        porMaterial.clear();
        porCategoria.clear();
        duplicados = 0;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichero);
        ConfigurationSection categorias = yml.getConfigurationSection("categorias");
        if (categorias == null) throw new IllegalStateException("precios.yml no tiene 'categorias'");

        StringBuilder choques = new StringBuilder();
        for (String categoria : categorias.getKeys(false)) {
            ConfigurationSection items = categorias.getConfigurationSection(categoria + ".items");
            if (items == null) continue;
            int n = 0;
            for (String clave : items.getKeys(false)) {
                Material material = Material.matchMaterial(clave);
                if (material == null) {
                    choques.append("\n  material desconocido: ").append(clave)
                           .append(" (").append(categoria).append(')');
                    continue;
                }
                ConfigurationSection it = items.getConfigurationSection(clave);
                if (it == null) continue;

                double compra = it.getDouble("compra", 0);
                double venta = it.getDouble("venta", 0);
                int tope = it.getInt("tope", 0);
                long ventana = leerDuracion(it.getString("ventana", "24h"));

                if (venta > 0 && compra > 0 && venta >= compra) {
                    choques.append("\n  BUCLE: ").append(material)
                           .append(" se vende a ").append(venta)
                           .append(" y se compra a ").append(compra);
                    continue;
                }

                Articulo previo = porMaterial.get(material);
                if (previo != null) {
                    duplicados++;
                    choques.append("\n  DUPLICADO: ").append(material)
                           .append(" en '").append(previo.categoria())
                           .append("' y en '").append(categoria).append('\'');
                    continue;
                }
                porMaterial.put(material, new Articulo(material, categoria, compra, venta, tope, ventana));
                n++;
            }
            porCategoria.put(categoria, n);
        }

        if (choques.length() > 0) throw new IllegalStateException("catalogo con problemas:" + choques);
    }

    /** Acepta 30m, 4h, 24h o un numero suelto (horas). */
    static long leerDuracion(String texto) {
        if (texto == null || texto.isBlank()) return 24L * 3600_000L;
        String t = texto.trim().toLowerCase();
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

    public Articulo de(Material material) { return porMaterial.get(material); }
    public int total() { return porMaterial.size(); }
    public int duplicados() { return duplicados; }
    public Map<String, Integer> categorias() { return porCategoria; }
}
