package net.ederus.edm.mundos;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;
import net.ederus.edm.comun.Bitacora;

/**
 * Lethal World: mundos con el terreno, los biomas y las estructuras de The Bracken Pack,
 * ya procesado (sin resource pack, sin textos en ingles, sin funciones ni botin propio).
 *
 * Paper no deja que un generador de plugin use el terreno de una dimension de datapack:
 * al crear un mundo, la plantilla sale SIEMPRE del tipo (normal, nether, end). Asi que un
 * mundo de Lethal World es una dimension del datapack con nombre propio, y Paper la carga
 * sola en el siguiente arranque, con sus alturas y techos de verdad.
 *
 * Bracken es "All Rights Reserved": el datapack procesado entra en el jar al compilar y
 * nunca se sube al repositorio ni a una release publica. Ver tools/lethal-world.
 */
public final class MundosPlugin extends Module {

    /** Namespace de las dimensiones que crea /lw. El mundo sale como lethal_world:<nombre>. */
    public static final String NAMESPACE = "lethal_world";
    private static final String PACK = "lethal_world";
    private static final Pattern NOMBRE_VALIDO = Pattern.compile("[a-z0-9_]{1,32}");

    private static MundosPlugin instancia;

    private Bitacora bitacora;
    private final List<String> generadores = new ArrayList<>();

    public MundosPlugin(EDMPlugin core) {
        super(core, "mundos", "LethalWorld");
    }

    /** El modulo si esta cargado, o null. */
    public static MundosPlugin activo() {
        return instancia;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        this.bitacora = core.bitacora("mundos");
        cargarGeneradores();
        boolean cambio = instalarDatapack();

        ComandoMundos comando = new ComandoMundos(this);
        var cmd = core.getCommand("lw");
        if (cmd != null) {
            cmd.setExecutor(comando);
            cmd.setTabCompleter(comando);
        } else {
            getLogger().warning("El comando /lw no esta en el plugin.yml de EDM.");
        }
        instancia = this;

        int cargados = 0;
        Map<String, String> creados = mundos();
        for (String nombre : creados.keySet()) if (mundo(nombre) != null) cargados++;
        getLogger().info("[Lethal World] " + generadores.size() + " generadores, " + creados.size()
                + " mundo(s) creados, " + cargados + " cargados.");
        if (cambio) {
            getLogger().warning("[Lethal World] Datapack instalado o actualizado. Hace falta reiniciar"
                    + " para que los mundos nuevos existan.");
        }
    }

    @Override
    public void onDisable() {
        instancia = null;
    }

    @Override
    public String recargar() {
        reloadConfig();
        return mundos().size() + " mundo(s).";
    }

    // ------------------------------------------------------------------ generadores

    private void cargarGeneradores() {
        generadores.clear();
        byte[] indice = recurso("generadores.index");
        if (indice == null) {
            getLogger().severe("[Lethal World] El jar no trae los generadores. Se compilo sin correr"
                    + " tools/lethal-world/construir.py.");
            return;
        }
        for (String linea : new String(indice, StandardCharsets.UTF_8).split("\n")) {
            if (!linea.isBlank()) generadores.add(linea.trim());
        }
    }

    public List<String> generadores() {
        return List.copyOf(generadores);
    }

    private final Map<String, List<String>> biomasPorGenerador = new LinkedHashMap<>();

    /** Los biomas que reparte un generador, en el orden de su plantilla y sin repetir. */
    public List<String> biomasDe(String generador) {
        return biomasPorGenerador.computeIfAbsent(generador, g -> {
            List<String> out = new ArrayList<>();
            byte[] plantilla = recurso("generadores/" + g + ".json");
            if (plantilla == null) return out;
            Matcher m = Pattern.compile("\"biome\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(new String(plantilla, StandardCharsets.UTF_8));
            while (m.find()) if (!out.contains(m.group(1))) out.add(m.group(1));
            return out;
        });
    }

    /** El generador de un mundo de Lethal World ya cargado, o null. */
    public String generadorDe(World w) {
        if (!esMundo(w)) return null;
        return mundos().get(w.getKey().getKey());
    }

    public String nombreGenerador(String id) {
        return getConfig().getString("generadores." + id + ".nombre", id);
    }

    // ----------------------------------------------------------------------- mundos

    /** nombre -> generador, en el orden en que se crearon. */
    public Map<String, String> mundos() {
        Map<String, String> out = new LinkedHashMap<>();
        ConfigurationSection s = getConfig().getConfigurationSection("mundos");
        if (s == null) return out;
        for (String nombre : s.getKeys(false)) {
            String gen = s.getString(nombre + ".generador");
            if (gen != null) out.put(nombre, gen);
        }
        return out;
    }

    /** El mundo de Bukkit si ya esta cargado, o null (falta el reinicio). */
    public World mundo(String nombre) {
        NamespacedKey key = NamespacedKey.fromString(NAMESPACE + ":" + nombre.toLowerCase(Locale.ROOT));
        return key == null ? null : core.getServer().getWorld(key);
    }

    /** Si un mundo es de Lethal World. Lo usan los mobs, las MobCoins y los jefes. */
    public static boolean esMundo(World w) {
        return w != null && NAMESPACE.equals(w.getKey().getNamespace());
    }

    /** Crea el mundo: queda escrito y existe tras reiniciar. Devuelve un error o null. */
    public String crear(String nombre, String generador) {
        nombre = nombre.toLowerCase(Locale.ROOT);
        generador = generador.toLowerCase(Locale.ROOT);
        if (!NOMBRE_VALIDO.matcher(nombre).matches()) {
            return "El nombre solo puede llevar minusculas, numeros y _ (hasta 32).";
        }
        if (!generadores.contains(generador)) return "No existe el generador '" + generador + "'.";
        if (mundos().containsKey(nombre)) return "Ya existe un mundo llamado '" + nombre + "'.";
        getConfig().set("mundos." + nombre + ".generador", generador);
        saveConfig();
        try {
            escribirDimension(carpetaPack(), nombre, generador);
        } catch (IOException e) {
            return "No se pudo escribir la dimension: " + e.getMessage();
        }
        bitacora.anotar("crear", nombre, "generador " + generador);
        return null;
    }

    /** Quita el mundo de la config y del datapack. Su carpeta en disco NO se toca. */
    public String borrar(String nombre) {
        nombre = nombre.toLowerCase(Locale.ROOT);
        if (!mundos().containsKey(nombre)) return "No existe el mundo '" + nombre + "'.";
        getConfig().set("mundos." + nombre, null);
        saveConfig();
        File dim = new File(carpetaPack(), "data/" + NAMESPACE + "/dimension/" + nombre + ".json");
        if (dim.isFile() && !dim.delete()) {
            return "Quitado de la config, pero no pude borrar " + dim.getPath() + ".";
        }
        bitacora.anotar("borrar", nombre);
        return null;
    }

    /** Los mundos con el mismo generador salen identicos: comparten la semilla del servidor. */
    public List<String> mismoGenerador(String generador) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : mundos().entrySet()) {
            if (e.getValue().equalsIgnoreCase(generador)) out.add(e.getKey());
        }
        return out;
    }

    // --------------------------------------------------------------------- datapack

    private File carpetaPack() {
        return new File(new File(new File(core.getServer().getWorldContainer(), nivel()), "datapacks"), PACK);
    }

    /**
     * Copia el datapack del jar al mundo principal y escribe una dimension por cada mundo
     * creado. Borra lo que ya no toca. Devuelve true si cambio algo (hace falta reiniciar).
     */
    private boolean instalarDatapack() {
        byte[] indice = recurso("datapack.index");
        if (indice == null) return false;
        File destino = carpetaPack();
        boolean cambio = false;
        Set<String> esperados = new HashSet<>();
        try {
            for (String rel : new String(indice, StandardCharsets.UTF_8).split("\n")) {
                if (rel.isBlank()) continue;
                rel = rel.trim();
                esperados.add(rel);
                cambio |= escribir(new File(destino, rel), recurso("datapack/" + rel));
            }
            for (Map.Entry<String, String> e : mundos().entrySet()) {
                if (!generadores.contains(e.getValue())) {
                    getLogger().warning("[Lethal World] El mundo " + e.getKey() + " usa el generador '"
                            + e.getValue() + "', que ya no existe. No se carga.");
                    continue;
                }
                esperados.add("data/" + NAMESPACE + "/dimension/" + e.getKey() + ".json");
                cambio |= escribirDimension(destino, e.getKey(), e.getValue());
            }
            cambio |= podar(destino, destino, esperados);
        } catch (IOException e) {
            getLogger().warning("[Lethal World] No se pudo instalar el datapack: " + e.getMessage());
            return false;
        }
        if (cambio) bitacora.anotar("datapack", "instalado o actualizado en " + destino.getPath());
        return cambio;
    }

    private boolean escribirDimension(File pack, String nombre, String generador) throws IOException {
        byte[] plantilla = recurso("generadores/" + generador + ".json");
        if (plantilla == null) throw new IOException("falta la plantilla del generador " + generador);
        return escribir(new File(pack, "data/" + NAMESPACE + "/dimension/" + nombre + ".json"), plantilla);
    }

    /** Borra los ficheros del pack que no estan en esperados. */
    private boolean podar(File raiz, File dir, Set<String> esperados) {
        boolean cambio = false;
        File[] hijos = dir.listFiles();
        if (hijos == null) return false;
        for (File f : hijos) {
            if (f.isDirectory()) {
                cambio |= podar(raiz, f, esperados);
                String[] quedan = f.list();
                if (quedan != null && quedan.length == 0) f.delete();
                continue;
            }
            String rel = raiz.toPath().relativize(f.toPath()).toString().replace('\\', '/');
            if (!esperados.contains(rel) && f.delete()) cambio = true;
        }
        return cambio;
    }

    private byte[] recurso(String ruta) {
        try (InputStream in = getResource(ruta)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean escribir(File f, byte[] datos) throws IOException {
        if (datos == null) return false;
        if (f.isFile() && f.length() == datos.length && Arrays.equals(Files.readAllBytes(f.toPath()), datos)) {
            return false;
        }
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), datos);
        return true;
    }

    private String nivel() {
        Properties props = new Properties();
        File file = new File(core.getServer().getWorldContainer(), "server.properties");
        try (Reader r = Files.newBufferedReader(file.toPath())) {
            props.load(r);
        } catch (IOException ignored) {
        }
        return props.getProperty("level-name", "world");
    }

    Bitacora bitacora() {
        return bitacora;
    }
}
