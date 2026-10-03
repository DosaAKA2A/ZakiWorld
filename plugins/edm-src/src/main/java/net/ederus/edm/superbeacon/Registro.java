package net.ederus.edm.superbeacon;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Donde viven los Super Beacon que no son un objeto: los colocados y los pendientes de
 * entregar, en superbeacon/data.yml.
 *
 * Tres indices para los colocados, porque se preguntan cosas distintas muy a menudo:
 *   - por id: /superbeacon remove, la regla de "un id, un sitio";
 *   - por bloque: cada BlockBreakEvent, pistones, explosiones... tiene que ser O(1);
 *   - por chunk: al cargar y descargar un chunk (hologramas y revision).
 *
 * El fichero se escribe entero y de golpe (a un .tmp y luego se cambia por el bueno): es
 * pequeño, y asi una caida a mitad de escritura no deja un data.yml cortado. Lo que no
 * puede esperar (colocar, recoger, entregar) se guarda al momento; lo que si (la eleccion
 * de efectos, la lista de vuelos) se marca y se guarda en la revision de cada 5 s.
 */
final class Registro {

    static final int VERSION = 1;

    private final File fichero;
    private final Logger log;

    private final Map<UUID, Baliza> porId = new LinkedHashMap<>();
    private final Map<String, Map<Long, Baliza>> porBloque = new HashMap<>();
    private final Map<String, Map<Long, List<Baliza>>> porChunk = new HashMap<>();
    private final Map<UUID, Pendiente> pendientes = new LinkedHashMap<>();
    /** A quien le dimos vuelo nosotros (ver ClaseVuelo). */
    private final Set<UUID> vuelos = new LinkedHashSet<>();

    /** Cuantas tiene colocadas cada dueño, para %edm_superbeacon_count% (puede leerse fuera del hilo). */
    private volatile Map<UUID, Integer> cuentas = Map.of();

    private boolean sucio;
    private boolean avisadoDisco;

    Registro(File fichero, Logger log) {
        this.fichero = fichero;
        this.log = log;
    }

    /* =============================================================== colocadas */

    static long claveBloque(int x, int y, int z) {
        return ((long) x & 0x7FFFFFFL) | (((long) z & 0x7FFFFFFL) << 27) | ((long) y << 54);
    }

    Baliza en(String mundo, int x, int y, int z) {
        Map<Long, Baliza> m = porBloque.get(mundo);
        return m == null ? null : m.get(claveBloque(x, y, z));
    }

    Baliza en(Block b) {
        return b == null ? null : en(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    Baliza porId(UUID id) {
        return id == null ? null : porId.get(id);
    }

    /** Las que empiezan por ese texto (un id corto de /superbeacon list). */
    List<Baliza> buscar(String prefijo) {
        String p = prefijo.toLowerCase(Locale.ROOT);
        List<Baliza> out = new ArrayList<>();
        for (Baliza b : porId.values()) {
            if (b.id.toString().startsWith(p)) out.add(b);
        }
        return out;
    }

    /** Vista que no se modifica. Para recorrer y tocar a la vez, copiarla antes. */
    Collection<Baliza> todas() {
        return Collections.unmodifiableCollection(porId.values());
    }

    int cuantas() {
        return porId.size();
    }

    List<Baliza> enChunk(String mundo, int chunkX, int chunkZ) {
        Map<Long, List<Baliza>> m = porChunk.get(mundo);
        if (m == null) return List.of();
        List<Baliza> l = m.get(Alcance.clave(chunkX, chunkZ));
        return l == null ? List.of() : new ArrayList<>(l);
    }

    List<Baliza> de(UUID dueno) {
        List<Baliza> out = new ArrayList<>();
        for (Baliza b : porId.values()) if (b.esDe(dueno)) out.add(b);
        return out;
    }

    /** Las de ese dueño que cumplen la condicion (las que cuentan en el maximo). */
    int cuantasDe(UUID dueno, Predicate<Baliza> cuenta) {
        int n = 0;
        for (Baliza b : porId.values()) if (b.esDe(dueno) && cuenta.test(b)) n++;
        return n;
    }

    /** Para el placeholder: se puede llamar desde cualquier hilo. */
    int colocadasDe(UUID dueno) {
        return dueno == null ? 0 : cuentas.getOrDefault(dueno, 0);
    }

    /**
     * Rellena el UUID de las balizas que el staff coloco a nombre de alguien que aun no
     * habia entrado nunca (se entregaron por nombre). Devuelve cuantas eran suyas.
     */
    int ligar(Player p) {
        int n = 0;
        for (Baliza b : porId.values()) {
            if (b.dueno == null && b.duenoNombre != null && b.duenoNombre.equalsIgnoreCase(p.getName())) {
                b.dueno = p.getUniqueId();
                n++;
            }
        }
        if (n > 0) {
            recontar();
            sucio = true;
        }
        return n;
    }

    /** Devuelve false si ese id o ese bloque ya estaban ocupados (no se pone). */
    boolean poner(Baliza b) {
        if (porId.containsKey(b.id) || en(b.mundo, b.x, b.y, b.z) != null) return false;
        porId.put(b.id, b);
        porBloque.computeIfAbsent(b.mundo, k -> new HashMap<>()).put(claveBloque(b.x, b.y, b.z), b);
        porChunk.computeIfAbsent(b.mundo, k -> new HashMap<>())
                .computeIfAbsent(Alcance.clave(b.x >> 4, b.z >> 4), k -> new ArrayList<>(2)).add(b);
        recontar();
        return true;
    }

    void quitar(Baliza b) {
        if (porId.get(b.id) != b) return;
        porId.remove(b.id);
        Map<Long, Baliza> m = porBloque.get(b.mundo);
        if (m != null) {
            m.remove(claveBloque(b.x, b.y, b.z));
            if (m.isEmpty()) porBloque.remove(b.mundo);
        }
        Map<Long, List<Baliza>> c = porChunk.get(b.mundo);
        if (c != null) {
            long k = Alcance.clave(b.x >> 4, b.z >> 4);
            List<Baliza> l = c.get(k);
            if (l != null) {
                l.remove(b);
                if (l.isEmpty()) c.remove(k);
            }
            if (c.isEmpty()) porChunk.remove(b.mundo);
        }
        recontar();
    }

    private void recontar() {
        Map<UUID, Integer> n = new HashMap<>();
        for (Baliza b : porId.values()) {
            if (b.dueno != null) n.merge(b.dueno, 1, Integer::sum);
        }
        cuentas = Map.copyOf(n);
    }

    /* ============================================================== pendientes */

    /** Apunta un pendiente. Uno por id: si ya habia uno de esa baliza, este lo sustituye. */
    void pendiente(Pendiente p) {
        pendientes.put(p.ficha.id(), p);
    }

    Pendiente pendiente(UUID idBaliza) {
        return pendientes.get(idBaliza);
    }

    Pendiente quitarPendiente(UUID idBaliza) {
        return pendientes.remove(idBaliza);
    }

    Collection<Pendiente> pendientes() {
        return Collections.unmodifiableCollection(pendientes.values());
    }

    List<Pendiente> pendientesDe(Player p) {
        List<Pendiente> out = new ArrayList<>();
        for (Pendiente pe : pendientes.values()) if (pe.esPara(p)) out.add(pe);
        return out;
    }

    /* ================================================================= vuelos */

    Set<UUID> vuelos() {
        return Collections.unmodifiableSet(vuelos);
    }

    void vuelo(UUID jugador, boolean dado) {
        boolean cambio = dado ? vuelos.add(jugador) : vuelos.remove(jugador);
        if (cambio) sucio = true;
    }

    /* ================================================================= disco */

    /** Hay cambios sin escribir: los escribe la revision de cada 5 s. */
    void marcar() {
        sucio = true;
    }

    void guardarSiHaceFalta() {
        if (sucio) guardar();
    }

    /**
     * Lee data.yml. Lo que no se entiende se avisa y se salta, nunca tumba el modulo. Dos
     * balizas apuntadas en el mismo bloque (no deberia pasar nunca) no pueden estar las
     * dos: la segunda pasa a pendiente de devolver a su dueño.
     */
    void cargar() {
        porId.clear();
        porBloque.clear();
        porChunk.clear();
        pendientes.clear();
        vuelos.clear();
        if (!fichero.exists()) {
            recontar();
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichero);
        int duplicadas = 0;
        ConfigurationSection bs = yml.getConfigurationSection("balizas");
        if (bs != null) {
            for (String k : bs.getKeys(false)) {
                ConfigurationSection s = bs.getConfigurationSection(k);
                UUID id = uuid(k);
                if (s == null || id == null) {
                    log.warning("[SuperBeacon] data.yml: balizas." + k + " no se entiende; se salta.");
                    continue;
                }
                Ficha f = ficha(id, s);
                String mundo = s.getString("mundo");
                if (f.tipo() == null || mundo == null) {
                    log.warning("[SuperBeacon] data.yml: balizas." + k + " no tiene tipo o mundo; se salta.");
                    continue;
                }
                Baliza b = new Baliza(f, mundo, s.getInt("x"), s.getInt("y"), s.getInt("z"),
                        material(s.getString("bloque"), k), s.getLong("colocada", System.currentTimeMillis()));
                b.avisoVencida = s.getBoolean("aviso-vencida", false);
                if (!poner(b)) {
                    duplicadas++;
                    pendientes.put(id, new Pendiente(f, b.material, f.dueno(), f.duenoNombre(), "duplicada",
                            System.currentTimeMillis()));
                }
            }
        }
        ConfigurationSection ps = yml.getConfigurationSection("pendientes");
        if (ps != null) {
            for (String k : ps.getKeys(false)) {
                ConfigurationSection s = ps.getConfigurationSection(k);
                UUID id = uuid(k);
                if (s == null || id == null || s.getString("tipo") == null) {
                    log.warning("[SuperBeacon] data.yml: pendientes." + k + " no se entiende; se salta.");
                    continue;
                }
                if (porId.containsKey(id)) {
                    // Colocada y pendiente a la vez: manda el mundo, el pendiente sobra.
                    log.warning("[SuperBeacon] data.yml: el pendiente " + k + " ya esta colocado; se descarta.");
                    continue;
                }
                pendientes.put(id, new Pendiente(ficha(id, s), material(s.getString("bloque"), k),
                        uuid(s.getString("para")), s.getString("para-nombre"), s.getString("motivo", "?"),
                        s.getLong("desde", System.currentTimeMillis())));
            }
        }
        for (String v : yml.getStringList("vuelos")) {
            UUID u = uuid(v);
            if (u != null) vuelos.add(u);
        }
        recontar();
        if (duplicadas > 0) {
            log.warning("[SuperBeacon] " + duplicadas + " baliza(s) repetida(s) en el mismo bloque pasan a pendiente de devolver.");
            guardar();
        }
    }

    private static Ficha ficha(UUID id, ConfigurationSection s) {
        return new Ficha(id, s.getString("tipo"), uuid(s.getString("dueno")), s.getString("dueno-nombre"),
                s.getString("clan"), s.getLong("vence", 0), s.getStringList("elegidos"));
    }

    private Material material(String nombre, String donde) {
        Material m = nombre == null ? null : Material.matchMaterial(nombre);
        if (m == null) {
            log.warning("[SuperBeacon] data.yml: " + donde + " tiene un bloque que no existe (" + nombre + "); se toma BEACON.");
            return Material.BEACON;
        }
        return m;
    }

    private static UUID uuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    void guardar() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "Super Beacons colocados y pendientes de entregar. Lo escribe el modulo solo.",
                "No lo toques con el servidor encendido: se sobrescribe en el siguiente guardado.",
                "vence y colocada: instantes en epoch ms (vence 0 = no caduca)."));
        yml.set("version", VERSION);
        for (Baliza b : porId.values()) {
            String r = "balizas." + b.id;
            escribir(yml, r, b.ficha());
            yml.set(r + ".mundo", b.mundo);
            yml.set(r + ".x", b.x);
            yml.set(r + ".y", b.y);
            yml.set(r + ".z", b.z);
            yml.set(r + ".bloque", b.material.name());
            yml.set(r + ".colocada", b.colocada);
            if (b.avisoVencida) yml.set(r + ".aviso-vencida", true);
        }
        for (Pendiente p : pendientes.values()) {
            String r = "pendientes." + p.ficha.id();
            yml.set(r + ".para", p.para == null ? null : p.para.toString());
            yml.set(r + ".para-nombre", p.paraNombre);
            yml.set(r + ".motivo", p.motivo);
            yml.set(r + ".desde", p.desde);
            escribir(yml, r, p.ficha);
            yml.set(r + ".bloque", p.material.name());
        }
        List<String> v = new ArrayList<>();
        for (UUID u : vuelos) v.add(u.toString());
        yml.set("vuelos", v);

        try {
            File padre = fichero.getParentFile();
            if (padre != null) padre.mkdirs();
            File tmp = new File(padre, fichero.getName() + ".tmp");
            Files.writeString(tmp.toPath(), yml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp.toPath(), fichero.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), fichero.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            sucio = false;
            avisadoDisco = false;
        } catch (IOException e) {
            if (!avisadoDisco) {
                avisadoDisco = true;
                log.severe("[SuperBeacon] No se pudo guardar data.yml (" + e.getMessage()
                        + "). Los Super Beacons siguen en memoria; se reintenta en el siguiente cambio.");
            }
            sucio = true;
        }
    }

    private static void escribir(YamlConfiguration yml, String r, Ficha f) {
        yml.set(r + ".tipo", f.tipo());
        yml.set(r + ".dueno", f.dueno() == null ? null : f.dueno().toString());
        yml.set(r + ".dueno-nombre", f.duenoNombre());
        yml.set(r + ".clan", f.clan());
        yml.set(r + ".vence", f.vence());
        yml.set(r + ".elegidos", new ArrayList<>(f.elegidos()));
    }
}
