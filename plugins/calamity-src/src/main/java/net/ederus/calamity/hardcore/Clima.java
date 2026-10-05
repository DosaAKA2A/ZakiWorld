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
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.WorldLoadEvent;
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
 * Temporal (1.12, tipo generico): lo que ve cualquier bioma de Panacea sin clima propio ("panacea/*" en la
 * tabla) mientras llueve en el ciclo: lluvia oscura (FALLING_DUST gris, nunca la azul de Minecraft), rachas
 * de viento (particulas empujadas en la direccion del viento del mundo) y truenos lejanos; en la tormenta,
 * ademas, rayos en el horizonte (tormenta.rayos). Sin dano. Lo de fuera de Panacea ("*") sigue sin nada.
 *
 * Tormenta de la PARCA (1.12, seccion tormenta-parca): la que ve quien esta cerca de ella desde la fase III,
 * en cualquier bioma y fase del ciclo. La pinta este modulo a peticion de ParcaAnomalia (segundoParca,
 * pulsoParca); mientras dura, el clima de su bioma no se pinta (Parca.lluviaSobre).
 *
 * Paso de hora (1.12): el cielo rojo fija la hora del jugador. Entrar o salir de el andando, volando o
 * porque escampa ya no es un salto: un barrido de cielo-rojo.transicion-ticks lleva su cielo por el camino
 * corto del dia de Panacea. Morir, cambiar de mundo, desconectarse, apagar el plugin o teletransportarse
 * lejos son cortes: ahi se le devuelve la hora en el acto, nunca queda fijada.
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
    /** 1.12 · Tope de particulas por pulso y jugador (efectos.tope-por-pulso): si la config pide mas, se reparte. */
    static final int TOPE_PULSO = 60;
    /** 1.12 · La seccion de la tormenta de la PARCA (fase III en adelante): hardcore.clima.tormenta-parca. */
    static final String PARCA = "tormenta-parca";
    /** 1.12 · Ticks que tarda el cielo en pasar del rojo al suyo, y al reves (cielo-rojo.transicion-ticks). */
    static final int CIELO_TRANSICION = 40;
    /** Lo mas lejos que el cliente pinta una particula que no es de largo alcance (32 bloques), con margen. */
    static final double ALCANCE_PARTICULAS = 30.0;
    /** Un teletransporte de mas de tantos bloques es un corte: el cielo no se barre, se decide ya. */
    static final double SALTO_TELEPORT = 16.0;

    /** Lo que le toca al bioma en el que esta. El id es tambien el nombre de su seccion en la config. */
    enum Tipo {
        NINGUNO("ninguno"),
        ACIDA("lluvia-acida"),
        ROJO("cielo-rojo"),
        ESPORAS("esporas"),
        POLEN("polinizacion"),
        CENIZA("ceniza"),
        /** 1.12 · El temporal de los biomas sin clima propio: lluvia oscura, viento y truenos; sin dano. */
        GENERICO("generico");

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
                case "generico", "genérico", "temporal" -> GENERICO;
                default -> null;
            };
        }

        /** Los que pasan por exposicion(): cordura y/o efecto a cielo abierto. */
        boolean porExposicion() {
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
        m.put("panacea/*", "generico");
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
            case GENERICO -> "Temporal";
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
            case GENERICO -> "Empieza a llover";
            default -> "";
        };
    }

    /** El anuncio si empieza a verlo en la fase de tormenta (inicio-tormenta); sin el suyo, el de inicio. */
    static String inicioTormentaDeSerie(Tipo t) {
        return t == Tipo.GENERICO ? "Estalla la tormenta" : inicioDeSerie(t);
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
            case GENERICO -> Paleta.TEMPORAL;
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

    /**
     * 1.12 · Lo que hace falta para pintarle una lluvia a un jugador: lo mide el segundo y lo lee el pulso.
     * Lo usa tambien la tormenta de la PARCA (ParcaAnomalia lleva uno por jugador).
     */
    static class Cielo {
        /** Segundos seguidos con esta lluvia (el ritmo de los sonidos y de las rachas). */
        int segundos;
        /** A cielo abierto para esta lluvia (con o sin hojas segun hojas-protegen). */
        boolean expuesto;
        /** Muy bajo tierra: ni particulas ni sonidos. */
        boolean profundo;
        /** El segundo en el que cae el proximo rayo (0 = aun sin sortear). */
        int proximoRayo;
    }

    /** Lo de cada jugador. Lo "puesto" es lo que este modulo le ha cambiado y tiene que devolverle. */
    private static final class Estado extends Cielo {
        final Episodio acida = new Episodio();
        final Episodio rojo = new Episodio();
        /** 1.12: esporas, polinizacion y ceniza (uno a la vez: al cambiar de tipo se olvida). */
        final Episodio expo = new Episodio();
        /** 1.12: el clima que ve ahora; lo pinta el pulso de particulas. */
        Tipo tipo = Tipo.NINGUNO;
        boolean tormenta;
        /** 1.12: le cae la tormenta de la PARCA; el clima del bioma se calla (ni particulas ni sonidos). */
        boolean deParca;
        /** Bajo el cielo rojo ahora mismo: Vineta le suma su parte mientras dure. */
        boolean bajoCielo;
        /** La hora es nuestra: la del cielo rojo, o la de un barrido hacia el. */
        boolean horaPuesta;
        /** El offset que le mandamos (no la hora del ciclo: ver offsetHora). */
        long hora;
        /** La hora del cielo rojo y el dia de Panacea con los que se calculo (para reponerla cada tick). */
        long horaDestino = CIELO_HORA;
        long periodoDestino;
        boolean fuegoPuesto;
    }

    /**
     * 1.12 · Una capa de particulas de un clima (hardcore.clima.<tipo>.particulas), por pulso. viento: cada
     * particula sale empujada en la direccion del viento del mundo (una por paquete: usarlas con poca
     * cantidad). racha-cada / racha-dura: la capa solo se pinta 'dura' segundos de cada 'cada' (las rachas).
     */
    record Capa(String particula, int cantidad, double radio, double altura, double espesor, double velocidad,
                int color, int color2, float tam, String bloque, boolean techo, boolean viento, int rachaCada,
                int rachaDura) {

        static Capa de(Map<?, ?> m) {
            return new Capa(texto(m, "particula", "").trim().toUpperCase(Locale.ROOT),
                    (int) Math.round(numero(m, "cantidad", 0)), numero(m, "radio", 6), numero(m, "altura", 2),
                    numero(m, "espesor", 1.5), numero(m, "velocidad", 0), colorDe(m.get("color"), 0xFFFFFF),
                    colorDe(m.get("color2"), colorDe(m.get("color"), 0xFFFFFF)), (float) numero(m, "tam", 1.0),
                    texto(m, "bloque", ""), Boolean.parseBoolean(texto(m, "techo", "false")),
                    Boolean.parseBoolean(texto(m, "viento", "false")), (int) Math.round(numero(m, "racha-cada", 0)),
                    (int) Math.round(numero(m, "racha-dura", 0)));
        }

        /** Si este segundo toca pintarla: sin rachas siempre; con rachas, los 'dura' primeros de cada 'cada'. */
        boolean toca(int segundo) {
            return enRacha(segundo, rachaCada, rachaDura);
        }
    }

    /** Las rachas: con cada <= 0 siempre; si no, los max(1, dura) primeros segundos de cada 'cada'. */
    static boolean enRacha(int segundo, int cada, int dura) {
        if (cada <= 0) return true;
        return Math.floorMod(segundo, cada) < Math.max(1, Math.min(cada, dura));
    }

    /** Un sonido de un tipo: cada 'cada' segundos, a 'distancia' bloques de el como mucho. */
    record Sonido(String sonido, int cada, float volumen, float tono, double distancia) {

        static Sonido de(Map<?, ?> m) {
            return new Sonido(texto(m, "sonido", ""), Math.max(1, (int) Math.round(numero(m, "cada", 5))),
                    (float) numero(m, "volumen", 0.5), (float) numero(m, "tono", 1.0), numero(m, "distancia", 4));
        }
    }

    /**
     * 1.12 · Los rayos de una tormenta (hardcore.clima.<seccion>.rayos): uno cada 'cada' segundos (+-40 %), a
     * 'distancia' bloques y bajando desde 'altura' sobre el, con su trueno. Solo a cielo abierto.
     */
    record Rayo(int cada, int color, float tam, double distancia, double altura, String sonido, float volumen,
                float tono) {

        /**
         * Null si no hay seccion o cada es 0 o menos (sin rayos). Se lee sin valor por defecto explicito
         * (s.get(k)): asi, en un config.yml del servidor anterior a la 1.12 (sin 'rayos'), valen los del jar.
         * Con getInt("cada", 0) Bukkit no mira los del jar y la tormenta se quedaba sin rayos.
         */
        static Rayo de(ConfigurationSection s) {
            if (s == null) return null;
            int cada = (int) Math.round(num(s, "cada", 0));
            if (cada <= 0) return null;
            Object son = s.get("sonido");
            return new Rayo(cada, colorDe(s.get("color"), 0xE6E1F5), (float) num(s, "tam", 1.6),
                    Math.max(4, num(s, "distancia", 20)), Math.max(4, num(s, "altura", 18)),
                    son == null ? "minecraft:entity.lightning_bolt.thunder" : String.valueOf(son),
                    (float) num(s, "volumen", 0.6), (float) num(s, "tono", 1.0));
        }

        /** Un numero de la seccion, o del jar si el servidor no lo tiene, o 'def'. */
        private static double num(ConfigurationSection s, String k, double def) {
            Object v = s.get(k);
            return v instanceof Number n ? n.doubleValue() : def;
        }

        /** Lo mas lejos que queda la punta del rayo de sus ojos (la distancia con su variacion y la altura). */
        double alcance() {
            double d = Math.min(distancia * 1.1, ALCANCE_PARTICULAS);
            return Math.sqrt(d * d + altura * altura);
        }
    }

    /** Lo ya leido de un clima: sus capas (con los datos de particula hechos), sus sonidos y sus rayos. */
    private record Receta(List<Capa> capas, List<Particle> particulas, List<Object> datos, List<Sonido> sonidos,
                          Rayo rayo) {
    }

    /**
     * 1.12 · El paso suave de la hora: del cielo rojo al suyo (haciaRojo false) o al reves. Cada tick se le
     * manda una hora fija un poco mas cerca del destino por el camino corto del dia de Panacea; al acabar
     * se le devuelve su hora (resetPlayerTime) o se le fija la del cielo rojo. 'ultimo' es el offset que se
     * le puso el tick anterior: si ya no lo tiene, otro (Eclipse, PARCA) le ha cambiado la hora y se deja.
     */
    private static final class Barrido {
        final UUID mundo;
        final boolean haciaRojo;
        final long hora;
        final long periodo;
        /** El estado del cielo rojo al que se llega (solo haciaRojo): ahi se apunta el offset de cada tick. */
        final Estado estado;
        double visto;
        int quedan;
        long ultimo;

        Barrido(UUID mundo, boolean haciaRojo, long hora, long periodo, Estado estado, double visto, int quedan,
                long ultimo) {
            this.mundo = mundo;
            this.haciaRojo = haciaRojo;
            this.hora = hora;
            this.periodo = periodo;
            this.estado = estado;
            this.visto = visto;
            this.quedan = quedan;
            this.ultimo = ultimo;
        }
    }

    private final Hardcore hc;
    private final Map<UUID, Estado> estados = new HashMap<>();
    /** A quien se ha mirado este segundo; al que no (espectador, otro mundo) se le devuelve todo en tick(). */
    private final Set<UUID> vistos = new HashSet<>();
    /** Quien esta recibiendo ahora mismo un golpe del clima, y cual: lo lee ParteDefuncion.onDano. */
    private final Map<UUID, String> enCurso = new HashMap<>();
    /** 1.12: cuando se le anuncio cada tipo por ultima vez (aviso-inicio-espera). */
    private final Map<UUID, Map<Tipo, Long>> anuncios = new HashMap<>();
    /** 1.12: los pasos de hora en curso (cielo rojo <-> el suyo), uno por jugador. */
    private final Map<UUID, Barrido> barridos = new HashMap<>();
    /** 1.12: lo leido de la config, por seccion (el id del tipo o tormenta-parca); se rehace si cambia (reload). */
    private final Map<String, Receta> recetas = new HashMap<>();
    private ConfigurationSection recetasDe;
    private ConfigurationSection tablaDe;
    private Map<String, Tipo> tabla = Map.of();
    /** 1.11: el reloj del clima (cuando llueve). */
    private final CicloClima ciclo;
    private final BukkitTask pulso;
    /** Cada cuantos ticks le toca su pulso de particulas a cada jugador (efectos.cada-ticks). */
    private final int cada;
    /** Ticks desde que arranco: el pulso de cada jugador cae en su tick (repartidos, no todos a la vez). */
    private long ticks;

    Clima(Hardcore hc) {
        this.hc = hc;
        this.ciclo = new CicloClima(hc);
        // Despues del ciclo: onLluvia/onTrueno lo leen, y si el ciclo no arranca no queda un oyente suelto.
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        // Un mundo que se guardo lloviendo (1.11 ponia setStorm(true)) se despeja ya, no al primer segundo.
        for (World w : hc.plugin().getServer().getWorlds()) hc.seguro("ciclo-clima", () -> ciclo.despejar(w));
        this.cada = Math.max(1, Math.min(20, cfg().getInt("efectos.cada-ticks", EFECTOS_CADA_TICKS)));
        // Cada tick: el paso de hora suave y el pulso de los jugadores a los que les toca este tick.
        this.pulso = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(),
                () -> hc.seguro("clima", this::pulso), 20L, 1L);
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
        boolean tormenta = tipo != Tipo.NINGUNO && ciclo.tormenta(p.getWorld());
        if (tipo != e.tipo) {
            e.segundos = 0;
            e.proximoRayo = 0;
            e.expo.olvidar();
            if (tipo != Tipo.NINGUNO) anunciar(p, tipo, tormenta, c);
        }
        e.tipo = tipo;
        e.segundos++;
        ConfigurationSection s = seccion(c, tipo.id);
        e.tormenta = tormenta;
        if (tipo != Tipo.NINGUNO) {
            medirCielo(p, e, s.getBoolean("hojas-protegen", hojasDeSerie(tipo)),
                    c.getInt("efectos.profundidad", PROFUNDIDAD));
            // La tormenta de la PARCA manda sobre lo que se ve del bioma (el dano, si lo hay, sigue).
            Parca parca = hc.parca();
            e.deParca = parca != null && hc.valor("parca", () -> parca.lluviaSobre(p), false);
        } else {
            e.expuesto = false;
            e.profundo = false;
            e.deParca = false;
        }
        boolean fuera = !spawn && danino;
        lluviaAcida(p, e, fuera && tipo == Tipo.ACIDA && e.expuesto, seccion(c, "lluvia-acida"));
        cieloRojo(p, e, tipo == Tipo.ROJO, fuera, r);
        exposicion(p, e, tipo, fuera && tipo.porExposicion() && e.expuesto, s);
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
        // Los pasos de hora a medias: la suya ya, sin barrido (el plugin se apaga).
        for (UUID u : new ArrayList<>(barridos.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) soltarHora(p, null);
        }
        barridos.clear();
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
     * 1.12 · Un teletransporte largo dentro del mismo mundo (el Cristal, /spawn, un /tp) es un corte de
     * escena: el cielo no se barre. Al tick siguiente, ya en el destino, si alli no toca el cielo rojo se le
     * devuelve su hora de golpe (no un segundo de cielo rojo donde no lo hay, ni medio barrido colgado);
     * si toca, se queda como esta y el segundo sigue. El cambio de mundo lo hace onCambiarMundo.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent ev) {
        Player p = ev.getPlayer();
        Location de = ev.getFrom(), a = ev.getTo();
        if (a == null || de.getWorld() == null || !de.getWorld().equals(a.getWorld())) return;
        if (!esSalto(de.distanceSquared(a))) return;
        UUID u = p.getUniqueId();
        Estado e = estados.get(u);
        if (!barridos.containsKey(u) && (e == null || !e.horaPuesta)) return;
        hc.plugin().getServer().getScheduler().runTask(hc.plugin(), () -> hc.seguro("clima", () -> trasSalto(p)));
    }

    private void trasSalto(Player p) {
        if (!p.isOnline()) return;
        UUID u = p.getUniqueId();
        Estado e = estados.get(u);
        if (e != null && sigueRojo(p)) {
            // Alli tambien es cielo rojo. Ya fijado: nada. A medio barrido: el rojo ya, sin acabarlo.
            Barrido b = barridos.get(u);
            if (b == null) return;
            barridos.remove(u);
            if (!esHora(p.isPlayerTimeRelative(), p.getPlayerTimeOffset(), b.ultimo)) return;
            ConfigurationSection r = seccion(cfg(), "cielo-rojo");
            long periodo = r.getLong("periodo-dia", CIELO_PERIODO);
            long off = offsetHora(p.getWorld().getFullTime(), hora(r), periodo);
            p.setPlayerTime(off, false);
            e.horaPuesta = true;
            e.hora = off;
            // Para que repasarHora la reponga al cambiar de bloque de 24000 (un estado recien hecho los tiene a 0).
            e.horaDestino = hora(r);
            e.periodoDestino = periodo;
            return;
        }
        soltarHora(p, e);
    }

    /** Si un teletransporte de esa distancia (al cuadrado) es un corte de escena. */
    static boolean esSalto(double distancia2) {
        return distancia2 > SALTO_TELEPORT * SALTO_TELEPORT;
    }

    /** Si donde esta ahora le toca el cielo rojo con su hora (lo mismo que decide pasar + ponerHora). */
    private boolean sigueRojo(Player p) {
        ConfigurationSection c = cfg();
        if (!c.getBoolean("activo", true) || p.isDead() || !hc.esHardcore(p)) return false;
        ConfigurationSection r = seccion(c, "cielo-rojo");
        if (hora(r) <= 0 || !ciclo.llueve(p.getWorld()) || tipo(bioma(p), c) != Tipo.ROJO) return false;
        return !hc.enSpawn(p) || r.getBoolean("en-spawn", true);
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

    /**
     * 1.12 · Un mundo de Calamity que se carga con lluvia guardada (de la 1.11, o de antes de ponerlo en
     * hardcore.mundos) se despeja al cargar y otra vez al tick siguiente (por si aun no estaba registrado
     * como mundo de Calamity): sin esto, quien entra antes del primer segundo del ciclo ve llover.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCargarMundo(WorldLoadEvent ev) {
        World w = ev.getWorld();
        hc.seguro("ciclo-clima", () -> ciclo.despejar(w));
        UUID id = w.getUID();
        hc.plugin().getServer().getScheduler().runTask(hc.plugin(), () -> {
            World cargado = Bukkit.getWorld(id);
            if (cargado != null) hc.seguro("ciclo-clima", () -> ciclo.despejar(cargado));
        });
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
    private static void medirCielo(Player p, Cielo e, boolean hojas, int profundidad) {
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
    private void anunciar(Player p, Tipo tipo, boolean tormenta, ConfigurationSection c) {
        if (!c.getBoolean("aviso-inicio", AVISO_INICIO)) return;
        ConfigurationSection s = seccion(c, tipo.id);
        String texto = s.getString("inicio", inicioDeSerie(tipo));
        // 1.12: en la fase de tormenta, su anuncio propio si lo tiene ("Estalla la tormenta" en el temporal).
        if (tormenta) texto = s.getString("inicio-tormenta", tipo == Tipo.GENERICO ? inicioTormentaDeSerie(tipo) : texto);
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
            // 1.12: sale del cielo rojo andando, volando o porque escampa: su hora vuelve barriendo, no de golpe.
            soltarHoraSuave(p, e, r);
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

    /**
     * La hora del cielo de sangre. El de la PARCA manda entero; la hora del Eclipse, tambien.
     *
     * 1.12: al entrar no se fija de golpe: un barrido de transicion-ticks lleva su cielo hasta el rojo por el
     * camino corto del dia de Panacea (ver Barrido). Mientras barre, aqui no se toca nada.
     */
    private void ponerHora(Player p, Estado e, ConfigurationSection r, boolean deParca, boolean deEclipse) {
        long hora = hora(r);
        if (hora > 0 && !deParca && !deEclipse) {
            Barrido b = barridos.get(p.getUniqueId());
            if (b != null && b.haciaRojo) return;
            // El offset depende del bloque de 24000 en el que va el reloj: lo repone cada tick el pulso
            // (repasarHora) y aqui se mira cada segundo por si otro le ha cambiado la hora.
            long periodo = r.getLong("periodo-dia", CIELO_PERIODO);
            long offset = offsetHora(p.getWorld().getFullTime(), hora, periodo);
            e.horaDestino = hora;
            e.periodoDestino = periodo;
            if (b == null && e.horaPuesta && esHora(p.isPlayerTimeRelative(), p.getPlayerTimeOffset(), offset)) return;
            int ticks = r.getInt("transicion-ticks", CIELO_TRANSICION);
            // Ya era nuestra (otro bloque de 24000) o sin transicion: fija ya. Si no, barrido desde lo que ve.
            boolean nuestra = b == null && e.horaPuesta
                    && esHora(p.isPlayerTimeRelative(), p.getPlayerTimeOffset(), e.hora);
            if (nuestra || ticks <= 0) {
                // Fija (relative false): el cielo no avanza mientras sigue ahi.
                barridos.remove(p.getUniqueId());
                p.setPlayerTime(offset, false);
                e.horaPuesta = true;
                e.hora = offset;
                return;
            }
            barrer(p, e, true, hora, periodo, ticks, b);
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

    /** Todo lo suyo fuera y el estado olvidado (cambio de mundo, muerte, salida). */
    private void soltar(Player p) {
        Estado e = estados.remove(p.getUniqueId());
        if (e != null) soltarCielo(p, e);
        else soltarHora(p, null);
    }

    private void soltarCielo(Player p, Estado e) {
        e.bajoCielo = false;
        e.tipo = Tipo.NINGUNO;
        soltarHora(p, e);
        quitarFuego(p, e);
    }

    /**
     * Devuelve la hora YA, solo si sigue la nuestra (la fija del cielo rojo o la del barrido en curso): la
     * noche del Eclipse o de la PARCA no se toca. Corta el barrido que hubiera. Para muerte, cambio de
     * mundo, salida, apagado, teletransporte largo y cuando el Eclipse o la PARCA toman el cielo.
     */
    private void soltarHora(Player p, Estado e) {
        boolean puesta = e != null && e.horaPuesta;
        long nuestra = e == null ? 0 : e.hora;
        if (e != null) e.horaPuesta = false;
        Barrido b = barridos.remove(p.getUniqueId());
        if (!p.isOnline()) return;
        boolean rel = p.isPlayerTimeRelative();
        long off = p.getPlayerTimeOffset();
        if ((puesta && esHora(rel, off, nuestra)) || (b != null && esHora(rel, off, b.ultimo))) p.resetPlayerTime();
    }

    /**
     * 1.12 · Sale del cielo rojo sin corte de escena (andando, volando, porque escampa): en vez de devolverle
     * la hora de golpe, un barrido de transicion-ticks lleva el cielo hasta su hora y entonces se le devuelve.
     */
    private void soltarHoraSuave(Player p, Estado e, ConfigurationSection r) {
        Barrido b = barridos.get(p.getUniqueId());
        if (b != null && !b.haciaRojo) {
            // Ya esta volviendo a su hora.
            e.horaPuesta = false;
            return;
        }
        if (!e.horaPuesta) return;
        int ticks = r.getInt("transicion-ticks", CIELO_TRANSICION);
        boolean nuestra = p.isOnline() && (b != null ? esHora(p.isPlayerTimeRelative(), p.getPlayerTimeOffset(), b.ultimo)
                : esHora(p.isPlayerTimeRelative(), p.getPlayerTimeOffset(), e.hora));
        if (!nuestra || ticks <= 0 || p.isDead()) {
            soltarHora(p, e);
            return;
        }
        e.horaPuesta = false;
        barrer(p, null, false, e.horaDestino, e.periodoDestino, ticks, b);
    }

    /**
     * Empieza (o da la vuelta a) un barrido desde lo que ve ahora. 'antes' es el barrido que hubiera: su
     * ultimo offset es el que tiene puesto el jugador.
     */
    private void barrer(Player p, Estado e, boolean haciaRojo, long hora, long periodo, int ticks, Barrido antes) {
        // El offset que tiene ahora pasa a ser nuestro: el del barrido anterior, o el fijo que tenga (la hora
        // roja, u otra que se pisa como se pisaba antes), o ninguno si va con su hora (relativa).
        long ultimo = antes != null ? antes.ultimo : p.isPlayerTimeRelative() ? SIN_OFFSET : p.getPlayerTimeOffset();
        Barrido b = new Barrido(p.getWorld().getUID(), haciaRojo, hora, periodo > 0 ? periodo : CIELO_PERIODO, e,
                p.getPlayerTime(), Math.max(1, ticks), ultimo);
        barridos.put(p.getUniqueId(), b);
        if (e != null) {
            // La hora es nuestra mientras barre: si muere o se va, soltarHora la devuelve.
            e.horaPuesta = true;
            e.hora = b.ultimo;
        }
    }

    /** Marca de "aun no le hemos puesto ningun offset" (empieza con su hora relativa). */
    private static final long SIN_OFFSET = Long.MIN_VALUE;

    /** Un tick de todos los barridos en curso (desde el pulso). */
    private void pasoBarridos() {
        for (java.util.Iterator<Map.Entry<UUID, Barrido>> it = barridos.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Barrido> en = it.next();
            Barrido b = en.getValue();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || !p.isOnline() || p.isDead() || !p.getWorld().getUID().equals(b.mundo)) {
                // Se fue, murio o cambio de mundo: lo devuelven soltar() y los oyentes. Por si alguno no llego
                // (un evento cancelado, otro orden), si sigue en linea con nuestra hora se le devuelve aqui.
                it.remove();
                if (p != null && p.isOnline() && esHora(p.isPlayerTimeRelative(), p.getPlayerTimeOffset(), b.ultimo)) {
                    p.resetPlayerTime();
                }
                if (b.estado != null) b.estado.horaPuesta = false;
                continue;
            }
            boolean rel = p.isPlayerTimeRelative();
            boolean suya = b.ultimo == SIN_OFFSET ? rel : esHora(rel, p.getPlayerTimeOffset(), b.ultimo);
            if (!suya) {
                // Otro (Eclipse, PARCA, un plugin) le ha puesto su hora: es suya, no se toca.
                it.remove();
                if (b.estado != null && b.estado.hora == b.ultimo) b.estado.horaPuesta = false;
                continue;
            }
            long reloj = p.getWorld().getFullTime();
            double destino = b.haciaRojo ? b.hora : reloj;
            b.visto = pasoHacia(b.visto, destino, b.quedan, b.periodo);
            b.quedan--;
            long off;
            if (b.quedan <= 0) {
                it.remove();
                if (!b.haciaRojo) {
                    p.resetPlayerTime();
                    continue;
                }
                off = offsetHora(reloj, b.hora, b.periodo);
            } else {
                off = Math.round(b.visto) - baseServidor(reloj);
            }
            p.setPlayerTime(off, false);
            b.ultimo = off;
            if (b.estado != null) b.estado.hora = off;
        }
    }

    /**
     * Cada tick, a quien tiene el cielo rojo fijo: al pasar el reloj al bloque de 24000 siguiente el offset
     * cambia (ver offsetHora) y se repone en ese mismo tick, no hasta un segundo despues con el cielo saltado.
     */
    private void repasarHora() {
        for (Map.Entry<UUID, Estado> en : estados.entrySet()) {
            Estado e = en.getValue();
            if (!e.horaPuesta || e.periodoDestino <= 0 || barridos.containsKey(en.getKey())) continue;
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || !p.isOnline()) continue;
            long off = offsetHora(p.getWorld().getFullTime(), e.horaDestino, e.periodoDestino);
            if (off == e.hora || !esHora(p.isPlayerTimeRelative(), p.getPlayerTimeOffset(), e.hora)) continue;
            p.setPlayerTime(off, false);
            e.hora = off;
        }
    }

    /**
     * El camino corto (con signo) de 'desde' a 'hasta' en un dia de 'periodo' ticks: entre -periodo/2 y
     * +periodo/2. Lo que importa es lo que se ve, que se repite cada periodo.
     */
    static double arco(double desde, double hasta, long periodo) {
        double per = periodo > 0 ? periodo : CIELO_PERIODO;
        double d = (hasta - desde) % per;
        if (d > per / 2) d -= per;
        if (d <= -per / 2) d += per;
        return d;
    }

    /** Un tick de barrido: lo que queda hasta el destino repartido entre los ticks que quedan. */
    static double pasoHacia(double visto, double destino, int quedan, long periodo) {
        return visto + arco(visto, destino, periodo) / Math.max(1, quedan);
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
     * 1.12 · El pulso, cada tick: primero los pasos de hora en curso (barridos) y la hora roja que cambia de
     * bloque; despues las particulas, a cada jugador cada efectos.cada-ticks y en su propio tick (repartidos,
     * no todos en el mismo). Un paquete por capa y jugador (el cliente reparte las particulas por el radio),
     * salvo las capas de viento, que van de una en una; con tope por pulso.
     */
    private void pulso() {
        long t = ++ticks;
        if (!barridos.isEmpty()) pasoBarridos();
        if (estados.isEmpty()) return;
        repasarHora();
        ConfigurationSection c = cfg();
        if (!c.getBoolean("activo", true)) return;
        double densidad = Math.max(0.0, c.getDouble("efectos.densidad", DENSIDAD));
        double tormenta = Math.max(0.0, c.getDouble("tormenta.densidad", TORMENTA_DENSIDAD));
        int tope = c.getInt("efectos.tope-por-pulso", TOPE_PULSO);
        for (Map.Entry<UUID, Estado> en : estados.entrySet()) {
            Estado e = en.getValue();
            if (e.tipo == Tipo.NINGUNO || e.profundo || e.deParca) continue;
            if (!tocaPulso(en.getKey().hashCode(), t, cada)) continue;
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || p.isDead()) continue;
            pintar(p, receta(e.tipo.id, c), e, densidad * (e.tormenta ? tormenta : 1.0), tope);
        }
    }

    /** Si en ese tick le toca el pulso a ese jugador (por su hash: cada uno en su tick, uno de cada 'cada'). */
    static boolean tocaPulso(int hash, long tick, int cada) {
        return Math.floorMod(hash + tick, (long) Math.max(1, cada)) == 0;
    }

    /** Las capas de una receta para ese jugador: las de techo siempre, el resto a cielo abierto; con tope. */
    private void pintar(Player p, Receta r, Cielo k, double factor, int tope) {
        int total = 0;
        for (Capa capa : r.capas()) {
            if (capa.toca(k.segundos) && (capa.techo() || k.expuesto)) total += cantidad(capa.cantidad(), factor);
        }
        if (total <= 0) return;
        double f = factor * escalaTope(total, tope);
        Location base = p.getLocation();
        for (int i = 0; i < r.capas().size(); i++) {
            Capa capa = r.capas().get(i);
            if (!capa.toca(k.segundos) || (!capa.techo() && !k.expuesto)) continue;
            int n = cantidad(capa.cantidad(), f);
            if (n <= 0) continue;
            if (capa.viento()) {
                viento(p, r.particulas().get(i), base, capa, n, r.datos().get(i));
            } else {
                particula(p, r.particulas().get(i), base.clone().add(0, capa.altura(), 0), n, capa.radio(),
                        capa.espesor(), capa.radio(), capa.velocidad(), r.datos().get(i));
            }
        }
    }

    /** Lo que se multiplica la densidad para no pasar del tope por pulso (tope 0 o menos = sin tope). */
    static double escalaTope(int total, int tope) {
        return tope > 0 && total > tope ? tope / (double) total : 1.0;
    }

    /**
     * 1.12 · Una capa de viento: cada particula sale con velocidad en la direccion del viento del mundo, desde
     * un punto al azar del radio corrido a barlovento (para que crucen a su alrededor y no salgan de el).
     */
    private static void viento(Player p, Particle tipo, Location base, Capa k, int n, Object datos) {
        double ang = direccionViento(System.currentTimeMillis(), p.getWorld().getUID().getLeastSignificantBits());
        double dx = Math.cos(ang), dz = Math.sin(ang);
        double vel = Math.max(0.05, k.velocidad());
        ThreadLocalRandom azar = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            double a = azar.nextDouble(Math.PI * 2);
            double d = azar.nextDouble(Math.max(0.5, k.radio()));
            double alto = k.espesor() > 0 ? azar.nextDouble(-k.espesor(), k.espesor()) : 0;
            Location l = base.clone().add(Math.cos(a) * d - dx * k.radio() * 0.5, k.altura() + alto,
                    Math.sin(a) * d - dz * k.radio() * 0.5);
            // Cantidad 0: el desplazamiento es la direccion y 'velocidad' su rapidez.
            particula(p, tipo, l, 0, dx, azar.nextDouble(-0.08, 0.03), dz, vel, datos);
        }
    }

    /**
     * La direccion del viento de un mundo (radianes): da una vuelta cada media hora con un vaiven lento, asi
     * que de un segundo al siguiente apenas cambia. 'semilla' la hace distinta por mundo.
     */
    static double direccionViento(long ms, long semilla) {
        double seg = ms / 1000.0;
        return Math.floorMod(semilla, 360L) * Math.PI / 180.0 + seg * (Math.PI * 2 / 1800.0) + 0.5 * Math.sin(seg / 97.0);
    }

    /** Las particulas de una capa con la densidad aplicada (redondeo normal; nunca negativo). */
    static int cantidad(int base, double factor) {
        return (int) Math.max(0, Math.round(base * factor));
    }

    /** Los sonidos del tipo (y los de la tormenta), a su ritmo; mas bajos a cubierto, ninguno muy bajo tierra. */
    private void sonidos(Player p, Estado e, ConfigurationSection c) {
        if (e.tipo == Tipo.NINGUNO || e.profundo || e.deParca) return;
        Receta r = receta(e.tipo.id, c);
        // Los rayos: los del clima si tiene; si no, en la tormenta los de hardcore.clima.tormenta.rayos.
        Rayo rayo = r.rayo() != null ? r.rayo() : e.tormenta ? rayoTormenta(c) : null;
        sonar(p, e, r.sonidos(), e.tormenta ? sonidosTormenta(c) : List.of(), rayo, c);
    }

    private List<Sonido> tormentaSonidos;
    private Rayo tormentaRayo;
    private ConfigurationSection tormentaDe;

    private List<Sonido> sonidosTormenta(ConfigurationSection c) {
        leerTormenta(c);
        return tormentaSonidos;
    }

    private Rayo rayoTormenta(ConfigurationSection c) {
        leerTormenta(c);
        return tormentaRayo;
    }

    private void leerTormenta(ConfigurationSection c) {
        if (c == tormentaDe && tormentaSonidos != null) return;
        ConfigurationSection t = seccion(c, "tormenta");
        tormentaSonidos = sonidos(t.getMapList("sonidos"));
        tormentaRayo = Rayo.de(t.getConfigurationSection("rayos"));
        tormentaDe = c;
    }

    /** Los sonidos de este segundo y, a cielo abierto, el rayo si le toca (ver esperaRayo). */
    private void sonar(Player p, Cielo k, List<Sonido> propios, List<Sonido> extra, Rayo rayo, ConfigurationSection c) {
        float factor = k.expuesto ? 1f : (float) Math.max(0, c.getDouble("efectos.volumen-a-cubierto", VOLUMEN_CUBIERTO));
        if (factor > 0) {
            for (Sonido s : propios) sonar(p, k, s, factor);
            for (Sonido s : extra) sonar(p, k, s, factor);
        }
        if (rayo == null) {
            k.proximoRayo = 0;
            return;
        }
        if (k.proximoRayo <= 0) {
            k.proximoRayo = k.segundos + esperaRayo(rayo.cada(), ThreadLocalRandom.current().nextDouble());
        } else if (k.segundos >= k.proximoRayo) {
            // Bajo techo no se ve: el siguiente se sortea igual (el trueno de la tormenta ya suena aparte).
            if (k.expuesto) rayo(p, rayo);
            k.proximoRayo = k.segundos + esperaRayo(rayo.cada(), ThreadLocalRandom.current().nextDouble());
        }
    }

    /** Segundos hasta el proximo rayo: 'cada' +-40 % segun azar (0 a 1), y nunca menos de 2. */
    static int esperaRayo(int cada, double azar) {
        double a = Math.max(0, Math.min(1, azar));
        return Math.max(2, (int) Math.round(Math.max(1, cada) * (0.6 + 0.8 * a)));
    }

    private static void sonar(Player p, Cielo k, Sonido s, float factor) {
        if (s.sonido().isBlank() || k.segundos % s.cada() != 0) return;
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

    /**
     * 1.12 · Un rayo solo para el: una linea quebrada de DUST que baja desde 'altura' sobre el hasta el suelo, a
     * 'distancia' bloques en una direccion al azar (dentro de los 32 en los que el cliente pinta particulas),
     * y su trueno desde ese lado, a no mas de 8 bloques (con volumen 1 o menos el cliente lo apaga a 16).
     */
    private static void rayo(Player p, Rayo r) {
        ThreadLocalRandom azar = ThreadLocalRandom.current();
        double ang = azar.nextDouble(Math.PI * 2);
        double d = Math.min(r.distancia() * (0.8 + azar.nextDouble() * 0.3), ALCANCE_PARTICULAS);
        Location pie = p.getLocation();
        double x = pie.getX() + Math.cos(ang) * d, z = pie.getZ() + Math.sin(ang) * d;
        double arriba = pie.getY() + r.altura(), abajo = pie.getY() - 2;
        Object datos = Compat.dust(r.color(), Math.max(0.1f, r.tam()));
        int tramos = 8;
        double alto = (arriba - abajo) / tramos;
        for (int i = 0; i <= tramos; i++) {
            double y = arriba - alto * i;
            particula(p, Compat.DUST, new Location(p.getWorld(), x, y, z), 3, 0.1, alto / 3, 0.1, 0, datos);
            x += azar.nextDouble(-1.2, 1.2);
            z += azar.nextDouble(-1.2, 1.2);
        }
        if (r.sonido() == null || r.sonido().isBlank()) return;
        double ds = Math.min(d, 8);
        Location l = pie.clone().add(Math.cos(ang) * ds, 2, Math.sin(ang) * ds);
        float tono = (float) Math.max(0.5, Math.min(2.0, r.tono() * (0.9 + azar.nextDouble() * 0.2)));
        try {
            p.playSound(l, r.sonido(), SoundCategory.WEATHER, r.volumen(), tono);
        } catch (Throwable ignorado) {
            // Un sonido con mal nombre no puede cortar el rayo.
        }
    }

    // ------------------------------------------------------- tormenta de la PARCA

    /** Cada cuantos ticks le toca el pulso de particulas a cada jugador (efectos.cada-ticks). */
    int cadaTicks() {
        return cada;
    }

    /**
     * 1.12 · El segundo de la tormenta de la PARCA para un jugador (lo llama ParcaAnomalia cada segundo a
     * quien esta cerca en la fase III o IV): mide su cielo y suena; a cielo abierto, rayos. Solo particulas y
     * sonidos suyos: el mundo y su clima no se tocan. Con tormenta-parca.activa en false, nada.
     */
    void segundoParca(Player p, Cielo k) {
        ConfigurationSection c = cfg();
        ConfigurationSection s = seccion(c, PARCA);
        if (!encendido(s) || p.isDead()) return;
        k.segundos++;
        medirCielo(p, k, s.getBoolean("hojas-protegen", true), c.getInt("efectos.profundidad", PROFUNDIDAD));
        if (k.profundo) return;
        Receta r = receta(PARCA, c);
        sonar(p, k, r.sonidos(), List.of(), r.rayo(), c);
    }

    /** 1.12 · Un pulso de particulas de la tormenta de la PARCA para ese jugador (desde su tick). */
    void pulsoParca(Player p, Cielo k) {
        if (k.profundo || k.segundos <= 0) return;
        ConfigurationSection c = cfg();
        if (!encendido(seccion(c, PARCA))) return;
        pintar(p, receta(PARCA, c), k, Math.max(0.0, c.getDouble("efectos.densidad", DENSIDAD)),
                c.getInt("efectos.tope-por-pulso", TOPE_PULSO));
    }

    /** Si en ese tick le toca el pulso de la tormenta de la PARCA (el mismo reparto que el del clima). */
    boolean tocaPulso(UUID jugador, long tick) {
        return tocaPulso(jugador.hashCode(), tick, cada);
    }

    /** Lo leido de la config para una seccion (capas validas con sus datos, sonidos y rayos). */
    private Receta receta(String id, ConfigurationSection c) {
        if (c != recetasDe) {
            recetas.clear();
            recetasDe = c;
        }
        return recetas.computeIfAbsent(id, t -> {
            ConfigurationSection s = seccion(c, t);
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
            return new Receta(capas, parts, datos, sonidos(s.getMapList("sonidos")),
                    Rayo.de(s.getConfigurationSection("rayos")));
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
        h.igual("bamboo_valley (Panacea sin clima propio): temporal", Tipo.GENERICO,
                tipo("panacea/bamboo_valley", serie, vacia));
        h.igual("quicksand_springs: temporal", Tipo.GENERICO, tipo("bracken:panacea/quicksand_springs", serie, vacia));
        h.igual("un clima de /lbiomes: nada", Tipo.NINGUNO, tipo("lethal:crimson", serie, vacia));
        h.igual("un bioma de Minecraft: nada", Tipo.NINGUNO, tipo("minecraft:plains", serie, vacia));
        h.igual("sin bioma, nada", Tipo.NINGUNO, tipo(null, serie, vacia));
        h.igual("la tabla de serie tiene sus 12 entradas", 12, serie.size());
        h.igual("texto: generico", Tipo.GENERICO, Tipo.deTexto("Genérico"));
        h.igual("texto: temporal", Tipo.GENERICO, Tipo.deTexto("temporal"));
        YamlConfiguration sinTemporal = new YamlConfiguration();
        sinTemporal.set("generico.activo", false);
        h.igual("temporal apagado: nada", Tipo.NINGUNO, tipo("panacea/bamboo_valley", serie, sinTemporal));
        h.igual("y la lluvia acida sigue", Tipo.ACIDA, tipo("panacea/creeper_dominion", serie, sinTemporal));
        YamlConfiguration todo = new YamlConfiguration();
        todo.set("por-bioma.*", "generico");
        h.igual("por-bioma \"*\": generico lo lleva a todo", Tipo.GENERICO, tipo("minecraft:plains", tabla(todo), todo));
        // El temporal no hace dano: ni golpe, ni cordura, ni efecto, ni aviso en la barra.
        h.ok("temporal: no pasa por la exposicion", !Tipo.GENERICO.porExposicion());
        h.ok("temporal: sin cordura ni efecto ni aviso", corduraDeSerie(Tipo.GENERICO) == 0
                && efectoDeSerie(Tipo.GENERICO).isEmpty() && avisoDeSerie(Tipo.GENERICO).isEmpty());
        h.ok("temporal: las hojas tapan como un techo", hojasDeSerie(Tipo.GENERICO));
        h.igual("temporal en tormenta: su anuncio", "Estalla la tormenta", inicioTormentaDeSerie(Tipo.GENERICO));
        h.igual("los demas en tormenta: el de siempre", inicioDeSerie(Tipo.ACIDA), inicioTormentaDeSerie(Tipo.ACIDA));

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

        // 1.12: rachas, reparto del pulso, tope, viento y rayos.
        h.ok("sin rachas: siempre", enRacha(1, 0, 0) && enRacha(5, 0, 2));
        List<Integer> racha = new ArrayList<>();
        for (int seg = 0; seg < 15; seg++) if (enRacha(seg, 7, 2)) racha.add(seg);
        h.igual("racha de 2 s cada 7", List.of(0, 1, 7, 8, 14), racha);
        h.ok("racha-dura 0 vale 1", enRacha(7, 7, 0) && !enRacha(8, 7, 0));
        h.ok("racha-dura mayor que cada: siempre", enRacha(3, 4, 9));
        boolean cadaUno = true;
        Map<Long, Integer> porTick = new HashMap<>();
        for (int j = 0; j < 40; j++) {
            int hash = UUID.nameUUIDFromBytes(("jugador" + j).getBytes()).hashCode();
            int veces = 0;
            for (long tk = 0; tk < 20; tk++) {
                if (!tocaPulso(hash, tk, EFECTOS_CADA_TICKS)) continue;
                veces++;
                porTick.merge(tk % EFECTOS_CADA_TICKS, 1, Integer::sum);
            }
            cadaUno &= veces == 20 / EFECTOS_CADA_TICKS;
        }
        h.ok("pulso: cada jugador 5 veces por segundo", cadaUno);
        h.ok("pulso: repartidos en los 4 ticks, no todos en el mismo", porTick.size() == EFECTOS_CADA_TICKS
                && porTick.values().stream().allMatch(v -> v < 40 * 5));
        h.ok("pulso: cada 1 = todos los ticks", tocaPulso(12345, 7, 1) && tocaPulso(-3, 8, 0));
        h.cerca("tope: por debajo no toca", 1.0, escalaTope(40, 60), 1e-9);
        h.cerca("tope: por encima rebaja", 0.5, escalaTope(120, 60), 1e-9);
        h.cerca("tope 0: sin tope", 1.0, escalaTope(500, 0), 1e-9);
        double maxCambio = 0;
        for (long ms = 0; ms < 3_600_000L; ms += 1000) {
            maxCambio = Math.max(maxCambio, Math.abs(direccionViento(ms + 1000, 7) - direccionViento(ms, 7)));
        }
        h.ok("viento: de un segundo al siguiente casi no cambia (" + Math.round(maxCambio * 1000) / 1000.0 + " rad)",
                maxCambio < 0.02);
        h.ok("viento: en media hora da la vuelta", Math.abs(direccionViento(1_800_000L, 0) - direccionViento(0, 0)
                - Math.PI * 2) < 1.0);
        h.ok("viento: cada mundo el suyo", direccionViento(0, 1) != direccionViento(0, 90));
        int menor = Integer.MAX_VALUE, mayor = 0;
        for (int i = 0; i <= 10; i++) {
            menor = Math.min(menor, esperaRayo(13, i / 10.0));
            mayor = Math.max(mayor, esperaRayo(13, i / 10.0));
        }
        h.ok("rayo: entre 8 y 18 s con cada 13 (" + menor + "-" + mayor + ")", menor == 8 && mayor == 18);
        h.ok("rayo: nunca menos de 2 s", esperaRayo(1, 0) == 2 && esperaRayo(0, 0.5) == 2);
        h.ok("rayo: sin seccion o con cada 0, no hay", Rayo.de(null) == null && Rayo.de(vacia) == null);
        YamlConfiguration lejos = new YamlConfiguration();
        lejos.set("cada", 10);
        lejos.set("distancia", 200);
        lejos.set("altura", 10);
        h.ok("rayo: la distancia se queda en los 30 que pinta el cliente", Rayo.de(lejos).alcance() < 32);
        h.ok("teletransporte de 20 bloques: corte", esSalto(20 * 20));
        h.ok("una perla de 10 bloques: no es corte (barre)", !esSalto(10 * 10));
        Capa viento = Capa.de(Map.of("particula", "cloud", "viento", true, "racha-cada", 7, "racha-dura", 2,
                "velocidad", 0.35));
        h.ok("capa de viento leida", viento.viento() && viento.rachaCada() == 7 && viento.toca(1) && !viento.toca(3));

        // 1.12: el paso de hora (barrido) del cielo rojo al suyo y al reves.
        autotestBarrido(h);

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

    /**
     * 1.12 · El barrido de la hora, simulado tick a tick como lo hace pasoBarridos: el reloj avanza 1 por tick,
     * lo que ve el cliente es base(reloj) + offset, y al acabar no hay salto (ni al soltarle la hora ni al
     * fijarle la roja). Por el camino corto, en los ticks pedidos y sin pasos gordos.
     */
    private static void autotestBarrido(Autotest.Hoja h) {
        h.cerca("arco: adelante", 1_000, arco(10_000, 11_000, CIELO_PERIODO), 1e-9);
        h.cerca("arco: atras", -1_000, arco(11_000, 10_000, CIELO_PERIODO), 1e-9);
        h.cerca("arco: por el final del dia", 2_000, arco(71_000, 1_000, CIELO_PERIODO), 1e-9);
        h.cerca("arco: un dia entero es nada", 0, arco(5_000, 77_000, CIELO_PERIODO), 1e-9);
        h.ok("arco: nunca mas de medio dia", Math.abs(arco(0, 36_001, CIELO_PERIODO)) <= 36_000);
        h.cerca("paso: en el ultimo tick llega", 11_000, pasoHacia(10_000, 11_000, 1, CIELO_PERIODO), 1e-9);
        h.cerca("paso: quedan 0 vale 1", 11_000, pasoHacia(10_000, 11_000, 0, CIELO_PERIODO), 1e-9);
        long[] relojes = {5_000L, 23_990L, 30_123L, 47_995L, 63_000L, 71_990L, 1_000_000L};
        boolean salida = true, entrada = true, cortos = true, enTicks = true;
        for (long reloj0 : relojes) {
            // Salida: tenia el cielo rojo fijo; tras CIELO_TRANSICION ticks se le suelta y ve su reloj.
            long reloj = reloj0;
            long off = offsetHora(reloj, CIELO_HORA, CIELO_PERIODO);
            double visto = baseServidor(reloj) + off;
            double antes = visto;
            int quedan = CIELO_TRANSICION, pasos = 0;
            double total = Math.abs(arco(visto, reloj + CIELO_TRANSICION, CIELO_PERIODO));
            while (quedan > 0) {
                reloj++;
                visto = pasoHacia(visto, reloj, quedan, CIELO_PERIODO);
                quedan--;
                pasos++;
                long enviado = baseServidor(reloj) + (Math.round(visto) - baseServidor(reloj));
                cortos &= Math.abs(arco(antes, enviado, CIELO_PERIODO)) <= total / CIELO_TRANSICION * 2 + 2;
                antes = enviado;
            }
            salida &= Math.abs(arco(visto, reloj, CIELO_PERIODO)) <= 1.0;
            enTicks &= pasos == CIELO_TRANSICION;
            // Entrada: con su hora (relativa) hasta el rojo; el ultimo paso fija el offset de offsetHora.
            reloj = reloj0;
            visto = reloj;
            quedan = CIELO_TRANSICION;
            while (quedan > 0) {
                reloj++;
                visto = pasoHacia(visto, CIELO_HORA, quedan, CIELO_PERIODO);
                quedan--;
            }
            long fija = baseServidor(reloj) + offsetHora(reloj, CIELO_HORA, CIELO_PERIODO);
            entrada &= Math.abs(arco(visto, fija, CIELO_PERIODO)) <= 1.0
                    && Math.floorMod(fija, CIELO_PERIODO) == CIELO_HORA;
        }
        h.ok("barrido de salida: acaba en su reloj, sin salto al soltarle la hora", salida);
        h.ok("barrido de entrada: acaba en el cielo de sangre, sin salto al fijarlo", entrada);
        h.ok("barrido: dura justo transicion-ticks", enTicks);
        h.ok("barrido: pasos parejos, ninguno gordo", cortos);
        h.ok("de serie: 2 s de transicion", CIELO_TRANSICION == 40);
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
        h.igual("jar: cielo-rojo.transicion-ticks", CIELO_TRANSICION, c.getInt("cielo-rojo.transicion-ticks", -1));
        h.igual("jar: efectos.tope-por-pulso", TOPE_PULSO, c.getInt("efectos.tope-por-pulso", -1));
        h.igual("jar: generico.activo", true, c.getBoolean("generico.activo", false));
        h.igual("jar: generico.inicio-tormenta", inicioTormentaDeSerie(Tipo.GENERICO), c.getString("generico.inicio-tormenta"));
        h.ok("jar: el temporal no hace dano (ni dano, ni cordura, ni efecto)", !c.isSet("generico.dano")
                && c.getDouble("generico.cordura-por-segundo", 0) == 0 && c.getString("generico.efecto.tipo", "").isEmpty());
        h.igual("jar: tormenta-parca.activa", true, c.getBoolean(PARCA + ".activa", false));
        Rayo rayoTormenta = Rayo.de(c.getConfigurationSection("tormenta.rayos"));
        h.ok("jar: la tormenta trae rayos", rayoTormenta != null);
        if (rayoTormenta != null) autotestRayo(h, "tormenta", rayoTormenta);
        h.cerca("jar: esporas.cordura-por-segundo", ESPORAS_CORDURA, c.getDouble("esporas.cordura-por-segundo", -1), 1e-9);
        h.igual("jar: polinizacion.efecto.tipo", POLEN_EFECTO, c.getString("polinizacion.efecto.tipo"));
        h.igual("jar: polinizacion.efecto.nivel", POLEN_NIVEL, c.getInt("polinizacion.efecto.nivel", -1));
        h.igual("jar: ceniza.efecto.tipo", "", c.getString("ceniza.efecto.tipo", "?"));

        double tormenta = Math.max(1.0, c.getDouble("tormenta.densidad", TORMENTA_DENSIDAD));
        for (Tipo t : Tipo.values()) {
            if (t == Tipo.NINGUNO) continue;
            ConfigurationSection s = seccion(c, t.id);
            h.igual("jar: " + t.id + ".nombre", nombreDeSerie(t), s.getString("nombre"));
            h.igual("jar: " + t.id + ".inicio", inicioDeSerie(t), s.getString("inicio"));
            h.igual("jar: " + t.id + ".aviso", avisoDeSerie(t), s.getString("aviso", ""));
            h.igual("jar: " + t.id + ".hojas-protegen", hojasDeSerie(t), s.getBoolean("hojas-protegen", !hojasDeSerie(t)));
            autotestSeccion(h, c, t.id, tormenta);
        }
        // La tormenta de la PARCA: con la densidad normal (no lleva la de la tormenta del ciclo encima).
        h.ok("jar: " + PARCA + ".hojas-protegen", seccion(c, PARCA).getBoolean("hojas-protegen", false));
        autotestSeccion(h, c, PARCA, 1.0);
        Rayo rayoParca = Rayo.de(seccion(c, PARCA).getConfigurationSection("rayos"));
        h.ok("jar: la tormenta de la PARCA trae rayos", rayoParca != null);
        if (rayoParca != null) autotestRayo(h, PARCA, rayoParca);
        // Un config.yml del servidor de antes de la 1.12 (sin tormenta-parca ni tormenta.rayos): valen los rayos del jar.
        if (c.getRoot() != null) {
            YamlConfiguration viejo = new YamlConfiguration();
            viejo.setDefaults(c.getRoot());
            ConfigurationSection cv = viejo.getConfigurationSection(c.getCurrentPath());
            h.ok("config vieja sin rayos: la tormenta de la PARCA trae los del jar",
                    cv != null && Rayo.de(seccion(cv, PARCA).getConfigurationSection("rayos")) != null);
            h.ok("config vieja sin rayos: la tormenta trae los del jar",
                    cv != null && Rayo.de(seccion(cv, "tormenta").getConfigurationSection("rayos")) != null);
        }
        for (Sonido so : sonidos(seccion(c, "tormenta").getMapList("sonidos"))) audible(h, "tormenta", so);
    }

    /** Las particulas y sonidos de una seccion: que existan, que no sean lluvia, DUST para Bedrock y presupuesto. */
    private static void autotestSeccion(Autotest.Hoja h, ConfigurationSection c, String id, double tormenta) {
        int cada = Math.max(1, c.getInt("efectos.cada-ticks", EFECTOS_CADA_TICKS));
        double pulsos = 20.0 / cada;
        ConfigurationSection s = seccion(c, id);
        List<Capa> capas = capas(s.getMapList("particulas"));
        h.ok("jar: " + id + " tiene particulas", !capas.isEmpty());
        h.ok("jar: " + id + " tiene al menos una capa que se ve a cubierto o una a cielo abierto",
                capas.stream().anyMatch(k -> k.cantidad() > 0));
        int porPulso = 0;
        boolean conDust = false;
        for (Capa k : capas) {
            h.ok("jar: " + id + ": " + k.particula() + " no es lluvia de Minecraft",
                    !PARTICULAS_DE_LLUVIA.contains(k.particula()));
            h.ok("jar: " + id + ": " + k.particula() + " existe", existe(k.particula()));
            h.ok("jar: " + id + ": " + k.particula() + " radio entre 1 y 16", k.radio() >= 1 && k.radio() <= 16);
            if (k.viento()) {
                h.ok("jar: " + id + ": el viento va de una en una, con pocas (" + k.cantidad() + ")", k.cantidad() <= 4);
                h.ok("jar: " + id + ": el viento se mueve", k.velocidad() > 0);
            }
            porPulso += k.cantidad();
            conDust |= k.particula().equals("DUST");
        }
        h.ok("jar: " + id + " lleva DUST (la que Bedrock pinta seguro)", conDust);
        double maximo = porPulso * pulsos * tormenta;
        h.ok("jar: " + id + ": " + Math.round(maximo) + " particulas/s por jugador con tormenta (tope "
                + PRESUPUESTO_SEGUNDO + ")", maximo <= PRESUPUESTO_SEGUNDO);
        h.ok("jar: " + id + ": " + Math.round(porPulso * tormenta) + " por pulso, dentro del tope-por-pulso",
                porPulso * tormenta <= c.getInt("efectos.tope-por-pulso", TOPE_PULSO));
        List<Sonido> sons = sonidos(s.getMapList("sonidos"));
        h.ok("jar: " + id + " tiene sonidos", !sons.isEmpty());
        for (Sonido so : sons) audible(h, id, so);
    }

    /** Un rayo: que se vea (dentro de los 32 bloques del cliente), que tenga trueno y que no sea un parpadeo continuo. */
    private static void autotestRayo(Autotest.Hoja h, String de, Rayo r) {
        h.ok("jar: " + de + ".rayos dentro de lo que pinta el cliente (" + Math.round(r.alcance()) + " < 32)",
                r.alcance() < 32);
        h.ok("jar: " + de + ".rayos: no mas de uno cada 5 s", r.cada() >= 5);
        h.ok("jar: " + de + ".rayos con trueno", r.sonido() != null && r.sonido().contains("thunder") && r.volumen() > 0);
        h.ok("jar: " + de + ".rayos claros (se ven de noche)", luminancia(r.color()) > 0.5);
    }

    private static double luminancia(int rgb) {
        return (0.2126 * ((rgb >> 16) & 0xFF) + 0.7152 * ((rgb >> 8) & 0xFF) + 0.0722 * (rgb & 0xFF)) / 255.0;
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
