package net.ederus.edm.crates;

import java.io.File;
import java.util.ArrayList;
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
        this.unicos.cargar();
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

    @EventHandler(priority = EventPriority.HIGH)
    public void alSortear(CrateRewardSelectionEvent e) {
        if (e.isHandled()) return;
        Calidades c = this.calidades;
        String idCaja = e.getCrate().getType().getIdentifier();
        Caja caja = c.caja(idCaja);
        if (caja == null) return;

        Player jugador = e.getPlayer();
        Map<String, BaseReward> candidatos = new LinkedHashMap<>();
        for (BaseReward r : e.getCandidates()) candidatos.put(r.getIdentifier(), r);

        Set<String> disponibles = new HashSet<>();
        for (String id : candidatos.keySet()) {
            Premio p = caja.premio(id);
            if (p == null) {
                if (this.avisados.add(caja.id() + ":" + id)) {
                    getLogger().warning("[crates] La caja " + caja.id() + " tiene el premio " + id
                            + " sin calidad en el config de EDM: no saldra nunca. Anadelo o apagalo en PhoenixCrates.");
                }
                continue;
            }
            Calidad q = c.calidad(p.calidad());
            if (q != null && q.unaVez() && jugador != null
                    && this.unicos.tiene(jugador.getUniqueId(), caja.id(), id)) {
                continue;
            }
            disponibles.add(id);
        }

        String elegido = Sorteo.elegir(caja, disponibles, () -> ThreadLocalRandom.current().nextDouble());
        if (elegido == null) {
            /* Nada que sortear con las calidades (todo agotado o sin configurar):
             * se deja a PhoenixCrates para no dejar al jugador sin premio. */
            getLogger().warning("[crates] La caja " + caja.id() + " no tenia ningun premio con calidad disponible para "
                    + (jugador == null ? "?" : jugador.getName()) + ": sorteo PhoenixCrates.");
            return;
        }

        Premio premio = caja.premio(elegido);
        Calidad q = c.calidad(premio.calidad());
        e.setSelectedReward(candidatos.get(elegido));
        e.setHandled(true);

        if (q.unaVez() && jugador != null) {
            this.unicos.anotar(jugador.getUniqueId(), caja.id(), elegido);
        }
        this.bitacora.anotar(jugador == null ? "?" : jugador.getName(), caja.id(), q.nombre(), elegido);
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
        for (Reward r : tipo.getAvailableRewards()) fuera.put(r.getIdentifier(), r);
        return fuera;
    }

    /**
     * Compara el config con las cajas de PhoenixCrates y lo cuenta en la consola
     * (o a quien lo pida). Devuelve cuantos problemas encontro.
     */
    private int informe(CommandSender quien) {
        Calidades c = this.calidades;
        List<String> lineas = new ArrayList<>(c.avisos());
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
        Set<String> disp = encendidos(tipo(caja.id())).keySet();
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
        Set<String> disp = encendidos(tipo(caja.id())).keySet();
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

    @SuppressWarnings("deprecation")
    private void unicosCmd(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage("§c/edm crates uniques <jugador> [remove <caja> <premio>]");
            return;
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(args[1]);
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
