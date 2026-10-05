package net.ederus.calamity.hardcore;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.ederus.edm.comun.Poder;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Calamity 1.13.0 · El Vigilante: la anomalia Monarca de tierra, un escalon bajo la Parca.
 *
 * Un golem de hierro a 2,2 veces su tamano con un ojo de luz amarilla en el pecho y tres nucleos
 * que le giran alrededor. Baja del cielo como un meteoro sobre quien se adentra demasiado: pasados
 * llegada.bloques (1.000) del borde de la zona spawn (la medida de Distancia.bloques), al abrir
 * llegada.cofres (3) cofres o matar llegada.minijefes (1) minijefe ahi. Antes, dos avisos (titulo
 * breve y barra de accion con la Paleta) y la marca de donde cae. Una vez al dia por jugador y como
 * mucho llegada.maximo-vivos (2) a la vez, contando los que estan cayendo.
 *
 * Esto es el gestor (como Ambush para PeleaAmbush): las cuentas de la llegada, la caida, el botin
 * por la Aduana, el comando de staff (/calamity vigilant), el unico Listener de la familia y su
 * ficha en el catalogo de EDM (VigilanteType, sale en /anomaly como las demas). La pelea es
 * PeleaVigilante y se mueve con la tarea de 2 ticks de Amenazas: no hay ninguna tarea propia, la
 * caida tambien va colgada de esa misma tarea.
 *
 * Como Ambush y por lo mismo, la pelea de verdad no pasa por EDM: EDM solo lleva una anomalia a la
 * vez y aqui puede haber dos Vigilantes, ni se puede anunciar a todo el servidor cada vez que alguien
 * abre tres cofres lejos. Abierto a mano desde /anomaly es una prueba (VigilanteEdm), sin presa ni
 * botin de Calamity.
 *
 * Los numeros son puros (escala, golpes, llegada, reparto) y el autotest "vigilante" los prueba:
 * los golpes fuertes entre el 25 % y el 90 % de la vida maxima y nunca matan con la vida llena; a el
 * ningun golpe le quita mas del 8 % de su vida; y las condiciones de la llegada.
 */
final class Vigilante implements Listener {

    static final String AMENAZA = "vigilante";
    private static final long HORA = 3_600_000L;
    /** SecureRandom: el botin raro es dinero en potencia (regla de la casa). */
    private static final SecureRandom AZAR = new SecureRandom();

    // =================================================================== ajustes

    /** Lo de una habilidad que se lee de habilidades.<clave>: espera, aviso y fraccion de la vida. */
    record Hab(int espera, int aviso, double fraccion) {
    }

    /**
     * hardcore.vigilante, con los valores de serie si falta (el config del dev no la trae). Se relee
     * cada 5 s. Los avisos de los golpes fuertes nunca bajan de AVISO_MINIMO ticks.
     */
    static final class Ajustes {
        /** Ningun golpe fuerte sin al menos un segundo de aviso en el suelo. */
        static final int AVISO_MINIMO = 20;

        final boolean activo;
        // llegada
        final double llegadaBloques;
        final int llegadaCofres, llegadaMinijefes, maximoVivos, avisoSegundos, caidaTicks;
        final double llegadaDistancia, impactoRadio, impactoFraccion;
        // cuerpo y numeros
        final double vidaBase, vidaPorNivel, golpeBase, golpePorNivel, topeGolpe, escalaCuerpo, velocidad;
        final int extraNivel, duracionMinutos, abandonoSegundos, atascoSegundos;
        final double radioPelea, radioGrupo;
        // escala
        final double porJugador, poderCada, poderVida, poderDano;
        final int jugadoresTope, poderTope;
        final String poderPlaceholder;
        // golpes fuertes
        final double golpeMinimo, golpeMaximo, marcadoExtra;
        final int ventanaTicks;
        // mirada
        final double miradaAlcance, miradaAngulo;
        final int miradaTicks, marcaSegundos;
        // habilidades
        final Map<PeleaVigilante.Habilidad, Hab> hab = new EnumMap<>(PeleaVigilante.Habilidad.class);
        final double machaqueRadio, machaqueAngulo, saltoRadio, saltoOndaFraccion, saltoOndaRadio;
        final double barridoRadio, barridoAngulo, embestidaLargo, escombrosRadio;
        final int escombrosCirculos, pilaresCuantos, pilaresSegundos;
        final int nucleosGolpes, nucleosGolpesPorJugador, nucleosSegundos, aturdidoSegundos;
        final double nucleosRadio, aturdidoExtra;
        final double tironMinima, tironFuerza;
        final int faroVueltas, faroTicksVuelta, faroAdelanto;
        final double faroAlcance;
        final int ondasSeparacion;
        final double ondasRadio, sentenciaRadio;
        // botin
        final int horasEntreCobros, esenciasBase, esenciasCada, gradoIV;
        final double participacion;
        final List<Minijefes.Botin> extra;
        final List<String> avisosBotin = new ArrayList<>();

        Ajustes(ConfigurationSection s) {
            if (s == null) s = new YamlConfiguration();
            activo = s.getBoolean("activo", true);
            llegadaBloques = Math.max(0, s.getDouble("llegada.bloques", 1000));
            llegadaCofres = Math.max(0, s.getInt("llegada.cofres", 3));
            llegadaMinijefes = Math.max(0, s.getInt("llegada.minijefes", 1));
            maximoVivos = Math.max(0, s.getInt("llegada.maximo-vivos", 2));
            avisoSegundos = Math.max(1, s.getInt("llegada.aviso-segundos", 5));
            caidaTicks = Math.max(AVISO_MINIMO, s.getInt("llegada.caida-ticks", 40));
            llegadaDistancia = Math.max(4, s.getDouble("llegada.distancia", 10));
            impactoRadio = Math.max(1, s.getDouble("llegada.impacto-radio", 4));
            impactoFraccion = s.getDouble("llegada.impacto-fraccion", 0.25);

            vidaBase = s.getDouble("vida-base", 360);
            vidaPorNivel = s.getDouble("vida-por-nivel", 0.10);
            extraNivel = s.getInt("extra-nivel", 8);
            golpeBase = s.getDouble("golpe-base", 9);
            golpePorNivel = s.getDouble("golpe-por-nivel", 0.04);
            topeGolpe = Math.max(0, s.getDouble("tope-golpe-fraccion", 0.08));
            escalaCuerpo = Math.max(1.0, Math.min(4.0, s.getDouble("escala-cuerpo", 2.2)));
            velocidad = s.getDouble("velocidad", 0.30);
            duracionMinutos = Math.max(1, s.getInt("duracion-minutos", 10));
            abandonoSegundos = Math.max(5, s.getInt("abandono-segundos", 30));
            atascoSegundos = Math.max(5, s.getInt("atasco-segundos", 15));
            radioPelea = Math.max(16, s.getDouble("radio-pelea", 48));
            radioGrupo = Math.max(4, s.getDouble("radio-grupo", 24));

            porJugador = Math.max(0, s.getDouble("escala.por-jugador", 0.35));
            jugadoresTope = Math.max(1, s.getInt("escala.jugadores-tope", 5));
            poderCada = Math.max(0, s.getDouble("escala.poder-cada", 2000));
            poderVida = Math.max(0, s.getDouble("escala.poder-vida", 0.06));
            poderDano = Math.max(0, s.getDouble("escala.poder-dano", 0.04));
            poderTope = Math.max(0, s.getInt("escala.poder-tope-tramos", 6));
            poderPlaceholder = s.getString("escala.poder-placeholder", "%edm_poder%");

            golpeMinimo = Math.max(0, Math.min(1, s.getDouble("golpes.minimo", 0.25)));
            // Ley 5: con la vida llena nada te mata de un golpe. Nunca 1 o mas.
            golpeMaximo = Math.max(golpeMinimo, Math.min(0.95, s.getDouble("golpes.maximo", 0.90)));
            marcadoExtra = Math.max(0, s.getDouble("golpes.marcado-extra", 0.25));
            ventanaTicks = Math.max(0, s.getInt("golpes.ventana-ticks", 10));

            miradaAlcance = Math.max(4, s.getDouble("mirada.alcance", 24));
            miradaAngulo = Math.max(10, Math.min(180, s.getDouble("mirada.angulo", 70)));
            miradaTicks = Math.max(2, (int) Math.round(s.getDouble("mirada.segundos", 2) * 20));
            marcaSegundos = Math.max(1, s.getInt("mirada.marca-segundos", 15));

            for (PeleaVigilante.Habilidad h : PeleaVigilante.Habilidad.values()) {
                String b = "habilidades." + h.clave + ".";
                int aviso = s.getInt(b + "aviso-ticks", h.aviso);
                if (h.fraccion > 0) aviso = Math.max(AVISO_MINIMO, aviso);
                hab.put(h, new Hab(Math.max(20, s.getInt(b + "espera-ticks", h.espera)), Math.max(0, aviso),
                        Math.max(0, s.getDouble(b + "fraccion", h.fraccion))));
            }
            machaqueRadio = Math.max(2, s.getDouble("habilidades.machaque.radio", 5.5));
            machaqueAngulo = Math.max(30, Math.min(360, s.getDouble("habilidades.machaque.angulo", 120)));
            saltoRadio = Math.max(1.5, s.getDouble("habilidades.salto.radio", 3.5));
            saltoOndaFraccion = Math.max(0, s.getDouble("habilidades.salto.onda-fraccion", 0.25));
            saltoOndaRadio = Math.max(3, s.getDouble("habilidades.salto.onda-radio", 10));
            barridoRadio = Math.max(4, s.getDouble("habilidades.barrido.radio", 14));
            barridoAngulo = Math.max(30, Math.min(300, s.getDouble("habilidades.barrido.angulo", 120)));
            embestidaLargo = Math.max(4, s.getDouble("habilidades.embestida.largo", 20));
            escombrosRadio = Math.max(1, s.getDouble("habilidades.escombros.radio", 2.2));
            escombrosCirculos = Math.max(1, s.getInt("habilidades.escombros.circulos", 5));
            pilaresCuantos = Math.max(1, Math.min(12, s.getInt("habilidades.pilares.cuantos", 6)));
            pilaresSegundos = Math.max(5, s.getInt("habilidades.pilares.segundos", 30));
            nucleosGolpes = Math.max(1, s.getInt("habilidades.nucleos.golpes", 4));
            nucleosGolpesPorJugador = Math.max(0, s.getInt("habilidades.nucleos.golpes-por-jugador", 2));
            // El estallido se avisa todo el rato que estan sueltos: nunca menos de un segundo.
            nucleosSegundos = Math.max(5, s.getInt("habilidades.nucleos.segundos", 14));
            nucleosRadio = Math.max(1, s.getDouble("habilidades.nucleos.radio", 5));
            aturdidoSegundos = Math.max(1, s.getInt("habilidades.nucleos.aturdido-segundos", 6));
            aturdidoExtra = Math.max(0, s.getDouble("habilidades.nucleos.aturdido-dano-extra", 0.30));
            tironMinima = Math.max(4, s.getDouble("habilidades.tiron.distancia-minima", 12));
            tironFuerza = Math.max(0.2, Math.min(3, s.getDouble("habilidades.tiron.fuerza", 1.6)));
            faroVueltas = Math.max(1, s.getInt("habilidades.faro.vueltas", 2));
            faroTicksVuelta = Math.max(40, s.getInt("habilidades.faro.ticks-por-vuelta", 80));
            faroAdelanto = Math.max(AVISO_MINIMO, s.getInt("habilidades.faro.adelanto-ticks", 20));
            faroAlcance = Math.max(6, s.getDouble("habilidades.faro.alcance", 20));
            ondasSeparacion = Math.max(10, s.getInt("habilidades.ondas.separacion-ticks", 24));
            ondasRadio = Math.max(4, s.getDouble("habilidades.ondas.radio", 16));
            sentenciaRadio = Math.max(3, s.getDouble("habilidades.sentencia.radio", 8));

            horasEntreCobros = Math.max(0, s.getInt("botin.horas-entre-cobros", 24));
            participacion = Math.max(0, Math.min(1, s.getDouble("botin.participacion-minima", 0.10)));
            esenciasBase = Math.max(0, s.getInt("botin.esencias-base", 5));
            esenciasCada = Math.max(1, s.getInt("botin.esencias-cada-niveles", 10));
            gradoIV = s.getInt("botin.reliquia-grado-iv", 50);
            extra = leerExtra(s, avisosBotin);
        }

        Hab hab(PeleaVigilante.Habilidad h) {
            return hab.get(h);
        }

        /** Los ticks que avisa en el suelo un golpe fuerte de esa habilidad; -1 si no hace dano. */
        int avisoDe(PeleaVigilante.Habilidad h) {
            return switch (h) {
                case PILARES, TIRON -> -1;
                case NUCLEOS -> nucleosSegundos * 20;
                case FARO -> Math.min(hab(h).aviso(), faroAdelanto);
                default -> hab(h).aviso();
            };
        }
    }

    /** botin.extra: como minijefes.botin (Minijefes.botinDe). Sin la clave, los de serie. */
    static List<Minijefes.Botin> leerExtra(ConfigurationSection s, List<String> avisos) {
        if (s == null || !s.isList("botin.extra")) return EXTRA_DE_SERIE;
        List<Minijefes.Botin> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        List<?> crudo = s.getList("botin.extra", List.of());
        for (int i = 0; i < crudo.size(); i++) {
            Object o = crudo.get(i);
            Map<?, ?> m = o instanceof Map<?, ?> mm ? mm : o instanceof ConfigurationSection cs ? cs.getValues(false) : null;
            String donde = "botin.extra (entrada " + (i + 1) + ")";
            if (m == null) {
                avisos.add(donde + ": no es una entrada {id: ..., prob: ...}");
                continue;
            }
            Minijefes.Botin b = Minijefes.botinDe(m, donde, avisos);
            if (b == null) continue;
            if (!ids.add(b.id())) {
                avisos.add(donde + ": el id '" + b.id() + "' esta repetido; vale el primero");
                continue;
            }
            out.add(b);
        }
        return List.copyOf(out);
    }

    /**
     * El botin extra de serie. La gema y las placas llegan en otro lote: hasta que MMOItems tenga
     * CALAMITY_GEMAS.GEMA_OJO_DEL_VIGILANTE y CALAMITY_MATERIALES.PLACA_DEL_VIGILANTE no salen ni cuentan
     * piedad (disponibles), en silencio, como Minijefes.gemaSinLote.
     */
    static final List<Minijefes.Botin> EXTRA_DE_SERIE = List.of(
            new Minijefes.Botin("gema", 0.20, "mejor", 8, Entregas.OJO_DEL_VIGILANTE, "", "la gema Ojo del Vigilante", true),
            new Minijefes.Botin("placa", 0.60, "participantes", 3, Entregas.PLACA_DEL_VIGILANTE, "", "una Placa del Vigilante", false),
            new Minijefes.Botin("libro", 0.06, "mejor", 0, "libro", "", "un libro LEGENDARY", false));

    // =================================================================== estado

    private final Hardcore hc;
    private final List<PeleaVigilante> peleas = new ArrayList<>();
    private final List<Llegada> llegadas = new ArrayList<>();
    /** Quien ya cumplio las cuentas pero aun no ha podido recibirlo (tope de vivos, se acerco al spawn...). */
    private final Set<UUID> pendientes = new LinkedHashSet<>();
    /** Una caida que no llego a caer (se fue al spawn, salio...) se vuelve a intentar pasado este momento. */
    private final Map<UUID, Long> reintento = new HashMap<>();
    /** Null si EDM no trae las clases de anomalias: entonces solo falta el registro en /anomaly. */
    private final VigilanteType tipo;
    private Ajustes ajustes;
    private long ajustesLeidos;
    private int segundos;
    private final Set<String> avisados = new HashSet<>();

    Vigilante(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("vigilante", Vigilante::autotest);
        Subcomandos.staff().registrar("vigilant",
                "vigilant spawn [player] | test | info [player] | health <0-1> | ability <name> | remove | reset <player>"
                        + ": el Vigilante (pruebas)",
                Subcomandos.PERMISO, this::comando, this::tab);
        this.tipo = VigilanteType.crear(this);
    }

    Hardcore hc() {
        return hc;
    }

    Ajustes ajustes() {
        long ahora = System.currentTimeMillis();
        if (ajustes == null || ahora - ajustesLeidos > 5_000) {
            ajustes = new Ajustes(hc.cfg().getConfigurationSection("vigilante"));
            ajustesLeidos = ahora;
            for (String x : ajustes.avisosBotin) {
                if (avisados.size() < 64 && avisados.add(x)) hc.plugin().getLogger().warning("[Calamity] vigilante." + x);
            }
        }
        return ajustes;
    }

    /** La apunta la pelea al nacer (tambien la de prueba de EDM). */
    void registrar(PeleaVigilante pe) {
        if (pe != null && !peleas.contains(pe)) peleas.add(pe);
    }

    int vivos() {
        int n = 0;
        for (PeleaVigilante pe : peleas) if (pe.estado != PeleaVigilante.Estado.FIN && !pe.prueba) n++;
        return n + llegadas.size();
    }

    // =================================================================== numeros (puros)

    /** N = min(100, el nivel de Calamity mas alto del grupo + extra-nivel). */
    static int nivel(Ajustes a, int n0) {
        return Math.max(1, Math.min(100, n0 + a.extraNivel));
    }

    /** Tramos enteros de Poder (/flex): poder-cada puntos cada uno, hasta poder-tope-tramos. */
    static int tramosPoder(double poder, Ajustes a) {
        if (a.poderCada <= 0 || poder <= 0) return 0;
        return (int) Math.min(a.poderTope, Math.floor(poder / a.poderCada));
    }

    /** Todo lo que lo hace mas duro, de una vez: para el info del staff y para la pelea. */
    record Escala(int nivel, int jugadores, double poder, int tramosPoder, DificultadAmenaza.Resultado dificultad,
                  double vida, double golpe, double multFraccion) {

        String texto() {
            return "N " + nivel + " · jugadores " + jugadores + " · poder " + Math.round(poder) + " (" + tramosPoder
                    + " tramos) · vida " + Math.round(vida) + " · golpe " + Math.round(golpe * 10) / 10.0
                    + " · golpes fuertes ×" + DificultadAmenaza.veces(multFraccion) + " · "
                    + (dificultad == null ? "sin presa" : dificultad.texto());
        }
    }

    /**
     * La escala del Vigilante (puro):
     *  vida  = vida-base x (1 + vida-por-nivel x (N-1)) x (1 + por-jugador x (J-1)) x (1 + poder-vida x tramos)
     *          x la dificultad de su presa (DificultadAmenaza: lejos, poca cordura, mucho rato dentro);
     *  golpe = golpe-base x (1 + golpe-por-nivel x (N-1)) x (1 + poder-dano x tramos) x la dificultad;
     *  los golpes fuertes (fraccion de TU vida) suben solo con la dificultad, como la Siega de la Parca,
     *  y siempre entre golpes.minimo y golpes.maximo.
     */
    static Escala escala(Ajustes a, int n0, int jugadores, double poder, DificultadAmenaza.Resultado dif) {
        DificultadAmenaza.Resultado d = dif == null ? DificultadAmenaza.NEUTRO : dif;
        int n = nivel(a, n0);
        int j = Math.max(1, Math.min(a.jugadoresTope, jugadores));
        int tp = tramosPoder(poder, a);
        double vida = a.vidaBase * (1 + a.vidaPorNivel * (n - 1)) * (1 + a.porJugador * (j - 1)) * (1 + a.poderVida * tp) * d.vida();
        double golpe = a.golpeBase * (1 + a.golpePorNivel * (n - 1)) * (1 + a.poderDano * tp) * d.dano();
        return new Escala(n, j, poder, tp, d, vida, golpe, d.dano());
    }

    /** La fraccion de TU vida maxima que quita un golpe fuerte: base x multiplicador (x marcado), entre minimo y maximo. */
    static double fraccionGolpe(double base, double mult, boolean marcado, Ajustes a) {
        double f = base * Math.max(0, mult) * (marcado ? 1 + a.marcadoExtra : 1);
        return Math.max(a.golpeMinimo, Math.min(a.golpeMaximo, f));
    }

    /**
     * Lo que puede entrar de un golpe fuerte si ya te han quitado "acumulado" (fraccion de tu vida
     * maxima) en la misma ventana de golpes.ventana-ticks: dos golpes juntos no pasan del maximo, asi
     * que con la vida llena ni uno ni dos que caigan a la vez te matan.
     */
    static double recorteVentana(double fraccion, double acumulado, double maximo) {
        return Math.max(0, Math.min(fraccion, maximo - Math.max(0, acumulado)));
    }

    /** Lo que de verdad quita a una vida maxima (DanoVerdadero.recorte con el tope de la ley 5). */
    static double quita(double fraccion, double vidaMax, double maximo) {
        return DanoVerdadero.recorte(fraccion * vidaMax, maximo, vidaMax);
    }

    /** En que fase esta por su vida: 1 por encima del 75 %, 2 del 50 %, 3 del 25 %, 4 debajo. Nunca vuelve atras. */
    static int faseDe(double fraccion, int actual) {
        int f = fraccion > 0.75 ? 1 : fraccion > 0.5 ? 2 : fraccion > 0.25 ? 3 : 4;
        return Math.max(actual, f);
    }

    /** Golpes que aguanta cada nucleo: golpes + golpes-por-jugador x (jugadores - 1). */
    static int golpesNucleo(Ajustes a, int jugadores) {
        return a.nucleosGolpes + a.nucleosGolpesPorJugador * Math.max(0, jugadores - 1);
    }

    /**
     * Por que no le cae ahora a ese jugador, o null si le cae. Sin Bukkit:
     *   apagado | hoy (ya vino hoy) | faltan (ni cofres ni minijefes suficientes) | cerca (no ha pasado la
     *   raya) | spawn | ocupado (la Parca o un Vigilante encima) | lleno (ya hay maximo-vivos).
     */
    static String motivoLlegada(Ajustes a, double bloques, int cofres, int minijefes, boolean yaHoy, boolean enSpawn,
                                boolean ocupado, int vivos) {
        if (!a.activo) return "apagado";
        if (yaHoy) return "hoy";
        boolean cuenta = (a.llegadaCofres > 0 && cofres >= a.llegadaCofres)
                || (a.llegadaMinijefes > 0 && minijefes >= a.llegadaMinijefes);
        if (!cuenta) return "faltan";
        if (bloques < a.llegadaBloques) return "cerca";
        if (enSpawn) return "spawn";
        if (ocupado) return "ocupado";
        if (vivos >= a.maximoVivos) return "lleno";
        return null;
    }

    /** Si un cofre o un minijefe en ese sitio cuenta para la llegada: pasada la raya. */
    static boolean cuentaAhi(Ajustes a, double bloques, boolean enSpawn) {
        return a.activo && !enSpawn && bloques >= a.llegadaBloques;
    }

    /** Lo que le toca a cada uno que le pego (motivo != null = no cobra). */
    record Cobro(UUID id, double fraccion, int esencias, int grado, String motivo) {
    }

    static int esencias(Ajustes a, int n) {
        return a.esenciasBase + n / a.esenciasCada;
    }

    /** La Reliquia alta: IV desde reliquia-grado-iv, III por debajo. */
    static int grado(Ajustes a, int n) {
        return n >= a.gradoIV ? 4 : 3;
    }

    /**
     * El reparto (puro): cobra cada uno con participacion-minima o mas de su vida logica, valido con la
     * presa en la Aduana (la presa siempre lo es consigo misma) y sin cobro en horas-entre-cobros. Todos
     * los que cobran: Esencias y una Reliquia alta. En orden de dano, de mas a menos.
     */
    static List<Cobro> repartir(Ajustes a, Map<UUID, Double> dano, double vida, int n, Predicate<UUID> yaCobro,
                                Predicate<UUID> valida) {
        List<Map.Entry<UUID, Double>> orden = new ArrayList<>(dano.entrySet());
        orden.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
        List<Cobro> out = new ArrayList<>();
        for (Map.Entry<UUID, Double> e : orden) {
            double f = vida <= 0 ? 0 : e.getValue() / vida;
            String motivo = f < a.participacion ? "poco-dano"
                    : !valida.test(e.getKey()) ? "invalida"
                    : yaCobro.test(e.getKey()) ? "ya-cobro" : null;
            out.add(motivo == null ? new Cobro(e.getKey(), f, esencias(a, n), grado(a, n), null)
                    : new Cobro(e.getKey(), f, 0, 0, motivo));
        }
        return out;
    }

    /**
     * Las entradas del botin extra que se pueden dar: una con objeto que no existe (la gema o las placas
     * sin su lote en MMOItems) se queda sin objeto, y tirarBotin la salta sin tirar ni contar piedad.
     * El libro y los comandos no se miran aqui.
     */
    static List<Minijefes.Botin> disponibles(List<Minijefes.Botin> entradas, Predicate<String> existe) {
        List<Minijefes.Botin> out = new ArrayList<>();
        for (Minijefes.Botin b : entradas) {
            if (!b.objeto().isEmpty() && !b.objeto().equals("libro") && !existe.test(b.objeto())) {
                out.add(new Minijefes.Botin(b.id(), b.prob(), b.para(), b.piedad(), "", "", b.nombre(), b.anuncio()));
            } else {
                out.add(b);
            }
        }
        return out;
    }

    static String sinCobro(Ajustes a, String motivo, String porQueInvalida, int horasMinimas) {
        String porQue = switch (motivo == null ? "" : motivo) {
            case "poco-dano" -> "tu daño no llegó al mínimo (" + Marco.porcentaje(a.participacion) + " de su vida).";
            case "ya-cobro" -> "ya cobraste por otro en las últimas " + a.horasEntreCobros + " h.";
            case "invalida" -> "horas".equals(porQueInvalida)
                    ? "para cobrar ayudando a otro, cada uno necesita al menos " + horasMinimas + " h jugadas."
                    : "la presa usa tu misma conexión.";
            default -> null;
        };
        return porQue == null ? null : "No cobras por el Vigilante: " + porQue;
    }

    // =================================================================== Poder y nivel

    private int nivelCalamity(Player p) {
        if (hc.plugin().mobs() == null) return Math.max(1, hc.bonusNivel(p));
        return hc.plugin().mobs().nivelCalamity(p);
    }

    /**
     * El Poder de /flex: por la API de EDM (Poder.calcular con los pesos del modulo flex); sin ella,
     * por PlaceholderAPI (escala.poder-placeholder); sin nada, 0.
     */
    double poder(Player p) {
        try {
            Plugin edm = hc.plugin().getServer().getPluginManager().getPlugin("EDM");
            if (edm instanceof EDMPlugin e && e.isEnabled()) {
                Plugin fuente = e.modulo("flex");
                return Poder.calcular(fuente != null ? fuente : e, p).total();
            }
        } catch (Throwable ignorado) {
            // Un EDM sin la clase Poder: se intenta por PlaceholderAPI.
        }
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Object r = papi.getMethod("setPlaceholders", OfflinePlayer.class, String.class)
                    .invoke(null, p, ajustes().poderPlaceholder);
            String n = String.valueOf(r).replaceAll("[^0-9]", "");
            return n.isEmpty() ? 0 : Double.parseDouble(n);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** La escala para una pelea contra ese grupo (la presa la primera; null = de prueba). */
    Escala escalaPara(Player presa, List<Player> grupo) {
        Ajustes a = ajustes();
        int n0 = 1;
        double poder = 0;
        for (Player g : grupo) {
            n0 = Math.max(n0, hc.valor("vigilante", () -> nivelCalamity(g), 1));
            poder = Math.max(poder, hc.valor("vigilante", () -> poder(g), 0.0));
        }
        DificultadAmenaza.Resultado dif = presa == null ? DificultadAmenaza.NEUTRO
                : hc.valor("vigilante", () -> DificultadAmenaza.para(hc, DificultadAmenaza.foto(hc, presa)), DificultadAmenaza.NEUTRO);
        return escala(a, n0, Math.max(1, grupo.size()), poder, dif);
    }

    /** Los que pelearian con el: el, y quien cuente a radio-grupo (sin espectadores ni gente en el spawn). */
    List<Player> grupo(Player presa, Location donde) {
        List<Player> g = new ArrayList<>();
        if (presa != null) g.add(presa);
        Ajustes a = ajustes();
        for (Player o : Fx.playersNear(donde, a.radioGrupo)) {
            if (g.contains(o) || !hc.cuenta(o) || hc.enSpawn(o)) continue;
            g.add(o);
        }
        return g;
    }

    // =================================================================== la llegada

    private String dia() {
        Calendario c = hc.calendario();
        return c != null ? c.dia() : new Calendario(hc).dia();
    }

    private String base(UUID u) {
        return "vigilante.cuenta." + u;
    }

    private int cuenta(UUID u, String que) {
        String b = base(u);
        return dia().equals(hc.datos().getString(b + ".dia", "")) ? hc.datos().getInt(b + "." + que, 0) : 0;
    }

    private void sumar(UUID u, String que) {
        String b = base(u);
        String hoy = dia();
        if (!hoy.equals(hc.datos().getString(b + ".dia", ""))) {
            hc.datos().set(b, null);
            hc.datos().set(b + ".dia", hoy);
        }
        hc.datos().set(b + "." + que, hc.datos().getInt(b + "." + que, 0) + 1);
        hc.marcarSucio();
    }

    private boolean yaHoy(UUID u) {
        return dia().equals(hc.datos().getString("vigilante.visto." + u, ""));
    }

    /** Un cofre abierto por un jugador que cuenta (Hardcore.onBotinDeCofre y Ruinas.alAbrir). */
    void alAbrirCofre(Player p) {
        apuntar(p, "cofres");
    }

    /** Un minijefe muerto a manos de ese jugador (Minijefes.alMorir; las simulaciones no). */
    void alMatarMinijefe(Player p) {
        apuntar(p, "minijefes");
    }

    private void apuntar(Player p, String que) {
        if (p == null || !hc.esHardcore(p) || !hc.cuenta(p)) return;
        Ajustes a = ajustes();
        if (!cuentaAhi(a, hc.bloquesAlSpawn(p), hc.enSpawn(p))) return;
        if (yaHoy(p.getUniqueId())) return;
        sumar(p.getUniqueId(), que);
        intentar(p, false);
    }

    /** Si le toca, empieza la caida; si aun no puede (tope, spawn...), se queda pendiente. */
    private void intentar(Player p, boolean desdeTick) {
        UUID u = p.getUniqueId();
        Long r = reintento.get(u);
        if (r != null) {
            if (r > System.currentTimeMillis()) {
                pendientes.add(u);
                return;
            }
            reintento.remove(u);
        }
        boolean ocupado = (hc.parca() != null && hc.valor("parca", () -> hc.parca().persigue(p), false)) || conVigilante(u);
        String no = motivoLlegada(ajustes(), hc.bloquesAlSpawn(p), cuenta(u, "cofres"), cuenta(u, "minijefes"), yaHoy(u),
                hc.enSpawn(p), ocupado, vivos());
        if (no == null) {
            pendientes.remove(u);
            empezarLlegada(p, false);
            return;
        }
        switch (no) {
            case "cerca", "spawn", "ocupado", "lleno" -> pendientes.add(u);
            default -> pendientes.remove(u);
        }
        if (!desdeTick && "lleno".equals(no)) hc.plugin().bitacora().anotar("vigilante", "espera", p.getName(), "lleno");
    }

    /** Si ya tiene una caida o una pelea encima (como presa). */
    private boolean conVigilante(UUID u) {
        for (Llegada l : llegadas) if (l.presa.equals(u)) return true;
        for (PeleaVigilante pe : peleas) if (pe.estado != PeleaVigilante.Estado.FIN && u.equals(pe.presa)) return true;
        return false;
    }

    /** Empieza los dos avisos y la caida. forzada: del staff, sin mirar ni gastar el dia. */
    boolean empezarLlegada(Player p, boolean forzada) {
        if (p == null || conVigilante(p.getUniqueId())) return false;
        Llegada l = new Llegada(p, forzada);
        llegadas.add(l);
        hc.amenazas().registrarPelea(l);
        hc.plugin().bitacora().anotar("vigilante", "avisa", p.getName(), forzada ? "forzado" : "cuentas",
                "bloques " + Math.round(hc.bloquesAlSpawn(p)));
        return true;
    }

    /** Llamada cada segundo desde Hardcore.tick. */
    void tick() {
        segundos++;
        peleas.removeIf(pe -> pe.estado == PeleaVigilante.Estado.FIN);
        if (!pendientes.isEmpty()) {
            for (UUID u : new ArrayList<>(pendientes)) {
                Player p = hc.plugin().getServer().getPlayer(u);
                if (p == null || !p.isOnline() || !hc.esHardcore(p) || !hc.cuenta(p)) {
                    pendientes.remove(u);
                    continue;
                }
                hc.seguro("vigilante", () -> intentar(p, true));
            }
        }
        if (segundos % 60 == 0 && tipo != null) hc.seguro("vigilante", tipo::revisar);
        if (segundos % 3600 == 0) hc.seguro("vigilante", this::podar);
    }

    /**
     * Los dos avisos y la caida, colgados de la tarea de 2 ticks de Amenazas:
     *   0 s: aviso 1 (titulo breve y barra de accion);
     *   aviso-segundos: aviso 2;
     *   2 x aviso-segundos: el meteoro aparece a 40 bloques sobre el sitio, que se marca en el suelo, y cae
     *   en caida-ticks (nunca menos de un segundo); al tocar el suelo golpea alrededor y nace el Vigilante.
     * Si la presa se va (sale, muere, entra al spawn, se desconecta) antes de que caiga, no viene y el dia
     * no se gasta.
     */
    private final class Llegada implements Runnable {
        final UUID presa;
        final String nombre;
        final boolean forzada;
        /** Los tiempos se leen al empezar: un cambio de config a medias no la deja colgada. */
        final long aviso;
        final int caidaTicks;
        long t;
        Location destino;
        BlockDisplay meteoro;
        boolean fin;

        Llegada(Player p, boolean forzada) {
            this.presa = p.getUniqueId();
            this.nombre = p.getName();
            this.forzada = forzada;
            this.aviso = ajustes().avisoSegundos * 20L;
            this.caidaTicks = ajustes().caidaTicks;
        }

        @Override
        public void run() {
            if (fin) {
                hc.amenazas().quitarPelea(this);
                return;
            }
            Ajustes a = ajustes();
            Player p = hc.plugin().getServer().getPlayer(presa);
            boolean sigue = p != null && p.isOnline() && !p.isDead() && hc.esHardcore(p) && hc.cuenta(p)
                    && (destino == null ? !hc.enSpawn(p) : p.getWorld() == destino.getWorld());
            if (!sigue) {
                cancelar(p, "se-fue");
                return;
            }
            if (t == 0) avisar(p, 1);
            else if (t == aviso) avisar(p, 2);
            else if (t == aviso * 2) {
                if (!prepararCaida(p, a)) {
                    cancelar(p, "sin-sitio");
                    return;
                }
            }
            if (destino != null) caer(a);
            t += 2;
        }

        private void avisar(Player p, int cual) {
            Component grande = Paleta.vigilante(cual == 1 ? "Algo te observa" : "El Vigilante");
            String pequena = cual == 1 ? "Una luz amarilla te sigue desde lo alto." : "Cae del cielo. Busca dónde cubrirte.";
            p.showTitle(Paleta.titulo(grande, pequena, Duration.ofMillis(200), Duration.ofMillis(1600), Duration.ofMillis(500)));
            Component barra = Component.text(cual == 1 ? "Algo te observa desde lo alto." : "El Vigilante cae sobre ti.",
                    Paleta.VIGILANTE);
            hc.barra().aviso(p, barra, 3);
            Compat.soundPlayers(p.getWorld(), p.getLocation(), cual == 1 ? "block.beacon.ambient" : "block.beacon.activate",
                    1.0f, cual == 1 ? 0.6f : 0.5f);
            if (cual == 2) {
                Component cerca = Component.text("El Vigilante cae cerca de ", Paleta.TEXTO)
                        .append(Component.text(p.getName(), Paleta.DETALLE)).append(Component.text(".", Paleta.TEXTO));
                for (Player o : Fx.viewersNear(p.getLocation(), 48)) if (!o.equals(p)) hc.barra().aviso(o, cerca, 3);
            }
        }

        /** El sitio: a llegada.distancia por detras de la presa (o delante si detras queda el spawn), con el chunk cargado. */
        private boolean prepararCaida(Player p, Ajustes a) {
            Location s = Parca.sitioDetras(p, a.llegadaDistancia);
            if (hc.enSpawn(s) || !cargado(s)) {
                Location girado = p.getLocation();
                girado.setYaw(girado.getYaw() + 180);
                s = Parca.sitioDetras(girado, a.llegadaDistancia);
            }
            if (hc.enSpawn(s) || !cargado(s)) return false;
            destino = s;
            World w = s.getWorld();
            Location alto = s.clone().add(6, 40, 6);
            try {
                meteoro = w.spawn(alto, BlockDisplay.class, d -> {
                    d.setBlock(Material.RAW_GOLD_BLOCK.createBlockData());
                    d.setPersistent(false);
                    d.setViewRange(4f);
                    d.setBrightness(new Display.Brightness(15, 15));
                    d.setGlowing(true);
                    d.setGlowColorOverride(Color.fromRGB(PeleaVigilante.RGB_OJO));
                    d.setTeleportDuration(2);
                    d.setTransformation(new Transformation(new Vector3f(-1.5f, -1.5f, -1.5f), new AxisAngle4f(),
                            new Vector3f(3f, 3f, 3f), new AxisAngle4f()));
                    d.getPersistentDataContainer().set(Marcas.VIGILANTE, PersistentDataType.STRING, presa.toString());
                });
            } catch (Throwable ignorado) {
                meteoro = null;
            }
            Compat.soundPlayers(w, s, "entity.blaze.shoot", 1.5f, 0.4f);
            return true;
        }

        /** Cada 2 ticks: el meteoro baja, deja estela y la marca del suelo avisa; al llegar, golpe y Vigilante. */
        private void caer(Ajustes a) {
            long desde = aviso * 2;
            double k = Math.min(1, (t - desde) / (double) caidaTicks);
            World w = destino.getWorld();
            Location pos = destino.clone().add(6 * (1 - k), 40 * (1 - k) + 1.5, 6 * (1 - k));
            if (meteoro != null && meteoro.isValid()) meteoro.teleport(pos);
            Compat.spawn(w, Compat.FLAME, pos, 6, 0.6, 0.6, 0.6, 0.02);
            Compat.spawn(w, Compat.LARGE_SMOKE, pos, 3, 0.5, 0.5, 0.5, 0.01);
            if ((t - desde) % 4 == 0) PeleaVigilante.circulo(w, destino, a.impactoRadio, PeleaVigilante.tono(k), 1.4f);
            if (k < 1) return;
            // Impacto.
            Fx.safeRemove(meteoro);
            meteoro = null;
            Compat.spawn(w, Compat.EXPLOSION_EMITTER, destino.clone().add(0, 0.5, 0), 1);
            Compat.spawn(w, Compat.BLOCK, destino.clone().add(0, 0.3, 0), 60, 2, 0.3, 2, 0.1,
                    Material.STONE.createBlockData());
            Compat.soundPlayers(w, destino, "entity.generic.explode", 2.0f, 0.6f);
            Compat.soundPlayers(w, destino, "entity.iron_golem.damage", 2.0f, 0.4f);
            Player p = hc.plugin().getServer().getPlayer(presa);
            List<Player> grupo = grupo(p, destino);
            Escala esc = escalaPara(p, grupo);
            PeleaVigilante pe = PeleaVigilante.crear(Vigilante.this, presa, nombre, esc, destino, grupo.size());
            if (pe == null) {
                cancelar(p, "spawn-cancelado");
                return;
            }
            // El impacto del meteoro tambien es un golpe fuerte: avisado desde que aparecio en el cielo.
            for (Player v : Fx.playersNear(destino, a.impactoRadio)) pe.golpeFuerte(v, a.impactoFraccion, "Meteoro");
            if (!forzada) {
                hc.datos().set("vigilante.visto." + presa, dia());
                hc.datos().set(base(presa), null);
                hc.marcarSucio();
            }
            Location l = pe.cuerpo.getLocation();
            hc.plugin().bitacora().anotar("vigilante", "llega", nombre, l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(),
                    forzada ? "forzado" : "cuentas", esc.texto());
            terminar();
        }

        void cancelar(Player p, String motivo) {
            if (p != null && p.isOnline() && destino == null && !"se-fue".equals(motivo)) {
                hc.barra().aviso(p, Component.text("El Vigilante pierde tu rastro.", Paleta.TEXTO), 3);
            }
            // No ha caido: el dia no se gasta y lo vuelve a intentar en medio minuto, si sigue pasada la raya.
            if (!forzada && !"desconexion".equals(motivo)) {
                reintento.put(presa, System.currentTimeMillis() + 30_000L);
                pendientes.add(presa);
            }
            hc.plugin().bitacora().anotar("vigilante", "no-llega", nombre, motivo);
            terminar();
        }

        void terminar() {
            fin = true;
            Fx.safeRemove(meteoro);
            meteoro = null;
            llegadas.remove(this);
            hc.amenazas().quitarPelea(this);
        }
    }

    static boolean cargado(Location l) {
        return l != null && l.getWorld() != null && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4);
    }

    // =================================================================== el botin

    /** El Vigilante ha caido: el reparto por la Aduana y el botin extra con su piedad. */
    void botin(PeleaVigilante pe, Map<UUID, Double> dano, double vida, long segundosPelea) {
        StringBuilder partes = new StringBuilder();
        for (Map.Entry<UUID, Double> e : dano.entrySet()) {
            if (partes.length() > 0) partes.append(",");
            partes.append(Saldo.nombre(e.getKey())).append(":").append(Math.round(vida <= 0 ? 0 : e.getValue() / vida * 100)).append("%");
        }
        hc.plugin().bitacora().anotar("vigilante", "fin", pe.presaNombre, "muerto", segundosPelea + " s", partes.toString());
        if (pe.prueba) {
            hc.plugin().bitacora().anotar("vigilante", "botin", "-", "prueba", "sin botin");
            return;
        }
        Ajustes a = ajustes();
        long ahora = System.currentTimeMillis();
        OfflinePlayer presa = pe.presa == null ? null : hc.plugin().getServer().getOfflinePlayer(pe.presa);
        Aduana ad = hc.aduana();
        List<Cobro> cobros = repartir(a, dano, vida, pe.escala.nivel(), id -> yaCobro(id, ahora, a),
                id -> presa == null || id.equals(presa.getUniqueId()) || (ad != null && hc.valor("aduana",
                        () -> ad.valida(hc.plugin().getServer().getOfflinePlayer(id), presa), false)));
        Reliquias rel = hc.reliquias();
        List<String> nombres = new ArrayList<>();
        Map<UUID, OfflinePlayer> quien = new HashMap<>();
        LinkedHashMap<UUID, Double> cobran = new LinkedHashMap<>();
        for (Cobro c : cobros) {
            OfflinePlayer op = hc.plugin().getServer().getOfflinePlayer(c.id());
            Player online = op.getPlayer();
            if (c.fraccion() >= a.participacion) nombres.add(Saldo.nombre(c.id()));
            if (c.motivo() != null) {
                hc.plugin().bitacora().anotar("vigilante", "botin", Saldo.nombre(c.id()), "esencias 0", "reliquia -", c.motivo());
                if (online != null) {
                    String porQue = !"invalida".equals(c.motivo()) || ad == null || presa == null ? ""
                            : hc.valor("aduana", () -> ad.motivoInvalida(op, presa), "");
                    String texto = sinCobro(a, c.motivo(), porQue, hc.cfg().getInt("aduana.horas-minimas", 10));
                    if (texto != null) online.sendMessage(ComandoCalamity.mensaje(texto));
                }
                continue;
            }
            List<ItemStack> reliquias = new ArrayList<>();
            if (rel != null && rel.activas()) {
                ItemStack r = hc.valor("reliquias", () -> rel.crear(c.grado(), "vigilante", null, pe.escala.nivel(), null, false), null);
                if (r != null) reliquias.add(r);
            }
            int esencias = hc.esenciasDelEquipo(online, c.esencias());
            Aduana.Pago pago = ad == null ? null : hc.valor("aduana",
                    () -> ad.pagar(op, "vigilante", esencias, 0L, reliquias, "vigilante N " + pe.escala.nivel()), null);
            int pagadas = pago == null ? 0 : pago.esencias();
            hc.datos().set("vigilante.cobro." + c.id(), ahora);
            hc.plugin().bitacora().anotar("vigilante", "botin", Saldo.nombre(c.id()), "esencias " + pagadas,
                    "reliquia " + (reliquias.isEmpty() ? "-" : Parca.romano(c.grado())), pe.presa != null && c.id().equals(pe.presa) ? "presa" : "ayudante");
            if (online != null && (pago == null || !pago.topado())) {
                Component linea = Component.text("Botín del Vigilante: ").append(Paleta.cifra(Marco.esencias(pagadas)));
                linea = reliquias.isEmpty() ? linea.append(Component.text("."))
                        : linea.append(Component.text(" y una ")).append(Paleta.detalle("Reliquia " + Parca.romano(c.grado())))
                        .append(Component.text("."));
                online.sendMessage(ComandoCalamity.mensaje(linea));
            }
            // El botin extra solo para los que han cobrado: con el tope del dia lleno no se tira nada.
            if (pago == null || !pago.topado()) {
                quien.put(c.id(), op);
                cobran.put(c.id(), c.fraccion());
            }
        }
        hc.seguro("vigilante", () -> botinExtra(pe, quien, cobran, a));
        hc.guardarYa();
        if (!nombres.isEmpty()) {
            String lista = nombres.size() == 1 ? nombres.get(0)
                    : String.join(", ", nombres.subList(0, nombres.size() - 1)) + " y " + nombres.get(nombres.size() - 1);
            Component anuncio = ComandoCalamity.mensaje(Component.text(lista, Paleta.DETALLE)
                    .append(Component.text(nombres.size() == 1 ? " ha derrotado al " : " han derrotado al "))
                    .append(Component.text("Vigilante", Paleta.VIGILANTE)).append(Component.text(".")));
            for (Player o : hc.plugin().getServer().getOnlinePlayers()) if (hc.esHardcore(o)) o.sendMessage(anuncio);
        }
    }

    private boolean yaCobro(UUID id, long ahora, Ajustes a) {
        long ultimo = hc.datos().getLong("vigilante.cobro." + id, 0);
        return ultimo > 0 && ahora - ultimo < a.horasEntreCobros * HORA;
    }

    private String rutaPiedad(UUID u, String id) {
        return "vigilante.piedad." + u + "." + id;
    }

    /**
     * La gema, las placas y el libro: las tiradas de Minijefes.tirarBotin (con su piedad) sobre los que han
     * cobrado. La piedad se guarda antes de entregar, como en Minijefes.botinExtra.
     */
    private void botinExtra(PeleaVigilante pe, Map<UUID, OfflinePlayer> quien, LinkedHashMap<UUID, Double> fr, Ajustes a) {
        if (fr.isEmpty()) return;
        Entregas ent = hc.entregas();
        List<Minijefes.Botin> entradas = disponibles(a.extra, o -> ent != null && hc.valor("entregas", () -> ent.crear(o) != null, false));
        Map<String, Integer> antes = new HashMap<>();
        for (Minijefes.Botin b : entradas) {
            if (b.piedad() <= 0) continue;
            for (UUID u : fr.keySet()) antes.put(Minijefes.clavePiedad(u, b.id()), hc.datos().getInt(rutaPiedad(u, b.id()), 0));
        }
        UUID mejor = Minijefes.mejorDe(fr, a.participacion);
        List<Minijefes.Caida> caidas = Minijefes.tirarBotin(entradas, mejor, fr, a.participacion, antes, u -> {
            OfflinePlayer op = quien.get(u);
            return op != null && op.getPlayer() != null;
        }, AZAR::nextDouble);
        boolean cae = false;
        for (Minijefes.Caida c : caidas) {
            if (c.botin().piedad() > 0 && c.piedadDespues() != c.piedadAntes()) {
                hc.datos().set(rutaPiedad(c.jugador(), c.botin().id()), c.piedadDespues() > 0 ? c.piedadDespues() : null);
            }
            cae |= c.cae();
        }
        hc.marcarSucio();
        if (!cae) return;
        hc.guardarYa();
        for (Minijefes.Caida c : caidas) {
            OfflinePlayer op = quien.get(c.jugador());
            if (c.cae() && op != null) hc.seguro("vigilante", () -> entregarExtra(c, op, pe));
        }
    }

    private void entregarExtra(Minijefes.Caida c, OfflinePlayer op, PeleaVigilante pe) {
        Minijefes.Botin b = c.botin();
        Entregas ent = hc.entregas();
        boolean ok;
        if (ent == null) {
            ok = false;
        } else if (b.objeto().equals("libro") && !ent.libroLibre()) {
            // Sin libros este mes: sus Esencias de sustituto por la Aduana, como en los minijefes.
            Aduana ad = hc.aduana();
            Aduana.Pago pago = ad == null ? null : ad.pagar(op, "vigilante", hc.cfg().getInt("caja.libro-sustituto-esencias", 20),
                    0, List.of(), "vigilante botin:" + b.id() + " sustituto");
            ok = pago != null;
        } else if (!b.objeto().isEmpty()) {
            ok = ent.dar(null, b.objeto(), op, 1, "vigilante:" + b.id());
        } else {
            ok = ent.comando(b.comando(), op.getName(), 1);
        }
        if (!ok && b.piedad() > 0) {
            int vuelta = c.participa() ? c.piedadAntes() + 1 : c.piedadAntes();
            hc.datos().set(rutaPiedad(c.jugador(), b.id()), vuelta > 0 ? vuelta : null);
        }
        hc.plugin().bitacora().anotar("vigilante", ok ? "botin-entregado" : "botin-fallo", Minijefes.nombreDe(op), b.id(),
                c.porQue(), "piedad " + c.piedadAntes(), "N " + pe.escala.nivel());
        Player p = op.getPlayer();
        Component quienDeja = Component.text().append(Component.text("El ")).append(Component.text("Vigilante", Paleta.VIGILANTE))
                .append(Component.text(" te ha dejado ")).build();
        if (p != null) {
            p.sendMessage(ComandoCalamity.mensaje(ok ? quienDeja.append(Component.text(b.nombre(), Paleta.MARCA)).append(Component.text("."))
                    : quienDeja.append(Component.text(b.nombre(), Paleta.MARCA))
                    .append(Component.text(", pero no se ha podido entregar. Avisa al staff.", Paleta.AVISO))));
        }
        if (ok && b.anuncio()) {
            hc.plugin().getServer().broadcast(ComandoCalamity.mensaje(Component.text()
                    .append(Component.text(Minijefes.nombreDe(op), Paleta.DETALLE))
                    .append(Component.text(" ha conseguido "))
                    .append(Component.text(b.nombre(), Paleta.MARCA))
                    .append(Component.text(" al vencer al "))
                    .append(Component.text("Vigilante", Paleta.VIGILANTE))
                    .append(Component.text(".")).build()));
        }
    }

    // =================================================================== EDM (a mano) y staff

    /**
     * Uno de prueba, sin presa ni botin: junto a "donde" (detras de quien este encima, si hay alguien),
     * con la escala de los que esten cerca. Lo usan /anomaly (VigilanteEdm) y /calamity vigilant test.
     */
    PeleaVigilante prueba(Location donde) {
        Player encima = Fx.nearest(donde, 4);
        Location sitio = encima != null ? Parca.sitioDetras(encima, 8) : Fx.ground(donde.clone(), 12);
        List<Player> grupo = grupo(null, sitio);
        Escala esc = escalaPara(null, grupo);
        PeleaVigilante pe = PeleaVigilante.crear(this, null, "prueba", esc, sitio, Math.max(1, grupo.size()));
        if (pe != null) {
            hc.plugin().bitacora().anotar("vigilante", "prueba", sitio.getBlockX() + " " + sitio.getBlockY() + " " + sitio.getBlockZ(),
                    esc.texto());
        }
        return pe;
    }

    private void comando(CommandSender quien, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "info";
        switch (sub) {
            case "spawn" -> {
                Player p = args.length > 2 ? hc.plugin().getServer().getPlayerExact(args[2]) : quien instanceof Player yo ? yo : null;
                if (p == null) {
                    decirAviso(quien, "No encuentro a ese jugador conectado.");
                    return;
                }
                if (!hc.esHardcore(p) || !hc.cuenta(p) || hc.enSpawn(p)) {
                    decirAviso(quien, p.getName() + " tiene que estar jugando en Calamity, fuera del spawn.");
                    return;
                }
                if (!empezarLlegada(p, true)) {
                    decirAviso(quien, p.getName() + " ya tiene un Vigilante encima.");
                    return;
                }
                decir(quien, "vigilante | cae sobre " + p.getName() + " en " + ajustes().avisoSegundos * 2 + " s (forzado: no gasta su día)");
            }
            case "test" -> {
                if (!(quien instanceof Player yo)) {
                    decirAviso(quien, "Desde la consola, usa spawn <player>.");
                    return;
                }
                PeleaVigilante pe = prueba(yo.getLocation());
                if (pe == null) decirAviso(quien, "vigilante | no ha salido (zona spawn, protección o chunk)");
                else decir(quien, "vigilante | prueba | " + pe.escala.texto());
            }
            case "info" -> info(quien, args);
            case "health" -> {
                PeleaVigilante pe = masCercano(quien);
                double f;
                try {
                    f = Double.parseDouble(args.length > 2 ? args[2] : "x");
                } catch (NumberFormatException e) {
                    decirAviso(quien, "Uso: /calamity vigilant health <0-1>");
                    return;
                }
                if (pe == null) {
                    decirAviso(quien, "No hay ningún Vigilante vivo.");
                    return;
                }
                hc.amenazas().ponerFraccion(pe.cuerpo, Math.max(0.01, Math.min(1, f)));
                decir(quien, "vigilante | vida " + Math.round(Amenazas.fraccion(pe.cuerpo) * 100) + " %");
            }
            case "ability" -> {
                PeleaVigilante pe = masCercano(quien);
                PeleaVigilante.Habilidad h = PeleaVigilante.Habilidad.buscar(args.length > 2 ? args[2] : "");
                if (pe == null || h == null) {
                    decirAviso(quien, pe == null ? "No hay ningún Vigilante vivo." : "Uso: /calamity vigilant ability <name>");
                    return;
                }
                String no = pe.forzar(h);
                if (no != null) decirAviso(quien, "vigilante | " + no);
                else decir(quien, "vigilante | " + h.alias + " | fase " + pe.fase());
            }
            case "remove" -> {
                int n = 0;
                for (PeleaVigilante pe : new ArrayList<>(peleas)) {
                    if (pe.estado == PeleaVigilante.Estado.FIN) continue;
                    pe.irse("admin:" + quien.getName(), null);
                    n++;
                }
                for (Llegada l : new ArrayList<>(llegadas)) {
                    l.terminar();
                    n++;
                }
                decir(quien, "vigilante | retirados " + n);
            }
            case "reset" -> {
                OfflinePlayer op = args.length > 2 ? hc.plugin().getServer().getOfflinePlayerIfCached(args[2]) : null;
                if (op == null) {
                    decirAviso(quien, "Uso: /calamity vigilant reset <player>");
                    return;
                }
                UUID u = op.getUniqueId();
                hc.datos().set("vigilante.visto." + u, null);
                hc.datos().set(base(u), null);
                hc.datos().set("vigilante.cobro." + u, null);
                hc.marcarSucio();
                pendientes.remove(u);
                decir(quien, "vigilante | " + op.getName() + " | sin día gastado, sin cuentas y sin cobro reciente");
            }
            default -> decirAviso(quien, "Uso: /calamity vigilant spawn [player] | test | info [player] | health <0-1>"
                    + " | ability <name> | remove | reset <player>");
        }
    }

    private void info(CommandSender quien, String[] args) {
        Ajustes a = ajustes();
        if (args.length > 2) {
            Player p = hc.plugin().getServer().getPlayerExact(args[2]);
            if (p == null) {
                decirAviso(quien, "No encuentro a ese jugador conectado.");
                return;
            }
            UUID u = p.getUniqueId();
            boolean ocupado = conVigilante(u);
            String no = motivoLlegada(a, hc.bloquesAlSpawn(p), cuenta(u, "cofres"), cuenta(u, "minijefes"), yaHoy(u),
                    hc.enSpawn(p), ocupado, vivos());
            decir(quien, "vigilante | " + p.getName() + " | " + Math.round(hc.bloquesAlSpawn(p)) + " de " + Math.round(a.llegadaBloques)
                    + " bloques | cofres " + cuenta(u, "cofres") + "/" + a.llegadaCofres + " | minijefes " + cuenta(u, "minijefes")
                    + "/" + a.llegadaMinijefes + " | hoy " + (yaHoy(u) ? "ya vino" : "libre")
                    + " | " + (no == null ? "le toca" : "no le toca: " + no) + (pendientes.contains(u) ? " (pendiente)" : ""));
            decir(quien, "vigilante | " + p.getName() + " | escala ahora: " + escalaPara(p, grupo(p, p.getLocation())).texto());
            return;
        }
        decir(quien, "vigilante | vivos " + vivos() + "/" + a.maximoVivos + " | cayendo " + llegadas.size() + " | pendientes "
                + pendientes.size() + (a.activo ? "" : " | apagado"));
        for (PeleaVigilante pe : peleas) {
            if (pe.estado == PeleaVigilante.Estado.FIN) continue;
            decir(quien, "vigilante | " + pe.estadoTexto());
        }
    }

    private PeleaVigilante masCercano(CommandSender quien) {
        PeleaVigilante mejor = null;
        double mejorD = Double.MAX_VALUE;
        for (PeleaVigilante pe : peleas) {
            if (pe.estado == PeleaVigilante.Estado.FIN || pe.cuerpo == null || !pe.cuerpo.isValid()) continue;
            double d = 0;
            if (quien instanceof Player p) {
                d = p.getWorld() == pe.cuerpo.getWorld() ? p.getLocation().distanceSquared(pe.cuerpo.getLocation()) : Double.MAX_VALUE / 2;
            }
            if (d <= mejorD) {
                mejor = pe;
                mejorD = d;
            }
        }
        return mejor;
    }

    private List<String> tab(String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 2) {
            out.addAll(List.of("spawn", "test", "info", "health", "ability", "remove", "reset"));
        } else if (args.length == 3) {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "spawn", "info", "reset" -> out.addAll(Entregas.nombresConectados());
                case "health" -> out.addAll(List.of("0.7", "0.45", "0.2"));
                case "ability" -> {
                    for (PeleaVigilante.Habilidad h : PeleaVigilante.Habilidad.values()) out.add(h.alias);
                }
                default -> {
                }
            }
        }
        return out;
    }

    private void decir(CommandSender quien, String linea) {
        quien.sendMessage(Component.text(linea, Paleta.TENUE));
    }

    private void decirAviso(CommandSender quien, String linea) {
        quien.sendMessage(Component.text(linea, Paleta.AVISO));
    }

    // =================================================================== listener

    PeleaVigilante deCuerpo(Entity e) {
        if (e == null || peleas.isEmpty()) return null;
        for (PeleaVigilante pe : peleas) if (pe.estado != PeleaVigilante.Estado.FIN && pe.esCuerpo(e)) return pe;
        return null;
    }

    private PeleaVigilante deNucleo(Entity e) {
        if (e == null || peleas.isEmpty()) return null;
        for (PeleaVigilante pe : peleas) if (pe.estado != PeleaVigilante.Estado.FIN && pe.esNucleo(e)) return pe;
        return null;
    }

    private static boolean esPieza(Entity e) {
        return e != null && e.getPersistentDataContainer().has(Marcas.VIGILANTE, PersistentDataType.STRING);
    }

    private static Entity autor(Entity e) {
        if (e instanceof Projectile pr && pr.getShooter() instanceof Entity t) return t;
        return e;
    }

    /** Muere el Vigilante: su botin. Y un nucleo muerto a mano (/kill) no suelta nada. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onMuerte(EntityDeathEvent e) {
        LivingEntity muerto = e.getEntity();
        if (esPieza(muerto)) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            return;
        }
        if (peleas.isEmpty() || !Marcas.esAmenaza(muerto)) return;
        PeleaVigilante pe = deCuerpo(muerto);
        if (pe != null) hc.seguro("vigilante", pe::alMorir);
    }

    /**
     * Golpes: a un nucleo suelto (cuenta un golpe por jugador y se cancela siempre); al Vigilante (el
     * extra si esta aturdido; Amenazas escala y topa despues, en HIGHEST); y los suyos (el atasco).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGolpe(EntityDamageByEntityEvent e) {
        if (peleas.isEmpty()) return;
        Entity victima = e.getEntity();
        Entity quien = autor(e.getDamager());
        if (esPieza(victima)) {
            e.setCancelled(true);
            PeleaVigilante pe = deNucleo(victima);
            if (pe != null && quien instanceof Player j && !hc.enSpawn(j) && Fx.isFightable(j)) {
                hc.seguro("vigilante", () -> pe.golpeNucleo(victima, j));
            }
            return;
        }
        PeleaVigilante recibe = Marcas.esAmenaza(victima) ? deCuerpo(victima) : null;
        if (recibe != null) {
            if (quien instanceof Player j && hc.enSpawn(j)) {
                e.setCancelled(true);
                return;
            }
            double f = recibe.factorRecibido();
            if (f != 1.0) e.setDamage(e.getDamage() * f);
            return;
        }
        if (victima instanceof Player) {
            PeleaVigilante da = deCuerpo(e.getDamager());
            if (da != null) da.haGolpeado();
        }
    }

    /** Los nucleos y demas piezas no reciben dano de nada (caidas, fuego, explosiones): solo cuentan golpes. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDanoPieza(EntityDamageEvent e) {
        if (!esPieza(e.getEntity()) || e.getCause() == EntityDamageEvent.DamageCause.KILL) return;
        if (e instanceof EntityDamageByEntityEvent) return;
        e.setCancelled(true);
    }

    /** Dano de verdad al Vigilante: se le nota (sonido y grietas). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDolor(EntityDamageEvent e) {
        if (peleas.isEmpty() || e.getFinalDamage() <= 0 || !Marcas.esAmenaza(e.getEntity())) return;
        PeleaVigilante pe = deCuerpo(e.getEntity());
        if (pe != null) pe.dolor();
    }

    /** Nada de chapas, riendas ni clic derecho en un nucleo. */
    @EventHandler(ignoreCancelled = true)
    public void onTocar(PlayerInteractEntityEvent e) {
        if (esPieza(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTocarEn(PlayerInteractAtEntityEvent e) {
        onTocar(e);
    }

    /** Un nucleo no apunta a nadie ni nadie a el (por si otro plugin le devuelve la IA). */
    @EventHandler(ignoreCancelled = true)
    public void onObjetivo(EntityTargetEvent e) {
        if (esPieza(e.getEntity()) || esPieza(e.getTarget())) e.setCancelled(true);
    }

    /** Se desconecta: si cayendo era su presa, no viene; si tenia permiso de vuelo prestado, se le quita. */
    @EventHandler
    public void onSalir(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        pendientes.remove(p.getUniqueId());
        for (Llegada l : new ArrayList<>(llegadas)) if (l.presa.equals(p.getUniqueId())) l.cancelar(null, "desconexion");
        for (PeleaVigilante pe : peleas) pe.soltarVuelo(p);
    }

    /** Se descarga el mundo de una pelea: se va y se lleva todo lo suyo. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDescargaMundo(WorldUnloadEvent e) {
        for (PeleaVigilante pe : new ArrayList<>(peleas)) {
            if (pe.estado != PeleaVigilante.Estado.FIN && pe.enMundo(e.getWorld())) hc.seguro("vigilante", () -> pe.irse("mundo", null));
        }
        for (Llegada l : new ArrayList<>(llegadas)) {
            if (l.destino != null && l.destino.getWorld() == e.getWorld()) l.terminar();
        }
    }

    // =================================================================== datos y parar

    /** Las cuentas de otros dias, los dias gastados viejos y los cobros de hace mas de horas-entre-cobros. */
    private void podar() {
        String hoy = dia();
        boolean cambio = false;
        ConfigurationSection s = hc.datos().getConfigurationSection("vigilante.cuenta");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                if (hoy.equals(s.getString(k + ".dia", ""))) continue;
                s.set(k, null);
                cambio = true;
            }
        }
        ConfigurationSection v = hc.datos().getConfigurationSection("vigilante.visto");
        if (v != null) {
            for (String k : v.getKeys(false)) {
                if (hoy.equals(v.getString(k, ""))) continue;
                v.set(k, null);
                cambio = true;
            }
        }
        long ahora = System.currentTimeMillis();
        long ventana = Math.max(24, ajustes().horasEntreCobros) * HORA;
        ConfigurationSection c = hc.datos().getConfigurationSection("vigilante.cobro");
        if (c != null) {
            for (String k : c.getKeys(false)) {
                if (ahora - c.getLong(k, 0) < ventana) continue;
                c.set(k, null);
                cambio = true;
            }
        }
        if (cambio) hc.marcarSucio();
    }

    /** Un reinicio acaba las peleas y las caidas: se retira todo lo que haya en el mundo (no queda nada suelto). */
    void parar() {
        for (Llegada l : new ArrayList<>(llegadas)) hc.seguro("vigilante", l::terminar);
        llegadas.clear();
        for (PeleaVigilante pe : new ArrayList<>(peleas)) hc.seguro("vigilante", pe::limpiar);
        peleas.clear();
        pendientes.clear();
        reintento.clear();
        if (tipo != null) tipo.parar();
        HandlerList.unregisterAll(this);
    }

    // =================================================================== autotest

    /**
     * "vigilante", sin servidor: los golpes fuertes entre el 25 % y el 90 % de tu vida maxima y nunca
     * mortales con la vida llena (tampoco dos juntos), el tope del 8 % por golpe que recibe el, la escala,
     * las fases, las condiciones de la llegada (1.000 bloques, 3 cofres o 1 minijefe, una vez al dia,
     * como mucho dos vivos), el reparto, el botin sin lote y que cada golpe fuerte avisa al menos un segundo.
     */
    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        Ajustes a = new Ajustes(new YamlConfiguration());

        // ---- Los golpes fuertes: entre el 25 % y el 90 %.
        h.cerca("golpe fuerte flojo sube al 25 %", 0.25, fraccionGolpe(0.05, 1, false, a), 1e-9);
        h.cerca("golpe fuerte enorme se queda en el 90 %", 0.90, fraccionGolpe(0.75, 6.688, true, a), 1e-9);
        h.cerca("golpe fuerte normal (machaque 30 %)", 0.30, fraccionGolpe(0.30, 1, false, a), 1e-9);
        h.cerca("al marcado le pega un 25 % mas (30 -> 37,5 %)", 0.375, fraccionGolpe(0.30, 1, true, a), 1e-9);
        boolean entre = true;
        for (PeleaVigilante.Habilidad x : PeleaVigilante.Habilidad.values()) {
            for (double m : new double[]{0.1, 1, 3.584, 6.688}) {
                for (boolean marca : new boolean[]{false, true}) {
                    double f = fraccionGolpe(a.hab(x).fraccion(), m, marca, a);
                    entre &= f >= 0.25 - 1e-9 && f <= 0.90 + 1e-9;
                }
            }
        }
        h.ok("todas las habilidades, con cualquier dificultad: entre el 25 % y el 90 %", entre);
        boolean nuncaMata = true;
        for (double vidaMax : new double[]{20, 40, 60, 200}) {
            double f = fraccionGolpe(0.95, 9, true, a);
            nuncaMata &= quita(f, vidaMax, a.golpeMaximo) < vidaMax;
        }
        h.ok("con la vida llena, ningun golpe fuerte mata (20, 40, 60 y 200 de vida)", nuncaMata);
        double primero = recorteVentana(0.75, 0, a.golpeMaximo);
        double segundo = recorteVentana(0.75, primero, a.golpeMaximo);
        h.cerca("dos golpes juntos: el segundo solo entra hasta el 90 % (0,75 + 0,15)", 0.90, primero + segundo, 1e-9);
        h.ok("y con la vida llena siguen sin matar", (primero + segundo) * 20 < 20);
        h.cerca("pasado el 90 % en la ventana no entra nada", 0, recorteVentana(0.3, 0.9, a.golpeMaximo), 1e-9);
        YamlConfiguration loco = new YamlConfiguration();
        loco.set("golpes.maximo", 1.5);
        h.ok("golpes.maximo nunca llega a 1 aunque la config lo diga", new Ajustes(loco).golpeMaximo < 1);

        // ---- A el: ningun golpe le quita mas del 8 % de su vida logica.
        h.cerca("tope por golpe de serie 8 %", 0.08, a.topeGolpe, 1e-9);
        double v = escala(a, 52, 1, 0, DificultadAmenaza.NEUTRO).vida();
        h.cerca("golpe de 5.000 a un Vigilante de N 60: se queda en el 8 % de su vida", 0.08 * v,
                Amenazas.golpeLogico(5000, a.topeGolpe, v), 1e-9);
        h.cerca("un golpe pequeño entra entero", 12, Amenazas.golpeLogico(12, a.topeGolpe, v), 1e-9);

        // ---- La escala: un escalon bajo la Parca.
        Escala e14 = escala(a, 6, 1, 0, DificultadAmenaza.NEUTRO);
        h.igual("N = el nivel del grupo + 8", 14, e14.nivel());
        h.cerca("vida N 14 = 360 x 2,3 = 828", 828, e14.vida(), 1e-6);
        h.cerca("golpe N 14 = 9 x 1,52 = 13,68", 13.68, e14.golpe(), 1e-6);
        Parca.Ajustes pa = new Parca.Ajustes(new YamlConfiguration());
        Escala e60 = escala(a, 52, 1, 0, DificultadAmenaza.NEUTRO);
        h.ok("por debajo de la Parca en vida y golpe (N 60)", e60.vida() < Parca.vidaLogica(pa, 60, 0, 0)
                && e60.golpe() < Parca.golpe(pa, 60, 0));
        h.cerca("tres jugadores: vida x1,7", e14.vida() * 1.7, escala(a, 6, 3, 0, DificultadAmenaza.NEUTRO).vida(), 1e-6);
        h.igual("jugadores con tope 5", 5, escala(a, 6, 12, 0, DificultadAmenaza.NEUTRO).jugadores());
        h.igual("Poder 9.000 -> 4 tramos; 100.000 -> 6 (tope)", List.of(4, 6), List.of(tramosPoder(9000, a), tramosPoder(100_000, a)));
        h.cerca("Poder 4.000: vida x1,12", e14.vida() * 1.12, escala(a, 6, 1, 4000, DificultadAmenaza.NEUTRO).vida(), 1e-6);
        DificultadAmenaza.Resultado ej = DificultadAmenaza.calcular(new DificultadAmenaza.Foto(2000, 18, 7500),
                DificultadAmenaza.Ajustes.defecto());
        Escala lejos = escala(a, 6, 1, 0, ej);
        h.cerca("lejos, sin cordura y con horas dentro: vida x2,352", e14.vida() * 2.352, lejos.vida(), 1e-6);
        h.cerca("y los golpes fuertes x3,584 (con su tope)", 3.584, lejos.multFraccion(), 1e-9);
        h.igual("nivel con tope 100", 100, nivel(a, 98));

        // ---- Las fases por vida, sin volver atras.
        h.igual("fases 80/60/40/20 %", List.of(1, 2, 3, 4),
                List.of(faseDe(0.8, 1), faseDe(0.6, 1), faseDe(0.4, 1), faseDe(0.2, 1)));
        h.igual("curarse no le devuelve a una fase anterior", 3, faseDe(0.9, 3));
        h.igual("nucleos: 4 golpes solo, 8 con tres jugadores", List.of(4, 8), List.of(golpesNucleo(a, 1), golpesNucleo(a, 3)));

        // ---- La llegada: 1.000 bloques, 3 cofres o 1 minijefe, una vez al dia, como mucho dos vivos.
        h.igual("a 999 bloques con 3 cofres: aun no", "cerca", motivoLlegada(a, 999, 3, 0, false, false, false, 0));
        h.igual("a 1.000 bloques con 3 cofres: viene", null, motivoLlegada(a, 1000, 3, 0, false, false, false, 0));
        h.igual("con 2 cofres: aun no", "faltan", motivoLlegada(a, 1500, 2, 0, false, false, false, 0));
        h.igual("con 1 minijefe: viene", null, motivoLlegada(a, 1500, 0, 1, false, false, false, 0));
        h.igual("ya vino hoy: no", "hoy", motivoLlegada(a, 1500, 3, 1, true, false, false, 0));
        h.igual("con uno vivo: viene", null, motivoLlegada(a, 1500, 3, 0, false, false, false, 1));
        h.igual("con dos vivos: espera", "lleno", motivoLlegada(a, 1500, 3, 0, false, false, false, 2));
        h.igual("en el spawn: espera", "spawn", motivoLlegada(a, 1500, 3, 0, false, true, false, 0));
        h.igual("con la Parca encima: espera", "ocupado", motivoLlegada(a, 1500, 3, 0, false, false, true, 0));
        h.ok("un cofre a 999 bloques no cuenta; a 1.000 si", !cuentaAhi(a, 999, false) && cuentaAhi(a, 1000, false));
        h.ok("un cofre en la zona spawn no cuenta", !cuentaAhi(a, 5000, true));
        YamlConfiguration apagado = new YamlConfiguration();
        apagado.set("activo", false);
        h.igual("activo: false no viene nunca", "apagado", motivoLlegada(new Ajustes(apagado), 5000, 9, 9, false, false, false, 0));
        h.igual("de serie: 1.000 bloques, 3 cofres, 1 minijefe y 2 vivos", List.of(1000.0, 3, 1, 2),
                List.of(a.llegadaBloques, a.llegadaCofres, a.llegadaMinijefes, a.maximoVivos));

        // ---- Cada golpe fuerte avisa al menos un segundo en el suelo, aunque la config diga menos.
        YamlConfiguration prisa = new YamlConfiguration();
        for (PeleaVigilante.Habilidad x : PeleaVigilante.Habilidad.values()) prisa.set("habilidades." + x.clave + ".aviso-ticks", 2);
        prisa.set("habilidades.faro.adelanto-ticks", 1);
        prisa.set("habilidades.nucleos.segundos", 0);
        prisa.set("llegada.caida-ticks", 4);
        for (Ajustes x : List.of(a, new Ajustes(prisa))) {
            int minimo = Integer.MAX_VALUE;
            String cual = "";
            for (PeleaVigilante.Habilidad hb : PeleaVigilante.Habilidad.values()) {
                int av = x.avisoDe(hb);
                if (av < 0) continue;
                if (av < minimo) {
                    minimo = av;
                    cual = hb.alias;
                }
            }
            h.ok((x == a ? "de serie" : "con avisos de 2 ticks en la config") + ": ningun golpe fuerte avisa menos de 20 ticks (minimo "
                    + minimo + ": " + cual + ")", minimo >= 20 && x.caidaTicks >= 20);
        }

        // ---- El reparto y el botin extra.
        UUID p0 = Autotest.sintetico(901), a1 = Autotest.sintetico(902), flojo = Autotest.sintetico(903);
        UUID alt = Autotest.sintetico(904), repe = Autotest.sintetico(905);
        Map<UUID, Double> dano = new HashMap<>();
        dano.put(p0, 400.0);
        dano.put(a1, 200.0);
        dano.put(flojo, 50.0);
        dano.put(alt, 150.0);
        dano.put(repe, 200.0);
        Map<UUID, Cobro> por = new HashMap<>();
        for (Cobro c : repartir(a, dano, 1000, 60, id -> id.equals(repe), id -> !id.equals(alt))) por.put(c.id(), c);
        h.ok("N 60: 5 + 6 = 11 Esencias y Reliquia IV", por.get(p0).motivo() == null && por.get(p0).esencias() == 11
                && por.get(p0).grado() == 4);
        h.ok("N 30: Reliquia III", repartir(a, Map.of(p0, 500.0), 1000, 30, id -> false, id -> true).get(0).grado() == 3);
        h.igual("5 % no cobra", "poco-dano", por.get(flojo).motivo());
        h.igual("misma conexion que la presa no cobra", "invalida", por.get(alt).motivo());
        h.igual("ya cobro en 24 h no cobra", "ya-cobro", por.get(repe).motivo());
        List<Minijefes.Botin> sinLote = disponibles(EXTRA_DE_SERIE, o -> false);
        boolean callado = true;
        for (Minijefes.Botin b : sinLote) if (!b.objeto().equals("libro")) callado &= !b.entregable();
        h.ok("sin la gema ni las placas en MMOItems: no salen (el libro si)", callado
                && sinLote.stream().anyMatch(b -> b.objeto().equals("libro") && b.entregable()));
        LinkedHashMap<UUID, Double> fr = new LinkedHashMap<>();
        fr.put(p0, 0.4);
        fr.put(a1, 0.2);
        Map<String, Integer> piedad = new HashMap<>();
        piedad.put(Minijefes.clavePiedad(p0, "gema"), 7);
        List<Minijefes.Caida> sin = Minijefes.tirarBotin(sinLote, p0, fr, a.participacion, piedad, u -> true, () -> 0.0);
        h.ok("sin lote: ni tiran ni cuentan piedad (aunque la piedad estuviera llena)",
                sin.stream().noneMatch(c -> c.botin().id().equals("gema") || c.botin().id().equals("placa")));
        List<Minijefes.Botin> conLote = disponibles(EXTRA_DE_SERIE, o -> true);
        List<Minijefes.Caida> con = Minijefes.tirarBotin(conLote, p0, fr, a.participacion, piedad, u -> true, () -> 0.99);
        h.ok("con lote: la gema cae por piedad al octavo", con.stream().anyMatch(c -> c.jugador().equals(p0)
                && c.botin().id().equals("gema") && c.cae() && "piedad".equals(c.porQue())));
        h.igual("objetos de la gema y las placas", List.of("gema-vigilante", "placa-del-vigilante"),
                List.of(Entregas.OJO_DEL_VIGILANTE, Entregas.PLACA_DEL_VIGILANTE));
        h.igual("sin cobro: poco daño", "No cobras por el Vigilante: tu daño no llegó al mínimo (10 % de su vida).",
                sinCobro(a, "poco-dano", "", 10));

        // ---- El config.yml del jar dice lo mismo que los valores de serie del codigo.
        YamlConfiguration jar = Ambush.configDelJar();
        if (jar == null) {
            h.ok("config.yml del jar encontrado", false);
        } else {
            Ajustes j = new Ajustes(jar.getConfigurationSection("hardcore.vigilante"));
            h.ok("config.yml: hardcore.vigilante esta", jar.isConfigurationSection("hardcore.vigilante"));
            h.igual("config.yml: las habilidades como las de serie", a.hab, j.hab);
            h.igual("config.yml: el botin extra como el de serie", EXTRA_DE_SERIE, j.extra);
            h.ok("config.yml: sin avisos al leer el botin extra", j.avisosBotin.isEmpty());
            h.ok("config.yml: llegada, golpes y mirada como los de serie", j.llegadaBloques == a.llegadaBloques
                    && j.llegadaCofres == a.llegadaCofres && j.llegadaMinijefes == a.llegadaMinijefes && j.maximoVivos == a.maximoVivos
                    && j.golpeMinimo == a.golpeMinimo && j.golpeMaximo == a.golpeMaximo && j.topeGolpe == a.topeGolpe
                    && j.miradaTicks == a.miradaTicks && j.vidaBase == a.vidaBase && j.golpeBase == a.golpeBase
                    && j.nucleosSegundos == a.nucleosSegundos && j.faroTicksVuelta == a.faroTicksVuelta);
            h.igual("config.yml: tope diario de la Aduana para el Vigilante", 1, jar.getInt("hardcore.aduana.topes-diarios.vigilante", -1));
            // 1.13.0: la gema ya llega con el lote de gemas (entregas.mmo.gema-vigilante); las placas
            // siguen fuera hasta que exista su plantilla, para que el selftest "mmo" no falle.
            h.igual("config.yml: la gema del Vigilante en entregas.mmo", "CALAMITY_GEMAS.GEMA_OJO_DEL_VIGILANTE",
                    jar.getString("hardcore.entregas.mmo." + Entregas.OJO_DEL_VIGILANTE));
            h.ok("config.yml: las placas no estan en entregas.mmo (van por el id de serie)",
                    !jar.isSet("hardcore.entregas.mmo." + Entregas.PLACA_DEL_VIGILANTE));
        }
        h.igual("ids de MMOItems de serie de la gema y las placas",
                List.of("CALAMITY_GEMAS.GEMA_OJO_DEL_VIGILANTE", "CALAMITY_MATERIALES.PLACA_DEL_VIGILANTE"),
                List.of(Entregas.MMO_DEFECTO.get(Entregas.OJO_DEL_VIGILANTE), Entregas.MMO_DEFECTO.get(Entregas.PLACA_DEL_VIGILANTE)));
        h.igual("Aduana: tope de serie del Vigilante", 1, Aduana.TOPES_DE_SERIE.get("vigilante"));

        // ---- La geometria de la mirada y de los pilares.
        PeleaVigilante.autotest(h, a);
        return h.lineas();
    }
}
