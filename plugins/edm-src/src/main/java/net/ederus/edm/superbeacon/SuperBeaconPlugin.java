package net.ederus.edm.superbeacon;

import java.io.File;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;
import net.ederus.edm.boost.BoostPlugin;
import net.ederus.edm.comun.Bitacora;
import net.ederus.edm.comun.Textos;
import net.kyori.adventure.text.format.TextColor;

/**
 * Super Beacon: balizas propias, independientes del faro vanilla, configurables y
 * vendibles, con efectos de area para el survival OP. Y el trofeo de temporada del clan
 * mas fuerte, que es un tipo mas (el ranking que lo entrega va aparte y solo necesita
 * /superbeacon give).
 *
 * Como esta montado:
 *   - los TIPOS (superbeacon/config.yml) son plantillas: nombre, bloque, alcance, a quien
 *     beneficia, cuanto dura y que efectos ofrece (ver {@link LectorTipos});
 *   - cada baliza lleva su FICHA (id, tipo, dueño, clan, vencimiento, eleccion) en el PDC
 *     del objeto, en el mundo o en un pendiente, y nunca en dos sitios a la vez
 *     (ver {@link Entregas});
 *   - los EFECTOS son clases enchufables (pocion, atributo, boost, vuelo, cultivos,
 *     sin-mobs; ver {@link ClaseEfecto}) y el {@link Motor} los aplica cada 2 s a quien
 *     alcanzan, quedandose con el mayor de cada grupo;
 *   - el bloque esta protegido y su faro vanilla neutralizado ({@link Guardia}), con su
 *     holograma ({@link Hologramas}) y su menu ({@link MenuBaliza}).
 *
 * Todo en el hilo principal; data.yml se escribe de golpe y en el acto en lo que importa.
 */
public final class SuperBeaconPlugin extends Module {

    static final String PERMISO_ADMIN = "ederus.superbeacon.admin";
    /** La seccion del titulo del menu: el gris carbon se lee sobre la barra clara del cofre. */
    static final TextColor SECCION = TextColor.color(0x3A3A3A);

    private static final int CONFIG_VERSION = 1;
    private static final int MENSAJES_VERSION = 1;

    /** Textos con acceso a las lineas sin prefijo y a las listas (holograma, lores). */
    static final class TextosBaliza extends Textos {
        private YamlConfiguration yml = new YamlConfiguration();

        @Override
        protected void alCargar(YamlConfiguration y) {
            this.yml = y;
        }

        void leer(File fichero) {
            this.yml = new YamlConfiguration();
            cargar(fichero);
        }

        /** El texto tal cual, sin prefijo ni marcadores, para componerlo con otros. */
        String crudo(String clave, String respaldo) {
            String s = yml.getString(clave);
            return s == null ? respaldo : s;
        }

        /** Una lista del fichero; si no esta o esta vacia, la de respaldo. */
        List<String> lista(String clave, List<String> respaldo) {
            if (!yml.isList(clave)) return respaldo;
            List<String> l = yml.getStringList(clave);
            return l.isEmpty() ? respaldo : l;
        }
    }

    private final TextosBaliza textos = new TextosBaliza();
    private Bitacora bitacora;

    private final Map<String, ClaseEfecto> clases = new LinkedHashMap<>();
    private ClaseAtributo atributos;
    private ClaseVuelo vuelo;
    private volatile Map<String, TipoBaliza> tipos = Map.of();

    private Registro registro;
    private Motor motor;
    private Hologramas hologramas;
    private MenuBaliza menu;
    private Entregas entregas;
    private Clanes clanes;
    private Objeto objeto;
    private Contorno contorno;

    private BukkitTask tareaEfectos;
    private BukkitTask tareaSegundo;
    private BukkitTask tareaRevision;
    private BukkitTask tareaHologramas;
    private volatile boolean detenido;

    private final Set<String> mundosExcluidos = new HashSet<>();
    private int maximoPorJugador = 3;
    private double holoAltura = 1.6;
    private int holoDistancia = 32;
    private int holoRefresco = 30;
    private ZoneId zona = ZoneId.systemDefault();

    public SuperBeaconPlugin(EDMPlugin core) {
        super(core, "superbeacon", "SuperBeacon");
    }

    /* ================================================================= vida */

    @Override
    public void onEnable() {
        detenido = false;
        migrar("config.yml", CONFIG_VERSION);
        saveDefaultConfig();
        reloadConfig();
        migrar("mensajes.yml", MENSAJES_VERSION);
        textos.leer(new File(getDataFolder(), "mensajes.yml"));
        bitacora = core.bitacora("superbeacon");

        registrarClases();
        clanes = new Clanes(getLogger());
        leerConfig();

        objeto = new Objeto(this);
        registro = new Registro(new File(getDataFolder(), "data.yml"), getLogger());
        registro.cargar();
        motor = new Motor(this);
        hologramas = new Hologramas(this);
        menu = new MenuBaliza(this);
        entregas = new Entregas(this);
        contorno = new Contorno(this);

        var pm = core.getServer().getPluginManager();
        pm.registerEvents(new Guardia(this), this);
        pm.registerEvents(menu, this);
        for (ClaseEfecto c : clases.values()) {
            if (c instanceof Listener l) pm.registerEvents(l, this);
            c.arrancar();
        }

        var cmd = core.getCommand("superbeacon");
        if (cmd != null) {
            ComandoSuperBeacon comando = new ComandoSuperBeacon(this);
            cmd.setExecutor(comando);
            cmd.setTabCompleter(comando);
        } else {
            getLogger().warning("El comando /superbeacon no esta en el plugin.yml de EDM.");
        }

        motor.reindexar();
        int huerfanos = hologramas.barrerHuerfanos();
        // Los que ya estaban dentro (un reinicio en caliente de EDM): restos fuera y su vuelo reconocido.
        for (Player p : Bukkit.getOnlinePlayers()) {
            atributos.barrer(p);
            if (vuelo.apuntado(p)) motor.sembrar(p, vuelo.marcador);
            registro.ligar(p);
        }

        // Un tic despues, con todos los modulos ya arrancados (boost va despues que este).
        core.getServer().getScheduler().runTask(core, this::primerTic);
        tareaEfectos = core.getServer().getScheduler().runTaskTimer(core, motor::ciclo, 40L, 40L);
        tareaSegundo = core.getServer().getScheduler().runTaskTimer(core, this::cadaSegundo, 20L, 20L);
        tareaRevision = core.getServer().getScheduler().runTaskTimer(core, this::revision, 100L, 100L);
        programarHologramas();

        getLogger().info("[SuperBeacon] " + tipos.size() + " tipo(s), " + registro.cuantas() + " colocado(s), "
                + registro.pendientes().size() + " pendiente(s) de entregar"
                + (huerfanos > 0 ? ", " + huerfanos + " holograma(s) sueltos barridos." : "."));
    }

    private void primerTic() {
        if (detenido) return;
        avisarBoost();
        for (Baliza b : new ArrayList<>(registro.todas())) revisar(b, true);
        entregas.repartir();
    }

    @Override
    public void onDisable() {
        detenido = true;
        for (BukkitTask t : new BukkitTask[]{tareaEfectos, tareaSegundo, tareaRevision, tareaHologramas}) {
            if (t != null) t.cancel();
        }
        if (menu != null) menu.cerrarTodos();
        if (contorno != null) contorno.pararTodo();
        if (motor != null) motor.parar();
        if (hologramas != null) hologramas.quitarTodos();
        if (registro != null) registro.guardar();
    }

    @Override
    public String recargar() {
        reloadConfig();
        textos.leer(new File(getDataFolder(), "mensajes.yml"));
        List<String> errores = leerConfig();
        for (Baliza b : registro.todas()) b.olvidarCache();
        motor.reindexar();
        programarHologramas();
        // Se rehacen enteros: la altura o la distancia del holograma pudieron cambiar.
        hologramas.quitarTodos();
        for (Baliza b : new ArrayList<>(registro.todas())) revisar(b, true);
        menu.refrescarTodos();
        return tipos.size() + " tipo(s), " + registro.cuantas() + " colocado(s)"
                + (errores.isEmpty() ? "." : "; " + errores.size() + " aviso(s) del config en la consola.");
    }

    /** Las clases de efecto. Una clase nueva es una linea aqui y su fichero; nada mas. */
    private void registrarClases() {
        clases.clear();
        atributos = new ClaseAtributo(this);
        vuelo = new ClaseVuelo(this);
        for (ClaseEfecto c : List.of(new ClasePocion(this), atributos, new ClaseBoost(this), vuelo,
                new ClaseCultivos(this), new ClaseSinMobs(this))) {
            clases.put(c.id(), c);
        }
    }

    /** Lee superbeacon/config.yml. Devuelve los avisos del config (ya sacados por consola). */
    private List<String> leerConfig() {
        FileConfiguration c = getConfig();
        mundosExcluidos.clear();
        for (String m : c.getStringList("mundos-excluidos")) mundosExcluidos.add(m.toLowerCase(Locale.ROOT));
        maximoPorJugador = Math.max(0, c.getInt("maximo-por-jugador", 3));
        holoAltura = c.getDouble("holograma.altura", 1.6);
        holoDistancia = Math.max(4, Math.min(256, c.getInt("holograma.distancia", 32)));
        holoRefresco = Math.max(5, Math.min(600, c.getInt("holograma.refresco-segundos", 30)));
        ZoneId z = Tiempo.zona(c.getString("zona-horaria", ""));
        if (z == null) {
            getLogger().warning("[SuperBeacon] zona-horaria \"" + c.getString("zona-horaria")
                    + "\" no es una zona valida (ejemplo: America/Lima); se usa la del sistema.");
            z = ZoneId.systemDefault();
        }
        zona = z;
        clanes.configurar(c.getString("clan.placeholder", "%uclans_tag_color%"), c.getStringList("clan.sin-clan"));

        List<String> errores = new ArrayList<>();
        tipos = Collections.unmodifiableMap(LectorTipos.leer(c.getConfigurationSection("tipos"), clases, errores));
        for (String e : errores) getLogger().warning("[SuperBeacon] config.yml > " + e);
        return errores;
    }

    /** Una vez al arrancar: hay efectos de boost en el config y el modulo boost esta apagado. */
    private void avisarBoost() {
        if (boost() != null) return;
        for (TipoBaliza t : tipos.values()) {
            for (Efecto e : t.efectos.values()) {
                if (e instanceof ClaseBoost.Multi) {
                    getLogger().warning("[SuperBeacon] Hay efectos de boost en los tipos (por ejemplo " + t.id + "."
                            + e.clave() + "), pero el modulo boost esta apagado (modulos.boost: false en el config de"
                            + " EDM): no se aplican y en el menu salen como no disponibles.");
                    return;
                }
            }
        }
    }

    private void programarHologramas() {
        if (tareaHologramas != null) tareaHologramas.cancel();
        long cada = holoRefresco * 20L;
        tareaHologramas = core.getServer().getScheduler().runTaskTimer(core, () -> {
            if (!detenido) hologramas.refrescarTodos();
        }, cada, cada);
    }

    /* ============================================================ las tareas */

    private void cadaSegundo() {
        if (detenido) return;
        motor.zona();
        menu.tic();
    }

    /**
     * Cada 5 s: vencimientos, bloques que desaparecieron (solo con el chunk cargado),
     * entregas pendientes de los conectados y lo que haya que guardar.
     */
    private void revision() {
        if (detenido) return;
        long ahora = System.currentTimeMillis();
        for (Baliza b : new ArrayList<>(registro.todas())) {
            if (b.vencida(ahora)) vencida(b);
            revisar(b, false);
        }
        entregas.repartir();
        registro.guardarSiHaceFalta();
        clanes.podar();
    }

    /**
     * Mira una baliza si su chunk esta cargado (nunca lo carga): si su bloque sigue
     * siendo el suyo, si toca destruirla por vencida y, si se pide, su holograma.
     */
    void revisar(Baliza b, boolean holograma) {
        if (registro.porId(b.id) != b) return;
        World w = Bukkit.getWorld(b.mundo);
        if (w == null || !w.isChunkLoaded(b.x >> 4, b.z >> 4)) return;
        Material hay = w.getBlockAt(b.x, b.y, b.z).getType();
        if (hay != b.material) {
            entregas.desaparecida(b, hay);
            return;
        }
        TipoBaliza t = tipo(b.tipo);
        if (t != null && t.alCaducar == TipoBaliza.AlCaducar.DESTRUIR && b.vencida(System.currentTimeMillis())) {
            entregas.destruir(b);
            return;
        }
        if (holograma) hologramas.refrescar(b);
    }

    /**
     * Vencida y de las que se apagan: se repinta como "Apagado" la primera vez que se ve, y
     * se le dice a su dueño la primera vez que se le ve conectado (se guarda, para que un
     * reinicio no repita el aviso). Las que se destruyen las quita revisar() con su chunk.
     */
    private void vencida(Baliza b) {
        TipoBaliza t = tipo(b.tipo);
        if (t == null) return;
        if (!b.vistaVencida) {
            b.vistaVencida = true;
            hologramas.refrescar(b);
            menu.refrescar(b.id);
            anotar("vencida", b.id.toString(), b.tipo, b.duenoTexto(), b.donde(),
                    t.alCaducar == TipoBaliza.AlCaducar.APAGAR ? "apagada" : "por destruir");
        }
        if (t.alCaducar != TipoBaliza.AlCaducar.APAGAR || b.avisoVencida || b.dueno == null) return;
        Player d = Bukkit.getPlayer(b.dueno);
        if (d == null) return;
        textos.manda(d, "vencido-apagado", "&7Tu %nombre% &7venció: se queda apagado, como recuerdo.",
                "%nombre%", t.nombre);
        b.avisoVencida = true;
        registro.marcar();
    }

    /* ============================================================ consultas */

    TipoBaliza tipo(String id) {
        return id == null ? null : tipos.get(id.toLowerCase(Locale.ROOT));
    }

    Map<String, TipoBaliza> tipos() {
        return tipos;
    }

    Collection<ClaseEfecto> clases() {
        return clases.values();
    }

    Map<String, ClaseEfecto> clasesPorId() {
        return Collections.unmodifiableMap(clases);
    }

    /** El modulo boost si esta en marcha (modulos.boost: true), o null. */
    BoostPlugin boost() {
        return core.modulo("boost") instanceof BoostPlugin b ? b : null;
    }

    TextosBaliza textos() {
        return textos;
    }

    Registro registro() {
        return registro;
    }

    Motor motor() {
        return motor;
    }

    Hologramas hologramas() {
        return hologramas;
    }

    MenuBaliza menu() {
        return menu;
    }

    Entregas entregas() {
        return entregas;
    }

    Clanes clanes() {
        return clanes;
    }

    Objeto objeto() {
        return objeto;
    }

    Contorno contorno() {
        return contorno;
    }

    ClaseAtributo atributos() {
        return atributos;
    }

    ClaseVuelo vuelo() {
        return vuelo;
    }

    boolean detenido() {
        return detenido;
    }

    ZoneId zona() {
        return zona;
    }

    double holoAltura() {
        return holoAltura;
    }

    int holoDistancia() {
        return holoDistancia;
    }

    int maximoPorJugador() {
        return maximoPorJugador;
    }

    boolean mundoExcluido(String mundo) {
        return mundo != null && mundosExcluidos.contains(mundo.toLowerCase(Locale.ROOT));
    }

    /** Si ese bloque cae dentro de una mina del modulo minas (si esta en marcha). */
    boolean enMina(org.bukkit.block.Block b) {
        if (!(core.modulo("minas") instanceof net.ederus.edm.minas.MinasPlugin minas) || minas.minas() == null) {
            return false;
        }
        return minas.minas().en(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()) != null;
    }

    boolean esAdmin(CommandSender quien) {
        return quien.hasPermission(PERMISO_ADMIN);
    }

    /** El dueño o el staff. Los demas solo ven de quien es. */
    boolean puedeGestionar(Player p, Baliza b) {
        return b.esDe(p.getUniqueId()) || esAdmin(p);
    }

    String beneficiaTexto(TipoBaliza.Beneficia b) {
        return switch (b) {
            case DUENO -> textos.crudo("beneficia-dueno", "solo a su dueño");
            case CLAN -> textos.crudo("beneficia-clan", "a su clan");
            case TODOS -> textos.crudo("beneficia-todos", "a todos");
        };
    }

    /** "Quedan 12 d 4 h", "Permanente" o "Apagado". */
    String restante(Baliza b, long ahora) {
        if (b.vence <= 0) return textos.crudo("permanente", "Permanente");
        if (b.vencida(ahora)) return textos.crudo("apagado", "Apagado");
        return textos.crudo("restante", "Quedan %tiempo%").replace("%tiempo%", Tiempo.restante(b.vence - ahora));
    }

    /**
     * Quita de la eleccion lo que ya no existe en el tipo y lo que pasa del tope, en el
     * orden del config (el mismo que usa TipoBaliza.activos). true si cambio algo.
     */
    boolean normalizar(Baliza b, TipoBaliza t) {
        if (t.fijo()) return false;          // la eleccion no cuenta; se deja como venia
        List<String> buenos = normalizados(b.elegidos, t);
        if (buenos.equals(new ArrayList<>(b.elegidos))) return false;
        b.elegidos.clear();
        b.elegidos.addAll(buenos);
        b.olvidarCache();
        registro.marcar();
        motor.reindexar();
        return true;
    }

    List<String> normalizados(Collection<String> elegidos, TipoBaliza t) {
        List<String> buenos = new ArrayList<>();
        if (t.fijo()) return buenos;
        for (String k : t.efectos.keySet()) {
            if (buenos.size() >= t.elegibles) break;
            if (elegidos.contains(k)) buenos.add(k);
        }
        return buenos;
    }

    void anotar(String... campos) {
        if (bitacora != null) bitacora.anotar(campos);
    }

    /* ========================================================= placeholders */

    /** %edm_superbeacon_count%: cuantas tiene colocadas. Cualquier hilo. */
    public int colocadasDe(UUID jugador) {
        return registro == null ? 0 : registro.colocadasDe(jugador);
    }

    /** %edm_superbeacon_buffs%: lo que recibe ahora, separado por comas. Cualquier hilo. */
    public String buffsDe(UUID jugador) {
        return motor == null ? "" : motor.buffs(jugador);
    }
}
