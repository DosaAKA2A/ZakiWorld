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
import org.bukkit.configuration.InvalidConfigurationException;
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
 * puede esperar (colocar, recoger, entregar, la lista de vuelos) se guarda al momento; lo
 * que si (la eleccion de efectos, la cache del clan) se marca y se guarda en la revision
 * de cada 5 s.
 *
 * Ademas guarda las posiciones VACIADAS: donde habia una baliza que se fue (recogida,
 * devuelta, destruida o desaparecida), con su id, su material y cuando. Si en una de ellas
 * reaparece un bloque de ese material sin baliza detras (un //undo de WorldEdit, un
 * rollback, un reinicio que no llego a guardar el aire), es un faro vanilla de regalo y
 * SuperBeaconPlugin.revisarVaciada lo quita. Se olvidan a los 7 dias o cuando un jugador
 * coloca algo ahi (entonces el bloque es suyo).
 */
final class Registro {

    static final int VERSION = 1;
    /** Cuanto se recuerda una posicion vaciada. */
    static final long VACIADA_VIDA_MS = 7L * 24 * 60 * 60 * 1000;

    /** Donde estuvo una baliza y ya no esta. */
    record Vaciada(UUID id, String mundo, int x, int y, int z, Material material, long desde) {
    }

    private final File fichero;
    private final Logger log;

    private final Map<UUID, Baliza> porId = new LinkedHashMap<>();
    private final Map<String, Map<Long, Baliza>> porBloque = new HashMap<>();
    private final Map<String, Map<Long, List<Baliza>>> porChunk = new HashMap<>();
    private final Map<UUID, Pendiente> pendientes = new LinkedHashMap<>();
    /** A quien le dimos vuelo nosotros (ver ClaseVuelo). */
    private final Set<UUID> vuelos = new LinkedHashSet<>();
    /** Posiciones vaciadas, por mundo y bloque. Pocas: las balizas movidas en la ultima semana. */
    private final Map<String, Map<Long, Vaciada>> vaciadas = new HashMap<>();

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
     * Al entrar un jugador: rellena el UUID de las balizas que el staff coloco a nombre de
     * alguien que aun no habia entrado nunca (se entregaron por nombre), y pone al dia el
     * nombre de las suyas si se lo cambio (el UUID es el mismo). Devuelve las que toco.
     */
    List<Baliza> ligar(Player p) {
        List<Baliza> tocadas = new ArrayList<>();
        boolean ligadas = false;
        for (Baliza b : porId.values()) {
            if (b.dueno == null && b.duenoNombre != null && b.duenoNombre.equalsIgnoreCase(p.getName())) {
                b.dueno = p.getUniqueId();
                ligadas = true;
                tocadas.add(b);
            } else if (b.esDe(p.getUniqueId()) && !p.getName().equals(b.duenoNombre)) {
                b.duenoNombre = p.getName();
                tocadas.add(b);
            }
        }
        if (ligadas) recontar();
        if (!tocadas.isEmpty()) sucio = true;
        return tocadas;
    }

    /** Devuelve false si ese id o ese bloque ya estaban ocupados (no se pone). */
    boolean poner(Baliza b) {
        if (porId.containsKey(b.id) || en(b.mundo, b.x, b.y, b.z) != null) return false;
        olvidarVaciada(b.mundo, b.x, b.y, b.z);
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
        // Se va de ese sitio (recogida, devuelta, destruida o desaparecida): se apunta el hueco.
        vaciadas.computeIfAbsent(b.mundo, k -> new HashMap<>()).put(claveBloque(b.x, b.y, b.z),
                new Vaciada(b.id, b.mundo, b.x, b.y, b.z, b.material, System.currentTimeMillis()));
        recontar();
    }

    /* ================================================================ vaciadas */

    Vaciada vaciadaEn(String mundo, int x, int y, int z) {
        Map<Long, Vaciada> m = vaciadas.get(mundo);
        return m == null ? null : m.get(claveBloque(x, y, z));
    }

    /** Un jugador puso algo ahi (o una baliza nueva): el hueco deja de vigilarse. */
    boolean olvidarVaciada(String mundo, int x, int y, int z) {
        Map<Long, Vaciada> m = vaciadas.get(mundo);
        if (m == null || m.remove(claveBloque(x, y, z)) == null) return false;
        if (m.isEmpty()) vaciadas.remove(mundo);
        sucio = true;
        return true;
    }

    boolean olvidarVaciada(Block b) {
        return b != null && olvidarVaciada(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    /** Las de ese chunk; vacia casi siempre. Se llama en cada carga de chunk. */
    List<Vaciada> vaciadasEnChunk(String mundo, int chunkX, int chunkZ) {
        Map<Long, Vaciada> m = vaciadas.get(mundo);
        if (m == null || m.isEmpty()) return List.of();
        List<Vaciada> out = new ArrayList<>();
        for (Vaciada v : m.values()) {
            if (v.x() >> 4 == chunkX && v.z() >> 4 == chunkZ) out.add(v);
        }
        return out;
    }

    List<Vaciada> vaciadas() {
        List<Vaciada> out = new ArrayList<>();
        for (Map<Long, Vaciada> m : vaciadas.values()) out.addAll(m.values());
        return out;
    }

    /** Fuera las de hace mas de 7 dias. Devuelve cuantas se olvidaron. */
    int podarVaciadas(long ahora) {
        int n = 0;
        for (Map<Long, Vaciada> m : vaciadas.values()) {
            int antes = m.size();
            m.values().removeIf(v -> caducada(v, ahora));
            n += antes - m.size();
        }
        vaciadas.values().removeIf(Map::isEmpty);
        if (n > 0) sucio = true;
        return n;
    }

    static boolean caducada(Vaciada v, long ahora) {
        return ahora - v.desde() > VACIADA_VIDA_MS;
    }

    /**
     * La regla, sin estado, para el selftest: en un hueco vigilado hay un faro huerfano si
     * hay un bloque del material de la baliza que se fue y ninguna baliza registrada ahi.
     */
    static boolean huerfano(Material hay, Vaciada v, boolean hayBaliza) {
        return v != null && !hayBaliza && hay == v.material();
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

    /**
     * Se guarda en el acto, al dar y al quitar: si el servidor cae con el vuelo ya guardado
     * en la ficha del jugador y esta lista sin guardar, al volver nadie se lo quitaria.
     */
    void vuelo(UUID jugador, boolean dado) {
        boolean cambio = dado ? vuelos.add(jugador) : vuelos.remove(jugador);
        if (cambio) guardar();
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
     *
     * Lo que se salta NO se puede perder: el siguiente guardado escribe solo lo que hay en
     * memoria, asi que un data.yml roto (un YAML mal editado a mano) o con entradas que no
     * se entienden se aparta antes tal cual (data.yml.roto-<instante>) para recuperarlo.
     * Si ni siquiera se puede apartar, el modulo no arranca: mejor caido que pisarlo.
     */
    void cargar() {
        porId.clear();
        porBloque.clear();
        porChunk.clear();
        pendientes.clear();
        vuelos.clear();
        vaciadas.clear();
        if (!fichero.exists()) {
            recontar();
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        try {
            yml.load(fichero);
        } catch (IOException | InvalidConfigurationException e) {
            // loadConfiguration() lo daria por vacio sin mas, y el guardado del apagado
            // borraria todas las balizas colocadas y pendientes.
            File aparte = apartar("no se puede leer: " + e.getMessage());
            log.severe("[SuperBeacon] data.yml no se puede leer. El modulo arranca SIN balizas registradas; la copia"
                    + " intacta esta en " + aparte.getName() + ": arreglala y vuelve a ponerla como data.yml con el"
                    + " servidor apagado.");
            recontar();
            return;
        }
        int duplicadas = 0;
        int saltadas = 0;
        ConfigurationSection bs = yml.getConfigurationSection("balizas");
        if (bs != null) {
            for (String k : bs.getKeys(false)) {
                ConfigurationSection s = bs.getConfigurationSection(k);
                UUID id = uuid(k);
                if (s == null || id == null) {
                    log.warning("[SuperBeacon] data.yml: balizas." + k + " no se entiende; se salta.");
                    saltadas++;
                    continue;
                }
                Ficha f = ficha(id, s);
                String mundo = s.getString("mundo");
                if (f.tipo() == null || mundo == null) {
                    log.warning("[SuperBeacon] data.yml: balizas." + k + " no tiene tipo o mundo; se salta.");
                    saltadas++;
                    continue;
                }
                Baliza b = new Baliza(f, mundo, s.getInt("x"), s.getInt("y"), s.getInt("z"),
                        material(s.getString("bloque"), k), s.getLong("colocada", System.currentTimeMillis()));
                b.avisoVencida = s.getBoolean("aviso-vencida", false);
                String clanDueno = s.getString("clan-dueno");
                b.clanDueno = clanDueno == null || clanDueno.isBlank() ? null : clanDueno;
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
                    saltadas++;
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
        long ahora = System.currentTimeMillis();
        for (Map<?, ?> m : yml.getMapList("vaciadas")) {
            Vaciada v = vaciada(m);
            // Una que no se entiende o ya caducada no se guarda mas: es solo una vigilancia.
            if (v == null || caducada(v, ahora) || en(v.mundo(), v.x(), v.y(), v.z()) != null) continue;
            vaciadas.computeIfAbsent(v.mundo(), k -> new HashMap<>()).put(claveBloque(v.x(), v.y(), v.z()), v);
        }
        recontar();
        if (saltadas > 0) {
            File aparte = apartar(saltadas + " entrada(s) que no se entienden");
            log.severe("[SuperBeacon] data.yml: " + saltadas + " entrada(s) no se entienden y no se cargan. El"
                    + " fichero tal cual estaba queda en " + aparte.getName() + " para recuperarlas a mano.");
        }
        if (duplicadas > 0) {
            log.warning("[SuperBeacon] " + duplicadas + " baliza(s) repetida(s) en el mismo bloque pasan a pendiente de devolver.");
            guardar();
        }
    }

    /**
     * Copia data.yml tal cual esta junto a el, con el instante en el nombre. Si no se
     * puede, el modulo no debe seguir (lanza): el siguiente guardado pisaria lo unico que
     * queda de esas balizas.
     */
    private File apartar(String motivo) {
        File aparte = new File(fichero.getParentFile(), fichero.getName() + ".roto-" + System.currentTimeMillis());
        try {
            Files.copy(fichero.toPath(), aparte.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("data.yml de Super Beacon " + motivo + " y no se pudo apartar ("
                    + e.getMessage() + "); el modulo no arranca para no pisarlo", e);
        }
        return aparte;
    }

    /** Una posicion vaciada de data.yml, o null si le falta algo. */
    private static Vaciada vaciada(Map<?, ?> m) {
        try {
            UUID id = uuid(String.valueOf(m.get("id")));
            Object mundo = m.get("mundo");
            Material mat = m.get("bloque") == null ? null : Material.matchMaterial(String.valueOf(m.get("bloque")));
            if (id == null || mundo == null || mat == null) return null;
            return new Vaciada(id, String.valueOf(mundo), ((Number) m.get("x")).intValue(),
                    ((Number) m.get("y")).intValue(), ((Number) m.get("z")).intValue(), mat,
                    ((Number) m.get("desde")).longValue());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Ficha ficha(UUID id, ConfigurationSection s) {
        return new Ficha(id, s.getString("tipo"), uuid(s.getString("dueno")), s.getString("dueno-nombre"),
                s.getString("clan"), s.getLong("vence", 0), s.getStringList("elegidos"), s.getLong("semana", 0));
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
            // Solo cache (ver Baliza.clanDueno): va aparte de "clan", que es el fijado y viaja.
            if (b.clanDueno != null) yml.set(r + ".clan-dueno", b.clanDueno);
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
        List<Map<String, Object>> huecos = new ArrayList<>();
        for (Vaciada h : vaciadas()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.id().toString());
            m.put("mundo", h.mundo());
            m.put("x", h.x());
            m.put("y", h.y());
            m.put("z", h.z());
            m.put("bloque", h.material().name());
            m.put("desde", h.desde());
            huecos.add(m);
        }
        yml.set("vaciadas", huecos);

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
        if (f.semana() > 0) yml.set(r + ".semana", f.semana());
    }
}
