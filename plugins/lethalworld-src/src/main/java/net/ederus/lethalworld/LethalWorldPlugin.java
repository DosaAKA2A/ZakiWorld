package net.ederus.lethalworld;

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

import org.bukkit.plugin.java.JavaPlugin;

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
 *
 * Esto vivia dentro de EDM como el modulo "mundos". Salio a plugin propio porque EDM
 * cambia casi a diario y el datapack pesa 33 MB: subir y borrar ese jar cada vez es
 * pedir un disgusto. Aqui el jar solo se mueve cuando cambia Lethal World.
 */
public final class LethalWorldPlugin extends JavaPlugin {

    /** Namespace de las dimensiones que crea /lw. El mundo sale como lethal_world:<nombre>. */
    public static final String NAMESPACE = "lethal_world";
    private static final String PACK = "lethal_world";
    private static final Pattern NOMBRE_VALIDO = Pattern.compile("[a-z0-9_]{1,32}");

    private Bitacora bitacora;
    private Pregenerador pregen;
    private MobsLethal mobs;
    private net.ederus.lethalworld.hardcore.Hardcore hardcore;
    private final List<String> generadores = new ArrayList<>();

    /** Los mobs de Lethal World, para consultarlos desde el comando. */
    /** El ciclo de mobs; lo usan tambien las reglas hardcore para invocar por su cuenta. */
    public MobsLethal mobs() {
        return mobs;
    }

    @Override
    public void onEnable() {
        importarDeEDM();
        saveDefaultConfig();
        reloadConfig();
        this.bitacora = new Bitacora(new File(getDataFolder(), "logs"), "lethal-world", getLogger());
        this.bitacora.podar(getConfig().getInt("logs.dias", 14));
        cargarGeneradores();
        boolean cambio = instalarDatapack();

        ComandoMundos comando = new ComandoMundos(this);
        var cmd = getCommand("lw");
        if (cmd != null) {
            cmd.setExecutor(comando);
            cmd.setTabCompleter(comando);
        } else {
            getLogger().warning("El comando /lw no esta en el plugin.yml.");
        }
        pregen = new Pregenerador(this);
        pregen.cargar();
        mobs = new MobsLethal(this);
        mobs.arrancar();
        hardcore = new net.ederus.lethalworld.hardcore.Hardcore(this);
        hardcore.arrancar();

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
        if (pregen != null) pregen.apagar();
        if (mobs != null) mobs.parar();
        if (hardcore != null) hardcore.parar();
        if (bitacora != null) bitacora.cerrar();
    }

    /**
     * Relee el config del disco. Lo llama /lw reload; antes era /edm reload mundos.
     * No rearranca mobs ni reglas: lo que se lee en cada vuelta (topes, niveles,
     * puertas) se entera solo; lo que se monta al arrancar necesita reiniciar.
     */
    public String recargar() {
        reloadConfig();
        return mundos().size() + " mundo(s).";
    }

    /**
     * La primera vez: se trae la config que el modulo "mundos" de EDM dejo en
     * plugins/EDM/mundos. Sin esto, un servidor que ya tenia Calamity montado
     * (sus mundos, sus puertas, sus horas) arrancaria en blanco y crearia otra vez
     * las dimensiones. La carpeta vieja NO se borra: queda como copia.
     */
    private void importarDeEDM() {
        File destino = getDataFolder();
        if (new File(destino, "config.yml").isFile()) return;
        File vieja = new File(new File(getServer().getPluginsFolder(), "EDM"), "mundos");
        if (!new File(vieja, "config.yml").isFile()) return;
        File[] ficheros = vieja.listFiles((d, n) -> n.endsWith(".yml"));
        if (ficheros == null) return;
        destino.mkdirs();
        int copiados = 0;
        for (File f : ficheros) {
            try {
                Files.copy(f.toPath(), new File(destino, f.getName()).toPath());
                copiados++;
            } catch (IOException e) {
                getLogger().warning("No se pudo importar " + f.getName() + ": " + e.getMessage());
            }
        }
        if (copiados > 0) {
            getLogger().info("Importada la configuracion de plugins/EDM/mundos (" + copiados + " fichero(s)).");
        }
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
        return key == null ? null : getServer().getWorld(key);
    }

    /** Las reglas de los mundos hardcore (Calamity). Puede ser null si estan apagadas. */
    public net.ederus.lethalworld.hardcore.Hardcore hardcore() {
        return hardcore;
    }

    /** Si un mundo es de Lethal World: lo mira el ciclo de mobs en cada vuelta. */
    public static boolean esMundo(World w) {
        return w != null && NAMESPACE.equals(w.getKey().getNamespace());
    }

    /** La semilla propia de un mundo, o null si usa la del servidor. */
    public Long semillaDe(String nombre) {
        String ruta = "mundos." + nombre + ".semilla";
        return getConfig().isSet(ruta) ? getConfig().getLong(ruta) : null;
    }

    /**
     * Crea el mundo: queda escrito y existe tras reiniciar. Con semilla, el terreno y los
     * biomas salen distintos a los de otro mundo del mismo generador. Devuelve un error o null.
     */
    public String crear(String nombre, String generador, Long semilla) {
        nombre = nombre.toLowerCase(Locale.ROOT);
        generador = generador.toLowerCase(Locale.ROOT);
        if (!NOMBRE_VALIDO.matcher(nombre).matches()) {
            return "El nombre solo puede llevar minusculas, numeros y _ (hasta 32).";
        }
        if (!generadores.contains(generador)) return "No existe el generador '" + generador + "'.";
        if (mundos().containsKey(nombre)) return "Ya existe un mundo llamado '" + nombre + "'.";
        try {
            escribirDimension(carpetaPack(), nombre, generador, semilla, new HashSet<>());
        } catch (IOException e) {
            return "No se pudo escribir la dimension: " + e.getMessage();
        }
        getConfig().set("mundos." + nombre + ".generador", generador);
        if (semilla != null) getConfig().set("mundos." + nombre + ".semilla", semilla);
        saveConfig();
        bitacora.anotar("crear", nombre, "generador " + generador + (semilla != null ? ", semilla " + semilla : ""));
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

    /** Los mundos con el mismo generador salen identicos si tambien comparten semilla. */
    public List<String> mismoGenerador(String generador) {
        return mismoGenerador(generador, null, false);
    }

    public List<String> mismoGenerador(String generador, Long semilla, boolean mismaSemilla) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : mundos().entrySet()) {
            if (!e.getValue().equalsIgnoreCase(generador)) continue;
            if (mismaSemilla && !java.util.Objects.equals(semillaDe(e.getKey()), semilla)) continue;
            out.add(e.getKey());
        }
        return out;
    }

    // --------------------------------------------------------------------- datapack

    private File carpetaPack() {
        return new File(new File(new File(getServer().getWorldContainer(), nivel()), "datapacks"), PACK);
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
                try {
                    cambio |= escribirDimension(destino, e.getKey(), e.getValue(), semillaDe(e.getKey()), esperados);
                } catch (IOException ex) {
                    getLogger().warning("[Lethal World] El mundo " + e.getKey() + " no se pudo escribir: " + ex.getMessage());
                }
            }
            cambio |= podar(destino, destino, esperados);
        } catch (IOException e) {
            getLogger().warning("[Lethal World] No se pudo instalar el datapack: " + e.getMessage());
            return false;
        }
        if (cambio) bitacora.anotar("datapack", "instalado o actualizado en " + destino.getPath());
        return cambio;
    }

    private boolean escribirDimension(File pack, String nombre, String generador, Long semilla,
                                      Set<String> esperados) throws IOException {
        byte[] plantilla = recurso("generadores/" + generador + ".json");
        if (plantilla == null) throw new IOException("falta la plantilla del generador " + generador);
        boolean cambio = false;
        if (semilla != null) {
            StringBuilder dimension = new StringBuilder();
            Map<String, byte[]> ficheros = new Resembrador(this::recurso)
                    .resembrar(new String(plantilla, StandardCharsets.UTF_8), semilla, dimension);
            for (Map.Entry<String, byte[]> f : ficheros.entrySet()) {
                esperados.add(f.getKey());
                cambio |= escribir(new File(pack, f.getKey()), f.getValue());
            }
            plantilla = dimension.toString().getBytes(StandardCharsets.UTF_8);
        }
        String rel = "data/" + NAMESPACE + "/dimension/" + nombre + ".json";
        esperados.add(rel);
        return escribir(new File(pack, rel), plantilla) | cambio;
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
        File file = new File(getServer().getWorldContainer(), "server.properties");
        try (Reader r = Files.newBufferedReader(file.toPath())) {
            props.load(r);
        } catch (IOException ignored) {
        }
        return props.getProperty("level-name", "world");
    }

    Bitacora bitacora() {
        return bitacora;
    }

    Pregenerador pregen() {
        return pregen;
    }
}
