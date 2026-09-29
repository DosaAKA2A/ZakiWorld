package net.ederus.calamity.hardcore;

import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.boss.BossFight;
import net.ederus.edm.anomaly.core.ActiveAnomaly;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.ederus.edm.comun.Tags;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * La PARCA como anomalia DIOS de EDM (Calamity 1.1.0). Sale en /anomaly como las demas (menu, start,
 * here, test) y es la que llega a por un AFK cuando EDM esta libre; si no, sale la de reserva
 * (PeleaParca). Para el gestor es una ParcaViva mas: marca, cosecha, pendiente, botin por la
 * Aduana y exencion van igual.
 *
 * Lo que cambia respecto a la reserva es la pelea. El dueno la vio "muy rigida": iba andando,
 * soltaba un aviso y esperaba. Aqui se mueve (acometidas en linea, saltos con onda, rodeos,
 * pasos encadenados, retirada y embestida), encadena golpes con ritmo y llena el suelo de cosas
 * que obligan a moverse (guadanas que giran, lluvia de almas, muros que parten la arena, un
 * anillo que se cierra). Cuatro fases con su luto, su ambiente y su tecnica de firma:
 *   I   La Siega      caza a pie: tajos, acecho, acometida, Paso Umbral.
 *   II  El Cortejo    las planideras, la cadena, las guadanas, el salto y la embestida.
 *   III El Umbral     llueve; pasos encadenados, lluvia de almas, muros que giran.
 *   IV  La Sentencia  de noche; el anillo que se cierra y las campanadas.
 * Cada cambio de fase es un respiro con escena: se alza, suenan campanas, oscurece, se llena
 * de niebla, marca la arena y arranca con la tecnica de firma de la fase nueva.
 *
 * Reglas que se cumplen en todas las tecnicas:
 *  - todo golpe fuerte tiene su aviso en el suelo con el color que va del coral al rojo, y el
 *    aviso minimo es de 10 ticks (el autotest lo mira); el golpe cae donde se aviso;
 *  - el dano escala con N como la reserva (Parca.golpe y Parca.vidaLogica); lo que empuja pasa
 *    por push()/lift() de EDM (respeta permitir-empuje y da permiso de vuelo);
 *  - nada de EDM elige por ella: EDM la mueve cada tick y ella decide (dirigir()).
 *
 * El cuerpo: el esqueleto wither invisible de Amenazas (vida logica, tope por golpe, dano
 * logico para el botin) con el maniqui Leonsaurusrex encima (CuerpoNpc).
 */
final class ParcaAnomalia extends BossFight implements ParcaViva {

    static final String ID = "parca";

    /** Colores de las particulas: claros, para que el aviso se lea en el suelo. */
    private static final int RGB_PARCA = 0xF26A63;
    private static final int RGB_HUESO = 0xE3DCCE;
    /** Los avisos empiezan coral y acaban en rojo vivo: el color dice cuanto queda. */
    private static final int RGB_DESDE = 0xFF9E80, RGB_HASTA = 0xFF2A2A;
    private static final int RGB_SENTENCIA_HASTA = 0x8E1022;
    private static final int RGB_ALMA = 0x8FE3DA;
    /** El hueco del anillo: casi blanco, que se lea como la salida. */
    private static final int RGB_HUECO = 0xE8FFFB;
    private static final int TICKS_APARICION = 40;
    private static final int TICKS_COSECHA = 60;
    private static final int TICKS_TRANSICION = 60;
    /** Radianes por tick de la orbita de las planideras: una vuelta cada 5 s. */
    private static final double VELOCIDAD_ORBITA = Math.PI * 2 / 100;
    /** Hasta donde llega el ambiente de las fases (lluvia, noche) y la barra. */
    private static final double RADIO_AMBIENTE = 48;

    // Dano de cada tecnica en golpes (Parca.golpe, el attack_damage de su nivel).
    private static final double K_TAJO = 0.9, K_TAJO_FINAL = 1.3, K_ACECHO = 1.0, K_ACOMETIDA = 1.1;
    private static final double K_ESTELA = 0.25, K_UMBRAL = 0.9, K_GUADANA = 0.8, K_SALTO = 1.4, K_ONDA = 0.6;
    private static final double K_EMBESTIDA = 1.5, K_LLUVIA = 0.9, K_MURO = 0.5, K_ANILLO = 1.0, K_IMPLOSION = 1.2;

    /**
     * hardcore.parca.anomalia: lo propio de la de EDM. Se lee una vez por pelea (la del menu de
     * EDM, vida y dano, no manda aqui salvo el multiplicador de dano, que se suma).
     */
    static final class Ajustes {
        final boolean activa, ambiente, anuncioGlobal;
        final int nivelSinPresa;
        final double dano;
        final Set<String> apagadas = new HashSet<>();
        final Map<String, Integer> esperas = new HashMap<>();

        Ajustes(ConfigurationSection s) {
            if (s == null) s = new YamlConfiguration();
            activa = s.getBoolean("activa", true);
            ambiente = s.getBoolean("ambiente", true);
            anuncioGlobal = s.getBoolean("anuncio-global", false);
            nivelSinPresa = Math.max(1, Math.min(100, s.getInt("nivel-sin-presa", 50)));
            dano = Math.max(0.1, Math.min(10, s.getDouble("dano", 1.0)));
            for (String x : s.getStringList("apagadas")) apagadas.add(x.toLowerCase(Locale.ROOT));
            ConfigurationSection e = s.getConfigurationSection("esperas");
            if (e != null) for (String k : e.getKeys(false)) esperas.put(k.toLowerCase(Locale.ROOT), e.getInt(k));
        }

        static Ajustes leer(Hardcore hc) {
            return new Ajustes(hc.cfg().getConfigurationSection("parca.anomalia"));
        }

        boolean apagada(HabilidadParca h) {
            return apagadas.contains(h.id) || apagadas.contains(h.alias());
        }

        /** Ticks de espera de esa tecnica: la de la config (en segundos) o def. */
        int espera(HabilidadParca h, int def) {
            Integer s = esperas.get(h.id);
            if (s == null) s = esperas.get(h.alias());
            return s == null ? def : Math.max(1, s) * 20;
        }
    }

    private final PuenteAnomalia puente;
    private final Parca gestor;
    private final Hardcore hc;
    private final Parca.Ajustes a;
    private final Ajustes aj;
    /** Lo que trajo la Huella; null si la abrieron a mano desde /anomaly (prueba: sin presa ni botin). */
    private final PuenteAnomalia.Encargo encargo;
    private final boolean prueba;
    private final UUID presa;
    private final String presaNombre;
    /** Presa y marcados extra (sec. 1.4.2). */
    private final LinkedHashSet<UUID> marcados = new LinkedHashSet<>();
    private int nivel;
    private final int repeticiones;
    /** M: marcados extra (suben la vida x1,5 cada uno). */
    private int extra;
    private final double factorR;
    private double golpe;
    private int extrasGrupo;
    private final Set<UUID> participantes = new HashSet<>();

    private WitherSkeleton cuerpo;
    private final CuerpoNpc npc;
    private boolean conNpc;
    private final Cadena cadena = new Cadena();

    private Estado estado = Estado.APARECE;
    private final long nacio = System.currentTimeMillis();
    private long desdeAparece;
    private long inicioPelea;
    private Location sitioFinal;
    private boolean furia;
    private boolean pagada;
    /** Ya no hay que cerrar nada en EDM: lo esta haciendo el (cleanup) o ya se hizo. */
    private boolean edmCerrado;
    private UUID objetivoPrueba;

    /** La tecnica en curso (una a la vez) y los efectos que siguen solos (estelas, guadanas, ondas). */
    private Tecnica actual;
    private final List<Efecto> efectos = new ArrayList<>();
    private final EnumMap<HabilidadParca, Long> lista = new EnumMap<>(HabilidadParca.class);
    private HabilidadParca ultima;
    /** La tecnica de firma que arranca al acabar un cambio de fase. */
    private HabilidadParca siguiente;
    /** Tras el Tiron: el golpe que va cuando la presa toca suelo. */
    private HabilidadParca trasTiron;
    private long trasTironDesde;
    private long respiroHasta;
    private long ultimoGolpe, ultimoSalto, ultimaVision;
    private long aturdidaHasta;
    private long esperaHasta;
    private long cosechaDesde;
    /** Cuanto se alza el cuerpo que se ve en un cambio de fase. */
    private double alzado;

    /** Mini withers del Cortejo, que orbitan a la PARCA. */
    private final List<Wither> planideras = new ArrayList<>();
    private final Map<UUID, Double> huecos = new HashMap<>();
    private long planNacio;
    private boolean planActivas;
    private boolean planCaducaron;

    /** Posiciones de los jugadores a <= 32, cada 2 ticks, anillo de 20 (2 s): "quieta" para la Siega. */
    private final Map<UUID, ArrayDeque<double[]>> rastro = new HashMap<>();

    private BossBar barra;
    private final Set<UUID> viendo = new HashSet<>();
    /** A quien se le ha puesto la lluvia (fase III) y la noche (fase IV): se les quita al alejarse o al acabar. */
    private final Set<UUID> conLluvia = new HashSet<>();
    private final Set<UUID> conNoche = new HashSet<>();

    ParcaAnomalia(AnomalyPlugin plugin, ActiveAnomaly event, Location arena, PuenteAnomalia puente,
                  PuenteAnomalia.Encargo encargo) {
        super(plugin, event, arena);
        this.puente = puente;
        this.gestor = puente.gestor();
        this.hc = gestor.hc();
        this.a = gestor.ajustes();
        this.aj = Ajustes.leer(hc);
        this.encargo = encargo;
        this.prueba = encargo == null;
        this.presa = encargo == null ? null : encargo.presa();
        this.presaNombre = encargo == null ? "prueba" : encargo.nombre();
        this.repeticiones = encargo == null ? 0 : encargo.r();
        this.extra = encargo == null ? 0 : encargo.m();
        this.nivel = encargo == null ? 1 : encargo.nivel();
        this.factorR = Parca.factorR(a, repeticiones);
        this.npc = new CuerpoNpc(hc, a);
        if (encargo != null) for (Player p : encargo.grupo()) marcados.add(p.getUniqueId());
        // Para el menu y /anomaly test: la eleccion normal no pasa por EDM (ver ambient()).
        abilities.addAll(puente.tipo().abilities());
    }

    PuenteAnomalia.Encargo encargo() {
        return encargo;
    }

    // ================================================================ numeros

    /** La vida logica con la que nace: la formula de Calamity (DIS sec. 1.6), no la del menu de EDM. */
    static double vidaInicial(Parca.Ajustes a, int n, int r, int m) {
        return Parca.vidaLogica(a, n, r, m);
    }

    static double golpeInicial(Parca.Ajustes a, int n, int r) {
        return Parca.golpe(a, n, r);
    }

    /** Aviso de cada corte de los Tajos: mas corto en cada fase; el ultimo, 4 ticks mas. */
    static int avisoTajo(int fase, boolean ultimo) {
        int base = switch (Math.max(1, Math.min(4, fase))) {
            case 1 -> 14;
            case 2 -> 12;
            case 3 -> 11;
            default -> 10;
        };
        return base + (ultimo ? 4 : 0);
    }

    static int avisoAcometida(int fase, boolean segunda) {
        return segunda ? 12 : fase >= 4 ? 16 : 18;
    }

    static int avisoGuadanas(int fase) {
        return fase >= 4 ? 18 : 22;
    }

    static int vueloSalto(int fase) {
        return fase >= 4 ? 18 : fase == 3 ? 20 : 22;
    }

    static int avisoEmbestida(int fase) {
        return fase >= 4 ? 18 : fase == 3 ? 22 : 26;
    }

    static int avisoUmbrales(int fase) {
        return fase >= 4 ? 14 : 16;
    }

    static int avisoLluvia(int fase) {
        return fase >= 4 ? 18 : 22;
    }

    static int avisoMuros(int fase) {
        return fase >= 4 ? 20 : 24;
    }

    /** Corte tras cada Paso de los Umbrales, y el del final del Acecho. */
    static final int AVISO_CORTE = 10, AVISO_ACECHO = 12, AVISO_ANILLO = 24, CARGA_SALTO = 10;

    /** El aviso mas corto que tiene esa tecnica en esa fase (el autotest exige >= 10 ticks). */
    static int avisoMinimo(Parca.Ajustes a, HabilidadParca h, int fase) {
        return switch (h) {
            case SIEGA -> a.siegaAviso;
            case TAJOS -> avisoTajo(fase, false);
            case ACECHO -> AVISO_ACECHO;
            case ACOMETIDA -> Math.min(avisoAcometida(fase, false), fase >= 4 ? avisoAcometida(fase, true) : 99);
            case UMBRAL -> a.umbralAviso;
            case TIRON -> a.tironAviso;
            case CORTEJO -> 30;
            case GUADANAS -> avisoGuadanas(fase);
            case SALTO -> CARGA_SALTO + vueloSalto(fase);
            case EMBESTIDA -> avisoEmbestida(fase);
            case UMBRALES -> Math.min(avisoUmbrales(fase), AVISO_CORTE);
            case LLUVIA -> avisoLluvia(fase);
            case MUROS -> avisoMuros(fase);
            case ANILLO -> AVISO_ANILLO;
            case SENTENCIA -> a.campCada * Math.max(1, a.campToques);
        };
    }

    /** Si (dx, dz) cae en el arco de radio y angulo (grados) que mira a (dirX, dirZ). */
    static boolean enArco(double dx, double dz, double dirX, double dirZ, double radio, double angulo) {
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > radio) return false;
        if (d <= 0.8) return true;
        double dl = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (dl < 1e-9) return true;
        double cos = (dx * dirX + dz * dirZ) / (d * dl);
        return cos >= Math.cos(Math.toRadians(angulo) / 2);
    }

    /** Distancia en el plano de (px, pz) al segmento a-b. */
    static double distanciaASegmento(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax, vz = bz - az;
        double l2 = vx * vx + vz * vz;
        double k = l2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / l2));
        double cx = ax + vx * k, cz = az + vz * k;
        return Math.sqrt((px - cx) * (px - cx) + (pz - cz) * (pz - cz));
    }

    /** Angulo en (-PI, PI]. */
    static double normalizar(double ang) {
        double r = ang % (Math.PI * 2);
        if (r > Math.PI) r -= Math.PI * 2;
        if (r <= -Math.PI) r += Math.PI * 2;
        return r;
    }

    /** Si el angulo cae en el hueco (centro y ancho total, en radianes). */
    static boolean enHueco(double angulo, double hueco, double ancho) {
        return Math.abs(normalizar(angulo - hueco)) <= ancho / 2;
    }

    // ============================================================= contrato EDM

    @Override
    public String bossName() {
        return "Parca";
    }

    @Override
    public int phaseCount() {
        return 4;
    }

    /** Una sola barra, la suya: el titulo es tambien el aviso de lo que viene (como la reserva). */
    @Override
    public boolean usesOwnBars() {
        return true;
    }

    @Override
    public TextColor accent() {
        return Paleta.PARCA;
    }

    /** Lo que EDM espera tras la muerte antes de barrer: la despedida del cuerpo son 1,5 s. */
    @Override
    public int deathAnimationTicks() {
        return 60;
    }

    /** Solo avanza, y solo peleando (ni saliendo del suelo, ni cosechando). */
    @Override
    protected boolean canChangePhase(int from, int to) {
        return to > from && (estado == Estado.PELEA || estado == Estado.ESPERA);
    }

    /**
     * En los mundos de Calamity las planideras y la campanada las pone Parca.onGolpe; fuera
     * (abierta a mano en otro mundo) ese listener no mira, asi que las pone EDM.
     */
    @Override
    public double incomingDamageMultiplier(Entity damager) {
        return hc.esHardcore(world()) ? 1.0 : factorRecibido();
    }

    @Override
    public void spawn() {
        if (!puente.vivo()) throw new IllegalStateException("Calamity no está en marcha");
        Amenazas am = hc.amenazas();
        if (am == null) throw new IllegalStateException("sin el módulo de amenazas de Calamity");
        World w = arena.getWorld();
        if (w == null) throw new IllegalStateException("sin mundo");
        if (encargo != null) {
            sitioFinal = arena.clone();
        } else {
            // A mano (/anomaly here, start, at): detras de quien este encima del punto; si no, al suelo.
            Player encima = Fx.nearest(arena, 4);
            sitioFinal = encima != null ? Parca.sitioDetras(encima, 6) : Fx.ground(arena.clone(), 12);
            nivel = nivelSinPresa();
        }
        golpe = golpeInicial(a, nivel, repeticiones);
        double vida = vidaInicial(a, nivel, repeticiones, extra);
        Location bajo = sitioFinal.clone().subtract(0, 2, 0);
        conNpc = CuerpoNpc.pedido(a);
        final boolean npcPedido = conNpc;
        WitherSkeleton ws = am.invocar(WitherSkeleton.class, bajo, "parca", nivel, Paleta.muerte("Parca"), e -> {
            if (presa != null) e.getPersistentDataContainer().set(Marcas.PRESA, PersistentDataType.STRING, presa.toString());
            e.setAI(false);
            e.setInvulnerable(true);
            e.setSilent(true);
            Compat.setAttribute(e, "scale", npcPedido ? PeleaParca.escalaEsqueleto(a) : a.escala);
            Compat.setAttribute(e, "knockback_resistance", 1.0);
            Compat.setAttribute(e, "movement_speed", a.velocidad);
            Compat.setAttribute(e, "follow_range", 64);
            Compat.setAttribute(e, "step_height", 1.5);
            Compat.setAttribute(e, "attack_damage", golpe);
            if (npcPedido) {
                // Invisible y desnudo: la invisibilidad no esconde el equipo.
                e.setInvisible(true);
                EntityEquipment eq = e.getEquipment();
                if (eq != null) eq.clear();
            } else {
                PeleaParca.vestir(e, a);
            }
        });
        if (ws == null) throw new IllegalStateException("el spawn de la Parca se ha cancelado (protección o chunk)");
        cuerpo = ws;
        boss = ws;
        Tags.markBoss(ws, ID);
        if (conNpc && !npc.poner(ws, bajo, NamedTextColor.RED)) {
            conNpc = false;
            volverAEsqueleto();
        }
        am.vidaLogica(ws, vida);
        double f = encargo == null ? 1.0 : encargo.fraccion();
        if (f < 1) am.ponerFraccion(ws, f);
        am.ancla(ws, sitioFinal);
        puente.sinBotinEdm();
        puente.callada(this, encargo != null && !aj.anuncioGlobal);
        desdeAparece = ticks();
        gestor.registrar(this);
        entrada();
        if (encargo == null) {
            Location l = sitioFinal;
            hc.plugin().bitacora().anotar("parca", "llega", "prueba", "N " + nivel, "r 0", "M 0",
                    l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(), "anomalia a mano");
        }
    }

    /** N de la que se abre a mano: el del jugador mas fuerte a 64 (+ extra-nivel), o nivel-sin-presa. */
    private int nivelSinPresa() {
        int n0 = 0;
        for (Player p : Fx.playersNear(sitioFinal, 64)) {
            int n = hc.plugin().mobs() == null ? Math.max(1, hc.bonusNivel(p)) : hc.plugin().mobs().nivelCalamity(p);
            n0 = Math.max(n0, n);
        }
        return n0 > 0 ? Parca.nivel(a, n0) : aj.nivelSinPresa;
    }

    /** Sin maniqui (no se pudo, o alguien lo borro): el esqueleto se ve, a su escala y vestido. */
    private void volverAEsqueleto() {
        if (cuerpo == null || !cuerpo.isValid()) return;
        cuerpo.setInvisible(false);
        Compat.setAttribute(cuerpo, "scale", a.escala);
        PeleaParca.vestir(cuerpo, a);
    }

    // =================================================================== tick

    /**
     * Cada tick, desde BossFight.tick (EDM). busyFor(1) le quita a EDM la eleccion al azar de
     * habilidades: aqui se escogen segun la distancia, la vista y lo ultimo que hizo.
     */
    @Override
    protected void ambient() {
        busyFor(1);
        if (estado == Estado.FIN || cuerpo == null || !cuerpo.isValid() || cuerpo.isDead()) return;
        long tk = ticks();
        World w = cuerpo.getWorld();
        switch (estado) {
            case APARECE -> aparecer(w, tk);
            case PELEA -> pelear(w, tk);
            case ESPERA -> {
                if (System.currentTimeMillis() >= esperaHasta) {
                    gestor.guardarPendiente(this, System.currentTimeMillis() + a.pendienteHoras * 3_600_000L, "desconexion");
                    irse("desconexion", null);
                    return;
                }
            }
            case COSECHA -> cosechar(w, tk);
            default -> {
            }
        }
        if (estado == Estado.FIN) return;
        seguirCuerpo();
        presencia(w, tk);
        if (tk % 20 == 0) {
            ambiente();
            refrescarBarra();
        } else if (barra != null) {
            barra.progress((float) Math.max(0, Math.min(1, Amenazas.fraccion(cuerpo))));
        }
    }

    /** Sube del suelo en 2 s con almas, invulnerable y sin IA (como la reserva). */
    private void aparecer(World w, long tk) {
        long t = tk - desdeAparece;
        Location l = cuerpo.getLocation();
        if (t == 4) titulos();
        if (t < TICKS_APARICION) {
            Location sube = l.clone().add(0, 0.05, 0);
            sube.setDirection(direccionA(objetivo(), sube));
            hc.amenazas().teleportar(cuerpo, sube);
            if (t % 2 == 0) {
                Compat.spawn(w, Compat.SOUL, sitioFinal, 4, 0.5, 0.2, 0.5, 0.02);
                Compat.spawn(w, Compat.SCULK_SOUL, sitioFinal, 2, 0.4, 0.2, 0.4, 0.02);
                Compat.spawn(w, Compat.SOUL_FIRE_FLAME, sitioFinal.clone().add(0, 0.2, 0), 2, 0.25, 0.1, 0.25, 0.03);
            }
            if (t % 4 == 0) {
                double r = 0.6 + 3.4 * t / (double) TICKS_APARICION;
                Fx.ring(sitioFinal, r, (int) (r * 8) + 8, t * 0.1, p -> {
                    Compat.spawn(w, Compat.SOUL, p.clone().add(0, 0.15, 0), 1, 0.05, 0.05, 0.05, 0.01);
                    Compat.spawn(w, Compat.LARGE_SMOKE, p.clone().add(0, 0.1, 0), 1, 0.1, 0.05, 0.1, 0.005);
                });
            }
            if (t == 10 || t == 20) Compat.sound(w, sitioFinal, "block.bell.use", 4f, 0.5f);
            if (t == 30) Compat.sound(w, sitioFinal, "block.bell.use", 4f, 0.45f);
            return;
        }
        Compat.sound(w, sitioFinal, "block.sculk_shrieker.shriek", 2f, 0.6f);
        Compat.spawn(w, Compat.SOUL, sitioFinal.clone().add(0, 1, 0), 30, 0.6, 1.0, 0.6, 0.04);
        Compat.spawn(w, Compat.SOUL_FIRE_FLAME, sitioFinal.clone().add(0, 0.5, 0), 20, 0.6, 0.4, 0.6, 0.03);
        Location fin = sitioFinal.clone();
        fin.setDirection(direccionA(objetivo(), fin));
        hc.amenazas().teleportar(cuerpo, fin);
        cuerpo.setInvulnerable(false);
        cuerpo.setAI(true);
        estado = Estado.PELEA;
        inicioPelea = tk;
        ultimoGolpe = tk;
        ultimoSalto = tk;
        ultimaVision = tk;
        respiroHasta = tk + 16;
        velocidad();
    }

    private void pelear(World w, long tk) {
        if (tk % 20 == 0 && !revisarMarcados()) return;
        Player obj = objetivo();
        if (tk % 2 == 0) apuntarRastros();
        if (tk % 20 == 0) {
            if (obj != null) cuerpo.setTarget(obj);
            revisarGrupo();
        }
        if (obj != null && tk % 10 == 0 && cuerpo.hasLineOfSight(obj)) ultimaVision = tk;

        long vivo = tk - inicioPelea;
        if (!furia && a.furiaMinutos > 0 && vivo >= a.furiaMinutos * 1200L) {
            furia = true;
            velocidad();
            hc.plugin().bitacora().anotar("parca", "furia", presaNombre, "anomalia");
        }
        if (a.duracionMaxima > 0 && vivo >= a.duracionMaxima * 1200L) {
            // Se cansa: se va sin botin y vuelve si la presa entra antes de media hora (P-25).
            gestor.guardarPendiente(this, System.currentTimeMillis() + 30 * 60_000L, "cansada");
            irse("cansada", ComandoCalamity.mensaje(Parca.CANSADA));
            return;
        }

        if (tk % 2 == 0) planideras(w, tk);
        tickEfectos(w);
        if (tk < aturdidaHasta) {
            if (tk % 2 == 0) Compat.spawn(w, Compat.CRIT, cuerpo.getLocation().add(0, 1.6, 0), 6, 0.5, 0.4, 0.5, 0.1);
            return;
        } else if (aturdidaHasta > 0) {
            aturdidaHasta = 0;
            cuerpo.setAI(true);
            npc.postura(Pose.STANDING);
            velocidad();
        }

        if (actual != null) {
            // Copia: un golpe de la tecnica puede matar a la presa, y la cosecha la corta ahi mismo.
            Tecnica tec = actual;
            boolean fin;
            try {
                fin = tec.paso(w);
            } catch (Throwable t) {
                hc.plugin().getLogger().warning("[Calamity] Fallo en la técnica " + tec.rotulo() + " de la Parca: " + t);
                fin = true;
            }
            tec.t++;
            if (fin && actual == tec && estado == Estado.PELEA) acabar(tk);
            return;
        }
        if (obj == null) return;
        dirigir(obj, tk);
    }

    /** Los efectos que siguen solos: estelas, guadanas en vuelo, ondas, la arena marcada. */
    private void tickEfectos(World w) {
        if (efectos.isEmpty()) return;
        for (Iterator<Efecto> it = new ArrayList<>(efectos).iterator(); it.hasNext(); ) {
            // Un efecto puede matar a la presa: con la cosecha ya no queda ninguno.
            if (estado != Estado.PELEA) return;
            Efecto e = it.next();
            boolean fin;
            try {
                fin = e.paso(w);
            } catch (Throwable t) {
                fin = true;
            }
            e.t++;
            if (fin) {
                e.quitar();
                efectos.remove(e);
            }
        }
    }

    private void quitarEfectos() {
        for (Efecto e : efectos) e.quitar();
        efectos.clear();
    }

    // ================================================================ decidir

    /**
     * Que hace ahora. Entre tecnica y tecnica hay un respiro corto (mas corto en cada fase) en el
     * que persigue andando y pega con la guadana; luego elige con pesos segun la situacion: de
     * cerca corta, de lejos acomete, salta o tira de la cadena, y si la pierden de vista cruza
     * el umbral. Lo ultimo que hizo pesa un cuarto: no repite si tiene otra cosa.
     */
    private void dirigir(Player obj, long tk) {
        Location yo = cuerpo.getLocation();
        double dist = obj.getWorld() == yo.getWorld() ? obj.getLocation().distance(yo) : Double.MAX_VALUE;

        // Red de atasco (sec. 1.9): sin golpear ni saltar atasco-segundos -> encima de la presa, sin aviso.
        if (tk - Math.max(ultimoGolpe, ultimoSalto) >= a.atasco * 20L) {
            Location sobre = obj.getLocation().clone();
            sobre.setDirection(obj.getLocation().getDirection().multiply(-1));
            hc.amenazas().teleportar(cuerpo, sobre);
            ultimoSalto = tk;
            hc.plugin().bitacora().anotar("parca", "atasco", presaNombre, "anomalia");
            return;
        }
        if (siguiente != null) {
            HabilidadParca h = siguiente;
            siguiente = null;
            if (!aj.apagada(h) && (h != HabilidadParca.CORTEJO || planideras.isEmpty())) {
                empezar(h, obj, tk);
                return;
            }
        }
        if (trasTiron != null) {
            if (tk - trasTironDesde >= 6 && obj.isOnGround()) {
                HabilidadParca h = trasTiron;
                trasTiron = null;
                if (dist <= 7) {
                    empezar(h, obj, tk);
                    return;
                }
            } else if (tk - trasTironDesde < 40) {
                return;
            } else {
                trasTiron = null;
            }
        }
        if (tk < respiroHasta) return;
        HabilidadParca h = elegir(obj, dist, tk);
        if (h != null) empezar(h, obj, tk);
    }

    private HabilidadParca elegir(Player obj, double dist, long tk) {
        int f = phase();
        boolean vista = tk - ultimaVision < 30;
        boolean lejos = dist > a.umbralDistancia || tk - ultimaVision >= a.umbralSinVision * 20L
                || tk - ultimoGolpe >= a.umbralSinGolpear * 20L;
        EnumMap<HabilidadParca, Double> pesos = new EnumMap<>(HabilidadParca.class);
        double total = 0;
        for (HabilidadParca h : HabilidadParca.values()) {
            if (!h.enFase(f) || !listo(h, tk) || aj.apagada(h)) continue;
            double p = h.peso;
            switch (h) {
                case SIEGA -> {
                    if (dist > a.siegaRadio + 1) continue;
                    if (dist < 3.5) p *= 2;
                }
                case TAJOS -> {
                    if (dist > 6) continue;
                    p *= dist < 4 ? 2.5 : 1.2;
                }
                case ACECHO -> {
                    if (dist > 14) continue;
                    if (dist > 8) p *= 0.8;
                }
                case ACOMETIDA -> {
                    if (dist < 5 || dist > 20) continue;
                    if (dist >= 8) p *= 2.5;
                }
                case UMBRAL -> {
                    if (!lejos) continue;
                    p *= 4;
                }
                case UMBRALES -> {
                    if (!lejos && dist > 16) continue;
                    if (lejos) p *= 4;
                }
                case TIRON -> {
                    if (dist < a.tironMin || dist > a.tironMax || !cuerpo.hasLineOfSight(obj)) continue;
                    p *= 2;
                }
                case CORTEJO -> {
                    if (!planideras.isEmpty()) continue;
                }
                case GUADANAS -> {
                    if (dist > 20) continue;
                }
                case SALTO -> {
                    if (dist > 18) continue;
                    p *= dist >= 7 ? 2 : 0.8;
                    if (!vista) p *= 1.5;
                }
                case EMBESTIDA, MUROS -> {
                    if (dist > 13) continue;
                }
                case ANILLO -> {
                    if (dist > 12) continue;
                }
                default -> {
                }
            }
            if (h == ultima) p *= 0.25;
            pesos.put(h, p);
            total += p;
        }
        if (pesos.isEmpty() || total <= 0) return null;
        double tirada = ThreadLocalRandom.current().nextDouble(total);
        for (Map.Entry<HabilidadParca, Double> e : pesos.entrySet()) {
            tirada -= e.getValue();
            if (tirada < 0) return e.getKey();
        }
        return pesos.keySet().iterator().next();
    }

    private boolean listo(HabilidadParca h, long tk) {
        return tk >= lista.getOrDefault(h, 0L);
    }

    /** Ticks hasta que la vuelve a usar: los de la config de la reserva donde los hay, x0,7 con furia. */
    private int espera(HabilidadParca h) {
        int base = switch (h) {
            case SIEGA -> (phase() >= 3 ? a.siegaEsperaF3 : a.siegaEspera) * 20;
            case UMBRAL -> a.umbralEspera * 20;
            case TIRON -> a.tironEspera * 20;
            case CORTEJO -> a.planEspera * 20;
            case SENTENCIA -> a.campEspera * 20;
            default -> h.espera;
        };
        base = aj.espera(h, base);
        return (int) Math.round(base * (furia ? 0.7 : 1.0));
    }

    /** El respiro entre tecnicas: 18-28 ticks en la I, 6-12 en la IV. */
    private int respiro() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int t = switch (phase()) {
            case 1 -> 18 + r.nextInt(11);
            case 2 -> 14 + r.nextInt(9);
            case 3 -> 10 + r.nextInt(8);
            default -> 6 + r.nextInt(7);
        };
        return (int) Math.round(t * (furia ? 0.7 : 1.0));
    }

    private void empezar(HabilidadParca h, Player obj, long tk) {
        Tecnica tec = switch (h) {
            case SIEGA -> new Siega(obj);
            case TAJOS -> new Tajos(obj);
            case ACECHO -> new Acecho(obj);
            case ACOMETIDA -> new Acometida(obj);
            case UMBRAL -> new Umbral(obj);
            case TIRON -> new Tiron(obj);
            case CORTEJO -> new Cortejo(obj);
            case GUADANAS -> new Guadanas(obj);
            case SALTO -> new Salto(obj);
            case EMBESTIDA -> new Embestida(obj);
            case UMBRALES -> new Umbrales(obj);
            case LLUVIA -> new Lluvia();
            case MUROS -> new Muros(obj);
            case ANILLO -> new Anillo();
            case SENTENCIA -> new Sentencia();
        };
        lista.put(h, tk + espera(h));
        ultima = h;
        Compat.setAttribute(cuerpo, "movement_speed", 0);
        actual = tec;
        nombreBarra();
    }

    private void acabar(long tk) {
        if (actual != null) actual.cortar();
        actual = null;
        cadena.quitar();
        alzado = 0;
        npc.postura(Pose.STANDING);
        if (cuerpo != null && cuerpo.isValid()) velocidad();
        respiroHasta = tk + respiro();
        nombreBarra();
    }

    /** Corta la tecnica en curso sin respiro (aturdida, transicion, cosecha, espera, fin). */
    private void cortarTecnica() {
        if (actual != null) actual.cortar();
        actual = null;
        cadena.quitar();
        alzado = 0;
        npc.postura(Pose.STANDING);
    }

    /**
     * /lw hardcore parca habilidad <nombre> (y /anomaly test): suelta esa tecnica ya, en
     * cualquier fase, para ver los avisos y los golpes con un cliente. Devuelve por que no, o null.
     */
    @Override
    public String forzar(String nombre, Player quien) {
        if (estado != Estado.PELEA) return "no está peleando (si acaba de salir, espera 2 s)";
        if (actual instanceof Transicion) return "está cambiando de fase";
        HabilidadParca h = HabilidadParca.buscar(nombre);
        if (h == null) return "habilidades: " + String.join(", ", HabilidadParca.nombres());
        Player obj = quien != null && cuerpo != null && quien.getWorld() == cuerpo.getWorld() ? quien : objetivo();
        if (obj == null) return "necesita un jugador en el mundo";
        if (h == HabilidadParca.CORTEJO && !planideras.isEmpty()) return "ya hay plañideras";
        cortarTecnica();
        empezar(h, obj, ticks());
        return null;
    }

    /** Desde la Ability de EDM (/anomaly test): sin jugador que lo pida, a su objetivo. */
    void forzarDesdeEdm(HabilidadParca h) {
        hc.seguro("parca", () -> forzar(h.alias(), null));
    }

    // ================================================================ fases

    @Override
    protected void onPhaseChange(int from, int to) {
        if (estado == Estado.FIN || cuerpo == null) return;
        hc.plugin().bitacora().anotar("parca", "fase", String.valueOf(to), presaNombre, "anomalia");
        cortarTecnica();
        quitarEfectos();
        trasTiron = null;
        siguiente = null;
        actual = new Transicion(to);
        nombreBarra();
        String texto = Parca.consejoFase(to);
        for (UUID id : marcados) {
            Player m = hc.plugin().getServer().getPlayer(id);
            if (m != null) hc.cordura().destello(m, Component.text(texto, Paleta.TEXTO), 3);
        }
    }

    private static String nombreFase(int f) {
        return switch (f) {
            case 1 -> "La Siega";
            case 2 -> "El Cortejo";
            case 3 -> "El Umbral";
            default -> "La Sentencia";
        };
    }

    private static HabilidadParca firma(int f) {
        return switch (f) {
            case 2 -> HabilidadParca.CORTEJO;
            case 3 -> HabilidadParca.UMBRALES;
            case 4 -> HabilidadParca.SENTENCIA;
            default -> null;
        };
    }

    // ============================================================== consultas

    @Override
    public Estado estado() {
        return estado;
    }

    @Override
    public boolean prueba() {
        return prueba;
    }

    @Override
    public UUID presa() {
        return presa;
    }

    @Override
    public String presaNombre() {
        return presaNombre;
    }

    @Override
    public Set<UUID> marcados() {
        return marcados;
    }

    @Override
    public LivingEntity cuerpo() {
        return cuerpo;
    }

    @Override
    public int nivel() {
        return nivel;
    }

    @Override
    public int repeticiones() {
        return repeticiones;
    }

    @Override
    public int extra() {
        return extra;
    }

    @Override
    public int fase() {
        return phase();
    }

    @Override
    public int extrasGrupo() {
        return extrasGrupo;
    }

    @Override
    public String tipo() {
        return "anomalia";
    }

    @Override
    public boolean vivaParaJugadores() {
        return estado != Estado.FIN && cuerpo != null && cuerpo.isValid() && !cuerpo.isDead();
    }

    @Override
    public boolean aceptaMarcados() {
        return (estado == Estado.PELEA || estado == Estado.APARECE) && vivaParaJugadores();
    }

    @Override
    public boolean hayMarcadoEnMundo() {
        for (UUID id : marcados) {
            Player m = hc.plugin().getServer().getPlayer(id);
            if (m != null && cuerpo != null && m.getWorld() == cuerpo.getWorld()) return true;
        }
        return false;
    }

    @Override
    public double vidaFinal() {
        return cuerpo == null ? 0 : hc.amenazas().vidaLogicaMaxima(cuerpo);
    }

    @Override
    public boolean esCuerpo(Entity e) {
        return e != null && cuerpo != null && cuerpo.getUniqueId().equals(e.getUniqueId());
    }

    @Override
    public boolean esCascara(Entity e) {
        return npc.es(e);
    }

    @Override
    public boolean esPlanidera(Entity e) {
        if (e == null || planideras.isEmpty()) return false;
        for (Wither v : planideras) if (v.getUniqueId().equals(e.getUniqueId())) return true;
        return false;
    }

    /** x0,5 con planideras, +25 % aturdida, +25 % mientras toca la Sentencia. */
    @Override
    public double factorRecibido() {
        double f = 1;
        if (!planideras.isEmpty()) f *= a.planReduccion;
        if (ticks() < aturdidaHasta) f *= 1 + a.planAturdidaExtra;
        if (actual instanceof Sentencia) f *= 1 + a.campExtra;
        return f;
    }

    /** En la abierta a mano, quien le pega pasa a ser su objetivo. */
    @Override
    public void golpeadaPor(Player j) {
        if (prueba) objetivoPrueba = j.getUniqueId();
    }

    @Override
    public void haGolpeado() {
        ultimoGolpe = ticks();
    }

    @Override
    public void blandir() {
        if (cuerpo != null && cuerpo.isValid()) cuerpo.swingMainHand();
        npc.blandir();
    }

    @Override
    public void dolor() {
        npc.dolor();
    }

    /** Las fases las lleva EDM por la vida (BossFight.tick): se ven en el tick siguiente. */
    @Override
    public void revisarFase() {
    }

    // ================================================================ objetivo

    private static Vector direccionA(Player p, Location desde) {
        if (p == null || p.getWorld() != desde.getWorld()) return desde.getDirection();
        Vector v = p.getLocation().toVector().subtract(desde.toVector()).setY(0);
        return v.lengthSquared() < 1e-4 ? desde.getDirection() : v;
    }

    private void velocidad() {
        if (cuerpo == null) return;
        Compat.setAttribute(cuerpo, "movement_speed", a.velocidad * (furia ? 1.2 : 1.0));
    }

    /** A quien va: la presa si esta a tiro; si no, el marcado mas cercano. A mano: quien le pego o el mas cercano. */
    private Player objetivo() {
        if (cuerpo == null) return null;
        World w = cuerpo.getWorld();
        if (prueba) {
            Player p = objetivoPrueba == null ? null : hc.plugin().getServer().getPlayer(objetivoPrueba);
            if (p != null && p.getWorld() == w && Fx.isFightable(p)
                    && p.getLocation().distanceSquared(cuerpo.getLocation()) <= 48 * 48) return p;
            return Fx.nearest(cuerpo.getLocation(), 32);
        }
        Player p = presa == null ? null : hc.plugin().getServer().getPlayer(presa);
        if (p != null && p.getWorld() == w && Fx.isFightable(p)) return p;
        Player mejor = null;
        double mejorD = Double.MAX_VALUE;
        for (UUID id : marcados) {
            Player m = hc.plugin().getServer().getPlayer(id);
            if (m == null || m.getWorld() != w || !Fx.isFightable(m)) continue;
            double d = m.getLocation().distanceSquared(cuerpo.getLocation());
            if (d < mejorD) {
                mejor = m;
                mejorD = d;
            }
        }
        return mejor;
    }

    private Player jugador(UUID id) {
        Player p = id == null ? null : hc.plugin().getServer().getPlayer(id);
        return p != null && cuerpo != null && p.getWorld() == cuerpo.getWorld() && Fx.isFightable(p) ? p : null;
    }

    /**
     * Cada segundo: la presa sigue siendo presa. Fuera del mundo por otra via = como la puerta;
     * creativo, espectador o exento = se va sin pendiente (sec. 1.9). False si ha acabado.
     */
    private boolean revisarMarcados() {
        if (prueba) return true;
        for (Iterator<UUID> it = marcados.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            Player m = hc.plugin().getServer().getPlayer(id);
            if (m == null) continue;
            boolean esPresa = id.equals(presa);
            if (!hc.cuenta(m) || gestor.exento(m)) {
                if (esPresa) {
                    irse("exento", null);
                    return false;
                }
                it.remove();
                continue;
            }
            if (!hc.esHardcore(m) || m.getWorld() != cuerpo.getWorld()) {
                if (esPresa) {
                    gestor.alSalir(m, "otra-via");
                    return false;
                }
                it.remove();
            }
        }
        if (marcados.isEmpty()) {
            irse("sin-presas", null);
            return false;
        }
        return true;
    }

    /** Dureza en grupo: un participante nuevo (no la presa) desde el segundo sube vida y golpe x1,25. */
    private void revisarGrupo() {
        Map<UUID, Double> dano = hc.amenazas().danoLogico(cuerpo);
        double vida = vidaFinal();
        for (Map.Entry<UUID, Double> e : dano.entrySet()) {
            UUID id = e.getKey();
            if (participantes.contains(id) || e.getValue() < a.participacionMinima * vida) continue;
            participantes.add(id);
            if (prueba || !Parca.subeGrupo(participantes.size(), id.equals(presa), extrasGrupo, a)) continue;
            double f = Amenazas.fraccion(cuerpo);
            extrasGrupo++;
            hc.amenazas().vidaLogica(cuerpo, vida * (1 + a.grupoExtra));
            hc.amenazas().ponerFraccion(cuerpo, f);
            golpe *= 1 + a.grupoExtra;
            Compat.setAttribute(cuerpo, "attack_damage", golpe);
            vida = vidaFinal();
            hc.plugin().bitacora().anotar("parca", "grupo", presaNombre, String.valueOf(participantes.size()),
                    "extra " + extrasGrupo, "anomalia");
        }
    }

    @Override
    public void agregarMarcado(Player p) {
        if (!marcados.add(p.getUniqueId())) return;
        double f = Amenazas.fraccion(cuerpo);
        extra++;
        hc.amenazas().vidaLogica(cuerpo, hc.amenazas().vidaLogicaMaxima(cuerpo)
                * (1 + a.vidaPorMarcado * extra) / (1 + a.vidaPorMarcado * (extra - 1)));
        hc.amenazas().ponerFraccion(cuerpo, f);
        alMarcar(p);
        gestor.retirarMinijefes(p);
        hc.plugin().bitacora().anotar("parca", "marcado", presaNombre, p.getName(), "M " + extra);
    }

    @Override
    public void quitarMarcado(UUID id, String motivo) {
        if (!marcados.remove(id)) return;
        if (!prueba && marcados.isEmpty()) irse("sin-presas:" + motivo, null);
    }

    // ============================================================ presentacion

    /** Lo que ve un marcado al quedar marcado: titulo P-08; si iba montado, abajo. */
    private void alMarcar(Player p) {
        if (p.isInsideVehicle()) p.leaveVehicle();
        p.showTitle(Paleta.titulo(Paleta.muerte("PARCA"), "Te quedaste quieto demasiado tiempo.",
                Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(1000)));
    }

    /**
     * La entrada, como la reserva: a 32 se apaga el cielo (Oscuridad 2 s, que acaba justo
     * cuando ella puede moverse), campanas graves y algo que sale de la tierra.
     */
    private void entrada() {
        World w = sitioFinal.getWorld();
        Compat.sound(w, sitioFinal, "block.respawn_anchor.deplete", 4f, 0.5f);
        Compat.sound(w, sitioFinal, "block.bell.use", 4f, 0.5f);
        Compat.sound(w, sitioFinal, "entity.warden.emerge", 3f, 0.8f);
        for (Player p : Fx.playersNear(sitioFinal, 32)) Compat.apply(p, "darkness", TICKS_APARICION, 0);
    }

    /**
     * Los titulos van 4 ticks despues del spawn: EDM saca el suyo (el nombre y las coordenadas)
     * a todo el servidor justo despues de spawn() y taparia el de los marcados.
     */
    private void titulos() {
        Title ajeno = Paleta.titulo(Paleta.muerte("PARCA"), prueba ? "Anomalía DIOS." : "Ha venido a buscar a alguien.",
                Duration.ofMillis(500), Duration.ofMillis(2500), Duration.ofMillis(1000));
        for (Player p : Fx.viewersNear(sitioFinal, 40)) {
            if (marcados.contains(p.getUniqueId())) alMarcar(p);
            else p.showTitle(ajeno);
        }
        for (UUID id : marcados) {
            Player p = hc.plugin().getServer().getPlayer(id);
            if (p != null && p.getWorld() == sitioFinal.getWorld() && p.getLocation().distanceSquared(sitioFinal) > 40 * 40) {
                alMarcar(p);
            }
        }
    }

    /**
     * Cada tick: el maniqui se pega al esqueleto. Mira a donde va el golpe durante una tecnica y,
     * si no, a los ojos de su objetivo. En un cambio de fase se alza un poco. Sin maniqui,
     * vuelve el esqueleto vestido.
     */
    private void seguirCuerpo() {
        if (!conNpc) return;
        Location l = cuerpo.getLocation();
        Vector m = actual != null ? actual.mira() : null;
        if (m != null && m.lengthSquared() > 1e-4) {
            l.setDirection(m);
        } else {
            Player obj = objetivo();
            if (obj != null && obj.getWorld() == l.getWorld()) {
                Vector v = obj.getEyeLocation().toVector().subtract(cuerpo.getEyeLocation().toVector());
                if (v.lengthSquared() > 1e-4) l.setDirection(v);
            }
        }
        if (alzado > 0) l.add(0, alzado, 0);
        if (!npc.seguir(l)) {
            conNpc = false;
            volverAEsqueleto();
        }
    }

    /**
     * Presencia: almas y ceniza a los pies, el ambiente cada 6 s, una campanada suave cada
     * 10 s. Desde la fase II una niebla de ceniza alrededor; en la IV, una campana cada 5 s.
     */
    private void presencia(World w, long tk) {
        Location l = cuerpo.getLocation();
        if (tk % 10 == 0) {
            Compat.spawn(w, Compat.SOUL, l.clone().add(0, 0.2, 0), 2, 0.4, 0.1, 0.4, 0.01);
            Compat.spawn(w, Compat.ASH, l.clone().add(0, 0.2, 0), 4, 0.6, 0.2, 0.6, 0.01);
            Compat.spawn(w, Compat.SMOKE, l.clone().add(0, 0.1, 0), 1, 0.3, 0.05, 0.3, 0.005);
            int f = phase();
            if (f >= 2 && estado == Estado.PELEA) {
                Compat.spawn(w, Compat.WHITE_ASH, l.clone().add(0, 1.5, 0), 8 + 4 * f, 7, 1.5, 7, 0.01);
                Compat.spawn(w, Compat.LARGE_SMOKE, l.clone().add(0, 0.3, 0), f, 5, 0.2, 5, 0.005);
            }
            if (f >= 3 && estado == Estado.PELEA) {
                Compat.spawn(w, Compat.SCULK_SOUL, l.clone().add(0, 0.5, 0), 3, 6, 0.4, 6, 0.01);
            }
        }
        if (estado == Estado.PELEA && tk % 120 == 0) Compat.sound(w, l, "entity.wither_skeleton.ambient", 1f, 0.5f);
        if (tk % 200 == 0) Compat.sound(w, l, "block.bell.use", 0.4f, 0.5f);
        if (phase() >= 4 && estado == Estado.PELEA && tk % 100 == 50) Compat.sound(w, l, "block.bell.use", 1.2f, 0.45f);
    }

    /**
     * El cielo de cada fase, solo para quien este a 48 (por jugador: el mundo no se toca): en la
     * III llueve, en la IV ademas es de noche. Al alejarse o al acabar se devuelve el suyo.
     */
    private void ambiente() {
        if (!aj.ambiente || cuerpo == null) return;
        int f = estado == Estado.PELEA || estado == Estado.ESPERA ? phase() : 0;
        Set<UUID> cerca = new HashSet<>();
        if (f >= 3) for (Player p : Fx.viewersNear(cuerpo.getLocation(), RADIO_AMBIENTE)) cerca.add(p.getUniqueId());
        for (UUID id : cerca) {
            Player p = hc.plugin().getServer().getPlayer(id);
            if (p == null) continue;
            if (conLluvia.add(id)) p.setPlayerWeather(WeatherType.DOWNFALL);
            if (f >= 4 && conNoche.add(id)) p.setPlayerTime(18000, false);
        }
        for (Iterator<UUID> it = conLluvia.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (cerca.contains(id)) continue;
            it.remove();
            Player p = hc.plugin().getServer().getPlayer(id);
            if (p != null) p.resetPlayerWeather();
        }
        for (Iterator<UUID> it = conNoche.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (cerca.contains(id) && f >= 4) continue;
            it.remove();
            Player p = hc.plugin().getServer().getPlayer(id);
            if (p != null) p.resetPlayerTime();
        }
    }

    private void quitarAmbiente() {
        for (UUID id : conLluvia) {
            Player p = hc.plugin().getServer().getPlayer(id);
            if (p != null) p.resetPlayerWeather();
        }
        for (UUID id : conNoche) {
            Player p = hc.plugin().getServer().getPlayer(id);
            if (p != null) p.resetPlayerTime();
        }
        conLluvia.clear();
        conNoche.clear();
    }

    // ================================================================= barra

    /** "Parca · II · Tajos": la fase y lo que esta haciendo, en rojo claro mientras dura el golpe. */
    private Component tituloBarra() {
        Component resto;
        if (actual != null) {
            resto = Component.text(actual.rotulo(), Paleta.AVISO);
        } else if (furia) {
            resto = Component.text("Furia", Paleta.AVISO);
        } else {
            resto = Component.text("Nv. " + nivel, Paleta.TEXTO);
        }
        return Paleta.muerte("Parca").append(Component.text(" · ", Paleta.SEPARADOR))
                .append(Component.text(Parca.romano(Math.max(1, phase())), Paleta.DETALLE))
                .append(Component.text(" · ", Paleta.SEPARADOR)).append(resto);
    }

    private void nombreBarra() {
        if (barra != null) barra.name(tituloBarra());
    }

    /** Para los marcados y quien este a <= 48; se recalcula cada segundo. */
    private void refrescarBarra() {
        if (cuerpo == null) return;
        if (barra == null) {
            barra = BossBar.bossBar(tituloBarra(), (float) Amenazas.fraccion(cuerpo), BossBar.Color.RED,
                    BossBar.Overlay.NOTCHED_10);
        }
        barra.progress((float) Math.max(0, Math.min(1, Amenazas.fraccion(cuerpo))));
        Set<UUID> ahora = new HashSet<>();
        for (Player p : Fx.viewersNear(cuerpo.getLocation(), RADIO_AMBIENTE)) ahora.add(p.getUniqueId());
        for (UUID id : marcados) {
            Player m = hc.plugin().getServer().getPlayer(id);
            if (m != null && m.getWorld() == cuerpo.getWorld()) ahora.add(id);
        }
        for (UUID id : ahora) {
            if (viendo.contains(id)) continue;
            Player p = hc.plugin().getServer().getPlayer(id);
            if (p != null) p.showBossBar(barra);
        }
        for (UUID id : viendo) {
            if (ahora.contains(id)) continue;
            Player p = hc.plugin().getServer().getPlayer(id);
            if (p != null) p.hideBossBar(barra);
        }
        viendo.clear();
        viendo.addAll(ahora);
    }

    private void quitarBarra() {
        if (barra == null) return;
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) p.hideBossBar(barra);
        viendo.clear();
    }

    // ============================================================ utilidades

    private Location pie() {
        return cuerpo.getLocation();
    }

    /** La direccion en el plano, unitaria (hacia delante si no hay direccion). */
    private static Vector plano(Vector v) {
        Vector c = v.clone().setY(0);
        return c.lengthSquared() < 1e-6 ? new Vector(0, 0, 1) : c.normalize();
    }

    private static Vector hacia(Location desde, Location hasta) {
        return plano(hasta.toVector().subtract(desde.toVector()));
    }

    private static Location enSuelo(Location l) {
        return Fx.ground(l, 4).add(0, 0.12, 0);
    }

    private static void polvo(World w, Location l, int rgb, float tam) {
        Compat.spawn(w, Compat.DUST, l, 1, 0, 0, 0, 0, Compat.dust(rgb, tam));
    }

    private static int tono(double avance) {
        return PeleaParca.mezcla(RGB_DESDE, RGB_HASTA, avance);
    }

    private boolean mover(Location destino) {
        return hc.amenazas().teleportar(cuerpo, destino);
    }

    /**
     * Un paso a ras de suelo desde "desde" en "dir": sube o baja escalones de hasta 1,5 bloques.
     * Null si hay pared o precipicio: las tecnicas que la mueven no atraviesan el mundo.
     * (1.8.0: tambien la usa la acometida de Ambush, por eso no es privada.)
     */
    static Location pisar(Location desde, Vector dir, double largo) {
        Location c = desde.clone().add(dir.clone().multiply(largo));
        Location g = Fx.ground(c.clone().add(0, 1.5, 0), 4);
        if (Math.abs(g.getY() - desde.getY()) > 1.6) return null;
        if (!Parca.libre(g, 2)) return null;
        g.setDirection(dir);
        return g;
    }

    /** Los puntos (cada 0,5) de una carrera en linea recta, cortada donde haya pared. */
    static void trazar(Location desde, Vector dir, double largo, List<Location> ruta) {
        ruta.clear();
        Location cur = desde.clone();
        cur.setDirection(dir);
        ruta.add(cur.clone());
        for (double d = 0.5; d <= largo; d += 0.5) {
            Location s = pisar(cur, dir, 0.5);
            if (s == null) break;
            ruta.add(s);
            cur = s;
        }
    }

    /** El camino avisado en el suelo: centro y bordes a +-ancho, del coral al rojo segun avance. */
    private static void pintarRuta(World w, List<Location> ruta, Vector dir, double avance, double ancho) {
        int rgb = tono(avance);
        Vector lado = new Vector(-dir.getZ(), 0, dir.getX()).multiply(ancho);
        for (int i = 0; i < ruta.size(); i += 2) {
            Location l = ruta.get(i).clone().add(0, 0.15, 0);
            polvo(w, l, rgb, 1.3f);
            polvo(w, l.clone().add(lado), rgb, 1.0f);
            polvo(w, l.clone().subtract(lado), rgb, 1.0f);
        }
        if (avance > 0.66) {
            for (int i = 0; i < ruta.size(); i += 4) {
                Compat.spawn(w, Compat.SOUL_FIRE_FLAME, ruta.get(i).clone().add(0, 0.2, 0), 1, 0.05, 0.05, 0.05, 0.01);
            }
        }
    }

    /** El arco de un corte asentado en el suelo (tres arcos y los dos bordes), una vez por aviso. */
    private static void prepararArco(Location origen, Vector dir, double radio, double angulo, List<Location> out) {
        out.clear();
        double spread = Math.toRadians(angulo);
        for (double f : new double[]{0.45, 0.75, 1.0}) {
            double r = radio * f;
            Fx.arc(origen, dir, r, spread, Math.max(5, (int) (r * spread * 2.2)), l -> out.add(enSuelo(l)));
        }
        double base = Math.atan2(dir.getZ(), dir.getX());
        for (int lado = -1; lado <= 1; lado += 2) {
            double ang = base + lado * spread / 2;
            for (double d = 0.8; d <= radio; d += 0.6) {
                out.add(enSuelo(origen.clone().add(Math.cos(ang) * d, 0, Math.sin(ang) * d)));
            }
        }
    }

    private static void pintarPuntos(World w, List<Location> puntos, double avance, float tam) {
        Particle.DustOptions p = Compat.dust(tono(avance), tam);
        for (Location l : puntos) Compat.spawn(w, Compat.DUST, l, 1, 0, 0, 0, 0, p);
    }

    /**
     * Un corte: dano a todo jugador dentro del arco (no atraviesa mas de 2,5 de altura) y un
     * empujon hacia fuera. Lo usan los Tajos, el Acecho y los Umbrales.
     */
    private void corte(World w, Location origen, Vector dir, double radio, double angulo, double k, double empuje,
                       float agudo, boolean reves) {
        if (reves) npc.reves();
        else blandir();
        double spread = Math.toRadians(angulo);
        Fx.arc(origen, dir, radio * 0.7, spread, 6, l -> Compat.spawn(w, Compat.SWEEP_ATTACK, l.clone().add(0, 1, 0), 1));
        Fx.arc(origen, dir, radio * 0.9, spread, 8, l -> Compat.spawn(w, Compat.SOUL_FIRE_FLAME,
                l.clone().add(0, 0.6, 0), 1, 0.1, 0.2, 0.1, 0.02));
        Compat.soundPlayers(w, origen, "entity.player.attack.sweep", 1.4f, agudo);
        Compat.sound(w, origen, "item.trident.throw", 1.0f, 0.6f);
        for (Player v : Fx.playersNear(origen, radio + 0.5)) {
            Vector hv = v.getLocation().toVector().subtract(origen.toVector());
            if (Math.abs(hv.getY()) > 2.5) continue;
            if (!enArco(hv.getX(), hv.getZ(), dir.getX(), dir.getZ(), radio, angulo)) continue;
            golpear(v, k);
            push(v, plano(hv).multiply(empuje).setY(0.18 + empuje * 0.25));
        }
        sacudir(origen, radio + 3);
    }

    /** Dano normal de una tecnica (la armadura cuenta), atribuido a ella: cuenta como muerte suya. */
    private void golpear(Player v, double k) {
        if (v == null || !Fx.isFightable(v) || cuerpo == null) return;
        double cantidad = golpe * k * aj.dano * plugin.registry().damageMultiplier(event.type());
        try {
            v.damage(cantidad, cuerpo);
        } catch (Throwable ignorado) {
            // Un plugin que revienta en el evento de dano no para la pelea.
        }
        // El reloj del atasco: fuera de Calamity (abierta a mano) Parca.onGolpe no lo apunta.
        ultimoGolpe = ticks();
    }

    /**
     * La vista tiembla un instante SIN dano (la animacion de golpe del cliente, desde el lado
     * del impacto): el golpe se siente aunque lo hayas esquivado.
     */
    private static void sacudir(Location desde, double radio) {
        for (Player p : Fx.playersNear(desde, radio)) p.playHurtAnimation(PeleaParca.ladoDe(p, desde));
    }

    /**
     * A 3 bloques DETRAS de la presa, en el hueco libre mas cercano a <= 3; si no hay, en el
     * bloque de la presa. Asi atraviesa cajas, pilares y pozos (sec. 1.7).
     */
    private static Location detras(Player p) {
        Location l = p.getLocation();
        Vector atras = l.getDirection().setY(0);
        if (atras.lengthSquared() < 1e-4) atras = new Vector(0, 0, 1);
        atras.normalize().multiply(-3);
        Location c = l.clone().add(atras);
        Location hueco = Parca.huecoCerca(c, 3);
        Location fin = hueco != null ? hueco : l.clone();
        fin.setDirection(l.toVector().subtract(fin.toVector()).setY(0).lengthSquared() < 1e-4
                ? l.getDirection() : l.toVector().subtract(fin.toVector()));
        return fin;
    }

    /** Cruza al destino: humo y almas en los dos lados, y a quien iba le tiembla la vista desde la espalda. */
    private void saltarA(World w, Location destino, Player v) {
        Location desde = cuerpo.getLocation();
        Compat.spawn(w, Compat.SOUL, desde.clone().add(0, 1, 0), 20, 0.4, 0.8, 0.4, 0.02);
        Compat.spawn(w, Compat.LARGE_SMOKE, desde.clone().add(0, 1, 0), 12, 0.3, 0.8, 0.3, 0.01);
        mover(destino);
        seguirCuerpo();
        Compat.spawn(w, Compat.SOUL, destino.clone().add(0, 1, 0), 20, 0.4, 0.8, 0.4, 0.02);
        Compat.spawn(w, Compat.LARGE_SMOKE, destino.clone().add(0, 1, 0), 12, 0.3, 0.8, 0.3, 0.01);
        Compat.sound(w, destino, "block.respawn_anchor.deplete", 1.4f, 0.6f);
        if (v != null && v.getWorld() == w && Fx.isFightable(v)) v.playHurtAnimation(PeleaParca.ladoDe(v, destino));
        ultimoSalto = ticks();
    }

    /** La mano de la guadana, mas o menos: de ahi sale la cadena (con el alto del cuerpo que se ve). */
    private Location mano(Vector dir) {
        LivingEntity c = npc.valido() ? npc.entidad() : cuerpo;
        Location l = c.getLocation();
        double alto = c.getHeight();
        Vector frente = l.getDirection().setY(0);
        if (frente.lengthSquared() < 1e-4) frente = dir == null ? new Vector(0, 0, 1) : dir.clone();
        frente.normalize();
        Vector derecha = new Vector(-frente.getZ(), 0, frente.getX());
        return l.add(0, 0.62 * alto, 0).add(derecha.multiply(0.2 * alto)).add(frente.multiply(0.15 * alto));
    }

    private double sumaDano() {
        double s = 0;
        for (double d : hc.amenazas().danoLogico(cuerpo).values()) s += d;
        return s;
    }

    /** Cada 2 ticks, las posiciones de los jugadores a <= 32 (anillo de 20 = 2 s). */
    private void apuntarRastros() {
        Set<UUID> vistos = new HashSet<>();
        for (Player p : Fx.playersNear(cuerpo.getLocation(), 32)) {
            vistos.add(p.getUniqueId());
            ArrayDeque<double[]> q = rastro.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
            Location l = p.getLocation();
            q.addLast(new double[]{l.getX(), l.getY(), l.getZ()});
            while (q.size() > 20) q.pollFirst();
        }
        rastro.keySet().retainAll(vistos);
    }

    /** "Quieta" = no se ha desplazado mas de 0,5 bloques en los ultimos 2 s. */
    private boolean quieta(Player v) {
        ArrayDeque<double[]> q = rastro.get(v.getUniqueId());
        if (q == null || q.size() < 20) return false;
        Location l = v.getLocation();
        for (double[] p : q) {
            double dx = p[0] - l.getX(), dy = p[1] - l.getY(), dz = p[2] - l.getZ();
            if (dx * dx + dy * dy + dz * dz > 0.25) return false;
        }
        return true;
    }

    /** Escudo levantado y mirando a la PARCA: se anula la Siega y el escudo se enfria 5 s. */
    private boolean escudo(Player v) {
        if (!v.isBlocking()) return false;
        Vector mira = v.getLocation().getDirection().setY(0);
        Vector aElla = cuerpo.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
        if (mira.lengthSquared() < 1e-4 || aElla.lengthSquared() < 1e-4) return false;
        if (mira.normalize().dot(aElla.normalize()) <= 0.3) return false;
        v.setCooldown(Material.SHIELD, 100);
        Compat.soundPlayers(v.getWorld(), v.getLocation(), "item.shield.block", 1f, 0.8f);
        return true;
    }

    // ================================================================ tecnicas

    /**
     * Una tecnica en curso. paso() corre cada tick (t cuenta desde 0) y devuelve true al acabar;
     * cortar() quita lo suyo si se interrumpe (aturdida, cambio de fase, cosecha, fin).
     */
    private abstract class Tecnica {
        final HabilidadParca h;
        long t;

        Tecnica(HabilidadParca h) {
            this.h = h;
        }

        abstract boolean paso(World w);

        void cortar() {
        }

        /** Lo que se lee en la barra mientras dura. */
        String rotulo() {
            return h == null ? "" : h.nombre;
        }

        /** A donde mira el cuerpo que se ve (null = a los ojos de su objetivo). */
        Vector mira() {
            return null;
        }
    }

    /** Lo que sigue solo despues de su tecnica: estelas, guadanas en vuelo, ondas, la arena marcada. */
    private abstract class Efecto {
        long t;

        abstract boolean paso(World w);

        void quitar() {
        }
    }

    // ------------------------------------------------------------------ Siega

    /**
     * La de siempre: un cono (angulo de la config, +30 desde la fase III) de dano verdadero que
     * atraviesa paredes; el doble al que esta quieto, el escudo de cara la anula. Coral a rojo
     * y llamas de alma en el filo en el ultimo tercio.
     */
    private final class Siega extends Tecnica {
        final Location origen;
        final Vector dir;
        final double angulo;
        final List<Location> cono = new ArrayList<>();
        final List<Location> filo = new ArrayList<>();

        Siega(Player obj) {
            super(HabilidadParca.SIEGA);
            origen = pie().clone();
            dir = hacia(origen, obj.getLocation());
            angulo = a.siegaAngulo + (phase() >= 3 ? 30 : 0);
            double spread = Math.toRadians(angulo);
            for (double f : new double[]{0.35, 0.6, 0.85}) {
                double r = a.siegaRadio * f;
                Fx.arc(origen, dir, r, spread, Math.max(6, (int) (r * spread * 2.2)), l -> cono.add(enSuelo(l)));
            }
            Fx.arc(origen, dir, a.siegaRadio, spread, Math.max(8, (int) (a.siegaRadio * spread * 2.6)),
                    l -> filo.add(enSuelo(l)));
            double base = Math.atan2(dir.getZ(), dir.getX());
            for (int lado = -1; lado <= 1; lado += 2) {
                double ang = base + lado * spread / 2;
                for (double d = 0.8; d <= a.siegaRadio; d += 0.5) {
                    cono.add(enSuelo(origen.clone().add(Math.cos(ang) * d, 0, Math.sin(ang) * d)));
                }
            }
            blandir();
            World w = origen.getWorld();
            Compat.sound(w, origen, "block.respawn_anchor.charge", 1.5f, 0.6f);
            Compat.sound(w, origen, "entity.warden.sonic_charge", 1.2f, 1.2f);
        }

        @Override
        boolean paso(World w) {
            double avance = Math.min(1, t / (double) Math.max(1, a.siegaAviso));
            if (t % 2 == 0) {
                pintarPuntos(w, cono, avance, 1.5f);
                pintarPuntos(w, filo, avance, 2.2f);
                if (avance > 0.66 && t % 4 == 0) {
                    for (int i = 0; i < filo.size(); i += 2) {
                        Compat.spawn(w, Compat.SOUL_FIRE_FLAME, filo.get(i).clone().add(0, 0.1, 0), 1, 0.05, 0.1, 0.05, 0.01);
                    }
                }
            }
            if (t < a.siegaAviso) return false;
            soltar(w);
            return true;
        }

        private void soltar(World w) {
            double spread = Math.toRadians(angulo);
            blandir();
            Fx.arc(origen, dir, a.siegaRadio * 0.7, spread, 6, l -> Compat.spawn(w, Compat.SWEEP_ATTACK,
                    l.clone().add(0, 1, 0), 1));
            for (Location l : filo) {
                Compat.spawn(w, Compat.SOUL_FIRE_FLAME, l.clone().add(0, 0.3, 0), 2, 0.1, 0.3, 0.1, 0.04);
                Compat.spawn(w, Compat.SMOKE, l.clone().add(0, 0.3, 0), 1, 0.1, 0.2, 0.1, 0.02);
            }
            Compat.soundPlayers(w, origen, "entity.player.attack.sweep", 1.5f, 0.5f);
            Compat.sound(w, origen, "entity.warden.attack_impact", 1.6f, 0.6f);
            Compat.sound(w, origen, "block.respawn_anchor.deplete", 1.2f, 0.8f);
            sacudir(origen, a.siegaRadio + 4);
            for (Player v : Fx.playersNear(origen, a.siegaRadio + 0.5)) {
                Vector hv = v.getLocation().toVector().subtract(origen.toVector());
                if (Math.abs(hv.getY()) > 3) continue;
                if (!enArco(hv.getX(), hv.getZ(), dir.getX(), dir.getZ(), a.siegaRadio, angulo)) continue;
                if (escudo(v)) continue;
                double vidaMax = Compat.getAttribute(v, "max_health", 20);
                boolean quieto = quieta(v);
                double cantidad = Parca.siegaFraccion(a, factorR, quieto) * vidaMax;
                // El parte lee las marcas detras de " · ": asi sabe que el x2 fue por quedarse quieto.
                DanoVerdadero.aplicar(v, cantidad, a.siegaTope, cuerpo, quieto ? "Siega · quieto" : "Siega");
                if (hc.esHardcore(v)) hc.cordura().sumar(v, -a.siegaCordura);
                ultimoGolpe = ticks();
            }
        }

        @Override
        Vector mira() {
            return dir;
        }
    }

    // ------------------------------------------------------------------ Tajos

    /**
     * Combo de dos cortes (fase I) o tres (II-IV) con ritmo: si esta lejos da un paso adelante,
     * avisa el arco en el suelo y corta; el ultimo es mas ancho, mas largo y empuja mas.
     */
    private final class Tajos extends Tecnica {
        final UUID quien;
        final int golpes;
        final List<Location> arco = new ArrayList<>();
        int hechos;
        long desde;
        boolean avisando;
        int aviso;
        double radio, angulo;
        Location origen;
        Vector dir;

        Tajos(Player obj) {
            super(HabilidadParca.TAJOS);
            quien = obj.getUniqueId();
            golpes = phase() == 1 ? 2 : 3;
        }

        @Override
        boolean paso(World w) {
            Player obj = jugador(quien);
            if (obj == null) obj = objetivo();
            if (obj == null) return true;
            long dt = t - desde;
            if (!avisando) {
                if (obj.getLocation().distance(pie()) > 2.6 && dt < 5) {
                    Location s = pisar(pie(), hacia(pie(), obj.getLocation()), 0.5);
                    if (s != null) mover(s);
                    if (t % 2 == 0) Compat.spawn(w, Compat.SOUL, pie().add(0, 0.1, 0), 2, 0.2, 0.05, 0.2, 0.01);
                    return false;
                }
                boolean ultimo = hechos == golpes - 1;
                origen = pie().clone();
                dir = hacia(origen, obj.getLocation());
                radio = ultimo ? 4.6 : 3.6;
                angulo = ultimo ? 130 : 90;
                aviso = avisoTajo(phase(), ultimo);
                prepararArco(origen, dir, radio, angulo, arco);
                avisando = true;
                desde = t;
                Compat.sound(w, origen, "item.trident.return", 1.2f, ultimo ? 0.5f : 0.8f);
                return false;
            }
            if (t % 2 == 0) pintarPuntos(w, arco, dt / (double) aviso, hechos == golpes - 1 ? 1.8f : 1.4f);
            if (dt < aviso) return false;
            boolean ultimo = hechos == golpes - 1;
            corte(w, origen, dir, radio, angulo, ultimo ? K_TAJO_FINAL : K_TAJO, ultimo ? 0.75 : 0.35,
                    0.7f + 0.15f * hechos, hechos % 2 == 1);
            if (ultimo) Compat.sound(w, origen, "entity.warden.attack_impact", 1.3f, 0.7f);
            hechos++;
            if (hechos >= golpes) return true;
            avisando = false;
            desde = t;
            return false;
        }

        @Override
        Vector mira() {
            return avisando ? dir : null;
        }
    }

    // ----------------------------------------------------------------- Acecho

    /**
     * Rodea a su presa a 5 bloques, rapida, dejando pisadas de almas (un sentido al azar; si
     * choca con algo, cambia de sentido), y al acabar la vuelta corta con aviso.
     */
    private final class Acecho extends Tecnica {
        final UUID quien;
        final int dura;
        final double distInicial;
        final List<Location> arco = new ArrayList<>();
        double ang;
        double sentido;
        int choques;
        boolean cortando;
        long desde;
        Location origen;
        Vector dir;

        Acecho(Player obj) {
            super(HabilidadParca.ACECHO);
            quien = obj.getUniqueId();
            Location c = obj.getLocation();
            ang = Math.atan2(pie().getZ() - c.getZ(), pie().getX() - c.getX());
            distInicial = Math.hypot(pie().getX() - c.getX(), pie().getZ() - c.getZ());
            sentido = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
            dura = 30 + phase() * 4;
            Compat.sound(pie().getWorld(), pie(), "entity.warden.sniff", 1.2f, 0.6f);
        }

        @Override
        boolean paso(World w) {
            Player obj = jugador(quien);
            if (obj == null) return true;
            if (!cortando) {
                if (t < dura && obj.getLocation().distanceSquared(pie()) < 20 * 20) {
                    ang += sentido * 0.085;
                    Location c = obj.getLocation();
                    // Desde lejos se cierra al circulo a 1 bloque por tick, sin saltos.
                    double radio = Math.max(5, distInicial - t);
                    Location g = Fx.ground(c.clone().add(Math.cos(ang) * radio, 1.5, Math.sin(ang) * radio), 5);
                    if (!Parca.libre(g, 2) || Math.abs(g.getY() - pie().getY()) > 2.5) {
                        ang -= sentido * 0.085;
                        sentido = -sentido;
                        if (++choques <= 2) return false;
                    } else {
                        g.setDirection(hacia(g, c));
                        mover(g);
                        if (t % 2 == 0) {
                            Compat.spawn(w, Compat.SOUL, g.clone().add(0, 0.1, 0), 1, 0.15, 0.02, 0.15, 0.01);
                            Compat.spawn(w, Compat.SMOKE, g.clone().add(0, 0.2, 0), 2, 0.2, 0.1, 0.2, 0.01);
                        }
                        if (t % 8 == 0) Compat.sound(w, g, "entity.wither_skeleton.step", 0.9f, 0.5f);
                        return false;
                    }
                }
                cortando = true;
                desde = t;
                origen = pie().clone();
                dir = hacia(origen, obj.getLocation());
                prepararArco(origen, dir, 4.0, 120, arco);
                Compat.sound(w, origen, "item.trident.return", 1.2f, 0.6f);
                return false;
            }
            long dt = t - desde;
            if (t % 2 == 0) pintarPuntos(w, arco, dt / (double) AVISO_ACECHO, 1.5f);
            if (dt < AVISO_ACECHO) return false;
            corte(w, origen, dir, 4.0, 120, K_ACECHO, 0.5, 0.75f, false);
            return true;
        }

        @Override
        Vector mira() {
            return cortando ? dir : null;
        }
    }

    // --------------------------------------------------------------- Acometida

    /**
     * Marca una linea en el suelo que pasa por donde esta su presa (y 3 bloques mas alla,
     * cortada en la primera pared) y la cruza de golpe a 1,5 bloques por tick. Quien este en
     * la linea se lleva el golpe y un empujon de lado. Desde la III deja la estela ardiendo;
     * en la IV repite contra donde este ahora la presa, con menos aviso.
     */
    private final class Acometida extends Tecnica {
        final UUID quien;
        final int cargas;
        final List<Location> ruta = new ArrayList<>();
        final List<Location> estela = new ArrayList<>();
        final Set<UUID> tocados = new HashSet<>();
        Vector dir;
        int aviso, hecha, indice;
        long desde;
        boolean corriendo;

        Acometida(Player obj) {
            super(HabilidadParca.ACOMETIDA);
            quien = obj.getUniqueId();
            cargas = phase() >= 4 ? 2 : 1;
            preparar(obj, avisoAcometida(phase(), false));
        }

        private void preparar(Player obj, int av) {
            tocados.clear();
            corriendo = false;
            indice = 0;
            desde = t;
            aviso = av;
            Location o = pie().clone();
            dir = hacia(o, obj.getLocation());
            trazar(o, dir, Math.max(6, Math.min(18, o.distance(obj.getLocation()) + 3)), ruta);
            Compat.sound(o.getWorld(), o, "block.respawn_anchor.charge", 1.4f, 0.8f);
            Compat.sound(o.getWorld(), o, "entity.wither_skeleton.ambient", 1.2f, 0.5f);
        }

        @Override
        boolean paso(World w) {
            long dt = t - desde;
            if (!corriendo) {
                if (t % 2 == 0) pintarRuta(w, ruta, dir, dt / (double) aviso, 0.7);
                if (dt == aviso - 6) npc.postura(Pose.SNEAKING);
                if (dt < aviso) return false;
                corriendo = true;
                npc.postura(Pose.STANDING);
                blandir();
                Compat.sound(w, pie(), "item.trident.riptide_2", 1.4f, 0.6f);
            }
            Location antes = pie().clone();
            int hasta = Math.min(ruta.size() - 1, indice + 3);
            if (hasta > indice) {
                Location destino = ruta.get(hasta).clone();
                destino.setDirection(dir);
                mover(destino);
                for (int i = indice; i <= hasta; i++) {
                    Location l = ruta.get(i);
                    Compat.spawn(w, Compat.SOUL_FIRE_FLAME, l.clone().add(0, 0.3, 0), 2, 0.2, 0.2, 0.2, 0.01);
                    Compat.spawn(w, Compat.SOUL, l.clone().add(0, 0.8, 0), 1, 0.2, 0.3, 0.2, 0.01);
                    if (i % 2 == 0) estela.add(l.clone());
                }
                for (Player v : Fx.playersNear(destino, 5)) {
                    if (tocados.contains(v.getUniqueId())) continue;
                    Location lv = v.getLocation();
                    if (Math.abs(lv.getY() - destino.getY()) > 2.5) continue;
                    if (distanciaASegmento(lv.getX(), lv.getZ(), antes.getX(), antes.getZ(), destino.getX(), destino.getZ()) > 1.4) {
                        continue;
                    }
                    tocados.add(v.getUniqueId());
                    golpear(v, K_ACOMETIDA);
                    Vector lado = new Vector(-dir.getZ(), 0, dir.getX());
                    if (lado.dot(lv.toVector().subtract(destino.toVector())) < 0) lado.multiply(-1);
                    push(v, lado.multiply(0.6).add(dir.clone().multiply(0.3)).setY(0.25));
                    Compat.apply(v, "slowness", 20, 0);
                }
                indice = hasta;
            }
            if (indice < ruta.size() - 1) return false;
            // Al final de la linea: el frenazo.
            Location fin = pie();
            Compat.spawn(w, Compat.SWEEP_ATTACK, fin.clone().add(0, 1, 0), 3, 0.6, 0.3, 0.6, 0);
            Compat.spawn(w, Compat.LARGE_SMOKE, fin.clone().add(0, 0.4, 0), 10, 0.5, 0.2, 0.5, 0.02);
            Compat.sound(w, fin, "entity.warden.attack_impact", 1.2f, 0.8f);
            ultimoSalto = ticks();
            hecha++;
            if (phase() >= 3 && estela.size() > 1) efectos.add(new Estela(new ArrayList<>(estela), 40));
            estela.clear();
            if (hecha < cargas) {
                Player obj = jugador(quien);
                if (obj == null) obj = objetivo();
                if (obj != null && obj.getLocation().distance(pie()) <= 20) {
                    preparar(obj, avisoAcometida(phase(), true));
                    return false;
                }
            }
            return true;
        }

        @Override
        Vector mira() {
            return dir;
        }
    }

    /**
     * La estela que queda ardiendo tras una acometida o una embestida: fuego de almas bajo que
     * quema a quien se quede encima (cada medio segundo, poco) y le quita cordura.
     */
    private final class Estela extends Efecto {
        final List<Location> puntos;
        final int dura;
        final Map<UUID, Long> ultimo = new HashMap<>();
        final Location centro;
        final double alcance;

        Estela(List<Location> puntos, int dura) {
            this.puntos = puntos;
            this.dura = dura;
            this.centro = puntos.get(puntos.size() / 2);
            double r = 0;
            for (Location l : puntos) r = Math.max(r, l.distance(centro));
            this.alcance = r + 2;
        }

        @Override
        boolean paso(World w) {
            if (t % 4 == 0) {
                for (int i = (int) (t / 4) % 2; i < puntos.size(); i += 2) {
                    Compat.spawn(w, Compat.SOUL_FIRE_FLAME, puntos.get(i).clone().add(0, 0.15, 0), 1, 0.15, 0.05, 0.15, 0.005);
                }
            }
            if (t % 5 == 0) {
                for (Player v : Fx.playersNear(centro, alcance)) {
                    Long u = ultimo.get(v.getUniqueId());
                    if (u != null && t - u < 10) continue;
                    Location lv = v.getLocation();
                    boolean encima = false;
                    for (Location p : puntos) {
                        double dx = p.getX() - lv.getX(), dz = p.getZ() - lv.getZ();
                        if (dx * dx + dz * dz < 0.8 && Math.abs(p.getY() - lv.getY()) < 1.5) {
                            encima = true;
                            break;
                        }
                    }
                    if (!encima) continue;
                    ultimo.put(v.getUniqueId(), t);
                    golpear(v, K_ESTELA);
                    if (hc.esHardcore(v)) hc.cordura().sumar(v, -2);
                }
            }
            return t >= dura;
        }
    }

    // ------------------------------------------------------------ Paso Umbral

    /** El de siempre (fases I y II): aviso en el suelo detras de la presa y aparece ahi. */
    private final class Umbral extends Tecnica {
        final Location destino;
        final UUID victima;

        Umbral(Player obj) {
            super(HabilidadParca.UMBRAL);
            destino = detras(obj);
            victima = obj.getUniqueId();
            obj.playSound(obj.getLocation(), "entity.enderman.teleport", SoundCategory.HOSTILE, 1f, 0.5f);
            Compat.sound(obj.getWorld(), destino, "block.sculk_catalyst.bloom", 1.5f, 0.6f);
            hc.cordura().destello(obj, Component.text("¡Cuidado, detrás de ti!", Paleta.AVISO), 2);
        }

        @Override
        boolean paso(World w) {
            if (t % 2 == 0) {
                Fx.telegraph(w, destino, 1.2, RGB_PARCA);
                Compat.spawn(w, Compat.SCULK_SOUL, destino.clone().add(0, 0.3, 0), 2, 0.3, 0.1, 0.3, 0.01);
                Compat.spawn(w, Compat.SOUL_FIRE_FLAME, destino.clone().add(0, 0.2, 0), 2, 0.2, 0.6, 0.2, 0.01);
            }
            if (t < a.umbralAviso) return false;
            saltarA(w, destino, jugador(victima));
            return true;
        }
    }

    /**
     * Los Umbrales (III-IV): dos o tres Pasos seguidos, cada uno con su aviso detras de quien
     * toque, y en cada aparicion un corte con su propio aviso. Entre uno y otro puede cambiar
     * de presa (a la mas cercana de sus marcados).
     */
    private final class Umbrales extends Tecnica {
        final int saltos;
        final int avisoSalto;
        final List<Location> arco = new ArrayList<>();
        int hechos;
        int etapa;
        long desde;
        Location destino;
        UUID victima;
        Location origen;
        Vector dir;

        Umbrales(Player obj) {
            super(HabilidadParca.UMBRALES);
            saltos = phase() >= 4 ? 3 : 2;
            avisoSalto = avisoUmbrales(phase());
            marcar(obj);
        }

        private void marcar(Player obj) {
            destino = detras(obj);
            victima = obj.getUniqueId();
            etapa = 0;
            desde = t;
            obj.playSound(obj.getLocation(), "entity.enderman.teleport", SoundCategory.HOSTILE, 1f, 0.5f);
            Compat.sound(obj.getWorld(), destino, "block.sculk_catalyst.bloom", 1.5f, 0.6f);
        }

        @Override
        boolean paso(World w) {
            long dt = t - desde;
            if (etapa == 0) {
                if (t % 2 == 0) {
                    Fx.telegraph(w, destino, 1.3, tono(dt / (double) avisoSalto));
                    Compat.spawn(w, Compat.SOUL_FIRE_FLAME, destino.clone().add(0, 0.2, 0), 3, 0.2, 0.8, 0.2, 0.01);
                }
                if (dt < avisoSalto) return false;
                Player v = jugador(victima);
                saltarA(w, destino, v);
                origen = pie().clone();
                dir = v != null ? hacia(origen, v.getLocation()) : plano(origen.getDirection());
                prepararArco(origen, dir, 3.4, 150, arco);
                etapa = 1;
                desde = t;
                return false;
            }
            if (t % 2 == 0) pintarPuntos(w, arco, dt / (double) AVISO_CORTE, 1.5f);
            if (dt < AVISO_CORTE) return false;
            corte(w, origen, dir, 3.4, 150, K_UMBRAL, 0.45, 0.8f + 0.1f * hechos, hechos % 2 == 1);
            hechos++;
            if (hechos >= saltos) return true;
            Player obj = objetivo();
            if (obj == null) return true;
            marcar(obj);
            return false;
        }

        @Override
        Vector mira() {
            return etapa == 1 ? dir : null;
        }

        @Override
        String rotulo() {
            return "Umbrales " + Math.min(saltos, hechos + 1) + "/" + saltos;
        }
    }

    // ------------------------------------------------------------------ Tiron

    /**
     * La cadena de la guadana a su presa (fase II en adelante). Si al final hay linea de vision
     * y nadie la ha roto (rompe = 3 % de su vida logica durante el aviso), la arrastra hacia ella
     * con Lentitud I 2 s y el siguiente golpe (Siega en la II, Tajos despues) va al aterrizar.
     */
    private final class Tiron extends Tecnica {
        final UUID victima;
        final double danoAlEmpezar;

        Tiron(Player obj) {
            super(HabilidadParca.TIRON);
            victima = obj.getUniqueId();
            danoAlEmpezar = sumaDano();
            World w = pie().getWorld();
            Compat.sound(w, pie(), "entity.fishing_bobber.throw", 1.5f, 0.5f);
            Compat.sound(w, pie(), "block.chain.place", 1.5f, 0.5f);
        }

        @Override
        boolean paso(World w) {
            Player v = jugador(victima);
            if (v == null) return true;
            Vector hacia = hacia(pie(), v.getLocation());
            Location desde = mano(hacia);
            Location hasta = v.getLocation().add(0, 1.0, 0);
            cadena.pintar(desde, hasta);
            if (t % 4 == 0) {
                Compat.spawn(w, Compat.SOUL_FIRE_FLAME, hasta, 2, 0.15, 0.25, 0.15, 0.0);
                Particle.DustOptions polvo = Compat.dust(RGB_HUESO, 0.8f);
                Fx.beam(desde, hasta, 0.9, l -> Compat.spawn(w, Compat.DUST, l, 1, 0.03, 0.03, 0.03, 0, polvo));
            }
            if (sumaDano() - danoAlEmpezar >= a.tironRompe * vidaFinal()) {
                Component roto = Component.text("La cadena se rompe.", Paleta.DETALLE);
                for (Player o : Fx.viewersNear(pie(), 32)) hc.cordura().destello(o, roto, 2);
                Compat.sound(w, pie(), "block.chain.break", 1.5f, 0.6f);
                Location medio = desde.clone().add(hasta.toVector().subtract(desde.toVector()).multiply(0.5));
                Compat.spawn(w, Compat.CRIT, medio, 20, 0.4, 0.4, 0.4, 0.2);
                return true;
            }
            if (t < a.tironAviso) return false;
            if (cuerpo.hasLineOfSight(v)) {
                Vector tiro = pie().toVector().subtract(v.getLocation().toVector()).setY(0);
                if (tiro.lengthSquared() > 1e-4) lift(v, tiro.normalize().multiply(a.tironFuerza).setY(0.12));
                Compat.apply(v, "slowness", 40, 0);
                Compat.sound(w, v.getLocation(), "block.chain.hit", 1.5f, 0.5f);
                Compat.sound(w, v.getLocation(), "entity.warden.attack_impact", 1.0f, 0.8f);
                v.playHurtAnimation(PeleaParca.ladoDe(v, pie()));
                trasTiron = phase() >= 3 ? HabilidadParca.TAJOS : HabilidadParca.SIEGA;
                trasTironDesde = ticks();
            }
            return true;
        }

        @Override
        void cortar() {
            cadena.quitar();
        }

        @Override
        Vector mira() {
            Player v = jugador(victima);
            return v == null ? null : hacia(pie(), v.getLocation());
        }
    }

    // ---------------------------------------------------------------- Cortejo

    /** base + M planideras, con tope. */
    private int cuantasPlanideras() {
        return Math.max(1, Math.min(a.planTope, a.planBase + extra));
    }

    /**
     * El Cortejo (fase II en adelante, y la firma de la II): un anillo por planidera donde va a
     * salir cada una, campanas, y salen los mini withers que la orbitan (ver planideras()).
     */
    private final class Cortejo extends Tecnica {
        final List<Location> puntos = new ArrayList<>();
        final Location origen;

        Cortejo(Player obj) {
            super(HabilidadParca.CORTEJO);
            origen = pie().clone();
            Vector dir = hacia(origen, obj.getLocation());
            blandir();
            Compat.sound(origen.getWorld(), origen, "entity.wither.ambient", 1.2f, 1.6f);
            int n = cuantasPlanideras();
            double base = Math.atan2(dir.getZ(), dir.getX());
            for (int i = 0; i < n; i++) {
                double ang = base + i * Math.PI * 2 / n;
                puntos.add(Fx.ground(origen.clone().add(Math.cos(ang) * 4, 0, Math.sin(ang) * 4), 4));
            }
        }

        @Override
        boolean paso(World w) {
            if (t == 2 || t == 10 || t == 18) Compat.sound(w, origen, "block.bell.use", 1.5f, 0.7f);
            if (t % 2 == 0) {
                for (Location p : puntos) {
                    Fx.telegraph(w, p, 0.8, RGB_HUESO);
                    if (t % 4 == 0) Compat.spawn(w, Compat.SOUL, p.clone().add(0, 0.3, 0), 1, 0.2, 0.3, 0.2, 0.02);
                }
            }
            if (t < 30) return false;
            soltarCortejo(puntos);
            return true;
        }
    }

    /**
     * Mini withers (scale planideras.escala) sin IA ni gravedad, mudos y SIN la barra de jefe
     * del wither: la unica barra es la de la PARCA. No pegan: lloran (quitan cordura). Mientras
     * quede una, la PARCA recibe x0,5; se matan a golpes. Parca.onDisparo y onExplotar son el
     * doble cerrojo si otro plugin les devuelve la IA.
     */
    private void soltarCortejo(List<Location> puntos) {
        int n = Math.min(cuantasPlanideras(), puntos.size());
        double vida = a.planVida * (1 + a.planVidaPorNivel * (nivel - 1));
        World w = cuerpo.getWorld();
        Location centro = cuerpo.getLocation();
        huecos.clear();
        for (int i = 0; i < n; i++) {
            Location l = puntos.get(i).clone().add(0, 1, 0);
            Wither wi = hc.amenazas().invocar(Wither.class, l, "planidera", nivel,
                    Paleta.nombre("Plañidera", Paleta.HUESO), e -> {
                        if (presa != null) e.getPersistentDataContainer().set(Marcas.PRESA, PersistentDataType.STRING, presa.toString());
                        e.setAI(false);
                        e.setGravity(false);
                        e.setSilent(true);
                        ocultarBarra(e);
                        Compat.setAttribute(e, "scale", a.planEscala);
                        Compat.setAttribute(e, "max_health", vida);
                        e.setHealth(Math.max(1, vida));
                        Compat.setAttribute(e, "knockback_resistance", 1.0);
                    });
            if (wi == null) continue;
            planideras.add(wi);
            huecos.put(wi.getUniqueId(), Math.atan2(l.getZ() - centro.getZ(), l.getX() - centro.getX()));
            Compat.spawn(w, Compat.SOUL, l, 16, 0.3, 0.4, 0.3, 0.04);
            Compat.spawn(w, Compat.LARGE_SMOKE, l, 8, 0.3, 0.3, 0.3, 0.01);
        }
        if (!planideras.isEmpty()) {
            planNacio = ticks();
            planActivas = true;
            planCaducaron = false;
            Compat.sound(w, centro, "entity.wither.ambient", 1.4f, 1.8f);
        }
    }

    /** La barra de jefe del wither fuera: invisible y sin nadie. */
    private static void ocultarBarra(Wither w) {
        try {
            org.bukkit.boss.BossBar b = w.getBossBar();
            if (b == null) return;
            b.setVisible(false);
            b.removeAll();
        } catch (Throwable ignorado) {
            // Si Paper cambia la API, la barra se veria: feo, pero la pelea sigue.
        }
    }

    /** Donde va la planidera ahora: en circulo alrededor de la PARCA, a la altura del pecho, subiendo y bajando. */
    private Location orbita(Wither wi, Location centro, long vida) {
        double hueco = huecos.getOrDefault(wi.getUniqueId(), 0.0);
        double ang = hueco + vida * VELOCIDAD_ORBITA;
        double entra = Math.max(0, 1 - vida / 20.0);
        double radio = a.planRadio + (4 - a.planRadio) * entra;
        double alto = a.planAltura * (1 - entra) + entra + 0.3 * Math.sin(vida * 0.12 + hueco * 3);
        Location l = centro.clone().add(Math.cos(ang) * radio, alto, Math.sin(ang) * radio);
        l.setDirection(new Vector(-Math.sin(ang), 0, Math.cos(ang)));
        return l;
    }

    /**
     * Cada 2 ticks: orbita, hilo de almas a la PARCA, lamento, caducidad a los vida-ticks y, si
     * caen todas antes de tiempo, la PARCA queda aturdida y recibe +25 % (P-15).
     */
    private void planideras(World w, long tk) {
        if (!planActivas) return;
        planideras.removeIf(v -> !v.isValid() || v.isDead());
        if (!planideras.isEmpty() && tk - planNacio >= a.planVidaTicks) {
            for (Wither v : planideras) {
                Compat.spawn(v.getWorld(), Compat.SOUL, v.getLocation().add(0, 0.5, 0), 10, 0.2, 0.3, 0.2, 0.03);
                Fx.safeRemove(v);
            }
            planideras.clear();
            planCaducaron = true;
        }
        if (planideras.isEmpty()) {
            planActivas = false;
            if (!planCaducaron && tk - planNacio < a.planVidaTicks) aturdir(tk);
            return;
        }
        Location centro = cuerpo.getLocation();
        long vida = tk - planNacio;
        for (Wither v : planideras) {
            if (v.getWorld() == w) hc.amenazas().teleportar(v, orbita(v, centro, vida));
        }
        if (tk % 10 == 0) {
            for (Wither v : planideras) {
                if (v.getWorld() != w) continue;
                Fx.beam(v.getLocation().add(0, 0.4, 0), cuerpo.getLocation().add(0, 1.4, 0), 0.5,
                        l -> Compat.spawn(w, Compat.SOUL, l, 1, 0, 0, 0, 0));
            }
        }
        if (tk % 20 == 0) for (Wither v : planideras) ocultarBarra(v);
        lamentos(w, vida);
    }

    /** El lamento: cada planidera, escalonadas, llora hacia el objetivo si esta a tiro (-cordura, nunca vida). */
    private void lamentos(World w, long vida) {
        long periodo = Math.max(20, a.planLamento * 20L);
        Player obj = objetivo();
        if (obj == null || obj.getWorld() != w) return;
        for (int i = 0; i < planideras.size(); i++) {
            if (Math.floorMod(vida - 10 - 12L * i, periodo) > 1) continue;
            Wither v = planideras.get(i);
            Location desde = v.getLocation().add(0, 0.5, 0);
            if (desde.distanceSquared(obj.getLocation()) > a.planLamentoRadio * a.planLamentoRadio) continue;
            Fx.beam(desde, obj.getLocation().add(0, 1.1, 0), 0.35,
                    l -> Compat.spawn(w, Compat.SOUL, l, 1, 0.02, 0.02, 0.02, 0));
            Compat.sound(w, desde, "entity.wither.ambient", 0.8f, 1.9f);
            obj.playSound(obj.getLocation(), "entity.allay.death", SoundCategory.HOSTILE, 0.5f, 0.5f);
            if (hc.esHardcore(obj)) hc.cordura().sumar(obj, -a.planCordura);
        }
    }

    private void aturdir(long tk) {
        aturdidaHasta = tk + a.planAturdir * 20L;
        cortarTecnica();
        cuerpo.setAI(false);
        huecos.clear();
        npc.postura(Pose.SNEAKING);
        Component texto = Component.text("La Parca se tambalea: ahora recibe más daño.", Paleta.DETALLE);
        for (Player o : Fx.viewersNear(cuerpo.getLocation(), 32)) hc.cordura().destello(o, texto, 2);
        Compat.sound(cuerpo.getWorld(), cuerpo.getLocation(), "entity.wither_skeleton.hurt", 1.5f, 0.5f);
        Compat.sound(cuerpo.getWorld(), cuerpo.getLocation(), "block.bell.resonate", 1.2f, 0.7f);
        hc.plugin().bitacora().anotar("parca", "aturdida", presaNombre, "anomalia");
    }

    // ---------------------------------------------------------------- Guadanas

    /**
     * Gira sobre si misma con las lineas de salida marcadas en el suelo y suelta guadanas que
     * ruedan a ras de suelo en linea recta: 3 en la II, 4 en la III (dos apuntadas a jugadores),
     * 6 en la IV, que ademas vuelven a ella como un bumeran. Las guadanas siguen solas (Efecto):
     * ella queda libre para lo siguiente.
     */
    private final class Guadanas extends Tecnica {
        final Location origen;
        final List<Vector> dirs = new ArrayList<>();
        final List<List<Location>> lineas = new ArrayList<>();
        final int aviso;

        Guadanas(Player obj) {
            super(HabilidadParca.GUADANAS);
            origen = pie().clone();
            aviso = avisoGuadanas(phase());
            int f = phase();
            int n = f <= 2 ? 3 : f == 3 ? 4 : 6;
            double base = Math.atan2(obj.getLocation().getZ() - origen.getZ(), obj.getLocation().getX() - origen.getX());
            List<Double> angulos = new ArrayList<>();
            if (f == 3) {
                for (Player p : Fx.playersNear(origen, 24)) {
                    if (angulos.size() >= 2) break;
                    angulos.add(Math.atan2(p.getLocation().getZ() - origen.getZ(), p.getLocation().getX() - origen.getX()));
                }
            }
            double libre = angulos.isEmpty() ? base : angulos.get(0) + Math.PI / n;
            while (angulos.size() < n) angulos.add(libre + (angulos.size()) * Math.PI * 2 / n);
            for (double ang : angulos) {
                Vector d = new Vector(Math.cos(ang), 0, Math.sin(ang));
                dirs.add(d);
                List<Location> l = new ArrayList<>();
                for (double k = 1.2; k <= 16; k += 1.0) l.add(enSuelo(origen.clone().add(d.clone().multiply(k))));
                lineas.add(l);
            }
            npc.postura(Pose.SPIN_ATTACK);
            Compat.sound(origen.getWorld(), origen, "item.trident.riptide_1", 1.3f, 0.6f);
        }

        @Override
        boolean paso(World w) {
            double avance = t / (double) aviso;
            if (t % 2 == 0) for (List<Location> l : lineas) pintarPuntos(w, l, avance, 1.3f);
            if (t % 5 == 0) {
                double giro = t * 0.6;
                for (int i = 0; i < 3; i++) {
                    Location p = origen.clone().add(Math.cos(giro + i * 2.1) * 1.6, 1.1, Math.sin(giro + i * 2.1) * 1.6);
                    Compat.spawn(w, Compat.SWEEP_ATTACK, p, 1);
                }
                Compat.sound(w, origen, "entity.player.attack.sweep", 0.8f, 0.6f + (float) avance * 0.6f);
            }
            if (t < aviso) return false;
            boolean vuelven = phase() >= 4;
            for (Vector d : dirs) efectos.add(new Guadana(origen, d, vuelven));
            blandir();
            Compat.sound(w, origen, "item.trident.throw", 1.4f, 0.5f);
            return true;
        }
    }

    /** Una guadana girando plana a ras de suelo (ItemDisplay): avanza, sigue el terreno, la para una pared. */
    private final class Guadana extends Efecto {
        final ItemDisplay d;
        final boolean vuelve;
        final Set<UUID> tocados = new HashSet<>();
        Location pos;
        Vector dir;
        double recorrido;
        boolean volviendo;
        float giro;

        Guadana(Location origen, Vector dir, boolean vuelve) {
            this.dir = dir.clone();
            this.vuelve = vuelve;
            pos = origen.clone().add(dir.clone().multiply(1.2)).add(0, 0.7, 0);
            ItemDisplay x = null;
            try {
                x = Fx.itemDisplay(origen.getWorld(), pos, new ItemStack(Material.NETHERITE_HOE), 1.6f);
                x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                x.setTeleportDuration(1);
                x.setInterpolationDuration(1);
                track(x);
            } catch (Throwable ignorado) {
                // Sin la guadana visible el golpe sigue avisado por la linea y las particulas.
            }
            d = x;
        }

        @Override
        boolean paso(World w) {
            Vector mov = volviendo ? hacia(pos, pie()) : dir;
            Location nuevo = pos.clone().add(mov.clone().multiply(0.6));
            Location g = Fx.ground(nuevo.clone().add(0, 0.8, 0), 4).add(0, 0.7, 0);
            boolean pared = Math.abs(g.getY() - pos.getY()) > 1.6 || !g.getBlock().isPassable();
            if (pared) {
                if (vuelve && !volviendo) {
                    volviendo = true;
                    tocados.clear();
                    return false;
                }
                chispas(w);
                return true;
            }
            pos = g;
            recorrido += 0.6;
            if (!volviendo && recorrido >= 16) {
                if (!vuelve) {
                    chispas(w);
                    return true;
                }
                volviendo = true;
                tocados.clear();
            }
            if (volviendo && (pos.distanceSquared(pie().add(0, 0.7, 0)) < 2.25 || recorrido >= 42)) {
                chispas(w);
                return true;
            }
            giro += (float) Math.toRadians(40);
            if (d != null && d.isValid()) {
                d.teleport(pos);
                d.setInterpolationDelay(0);
                d.setTransformation(new Transformation(new Vector3f(),
                        new Quaternionf().rotateY(giro).rotateX((float) -Math.PI / 2),
                        new Vector3f(1.6f, 1.6f, 1.6f), new Quaternionf()));
            }
            Compat.spawn(w, Compat.SOUL_FIRE_FLAME, pos, 1, 0.2, 0.05, 0.2, 0.01);
            if (t % 3 == 0) Compat.spawn(w, Compat.SWEEP_ATTACK, pos.clone().add(0, 0.2, 0), 1);
            if (t % 8 == 0) Compat.sound(w, pos, "entity.player.attack.sweep", 0.7f, 1.4f);
            for (Player v : Fx.playersNear(pos, 2.2)) {
                if (tocados.contains(v.getUniqueId())) continue;
                Location lv = v.getLocation();
                double dx = lv.getX() - pos.getX(), dz = lv.getZ() - pos.getZ();
                if (dx * dx + dz * dz > 1.44 || Math.abs(lv.getY() + 0.5 - pos.getY()) > 1.8) continue;
                tocados.add(v.getUniqueId());
                golpear(v, K_GUADANA);
                push(v, mov.clone().multiply(0.5).setY(0.2));
            }
            return false;
        }

        private void chispas(World w) {
            Compat.spawn(w, Compat.CRIT, pos, 10, 0.2, 0.2, 0.2, 0.1);
            Compat.sound(w, pos, "block.chain.break", 0.8f, 1.2f);
        }

        @Override
        void quitar() {
            if (d != null) {
                spawned.remove(d);
                Fx.safeRemove(d);
            }
        }
    }

    // ------------------------------------------------------------------ Salto

    /**
     * Se agacha, salta en arco sobre donde estaba su presa (el circulo de caida marcado desde el
     * primer tick) y al caer golpea en el circulo; luego sale una onda por el suelo que solo
     * pega a quien este pisando cuando pase: se esquiva saltando.
     */
    private final class Salto extends Tecnica {
        final Location origen;
        final Location destino;
        final int vuelo;
        final double radio = 3.5;
        final List<Location> caida;

        Salto(Player obj) {
            super(HabilidadParca.SALTO);
            origen = pie().clone();
            Location d = Fx.ground(obj.getLocation().clone().add(0, 1, 0), 6);
            if (!Parca.libre(d, 2)) {
                Location h = Parca.huecoCerca(d, 3);
                if (h != null) d = h;
            }
            d.setDirection(hacia(origen, d));
            destino = d;
            caida = circulo(d, radio);
            vuelo = vueloSalto(phase());
            npc.postura(Pose.SNEAKING);
            Compat.sound(origen.getWorld(), origen, "entity.warden.sonic_charge", 1.0f, 0.7f);
        }

        @Override
        boolean paso(World w) {
            if (t % 2 == 0) {
                double avance = t / (double) (CARGA_SALTO + vuelo);
                pintarPuntos(w, caida, avance, 1.5f);
                if (avance > 0.5) Fx.ring(destino, radio * 0.3, 8, l -> polvo(w, enSuelo(l), RGB_HASTA, 1.2f));
            }
            if (t < CARGA_SALTO) return false;
            if (t == CARGA_SALTO) {
                npc.postura(Pose.STANDING);
                cuerpo.setAI(false);
                Compat.sound(w, origen, "entity.breeze.jump", 1.4f, 0.6f);
                Compat.spawn(w, Compat.GUST_EMITTER_LARGE != null ? Compat.GUST_EMITTER_LARGE : Compat.CLOUD,
                        origen.clone().add(0, 0.3, 0), 1);
            }
            long s = t - CARGA_SALTO;
            if (s < vuelo) {
                double k = (s + 1) / (double) vuelo;
                Location p = origen.clone().add(destino.toVector().subtract(origen.toVector()).multiply(k));
                p.setY(origen.getY() + (destino.getY() - origen.getY()) * k + 24 * k * (1 - k));
                p.setDirection(hacia(origen, destino));
                mover(p);
                if (s % 2 == 0) Compat.spawn(w, Compat.SOUL, p.clone().add(0, 1, 0), 2, 0.3, 0.3, 0.3, 0.01);
                return false;
            }
            mover(destino);
            cuerpo.setAI(true);
            blandir();
            Compat.spawn(w, Compat.EXPLOSION, destino.clone().add(0, 0.5, 0), 2, 0.5, 0.2, 0.5, 0);
            Compat.spawn(w, Compat.SOUL, destino.clone().add(0, 0.5, 0), 30, radio / 2, 0.3, radio / 2, 0.05);
            Compat.sound(w, destino, "entity.warden.attack_impact", 1.8f, 0.5f);
            Compat.sound(w, destino, "entity.generic.explode", 1.0f, 0.6f);
            for (Player v : Fx.playersNear(destino, radio + 0.5)) {
                Location lv = v.getLocation();
                double dx = lv.getX() - destino.getX(), dz = lv.getZ() - destino.getZ();
                if (dx * dx + dz * dz > radio * radio || Math.abs(lv.getY() - destino.getY()) > 2.5) continue;
                golpear(v, K_SALTO);
                lift(v, plano(new Vector(dx, 0, dz)).multiply(0.5).setY(0.7));
            }
            sacudir(destino, 14);
            efectos.add(new Onda(destino.clone(), radio, 11));
            ultimoSalto = ticks();
            return true;
        }

        @Override
        void cortar() {
            if (cuerpo != null && cuerpo.isValid()) cuerpo.setAI(true);
        }

        @Override
        Vector mira() {
            return hacia(origen, destino);
        }
    }

    /** La onda del Salto: un anillo que corre por el suelo; pega (una vez) a quien este pisando cuando pase. */
    private final class Onda extends Efecto {
        final Location centro;
        final double hasta;
        final Set<UUID> tocados = new HashSet<>();
        double r;

        Onda(Location centro, double desde, double hasta) {
            this.centro = centro;
            this.r = desde;
            this.hasta = hasta;
        }

        @Override
        boolean paso(World w) {
            r += 0.6;
            Particle.DustOptions p = Compat.dust(RGB_HUESO, 1.6f);
            Fx.ring(centro, r, Math.max(16, (int) (r * 6)), l -> {
                Location g = enSuelo(l);
                Compat.spawn(w, Compat.DUST, g, 1, 0, 0, 0, 0, p);
                Compat.spawn(w, Compat.SOUL, g.clone().add(0, 0.2, 0), 1, 0.02, 0.1, 0.02, 0.005);
            });
            for (Player v : Fx.playersNear(centro, hasta + 1)) {
                if (tocados.contains(v.getUniqueId()) || !v.isOnGround()) continue;
                Location lv = v.getLocation();
                double d = Math.hypot(lv.getX() - centro.getX(), lv.getZ() - centro.getZ());
                if (Math.abs(d - r) > 0.7 || Math.abs(lv.getY() - centro.getY()) > 1.5) continue;
                tocados.add(v.getUniqueId());
                golpear(v, K_ONDA);
                Compat.apply(v, "slowness", 30, 1);
                push(v, plano(lv.toVector().subtract(centro.toVector())).multiply(0.4).setY(0.25));
            }
            return r >= hasta;
        }
    }

    // --------------------------------------------------------------- Embestida

    /**
     * Retirada y embestida: se aparta de un salto hacia atras (hasta 8 bloques, lo que deje el
     * terreno), se agacha marcando un camino largo que pasa por su presa y embiste a 2 bloques
     * por tick atravesandolo todo. Golpe fuerte, empujon y una estela que arde 3 s.
     */
    private final class Embestida extends Tecnica {
        final UUID quien;
        final Vector atras;
        final List<Location> ruta = new ArrayList<>();
        final List<Location> estela = new ArrayList<>();
        final Set<UUID> tocados = new HashSet<>();
        int etapa;
        int aviso;
        int indice;
        long desde;
        Vector dir;

        Embestida(Player obj) {
            super(HabilidadParca.EMBESTIDA);
            quien = obj.getUniqueId();
            atras = hacia(obj.getLocation(), pie());
            Compat.sound(pie().getWorld(), pie(), "entity.phantom.flap", 1.4f, 0.5f);
        }

        @Override
        boolean paso(World w) {
            long dt = t - desde;
            if (etapa == 0) {
                if (dt < 10) {
                    Location s = pisar(pie(), atras, 0.8);
                    if (s != null) {
                        s.setDirection(atras.clone().multiply(-1));
                        mover(s);
                        Compat.spawn(w, Compat.LARGE_SMOKE, s.clone().add(0, 0.6, 0), 2, 0.3, 0.3, 0.3, 0.01);
                        Compat.spawn(w, Compat.SOUL, s.clone().add(0, 0.3, 0), 1, 0.2, 0.1, 0.2, 0.01);
                        return false;
                    }
                }
                Player obj = jugador(quien);
                if (obj == null) obj = objetivo();
                if (obj == null) return true;
                Location o = pie().clone();
                dir = hacia(o, obj.getLocation());
                trazar(o, dir, Math.min(24, o.distance(obj.getLocation()) + 6), ruta);
                aviso = avisoEmbestida(phase());
                etapa = 1;
                desde = t;
                npc.postura(Pose.SNEAKING);
                Compat.sound(w, o, "entity.ravager.roar", 0.9f, 0.6f);
                return false;
            }
            if (etapa == 1) {
                if (t % 2 == 0) pintarRuta(w, ruta, dir, dt / (double) aviso, 0.9);
                if (t % 3 == 0) Compat.spawn(w, Compat.SCULK_SOUL, pie().add(0, 0.3, 0), 3, 0.4, 0.1, 0.4, 0.02);
                if (dt < aviso) return false;
                etapa = 2;
                indice = 0;
                npc.postura(Pose.STANDING);
                blandir();
                Compat.sound(w, pie(), "item.trident.riptide_3", 1.5f, 0.5f);
            }
            Location antes = pie().clone();
            int hasta = Math.min(ruta.size() - 1, indice + 4);
            if (hasta > indice) {
                Location destino = ruta.get(hasta).clone();
                destino.setDirection(dir);
                mover(destino);
                for (int i = indice; i <= hasta; i++) {
                    Location l = ruta.get(i);
                    Compat.spawn(w, Compat.SOUL_FIRE_FLAME, l.clone().add(0, 0.3, 0), 2, 0.3, 0.3, 0.3, 0.01);
                    Compat.spawn(w, Compat.LARGE_SMOKE, l.clone().add(0, 0.8, 0), 1, 0.2, 0.3, 0.2, 0.01);
                    if (i % 2 == 0) estela.add(l.clone());
                }
                for (Player v : Fx.playersNear(destino, 6)) {
                    if (tocados.contains(v.getUniqueId())) continue;
                    Location lv = v.getLocation();
                    if (Math.abs(lv.getY() - destino.getY()) > 2.5) continue;
                    if (distanciaASegmento(lv.getX(), lv.getZ(), antes.getX(), antes.getZ(), destino.getX(), destino.getZ()) > 1.8) {
                        continue;
                    }
                    tocados.add(v.getUniqueId());
                    golpear(v, K_EMBESTIDA);
                    Vector lado = new Vector(-dir.getZ(), 0, dir.getX());
                    if (lado.dot(lv.toVector().subtract(destino.toVector())) < 0) lado.multiply(-1);
                    push(v, lado.multiply(0.8).add(dir.clone().multiply(0.5)).setY(0.45));
                    v.playHurtAnimation(PeleaParca.ladoDe(v, antes));
                }
                indice = hasta;
            }
            if (indice < ruta.size() - 1) return false;
            Location fin = pie();
            Compat.spawn(w, Compat.EXPLOSION, fin.clone().add(0, 0.8, 0), 1);
            Compat.spawn(w, Compat.LARGE_SMOKE, fin.clone().add(0, 0.4, 0), 14, 0.6, 0.3, 0.6, 0.02);
            Compat.sound(w, fin, "entity.warden.attack_impact", 1.6f, 0.6f);
            sacudir(fin, 10);
            ultimoSalto = ticks();
            if (estela.size() > 1) efectos.add(new Estela(new ArrayList<>(estela), 60));
            return true;
        }

        @Override
        Vector mira() {
            return etapa == 0 ? atras.clone().multiply(-1) : dir;
        }
    }

    // ----------------------------------------------------------------- Lluvia

    /** Una marca de la Lluvia: donde cae, cuando, y su circulo ya asentado en el suelo. */
    private record Marca(Location sitio, long cae, List<Location> circulo) {
    }

    /** Un circulo de aviso (borde y medio radio) asentado en el suelo una vez, para pintarlo cada 2 ticks. */
    private static List<Location> circulo(Location centro, double radio) {
        List<Location> out = new ArrayList<>();
        int n = Math.max(12, (int) (radio * 9));
        Fx.ring(centro, radio, n, l -> out.add(enSuelo(l)));
        Fx.ring(centro, radio * 0.55, Math.max(8, n / 2), l -> out.add(enSuelo(l)));
        return out;
    }

    /**
     * Lluvia de Almas (III-IV): 3 oleadas (4 en la IV), una cada 16 ticks. Cada oleada marca un
     * circulo bajo cada jugador a 26 (donde esta en ese momento) y unos cuantos al azar; las almas
     * se ven caer y al llegar al suelo pegan en el circulo. Quedarse quieto es comerselas todas.
     */
    private final class Lluvia extends Tecnica {
        final List<Marca> marcas = new ArrayList<>();
        final int oleadas;
        final int aviso;
        final double radio = 2.2;
        int lanzadas;

        Lluvia() {
            super(HabilidadParca.LLUVIA);
            oleadas = phase() >= 4 ? 4 : 3;
            aviso = avisoLluvia(phase());
            Compat.sound(pie().getWorld(), pie(), "block.bell.resonate", 1.5f, 0.6f);
        }

        @Override
        boolean paso(World w) {
            if (lanzadas < oleadas && t >= (long) lanzadas * 16) {
                oleada();
                lanzadas++;
                blandir();
                Compat.sound(w, pie(), "entity.vex.charge", 1.4f, 0.5f);
            }
            for (Iterator<Marca> it = marcas.iterator(); it.hasNext(); ) {
                Marca m = it.next();
                long falta = m.cae() - t;
                if (falta > 0) {
                    if (t % 2 == 0) {
                        double avance = 1 - falta / (double) aviso;
                        pintarPuntos(w, m.circulo(), avance, 1.4f);
                        Compat.spawn(w, Compat.SOUL, m.sitio().clone().add(0, 1 + falta * 0.35, 0), 2, 0.3, 0.2, 0.3, 0.01);
                    }
                    continue;
                }
                caer(w, m.sitio());
                it.remove();
            }
            return lanzadas >= oleadas && marcas.isEmpty();
        }

        private void oleada() {
            long cae = t + aviso;
            Location yo = pie();
            for (Player p : Fx.playersNear(yo, 26)) {
                Location l = Fx.ground(p.getLocation(), 6);
                marcas.add(new Marca(l, cae, circulo(l, radio)));
            }
            ThreadLocalRandom r = ThreadLocalRandom.current();
            int extras = phase() >= 4 ? 5 : 3;
            for (int i = 0; i < extras; i++) {
                double ang = r.nextDouble(Math.PI * 2), d = 3 + r.nextDouble(8);
                Location l = Fx.ground(yo.clone().add(Math.cos(ang) * d, 2, Math.sin(ang) * d), 8);
                marcas.add(new Marca(l, cae, circulo(l, radio)));
            }
        }

        private void caer(World w, Location l) {
            for (double y = 5; y >= 0; y -= 0.5) {
                Compat.spawn(w, Compat.SOUL, l.clone().add(0, y, 0), 1, 0.15, 0.1, 0.15, 0.01);
            }
            Compat.spawn(w, Compat.SOUL_FIRE_FLAME, l.clone().add(0, 0.2, 0), 14, radio / 2, 0.1, radio / 2, 0.04);
            Compat.spawn(w, Compat.SCULK_SOUL, l.clone().add(0, 0.4, 0), 6, radio / 3, 0.2, radio / 3, 0.02);
            Compat.sound(w, l, "block.soul_sand.break", 1.4f, 0.6f);
            Compat.sound(w, l, "entity.allay.death", 0.8f, 0.5f);
            for (Player v : Fx.playersNear(l, radio + 1)) {
                Location lv = v.getLocation();
                double dx = lv.getX() - l.getX(), dz = lv.getZ() - l.getZ();
                if (dx * dx + dz * dz > radio * radio || Math.abs(lv.getY() - l.getY()) > 3) continue;
                golpear(v, K_LLUVIA);
                if (hc.esHardcore(v)) hc.cordura().sumar(v, -3);
            }
        }
    }

    // ------------------------------------------------------------------ Muros

    /**
     * Muros de Almas (III-IV): una cruz de cortinas de almas centrada en ella que parte la arena
     * en cuatro y gira (1,2 grados por tick en la III; 1,8 en la IV, y a mitad cambia de sentido).
     * Tocar un muro pega y te empuja por delante del giro: hay que andar con la cruz. En el
     * centro hay un hueco donde se le puede pegar a ella, que sigue quieta canalizando.
     */
    private final class Muros extends Tecnica {
        final Location centro;
        final double ang0;
        final int aviso;
        final int activo;
        final double vel;
        final double rMin = 1.8, rMax = 12;
        final Map<UUID, Long> ultimoToque = new HashMap<>();

        Muros(Player obj) {
            super(HabilidadParca.MUROS);
            centro = pie().clone();
            ang0 = Math.atan2(obj.getLocation().getZ() - centro.getZ(), obj.getLocation().getX() - centro.getX()) + Math.PI / 4;
            aviso = avisoMuros(phase());
            activo = phase() >= 4 ? 110 : 100;
            vel = Math.toRadians(phase() >= 4 ? 1.8 : 1.2) * (ThreadLocalRandom.current().nextBoolean() ? 1 : -1);
            npc.postura(Pose.SPIN_ATTACK);
            Compat.sound(centro.getWorld(), centro, "block.respawn_anchor.charge", 1.5f, 0.5f);
        }

        private double angulo(long tt) {
            if (tt < aviso) return ang0;
            long s = tt - aviso;
            if (phase() >= 4 && s > activo / 2) return ang0 + vel * (activo / 2.0) - vel * (s - activo / 2.0);
            return ang0 + vel * s;
        }

        @Override
        boolean paso(World w) {
            double ang = angulo(t);
            if (t < aviso) {
                if (t % 2 == 0) {
                    Particle.DustOptions p = Compat.dust(tono(t / (double) aviso), 1.4f);
                    for (int k = 0; k < 4; k++) {
                        double b = ang + k * Math.PI / 2;
                        for (double r = rMin; r <= rMax; r += 0.8) {
                            Compat.spawn(w, Compat.DUST, centro.clone().add(Math.cos(b) * r, 0.2, Math.sin(b) * r), 1, 0, 0, 0, 0, p);
                        }
                    }
                }
                return false;
            }
            long s = t - aviso;
            if (s == 0) {
                npc.postura(Pose.STANDING);
                blandir();
                Compat.sound(w, centro, "entity.warden.roar", 1.2f, 0.6f);
            }
            if (s % 20 == 0) Compat.sound(w, centro, "block.soul_sand.step", 1.5f, 0.5f);
            if (t % 2 == 0) {
                Particle.DustOptions p = Compat.dust(RGB_ALMA, 1.3f);
                for (int k = 0; k < 4; k++) {
                    double b = ang + k * Math.PI / 2;
                    for (double r = rMin; r <= rMax; r += 0.9) {
                        Location l = centro.clone().add(Math.cos(b) * r, 0.3, Math.sin(b) * r);
                        Compat.spawn(w, (t / 2) % 2 == 0 ? Compat.SOUL_FIRE_FLAME : Compat.SOUL, l, 1, 0.05, 0.3, 0.05, 0.005);
                        Compat.spawn(w, Compat.DUST, l.clone().add(0, 1.2, 0), 1, 0.05, 0.4, 0.05, 0, p);
                    }
                }
            }
            for (Player v : Fx.playersNear(centro, rMax + 1.5)) {
                Location lv = v.getLocation();
                double dx = lv.getX() - centro.getX(), dz = lv.getZ() - centro.getZ();
                double d = Math.hypot(dx, dz);
                if (d < rMin - 0.3 || d > rMax + 0.5 || Math.abs(lv.getY() - centro.getY()) > 3) continue;
                Long u = ultimoToque.get(v.getUniqueId());
                if (u != null && t - u < 10) continue;
                double pa = Math.atan2(dz, dx);
                for (int k = 0; k < 4; k++) {
                    double b = ang + k * Math.PI / 2;
                    double diff = normalizar(pa - b);
                    if (Math.abs(diff) > Math.PI / 2 || d * Math.abs(Math.sin(diff)) > 0.75) continue;
                    ultimoToque.put(v.getUniqueId(), t);
                    golpear(v, K_MURO);
                    double sentido = Math.signum(vel) * (phase() >= 4 && s > activo / 2 ? -1 : 1);
                    Vector tangente = new Vector(-Math.sin(b), 0, Math.cos(b)).multiply(sentido);
                    push(v, tangente.multiply(0.55).setY(0.2));
                    break;
                }
            }
            if (s < activo) return false;
            for (int k = 0; k < 4; k++) {
                double b = ang + k * Math.PI / 2;
                for (double r = rMin; r <= rMax; r += 1.5) {
                    Compat.spawn(w, Compat.LARGE_SMOKE, centro.clone().add(Math.cos(b) * r, 0.8, Math.sin(b) * r), 2, 0.2, 0.4, 0.2, 0.01);
                }
            }
            Compat.sound(w, centro, "block.respawn_anchor.deplete", 1.5f, 0.6f);
            return true;
        }
    }

    // ----------------------------------------------------------------- Anillo

    /**
     * El Anillo que se Cierra (IV): un anillo de almas a 14 bloques de ella, con UN hueco (se ve
     * casi blanco), se cierra en 2,8 s. Al que pase por encima fuera del hueco le pega y le frena;
     * al final implota en el centro. La salida es cruzarlo por el hueco antes de que llegue.
     */
    private final class Anillo extends Tecnica {
        final Location centro;
        final double r0 = 14, rFin = 1.5;
        final double hueco;
        final double ancho = Math.toRadians(55);
        final int cierre = 56;
        final Set<UUID> tocados = new HashSet<>();

        Anillo() {
            super(HabilidadParca.ANILLO);
            centro = pie().clone();
            hueco = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            Compat.sound(centro.getWorld(), centro, "block.bell.use", 2f, 0.5f);
            Compat.sound(centro.getWorld(), centro, "block.bell.resonate", 1.5f, 0.5f);
        }

        private double radio() {
            if (t < AVISO_ANILLO) return r0;
            double k = Math.min(1, (t - AVISO_ANILLO) / (double) cierre);
            return r0 + (rFin - r0) * k;
        }

        @Override
        boolean paso(World w) {
            double r = radio();
            if (t % 2 == 0) {
                int n = Math.max(24, (int) (r * 7));
                Particle.DustOptions p = Compat.dust(t < AVISO_ANILLO ? tono(t / (double) AVISO_ANILLO) : RGB_HASTA, 1.5f);
                Particle.DustOptions salida = Compat.dust(RGB_HUECO, 1.6f);
                for (int i = 0; i < n; i++) {
                    double b = i * Math.PI * 2 / n;
                    Location l = centro.clone().add(Math.cos(b) * r, 0.25, Math.sin(b) * r);
                    if (enHueco(b, hueco, ancho)) {
                        if (i % 3 == 0) Compat.spawn(w, Compat.DUST, l, 1, 0, 0, 0, 0, salida);
                        continue;
                    }
                    Compat.spawn(w, Compat.DUST, l, 1, 0, 0, 0, 0, p);
                    if (t >= AVISO_ANILLO && i % 2 == 0) {
                        Compat.spawn(w, Compat.SOUL_FIRE_FLAME, l.clone().add(0, 0.5, 0), 1, 0.05, 0.4, 0.05, 0.005);
                    }
                }
                // Los dos postes del hueco: se ven de lejos.
                for (int lado = -1; lado <= 1; lado += 2) {
                    double b = hueco + lado * ancho / 2;
                    Compat.spawn(w, Compat.END_ROD, centro.clone().add(Math.cos(b) * r, 1.2, Math.sin(b) * r), 2, 0.05, 0.6, 0.05, 0);
                }
            }
            if (t < AVISO_ANILLO) return false;
            if ((t - AVISO_ANILLO) % 10 == 0) Compat.sound(w, centro, "entity.warden.heartbeat", 2f, 0.8f + (float) (1 - r / r0) * 0.6f);
            for (Player v : Fx.playersNear(centro, r0 + 1)) {
                if (tocados.contains(v.getUniqueId())) continue;
                Location lv = v.getLocation();
                double dx = lv.getX() - centro.getX(), dz = lv.getZ() - centro.getZ();
                double d = Math.hypot(dx, dz);
                if (Math.abs(d - r) > 0.7 || Math.abs(lv.getY() - centro.getY()) > 3) continue;
                if (enHueco(Math.atan2(dz, dx), hueco, ancho)) continue;
                tocados.add(v.getUniqueId());
                golpear(v, K_ANILLO);
                Compat.apply(v, "slowness", 20, 1);
            }
            if (t < AVISO_ANILLO + cierre) return false;
            blandir();
            Compat.spawn(w, Compat.SOUL, centro.clone().add(0, 1, 0), 60, 1.5, 1, 1.5, 0.08);
            Compat.spawn(w, Compat.SCULK_SOUL, centro.clone().add(0, 1, 0), 30, 1.5, 0.8, 1.5, 0.05);
            Compat.sound(w, centro, "entity.warden.sonic_boom", 1.6f, 0.7f);
            for (Player v : Fx.playersNear(centro, 3.7)) {
                Location lv = v.getLocation();
                if (Math.hypot(lv.getX() - centro.getX(), lv.getZ() - centro.getZ()) > 3.2) continue;
                golpear(v, K_IMPLOSION);
                push(v, plano(lv.toVector().subtract(centro.toVector())).multiply(0.8).setY(0.4));
            }
            sacudir(centro, 18);
            return true;
        }
    }

    // --------------------------------------------------------------- Sentencia

    /**
     * La Sentencia (firma de la IV): cinco toques cada campanada.cada-ticks. El anillo late y se
     * oscurece con cada campanada; en el 3.o, titulo a quien este cerca; en el 4.o, aviso a quien
     * siga dentro; en el 5.o, dano verdadero (tope 90 %), -cordura y Oscuridad a los de dentro.
     * Mientras toca recibe +25 %.
     */
    private final class Sentencia extends Tecnica {
        final Location origen;
        final List<Location> anillo = new ArrayList<>();
        final List<Location> dentro = new ArrayList<>();
        int toques;
        long ultimoToque;

        Sentencia() {
            super(HabilidadParca.SENTENCIA);
            origen = pie().clone();
            int pts = Math.max(24, (int) (a.campRadio * 10));
            Fx.ring(origen, a.campRadio, pts, l -> anillo.add(enSuelo(l)));
            Fx.ring(origen, a.campRadio * 0.55, Math.max(12, pts / 2), l -> dentro.add(enSuelo(l)));
        }

        @Override
        boolean paso(World w) {
            double oscuro = a.campToques <= 1 ? 1 : toques / (double) a.campToques;
            double pulso = Math.max(0, 1 - (t - ultimoToque) / 12.0);
            if (t % 2 == 0) {
                Particle.DustOptions polvo = Compat.dust(PeleaParca.mezcla(RGB_DESDE, RGB_SENTENCIA_HASTA, oscuro),
                        (float) (1.3 + 1.2 * pulso));
                for (Location l : anillo) Compat.spawn(w, Compat.DUST, l, 1, 0, 0, 0, 0, polvo);
                if (t % 4 == 0) for (Location l : dentro) Compat.spawn(w, Compat.DUST, l, 1, 0, 0, 0, 0, polvo);
            }
            if (t % 4 == 0 && !anillo.isEmpty()) {
                ThreadLocalRandom azar = ThreadLocalRandom.current();
                for (int i = 0; i < 2 + toques * 2; i++) {
                    Location l = anillo.get(azar.nextInt(anillo.size()));
                    Location d = origen.clone().add(l.toVector().subtract(origen.toVector()).multiply(azar.nextDouble()));
                    Compat.spawn(w, i % 2 == 0 ? Compat.SMOKE : Compat.SCULK_SOUL, d.add(0, 0.3, 0), 1, 0.1, 0.3, 0.1, 0.01);
                }
            }
            while (toques < a.campToques && t >= (long) (toques + 1) * a.campCada) {
                toques++;
                ultimoToque = t;
                double avance = a.campToques <= 1 ? 1 : (toques - 1) / (double) (a.campToques - 1);
                Compat.sound(w, origen, "block.bell.use", 2.0f, (float) (0.8 - 0.3 * avance));
                Compat.sound(w, origen, "entity.warden.heartbeat", 2.0f, (float) (0.7 + 0.3 * avance));
                Fx.shockwave(w, origen, a.campRadio, Compat.SOUL, 6);
                nombreBarra();
                if (toques == 3) {
                    Title titulo = Paleta.titulo(Paleta.muerte("Aléjate"), "Campanada 3 de " + a.campToques + ": sal del anillo",
                            Duration.ofMillis(100), Duration.ofMillis(1600), Duration.ofMillis(400));
                    for (Player o : Fx.viewersNear(origen, 12)) o.showTitle(titulo);
                }
                if (toques == 4) {
                    Component sal = Component.text("Sal del anillo.", Paleta.AVISO);
                    for (Player o : Fx.playersNear(origen, a.campRadio)) hc.cordura().destello(o, sal, 2);
                }
                if (toques >= a.campToques) {
                    sentencia(w);
                    return true;
                }
            }
            return false;
        }

        private void sentencia(World w) {
            Compat.spawn(w, Compat.SOUL, origen.clone().add(0, 1, 0), 80, a.campRadio / 2, 1, a.campRadio / 2, 0.05);
            Compat.spawn(w, Compat.SCULK_SOUL, origen.clone().add(0, 1, 0), 40, a.campRadio / 2, 0.8, a.campRadio / 2, 0.03);
            Compat.spawn(w, Compat.LARGE_SMOKE, origen.clone().add(0, 0.5, 0), 50, a.campRadio / 2, 0.5, a.campRadio / 2, 0.02);
            Compat.spawn(w, Compat.SONIC_BOOM, origen.clone().add(0, 1.2, 0), 1);
            for (int i = 0; i < anillo.size(); i += 2) {
                Compat.spawn(w, Compat.SOUL_FIRE_FLAME, anillo.get(i).clone().add(0, 0.2, 0), 2, 0.1, 0.4, 0.1, 0.03);
            }
            Compat.sound(w, origen, "entity.warden.sonic_boom", 3f, 0.6f);
            Compat.sound(w, origen, "block.sculk_shrieker.shriek", 2f, 0.5f);
            Compat.sound(w, origen, "block.respawn_anchor.deplete", 2f, 0.5f);
            double r2 = a.campRadio * a.campRadio;
            for (Player v : Fx.playersNear(origen, a.campRadio + 1)) {
                double dx = v.getLocation().getX() - origen.getX(), dz = v.getLocation().getZ() - origen.getZ();
                if (dx * dx + dz * dz > r2) continue;
                double vidaMax = Compat.getAttribute(v, "max_health", 20);
                DanoVerdadero.aplicar(v, Parca.sentenciaFraccion(a, factorR) * vidaMax, a.campTope, cuerpo, "Sentencia");
                ultimoGolpe = ticks();
                Compat.apply(v, "darkness", 30, 0);
                if (hc.esHardcore(v)) hc.cordura().sumar(v, -a.campCordura);
            }
            sacudir(origen, a.campRadio + 8);
        }

        @Override
        String rotulo() {
            return "Sentencia " + Math.max(1, toques) + "/" + a.campToques;
        }
    }

    // --------------------------------------------------------------- Transicion

    /**
     * El cambio de fase: 3 s invulnerable y quieta, alzandose. Campanas que bajan, Oscuridad
     * 2 s a quien este a 32, niebla de almas y ceniza por la arena, el borde de la arena
     * marcado en el suelo y, a los 2,2 s, una onda que aparta a los que esten pegados (sin
     * dano). Al acabar arranca la tecnica de firma de la fase nueva.
     */
    private final class Transicion extends Tecnica {
        final int fase;
        final Location centro;

        Transicion(int fase) {
            super(null);
            this.fase = fase;
            this.centro = pie().clone();
        }

        @Override
        boolean paso(World w) {
            if (t == 0) {
                cuerpo.setInvulnerable(true);
                cuerpo.setAI(false);
                Title titulo = Paleta.titulo(Paleta.muerte(Parca.romano(fase) + " · " + nombreFase(fase)),
                        Parca.subtituloFase(fase), Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(700));
                for (Player p : Fx.viewersNear(centro, RADIO_AMBIENTE)) p.showTitle(titulo);
                for (Player p : Fx.playersNear(centro, 32)) Compat.apply(p, "darkness", 40, 0);
                Compat.sound(w, centro, "block.bell.use", 3f, 0.6f);
                Compat.sound(w, centro, "entity.warden.emerge", 1.5f, 0.7f);
            }
            if (t == 16) Compat.sound(w, centro, "block.bell.use", 3f, 0.5f);
            if (t == 32) Compat.sound(w, centro, "block.bell.use", 3f, 0.42f);
            alzado = Math.min(0.9, t * 0.03);
            if (t % 3 == 0) {
                Fx.helix(pie(), 1.0, 2.6, 10, 2, l -> Compat.spawn(w, Compat.SOUL, l.add(0, t * 0.01, 0), 1, 0, 0, 0, 0));
            }
            if (t % 4 == 0) {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                for (int i = 0; i < 10; i++) {
                    double ang = r.nextDouble(Math.PI * 2), d = r.nextDouble(12);
                    Location l = centro.clone().add(Math.cos(ang) * d, 0.5 + r.nextDouble(2), Math.sin(ang) * d);
                    Compat.spawn(w, i % 3 == 0 ? Compat.SCULK_SOUL : i % 3 == 1 ? Compat.LARGE_SMOKE : Compat.ASH, l, 2, 0.4, 0.3, 0.4, 0.005);
                }
                Fx.ring(centro, 14, 56, t * 0.02, l -> Compat.spawn(w, Compat.SOUL_FIRE_FLAME,
                        l.clone().add(0, 0.2, 0), 1, 0.05, 0.1, 0.05, 0.005));
            }
            if (t == 44) {
                for (int i = 1; i <= 3; i++) Fx.shockwave(w, centro, i * 2.5, Compat.SOUL, 5);
                Compat.sound(w, centro, "entity.warden.attack_impact", 2f, 0.5f);
                for (Player p : Fx.playersNear(centro, 6)) {
                    push(p, plano(p.getLocation().toVector().subtract(centro.toVector())).multiply(0.7).setY(0.3));
                }
            }
            if (t < TICKS_TRANSICION) return false;
            alzado = 0;
            cuerpo.setInvulnerable(false);
            cuerpo.setAI(true);
            efectos.add(new MarcaArena(centro, 14, 100));
            siguiente = firma(fase);
            return true;
        }

        @Override
        void cortar() {
            alzado = 0;
            if (cuerpo != null && cuerpo.isValid()) {
                cuerpo.setInvulnerable(false);
                cuerpo.setAI(true);
            }
        }

        @Override
        String rotulo() {
            return nombreFase(fase);
        }
    }

    /** El borde de la arena marcado en el suelo unos segundos tras cada cambio de fase (solo se ve). */
    private final class MarcaArena extends Efecto {
        final Location centro;
        final double radio;
        final int dura;

        MarcaArena(Location centro, double radio, int dura) {
            this.centro = centro;
            this.radio = radio;
            this.dura = dura;
        }

        @Override
        boolean paso(World w) {
            if (t % 10 == 0) {
                Particle.DustOptions p = Compat.dust(RGB_PARCA, 1.2f);
                Fx.ring(centro, radio, 64, t * 0.01, l -> {
                    Compat.spawn(w, Compat.DUST, l.clone().add(0, 0.2, 0), 1, 0, 0, 0, 0, p);
                    if (ThreadLocalRandom.current().nextInt(4) == 0) {
                        Compat.spawn(w, Compat.SOUL_FIRE_FLAME, l.clone().add(0, 0.2, 0), 1, 0.05, 0.1, 0.05, 0.005);
                    }
                });
            }
            return t >= dura;
        }
    }

    // =================================================================== fin

    /** La presa se desconecta sin etiqueta: se queda quieta espera-desconexion-segundos. */
    @Override
    public void esperar() {
        if (estado == Estado.FIN || estado == Estado.COSECHA) return;
        cortarTecnica();
        quitarEfectos();
        estado = Estado.ESPERA;
        esperaHasta = System.currentTimeMillis() + a.esperaDesconexion * 1000L;
        cuerpo.setAI(false);
        cuerpo.setInvulnerable(false);
        for (Wither v : planideras) Fx.safeRemove(v);
        planideras.clear();
        planActivas = false;
        hc.plugin().bitacora().anotar("parca", "espera", presaNombre, a.esperaDesconexion + " s", "anomalia");
    }

    @Override
    public void reanudar() {
        if (estado != Estado.ESPERA) return;
        estado = Estado.PELEA;
        cuerpo.setAI(true);
        velocidad();
        long tk = ticks();
        ultimoGolpe = tk;
        ultimoSalto = tk;
        respiroHasta = tk + 20;
        hc.plugin().bitacora().anotar("parca", "reanuda", presaNombre, "anomalia");
    }

    /** La presa ha muerto (por ella o por lo que sea): 3 s quieta con una helice de almas y se va sin botin. */
    @Override
    public void cosecha(boolean porElla) {
        if (estado == Estado.FIN || estado == Estado.COSECHA) return;
        cortarTecnica();
        quitarEfectos();
        estado = Estado.COSECHA;
        cosechaDesde = ticks();
        cuerpo.setAI(false);
        cuerpo.setInvulnerable(true);
        for (Wither v : planideras) Fx.safeRemove(v);
        planideras.clear();
        Compat.sound(cuerpo.getWorld(), cuerpo.getLocation(), "block.bell.use", 3f, 0.5f);
        hc.plugin().bitacora().anotar("parca", "cosecha", presaNombre, porElla ? "por ella" : "por otra cosa",
                (System.currentTimeMillis() - nacio) / 1000 + " s", "anomalia");
        gestor.telemetria("cosecha", this, null, null);
    }

    private void cosechar(World w, long tk) {
        long t = tk - cosechaDesde;
        if (t % 4 == 0) {
            double h = Math.min(3, t / 20.0);
            Fx.helix(cuerpo.getLocation(), 0.8, h, 12, 2, l -> Compat.spawn(w, Compat.SOUL, l, 1, 0, 0, 0, 0));
        }
        if (t >= TICKS_COSECHA) {
            graciaMarcados();
            limpiar();
        }
    }

    /**
     * Ha caido (lo llama EDM en su muerte, y Parca.onMuerte despues: la segunda vez no hace nada).
     * El botin lo reparte el gestor por la Aduana con el dano logico de Amenazas. EDM remata con
     * su destello y barre la escena despues: aqui no se le cierra nada.
     */
    @Override
    public void alMorir() {
        if (pagada || estado == Estado.FIN || cuerpo == null) return;
        pagada = true;
        Map<UUID, Double> dano = hc.amenazas().danoLogico(cuerpo);
        double vida = vidaFinal();
        long segundos = (System.currentTimeMillis() - nacio) / 1000;
        World w = cuerpo.getWorld();
        Location l = cuerpo.getLocation();
        Compat.sound(w, l, "block.bell.use", 4f, 0.4f);
        Compat.sound(w, l, "entity.wither_skeleton.death", 1.5f, 0.5f);
        Compat.sound(w, l, "entity.warden.death", 1.5f, 0.7f);
        Compat.sound(w, l, "block.respawn_anchor.deplete", 2f, 0.4f);
        Fx.helix(l, 1.0, 3.0, 40, 3, p -> Compat.spawn(w, Compat.SOUL, p, 1, 0, 0, 0, 0.01));
        Compat.spawn(w, Compat.SOUL_FIRE_FLAME, l.clone().add(0, 1, 0), 40, 0.6, 1.0, 0.6, 0.05);
        sacudir(l, 16);
        // El cuerpo que se ve cae y se deshace en almas (Parca.despedida): la limpieza ya no lo toca.
        Mannequin caido = npc.soltar();
        if (caido != null) gestor.despedida(caido);
        hc.seguro("parca", () -> gestor.pagar(this, dano, vida, segundos));
        estado = Estado.FIN;
        edmCerrado = true;
        limpiarPropio();
        graciaMarcados();
        if (presa != null) gestor.borrarPendiente(presa);
    }

    @Override
    public void onDeath() {
        hc.seguro("parca", this::alMorir);
    }

    /** Se va sin botin (retirada, puerta, desconexion, cansada...). Humo, almas y una campanada. */
    @Override
    public void irse(String motivo, Component aviso) {
        if (estado == Estado.FIN) return;
        if (cuerpo != null && cuerpo.isValid()) {
            World w = cuerpo.getWorld();
            Location l = cuerpo.getLocation();
            Compat.spawn(w, Compat.LARGE_SMOKE, l.clone().add(0, 1, 0), 40, 0.5, 1, 0.5, 0.02);
            Compat.spawn(w, Compat.SOUL, l.clone().add(0, 1, 0), 30, 0.5, 1, 0.5, 0.02);
            Compat.sound(w, l, "block.bell.use", 2f, 0.5f);
            if (aviso != null) for (Player o : Fx.viewersNear(l, 48)) o.sendMessage(aviso);
        }
        hc.plugin().bitacora().anotar("parca", "se-va", "sin botin", presaNombre, motivo, "anomalia");
        gestor.telemetria("se-va", this, null, null);
        graciaMarcados();
        limpiar();
    }

    /** Fin de la PARCA para sus marcados: gracia-minutos sin contar en la Huella. */
    private void graciaMarcados() {
        if (hc.huella() == null) return;
        for (UUID id : marcados) {
            Player m = hc.plugin().getServer().getPlayer(id);
            if (m != null && hc.esHardcore(m)) hc.seguro("huella", () -> hc.huella().gracia(m));
        }
    }

    /**
     * Retira todo lo suyo (idempotente) y cierra la anomalia en EDM si sigue abierta: EDM llama
     * a cleanup(), que ya la encuentra acabada.
     */
    @Override
    public void limpiar() {
        estado = Estado.FIN;
        limpiarPropio();
        if (cuerpo != null) Fx.safeRemove(cuerpo);
        if (!edmCerrado) {
            edmCerrado = true;
            puente.cerrar(event);
        }
    }

    /** Lo que es suyo y no de EDM: barra, ambiente, tecnica, efectos, planideras, cadena y el maniqui. */
    private void limpiarPropio() {
        puente.callada(this, false);
        quitarBarra();
        quitarAmbiente();
        if (actual != null) {
            try {
                actual.cortar();
            } catch (Throwable ignorado) {
                // Cortar no puede impedir limpiar.
            }
        }
        actual = null;
        quitarEfectos();
        for (Wither v : planideras) Fx.safeRemove(v);
        planideras.clear();
        planActivas = false;
        cadena.quitar();
        npc.quitar();
        rastro.clear();
    }

    /**
     * EDM cierra el evento: tras la muerte (ya acabada aqui), o sin que muera: tiempo limite,
     * /anomaly stop, recarga o apagado de EDM. En los dos ultimos casos la PARCA se va como
     * si se cansara (con el tiempo) o sin mas (a mano), y lo suyo se retira antes que lo de EDM.
     */
    @Override
    public void cleanup() {
        edmCerrado = true;
        if (estado != Estado.FIN && cuerpo != null) {
            hc.seguro("parca", () -> {
                boolean tiempo = event.elapsedSeconds() >= plugin.settings().timeLimitMinutes() * 60L;
                if (tiempo && !prueba) {
                    gestor.guardarPendiente(this, System.currentTimeMillis() + 30 * 60_000L, "cansada");
                    irse("cansada", ComandoCalamity.mensaje(Parca.CANSADA));
                } else {
                    irse("anomalia-cerrada", null);
                }
            });
        }
        estado = Estado.FIN;
        limpiarPropio();
        super.cleanup();
    }
}
