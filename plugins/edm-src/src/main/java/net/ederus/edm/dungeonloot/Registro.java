package net.ederus.edm.dungeonloot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import net.ederus.edm.anomaly.drops.DropEntry;
import net.ederus.edm.anomaly.drops.DropTable;

/**
 * Las cajas y las bovedas plantadas, en disco.
 *
 * Dos ficheros a proposito: `cajas.yml` es el diseño —lo que un admin escribe y
 * revisa— y `bovedas.yml` es el mapa —lo que se llena solo segun se colocan—. Si
 * hay que rehacer el mapa no se toca el diseño, y al reves.
 *
 * Los objetos se guardan con la serializacion nativa de Bukkit, igual que el
 * botin de las anomalias, para que un item de MMOItems siga siendo el mismo item
 * al salir de la boveda.
 */
public final class Registro {

    private final DungeonLootPlugin plugin;

    private final Map<String, Caja> cajas = new LinkedHashMap<>();
    private final Map<String, Boveda> bovedas = new LinkedHashMap<>();
    /** Indice por coordenada, para responder en O(1) al clic en un bloque. */
    private final Map<String, Boveda> porClave = new LinkedHashMap<>();

    public Registro(DungeonLootPlugin plugin) {
        this.plugin = plugin;
    }

    /* --------------------------------------------------------------- consultas */

    public List<Caja> cajas() {
        return new ArrayList<>(cajas.values());
    }

    public Caja caja(String id) {
        return id == null ? null : cajas.get(id.toLowerCase(Locale.ROOT));
    }

    public List<Boveda> bovedas() {
        return new ArrayList<>(bovedas.values());
    }

    /** Las bovedas plantadas de una caja. */
    public List<Boveda> bovedasDe(String cajaId) {
        List<Boveda> out = new ArrayList<>();
        for (Boveda b : bovedas.values()) {
            if (b.cajaId().equals(cajaId)) out.add(b);
        }
        return out;
    }

    public Boveda bovedaEn(String world, int x, int y, int z) {
        return porClave.get(Boveda.clave(world, x, y, z));
    }

    /* ------------------------------------------------------------------ altas */

    /** Un id libre a partir del nombre: "Caja de Hierro" -> "caja-de-hierro". */
    public String idLibre(String nombre) {
        String base = nombre.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (base.isEmpty()) base = "caja";
        if (!cajas.containsKey(base)) return base;
        for (int i = 2; i < 1000; i++) {
            if (!cajas.containsKey(base + "-" + i)) return base + "-" + i;
        }
        return base + "-" + System.currentTimeMillis();
    }

    public Caja crear(String nombre, Caja.Tipo tipo) {
        Caja c = new Caja(idLibre(nombre), nombre);
        c.tipo(tipo);
        c.colorRgb(tipo == Caja.Tipo.OMINOSA ? 0xC792EA : 0x8FB8C4);
        cajas.put(c.id(), c);
        return c;
    }

    /** Borra la caja Y sus bovedas: dejar bovedas huerfanas es peor que borrarlas. */
    public void borrar(Caja caja) {
        cajas.remove(caja.id());
        for (Boveda b : bovedasDe(caja.id())) quitar(b);
    }

    public Boveda plantar(Caja caja, String world, int x, int y, int z) {
        String id = caja.id() + "-" + (bovedasDe(caja.id()).size() + 1) + "-" + Integer.toHexString(
                (world + x + ":" + y + ":" + z).hashCode() & 0xFFFF);
        Boveda b = new Boveda(id, caja.id(), world, x, y, z);
        bovedas.put(id, b);
        porClave.put(b.clave(), b);
        return b;
    }

    public void quitar(Boveda b) {
        bovedas.remove(b.id());
        porClave.remove(b.clave());
    }

    /* ------------------------------------------------------------------ disco */

    public void cargar() {
        cajas.clear();
        bovedas.clear();
        porClave.clear();
        cargarCajas();
        cargarBovedas();
        plugin.getLogger().info("DungeonLoot: " + cajas.size() + " caja(s), "
                + bovedas.size() + " boveda(s) plantada(s).");
    }

    private void cargarCajas() {
        File f = new File(plugin.getDataFolder(), "cajas.yml");
        if (!f.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = yml.getConfigurationSection("cajas");
        if (root == null) return;

        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Caja c = new Caja(id, s.getString("nombre", id));
            try {
                c.tipo(Caja.Tipo.valueOf(s.getString("tipo", "COMUN")));
            } catch (IllegalArgumentException ignored) {
                // tipo desconocido: se queda con la comun
            }
            c.colorRgb(s.getInt("color", 0x8FB8C4));
            c.tiradas(s.getInt("tiradas", 3));
            c.nombreLlave(s.getString("nombre-llave", ""));

            ConfigurationSection botin = s.getConfigurationSection("botin");
            if (botin != null) {
                List<String> claves = new ArrayList<>(botin.getKeys(false));
                claves.sort(Registro::porNumero);
                for (String k : claves) {
                    DropEntry e = leerEntrada(botin.getConfigurationSection(k));
                    if (e != null) c.tabla().entries().add(e);
                }
            }
            DropEntry unico = leerEntrada(s.getConfigurationSection("unico"));
            if (unico != null) c.unico(unico);
            cajas.put(id, c);
        }
    }

    private DropEntry leerEntrada(ConfigurationSection e) {
        if (e == null) return null;
        ItemStack item = e.getItemStack("item");
        if (item == null) return null;
        DropEntry entry = new DropEntry(item, e.getDouble("probabilidad", 100), 1, 1,
                DropEntry.Recipient.TODOS);
        entry.amount(e.getInt("cantidad-min", item.getAmount()), e.getInt("cantidad-max", item.getAmount()));
        entry.chance(e.getDouble("probabilidad", 100));
        return entry;
    }

    private static int porNumero(String a, String b) {
        try {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        } catch (NumberFormatException ex) {
            return a.compareTo(b);
        }
    }

    private void cargarBovedas() {
        File f = new File(plugin.getDataFolder(), "bovedas.yml");
        if (!f.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = yml.getConfigurationSection("bovedas");
        if (root == null) return;

        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            String cajaId = s.getString("caja", "");
            // Una boveda cuya caja ya no existe no se carga: seria un bloque que
            // no sabe que soltar y que ademas bloquearia el sitio.
            if (!cajas.containsKey(cajaId)) {
                plugin.getLogger().warning("La boveda " + id + " apunta a la caja '" + cajaId
                        + "', que ya no existe. No se carga.");
                continue;
            }
            Boveda b = new Boveda(id, cajaId, s.getString("mundo", "world"),
                    s.getInt("x"), s.getInt("y"), s.getInt("z"));
            b.aperturas(s.getInt("aperturas", 0));
            bovedas.put(id, b);
            porClave.put(b.clave(), b);
        }
    }

    public void guardar() {
        guardarCajas();
        guardarBovedas();
    }

    private void guardarCajas() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "Las cajas de mazmorra.",
                "",
                "Cada caja es una boveda del Trial Chamber con botin propio: la comun y la",
                "ominosa, que es la buena. El objeto de 'unico' va aparte de la lista porque",
                "es la pieza rara de la caja, la que justifica seguir abriendola.",
                "",
                "'tiradas' es cuantos objetos de la lista salen en cada apertura. Las",
                "probabilidades son por objeto y no tienen que sumar 100."));
        for (Caja c : cajas.values()) {
            String base = "cajas." + c.id();
            yml.set(base + ".nombre", c.display());
            yml.set(base + ".tipo", c.tipo().name());
            yml.set(base + ".color", c.colorRgb());
            yml.set(base + ".tiradas", c.tiradas());
            yml.set(base + ".nombre-llave", c.nombreLlave().isEmpty() ? null : c.nombreLlave());
            yml.set(base + ".botin", null);
            int i = 1;
            for (DropEntry e : c.tabla().entries()) {
                escribirEntrada(yml, base + ".botin." + (i++), e);
            }
            yml.set(base + ".unico", null);
            if (c.unico() != null) escribirEntrada(yml, base + ".unico", c.unico());
        }
        escribir(yml, "cajas.yml");
    }

    private void escribirEntrada(YamlConfiguration yml, String base, DropEntry e) {
        yml.set(base + ".item", e.item());
        yml.set(base + ".probabilidad", e.chance());
        yml.set(base + ".cantidad-min", e.min());
        yml.set(base + ".cantidad-max", e.max());
    }

    private void guardarBovedas() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "Donde esta plantada cada boveda. Este fichero se llena solo:",
                "se escribe al colocar una y se limpia al romperla."));
        for (Boveda b : bovedas.values()) {
            String base = "bovedas." + b.id();
            yml.set(base + ".caja", b.cajaId());
            yml.set(base + ".mundo", b.worldName());
            yml.set(base + ".x", b.x());
            yml.set(base + ".y", b.y());
            yml.set(base + ".z", b.z());
            yml.set(base + ".aperturas", b.aperturas());
        }
        escribir(yml, "bovedas.yml");
    }

    private void escribir(YamlConfiguration yml, String nombre) {
        try {
            yml.save(new File(plugin.getDataFolder(), nombre));
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "No se pudo guardar " + nombre, e);
        }
    }

    /** El total de objetos configurados en una caja, contando el unico. */
    public static int cuantos(Caja c) {
        DropTable t = c.tabla();
        return t.entries().size() + (c.unico() != null ? 1 : 0);
    }
}
