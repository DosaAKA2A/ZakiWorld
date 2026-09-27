package net.ederus.lethalworld.hardcore;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.boss.PhaseBars;
import net.ederus.edm.anomaly.core.ActiveAnomaly;
import net.ederus.edm.anomaly.core.AnomalyClass;
import net.ederus.edm.anomaly.drops.DropTable;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * El puente entre la PARCA de Calamity y el modulo de anomalias de EDM (1.2.0). Todo lo que
 * toca clases de anomalias de EDM desde el gestor pasa por aqui, para que Parca siga sin
 * saber de ellas y un EDM sin el modulo no rompa nada: la reserva (PeleaParca) sale igual.
 *
 * Lo que hace:
 *  - registra la ficha (ParcaType) en el catalogo de EDM al arrancar y la vuelve a mirar cada
 *    minuto (si EDM recarga su modulo, el catalogo nuevo no la trae);
 *  - abre la anomalia junto a la presa cuando la Huella la llama, pasandole el encargo (presa,
 *    grupo, N, r, M, lo pendiente) por un campo que create() consume en el acto;
 *  - deja a cero lo que EDM pagaria por ella (experiencia y MobCoins): el botin de la PARCA lo
 *    paga Calamity por la Aduana y no se cobra dos veces;
 *  - la de un AFK no se anuncia a todo el servidor (titulo y coordenadas a todos los conectados
 *    cada vez que alguien se queda quieto en Calamity): se oye la campana a 128 bloques, como
 *    siempre. parca.anomalia.anuncio-global en true lo devuelve. La abierta a mano se anuncia.
 */
final class PuenteAnomalia {

    /** Lo que la Huella le pide a la anomalia: la presa, su grupo y los numeros de Calamity. */
    record Encargo(UUID presa, String nombre, List<Player> grupo, int nivel, int r, int m, double fraccion, int fase) {
    }

    enum Decision { ANOMALIA, RESERVA }

    private final Parca gestor;
    private final ParcaType tipo;
    /** El encargo mientras EDM crea la pelea (dentro de open()); null el resto del tiempo. */
    private Encargo pendiente;
    private boolean vivo = true;
    /** Lo ultimo avisado del botin de EDM, para no repetirlo cada minuto. */
    private final Set<String> avisados = new HashSet<>();

    private PuenteAnomalia(Parca gestor) {
        this.gestor = gestor;
        this.tipo = new ParcaType(this);
    }

    /**
     * Lo crea y registra la ficha si el modulo de anomalias esta. Null solo si EDM no trae las
     * clases (no deberia: es dependencia dura); entonces la PARCA es siempre la de reserva.
     */
    static PuenteAnomalia crear(Parca gestor) {
        try {
            PuenteAnomalia p = new PuenteAnomalia(gestor);
            Autotest.registrar("parca-anomalia", () -> autotest(p));
            p.revisar();
            return p;
        } catch (Throwable t) {
            gestor.hc().plugin().getLogger().warning("[Calamity] La Parca no se puede registrar como anomalía de EDM"
                    + " (sale siempre la de reserva): " + t);
            return null;
        }
    }

    Parca gestor() {
        return gestor;
    }

    ParcaType tipo() {
        return tipo;
    }

    /** False desde que Calamity se para: una PARCA abierta a mano desde /anomaly ya no puede nacer. */
    boolean vivo() {
        return vivo;
    }

    /** El modulo de anomalias de EDM en marcha, o null. */
    AnomalyPlugin modulo() {
        Plugin p = gestor.hc().plugin().getServer().getPluginManager().getPlugin("EDM");
        if (!(p instanceof EDMPlugin edm) || !edm.isEnabled()) return null;
        if (!(edm.modulo("anomaly") instanceof AnomalyPlugin a)) return null;
        return a.registry() != null && a.manager() != null ? a : null;
    }

    /** Que la ficha este en el catalogo (al arrancar y cada minuto, desde Parca.tick). */
    void revisar() {
        if (!vivo) return;
        AnomalyPlugin a = modulo();
        if (a == null) return;
        if (a.registry().get(ParcaAnomalia.ID) != tipo) {
            a.registry().register(tipo);
            gestor.hc().plugin().getLogger().info("[Calamity] La Parca, registrada en EDM como anomalía "
                    + a.registry().classOf(tipo).display() + ".");
        }
        sinBotinEdm(a, true);
    }

    /** Desde el spawn de la pelea: solo la experiencia, en memoria (dentro de open() no se guarda nada). */
    void sinBotinEdm() {
        AnomalyPlugin a = modulo();
        if (a != null) sinBotinEdm(a, false);
    }

    /**
     * Sin doble botin. La PARCA la paga Calamity por la Aduana (Parca.pagar); lo de EDM se queda
     * a cero:
     *  - la experiencia de su tabla de botin (EDM la crea con 500 al pintar el hover del anuncio,
     *    asi que se pone a 0 aqui y otra vez en cada spawn);
     *  - las MobCoins de EDM (anomalias.parca.mobcoins en su config.yml): si nadie las ha puesto
     *    a mano se escriben a 0 una vez; si alguien puso otra cosa, se respeta y se avisa.
     * Los objetos y comandos de su tabla no se tocan: si un admin los pone desde /anomaly menu,
     * se avisa en consola, porque se pagarian ademas de lo de Calamity.
     */
    void sinBotinEdm(AnomalyPlugin a, boolean escribir) {
        try {
            DropTable t = a.drops().table(ParcaAnomalia.ID);
            if (t.experience() != 0) t.experience(0);
            if ((!t.entries().isEmpty() || !t.commands().isEmpty()) && avisados.add("tabla")) {
                gestor.hc().plugin().getLogger().warning("[Calamity] La tabla de botín de EDM para 'parca' no está vacía:"
                        + " se pagaría además de lo de Calamity. Vacíala en /anomaly menu -> Botín.");
            }
            if (!escribir) return;
            if (a.settings().rawInt("anomalias." + ParcaAnomalia.ID + ".mobcoins", -1) < 0) {
                if (a.settings().mobcoins(ParcaAnomalia.ID) > 0) a.settings().mobcoins(ParcaAnomalia.ID, 0);
            } else if (a.settings().mobcoins(ParcaAnomalia.ID) > 0 && avisados.add("mobcoins")) {
                gestor.hc().plugin().getLogger().warning("[Calamity] EDM tiene anomalias.parca.mobcoins > 0: la Parca"
                        + " pagaría MobCoins además de lo de Calamity. Ponlo a 0 en el config de anomalías de EDM.");
            }
        } catch (Throwable ignorado) {
            // Un EDM que cambie su API de botin no puede parar a la Parca: se vera en la prueba.
        }
    }

    /**
     * Anomalia o reserva, sin Bukkit (el autotest lo prueba entero). Solo sale la anomalia si
     * el modulo esta, la config la quiere, EDM la tiene activada en su menu y no hay otra
     * anomalia abierta ni buscando sitio (EDM solo lleva una a la vez).
     */
    static Decision decidir(boolean moduloPresente, boolean hayActiva, boolean buscando, boolean habilitada,
                            boolean usarAnomalia) {
        if (!usarAnomalia || !moduloPresente || !habilitada || hayActiva || buscando) return Decision.RESERVA;
        return Decision.ANOMALIA;
    }

    private Decision decision(AnomalyPlugin a) {
        return decidir(a != null, a != null && a.manager().active(), a != null && a.manager().searching(),
                a != null && a.registry().isEnabled(tipo), ParcaAnomalia.Ajustes.leer(gestor.hc()).activa);
    }

    /**
     * La abre en EDM junto a la presa. Devuelve la pelea viva, o null si toca la reserva (EDM
     * no esta, esta ocupado con otra, la tiene apagada, o el spawn fallo).
     */
    ParcaViva abrir(Encargo e, Location sitio) {
        AnomalyPlugin a = modulo();
        revisar();
        if (decision(a) != Decision.ANOMALIA) return null;
        // Sin el anuncio a todo el servidor: se apaga en memoria solo mientras dura open() (que
        // lo lee dentro, en Announcer.opened) y se devuelve en el acto. Dentro de open() no se
        // guarda el config de EDM (ver sinBotinEdm), asi que el false nunca llega al disco.
        boolean callada = !ParcaAnomalia.Ajustes.leer(gestor.hc()).anuncioGlobal;
        FileConfiguration cfg = a.getConfig();
        Object antes = cfg.get("anuncio.activo");
        pendiente = e;
        try {
            if (callada) cfg.set("anuncio.activo", false);
            a.manager().open(tipo, sitio);
        } finally {
            pendiente = null;
            if (callada) cfg.set("anuncio.activo", antes);
        }
        ActiveAnomaly ev = a.manager().current();
        if (ev != null && ev.fight() instanceof ParcaAnomalia pa && pa.encargo() == e && pa.vivaParaJugadores()) {
            return pa;
        }
        return null;
    }

    /** Lo consume ParcaType.create: con encargo es la PARCA de un AFK; sin el, una abierta a mano. */
    Encargo tomar() {
        Encargo e = pendiente;
        pendiente = null;
        return e;
    }

    /** Cierra en EDM esa anomalia si sigue siendo la abierta (sin mensaje: los avisos los da la pelea). */
    void cerrar(ActiveAnomaly ev) {
        AnomalyPlugin a = modulo();
        if (a != null && ev != null && a.manager().current() == ev) a.manager().stop(true);
    }

    void parar() {
        vivo = false;
        pendiente = null;
    }

    /** /lw hardcore parca anomalia: lo que hay en EDM y que saldria ahora. */
    String estado() {
        AnomalyPlugin a = modulo();
        if (a == null) return "anomalia | sin el modulo de anomalias de EDM | la siguiente: reserva";
        ActiveAnomaly ev = a.manager().current();
        String abierta = ev == null ? "ninguna"
                : ev.typeId() + (ev.fight() instanceof ParcaAnomalia ? " (esta)" : "") + " " + ev.elapsedSeconds() + " s";
        DropTable t = a.drops().table(ParcaAnomalia.ID);
        return "anomalia | registrada " + (a.registry().get(ParcaAnomalia.ID) == tipo ? "si" : "no")
                + " | clase " + a.registry().classOf(tipo).display()
                + " | activa en el menu " + (a.registry().isEnabled(tipo) ? "si" : "no")
                + " | abierta " + abierta
                + " | la siguiente: " + (decision(a) == Decision.ANOMALIA ? "anomalia" : "reserva")
                + " | EDM paga: xp " + t.experience() + ", objetos " + t.entries().size()
                + ", comandos " + t.commands().size() + ", mobcoins " + a.settings().mobcoins(ParcaAnomalia.ID);
    }

    // ================================================================ autotest

    /**
     * "parca-anomalia": la ficha (id, clase DIOS, 4 fases, tecnicas), el escalado de vida y
     * golpe (el de Calamity, no el de EDM), el reparto de fases, la eleccion entre anomalia y
     * reserva, la geometria de los golpes y que ningun aviso baje de medio segundo. Con el
     * modulo de EDM en marcha, ademas, que esta registrada y que EDM no paga nada por ella.
     */
    static List<String> autotest(PuenteAnomalia p) {
        Autotest.Hoja h = new Autotest.Hoja();
        ParcaType t = p.tipo;
        h.igual("id", "parca", t.id());
        h.igual("clase de serie", AnomalyClass.DIOS, t.defaultClass());
        h.ok("tecnicas en la ficha: " + t.abilities().size(), t.abilities().size() == HabilidadParca.values().length);
        List<String> lineas = h.lineas();
        lineas.addAll(autotestNucleo());
        AnomalyPlugin m = p.modulo();
        Autotest.Hoja e = new Autotest.Hoja();
        if (m == null) {
            e.ok("sin el modulo de anomalias de EDM: sale la reserva (nada que registrar)", true);
        } else {
            // Sin revisar(): la prueba no escribe nada (el registro ya se hizo al arrancar).
            e.ok("registrada en EDM", m.registry().get(ParcaAnomalia.ID) == t);
            e.igual("EDM no da experiencia por ella", 0, m.drops().table(ParcaAnomalia.ID).experience());
            e.igual("EDM no paga MobCoins por ella", 0, m.settings().mobcoins(ParcaAnomalia.ID));
        }
        lineas.addAll(e.lineas());
        return lineas;
    }

    /** Lo que no necesita servidor: tecnicas por fase, avisos, numeros, fases, decision y geometria. */
    static List<String> autotestNucleo() {
        Autotest.Hoja h = new Autotest.Hoja();
        Parca.Ajustes a = new Parca.Ajustes(new YamlConfiguration());
        Set<String> ids = new HashSet<>();
        boolean unicos = true;
        for (HabilidadParca x : HabilidadParca.values()) unicos &= ids.add(x.id) && x.id.startsWith("pa_");
        h.ok("ids unicos con pa_", unicos);
        h.ok("nombres de 1.1.1 siguen valiendo (paso, campanada, siega)", HabilidadParca.buscar("paso") == HabilidadParca.UMBRAL
                && HabilidadParca.buscar("campanada") == HabilidadParca.SENTENCIA
                && HabilidadParca.buscar("pa_siega") == HabilidadParca.SIEGA);
        for (int f = 1; f <= 4; f++) {
            int n = 0;
            for (HabilidadParca x : HabilidadParca.values()) if (x.enFase(f)) n++;
            h.ok("fase " + f + ": " + n + " tecnicas (>= 5)", n >= 5);
        }
        int aviso = Integer.MAX_VALUE;
        String cual = "";
        for (HabilidadParca x : HabilidadParca.values()) {
            for (int f = x.faseDesde; f <= x.faseHasta; f++) {
                int v = ParcaAnomalia.avisoMinimo(a, x, f);
                if (v < aviso) {
                    aviso = v;
                    cual = x.alias() + " fase " + f;
                }
            }
        }
        h.ok("ningun golpe sin aviso de al menos 10 ticks (minimo " + aviso + ": " + cual + ")", aviso >= 10);

        h.cerca("vida N 14 = la de Calamity (920)", 920, ParcaAnomalia.vidaInicial(a, 14, 0, 0), 1e-6);
        h.cerca("vida N 52 (2440)", 2440, ParcaAnomalia.vidaInicial(a, 52, 0, 0), 1e-6);
        h.cerca("vida r 1 x1,25 y M 1 x1,5 (N 14: 1725)", 1725, ParcaAnomalia.vidaInicial(a, 14, 1, 1), 1e-6);
        h.cerca("golpe N 100 (39,68)", 39.68, ParcaAnomalia.golpeInicial(a, 100, 0), 1e-6);
        h.igual("fases por vida 80/60/40/20 %", List.of(1, 2, 3, 4), List.of(PhaseBars.currentPhase(0.8, 4),
                PhaseBars.currentPhase(0.6, 4), PhaseBars.currentPhase(0.4, 4), PhaseBars.currentPhase(0.2, 4)));

        h.igual("EDM libre -> anomalia", Decision.ANOMALIA, decidir(true, false, false, true, true));
        h.igual("otra anomalia abierta -> reserva", Decision.RESERVA, decidir(true, true, false, true, true));
        h.igual("EDM buscando sitio -> reserva", Decision.RESERVA, decidir(true, false, true, true, true));
        h.igual("sin modulo de anomalias -> reserva", Decision.RESERVA, decidir(false, false, false, true, true));
        h.igual("apagada en /anomaly menu -> reserva", Decision.RESERVA, decidir(true, false, false, false, true));
        h.igual("parca.anomalia.activa false -> reserva", Decision.RESERVA, decidir(true, false, false, true, false));

        h.ok("arco: delante dentro, detras fuera", ParcaAnomalia.enArco(2, 0, 1, 0, 3.6, 90)
                && !ParcaAnomalia.enArco(-2, 0, 1, 0, 3.6, 90) && !ParcaAnomalia.enArco(4, 0, 1, 0, 3.6, 90));
        h.cerca("distancia a la linea de la acometida", 1.0,
                ParcaAnomalia.distanciaASegmento(5, 1, 0, 0, 10, 0), 1e-9);
        h.ok("hueco del anillo: 20 grados dentro, 40 fuera (ancho 55)",
                ParcaAnomalia.enHueco(Math.toRadians(20), 0, Math.toRadians(55))
                        && !ParcaAnomalia.enHueco(Math.toRadians(40), 0, Math.toRadians(55)));

        return h.lineas();
    }
}
