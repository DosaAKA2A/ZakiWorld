package net.ederus.edm.crates;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import com.phoenixplugins.phoenixcrates.api.PhoenixCratesAPI;
import com.phoenixplugins.phoenixcrates.api.crate.CrateType;
import com.phoenixplugins.phoenixcrates.api.crate.events.CrateRewardPlayerEvent;
import com.phoenixplugins.phoenixcrates.api.crate.events.CrateRewardSelectionEvent;
import com.phoenixplugins.phoenixcrates.api.reward.BaseReward;
import com.phoenixplugins.phoenixcrates.api.reward.Reward;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;
import net.ederus.edm.comun.Bitacora;
import net.ederus.edm.crates.Calidades.Caja;
import net.ederus.edm.crates.Calidades.Calidad;
import net.ederus.edm.crates.Calidades.Premio;

/**
 * Calidades en las cajas de PhoenixCrates.
 *
 * PhoenixCrates sortea con una lista plana de pesos. Aqui se sortea en dos
 * pasos: primero la calidad (Raro, Epico... con su porcentaje) y despues el
 * premio dentro de ella (con su peso). El enganche es CrateRewardSelectionEvent,
 * que PhoenixCrates lanza cada vez que va a elegir un premio de verdad y que
 * deja a otro plugin decidir cual: la animacion, el mensaje y la entrega son los
 * suyos de siempre, con el premio que se eligio aqui.
 *
 * Ademas:
 *   - las calidades "una-vez" (Unico) no se repiten por jugador ({@link Unicos});
 *   - la vista previa de la caja pinta la calidad y la probabilidad real de cada
 *     premio ({@link Vitrina}, solo con ProtocolLib);
 *   - /edm crates info|simulate|uniques|reload para el staff.
 *
 * Una caja que no esta en el config sortea como siempre. Si el modulo se apaga,
 * PhoenixCrates vuelve a sortear con sus pesos: nada se queda a medias.
 */
public final class CratesPlugin extends Module implements Listener {

    private static final int CONFIG_VERSION = 1;

    private volatile Calidades calidades;
    private Unicos unicos;
    private Bitacora bitacora;
    private Vitrina vitrina;
    /** Para avisar una sola vez por premio sin configurar, no en cada apertura. */
    private final Set<String> avisados = new HashSet<>();
    /**
     * Unicos elegidos y aun sin entregar: jugador -> "caja:premio" -> cuando.
     * Se anotan de verdad al entregarse ({@link #alEntregar}). La reserva evita
     * que salgan dos veces en una apertura de varias llaves, y caduca sola si la
     * entrega no llega (PhoenixCrates puede volver a sortear una apertura
     * cortada: entonces el primero no se entrego y no debe contar).
     */
    private final Map<UUID, Map<String, Long>> reservas = new HashMap<>();
    private static final long RESERVA_MS = 10 * 60 * 1000L;
    private static final String ALTERNATIVA = "_alternative";

    public CratesPlugin(EDMPlugin core) {
        super(core, "crates", "EderusCrates");
    }

    @Override
    public void onEnable() {
        migrar("config.yml", CONFIG_VERSION);
        reloadConfig();
        this.bitacora = core.bitacora("crates");
        this.unicos = new Unicos(new File(getDataFolder(), "unicos.yml"), getLogger());
        this.calidades = Calidades.leer(getConfig());

        getServer().getPluginManager().registerEvents(this, this);
        if (getServer().getPluginManager().getPlugin("ProtocolLib") != null) {
            this.vitrina = new Vitrina(this);
            this.vitrina.encender();
        } else {
            getLogger().info("[crates] Sin ProtocolLib: la vista previa no muestra las calidades.");
        }

        /* PhoenixCrates carga sus cajas despues que nosotros (load: POSTWORLD y
         * arranca tarde): la comprobacion contra sus premios va al primer tic. */
        getServer().getScheduler().runTask(core, () -> informe(null));
    }

    @Override
    public void onDisable() {
        if (this.vitrina != null) {
            this.vitrina.apagar();
            this.vitrina = null;
        }
        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);
    }

    @Override
    public String recargar() {
        reloadConfig();
        this.calidades = Calidades.leer(getConfig());
        /* unicos.yml NO se relee: si un guardado fallo, lo que hay en memoria es
         * lo bueno y releer el disco dejaria repetir un Unico. */
        this.avisados.clear();
        if (this.vitrina != null) this.vitrina.olvidarCajas();
        int problemas = informe(null);
        return "Crates recargado: " + this.calidades.cajas().size() + " caja(s) con calidades"
                + (problemas == 0 ? "." : ", " + problemas + " aviso(s) en la consola.");
    }

    Calidades calidades() {
        return this.calidades;
    }

    Unicos unicos() {
        return this.unicos;
    }

    /* ------------------------------------------------------------------ */
    /* El sorteo                                                           */
    /* ------------------------------------------------------------------ */

    /** El premio del que sale una alternativa de PhoenixCrates ("<id>_alternative"). */
    static String base(String id) {
        return id != null && id.endsWith(ALTERNATIVA) ? id.substring(0, id.length() - ALTERNATIVA.length()) : id;
    }

    /** Si el jugador ya tiene (o tiene reservado) este premio de una vez. */
    boolean bloqueado(UUID jugador, String caja, String premio) {
        if (jugador == null) return false;
        if (this.unicos.tiene(jugador, caja, premio)) return true;
        Map<String, Long> r = this.reservas.get(jugador);
        if (r == null) return false;
        Long cuando = r.get(caja + ":" + premio);
        if (cuando == null) return false;
        if (System.currentTimeMillis() - cuando > RESERVA_MS) {
            r.remove(caja + ":" + premio);
            if (r.isEmpty()) this.reservas.remove(jugador);
            return false;
        }
        return true;
    }

    /** Si este premio es de una vez y el jugador ya no puede sacarlo. */
    private boolean unicoAgotado(Calidades c, Caja caja, UUID jugador, String id) {
        Premio p = caja.premio(base(id));
        Calidad q = p == null ? null : c.calidad(p.calidad());
        return q != null && q.unaVez() && bloqueado(jugador, caja.id(), p.id());
    }

    /** Si este premio puede salir con calidades para este jugador. */
    private boolean sorteable(Calidades c, Caja caja, UUID jugador, String id) {
        Premio p = caja.premio(base(id));
        return p != null && c.calidad(p.calidad()) != null && !unicoAgotado(c, caja, jugador, id);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void alSortear(CrateRewardSelectionEvent e) {
        if (e.isHandled()) return;
        Calidades c = this.calidades;
        Caja caja = c.caja(e.getCrate().getType().getIdentifier());
        if (caja == null) return;

        Player jugador = e.getPlayer();
        UUID uuid = jugador == null ? null : jugador.getUniqueId();
        /* Por el premio base: la alternativa de un premio agotado lleva su calidad. */
        Map<String, BaseReward> candidatos = new LinkedHashMap<>();
        for (BaseReward r : e.getCandidates()) {
            String id = r.getIdentifier();
            if (caja.premio(base(id)) == null && this.avisados.add(caja.id() + ":" + id)) {
                getLogger().warning("[crates] La caja " + caja.id() + " tiene el premio " + id
                        + " sin calidad en el config de EDM: no saldra nunca. Anadelo o apagalo en PhoenixCrates.");
            }
            if (sorteable(c, caja, uuid, id)) candidatos.put(base(id), r);
        }

        String elegido = Sorteo.elegir(caja, candidatos.keySet(), () -> ThreadLocalRandom.current().nextDouble());
        if (elegido == null) {
            /* Nada que sortear con calidades. PhoenixCrates sortea con sus pesos;
             * se le quitan los Unicos ya sacados mientras quede algo, porque una
             * lista vacia lo dejaria sin premio. */
            try {
                List<? extends BaseReward> lista = e.getCandidates();
                if (lista.stream().anyMatch(r -> !unicoAgotado(c, caja, uuid, r.getIdentifier()))) {
                    lista.removeIf(r -> unicoAgotado(c, caja, uuid, r.getIdentifier()));
                }
            } catch (UnsupportedOperationException ignored) {
                // Lista de solo lectura: sortea PhoenixCrates con todo.
            }
            getLogger().warning("[crates] La caja " + caja.id() + " no tenia ningun premio con calidad disponible para "
                    + (jugador == null ? "?" : jugador.getName()) + ": sorteo PhoenixCrates.");
            return;
        }

        e.setSelectedReward(candidatos.get(elegido));
        e.setHandled(true);
        Calidad q = c.calidad(caja.premio(elegido).calidad());
        if (q.unaVez() && uuid != null) {
            this.reservas.computeIfAbsent(uuid, u -> new HashMap<>())
                    .put(caja.id() + ":" + elegido, System.currentTimeMillis());
        }
    }

    /**
     * La entrega de verdad: aqui se anota el Unico y la bitacora. Cubre tambien
     * los premios garantizados, que PhoenixCrates da sin pasar por el sorteo.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void alEntregar(CrateRewardPlayerEvent e) {
        if (e.getReward() == null || e.getCrate() == null) return;
        Calidades c = this.calidades;
        Caja caja = c.caja(e.getCrate().getType().getIdentifier());
        if (caja == null) return;
        String id = base(e.getReward().getIdentifier());
        Premio p = caja.premio(id);
        Calidad q = p == null ? null : c.calidad(p.calidad());
        Player jugador = e.getPlayer();
        if (q != null && q.unaVez() && jugador != null) {
            this.unicos.anotar(jugador.getUniqueId(), caja.id(), id);
            Map<String, Long> r = this.reservas.get(jugador.getUniqueId());
            if (r != null) {
                r.remove(caja.id() + ":" + id);
                if (r.isEmpty()) this.reservas.remove(jugador.getUniqueId());
            }
        }
        this.bitacora.anotar(jugador == null ? "?" : jugador.getName(), caja.id(),
                q == null ? "sin calidad" : q.nombre(), e.getReward().getIdentifier());
    }

    /**
     * Lo que puede salirle a este jugador: lo encendido en PhoenixCrates menos
     * sus Unicos ya sacados. Es la base de la probabilidad que se le muestra.
     */
    Set<String> disponiblesPara(UUID jugador, Caja caja, CrateType tipo) {
        Set<String> fuera = new HashSet<>();
        for (String id : encendidos(tipo).keySet()) {
            if (sorteable(this.calidades, caja, jugador, id)) fuera.add(base(id));
        }
        return fuera;
    }

    /* ------------------------------------------------------------------ */
    /* Lo que hay en PhoenixCrates                                         */
    /* ------------------------------------------------------------------ */

    /** La caja de PhoenixCrates, o null si no existe o PhoenixCrates no esta listo. */
    static CrateType tipo(String id) {
        try {
            var m = PhoenixCratesAPI.getCratesManager();
            return m == null ? null : m.getTypeByIdentifier(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Los premios de la caja que PhoenixCrates tiene encendidos. */
    static Map<String, Reward> encendidos(CrateType tipo) {
        Map<String, Reward> fuera = new LinkedHashMap<>();
        if (tipo == null) return fuera;
        for (Reward r : tipo.getAvailableRewards()) {
            /* Con peso 0, PhoenixCrates no lo da como candidato nunca. */
            if (r.getWeight() > 0) fuera.put(r.getIdentifier(), r);
        }
        return fuera;
    }

    /**
     * Compara el config con las cajas de PhoenixCrates y lo cuenta en la consola
     * (o a quien lo pida). Devuelve cuantos problemas encontro.
     */
    private int informe(CommandSender quien) {
        Calidades c = this.calidades;
        List<String> lineas = new ArrayList<>(c.avisos());
        /* Sin licencia valida PhoenixCrates arranca pero no carga ninguna caja
         * (paso en el dev el 2026-10-12: "HttpResponseCode: 403. Grace period has
         * passed"). Se dice asi, en vez de "la caja no existe" por cada una. */
        int cargadas;
        try {
            var m = PhoenixCratesAPI.getCratesManager();
            cargadas = m == null ? 0 : m.getCrateTypes().size();
        } catch (Throwable t) {
            cargadas = 0;
            lineas.add("No se pudo preguntar a PhoenixCrates por sus cajas: " + t);
        }
        if (cargadas == 0) {
            lineas.add("PhoenixCrates no tiene ninguna caja cargada (mira su licencia en el arranque): "
                    + "las calidades no se aplican hasta que las cargue y se haga /edm crates reload.");
            for (String l : lineas) {
                getLogger().warning("[crates] " + l);
                if (quien != null) quien.sendMessage("§e" + l);
            }
            return lineas.size();
        }
        for (Caja caja : c.cajas()) {
            CrateType tipo = tipo(caja.id());
            if (tipo == null) {
                lineas.add("La caja '" + caja.id() + "' no existe en PhoenixCrates.");
                continue;
            }
            Map<String, Reward> on = encendidos(tipo);
            for (String id : on.keySet()) {
                if (caja.premio(id) == null) {
                    lineas.add("Caja " + caja.id() + ": el premio " + id + " esta encendido en PhoenixCrates pero no tiene calidad. No saldra.");
                }
            }
            for (String id : caja.premios().keySet()) {
                if (tipo.getRewardByIdentifier(id) == null) {
                    lineas.add("Caja " + caja.id() + ": el premio " + id + " del config no existe en PhoenixCrates.");
                }
            }
        }
        for (String l : lineas) {
            getLogger().warning("[crates] " + l);
            if (quien != null) quien.sendMessage("§e" + l);
        }
        if (lineas.isEmpty()) {
            getLogger().info("[crates] Calidades listas en " + c.cajas().size() + " caja(s), sin avisos.");
        }
        return lineas.size();
    }

    /* ------------------------------------------------------------------ */
    /* /edm crates ...                                                     */
    /* ------------------------------------------------------------------ */

    @Override
    public boolean subcomando(CommandSender quien, String[] args) {
        if (args.length == 0) return false;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info" -> info(quien, args);
            case "simulate" -> simular(quien, args);
            case "uniques" -> unicosCmd(quien, args);
            case "check" -> {
                if (informe(quien) == 0) quien.sendMessage("§aTodo cuadra entre el config y PhoenixCrates.");
            }
            case "help" -> ayuda(quien);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void ayuda(CommandSender quien) {
        quien.sendMessage("§6/edm crates info <caja> §7— probabilidad real de cada calidad y premio");
        quien.sendMessage("§6/edm crates simulate <caja> [aperturas] §7— sortea en vacio y compara");
        quien.sendMessage("§6/edm crates uniques <jugador> [remove <caja> <premio>] §7— premios de una vez");
        quien.sendMessage("§6/edm crates check §7— compara el config con PhoenixCrates");
        quien.sendMessage("§6/edm crates reload §7— relee crates/config.yml");
    }

    private Caja cajaDe(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage("§cFalta la caja. Con calidades: " + String.join(", ",
                    this.calidades.cajas().stream().map(Caja::id).toList()));
            return null;
        }
        Caja caja = this.calidades.caja(args[1]);
        if (caja == null) quien.sendMessage("§cLa caja '" + args[1] + "' no tiene calidades en el config de EDM.");
        return caja;
    }

    private void info(CommandSender quien, String[] args) {
        Caja caja = cajaDe(quien, args);
        if (caja == null) return;
        Set<String> disp = disponiblesPara(null, caja, tipo(caja.id()));
        Map<String, Double> pq = Sorteo.porCalidad(caja, disp);
        Map<String, Double> pp = Sorteo.probabilidades(caja, disp);
        quien.sendMessage("§6Caja " + caja.id() + " §7(" + pp.size() + " premios que pueden salir)");
        for (Calidad q : this.calidades.calidades()) {
            Double p = pq.get(q.id());
            if (p == null) continue;
            quien.sendMessage("§f" + q.nombre() + " §7" + Sorteo.porcentaje(p) + " %" + (q.unaVez() ? " §8(una vez)" : ""));
            for (Premio pr : caja.premios().values()) {
                Double x = pp.get(pr.id());
                if (x == null || !pr.calidad().equals(q.id())) continue;
                quien.sendMessage("§8  " + nombre(caja.id(), pr.id()) + " §7" + Sorteo.porcentaje(x) + " % §8("
                        + Sorteo.unoDeCada(x) + ")");
            }
        }
    }

    private void simular(CommandSender quien, String[] args) {
        Caja caja = cajaDe(quien, args);
        if (caja == null) return;
        int n = 100000;
        if (args.length >= 3) {
            try {
                n = Math.max(1, Math.min(5_000_000, Integer.parseInt(args[2])));
            } catch (NumberFormatException e) {
                quien.sendMessage("§c'" + args[2] + "' no es un numero.");
                return;
            }
        }
        Set<String> disp = disponiblesPara(null, caja, tipo(caja.id()));
        Map<String, Integer> cuenta = new LinkedHashMap<>();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            String id = Sorteo.elegir(caja, disp, r::nextDouble);
            if (id != null) cuenta.merge(caja.premio(id).calidad(), 1, Integer::sum);
        }
        Map<String, Double> esperado = Sorteo.porCalidad(caja, disp);
        quien.sendMessage("§6Simulacion de " + n + " aperturas de " + caja.id() + " §7(salio / esperado)");
        for (Calidad q : this.calidades.calidades()) {
            Double e = esperado.get(q.id());
            if (e == null) continue;
            int k = cuenta.getOrDefault(q.id(), 0);
            quien.sendMessage("§f" + q.nombre() + " §7" + k + " = " + Sorteo.porcentaje((double) k / n) + " % §8/ "
                    + Sorteo.porcentaje(e) + " %");
        }
    }

    private void unicosCmd(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage("§c/edm crates uniques <jugador> [remove <caja> <premio>]");
            return;
        }
        /* Solo jugadores que ya pasaron por el servidor: preguntar a Mojang por un
         * nombre desconocido bloquea el hilo y con Bedrock puede dar otro UUID. */
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (op == null) {
            quien.sendMessage("§cNo conozco a '" + args[1] + "': tiene que haber entrado alguna vez al servidor.");
            return;
        }
        UUID u = op.getUniqueId();
        if (args.length >= 5 && args[2].equalsIgnoreCase("remove")) {
            boolean ok = this.unicos.quitar(u, args[3].toLowerCase(Locale.ROOT), args[4]);
            quien.sendMessage(ok ? "§aQuitado: " + args[1] + " puede volver a sacar " + args[4] + " de " + args[3] + "."
                    : "§c" + args[1] + " no tenia anotado " + args[3] + ":" + args[4] + ".");
            if (ok) this.bitacora.anotar(quien.getName(), "quita unico", args[1], args[3] + ":" + args[4]);
            return;
        }
        List<String> l = this.unicos.de(u);
        quien.sendMessage(l.isEmpty() ? "§7" + args[1] + " no saco ningun premio de una vez."
                : "§6" + args[1] + ": §f" + String.join("§7, §f", l));
    }

    /** El nombre del premio sin colores, para los listados. */
    private static String nombre(String idCaja, String idPremio) {
        CrateType t = tipo(idCaja);
        Reward r = t == null ? null : t.getRewardByIdentifier(idPremio);
        if (r == null || r.getDisplayItem() == null) return idPremio;
        String n = r.getDisplayItem().getDisplayName();
        if (n == null || n.isBlank()) return idPremio;
        return org.bukkit.ChatColor.stripColor(n.replace('&', '§')).trim();
    }
}
