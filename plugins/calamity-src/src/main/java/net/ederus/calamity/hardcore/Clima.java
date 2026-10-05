package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Plataforma;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.TriState;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Calamity 1.9.0 · El clima de Calamity por bioma, solo mientras dura la lluvia del ciclo. Encargo de
 * Dosa: en los biomas verdes la lluvia es acida y hace dano; en los rojos el cielo se oscurece en rojo y
 * cada cierto tiempo arde.
 *
 * Calamity 1.12 · Encargo de Dosa (2026-10-05): la lluvia de Minecraft se retira por completo, y cada bioma
 * tiene su propio clima, muy visible: lluvia acida en los pantanos y biomas toxicos, esporas (quitan
 * cordura) en los de hongos y bosques oscuros, polinizacion (polen amarillo y Lentitud) en el colmenar,
 * ceniza en la taiga condenada y el cielo rojo de siempre en el organismo carmesi. La tabla bioma -> tipo
 * es hardcore.clima.por-bioma.
 *
 * Por que el mundo ya no llueve nunca: hasta la 1.11 el ciclo ponia World#setStorm(true) y aqui se le
 * ocultaba la lluvia a cada jugador con setPlayerWeather(CLEAR) una vez por segundo. El servidor manda el
 * paquete de "empieza a llover" a todos en cuanto cambia el mundo, asi que siempre habia un hueco de hasta
 * un segundo (y el fundido de lluvia del cliente) antes de que llegara el CLEAR, ademas de en cada
 * entrada, respawn, cambio de mundo o paso por un bioma sin clima propio. Ahora la fase (despejado,
 * lluvia, tormenta) es solo un estado de CicloClima: el mundo se queda despejado, WeatherChangeEvent y
 * ThunderChangeEvent hacia lluvia se cancelan en los mundos de Calamity (un /weather, otro plugin, el
 * ciclo vanilla) y el cliente nunca recibe nada de lluvia. Todo lo que se ve lo pinta este modulo.
 *
 * Lo que se ve: particulas solo para cada jugador (Player#spawnParticle: un paquete por capa) a su
 * alrededor, repartidas en pulsos cada efectos.cada-ticks, y sonidos suyos. Bajo techo solo siguen las
 * capas que caen (techo: true, chocan con el tejado) y bien bajo tierra (efectos.profundidad) nada.
 * Nada de esto entra en la zona spawn salvo el cielo de sangre del bioma rojo (cielo-rojo.en-spawn).
 *
 * Lluvia acida: a cielo abierto (las hojas tapan, como tapaba la lluvia de verdad) quema tras un aviso y
 * un margen. Va por damage(MAGIC) sin entidad y no por DanoVerdadero: no es un combate, asi que no pone
 * la etiqueta "En combate" ni congela la Huella.
 *
 * Cielo rojo: la hora del cielo de sangre del ciclo de Panacea (recalculada cada segundo, ver offsetHora)
 * y Vineta le suma su borde rojo. La quemadura es a mano: fuego visual y dano de fuego (ON_FIRE) cada
 * segundo; la resistencia al fuego lo para.
 *
 * Esporas, polinizacion y ceniza: a cielo abierto (de serie las hojas no tapan las esporas ni el polen,
 * que flotan) quitan cordura-por-segundo y/o ponen efecto (Lentitud en el polen), con un aviso en la
 * barra la primera vez de cada episodio.
 *
 * Convivencia: solo se devuelve lo que puso este modulo, y solo si sigue siendo lo suyo. El cielo de la
 * PARCA manda (Parca.cieloSobre) y la hora del Eclipse tambien. En una zona pintada con /lbiomes el bioma
 * deja de ser de Panacea: la tabla de serie no la nombra y ahi este modulo no pinta nada.
 */
final class Clima implements Listener {

    /** Nombre del golpe en el parte de defuncion (linea del golpe y "por que"). */
    static final String CAUSA_ACIDA = "lluvia ácida";
    static final String CAUSA_CIELO = "cielo rojo";

    /*
     * Los valores de serie. Son los mismos que trae hardcore.clima en el config.yml del jar (el
     * autotest los compara uno a uno): si el servidor no tiene la clave, da igual de donde se lean.
     */

    /** La tabla de serie bioma -> tipo (hardcore.clima.por-bioma). "*" = cualquier otro. */
    static final Map<String, String> POR_BIOMA = Collections.unmodifiableMap(porBiomaDeSerie());

    static final double ACIDA_DANO = 1.0;
    static final int ACIDA_CADA = 2;
    static final int ACIDA_MARGEN = 2;
    static final int ACIDA_OLVIDO = 15;
    /** La hora del cielo de sangre del ciclo de Panacea (dia de 72000 ticks; el rojo va de 63500 a 65000). */
    static final long CIELO_HORA = 64_250L;
    /** Lo que dura el dia de Panacea: el period_ticks de bracken:timeline/panacea_day. */
    static final long CIELO_PERIODO = 72_000L;
    /**
     * El bloque en el que el servidor fija la hora. Con la hora fija (relative false) no manda el
     * offset tal cual: ServerPlayer.getPlayerTime devuelve reloj - (reloj % 24000) + offset, y el
     * cliente lo aplica al reloj de la dimension (minecraft:overworld, el mismo del timeline).
     */
    static final long BLOQUE_SERVIDOR = 24_000L;
    static final double CIELO_VINETA = 0.4;
    static final int CIELO_CADA = 20;
    static final int CIELO_ARDE = 4;
    static final int CIELO_AVISO = 2;
    static final double CIELO_DANO = 1.0;
    static final int CIELO_OLVIDO = 30;

    /** 1.12: esporas, polinizacion y ceniza. */
    static final double ESPORAS_CORDURA = 0.1;
    static final String POLEN_EFECTO = "slowness";
    static final int POLEN_NIVEL = 1;
    static final int EXPO_OLVIDO = 15;
    /** Lo que dura cada toque del efecto (se renueva cada segundo mientras sigue fuera). */
    static final int EFECTO_TICKS = 50;

    /** 1.12: los pulsos de particulas y el aviso de inicio. */
    static final int EFECTOS_CADA_TICKS = 4;
    static final double DENSIDAD = 1.0;
    static final int PROFUNDIDAD = 12;
    static final double VOLUMEN_CUBIERTO = 0.5;
    static final double TORMENTA_DENSIDAD = 1.5;
    static final boolean AVISO_INICIO = true;
    static final int AVISO_INICIO_ESPERA = 90;
    /** Tope de particulas por segundo y jugador con la config del jar, tormenta incluida (autotest). */
    static final int PRESUPUESTO_SEGUNDO = 300;
    /** Las particulas que son la lluvia de Minecraft (azules): ningun tipo puede usarlas. */
    static final Set<String> PARTICULAS_DE_LLUVIA = Set.of("RAIN", "FALLING_WATER", "DRIPPING_WATER", "SPLASH",
            "FALLING_DRIPSTONE_WATER", "DRIPPING_DRIPSTONE_WATER", "BUBBLE", "BUBBLE_POP");

    /** Lo que le toca al bioma en el que esta. El id es tambien el nombre de su seccion en la config. */
    enum Tipo {
        NINGUNO("ninguno"),
        ACIDA("lluvia-acida"),
        ROJO("cielo-rojo"),
        ESPORAS("esporas"),
        POLEN("polinizacion"),
        CENIZA("ceniza");

        final String id;

        Tipo(String id) {
            this.id = id;
        }

        /** "lluvia-acida", "acida", "Polen"... o null si no es ningun tipo. */
        static Tipo deTexto(String s) {
            if (s == null) return null;
            return switch (s.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-')) {
                case "ninguno", "nada", "none" -> NINGUNO;
                case "lluvia-acida", "acida", "ácida", "lluvia-ácida" -> ACIDA;
                case "cielo-rojo", "rojo" -> ROJO;
                case "esporas", "espora" -> ESPORAS;
                case "polinizacion", "polinización", "polen" -> POLEN;
                case "ceniza", "cenizas" -> CENIZA;
                default -> null;
            };
        }

        /** Los que pasan por exposicion(): cordura y/o efecto a cielo abierto. */
        boolean generico() {
            return this == ESPORAS || this == POLEN || this == CENIZA;
        }
    }

    private static LinkedHashMap<String, String> porBiomaDeSerie() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("panacea/sweltering_swamp", "lluvia-acida");
        m.put("panacea/wildflower_bog", "lluvia-acida");
        m.put("panacea/creeper_dominion", "lluvia-acida");
        m.put("panacea/horsetail_tropics", "lluvia-acida");
        m.put("panacea/hungering_jungle", "lluvia-acida");
        m.put("panacea/polypore_plains", "esporas");
        m.put("panacea/ravenous_greenwood", "esporas");
        m.put("panacea/honeybee_biome", "polinizacion");
        m.put("panacea/condemned_taiga", "ceniza");
        m.put("panacea/crimson_organism", "cielo-rojo");
        m.put("*", "ninguno");
        return m;
    }

    /** Si ese tipo esta encendido de serie ('activa' o 'activo' en su seccion). */
    private static boolean encendido(ConfigurationSection s) {
        return s.getBoolean("activa", s.getBoolean("activo", true));
    }

    /** Si las hojas tapan ese clima de serie: la lluvia, la ceniza y el cielo si; esporas y polen flotan bajo la copa. */
    static boolean hojasDeSerie(Tipo t) {
        return t != Tipo.ESPORAS && t != Tipo.POLEN;
    }

    static String nombreDeSerie(Tipo t) {
        return switch (t) {
            case ACIDA -> "Lluvia ácida";
            case ROJO -> "El cielo arde";
            case ESPORAS -> "Esporas";
            case POLEN -> "Polinización";
            case CENIZA -> "Ceniza";
            default -> "";
        };
    }

    static String inicioDeSerie(Tipo t) {
        return switch (t) {
            case ACIDA -> "Comienza la lluvia ácida";
            case ROJO -> "El cielo se tiñe de sangre";
            case ESPORAS -> "Se levantan las esporas";
            case POLEN -> "Llega la polinización";
            case CENIZA -> "Empieza a caer ceniza";
            default -> "";
        };
    }

    static String avisoDeSerie(Tipo t) {
        return switch (t) {
            case ACIDA -> "busca un techo, que quema.";
            case ROJO -> "te vas a quemar.";
            case ESPORAS -> "te nublan la mente; busca un techo.";
            case POLEN -> "te pesa en el paso; busca un techo.";
            default -> "";
        };
    }

    static double corduraDeSerie(Tipo t) {
        return t == Tipo.ESPORAS ? ESPORAS_CORDURA : 0.0;
    }

    static String efectoDeSerie(Tipo t) {
        return t == Tipo.POLEN ? POLEN_EFECTO : "";
    }

    static TextColor color(Tipo t) {
        return switch (t) {
            case ACIDA -> Paleta.ACIDO;
            case ROJO -> Paleta.FUEGO;
            case ESPORAS -> Paleta.ESPORAS;
            case POLEN -> Paleta.POLEN;
            case CENIZA -> Paleta.CENIZA;
            default -> Paleta.TEXTO;
        };
    }

    /**
     * Segundos seguidos dentro de algo (bajo la lluvia acida, bajo el cielo rojo) con memoria corta:
     * salir un momento pausa la cuenta y no la reinicia, para que ponerse a cubierto un segundo
     * cada dos no sirva para saltarse los golpes ni para volver a tener el margen del aviso. Solo
     * tras 'olvido' segundos seguidos fuera se empieza de cero (y vuelve el aviso).
     */
    static final class Episodio {
        int dentro;
        int fuera;

        void paso(boolean ahora, int olvido) {
            if (ahora) {
                fuera = 0;
                dentro++;
                return;
            }
            if (dentro == 0) return;
            if (++fuera >= Math.max(1, olvido)) {
                dentro = 0;
                fuera = 0;
            }
        }

        void olvidar() {
            dentro = 0;
            fuera = 0;
        }
    }

    /** Lo de cada jugador. Lo "puesto" es lo que este modulo le ha cambiado y tiene que devolverle. */
    private static final class Estado {
        final Episodio acida = new Episodio();
        final Episodio rojo = new Episodio();
        /** 1.12: esporas, polinizacion y ceniza (uno a la vez: al cambiar de tipo se olvida). */
        final Episodio expo = new Episodio();
        /** 1.12: el clima que ve ahora; lo pinta el pulso de particulas. */
        Tipo tipo = Tipo.NINGUNO;
        /** Segundos seguidos con este tipo (el ritmo de los sonidos). */
        int segundos;
        boolean tormenta;
        /** A cielo abierto para este tipo (con o sin hojas segun hojas-protegen). */
        boolean expuesto;
        /** Muy bajo tierra: ni particulas ni sonidos. */
        boolean profundo;
        /** Bajo el cielo rojo ahora mismo: Vineta le suma su parte mientras dure. */
        boolean bajoCielo;
        boolean horaPuesta;
        /** El offset que le mandamos (no la hora del ciclo: ver offsetHora). */
        long hora;
        boolean fuegoPuesto;
    }

    /** Una capa de particulas de un tipo (hardcore.clima.<tipo>.particulas), por pulso. */
    record Capa(String particula, int cantidad, double radio, double altura, double espesor, double velocidad,
                int color, int color2, float tam, String bloque, boolean techo) {

        static Capa de(Map<?, ?> m) {
            return new Capa(texto(m, "particula", "").trim().toUpperCase(Locale.ROOT),
                    (int) Math.round(numero(m, "cantidad", 0)), numero(m, "radio", 6), numero(m, "altura", 2),
                    numero(m, "espesor", 1.5), numero(m, "velocidad", 0), colorDe(m.get("color"), 0xFFFFFF),
                    colorDe(m.get("color2"), colorDe(m.get("color"), 0xFFFFFF)), (float) numero(m, "tam", 1.0),
                    texto(m, "bloque", ""), Boolean.parseBoolean(texto(m, "techo", "false")));
        }
    }

    /** Un sonido de un tipo: cada 'cada' segundos, a 'distancia' bloques de el como mucho. */
    record Sonido(String sonido, int cada, float volumen, float tono, double distancia) {

        static Sonido de(Map<?, ?> m) {
            return new Sonido(texto(m, "sonido", ""), Math.max(1, (int) Math.round(numero(m, "cada", 5))),
                    (float) numero(m, "volumen", 0.5), (float) numero(m, "tono", 1.0), numero(m, "distancia", 4));
        }
    }

    /** Lo ya leido de un tipo: sus capas (con los datos de particula hechos) y sus sonidos. */
    private record Receta(List<Capa> capas, List<Particle> particulas, List<Object> datos, List<Sonido> sonidos) {
    }

    private final Hardcore hc;
    private final Map<UUID, Estado> estados = new HashMap<>();
    /** A quien se ha mirado este segundo; al que no (espectador, otro mundo) se le devuelve todo en tick(). */
    private final Set<UUID> vistos = new HashSet<>();
    /** Quien esta recibiendo ahora mismo un golpe del clima, y cual: lo lee ParteDefuncion.onDano. */
    private final Map<UUID, String> enCurso = new HashMap<>();
    /** 1.12: cuando se le anuncio cada tipo por ultima vez (aviso-inicio-espera). */
    private final Map<UUID, Map<Tipo, Long>> anuncios = new HashMap<>();
    /** 1.12: lo leido de la config, por tipo; se rehace si cambia la seccion (reload). */
    private final Map<Tipo, Receta> recetas = new EnumMap<>(Tipo.class);
    private ConfigurationSection recetasDe;
    private ConfigurationSection tablaDe;
    private Map<String, Tipo> tabla = Map.of();
    /** 1.11: el reloj del clima (cuando llueve). */
    private final CicloClima ciclo;
    private final BukkitTask pulso;

    Clima(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        this.ciclo = new CicloClima(hc);
        int cada = Math.max(1, Math.min(20, cfg().getInt("efectos.cada-ticks", EFECTOS_CADA_TICKS)));
        this.pulso = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(),
                () -> hc.seguro("clima", this::pulso), 20L, cada);
        Autotest.registrar("clima", () -> autotest(hc.plugin().getConfig().getDefaults()));
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = hc.cfg().getConfigurationSection("clima");
        return s == null ? new YamlConfiguration() : s;
    }

    private static ConfigurationSection seccion(ConfigurationSection c, String clave) {
        ConfigurationSection s = c == null ? null : c.getConfigurationSection(clave);
        return s == null ? new YamlConfiguration() : s;
    }

    // ------------------------------------------------------------------ ganchos

    /**
     * Una vez por segundo por jugador que cuenta, desde Hardcore.tick y SOLO fuera de la zona
     * spawn. Quien no pase por aqui (ni por enSpawn/soloVista) en un segundo pierde lo suyo en tick().
     */
    void segundo(Player p) {
        pasar(p, false, true);
    }

    /** En la zona spawn: sin clima, salvo el cielo de sangre del bioma rojo (sin fuego ni dano). */
    void enSpawn(Player p) {
        pasar(p, true, false);
    }

    /**
     * 1.11 · Lo que se VE del clima, sin dano ni avisos: quien no cuenta (creativo, vanish) tambien tiene
     * que ver el Calamity de verdad. En el spawn, lo mismo que enSpawn.
     */
    void soloVista(Player p, boolean spawn) {
        pasar(p, spawn, false);
    }

    /**
     * El segundo de un jugador. 'danino' = cuenta y esta fuera del spawn: solo entonces hay dano, fuego,
     * vineta, cordura, efectos y avisos en la barra; lo que se ve (particulas, sonidos, hora, el anuncio
     * de inicio) es para todos.
     */
    private void pasar(Player p, boolean spawn, boolean danino) {
        ConfigurationSection c = cfg();
        if (!c.getBoolean("activo", true)) return;
        UUID u = p.getUniqueId();
        if (p.isDead()) {
            soltar(p);
            return;
        }
        ConfigurationSection r = seccion(c, "cielo-rojo");
        Tipo tipo = ciclo.llueve(p.getWorld()) ? tipo(bioma(p), c) : Tipo.NINGUNO;
        if (spawn && !(tipo == Tipo.ROJO && r.getBoolean("en-spawn", true))) tipo = Tipo.NINGUNO;
        Estado e = estados.get(u);
        if (e == null) {
            if (tipo == Tipo.NINGUNO) return;
            e = new Estado();
            estados.put(u, e);
        }
        vistos.add(u);
        if (tipo != e.tipo) {
            e.segundos = 0;
            e.expo.olvidar();
            if (tipo != Tipo.NINGUNO) anunciar(p, tipo, c);
        }
        e.tipo = tipo;
        e.segundos++;
        ConfigurationSection s = seccion(c, tipo.id);
        if (tipo != Tipo.NINGUNO) {
            e.tormenta = ciclo.tormenta(p.getWorld());
            medirCielo(p, e, s.getBoolean("hojas-protegen", hojasDeSerie(tipo)),
                    c.getInt("efectos.profundidad", PROFUNDIDAD));
        } else {
            e.tormenta = false;
            e.expuesto = false;
            e.profundo = false;
        }
        boolean fuera = !spawn && danino;
        lluviaAcida(p, e, fuera && tipo == Tipo.ACIDA && e.expuesto, seccion(c, "lluvia-acida"));
        cieloRojo(p, e, tipo == Tipo.ROJO, fuera, r);
        exposicion(p, e, tipo, fuera && tipo.generico() && e.expuesto, s);
        sonidos(p, e, c);
        // Sin nada puesto y con las cuentas olvidadas, no hace falta seguir acordandose de el.
        if (e.tipo == Tipo.NINGUNO && e.acida.dentro == 0 && e.rojo.dentro == 0 && e.expo.dentro == 0
                && !e.bajoCielo && !e.horaPuesta && !e.fuegoPuesto && estados.get(u) == e) {
            estados.remove(u);
        }
    }

    /** Una vez por segundo, despues de los jugadores: quien no se ha visto recupera su cielo. */
    void tick() {
        // 1.11: primero el reloj del clima, que decide la fase (la vera el segundo siguiente de cada jugador).
        hc.seguro("ciclo-clima", ciclo::tick);
        if (!estados.isEmpty()) {
            for (UUID u : new ArrayList<>(estados.keySet())) {
                if (vistos.contains(u)) continue;
                Estado e = estados.remove(u);
                Player p = Bukkit.getPlayer(u);
                if (p != null && e != null) soltarCielo(p, e);
            }
        }
        vistos.clear();
    }

    /** 1.11: lo que le toca al bioma en el que esta ahora (para el aviso de CicloClima), con los interruptores. */
    Tipo tipoAhora(Player p) {
        ConfigurationSection c = cfg();
        return c.getBoolean("activo", true) ? tipo(bioma(p), c) : Tipo.NINGUNO;
    }

    /** Lo que Vineta le suma a su intensidad: la del cielo rojo mientras esta debajo, si no 0. */
    double vinetaExtra(Player p) {
        Estado e = p == null ? null : estados.get(p.getUniqueId());
        if (e == null || !e.bajoCielo) return 0.0;
        return Math.max(0.0, seccion(cfg(), "cielo-rojo").getDouble("vinheta", CIELO_VINETA));
    }

    /** El golpe del clima que esta entrando ahora a ese jugador (CAUSA_ACIDA o CAUSA_CIELO), o null. */
    String golpeEnCurso(UUID jugador) {
        return enCurso.get(jugador);
    }

    void parar() {
        HandlerList.unregisterAll(this);
        if (pulso != null) pulso.cancel();
        hc.seguro("ciclo-clima", ciclo::parar);
        for (UUID u : new ArrayList<>(estados.keySet())) {
            Estado e = estados.remove(u);
            Player p = Bukkit.getPlayer(u);
            if (p != null && e != null) soltarCielo(p, e);
        }
        estados.clear();
        vistos.clear();
        enCurso.clear();
        anuncios.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCambiarMundo(PlayerChangedWorldEvent ev) {
        soltar(ev.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMuerte(PlayerDeathEvent ev) {
        soltar(ev.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSalir(PlayerQuitEvent ev) {
        // El fuego visual NO es de la conexion: Paper lo guarda en el jugador (Paper.FireOverride)
        // y los datos se guardan despues de este evento, asi que quien se va ardiendo volveria en
        // llamas para siempre. Se le devuelve todo antes.
        soltar(ev.getPlayer());
        anuncios.remove(ev.getPlayer().getUniqueId());
    }

    /**
     * 1.12 · En los mundos de Calamity el mundo no llueve nunca: ni el ciclo vanilla, ni un /weather, ni
     * otro plugin. Solo con el ciclo de Calamity encendido (con el apagado vuelve el clima de Minecraft).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLluvia(WeatherChangeEvent ev) {
        if (cancelaLluvia(ev.toWeatherState(), hc.esHardcore(ev.getWorld()), ciclo.activo())) ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrueno(ThunderChangeEvent ev) {
        if (cancelaLluvia(ev.toThunderState(), hc.esHardcore(ev.getWorld()), ciclo.activo())) ev.setCancelled(true);
    }

    /** Si hay que cancelar un cambio de clima del mundo: solo hacia lluvia/trueno, en Calamity y con su ciclo. */
    static boolean cancelaLluvia(boolean haciaLluvia, boolean mundoCalamity, boolean cicloActivo) {
        return haciaLluvia && mundoCalamity && cicloActivo;
    }

    // ------------------------------------------------------------ cielo abierto

    /**
     * Si esta a cielo abierto (los ojos por encima del bloque mas alto de su columna) y si esta muy bajo
     * tierra. Dos lecturas del mapa de alturas por jugador y segundo: es lo que hacia isInRain, que ya no
     * sirve (el mundo no llueve).
     */
    private static void medirCielo(Player p, Estado e, boolean hojas, int profundidad) {
        Location l = p.getLocation();
        World w = p.getWorld();
        int ojo = (int) Math.floor(p.getEyeLocation().getY());
        int suelo = w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES);
        int copa = hojas ? w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ(), HeightMap.MOTION_BLOCKING) : suelo;
        e.expuesto = aCieloAbierto(ojo, copa);
        e.profundo = muyProfundo(ojo, suelo, profundidad);
    }

    static boolean aCieloAbierto(int ojo, int alturaColumna) {
        return ojo > alturaColumna;
    }

    static boolean muyProfundo(int ojo, int superficie, int profundidad) {
        return profundidad > 0 && superficie - ojo > profundidad;
    }

    // ------------------------------------------------------------ lluvia acida

    private void lluviaAcida(Player p, Estado e, boolean bajo, ConfigurationSection a) {
        e.acida.paso(bajo, a.getInt("olvido-segundos", ACIDA_OLVIDO));
        if (!bajo) return;
        int s = e.acida.dentro;
        salpicadura(p);
        if (avisaAcida(s)) {
            destello(p, Tipo.ACIDA, a, 3);
        }
        if (!muerdeAcida(s, a.getInt("margen-segundos", ACIDA_MARGEN), a.getInt("cada-segundos", ACIDA_CADA))) return;
        double dano = Math.max(0.0, a.getDouble("dano", ACIDA_DANO));
        if (dano <= 0) return;
        chisporroteo(p);
        herir(p, dano, DamageType.MAGIC, CAUSA_ACIDA);
    }

    /** El aviso sale el primer segundo de cada episodio (tras el olvido vuelve a salir). */
    static boolean avisaAcida(int segundo) {
        return segundo == 1;
    }

    /** Si este segundo quema: el primero tras el margen y despues cada 'cada' segundos. */
    static boolean muerdeAcida(int segundo, int margen, int cada) {
        int primero = 1 + Math.max(0, margen);
        return segundo >= primero && (segundo - primero) % Math.max(1, cada) == 0;
    }

    // ------------------------------------------- esporas, polinizacion, ceniza

    /**
     * 1.12 · A cielo abierto bajo esporas, polen o ceniza: aviso en la barra la primera vez del episodio,
     * cordura-por-segundo menos y el efecto (Lentitud I en el polen) renovado cada segundo.
     */
    private void exposicion(Player p, Estado e, Tipo tipo, boolean bajo, ConfigurationSection s) {
        e.expo.paso(bajo, s.getInt("olvido-segundos", EXPO_OLVIDO));
        if (!bajo) return;
        if (e.expo.dentro == 1) destello(p, tipo, s, 3);
        double cordura = s.getDouble("cordura-por-segundo", corduraDeSerie(tipo));
        if (cordura > 0) hc.cordura().sumar(p, -cordura);
        String efecto = s.getString("efecto.tipo", efectoDeSerie(tipo));
        int nivel = s.getInt("efecto.nivel", tipo == Tipo.POLEN ? POLEN_NIVEL : 1);
        if (efecto == null || efecto.isBlank() || nivel <= 0) return;
        PotionEffectType t = Compat.effect(efecto.trim().toLowerCase(Locale.ROOT));
        if (t == null) return;
        // Ambiental, sin particulas y con icono: vanilla no acorta uno mas largo ni rebaja uno mas fuerte.
        p.addPotionEffect(new PotionEffect(t, EFECTO_TICKS, nivel - 1, true, false, true));
    }

    /** "Esporas: te nublan la mente; busca un techo." en la barra (el nombre en su color). Sin aviso, nada. */
    private void destello(Player p, Tipo tipo, ConfigurationSection s, int segundos) {
        String aviso = s.getString("aviso", avisoDeSerie(tipo));
        if (aviso == null || aviso.isBlank()) return;
        hc.cordura().destello(p, Component.text(s.getString("nombre", nombreDeSerie(tipo)), color(tipo))
                .append(Component.text(": " + aviso, Paleta.TEXTO)), segundos);
    }

    /**
     * 1.12 · "Comienza la lluvia ácida": al empezar a verlo, como subtitulo corto (sin titular), y no mas de
     * una vez cada aviso-inicio-espera segundos por tipo (pasear por la linea entre dos biomas no lo repite).
     * En Bedrock el subtitulo solo no siempre sale: alli va en la barra.
     */
    private void anunciar(Player p, Tipo tipo, ConfigurationSection c) {
        if (!c.getBoolean("aviso-inicio", AVISO_INICIO)) return;
        String texto = seccion(c, tipo.id).getString("inicio", inicioDeSerie(tipo));
        if (texto == null || texto.isBlank()) return;
        long ahora = System.currentTimeMillis();
        Map<Tipo, Long> m = anuncios.computeIfAbsent(p.getUniqueId(), k -> new EnumMap<>(Tipo.class));
        if (!tocaAnuncio(m.get(tipo), ahora, c.getInt("aviso-inicio-espera", AVISO_INICIO_ESPERA) * 1000L)) return;
        m.put(tipo, ahora);
        Component linea = Component.text(texto, color(tipo));
        boolean bedrock = hc.valor("clima", () -> Plataforma.esBedrock(p), false);
        if (bedrock) {
            hc.cordura().destello(p, linea, 3);
            return;
        }
        p.showTitle(Title.title(Component.empty(), linea,
                Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2600), Duration.ofMillis(900))));
    }

    /** Si toca anunciar: nunca anunciado o hace al menos 'espera' ms. */
    static boolean tocaAnuncio(Long ultimo, long ahora, long espera) {
        return ultimo == null || ahora - ultimo >= Math.max(0, espera);
    }

    // --------------------------------------------------------------- cielo rojo

    private void cieloRojo(Player p, Estado e, boolean dentro, boolean danino, ConfigurationSection r) {
        e.rojo.paso(dentro && danino, r.getInt("olvido-segundos", CIELO_OLVIDO));
        if (!dentro) {
            e.bajoCielo = false;
            soltarHora(p, e);
            quitarFuego(p, e);
            return;
        }
        Parca parca = hc.parca();
        boolean deParca = parca != null && hc.valor("parca", () -> parca.cieloSobre(p), false);
        Eclipse eclipse = hc.eclipse();
        boolean deEclipse = eclipse != null && hc.valor("eclipse", eclipse::activo, false);
        ponerHora(p, e, r, deParca, deEclipse);
        if (!danino) {
            // En el spawn o sin contar (creativo, vanish): el cielo se ve, pero sin fuego ni vineta.
            e.bajoCielo = false;
            quitarFuego(p, e);
            return;
        }
        e.bajoCielo = true;

        int s = e.rojo.dentro;
        int cada = r.getInt("arde-cada-segundos", CIELO_CADA);
        int aviso = r.getInt("aviso-segundos", CIELO_AVISO);
        int dura = r.getInt("arde-segundos", CIELO_ARDE);
        if (avisaQuema(s, cada, aviso, dura)) {
            destello(p, Tipo.ROJO, r, Math.max(1, aviso) + 1);
            p.playSound(p.getLocation(), "minecraft:item.firecharge.use", SoundCategory.HOSTILE, 0.7f, 0.6f);
        }
        // Con techo-protege, bajo techo no arde.
        boolean arde = arde(s, cada, aviso, dura) && (!r.getBoolean("techo-protege", false) || e.expuesto);
        if (!arde) {
            quitarFuego(p, e);
            return;
        }
        ponerFuego(p, e);
        llamas(p);
        double dano = Math.max(0.0, r.getDouble("dano-por-segundo", CIELO_DANO));
        if (dano > 0) herir(p, dano, DamageType.ON_FIRE, CAUSA_CIELO);
    }

    /** La hora del cielo de sangre. El de la PARCA manda entero; la hora del Eclipse, tambien. */
    private void ponerHora(Player p, Estado e, ConfigurationSection r, boolean deParca, boolean deEclipse) {
        long hora = hora(r);
        if (hora > 0 && !deParca && !deEclipse) {
            // El offset depende del bloque de 24000 en el que va el reloj, asi que se calcula cada
            // segundo: al pasar al bloque siguiente cambia y se vuelve a mandar (como mucho un
            // segundo de cielo normal cada 20 minutos). getPlayerTime - offset es el reloj (o ya su
            // bloque, si la hora esta fija): baseServidor lo redondea igual en los dos casos.
            long reloj = p.getPlayerTime() - p.getPlayerTimeOffset();
            long offset = offsetHora(reloj, hora, r.getLong("periodo-dia", CIELO_PERIODO));
            if (!e.horaPuesta || !esHora(p.isPlayerTimeRelative(), p.getPlayerTimeOffset(), offset)) {
                // Fija (relative false): el cielo no avanza mientras sigue ahi.
                p.setPlayerTime(offset, false);
                e.horaPuesta = true;
                e.hora = offset;
            }
        } else {
            soltarHora(p, e);
        }
    }

    /** La hora del cielo rojo; 0 (o menos) = no se toca la hora. */
    static long hora(ConfigurationSection r) {
        return Math.max(0L, r.getLong("hora", CIELO_HORA));
    }

    /**
     * El offset que hay que mandarle con la hora fija para que vea 'hora' en un dia de 'periodo'
     * ticks. El servidor le manda base + offset (base = el reloj redondeado hacia abajo a 24000) y
     * el timeline de Panacea lo lee modulo 72000: con offset = hora fijo, solo uno de cada tres
     * bloques de 24000 cae en el cielo de sangre. Con este, (base + offset) % periodo = hora siempre.
     * Un periodo de 0 o menos vale como el de serie.
     */
    static long offsetHora(long reloj, long hora, long periodo) {
        long per = periodo > 0 ? periodo : CIELO_PERIODO;
        return Math.floorMod(hora - baseServidor(reloj), per);
    }

    /** La base que suma el servidor a la hora fija: la misma cuenta que ServerPlayer.getPlayerTime. */
    static long baseServidor(long reloj) {
        return reloj - (reloj % BLOQUE_SERVIDOR);
    }

    /** Si la hora que tiene puesta es la nuestra (fija y con ese offset). */
    static boolean esHora(boolean relativa, long offset, long nuestra) {
        return !relativa && offset == nuestra;
    }

    /** Que el aviso y la quemadura quepan en el periodo: si no, se alarga lo justo. */
    static int periodo(int cada, int aviso, int dura) {
        return Math.max(cada, Math.max(1, dura) + Math.max(0, aviso) + 1);
    }

    /** El aviso: 'aviso' segundos antes de cada quemadura (con 0, sin aviso). */
    static boolean avisaQuema(int segundo, int cada, int aviso, int dura) {
        if (aviso <= 0 || segundo <= 0) return false;
        int per = periodo(cada, aviso, dura);
        return segundo % per == per - aviso;
    }

    /** Si arde este segundo: la primera vez a los 'cada' segundos de llegar, y despues cada 'cada'. */
    static boolean arde(int segundo, int cada, int aviso, int dura) {
        int per = periodo(cada, aviso, dura);
        return segundo >= per && segundo % per < Math.max(1, dura);
    }

    // -------------------------------------------------------------- devolver

    /** Todo lo suyo fuera y el estado olvidado (cambio de mundo, muerte). */
    private void soltar(Player p) {
        Estado e = estados.remove(p.getUniqueId());
        if (e != null) soltarCielo(p, e);
    }

    private static void soltarCielo(Player p, Estado e) {
        e.bajoCielo = false;
        e.tipo = Tipo.NINGUNO;
        soltarHora(p, e);
        quitarFuego(p, e);
    }

    /** Devuelve la hora solo si sigue la nuestra: la noche del Eclipse o de la PARCA no se toca. */
    private static void soltarHora(Player p, Estado e) {
        if (!e.horaPuesta) return;
        e.horaPuesta = false;
        if (p.isOnline() && esHora(p.isPlayerTimeRelative(), p.getPlayerTimeOffset(), e.hora)) p.resetPlayerTime();
    }

    private static void ponerFuego(Player p, Estado e) {
        if (e.fuegoPuesto && p.getVisualFire() == TriState.TRUE) return;
        p.setVisualFire(TriState.TRUE);
        e.fuegoPuesto = true;
    }

    private static void quitarFuego(Player p, Estado e) {
        if (!e.fuegoPuesto) return;
        e.fuegoPuesto = false;
        if (p.getVisualFire() == TriState.TRUE) p.setVisualFire(TriState.NOT_SET);
    }

    // -------------------------------------------------------------------- dano

    /**
     * El golpe del clima: un EntityDamageEvent normal, sin entidad (no es combate). Mientras entra,
     * el jugador esta en enCurso para que el parte de defuncion lo apunte con su nombre.
     */
    private void herir(Player p, double dano, DamageType tipo, String causa) {
        UUID u = p.getUniqueId();
        enCurso.put(u, causa);
        try {
            p.damage(dano, DamageSource.builder(tipo).build());
        } finally {
            enCurso.remove(u);
        }
    }

    /** Si el golpe que mato fue del clima: su nombre (CAUSA_ACIDA o CAUSA_CIELO), o null. */
    static String causaDeMuerte(String ultimoGolpe, DamageCause causa) {
        if (CAUSA_ACIDA.equals(ultimoGolpe) && (causa == null || causa == DamageCause.MAGIC)) return CAUSA_ACIDA;
        if (CAUSA_CIELO.equals(ultimoGolpe) && (causa == null || causa == DamageCause.FIRE_TICK
                || causa == DamageCause.FIRE)) {
            return CAUSA_CIELO;
        }
        return null;
    }

    // ------------------------------------------------------------------ biomas

    private static String bioma(Player p) {
        Location l = p.getLocation();
        return p.getWorld().getBiome(l.getBlockX(), l.getBlockY(), l.getBlockZ()).getKey().getKey();
    }

    /** El clima de ese bioma ("panacea/<id>", con o sin namespace) segun la tabla y los interruptores. */
    Tipo tipo(String bioma, ConfigurationSection c) {
        if (c != tablaDe) {
            tabla = tabla(c);
            tablaDe = c;
        }
        return tipo(bioma, tabla, c);
    }

    /** Lo mismo, con la tabla ya leida (estatico: lo usa el autotest). */
    static Tipo tipo(String bioma, Map<String, Tipo> tabla, ConfigurationSection c) {
        Tipo t = buscar(bioma, tabla);
        if (t == Tipo.NINGUNO) return t;
        return encendido(seccion(c, t.id)) ? t : Tipo.NINGUNO;
    }

    /**
     * hardcore.clima.por-bioma, ya normalizada (Clima.clave; "panacea/*" y "*" tal cual). Si el servidor no
     * la tiene, la del jar; sin ninguna (autotest), la de serie del codigo. Un tipo que no existe se ignora.
     */
    static Map<String, Tipo> tabla(ConfigurationSection c) {
        ConfigurationSection s = c == null ? null : c.getConfigurationSection("por-bioma");
        ConfigurationSection origen = s;
        if (s != null && s.getKeys(false).isEmpty()) origen = s.getDefaultSection();
        Map<String, Tipo> out = new LinkedHashMap<>();
        if (origen != null) {
            for (String k : origen.getKeys(false)) {
                Tipo t = Tipo.deTexto(origen.getString(k));
                if (t != null) out.put(clave(k), t);
            }
        }
        if (out.isEmpty()) {
            for (Map.Entry<String, String> en : POR_BIOMA.entrySet()) out.put(clave(en.getKey()), Tipo.deTexto(en.getValue()));
        }
        return out;
    }

    /** El tipo de un bioma en la tabla: el exacto, si no el comodin "prefijo*" mas largo, si no "*", si no nada. */
    static Tipo buscar(String bioma, Map<String, Tipo> tabla) {
        if (bioma == null || bioma.isBlank() || tabla == null) return Tipo.NINGUNO;
        String b = clave(bioma);
        Tipo exacto = tabla.get(b);
        if (exacto != null) return exacto;
        Tipo mejor = null;
        int largo = -1;
        for (Map.Entry<String, Tipo> en : tabla.entrySet()) {
            String k = en.getKey();
            if (!k.endsWith("*")) continue;
            String prefijo = k.substring(0, k.length() - 1);
            if (b.startsWith(prefijo) && prefijo.length() > largo) {
                mejor = en.getValue();
                largo = prefijo.length();
            }
        }
        return mejor == null ? Tipo.NINGUNO : mejor;
    }

    /** "Bracken:Panacea/Crimson_Organism " -> "panacea/crimson_organism": sin namespace ni mayusculas. */
    static String clave(String bioma) {
        String s = bioma.trim().toLowerCase(Locale.ROOT);
        int i = s.indexOf(':');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    // --------------------------------------------------------------- efectos

    /**
     * 1.12 · El pulso (cada efectos.cada-ticks): las capas de particulas del clima que ve cada jugador, solo
     * para el. Un paquete por capa y jugador; el cliente reparte las 'cantidad' particulas por el radio.
     */
    private void pulso() {
        if (estados.isEmpty()) return;
        ConfigurationSection c = cfg();
        if (!c.getBoolean("activo", true)) return;
        double densidad = Math.max(0.0, c.getDouble("efectos.densidad", DENSIDAD));
        double tormenta = Math.max(0.0, c.getDouble("tormenta.densidad", TORMENTA_DENSIDAD));
        for (Map.Entry<UUID, Estado> en : estados.entrySet()) {
            Estado e = en.getValue();
            if (e.tipo == Tipo.NINGUNO || e.profundo) continue;
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || p.isDead()) continue;
            Receta r = receta(e.tipo, c);
            double f = densidad * (e.tormenta ? tormenta : 1.0);
            Location base = p.getLocation();
            for (int i = 0; i < r.capas().size(); i++) {
                Capa k = r.capas().get(i);
                if (!k.techo() && !e.expuesto) continue;
                int n = cantidad(k.cantidad(), f);
                if (n <= 0) continue;
                particula(p, r.particulas().get(i), base.clone().add(0, k.altura(), 0), n, k.radio(), k.espesor(),
                        k.radio(), k.velocidad(), r.datos().get(i));
            }
        }
    }

    /** Las particulas de una capa con la densidad aplicada (redondeo normal; nunca negativo). */
    static int cantidad(int base, double factor) {
        return (int) Math.max(0, Math.round(base * factor));
    }

    /** Los sonidos del tipo (y los de la tormenta), a su ritmo; mas bajos a cubierto, ninguno muy bajo tierra. */
    private void sonidos(Player p, Estado e, ConfigurationSection c) {
        if (e.tipo == Tipo.NINGUNO || e.profundo) return;
        float factor = e.expuesto ? 1f : (float) Math.max(0, c.getDouble("efectos.volumen-a-cubierto", VOLUMEN_CUBIERTO));
        if (factor <= 0) return;
        for (Sonido s : receta(e.tipo, c).sonidos()) sonar(p, e, s, factor);
        if (e.tormenta) for (Sonido s : sonidosTormenta(c)) sonar(p, e, s, factor);
    }

    private List<Sonido> tormentaSonidos;
    private ConfigurationSection tormentaDe;

    private List<Sonido> sonidosTormenta(ConfigurationSection c) {
        if (c != tormentaDe || tormentaSonidos == null) {
            tormentaSonidos = sonidos(seccion(c, "tormenta").getMapList("sonidos"));
            tormentaDe = c;
        }
        return tormentaSonidos;
    }

    private static void sonar(Player p, Estado e, Sonido s, float factor) {
        if (s.sonido().isBlank() || e.segundos % s.cada() != 0) return;
        ThreadLocalRandom azar = ThreadLocalRandom.current();
        double ang = azar.nextDouble(Math.PI * 2);
        double d = s.distancia() <= 0 ? 0 : azar.nextDouble(s.distancia() * 0.4, s.distancia());
        Location l = p.getLocation().add(Math.cos(ang) * d, 1.5, Math.sin(ang) * d);
        float tono = (float) Math.max(0.5, Math.min(2.0, s.tono() * (0.9 + azar.nextDouble() * 0.2)));
        try {
            p.playSound(l, s.sonido(), SoundCategory.WEATHER, s.volumen() * factor, tono);
        } catch (Throwable ignorado) {
            // Un sonido con mal nombre no puede cortar el segundo.
        }
    }

    /** Lo leido de la config para un tipo (capas validas con sus datos, y sonidos). */
    private Receta receta(Tipo tipo, ConfigurationSection c) {
        if (c != recetasDe) {
            recetas.clear();
            recetasDe = c;
        }
        return recetas.computeIfAbsent(tipo, t -> {
            ConfigurationSection s = seccion(c, t.id);
            List<Capa> capas = new ArrayList<>();
            List<Particle> parts = new ArrayList<>();
            List<Object> datos = new ArrayList<>();
            for (Capa k : capas(s.getMapList("particulas"))) {
                Particle pt = Compat.particleByName(k.particula());
                if (pt == null || PARTICULAS_DE_LLUVIA.contains(k.particula())) continue;
                Object d = datos(pt, k);
                if (d == SIN_DATOS) continue;
                capas.add(k);
                parts.add(pt);
                datos.add(d);
            }
            return new Receta(capas, parts, datos, sonidos(s.getMapList("sonidos")));
        });
    }

    static List<Capa> capas(List<Map<?, ?>> lista) {
        List<Capa> out = new ArrayList<>();
        if (lista == null) return out;
        for (Map<?, ?> m : lista) if (m != null) out.add(Capa.de(m));
        return out;
    }

    static List<Sonido> sonidos(List<Map<?, ?>> lista) {
        List<Sonido> out = new ArrayList<>();
        if (lista == null) return out;
        for (Map<?, ?> m : lista) if (m != null) out.add(Sonido.de(m));
        return out;
    }

    /** Marca de "esta capa no se puede pintar" (datos que no sabemos hacer). */
    private static final Object SIN_DATOS = new Object();

    /** Los datos que pide esa particula: color, bloque u objeto. Null si no pide nada. */
    private static Object datos(Particle t, Capa k) {
        Class<?> cl = t.getDataType();
        if (cl == Void.class) return null;
        if (cl == Particle.DustOptions.class) return new Particle.DustOptions(Color.fromRGB(k.color()), Math.max(0.1f, k.tam()));
        if (cl == Particle.DustTransition.class) {
            return new Particle.DustTransition(Color.fromRGB(k.color()), Color.fromRGB(k.color2()), Math.max(0.1f, k.tam()));
        }
        if (cl == Color.class) return Color.fromRGB(k.color());
        if (cl == BlockData.class) {
            Material m = Material.matchMaterial(k.bloque());
            return m != null && m.isBlock() ? m.createBlockData() : SIN_DATOS;
        }
        if (cl == ItemStack.class) {
            Material m = Material.matchMaterial(k.bloque());
            return m != null && m.isItem() ? new ItemStack(m) : SIN_DATOS;
        }
        return SIN_DATOS;
    }

    /** El acido sobre el: lo que se ve cuando le esta cayendo encima. */
    private void salpicadura(Player p) {
        particula(p, Compat.DUST, p.getLocation().add(0, 1.2, 0), 5, 0.5, 0.7, 0.5, 0,
                Compat.dust(Paleta.ACIDO.value(), 0.9f));
    }

    /** Un chisporroteo suave cuando la lluvia le quema. */
    private void chisporroteo(Player p) {
        particula(p, Compat.SMOKE, p.getLocation().add(0, 1.0, 0), 4, 0.3, 0.5, 0.3, 0, null);
        p.playSound(p.getLocation(), "minecraft:block.fire.extinguish", SoundCategory.PLAYERS, 0.25f, 1.8f);
    }

    private void llamas(Player p) {
        particula(p, Compat.FLAME, p.getLocation().add(0, 1.0, 0), 5, 0.3, 0.6, 0.3, 0, null);
    }

    /** Una particula solo para ese jugador. Si cambia de nombre o de datos, no sale y ya. */
    private static void particula(Player p, Particle tipo, Location l, int n, double ox, double oy, double oz,
                                  double velocidad, Object datos) {
        if (tipo == null || l == null) return;
        try {
            Class<?> clase = tipo.getDataType();
            if (clase == Void.class) p.spawnParticle(tipo, l, n, ox, oy, oz, velocidad);
            else if (datos != null && clase.isInstance(datos)) p.spawnParticle(tipo, l, n, ox, oy, oz, velocidad, datos);
        } catch (Throwable ignorado) {
            // Una particula que falle no puede cortar el golpe ni el cielo.
        }
    }

    // ------------------------------------------------------- lectura de mapas

    private static String texto(Map<?, ?> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : String.valueOf(v);
    }

    private static double numero(Map<?, ?> m, String k, double def) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return def;
        try {
            return Double.parseDouble(String.valueOf(v).trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** "#8FD14F", "8FD14F", 0x8FD14F o 9425231 -> el entero; lo que no se entienda, 'def'. */
    static int colorDe(Object v, int def) {
        if (v instanceof Number n) return n.intValue() & 0xFFFFFF;
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        if (s.startsWith("#")) s = s.substring(1);
        else if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        try {
            return Integer.parseInt(s, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ autotest

    /**
     * La logica pura (tabla de biomas, ritmos, horas, cielo abierto, vineta y parte de defuncion), que el
     * mundo no llueva nunca y que los valores de serie del codigo sean los del config.yml del jar (con sus
     * particulas: que existan, que ninguna sea lluvia de Minecraft y que quepan en el presupuesto). 'jar' es
     * la config por defecto del jar (la raiz); con null se salta esa parte. No toca a ningun jugador.
     */
    static List<String> autotest(ConfigurationSection jar) {
        Autotest.Hoja h = new Autotest.Hoja();
        YamlConfiguration vacia = new YamlConfiguration();
        Map<String, Tipo> serie = tabla(vacia);

        // Tabla de serie: lo aprobado por Dosa.
        for (String b : List.of("sweltering_swamp", "wildflower_bog", "creeper_dominion", "horsetail_tropics",
                "hungering_jungle")) {
            h.igual(b + " es lluvia acida", Tipo.ACIDA, tipo("panacea/" + b, serie, vacia));
        }
        h.igual("polypore_plains: esporas", Tipo.ESPORAS, tipo("panacea/polypore_plains", serie, vacia));
        h.igual("ravenous_greenwood: esporas", Tipo.ESPORAS, tipo("panacea/ravenous_greenwood", serie, vacia));
        h.igual("honeybee_biome: polinizacion", Tipo.POLEN, tipo("panacea/honeybee_biome", serie, vacia));
        h.igual("condemned_taiga: ceniza", Tipo.CENIZA, tipo("panacea/condemned_taiga", serie, vacia));
        h.igual("crimson_organism: cielo rojo", Tipo.ROJO, tipo("panacea/crimson_organism", serie, vacia));
        h.igual("con namespace tambien", Tipo.ROJO, tipo("bracken:panacea/crimson_organism", serie, vacia));
        h.igual("bamboo_valley: nada", Tipo.NINGUNO, tipo("panacea/bamboo_valley", serie, vacia));
        h.igual("un clima de /lbiomes: nada", Tipo.NINGUNO, tipo("lethal:crimson", serie, vacia));
        h.igual("sin bioma, nada", Tipo.NINGUNO, tipo(null, serie, vacia));
        h.igual("la tabla de serie tiene sus 11 entradas", 11, serie.size());

        // Tipos por texto.
        h.igual("texto: acida", Tipo.ACIDA, Tipo.deTexto(" Acida "));
        h.igual("texto: polen", Tipo.POLEN, Tipo.deTexto("polen"));
        h.igual("texto: polinización", Tipo.POLEN, Tipo.deTexto("Polinización"));
        h.igual("texto: cielo_rojo", Tipo.ROJO, Tipo.deTexto("cielo_rojo"));
        h.igual("texto: raro", null, Tipo.deTexto("granizo"));
        for (Tipo t : Tipo.values()) h.igual("texto: el id de " + t + " vuelve a " + t, t, Tipo.deTexto(t.id));

        // Interruptores por tipo.
        YamlConfiguration apagada = new YamlConfiguration();
        apagada.set("lluvia-acida.activa", false);
        apagada.set("cielo-rojo.activo", false);
        apagada.set("polinizacion.activa", false);
        h.igual("lluvia acida apagada", Tipo.NINGUNO, tipo("panacea/creeper_dominion", serie, apagada));
        h.igual("cielo rojo apagado", Tipo.NINGUNO, tipo("panacea/crimson_organism", serie, apagada));
        h.igual("polinizacion apagada", Tipo.NINGUNO, tipo("panacea/honeybee_biome", serie, apagada));
        h.igual("las esporas siguen", Tipo.ESPORAS, tipo("panacea/polypore_plains", serie, apagada));

        // La tabla de la config manda, con comodines: el exacto gana al prefijo y el prefijo mas largo a "*".
        YamlConfiguration otra = new YamlConfiguration();
        otra.set("por-bioma.Bracken:Panacea/Bamboo_Valley", "cielo-rojo");
        otra.set("por-bioma.panacea/*", "esporas");
        otra.set("por-bioma.panacea/honey*", "polen");
        otra.set("por-bioma.*", "ceniza");
        otra.set("por-bioma.panacea/quicksand_springs", "granizo");
        Map<String, Tipo> t2 = tabla(otra);
        h.igual("exacto (namespace y mayusculas dan igual)", Tipo.ROJO, tipo("panacea/bamboo_valley", t2, otra));
        h.igual("prefijo mas largo", Tipo.POLEN, tipo("panacea/honeybee_biome", t2, otra));
        h.igual("prefijo de Panacea", Tipo.ESPORAS, tipo("panacea/crimson_organism", t2, otra));
        h.igual("un tipo que no existe se ignora (cae en el prefijo)", Tipo.ESPORAS,
                tipo("panacea/quicksand_springs", t2, otra));
        h.igual("el resto, el *", Tipo.CENIZA, tipo("minecraft:plains", t2, otra));
        YamlConfiguration sinComodin = new YamlConfiguration();
        sinComodin.set("por-bioma.panacea/honeybee_biome", "lluvia-acida");
        Map<String, Tipo> t3 = tabla(sinComodin);
        h.igual("tabla propia: el colmenar pasa a acida", Tipo.ACIDA, tipo("panacea/honeybee_biome", t3, sinComodin));
        h.igual("tabla propia sin *: lo demas, nada", Tipo.NINGUNO, tipo("panacea/crimson_organism", t3, sinComodin));

        // El mundo no llueve nunca: los cambios hacia lluvia se cancelan en Calamity con el ciclo encendido.
        h.ok("lluvia en Calamity: se cancela", cancelaLluvia(true, true, true));
        h.ok("despejar en Calamity: se deja", !cancelaLluvia(false, true, true));
        h.ok("lluvia fuera de Calamity: se deja", !cancelaLluvia(true, false, true));
        h.ok("con el ciclo apagado manda Minecraft", !cancelaLluvia(true, true, false));
        for (String fase : List.of(CicloClima.DESPEJADO, CicloClima.LLUVIA, CicloClima.TORMENTA)) {
            h.ok("fase " + fase + ": el mundo sigue sin lluvia ni trueno", !CicloClima.lluviaDelMundo(fase)
                    && !CicloClima.truenoDelMundo(fase));
        }
        h.ok("lluvia y tormenta son clima de Calamity", CicloClima.llueveEnFase(CicloClima.LLUVIA)
                && CicloClima.llueveEnFase(CicloClima.TORMENTA) && !CicloClima.llueveEnFase(CicloClima.DESPEJADO));

        // Cielo abierto y profundidad.
        h.ok("ojos por encima de la columna: a cielo abierto", aCieloAbierto(71, 70));
        h.ok("tejado justo encima: a cubierto", !aCieloAbierto(70, 71));
        h.ok("ojos a la altura del tejado: a cubierto", !aCieloAbierto(70, 70));
        h.ok("a 5 bajo la superficie: no es profundo", !muyProfundo(60, 65, PROFUNDIDAD));
        h.ok("a 20 bajo la superficie: profundo", muyProfundo(40, 60, PROFUNDIDAD));
        h.ok("profundidad 0: nunca profundo", !muyProfundo(-40, 100, 0));
        h.ok("hojas: tapan la lluvia y la ceniza", hojasDeSerie(Tipo.ACIDA) && hojasDeSerie(Tipo.CENIZA)
                && hojasDeSerie(Tipo.ROJO));
        h.ok("hojas: no tapan esporas ni polen", !hojasDeSerie(Tipo.ESPORAS) && !hojasDeSerie(Tipo.POLEN));

        // Anuncio de inicio: una vez y no otra hasta la espera.
        h.ok("nunca anunciado: toca", tocaAnuncio(null, 1_000, 90_000));
        h.ok("hace 10 s: no", !tocaAnuncio(1_000L, 11_000, 90_000));
        h.ok("hace 90 s: si", tocaAnuncio(1_000L, 91_000, 90_000));
        for (Tipo t : Tipo.values()) {
            if (t == Tipo.NINGUNO) continue;
            h.ok("inicio de " + t.id + " sin emojis ni mayusculas sostenidas", textoLimpio(inicioDeSerie(t)));
            h.ok("color de " + t.id + " distinto del texto", !Paleta.TEXTO.equals(color(t)));
        }

        // Efectos de serie: esporas quitan cordura; el polen pone Lentitud I; la ceniza, de serie solo se ve.
        h.ok("esporas: cordura", corduraDeSerie(Tipo.ESPORAS) > 0 && efectoDeSerie(Tipo.ESPORAS).isEmpty());
        h.ok("polen: Lentitud", "slowness".equals(efectoDeSerie(Tipo.POLEN)) && POLEN_NIVEL == 1
                && corduraDeSerie(Tipo.POLEN) == 0);
        h.ok("ceniza: nada de serie", corduraDeSerie(Tipo.CENIZA) == 0 && efectoDeSerie(Tipo.CENIZA).isEmpty());
        h.igual("densidad 1: igual", 14, cantidad(14, 1.0));
        h.igual("tormenta 1.5", 21, cantidad(14, 1.5));
        h.igual("densidad 0: ninguna", 0, cantidad(14, 0));
        h.igual("densidad negativa: ninguna", 0, cantidad(14, -2));
        h.igual("color #8FD14F", 0x8FD14F, colorDe("#8FD14F", 0));
        h.igual("color sin #", 0xDA5955, colorDe("da5955", 0));
        h.igual("color raro: el de defecto", 7, colorDe("verde", 7));
        Capa capa = Capa.de(Map.of("particula", "falling_dust", "bloque", "LIME_CONCRETE_POWDER", "cantidad", 14,
                "radio", 8, "altura", 7, "techo", true));
        h.igual("capa: nombre en mayusculas", "FALLING_DUST", capa.particula());
        h.ok("capa: techo y cantidad", capa.techo() && capa.cantidad() == 14 && capa.radio() == 8);
        Sonido son = Sonido.de(Map.of("sonido", "minecraft:entity.bee.loop", "cada", 0));
        h.igual("sonido: cada 0 se trata como 1", 1, son.cada());

        // Lluvia acida de serie: aviso el primer segundo, primer golpe 2 s despues y luego cada 2 s.
        h.ok("segundo 1: aviso", avisaAcida(1));
        h.ok("segundo 2: sin aviso", !avisaAcida(2));
        h.igual("golpes en los 9 primeros segundos", List.of(3, 5, 7, 9), golpes(9, ACIDA_MARGEN, ACIDA_CADA));
        h.igual("sin margen, golpea desde el primero", List.of(1, 3, 5), golpes(5, 0, 2));
        h.igual("margen negativo como 0", List.of(1, 2, 3), golpes(3, -3, 0));
        h.igual("cada 3 con margen 1", List.of(2, 5, 8), golpes(8, 1, 3));
        h.cerca("de serie: medio corazon", 1.0, ACIDA_DANO, 1e-9);

        // Episodio: a cubierto un rato corto se pausa; tras el olvido, de cero y con aviso otra vez.
        Episodio ep = new Episodio();
        for (int i = 0; i < 3; i++) ep.paso(true, ACIDA_OLVIDO);
        h.igual("3 s bajo la lluvia", 3, ep.dentro);
        for (int i = 0; i < 5; i++) ep.paso(false, ACIDA_OLVIDO);
        h.igual("5 s a cubierto: la cuenta sigue", 3, ep.dentro);
        ep.paso(true, ACIDA_OLVIDO);
        h.igual("vuelve: sigue contando", 4, ep.dentro);
        h.ok("y sin aviso nuevo", !avisaAcida(ep.dentro));
        for (int i = 0; i < ACIDA_OLVIDO; i++) ep.paso(false, ACIDA_OLVIDO);
        h.igual("15 s a cubierto: de cero", 0, ep.dentro);
        ep.paso(true, ACIDA_OLVIDO);
        h.ok("al volver, aviso otra vez", avisaAcida(ep.dentro));
        ep.olvidar();
        h.igual("olvidar: de cero", 0, ep.dentro);
        Episodio corto = new Episodio();
        corto.paso(true, 0);
        corto.paso(false, 0);
        h.igual("olvido 0 se trata como 1", 0, corto.dentro);
        Episodio quieto = new Episodio();
        quieto.paso(false, 5);
        h.igual("fuera sin haber entrado no cuenta nada", 0, quieto.fuera);

        // Cielo rojo de serie: aviso a los 18 s, arde de 20 a 23, y otra vez a los 38 y de 40 a 43.
        List<Integer> avisos = new ArrayList<>(), ardiendo = new ArrayList<>();
        for (int s = 1; s <= 45; s++) {
            if (avisaQuema(s, CIELO_CADA, CIELO_AVISO, CIELO_ARDE)) avisos.add(s);
            if (arde(s, CIELO_CADA, CIELO_AVISO, CIELO_ARDE)) ardiendo.add(s);
        }
        h.igual("avisos de serie", List.of(18, 38), avisos);
        h.igual("quemaduras de serie", List.of(20, 21, 22, 23, 40, 41, 42, 43), ardiendo);
        h.igual("un periodo demasiado corto se alarga", 7, periodo(3, 2, 4));
        h.ok("y aun asi avisa antes de arder", avisaQuema(5, 3, 2, 4) && !arde(6, 3, 2, 4) && arde(7, 3, 2, 4));
        boolean algunAviso = false;
        for (int s = 1; s <= 60; s++) algunAviso |= avisaQuema(s, 20, 0, 4);
        h.ok("con aviso 0 no avisa", !algunAviso);
        h.ok("arde-segundos 0 se trata como 1", arde(20, 20, 2, 0) && !arde(21, 20, 2, 0));
        h.ok("nada al llegar", !arde(1, CIELO_CADA, CIELO_AVISO, CIELO_ARDE));

        // La hora: la del cielo de sangre de Panacea; 0 la apaga.
        h.igual("hora de serie", CIELO_HORA, hora(vacia));
        h.ok("la hora de serie cae en el cielo de sangre (63500-65000)", CIELO_HORA >= 63_500 && CIELO_HORA <= 65_000);
        YamlConfiguration sinHora = new YamlConfiguration();
        sinHora.set("hora", 0);
        h.igual("hora 0: no se toca", 0L, hora(sinHora));
        sinHora.set("hora", -5);
        h.igual("hora negativa: tampoco", 0L, hora(sinHora));
        h.ok("fija y con nuestro valor: nuestra", esHora(false, CIELO_HORA, CIELO_HORA));
        h.ok("relativa (reseteada): no", !esHora(true, CIELO_HORA, CIELO_HORA));
        h.ok("la noche del Eclipse: no", !esHora(false, 18_000L, CIELO_HORA));

        long[] relojes = {0L, 5_000L, 23_999L, 24_000L, 30_123L, 47_999L, 48_000L, 60_000L, 71_999L, 72_000L,
                96_500L, 1_000_000L, 123_456_789L};
        for (long reloj : relojes) {
            long off = offsetHora(reloj, CIELO_HORA, CIELO_PERIODO);
            h.igual("reloj " + reloj + ": ve el cielo de sangre", CIELO_HORA,
                    Math.floorMod(vistaServidor(reloj, off), CIELO_PERIODO));
            h.igual("reloj " + reloj + ": estable con la hora fija", off,
                    offsetHora(vistaServidor(reloj, off) - off, CIELO_HORA, CIELO_PERIODO));
            h.ok("reloj " + reloj + ": nunca choca con la noche del Eclipse", off != 18_000L);
            h.ok("reloj " + reloj + ": offset dentro del dia", off >= 0 && off < CIELO_PERIODO);
        }
        h.igual("k = 0: offset 64250", 64_250L, offsetHora(10_000L, CIELO_HORA, CIELO_PERIODO));
        h.igual("k = 1: offset 40250", 40_250L, offsetHora(30_000L, CIELO_HORA, CIELO_PERIODO));
        h.igual("k = 2: offset 16250", 16_250L, offsetHora(50_000L, CIELO_HORA, CIELO_PERIODO));
        h.ok("al pasar de bloque cambia el offset (y se repone)",
                offsetHora(23_999L, CIELO_HORA, CIELO_PERIODO) != offsetHora(24_000L, CIELO_HORA, CIELO_PERIODO));
        h.igual("periodo 0: vale el de serie", offsetHora(30_000L, CIELO_HORA, CIELO_PERIODO),
                offsetHora(30_000L, CIELO_HORA, 0));
        h.igual("dia de 24000: la hora dentro del dia de siempre", 6_000L, offsetHora(50_000L, 6_000L, 24_000L));

        // Vineta: el cielo rojo suma su parte, sola o encima de la de cordura, con el tope de siempre.
        h.cerca("sin vineta de cordura: la del cielo rojo sola", CIELO_VINETA, Vineta.conExtra(0, CIELO_VINETA), 1e-9);
        h.cerca("encima de la de cordura, con tope", Vineta.INTENSIDAD_MAXIMA, Vineta.conExtra(0.85, CIELO_VINETA), 1e-9);
        h.cerca("sumada a un tramo medio", 0.75, Vineta.conExtra(0.35, CIELO_VINETA), 1e-9);
        h.cerca("un extra negativo no resta", 0.35, Vineta.conExtra(0.35, -1), 1e-9);
        h.ok("con la de serie hay borde", Vineta.aviso(10_000, Vineta.conExtra(0, CIELO_VINETA)) > 0);
        h.igual("sin nada, sin borde", 0, Vineta.aviso(10_000, Vineta.conExtra(0, 0)));

        // Parte de defuncion: la lluvia o el cielo, solo si el golpe que mato fue suyo.
        h.igual("muere por la lluvia acida", CAUSA_ACIDA, causaDeMuerte(CAUSA_ACIDA, DamageCause.MAGIC));
        h.igual("muere por el cielo rojo", CAUSA_CIELO, causaDeMuerte(CAUSA_CIELO, DamageCause.FIRE_TICK));
        h.igual("la lluvia y despues una caida no es la lluvia", null, causaDeMuerte(CAUSA_ACIDA, DamageCause.FALL));
        h.igual("un golpe normal no es clima", null, causaDeMuerte("golpe", DamageCause.MAGIC));
        h.igual("P-D10: lluvia acida", "P-D10", idPorQue(false, 30, DamageCause.MAGIC, CAUSA_ACIDA));
        h.igual("P-D11: cielo rojo", "P-D11", idPorQue(false, 30, DamageCause.FIRE_TICK, CAUSA_CIELO));
        h.igual("la lluvia antes que la cordura a 0", "P-D10", idPorQue(false, 0, DamageCause.MAGIC, CAUSA_ACIDA));
        h.igual("la Parca sigue ganando", "P-D01", idPorQue(true, 0, DamageCause.MAGIC, CAUSA_ACIDA));
        h.igual("sin clima, lo de siempre", "P-D05", idPorQue(false, 30, DamageCause.FALL, null));
        ParteDefuncion.PorQue texto = ParteDefuncion.porQue(new ParteDefuncion.Causas(false, 30, null, null,
                DamageCause.MAGIC, 0, 12, false, CAUSA_ACIDA));
        h.ok("el texto de la lluvia habla de techo", texto != null && texto.texto().contains("techo"));

        // Los de serie del codigo contra los del config.yml del jar.
        ConfigurationSection c = jar == null ? null : jar.getConfigurationSection("hardcore.clima");
        if (jar == null) {
            h.ok("sin la config del jar a mano: no se compara", true);
        } else if (c == null) {
            h.ok("el config.yml del jar trae hardcore.clima", false);
        } else {
            autotestJar(h, c);
        }
        return h.lineas();
    }

    /** hardcore.clima del jar contra el codigo, y sus particulas y sonidos. */
    private static void autotestJar(Autotest.Hoja h, ConfigurationSection c) {
        h.igual("jar: activo", true, c.getBoolean("activo", false));
        Map<String, String> jarTabla = new LinkedHashMap<>();
        ConfigurationSection pb = c.getConfigurationSection("por-bioma");
        if (pb != null) for (String k : pb.getKeys(false)) jarTabla.put(k, pb.getString(k));
        h.igual("jar: por-bioma", POR_BIOMA, jarTabla);
        h.igual("jar: aviso-inicio", AVISO_INICIO, c.getBoolean("aviso-inicio", !AVISO_INICIO));
        h.igual("jar: aviso-inicio-espera", AVISO_INICIO_ESPERA, c.getInt("aviso-inicio-espera", -1));
        h.igual("jar: efectos.cada-ticks", EFECTOS_CADA_TICKS, c.getInt("efectos.cada-ticks", -1));
        h.cerca("jar: efectos.densidad", DENSIDAD, c.getDouble("efectos.densidad", -1), 1e-9);
        h.igual("jar: efectos.profundidad", PROFUNDIDAD, c.getInt("efectos.profundidad", -1));
        h.cerca("jar: efectos.volumen-a-cubierto", VOLUMEN_CUBIERTO, c.getDouble("efectos.volumen-a-cubierto", -1), 1e-9);
        h.cerca("jar: tormenta.densidad", TORMENTA_DENSIDAD, c.getDouble("tormenta.densidad", -1), 1e-9);
        h.ok("jar: sin las listas viejas de biomas", !c.isSet("lluvia-acida.biomas") && !c.isSet("cielo-rojo.biomas"));
        h.ok("jar: sin ocultar-lluvia (ya no hay lluvia que ocultar)",
                !c.isSet("lluvia-acida.ocultar-lluvia") && !c.isSet("cielo-rojo.ocultar-lluvia"));

        h.igual("jar: lluvia-acida.activa", true, c.getBoolean("lluvia-acida.activa", false));
        h.cerca("jar: lluvia-acida.dano", ACIDA_DANO, c.getDouble("lluvia-acida.dano", -1), 1e-9);
        h.igual("jar: lluvia-acida.cada-segundos", ACIDA_CADA, c.getInt("lluvia-acida.cada-segundos", -1));
        h.igual("jar: lluvia-acida.margen-segundos", ACIDA_MARGEN, c.getInt("lluvia-acida.margen-segundos", -1));
        h.igual("jar: lluvia-acida.olvido-segundos", ACIDA_OLVIDO, c.getInt("lluvia-acida.olvido-segundos", -1));
        h.igual("jar: cielo-rojo.activo", true, c.getBoolean("cielo-rojo.activo", false));
        h.igual("jar: cielo-rojo.hora", CIELO_HORA, c.getLong("cielo-rojo.hora", -1));
        h.igual("jar: cielo-rojo.periodo-dia", CIELO_PERIODO, c.getLong("cielo-rojo.periodo-dia", -1));
        h.cerca("jar: cielo-rojo.vinheta", CIELO_VINETA, c.getDouble("cielo-rojo.vinheta", -1), 1e-9);
        h.igual("jar: cielo-rojo.arde-cada-segundos", CIELO_CADA, c.getInt("cielo-rojo.arde-cada-segundos", -1));
        h.igual("jar: cielo-rojo.arde-segundos", CIELO_ARDE, c.getInt("cielo-rojo.arde-segundos", -1));
        h.igual("jar: cielo-rojo.aviso-segundos", CIELO_AVISO, c.getInt("cielo-rojo.aviso-segundos", -1));
        h.cerca("jar: cielo-rojo.dano-por-segundo", CIELO_DANO, c.getDouble("cielo-rojo.dano-por-segundo", -1), 1e-9);
        h.igual("jar: cielo-rojo.techo-protege", false, c.getBoolean("cielo-rojo.techo-protege", true));
        h.igual("jar: cielo-rojo.olvido-segundos", CIELO_OLVIDO, c.getInt("cielo-rojo.olvido-segundos", -1));
        h.cerca("jar: esporas.cordura-por-segundo", ESPORAS_CORDURA, c.getDouble("esporas.cordura-por-segundo", -1), 1e-9);
        h.igual("jar: polinizacion.efecto.tipo", POLEN_EFECTO, c.getString("polinizacion.efecto.tipo"));
        h.igual("jar: polinizacion.efecto.nivel", POLEN_NIVEL, c.getInt("polinizacion.efecto.nivel", -1));
        h.igual("jar: ceniza.efecto.tipo", "", c.getString("ceniza.efecto.tipo", "?"));

        int cada = Math.max(1, c.getInt("efectos.cada-ticks", EFECTOS_CADA_TICKS));
        double pulsos = 20.0 / cada;
        for (Tipo t : Tipo.values()) {
            if (t == Tipo.NINGUNO) continue;
            ConfigurationSection s = seccion(c, t.id);
            h.igual("jar: " + t.id + ".nombre", nombreDeSerie(t), s.getString("nombre"));
            h.igual("jar: " + t.id + ".inicio", inicioDeSerie(t), s.getString("inicio"));
            h.igual("jar: " + t.id + ".aviso", avisoDeSerie(t), s.getString("aviso", ""));
            h.igual("jar: " + t.id + ".hojas-protegen", hojasDeSerie(t), s.getBoolean("hojas-protegen", !hojasDeSerie(t)));
            List<Capa> capas = capas(s.getMapList("particulas"));
            h.ok("jar: " + t.id + " tiene particulas", !capas.isEmpty());
            h.ok("jar: " + t.id + " tiene al menos una capa que se ve a cubierto o una a cielo abierto",
                    capas.stream().anyMatch(k -> k.cantidad() > 0));
            double porSegundo = 0;
            boolean conDust = false;
            for (Capa k : capas) {
                h.ok("jar: " + t.id + ": " + k.particula() + " no es lluvia de Minecraft",
                        !PARTICULAS_DE_LLUVIA.contains(k.particula()));
                h.ok("jar: " + t.id + ": " + k.particula() + " existe", existe(k.particula()));
                h.ok("jar: " + t.id + ": " + k.particula() + " radio entre 1 y 16", k.radio() >= 1 && k.radio() <= 16);
                porSegundo += k.cantidad() * pulsos;
                conDust |= k.particula().equals("DUST");
            }
            h.ok("jar: " + t.id + " lleva DUST (la que Bedrock pinta seguro)", conDust);
            double maximo = porSegundo * Math.max(1.0, c.getDouble("tormenta.densidad", TORMENTA_DENSIDAD));
            h.ok("jar: " + t.id + ": " + Math.round(maximo) + " particulas/s por jugador con tormenta (tope "
                    + PRESUPUESTO_SEGUNDO + ")", maximo <= PRESUPUESTO_SEGUNDO);
            List<Sonido> sons = sonidos(s.getMapList("sonidos"));
            h.ok("jar: " + t.id + " tiene sonidos", !sons.isEmpty());
            for (Sonido so : sons) audible(h, t.id, so);
        }
        for (Sonido so : sonidos(seccion(c, "tormenta").getMapList("sonidos"))) audible(h, "tormenta", so);
    }

    /** Que no sea la lluvia de Minecraft y que se oiga: con volumen 1 o menos el cliente lo apaga a 16 bloques. */
    private static void audible(Autotest.Hoja h, String de, Sonido so) {
        h.ok("jar: " + de + ": " + so.sonido() + " no es la lluvia de Minecraft", !so.sonido().contains("weather.rain"));
        double alcance = 16.0 * Math.max(1.0, so.volumen());
        h.ok("jar: " + de + ": " + so.sonido() + " se oye (distancia " + so.distancia() + " < " + alcance + ")",
                so.distancia() < alcance * 0.75 && so.volumen() > 0);
    }

    private static boolean existe(String particula) {
        try {
            Particle.valueOf(particula);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Sin emojis (nada fuera del plano basico) y sin palabras enteras en mayusculas. */
    private static boolean textoLimpio(String s) {
        if (s == null || s.isBlank()) return false;
        if (s.codePoints().anyMatch(cp -> cp > 0xFFFF || Character.getType(cp) == Character.OTHER_SYMBOL)) return false;
        for (String palabra : s.split("\\s+")) {
            if (palabra.length() > 1 && palabra.equals(palabra.toUpperCase(Locale.ROOT))
                    && !palabra.equals(palabra.toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }

    /** Lo que el servidor le manda al cliente con la hora fija (ServerPlayer.getPlayerTime en 26.1.2). */
    private static long vistaServidor(long reloj, long offset) {
        return reloj - (reloj % BLOQUE_SERVIDOR) + offset;
    }

    private static List<Integer> golpes(int hasta, int margen, int cada) {
        List<Integer> out = new ArrayList<>();
        for (int s = 1; s <= hasta; s++) if (muerdeAcida(s, margen, cada)) out.add(s);
        return out;
    }

    private static String idPorQue(boolean parca, double cordura, DamageCause causa, String clima) {
        ParteDefuncion.PorQue p = ParteDefuncion.porQue(new ParteDefuncion.Causas(parca, cordura, null, null, causa,
                0, 12, false, clima));
        return p == null ? null : p.id();
    }
}
