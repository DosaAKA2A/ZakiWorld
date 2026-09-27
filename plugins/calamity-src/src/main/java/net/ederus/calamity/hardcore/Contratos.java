package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M14 · Contratos del Umbral (DIS M14, EST sec. 5.9): tres encargos al dia que solo se cobran
 * al salir vivo.
 *
 * Cada dia (00:00 en hardcore.zona) se sortean contratos.por-dia (3) del pool, al menos
 * cortos-garantizados (1) de los cortos: el corto es el que hace que merezca la pena entrar
 * veinte minutos. Un contrato cuya mecanica esta apagada (clave "mecanica" del pool) no entra
 * en el sorteo: pedir "abre 3 cofres" con los cofres vacios seria un encargo imposible.
 *
 * El progreso es de la EXPEDICION: se pone a cero al entrar y al morir, y lo cumplido solo se
 * cobra en la Tasacion (cobrarEnTasacion, que llama Tasacion.tasar antes del teleport). Morir
 * con un contrato cumplido es perderlo; el contrato sigue ahi para la siguiente.
 *
 * La lista de una expedicion se fija al entrar: pasar las 00:00 dentro no borra lo que llevas
 * (el sorteo del dia nuevo espera a la siguiente entrada o a que lo mires desde fuera).
 *
 * De donde sale cada evento (los nombres del pool):
 *   mob, destacado     Grifo, solo mobs que pasan los puntos 1-3 (via NORMAL)
 *   minijefe           Minijefes, al asesino
 *   cofre              Cofres, cofre de estructura abierto por un jugador que cuenta
 *   reliquia-ii        Tasacion ("tasa-ii" = cuantas de grado II o mas saca)
 *   minutos            1 por minuto dentro (via Horas.segundo)
 *   minutos-limite     1 por minuto con cordura < contratos.cordura-limite (25)
 *   minutos-sin-frasco 1 por minuto sin beber; beber lo pone a cero si no estaba cumplido
 *   eco-valido         stats.cazas-validas que suben durante la expedicion
 *   redimir            stats.ecos-redimidos que suben durante la expedicion
 *
 * Datos: contratos.<uuid> = {dia, lista.<1-3> = {id, progreso, cumplido, cobrado}, cambios,
 * base.<clave>, semana, cobrados-semana, premio-semana, avisado}.
 */
final class Contratos implements Listener {

    static final TextColor VERDE_PALIDO = Paleta.DETALLE;
    static final TextColor AMBAR = TextColor.color(0xE8A33D);

    /** Lo que emiten otros modulos con otro nombre. */
    private static final Map<String, String> ALIAS = Map.of("tasa-ii", "reliquia-ii");
    /** Eventos que salen de una estadistica que sube durante la expedicion. */
    private static final Map<String, String> POR_ESTADISTICA = Map.of(
            "eco-valido", "cazas-validas",
            "redimir", "ecos-redimidos");
    /** "lw hardcore dar llave %jugador% N" en semana-premio: la llave va por Entregas con origen contratos. */
    private static final Pattern LLAVE = Pattern.compile("^/?lw hardcore dar llave %jugador% (\\d+)$");

    record Def(String id, String texto, String evento, int objetivo, int esencias, long mobcoins,
               boolean corto, String mecanica) {
    }

    record Avance(List<Integer> cambiados, List<Integer> cumplidos) {
    }

    private final Hardcore hc;
    private final SecureRandom azar = new SecureRandom();
    /** Segundos de la expedicion: {dentro, con cordura baja, sin beber}. Se limpia en el quit. */
    private final Map<UUID, int[]> reloj = new HashMap<>();
    /** Ultimo destello P-O01 por jugador: con mobs cayendo de dos en dos no se pisa la barra. */
    private final Map<UUID, Long> ultimoDestello = new HashMap<>();
    private final Set<BukkitTask> tareas = new HashSet<>();
    private Map<String, Def> poolLeido;
    private long poolLeidoEn;

    Contratos(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Subcomandos.lw().registrar("contratos", "contratos <jugador> [reset]: ver o volver a sortear sus contratos (M14)",
                "ederus.mundos", this::comandoAdmin,
                args -> switch (args.length) {
                    case 2 -> Entregas.nombresConectados();
                    case 3 -> List.of("reset");
                    default -> List.of();
                });
        Subcomandos.calamity().registrar("contratos", "tus contratos de hoy (se cobran al salir vivo)",
                "lethalworld.calamity", (quien, args) -> {
                    if (quien instanceof Player p) mostrar(p, p);
                    else quien.sendMessage(ComandoCalamity.mensaje("Solo para jugadores."));
                }, null);
        Subcomandos.calamity().registrar("cambiar", "cambiar <1-3>: cambia un contrato (uno gratis al día)",
                "lethalworld.calamity", (quien, args) -> {
                    if (!(quien instanceof Player p)) {
                        quien.sendMessage(ComandoCalamity.mensaje("Solo para jugadores."));
                        return;
                    }
                    int i;
                    try {
                        i = Integer.parseInt(args.length > 1 ? args[1] : "");
                    } catch (NumberFormatException e) {
                        p.sendMessage(ComandoCalamity.mensaje("Uso: /calamity cambiar <1-3>"));
                        return;
                    }
                    cambiar(p, i);
                }, args -> args.length == 2 ? List.of("1", "2", "3") : List.of());
        Autotest.registrar("contratos", this::autotest);
    }

    boolean activo() {
        return hc.cfg().getBoolean("contratos.activo", false);
    }

    // ------------------------------------------------------------------ el pool

    /**
     * El pool de la config; si no hay, el de EST sec. 5.9 escrito aqui. Se relee como mucho
     * cada 10 s: lo pide cada mob muerto dentro y un /lw reload se nota igual enseguida.
     */
    Map<String, Def> pool() {
        long ahora = System.currentTimeMillis();
        if (poolLeido != null && ahora - poolLeidoEn < 10_000L) return poolLeido;
        poolLeido = leerPool();
        poolLeidoEn = ahora;
        return poolLeido;
    }

    private Map<String, Def> leerPool() {
        Map<String, Def> out = new LinkedHashMap<>();
        ConfigurationSection s = hc.cfg().getConfigurationSection("contratos.pool");
        if (s != null) {
            for (String id : s.getKeys(false)) {
                ConfigurationSection d = s.getConfigurationSection(id);
                if (d == null || !d.getBoolean("activo", true)) continue;
                out.put(id, new Def(id, d.getString("texto", id), d.getString("evento", ""),
                        Math.max(1, d.getInt("objetivo", 1)), Math.max(0, d.getInt("esencias", 0)),
                        Math.max(0, d.getLong("mobcoins", 0)), d.getBoolean("corto", false), d.getString("mecanica", "")));
            }
        }
        if (out.isEmpty()) for (Def d : POR_DEFECTO) out.put(d.id(), d);
        return java.util.Collections.unmodifiableMap(out);
    }

    static final List<Def> POR_DEFECTO = List.of(
            new Def("corto-mobs", "Mata 10 mobs y sal", "mob", 10, 2, 20, true, ""),
            new Def("corto-cofre", "Abre 1 cofre de estructura y sal", "cofre", 1, 2, 20, true, "cofres.activo"),
            new Def("corto-reliquia", "Saca 1 Reliquia de grado II o más", "reliquia-ii", 1, 2, 20, true, "reliquias.activas"),
            new Def("corto-15", "Sal vivo tras 15 min dentro", "minutos", 15, 2, 20, true, ""),
            new Def("extraer-ii", "Saca 3 Reliquias de grado II o más en una salida", "reliquia-ii", 3, 4, 60, false, "reliquias.activas"),
            new Def("destacados", "Mata 5 mobs destacados y sal", "destacado", 5, 3, 40, false, ""),
            new Def("eco", "Cierra un Eco ajeno válido y sal", "eco-valido", 1, 5, 80, false, "eco.activo"),
            new Def("al-limite", "Aguanta 15 min con cordura por debajo de 25 y sal", "minutos-limite", 15, 5, 80, false, ""),
            new Def("minijefe", "Mata un minijefe y sal", "minijefe", 1, 6, 100, false, ""),
            new Def("sin-frasco", "Pasa 30 min dentro sin beber y sal", "minutos-sin-frasco", 30, 4, 60, false, ""),
            new Def("cofres", "Abre 3 cofres de estructura y sal", "cofre", 3, 3, 40, false, "cofres.activo"),
            new Def("redimir", "Redime tu Eco y sal", "redimir", 1, 4, 60, false, "eco.activo"));

    /**
     * Si el contrato puede salir: su interruptor (mecanica) encendido y, para los de cofres,
     * que los cofres no esten vaciados por la regla de dificultad (entonces no llega ningun
     * evento "cofre").
     */
    boolean disponible(Def d) {
        String m = d.mecanica();
        if (m != null && !m.isBlank() && !hc.cfg().getBoolean(m, true)) return false;
        if ("cofre".equals(d.evento()) && hc.cfg().getBoolean("dificultad.cofres-vacios", true)) return false;
        return true;
    }

    /**
     * El sorteo del dia: primero los cortos garantizados, luego el resto de todo lo que quede
     * (puede salir otro corto). Sin repetir. Si el pool no llega, salen los que haya.
     */
    static List<Def> sortear(Collection<Def> pool, int porDia, int cortos, Predicate<Def> disponible, SecureRandom azar) {
        List<Def> libres = new ArrayList<>();
        for (Def d : pool) if (disponible.test(d)) libres.add(d);
        List<Def> out = new ArrayList<>();
        List<Def> soloCortos = new ArrayList<>();
        for (Def d : libres) if (d.corto()) soloCortos.add(d);
        for (int i = 0; i < cortos && out.size() < porDia && !soloCortos.isEmpty(); i++) {
            Def d = soloCortos.remove(azar.nextInt(soloCortos.size()));
            out.add(d);
            libres.remove(d);
        }
        while (out.size() < porDia && !libres.isEmpty()) out.add(libres.remove(azar.nextInt(libres.size())));
        return out;
    }

    /** Uno nuevo para un hueco: ninguno de los que ya tiene; corto si hace falta para mantener la garantia. */
    static Def sustituto(Collection<Def> pool, Set<String> yaEstan, boolean hadeSerCorto, Predicate<Def> disponible,
                         SecureRandom azar) {
        List<Def> libres = new ArrayList<>();
        for (Def d : pool) {
            if (yaEstan.contains(d.id()) || !disponible.test(d)) continue;
            if (hadeSerCorto && !d.corto()) continue;
            libres.add(d);
        }
        return libres.isEmpty() ? null : libres.get(azar.nextInt(libres.size()));
    }

    // ------------------------------------------------------------ la libreta (nucleo)

    /** Escribe la lista del dia en la seccion del jugador (y pone los cambios a cero). */
    static void escribirLista(ConfigurationSection s, String dia, List<Def> lista) {
        s.set("dia", dia);
        s.set("lista", null);
        s.set("cambios", 0);
        for (int i = 0; i < lista.size(); i++) ponerEn(s, i + 1, lista.get(i).id());
    }

    static void ponerEn(ConfigurationSection s, int i, String id) {
        String r = "lista." + i;
        s.set(r + ".id", id);
        s.set(r + ".progreso", 0);
        s.set(r + ".cumplido", false);
        s.set(r + ".cobrado", false);
    }

    static List<Integer> huecos(ConfigurationSection s) {
        List<Integer> out = new ArrayList<>();
        ConfigurationSection l = s.getConfigurationSection("lista");
        if (l == null) return out;
        for (String k : l.getKeys(false)) {
            try {
                out.add(Integer.parseInt(k));
            } catch (NumberFormatException ignorado) {
                // Una clave rara no es un hueco.
            }
        }
        out.sort(Integer::compare);
        return out;
    }

    /** Suma n al progreso de los contratos no cobrados de ese evento. No paga nada. */
    static Avance avanzar(ConfigurationSection s, Map<String, Def> pool, String evento, int n) {
        String ev = ALIAS.getOrDefault(evento, evento);
        List<Integer> cambiados = new ArrayList<>(), cumplidos = new ArrayList<>();
        for (int i : huecos(s)) {
            String r = "lista." + i;
            Def d = pool.get(s.getString(r + ".id", ""));
            if (d == null || !d.evento().equals(ev) || s.getBoolean(r + ".cobrado", false)
                    || s.getBoolean(r + ".cumplido", false)) continue;
            int nuevo = Math.min(d.objetivo(), s.getInt(r + ".progreso", 0) + n);
            s.set(r + ".progreso", nuevo);
            cambiados.add(i);
            if (nuevo >= d.objetivo()) {
                s.set(r + ".cumplido", true);
                cumplidos.add(i);
            }
        }
        return new Avance(cambiados, cumplidos);
    }

    /** Los huecos cumplidos y sin cobrar. */
    static List<Integer> cobrables(ConfigurationSection s) {
        List<Integer> out = new ArrayList<>();
        for (int i : huecos(s)) {
            String r = "lista." + i;
            if (s.getBoolean(r + ".cumplido", false) && !s.getBoolean(r + ".cobrado", false)) out.add(i);
        }
        return out;
    }

    /** Expedicion nueva o muerte: lo no cobrado vuelve a cero (el contrato sigue). */
    static void reiniciarExpedicion(ConfigurationSection s) {
        for (int i : huecos(s)) {
            String r = "lista." + i;
            if (s.getBoolean(r + ".cobrado", false)) continue;
            s.set(r + ".progreso", 0);
            s.set(r + ".cumplido", false);
        }
    }

    /** Pone a cero los de un evento que aun no se cumplieron (beber rompe el "sin frasco"). */
    static void romper(ConfigurationSection s, Map<String, Def> pool, String evento) {
        for (int i : huecos(s)) {
            String r = "lista." + i;
            Def d = pool.get(s.getString(r + ".id", ""));
            if (d == null || !d.evento().equals(evento) || s.getBoolean(r + ".cumplido", false)) continue;
            s.set(r + ".progreso", 0);
        }
    }

    // ------------------------------------------------------------------ datos

    private String hoy() {
        Calendario c = hc.calendario();
        return c != null ? c.dia() : new Calendario(hc).dia();
    }

    private String semana() {
        Calendario c = hc.calendario();
        return c != null ? c.semana() : new Calendario(hc).semana();
    }

    private ConfigurationSection seccion(UUID u) {
        ConfigurationSection s = hc.datos().getConfigurationSection("contratos." + u);
        return s != null ? s : hc.datos().createSection("contratos." + u);
    }

    /**
     * La libreta del jugador. Sortea si esta vacia o si es de otro dia y se puede (fuera de
     * Calamity o al entrar): dentro, la lista de la expedicion no cambia a medianoche.
     */
    private ConfigurationSection libreta(UUID u, boolean puedeSortear) {
        ConfigurationSection s = seccion(u);
        boolean vacia = huecos(s).isEmpty();
        if (vacia || (puedeSortear && !hoy().equals(s.getString("dia", "")))) {
            ConfigurationSection c = hc.cfg();
            List<Def> lista = sortear(pool().values(), Math.max(1, c.getInt("contratos.por-dia", 3)),
                    Math.max(0, c.getInt("contratos.cortos-garantizados", 1)), this::disponible, azar);
            escribirLista(s, hoy(), lista);
            s.set("base", null);
            hc.marcarSucio();
            List<String> ids = new ArrayList<>();
            for (Def d : lista) ids.add(d.id());
            hc.plugin().bitacora().anotar("contrato", "sorteo", Saldo.nombre(u), String.join(",", ids));
        }
        return s;
    }

    // ------------------------------------------------------------------ ganchos

    /** Al entrar (Hardcore.meter): expedicion nueva, foto de las estadisticas y P-O04. */
    void alEntrar(Player p) {
        if (!activo() || p == null) return;
        UUID u = p.getUniqueId();
        ConfigurationSection s = libreta(u, true);
        reiniciarExpedicion(s);
        Estadisticas st = hc.estadisticas();
        s.set("base", null);
        if (st != null) for (String clave : POR_ESTADISTICA.values()) s.set("base." + clave, st.de(u, clave));
        reloj.put(u, new int[3]);
        hc.marcarSucio();

        String dia = hoy();
        boolean queda = false;
        for (int i : huecos(s)) if (!s.getBoolean("lista." + i + ".cobrado", false)) queda = true;
        if (queda && !dia.equals(s.getString("avisado", ""))) {
            s.set("avisado", dia);
            // Dos segundos despues: al entrar llueven mensajes (bienvenida, Ecos, altar).
            final BukkitTask[] t = new BukkitTask[1];
            t[0] = hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(), () -> {
                tareas.remove(t[0]);
                if (p.isOnline()) {
                    p.sendMessage(ComandoCalamity.mensaje(Component.text("El Tasador tiene trabajo para ti. ")
                            .append(Component.text("/calamity contratos", Paleta.DETALLE))));
                }
            }, 40L);
            tareas.add(t[0]);
        }
    }

    /** Lo llaman Grifo, Minijefes, Cofres y Tasacion. Solo cuenta dentro. */
    void progreso(Player p, String evento, int n) {
        if (!activo() || p == null || evento == null || n <= 0 || !hc.esHardcore(p)) return;
        UUID u = p.getUniqueId();
        ConfigurationSection s = libreta(u, false);
        Map<String, Def> pool = pool();
        Avance a = avanzar(s, pool, evento, n);
        if (a.cambiados().isEmpty()) return;
        hc.marcarSucio();
        for (int i : a.cumplidos()) {
            Def d = pool.get(s.getString("lista." + i + ".id", ""));
            if (d == null) continue;
            p.sendMessage(ComandoCalamity.mensaje("Contrato cumplido. Se cobra al salir."));
            hc.plugin().bitacora().anotar("contrato", "cumplido", p.getName(), d.id());
            telemetria(p, d, "cumplido", 0, 0);
        }
        if (a.cumplidos().isEmpty()) destello(p, s, pool, a.cambiados().get(0));
    }

    /** P-O01, como mucho uno cada 2 s. */
    private void destello(Player p, ConfigurationSection s, Map<String, Def> pool, int i) {
        long ahora = System.currentTimeMillis();
        Long antes = ultimoDestello.get(p.getUniqueId());
        if (antes != null && ahora - antes < 2000) return;
        Def d = pool.get(s.getString("lista." + i + ".id", ""));
        if (d == null) return;
        ultimoDestello.put(p.getUniqueId(), ahora);
        hc.cordura().destello(p, Component.text("Contrato · ", VERDE_PALIDO)
                .append(Component.text(d.texto() + " ", Paleta.TEXTO))
                .append(Component.text(s.getInt("lista." + i + ".progreso", 0) + "/" + d.objetivo(), Paleta.CIFRA)), 2);
    }

    /** Cada segundo dentro (desde Horas.segundo): los contratos de tiempo. */
    void segundo(Player p) {
        if (!activo()) return;
        UUID u = p.getUniqueId();
        int[] c = reloj.computeIfAbsent(u, k -> new int[3]);
        if (++c[0] % 60 == 0) progreso(p, "minutos", 1);
        Cordura.Estado e = hc.cordura().todos().get(u);
        if (e != null && e.valor < hc.cfg().getDouble("contratos.cordura-limite", 25) && ++c[1] % 60 == 0) {
            progreso(p, "minutos-limite", 1);
        }
        if (++c[2] % 60 == 0) progreso(p, "minutos-sin-frasco", 1);
    }

    /**
     * Una estadistica que ha cambiado (lo reenvia Hitos.revisar): las cazas validas y los Ecos
     * redimidos cuentan lo que suben desde la foto de la entrada.
     */
    void estadistica(UUID u, String clave) {
        if (u == null || clave == null || !POR_ESTADISTICA.containsValue(clave) || !activo()) return;
        Player p = Bukkit.getPlayer(u);
        Estadisticas st = hc.estadisticas();
        if (p == null || st == null || !hc.esHardcore(p)) return;
        ConfigurationSection s = seccion(u);
        long base = s.getLong("base." + clave, -1);
        if (base < 0) return;
        long ahora = st.de(u, clave);
        if (ahora <= base) return;
        s.set("base." + clave, ahora);
        for (Map.Entry<String, String> en : POR_ESTADISTICA.entrySet()) {
            if (en.getValue().equals(clave)) progreso(p, en.getKey(), (int) Math.min(Integer.MAX_VALUE, ahora - base));
        }
    }

    /**
     * Beber del Frasco rompe el "sin beber". LOWEST y sin cancelar: se mira lo mismo que mira
     * Hardcore.onUsar antes de dar el trago (mano principal, clic derecho, frasco con tragos).
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void alBeber(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !e.getAction().isRightClick()) return;
        ItemStack it = e.getItem();
        if (it == null || !activo()) return;
        Player p = e.getPlayer();
        if (!hc.esHardcore(p) || hc.items().tragos(it) <= 0) return;
        int[] c = reloj.get(p.getUniqueId());
        if (c != null) c[2] = 0;
        ConfigurationSection s = hc.datos().getConfigurationSection("contratos." + p.getUniqueId());
        if (s == null) return;
        romper(s, pool(), "minutos-sin-frasco");
        hc.marcarSucio();
    }

    /** Morir es perder lo de esta expedicion (MONITOR: si otro cancela la muerte, no pasa nada). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alMorir(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!activo() || !hc.esHardcore(p)) return;
        reloj.remove(p.getUniqueId());
        ConfigurationSection s = hc.datos().getConfigurationSection("contratos." + p.getUniqueId());
        if (s == null) return;
        reiniciarExpedicion(s);
        s.set("base", null);
        hc.marcarSucio();
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        reloj.remove(e.getPlayer().getUniqueId());
        ultimoDestello.remove(e.getPlayer().getUniqueId());
    }

    /**
     * Ids de los contratos cumplidos que se cobran en esta tasacion. Paga cada uno por la
     * Aduana (tipo contratos, tope diario 3): se marca cobrado y se guarda ANTES de pagar.
     */
    List<String> cobrarEnTasacion(Player p) {
        if (!activo() || p == null) return List.of();
        UUID u = p.getUniqueId();
        ConfigurationSection s = hc.datos().getConfigurationSection("contratos." + u);
        if (s == null) return List.of();
        List<Integer> cobrar = cobrables(s);
        if (cobrar.isEmpty()) return List.of();
        Map<String, Def> pool = pool();
        for (int i : cobrar) s.set("lista." + i + ".cobrado", true);
        String sem = semana();
        if (!sem.equals(s.getString("semana", ""))) {
            s.set("semana", sem);
            s.set("cobrados-semana", 0);
        }
        s.set("cobrados-semana", s.getInt("cobrados-semana", 0) + cobrar.size());
        hc.guardarYa();

        List<String> ids = new ArrayList<>();
        Aduana ad = hc.aduana();
        Estadisticas st = hc.estadisticas();
        for (int i : cobrar) {
            String id = s.getString("lista." + i + ".id", "?");
            ids.add(id);
            Def d = pool.get(id);
            int e = d == null ? 0 : d.esencias();
            long mc = d == null ? 0 : d.mobcoins();
            Aduana.Pago pago = ad == null ? null : ad.pagar(p, "contratos", e, mc, List.of(), "contrato:" + id);
            int pe = pago == null ? 0 : pago.esencias();
            long pmc = pago == null ? 0 : pago.mc();
            if (st != null) st.sumar(u, "contratos", 1);
            hc.plugin().bitacora().anotar("contrato", "cobrado", p.getName(), id, "e " + pe, "mc " + pmc);
            telemetria(p, d == null ? new Def(id, id, "", 1, 0, 0, false, "") : d, "cobrado", pe, pmc);
            p.sendMessage(ComandoCalamity.mensaje(Component.text("Contrato cobrado: ")
                    .append(Component.text(d == null ? id : d.texto(), Paleta.DETALLE))
                    .append(Component.text(". "))
                    .append(Component.text("+" + pe, Paleta.CIFRA)).append(Component.text(" Esencias, "))
                    .append(Component.text("+" + pmc, Paleta.CIFRA)).append(Component.text(" MobCoins."))));
        }
        premioSemana(p, s, sem);
        return ids;
    }

    /** semana-objetivo (12) cobrados en la semana: una vez por semana, la Llave del Caos. */
    private void premioSemana(Player p, ConfigurationSection s, String sem) {
        int objetivo = Math.max(1, hc.cfg().getInt("contratos.semana-objetivo", 12));
        if (s.getInt("cobrados-semana", 0) < objetivo || sem.equals(s.getString("premio-semana", ""))) return;
        s.set("premio-semana", sem);
        hc.guardarYa();
        List<String> premio = hc.cfg().getStringList("contratos.semana-premio");
        if (premio.isEmpty() && !hc.cfg().isSet("contratos.semana-premio")) premio = List.of("lw hardcore dar llave %jugador% 1");
        for (String plantilla : premio) {
            Matcher m = LLAVE.matcher(plantilla.trim());
            if (m.matches()) {
                // Por Entregas y no por el comando: asi la llave cuenta en el tope con origen "contratos".
                Entregas en = hc.entregas();
                int n = Integer.parseInt(m.group(1));
                int dadas = en == null ? -1 : en.llave(p, n, "contratos", true);
                hc.plugin().bitacora().anotar("contrato", "semana", p.getName(), "llave " + n, "dadas " + dadas);
            } else {
                boolean ok = consola(plantilla, p.getName());
                hc.plugin().bitacora().anotar("contrato", "semana", p.getName(), plantilla, ok ? "ok" : "fallo");
            }
        }
        p.sendMessage(ComandoCalamity.mensaje(Component.text(objetivo + " contratos esta semana. ")
                .append(Component.text("El Tasador te da una Llave del Caos.", Paleta.DETALLE))));
        telemetria(p, new Def("semana", "semana", "", objetivo, 0, 0, false, ""), "semana", 0, 0);
    }

    private boolean consola(String plantilla, String nombre) {
        if (plantilla == null || plantilla.isBlank()) return true;
        if (nombre == null || !Entregas.NOMBRE_VALIDO.matcher(nombre).matches()) return false;
        String cmd = plantilla.replace("%jugador%", nombre).trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        try {
            return hc.plugin().getServer().dispatchCommand(hc.plugin().getServer().getConsoleSender(), cmd);
        } catch (Throwable t) {
            hc.plugin().getLogger().warning("[Calamity] Fallo el comando de contrato \"" + cmd + "\": " + t);
            return false;
        }
    }

    private void telemetria(OfflinePlayer p, Def d, String estado, int e, long mc) {
        Telemetria t = hc.telemetria();
        if (t == null) return;
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("id", d.id());
        campos.put("estado", estado);
        campos.put("corto", d.corto());
        if (e > 0) campos.put("esencias", e);
        if (mc > 0) campos.put("mc", mc);
        hc.seguro("telemetria", () -> t.suceso("contrato", p, campos));
    }

    // ------------------------------------------------------------------ jugador

    /** /calamity contratos (y el boton del Altar): los tres de hoy y como van. */
    void mostrar(CommandSender a, Player p) {
        if (!activo()) {
            a.sendMessage(ComandoCalamity.mensaje("El Tasador no tiene contratos ahora mismo."));
            return;
        }
        UUID u = p.getUniqueId();
        ConfigurationSection s = libreta(u, !hc.esHardcore(p));
        Map<String, Def> pool = pool();
        a.sendMessage(ComandoCalamity.mensaje("Contratos de hoy. Se cobran al salir vivo."));
        for (int i : huecos(s)) {
            String r = "lista." + i;
            Def d = pool.get(s.getString(r + ".id", ""));
            if (d == null) continue;
            boolean cobrado = s.getBoolean(r + ".cobrado", false), cumplido = s.getBoolean(r + ".cumplido", false);
            Component estado = cobrado ? Component.text("cobrado", VERDE_PALIDO)
                    : cumplido ? Component.text("cumplido, se cobra al salir", AMBAR)
                    : Component.text(s.getInt(r + ".progreso", 0) + "/" + d.objetivo(), Paleta.CIFRA);
            a.sendMessage(Component.text("  " + i + ". ", Paleta.TENUE)
                    .append(Component.text(d.texto(), cobrado ? Paleta.TENUE : Paleta.TEXTO))
                    .append(Component.text(" · ", Paleta.SEPARADOR)).append(estado)
                    .append(Component.text(" · " + d.esencias() + " E + " + d.mobcoins() + " MC"
                            + (d.corto() ? " · corto" : ""), Paleta.TENUE)));
        }
        int gratis = Math.max(0, hc.cfg().getInt("contratos.cambios-gratis", 1) - s.getInt("cambios", 0));
        int precio = Math.max(0, hc.cfg().getInt("contratos.precio-cambio", 1));
        a.sendMessage(Component.text("  " + (gratis > 0 ? "Te queda " + gratis + " cambio gratis hoy."
                : "Cambiar uno cuesta " + precio + (precio == 1 ? " Esencia." : " Esencias."))
                + " /calamity cambiar <1-3>", Paleta.TENUE));
        int objetivo = Math.max(1, hc.cfg().getInt("contratos.semana-objetivo", 12));
        int hechos = semana().equals(s.getString("semana", "")) ? s.getInt("cobrados-semana", 0) : 0;
        a.sendMessage(Component.text("  Esta semana: " + Math.min(hechos, objetivo) + "/" + objetivo
                + " cobrados para la Llave del Caos.", Paleta.TENUE));
    }

    /** Un contrato de hoy tal y como lo pinta el menu del Tasador. */
    record Estado(int hueco, Def def, int progreso, boolean cumplido, boolean cobrado) {
    }

    /**
     * Lo mismo que mostrar() escribe en el chat, para el menu del Tasador: los contratos de hoy
     * (con el mismo sorteo: fuera de Calamity, si la libreta es de otro dia), sin pagar nada.
     */
    List<Estado> estados(Player p) {
        ConfigurationSection s = libreta(p.getUniqueId(), !hc.esHardcore(p));
        Map<String, Def> pool = pool();
        List<Estado> out = new ArrayList<>();
        for (int i : huecos(s)) {
            String r = "lista." + i;
            Def d = pool.get(s.getString(r + ".id", ""));
            if (d == null) continue;
            out.add(new Estado(i, d, s.getInt(r + ".progreso", 0), s.getBoolean(r + ".cumplido", false),
                    s.getBoolean(r + ".cobrado", false)));
        }
        return out;
    }

    /** Cambios gratis que le quedan hoy (los de la libreta ya sorteada). */
    int cambiosGratis(UUID u) {
        return Math.max(0, hc.cfg().getInt("contratos.cambios-gratis", 1) - seccion(u).getInt("cambios", 0));
    }

    /** Lo que cuesta cambiar uno ahora mismo: 0 mientras quede cambio gratis (como en cambiar()). */
    int precioCambio(UUID u) {
        return cambiosGratis(u) > 0 ? 0 : Math.max(0, hc.cfg().getInt("contratos.precio-cambio", 1));
    }

    /** {cobrados esta semana, los que pide el premio de la semana}. */
    int[] semanaDe(UUID u) {
        ConfigurationSection s = seccion(u);
        int objetivo = Math.max(1, hc.cfg().getInt("contratos.semana-objetivo", 12));
        int hechos = semana().equals(s.getString("semana", "")) ? s.getInt("cobrados-semana", 0) : 0;
        return new int[]{hechos, objetivo};
    }

    /** /calamity cambiar <1-3> (y el trueque del Altar). True si se cambio. */
    boolean cambiar(Player p, int i) {
        if (!activo()) {
            p.sendMessage(ComandoCalamity.mensaje("El Tasador no tiene contratos ahora mismo."));
            return false;
        }
        UUID u = p.getUniqueId();
        ConfigurationSection s = libreta(u, !hc.esHardcore(p));
        String r = "lista." + i;
        if (!s.isSet(r + ".id")) {
            p.sendMessage(ComandoCalamity.mensaje("Uso: /calamity cambiar <1-3>"));
            return false;
        }
        if (s.getBoolean(r + ".cobrado", false)) {
            p.sendMessage(ComandoCalamity.mensaje("Ese ya está cobrado."));
            return false;
        }
        if (s.getBoolean(r + ".cumplido", false)) {
            p.sendMessage(ComandoCalamity.mensaje("Ese ya está cumplido. Se cobra al salir."));
            return false;
        }
        Map<String, Def> pool = pool();
        Set<String> ya = new HashSet<>();
        int cortosOtros = 0;
        for (int k : huecos(s)) {
            String id = s.getString("lista." + k + ".id", "");
            ya.add(id);
            Def d = pool.get(id);
            if (k != i && d != null && d.corto()) cortosOtros++;
        }
        boolean corto = cortosOtros < Math.max(0, hc.cfg().getInt("contratos.cortos-garantizados", 1));
        Def nuevo = sustituto(pool.values(), ya, corto, this::disponible, azar);
        if (nuevo == null) {
            p.sendMessage(ComandoCalamity.mensaje("El Tasador no tiene otro encargo para ese hueco."));
            return false;
        }
        int cambios = s.getInt("cambios", 0);
        int precio = cambios < Math.max(0, hc.cfg().getInt("contratos.cambios-gratis", 1))
                ? 0 : Math.max(0, hc.cfg().getInt("contratos.precio-cambio", 1));
        if (precio > 0) {
            Saldo sal = hc.saldo();
            if (sal == null || !sal.restar(u, precio, "contrato:cambio")) {
                p.sendMessage(ComandoCalamity.mensaje("Cambiarlo cuesta " + precio
                        + (precio == 1 ? " Esencia" : " Esencias") + " y no te llega."));
                return false;
            }
        }
        String viejo = s.getString(r + ".id", "?");
        ponerEn(s, i, nuevo.id());
        s.set("cambios", cambios + 1);
        hc.marcarSucio();
        hc.plugin().bitacora().anotar("contrato", "cambio", p.getName(), viejo + " -> " + nuevo.id(), "coste " + precio);
        telemetria(p, nuevo, "cambiado", 0, 0);
        p.sendMessage(ComandoCalamity.mensaje(Component.text("Contrato nuevo: ")
                .append(Component.text(nuevo.texto(), Paleta.DETALLE)).append(Component.text("."))));
        return true;
    }

    // ------------------------------------------------------------------ admin

    private void comandoAdmin(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(ComandoCalamity.mensaje("Uso: /lw hardcore contratos <jugador> [reset]"));
            return;
        }
        OfflinePlayer o = Entregas.buscar(args[1]);
        if (o == null) {
            quien.sendMessage(ComandoCalamity.mensaje("No encuentro a ese jugador."));
            return;
        }
        UUID u = o.getUniqueId();
        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            // El dia vuelve a empezar (sorteo y cambios); lo de la semana se queda.
            ConfigurationSection s = seccion(u);
            s.set("lista", null);
            s.set("dia", null);
            s.set("cambios", null);
            s.set("base", null);
            s.set("avisado", null);
            hc.marcarSucio();
            hc.plugin().bitacora().anotar("contrato", "reset", Entregas.nombre(o), "admin");
        }
        Player online = o.getPlayer();
        if (online != null) {
            mostrar(quien, online);
            return;
        }
        ConfigurationSection s = libreta(u, true);
        Map<String, Def> pool = pool();
        quien.sendMessage(ComandoCalamity.mensaje("Contratos de " + Entregas.nombre(o) + " (" + s.getString("dia", "?") + "):"));
        for (int i : huecos(s)) {
            String r = "lista." + i;
            Def d = pool.get(s.getString(r + ".id", ""));
            quien.sendMessage(Component.text("  " + i + ". " + s.getString(r + ".id", "?")
                    + (d == null ? "" : " · " + s.getInt(r + ".progreso", 0) + "/" + d.objetivo())
                    + (s.getBoolean(r + ".cumplido", false) ? " · cumplido" : "")
                    + (s.getBoolean(r + ".cobrado", false) ? " · cobrado" : ""), Paleta.TENUE));
        }
    }

    void parar() {
        HandlerList.unregisterAll(this);
        for (BukkitTask t : tareas) t.cancel();
        tareas.clear();
        reloj.clear();
        ultimoDestello.clear();
    }

    // ------------------------------------------------------------------ autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        Map<String, Def> pool = pool();
        SecureRandom r = new SecureRandom();

        boolean siempreCorto = true, tres = true, distintos = true;
        for (int k = 0; k < 300; k++) {
            List<Def> l = sortear(pool.values(), 3, 1, d -> true, r);
            if (l.size() != 3) tres = false;
            if (l.stream().noneMatch(Def::corto)) siempreCorto = false;
            if (l.stream().map(Def::id).distinct().count() != l.size()) distintos = false;
        }
        h.ok("300 sorteos: siempre 3", tres);
        h.ok("300 sorteos: siempre al menos un corto", siempreCorto);
        h.ok("300 sorteos: sin repetir", distintos);

        // Mecanica apagada: eco.activo en false -> ni "eco" ni "redimir".
        Predicate<Def> sinEco = d -> !"eco.activo".equals(d.mecanica());
        boolean salioEco = false, cortoSinEco = true;
        for (int k = 0; k < 300; k++) {
            List<Def> l = sortear(pool.values(), 3, 1, sinEco, r);
            for (Def d : l) if (d.id().equals("eco") || d.id().equals("redimir")) salioEco = true;
            if (l.stream().noneMatch(Def::corto)) cortoSinEco = false;
        }
        h.ok("con eco.activo apagado no sale ni eco ni redimir", !salioEco);
        h.ok("con eco.activo apagado sigue habiendo un corto", cortoSinEco);
        Predicate<Def> sinCofres = d -> !"cofre".equals(d.evento());
        boolean salioCofre = false;
        for (int k = 0; k < 300; k++) for (Def d : sortear(pool.values(), 3, 1, sinCofres, r)) if (d.evento().equals("cofre")) salioCofre = true;
        h.ok("con los cofres vacios no sale ningun contrato de cofres", !salioCofre);
        h.igual("pool de serie: 12 contratos", 12, POR_DEFECTO.size());
        h.igual("pool de serie: 4 cortos", 4L, POR_DEFECTO.stream().filter(Def::corto).count());

        // La libreta en memoria: el progreso no paga nada; solo cobrables() lo sabe.
        YamlConfiguration yml = new YamlConfiguration();
        ConfigurationSection s = yml.createSection("x");
        Map<String, Def> base = new LinkedHashMap<>();
        for (Def d : POR_DEFECTO) base.put(d.id(), d);
        escribirLista(s, "2026-09-26", List.of(base.get("corto-mobs"), base.get("extraer-ii"), base.get("sin-frasco")));
        avanzar(s, base, "mob", 9);
        h.igual("9 mobs: progreso 9", 9, s.getInt("lista.1.progreso"));
        h.ok("9 mobs: no cumplido", !s.getBoolean("lista.1.cumplido"));
        Avance a = avanzar(s, base, "mob", 5);
        h.igual("14 mobs: se queda en 10", 10, s.getInt("lista.1.progreso"));
        h.igual("14 mobs: cumplido el 1", List.of(1), a.cumplidos());
        h.ok("cumplido no es cobrado (solo se cobra en la Tasacion)", !s.getBoolean("lista.1.cobrado"));
        avanzar(s, base, "tasa-ii", 3);
        h.ok("tasa-ii cuenta como reliquia-ii", s.getBoolean("lista.2.cumplido"));
        h.igual("dos cobrables", List.of(1, 2), cobrables(s));
        reiniciarExpedicion(s);
        h.igual("morir: nada cobrable", List.of(), cobrables(s));
        h.igual("morir: progreso a cero", 0, s.getInt("lista.1.progreso"));
        avanzar(s, base, "mob", 10);
        for (int i : cobrables(s)) s.set("lista." + i + ".cobrado", true);
        reiniciarExpedicion(s);
        h.ok("lo cobrado sobrevive a la siguiente expedicion", s.getBoolean("lista.1.cobrado"));
        h.ok("lo cobrado ya no avanza", avanzar(s, base, "mob", 5).cambiados().isEmpty());
        avanzar(s, base, "minutos-sin-frasco", 20);
        romper(s, base, "minutos-sin-frasco");
        h.igual("beber pone a cero el sin frasco", 0, s.getInt("lista.3.progreso"));

        Def otro = sustituto(base.values(), Set.of("corto-mobs", "extraer-ii", "sin-frasco"), true, d -> true, r);
        h.ok("cambiar el unico corto da otro corto", otro != null && otro.corto() && !otro.id().equals("corto-mobs"));
        h.ok("autotest no toca contratos reales", !hc.datos().isSet("contratos." + Autotest.sintetico(1)));
        h.ok("/calamity contratos registrado", Subcomandos.calamity().nombres(null).contains("contratos"));
        return h.lineas();
    }
}
