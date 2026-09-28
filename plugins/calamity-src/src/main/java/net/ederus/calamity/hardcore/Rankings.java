package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * M17 · Rankings semanales y placeholders (DIS M17, PLAN sec. 7.3).
 *
 * Cuatro tablas sobre stats-semana: Extraido (tasado-mc), Cazador (cazas-validas), Segador
 * (parcas) y Superviviente (expedicion-max-seg). Solo puntua quien lleva >= 3 extracciones en
 * la semana; con menos de 8 elegibles en una tabla solo se paga el 1.o (tres premios entre
 * cinco jugadores serian un premio por aparecer). Nadie cobra mas de 2 tablas: se queda con
 * las de mas premio (empate: Extraido > Cazador > Segador > Superviviente) y su hueco pasa al
 * siguiente de esa tabla.
 *
 * El cierre es el lunes 00:00 en hardcore.zona: la tarea de cada recalcular-segundos mira si
 * la semana anterior ya se cerro (ranking-cerrada en datos). La primera vez que corre solo
 * apunta la semana anterior como cerrada: encender el ranking no paga semanas viejas.
 * /lw hardcore ranking cerrar cierra la semana EN CURSO a mano (pruebas) y la marca cerrada.
 *
 * Los tops de los placeholders se recalculan en esa misma tarea y se sirven de una cache
 * inmutable: PlaceholderAPI pregunta desde otros hilos y no puede leer hardcore-datos.yml
 * mientras el reloj escribe (DIS M17: "nunca dentro de la peticion"). Desde 1.3.2 son
 * clasificaciones enteras (Tops): el top 10 con nombre y el puesto de cualquiera, para los
 * hologramas, y el total lleva ademas el saldo de Esencias. La tarea solo lee el yml; ordenar
 * a todos y buscar los nombres va en una tarea asincrona.
 */
final class Rankings implements Listener {

    record Tabla(String id, String estadistica, String nombre) {
    }

    /** Un premio del cierre: tabla, puesto pagado (1-3), jugador y su valor. */
    record Puesto(String tabla, int puesto, UUID jugador, long valor) {
    }

    record Fila(UUID jugador, String nombre, long valor) {
    }

    static final List<Tabla> TABLAS = List.of(
            new Tabla("extraido", "tasado-mc", "Extraído"),
            new Tabla("cazador", "cazas-validas", "Cazador"),
            new Tabla("segador", "parcas", "Segador"),
            new Tabla("superviviente", "expedicion-max-seg", "Superviviente"));

    private static final int TOP = 10;

    private final Hardcore hc;
    private BukkitTask tarea;
    private final Set<BukkitTask> avisos = new HashSet<>();
    /** Nombres ya buscados: getOfflinePlayer no es gratis y los tops piden los mismos. Tambien fuera del hilo principal. */
    private final Map<UUID, String> nombres = new ConcurrentHashMap<>();

    /** Clave -> clasificacion (Tops). La total lleva tambien el saldo de Esencias. */
    private volatile Map<String, Tops.Clasificacion> clasTotal = Map.of();
    private volatile Map<String, Tops.Clasificacion> clasSemana = Map.of();
    private volatile Map<UUID, Map<String, Long>> copiaStats = Map.of();

    Rankings(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Subcomandos.lw().registrar("ranking", "ranking [ver|cerrar]: tablas de la semana; cerrar paga ya (M17)",
                "ederus.mundos", this::comando, args -> args.length == 2 ? List.of("ver", "cerrar") : List.of());
        PlaceholdersLethal.registrar("parcas", (j, r) -> stat(j, "parcas"));
        PlaceholdersLethal.registrar("ecos", (j, r) -> stat(j, "ecos-cerrados"));
        PlaceholdersLethal.registrar("stat", (j, r) -> r == null || r.isEmpty() ? null : stat(j, r.toLowerCase(Locale.ROOT)));
        PlaceholdersLethal.registrar("top", this::top);
        Autotest.registrar("ranking", this::autotest);
        Autotest.registrar("tops", Tops::autotest);
        long cada = Math.max(20L, hc.cfg().getLong("ranking.recalcular-segundos", 60) * 20L);
        tarea = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(),
                () -> hc.seguro("rankings", this::ciclo), 40L, cada);
    }

    boolean activo() {
        return hc.cfg().getBoolean("ranking.activo", false);
    }

    private Calendario cal() {
        return hc.calendario() != null ? hc.calendario() : new Calendario(hc);
    }

    private void ciclo() {
        recalcular();
        if (activo()) comprobarCierre(System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ cache

    private String nombre(UUID u) {
        String n = nombres.get(u);
        if (n != null) return n;
        OfflinePlayer o = Bukkit.getOfflinePlayer(u);
        n = o.getName() == null ? u.toString().substring(0, 8) : o.getName();
        nombres.put(u, n);
        return n;
    }

    /**
     * Solo en el hilo principal (la tarea): lee hardcore-datos.yml y deja mapas inmutables para
     * los otros hilos. Las clasificaciones se hacen fuera (clasificar).
     */
    private void recalcular() {
        Map<UUID, Map<String, Long>> stats = leer(hc.datos().getConfigurationSection("stats"));
        Horas h = hc.horas();
        if (h != null) {
            for (Map.Entry<UUID, Long> e : h.todos().entrySet()) {
                stats.computeIfAbsent(e.getKey(), k -> new HashMap<>()).put("horas-activas", e.getValue());
            }
        }
        Map<UUID, Map<String, Long>> semana = leer(hc.datos().getConfigurationSection("stats-semana." + cal().semana()));
        Saldo s = hc.saldo();
        Map<UUID, Long> esencias = s == null ? Map.of() : s.todos();
        Map<UUID, Map<String, Long>> copia = new HashMap<>();
        for (Map.Entry<UUID, Map<String, Long>> e : stats.entrySet()) copia.put(e.getKey(), Map.copyOf(e.getValue()));
        copiaStats = Map.copyOf(copia);
        // Ordenar a todos por cada clave son decenas de ms con miles de jugadores, y un nombre que
        // no esta en la cache puede ir a disco: fuera del hilo principal. Los mapas son los recien
        // leidos y ya nadie mas los toca.
        hc.plugin().getServer().getScheduler().runTaskAsynchronously(hc.plugin(), () -> {
            try {
                clasificar(stats, semana, esencias);
            } catch (Throwable t) {
                hc.plugin().getLogger().log(Level.WARNING, "[Calamity] No se pudieron rehacer los tops", t);
            }
        });
    }

    /** En la tarea asincrona: las clasificaciones de Tops, que se publican de una vez. */
    private void clasificar(Map<UUID, Map<String, Long>> stats, Map<UUID, Map<String, Long>> semana,
                            Map<UUID, Long> esencias) {
        Map<String, Tops.Clasificacion> total = Tops.clasificar(stats, TOP, this::nombre);
        // El saldo de Esencias de fuera no se apunta por semanas: solo va en el total.
        total.put(Tops.ESENCIAS, Tops.clasificacion(esencias, TOP, this::nombre));
        clasTotal = Map.copyOf(total);
        clasSemana = Map.copyOf(Tops.clasificar(semana, TOP, this::nombre));
    }

    private static Map<UUID, Map<String, Long>> leer(ConfigurationSection s) {
        Map<UUID, Map<String, Long>> out = new HashMap<>();
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            UUID u;
            try {
                u = UUID.fromString(k);
            } catch (IllegalArgumentException ignorado) {
                continue;
            }
            ConfigurationSection j = s.getConfigurationSection(k);
            if (j == null) continue;
            Map<String, Long> m = new HashMap<>();
            for (String clave : j.getKeys(false)) m.put(clave, j.getLong(clave, 0));
            out.put(u, m);
        }
        return out;
    }

    /** %lethalworld_stat_<clave>%, parcas, ecos. En el hilo principal, al dia; en otro, la cache. */
    private String stat(OfflinePlayer j, String clave) {
        if (j == null) return "";
        if (clave.equals("horas-activas")) {
            Horas h = hc.horas();
            return String.valueOf(h == null ? 0 : (long) h.horasActivas(j.getUniqueId()));
        }
        if (Bukkit.isPrimaryThread()) {
            Estadisticas st = hc.estadisticas();
            return String.valueOf(st == null ? 0 : st.de(j.getUniqueId(), clave));
        }
        Map<String, Long> m = copiaStats.get(j.getUniqueId());
        Long v = m == null ? null : m.get(clave);
        return String.valueOf(v == null ? 0 : v);
    }

    /**
     * El podio de la semana en curso de cada tabla de ranking.tablas, en su orden, de la cache
     * que rehace la tarea (puede ir un minuto atrasado). Lo ensena el Cazador de la antesala
     * (Npcs): es la clasificacion tal cual, sin el reparto de premios de cerrar().
     */
    Map<Tabla, List<Fila>> podioSemana(int n) {
        Map<String, Tops.Clasificacion> tops = clasSemana;
        Map<Tabla, List<Fila>> out = new LinkedHashMap<>();
        for (String id : tablas()) {
            Tabla t = tabla(id);
            List<Fila> l = tops.getOrDefault(t.estadistica(), Tops.Clasificacion.VACIA).top();
            out.put(t, l.subList(0, Math.min(Math.max(0, n), l.size())));
        }
        return out;
    }

    /**
     * La clasificacion de una clave de la semana en curso o de siempre (la total lleva tambien
     * horas-activas y el saldo de Esencias), de la cache: el menu de Rhen (MenuCazador) pinta
     * cada categoria con ella. Vacia si nadie tiene esa clave.
     */
    Tops.Clasificacion clasificacion(String clave, boolean semana) {
        return (semana ? clasSemana : clasTotal).getOrDefault(clave, Tops.Clasificacion.VACIA);
    }

    /**
     * %lethalworld_top_<clave>_<n>_nombre|valor|texto[_semana]%, top_<clave>_pos[_semana] y
     * top_<clave>_yo[_semana] (Tops). Solo lee la cache: vale desde cualquier hilo.
     */
    private String top(OfflinePlayer j, String resto) {
        Tops.Peticion p = Tops.leer(resto, TOP);
        if (p == null) return null;
        return Tops.responder(p, p.semana() ? clasSemana : clasTotal, j == null ? null : j.getUniqueId());
    }

    // ------------------------------------------------------------------ reparto

    /**
     * Quien cobra que (nucleo sin Bukkit, lo usa el autotest).
     *
     * Se recorren los premios de mas a menos (1.o de cada tabla en el orden de TABLAS, luego
     * los 2.os, luego los 3.os) y cada uno va al mejor de esa tabla que aun no cobro en ella y
     * no llego a maxTablas. Asi cada jugador se queda justo con sus premios mas altos.
     *
     * @param valores     tabla -> jugador -> valor de la semana
     * @param extracciones jugador -> extracciones de la semana
     */
    static List<Puesto> reparto(List<String> tablas, Map<String, Map<UUID, Long>> valores, Map<UUID, Long> extracciones,
                                int minimoExtracciones, int minimoElegibles, int maximoTablas, int puestos) {
        Map<String, List<Map.Entry<UUID, Long>>> orden = new LinkedHashMap<>();
        Map<String, Integer> pagados = new HashMap<>();
        for (String t : tablas) {
            List<Map.Entry<UUID, Long>> l = new ArrayList<>();
            for (Map.Entry<UUID, Long> e : valores.getOrDefault(t, Map.of()).entrySet()) {
                if (e.getValue() > 0 && extracciones.getOrDefault(e.getKey(), 0L) >= minimoExtracciones) l.add(e);
            }
            l.sort(Map.Entry.<UUID, Long>comparingByValue().reversed().thenComparing(e -> e.getKey().toString()));
            orden.put(t, l);
            pagados.put(t, l.size() < minimoElegibles ? Math.min(1, l.size()) : Math.min(puestos, l.size()));
        }
        List<Puesto> out = new ArrayList<>();
        Map<UUID, Integer> cuenta = new HashMap<>();
        Map<String, Set<UUID>> ya = new HashMap<>();
        for (int puesto = 1; puesto <= puestos; puesto++) {
            for (String t : tablas) {
                if (puesto > pagados.get(t)) continue;
                Set<UUID> enTabla = ya.computeIfAbsent(t, k -> new HashSet<>());
                for (Map.Entry<UUID, Long> e : orden.get(t)) {
                    UUID u = e.getKey();
                    if (enTabla.contains(u) || cuenta.getOrDefault(u, 0) >= maximoTablas) continue;
                    enTabla.add(u);
                    cuenta.merge(u, 1, Integer::sum);
                    out.add(new Puesto(t, puesto, u, e.getValue()));
                    break;
                }
            }
        }
        return out;
    }

    /** El reparto de una semana con los datos de Estadisticas (reales o en memoria). */
    List<Puesto> repartoDe(Estadisticas st, String semana) {
        List<String> ids = tablas();
        Map<String, Map<UUID, Long>> valores = new LinkedHashMap<>();
        Map<UUID, Long> extr = new HashMap<>();
        for (UUID u : st.jugadoresDeSemana(semana)) {
            extr.put(u, st.semana(u, "extracciones", semana));
            for (String id : ids) {
                Tabla t = tabla(id);
                if (t != null) valores.computeIfAbsent(id, k -> new HashMap<>()).put(u, st.semana(u, t.estadistica(), semana));
            }
        }
        ConfigurationSection c = hc.cfg();
        return reparto(ids, valores, extr, c.getInt("ranking.minimo-extracciones", 3),
                c.getInt("ranking.minimo-elegibles", 8), Math.max(1, c.getInt("ranking.maximo-tablas", 2)),
                Math.max(1, premios().size()));
    }

    /** Las tablas de ranking.tablas que existen, en su orden (define el desempate). */
    List<String> tablas() {
        List<String> conf = hc.cfg().getStringList("ranking.tablas");
        List<String> out = new ArrayList<>();
        for (String s : conf.isEmpty() ? List.of("extraido", "cazador", "segador", "superviviente") : conf) {
            if (tabla(s) != null && !out.contains(s)) out.add(s);
        }
        return out;
    }

    static Tabla tabla(String id) {
        for (Tabla t : TABLAS) if (t.id().equals(id)) return t;
        return null;
    }

    record Premio(int esencias, int llaves, List<String> comandos) {
    }

    List<Premio> premios() {
        List<Premio> out = new ArrayList<>();
        for (Map<?, ?> m : hc.cfg().getMapList("ranking.premios")) {
            List<String> cmds = new ArrayList<>();
            if (m.get("comandos") instanceof Collection<?> c) for (Object o : c) cmds.add(String.valueOf(o));
            out.add(new Premio(entero(m.get("esencias")), entero(m.get("llaves")), cmds));
        }
        if (out.isEmpty()) {
            out.add(new Premio(30, 2, List.of("lp user %jugador% permission settemp anima.badge.unlocked true 7d")));
            out.add(new Premio(20, 1, List.of()));
            out.add(new Premio(10, 1, List.of()));
        }
        return out;
    }

    private static int entero(Object o) {
        if (o instanceof Number n) return Math.max(0, n.intValue());
        try {
            return o == null ? 0 : Math.max(0, Integer.parseInt(String.valueOf(o)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ cierre

    private void comprobarCierre(long ahora) {
        String anterior = cal().semanaAnterior(ahora);
        String cerrada = hc.datos().getString("ranking-cerrada", null);
        if (cerrada == null) {
            hc.datos().set("ranking-cerrada", anterior);
            hc.marcarSucio();
            return;
        }
        // "2026-W39" ordena bien como texto (anio de cuatro cifras, semana de dos).
        if (cerrada.compareTo(anterior) < 0) cerrar(anterior, "lunes");
    }

    /** Cierra una semana: se marca cerrada y se guarda ANTES de pagar (nunca dos veces). */
    List<Puesto> cerrar(String semana, String motivo) {
        Estadisticas st = hc.estadisticas();
        if (st == null) return List.of();
        List<Puesto> res = repartoDe(st, semana);
        hc.datos().set("ranking-cerrada", semana);
        List<String> hist = new ArrayList<>();
        for (Puesto p : res) hist.add(p.tabla() + ":" + p.puesto() + ":" + p.jugador() + ":" + p.valor());
        hc.datos().set("ranking-historial." + semana, hist);
        hc.guardarYa();
        hc.plugin().bitacora().anotar("ranking", "cierre", semana, motivo, res.size() + " premios");
        List<Premio> premios = premios();
        for (Puesto p : res) hc.seguro("rankings", () -> pagar(p, premios.get(p.puesto() - 1), semana));
        return res;
    }

    private void pagar(Puesto p, Premio pr, String semana) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(p.jugador());
        String nombre = nombre(p.jugador());
        Tabla t = tabla(p.tabla());
        String tablaNombre = t == null ? p.tabla() : t.nombre();
        int e = 0;
        if (pr.esencias() > 0) {
            Aduana ad = hc.aduana();
            Aduana.Pago pago = ad == null ? null : ad.pagar(op, "ranking", pr.esencias(), 0, List.of(),
                    "ranking:" + p.tabla() + ":" + p.puesto() + ":" + semana);
            e = pago == null ? 0 : pago.esencias();
        }
        int llaves = 0;
        if (pr.llaves() > 0) {
            Entregas en = hc.entregas();
            llaves = en == null ? -1 : en.llave(op, pr.llaves(), "ranking", true);
        }
        for (String plantilla : pr.comandos()) consola(plantilla, nombre);
        hc.plugin().bitacora().anotar("ranking", "premio", nombre, p.tabla(), p.puesto() + ".o", "valor " + p.valor(),
                "e " + e, "llaves " + llaves, semana);

        // "1.º en Extraído (30 Esencias, 2 Llaves del Caos y [ÁNIMA] 7 días)". Se guarda tal cual para
        // quien no esta conectado (ranking-avisos) y se le ensena al entrar.
        List<String> partes = new ArrayList<>();
        if (e > 0) partes.add(e + (e == 1 ? " Esencia" : " Esencias"));
        if (pr.llaves() > 0) partes.add(pr.llaves() + (pr.llaves() == 1 ? " Llave del Caos" : " Llaves del Caos"));
        if (!pr.comandos().isEmpty()) partes.add("[ÁNIMA] 7 días");
        StringBuilder texto = new StringBuilder(p.puesto() + ".º en " + tablaNombre);
        if (!partes.isEmpty()) texto.append(" (").append(lista(partes)).append(")");
        Player online = op.getPlayer();
        if (online != null) {
            online.sendMessage(ComandoCalamity.mensaje(Component.text("Premio del ranking semanal: ")
                    .append(Component.text(texto.toString(), Paleta.DETALLE)).append(Component.text("."))));
        } else {
            List<String> l = new ArrayList<>(hc.datos().getStringList("ranking-avisos." + p.jugador()));
            l.add(texto.toString());
            hc.datos().set("ranking-avisos." + p.jugador(), l);
            hc.marcarSucio();
        }
    }

    /** "a", "a y b", "a, b y c". */
    private static String lista(List<String> cosas) {
        if (cosas.size() <= 1) return cosas.isEmpty() ? "" : cosas.get(0);
        return String.join(", ", cosas.subList(0, cosas.size() - 1)) + " y " + cosas.get(cosas.size() - 1);
    }

    private boolean consola(String plantilla, String nombre) {
        if (plantilla == null || plantilla.isBlank()) return true;
        if (nombre == null || !Entregas.NOMBRE_VALIDO.matcher(nombre).matches()) return false;
        String cmd = plantilla.replace("%jugador%", nombre).trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        try {
            return hc.plugin().getServer().dispatchCommand(hc.plugin().getServer().getConsoleSender(), cmd);
        } catch (Throwable t) {
            hc.plugin().getLogger().warning("[Calamity] Falló el comando de ranking \"" + cmd + "\": " + t);
            return false;
        }
    }

    /** P-H02 al conectarse, un par de segundos despues (que no se pierda entre los del join). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void alEntrar(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String ruta = "ranking-avisos." + p.getUniqueId();
        if (!hc.datos().isSet(ruta)) return;
        final BukkitTask[] t = new BukkitTask[1];
        t[0] = hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(), () -> {
            avisos.remove(t[0]);
            if (!p.isOnline()) return;
            List<String> l = hc.datos().getStringList(ruta);
            hc.datos().set(ruta, null);
            hc.marcarSucio();
            for (String s : l) {
                p.sendMessage(ComandoCalamity.mensaje(Component.text("Te esperaba un premio del ranking semanal: ")
                        .append(Component.text(s, Paleta.DETALLE)).append(Component.text("."))));
            }
        }, 40L);
        avisos.add(t[0]);
    }

    void parar() {
        HandlerList.unregisterAll(this);
        if (tarea != null) tarea.cancel();
        tarea = null;
        for (BukkitTask t : avisos) t.cancel();
        avisos.clear();
    }

    // ------------------------------------------------------------------ comando

    private void comando(CommandSender quien, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "ver";
        Estadisticas st = hc.estadisticas();
        if (st == null) {
            quien.sendMessage(ComandoCalamity.mensaje("Sin estadísticas: el módulo no arrancó."));
            return;
        }
        String semana = cal().semana();
        if (sub.equals("cerrar")) {
            if (!activo()) quien.sendMessage(ComandoCalamity.mensaje("ranking.activo está apagado, pero se cierra igual porque lo pides a mano."));
            List<Puesto> res = cerrar(semana, "admin");
            quien.sendMessage(ComandoCalamity.mensaje("Semana " + semana + " cerrada: " + res.size() + " premios."));
            listar(quien, res);
            recalcular();
            return;
        }
        List<Puesto> res = repartoDe(st, semana);
        quien.sendMessage(ComandoCalamity.mensaje("Semana " + semana + " (sin cerrar"
                + (semana.equals(hc.datos().getString("ranking-cerrada", "")) ? ", ya pagada a mano" : "")
                + "). Si cerrase ahora:"));
        listar(quien, res);
    }

    private void listar(CommandSender quien, List<Puesto> res) {
        if (res.isEmpty()) {
            quien.sendMessage(Component.text("  Nadie con premio (hacen falta "
                    + hc.cfg().getInt("ranking.minimo-extracciones", 3) + " salidas con vida en la semana).", Paleta.TENUE));
            return;
        }
        for (Puesto p : res) {
            Tabla t = tabla(p.tabla());
            quien.sendMessage(Component.text("  " + (t == null ? p.tabla() : t.nombre()) + " " + p.puesto() + ".º  ",
                    Paleta.TENUE).append(Component.text(nombre(p.jugador()), Paleta.DETALLE))
                    .append(Component.text("  " + p.valor(), Paleta.CIFRA)));
        }
    }

    // ------------------------------------------------------------------ autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        Calendario madrid = new Calendario(java.time.ZoneId.of("Europe/Madrid"));
        String sem = madrid.semana();

        // 10 jugadores con 3+ extracciones; el 1 va el primero en todo, el 2 segundo en todo...
        YamlConfiguration yml = new YamlConfiguration();
        Estadisticas st = new Estadisticas(yml, madrid);
        for (int i = 1; i <= 10; i++) {
            UUID u = Autotest.sintetico(i);
            st.sumar(u, "extracciones", 3);
            st.sumar(u, "tasado-mc", 1000 - i * 10);
            st.sumar(u, "cazas-validas", 50 - i);
            st.sumar(u, "parcas", 20 - i);
            st.maximo(u, "expedicion-max-seg", 5000 - i * 100);
        }
        List<Puesto> r = repartoDe(st, sem);
        h.igual("10 elegibles: 3 puestos x 4 tablas", 12, r.size());
        Map<UUID, Integer> cuenta = new HashMap<>();
        for (Puesto p : r) cuenta.merge(p.jugador(), 1, Integer::sum);
        h.ok("nadie cobra mas de 2 tablas", cuenta.values().stream().allMatch(n -> n <= 2));
        for (String t : List.of("extraido", "cazador", "segador", "superviviente")) {
            List<Integer> ps = r.stream().filter(p -> p.tabla().equals(t)).map(Puesto::puesto).sorted().toList();
            h.igual("tabla " + t + ": puestos 1, 2 y 3", List.of(1, 2, 3), ps);
        }
        UUID uno = Autotest.sintetico(1);
        List<String> delUno = r.stream().filter(p -> p.jugador().equals(uno)).map(Puesto::tabla).toList();
        h.igual("el primero en todo se queda Extraido y Cazador (1.os, desempate)", List.of("extraido", "cazador"), delUno);
        h.ok("el segador 1.o es el segundo de la tabla",
                r.stream().anyMatch(p -> p.tabla().equals("segador") && p.puesto() == 1 && p.jugador().equals(Autotest.sintetico(2))));

        // Con 6 elegibles (< 8) solo el 1.o de cada tabla.
        YamlConfiguration yml6 = new YamlConfiguration();
        Estadisticas st6 = new Estadisticas(yml6, madrid);
        for (int i = 1; i <= 6; i++) {
            UUID u = Autotest.sintetico(i);
            st6.sumar(u, "extracciones", 3);
            st6.sumar(u, "tasado-mc", 1000 - i);
            st6.sumar(u, "cazas-validas", 10 + i);
            st6.sumar(u, "parcas", i);
            st6.maximo(u, "expedicion-max-seg", 100 * i);
        }
        // Y uno con mucho pero solo 2 extracciones: no puntua.
        st6.sumar(Autotest.sintetico(7), "extracciones", 2);
        st6.sumar(Autotest.sintetico(7), "tasado-mc", 99_999);
        List<Puesto> r6 = repartoDe(st6, sem);
        h.igual("6 elegibles: solo el 1.o de cada tabla", 4, r6.size());
        h.ok("6 elegibles: todos son 1.o", r6.stream().allMatch(p -> p.puesto() == 1));
        h.ok("con 2 extracciones no se puntua", r6.stream().noneMatch(p -> p.jugador().equals(Autotest.sintetico(7))));

        List<Puesto> vacio = reparto(List.of("extraido"), Map.of(), Map.of(), 3, 8, 2, 3);
        h.igual("sin nadie, nada", 0, vacio.size());
        h.igual("premios de serie: 3", 3, premios().size());
        // Una clave que nadie tiene: con tasado-mc el resultado dependeria de los datos reales del servidor.
        h.igual("top sin datos: nombre", "—", top(null, "autotest-sin-datos_1_nombre"));
        h.igual("top sin datos: valor", "0", top(null, "autotest-sin-datos_1_valor"));
        h.igual("top con n fuera de rango", null, top(null, "tasado-mc_11_valor"));
        h.igual("stat sin jugador", "", PlaceholdersLethal.resolver(null, "stat_parcas"));
        h.ok("autotest no toca stats-semana reales", !hc.datos().isSet("stats-semana." + sem + "." + uno));
        h.ok("/lw hardcore ranking registrado", Subcomandos.lw().nombres(null).contains("ranking"));
        return h.lineas();
    }
}
