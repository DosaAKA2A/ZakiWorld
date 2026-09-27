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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
 * mientras el reloj escribe (DIS M17: "nunca dentro de la peticion").
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
    /** Nombres ya buscados: getOfflinePlayer no es gratis y los tops piden los mismos. */
    private final Map<UUID, String> nombres = new HashMap<>();

    private volatile Map<String, List<Fila>> topsTotal = Map.of();
    private volatile Map<String, List<Fila>> topsSemana = Map.of();
    private volatile Map<UUID, Map<String, Long>> copiaStats = Map.of();

    Rankings(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Subcomandos.lw().registrar("ranking", "ranking [ver|cerrar]: tablas de la semana; cerrar paga ya (M17)",
                "ederus.mundos", this::comando, args -> args.length == 2 ? List.of("ver", "cerrar") : List.of());
        PlaceholdersLethal.registrar("parcas", (j, r) -> stat(j, "parcas"));
        PlaceholdersLethal.registrar("ecos", (j, r) -> stat(j, "ecos-cerrados"));
        PlaceholdersLethal.registrar("stat", (j, r) -> r == null || r.isEmpty() ? null : stat(j, r.toLowerCase(Locale.ROOT)));
        PlaceholdersLethal.registrar("top", (j, r) -> top(r));
        Autotest.registrar("ranking", this::autotest);
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

    /** Solo en el hilo principal (la tarea). Deja mapas inmutables para los otros hilos. */
    private void recalcular() {
        Map<UUID, Map<String, Long>> stats = leer(hc.datos().getConfigurationSection("stats"));
        Horas h = hc.horas();
        if (h != null) {
            for (Map.Entry<UUID, Long> e : h.todos().entrySet()) {
                stats.computeIfAbsent(e.getKey(), k -> new HashMap<>()).put("horas-activas", e.getValue());
            }
        }
        Map<UUID, Map<String, Long>> semana = leer(hc.datos().getConfigurationSection("stats-semana." + cal().semana()));
        topsTotal = tops(stats);
        topsSemana = tops(semana);
        Map<UUID, Map<String, Long>> copia = new HashMap<>();
        for (Map.Entry<UUID, Map<String, Long>> e : stats.entrySet()) copia.put(e.getKey(), Map.copyOf(e.getValue()));
        copiaStats = Map.copyOf(copia);
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

    private Map<String, List<Fila>> tops(Map<UUID, Map<String, Long>> stats) {
        Map<String, List<Fila>> porClave = new HashMap<>();
        for (Map.Entry<UUID, Map<String, Long>> e : stats.entrySet()) {
            for (Map.Entry<String, Long> v : e.getValue().entrySet()) {
                if (v.getValue() <= 0) continue;
                porClave.computeIfAbsent(v.getKey(), k -> new ArrayList<>()).add(new Fila(e.getKey(), null, v.getValue()));
            }
        }
        Map<String, List<Fila>> out = new HashMap<>();
        for (Map.Entry<String, List<Fila>> e : porClave.entrySet()) {
            List<Fila> l = e.getValue();
            l.sort(Comparator.comparingLong(Fila::valor).reversed().thenComparing(f -> f.jugador().toString()));
            List<Fila> top = new ArrayList<>();
            for (int i = 0; i < Math.min(TOP, l.size()); i++) {
                Fila f = l.get(i);
                top.add(new Fila(f.jugador(), nombre(f.jugador()), f.valor()));
            }
            out.put(e.getKey(), List.copyOf(top));
        }
        return Map.copyOf(out);
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
        Map<String, List<Fila>> tops = topsSemana;
        Map<Tabla, List<Fila>> out = new LinkedHashMap<>();
        for (String id : tablas()) {
            Tabla t = tabla(id);
            List<Fila> l = tops.getOrDefault(t.estadistica(), List.of());
            out.put(t, l.subList(0, Math.min(Math.max(0, n), l.size())));
        }
        return out;
    }

    /**
     * %lethalworld_top_<clave>_<n>_nombre|valor[_semana]%. La clave puede llevar guiones
     * (tasado-mc), no guiones bajos: se lee desde el final.
     */
    private String top(String resto) {
        if (resto == null || resto.isEmpty()) return null;
        String r = resto.toLowerCase(Locale.ROOT);
        boolean semanal = r.endsWith("_semana");
        if (semanal) r = r.substring(0, r.length() - "_semana".length());
        String[] t = r.split("_");
        if (t.length < 3) return null;
        String que = t[t.length - 1];
        int n;
        try {
            n = Integer.parseInt(t[t.length - 2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (n < 1 || n > TOP || (!que.equals("nombre") && !que.equals("valor"))) return null;
        String clave = String.join("_", java.util.Arrays.copyOfRange(t, 0, t.length - 2));
        List<Fila> l = (semanal ? topsSemana : topsTotal).get(clave);
        if (l == null || l.size() < n) return que.equals("nombre") ? "—" : "0";
        Fila f = l.get(n - 1);
        if (que.equals("nombre")) return f.nombre();
        return String.valueOf(clave.equals("horas-activas") ? f.valor() / 3600 : f.valor());
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
    private List<String> tablas() {
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

        StringBuilder texto = new StringBuilder(tablaNombre + ", " + p.puesto() + ".º: ");
        List<String> partes = new ArrayList<>();
        if (e > 0) partes.add(e + " Esencias");
        if (pr.llaves() > 0) partes.add(pr.llaves() + (pr.llaves() == 1 ? " Llave del Caos" : " Llaves del Caos"));
        if (!pr.comandos().isEmpty()) partes.add("[ÁNIMA] 7 días");
        texto.append(partes.isEmpty() ? "tu puesto" : String.join(", ", partes));
        Player online = op.getPlayer();
        if (online != null) {
            online.sendMessage(ComandoCalamity.mensaje(Component.text("Premio de la semana: ")
                    .append(Component.text(texto.toString(), Paleta.DETALLE)).append(Component.text("."))));
        } else {
            List<String> l = new ArrayList<>(hc.datos().getStringList("ranking-avisos." + p.jugador()));
            l.add(texto.toString());
            hc.datos().set("ranking-avisos." + p.jugador(), l);
            hc.marcarSucio();
        }
    }

    private boolean consola(String plantilla, String nombre) {
        if (plantilla == null || plantilla.isBlank()) return true;
        if (nombre == null || !Entregas.NOMBRE_VALIDO.matcher(nombre).matches()) return false;
        String cmd = plantilla.replace("%jugador%", nombre).trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        try {
            return hc.plugin().getServer().dispatchCommand(hc.plugin().getServer().getConsoleSender(), cmd);
        } catch (Throwable t) {
            hc.plugin().getLogger().warning("[Calamity] Fallo el comando de ranking \"" + cmd + "\": " + t);
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
                p.sendMessage(ComandoCalamity.mensaje(Component.text("Te esperaba un premio de la semana: ")
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
            quien.sendMessage(ComandoCalamity.mensaje("Sin estadisticas: el modulo no arranco."));
            return;
        }
        String semana = cal().semana();
        if (sub.equals("cerrar")) {
            if (!activo()) quien.sendMessage(ComandoCalamity.mensaje("ranking.activo esta apagado: se cierra igual porque lo pides a mano."));
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
                    + hc.cfg().getInt("ranking.minimo-extracciones", 3) + " extracciones en la semana).", Paleta.TENUE));
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
        h.igual("top sin datos: nombre", "—", top("autotest-sin-datos_1_nombre"));
        h.igual("top sin datos: valor", "0", top("autotest-sin-datos_1_valor"));
        h.igual("top con n fuera de rango", null, top("tasado-mc_11_valor"));
        h.igual("stat sin jugador", "", PlaceholdersLethal.resolver(null, "stat_parcas"));
        h.ok("autotest no toca stats-semana reales", !hc.datos().isSet("stats-semana." + sem + "." + uno));
        h.ok("/lw hardcore ranking registrado", Subcomandos.lw().nombres(null).contains("ranking"));
        return h.lineas();
    }
}
