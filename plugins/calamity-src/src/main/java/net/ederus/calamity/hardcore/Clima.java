package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.util.TriState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.WeatherType;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Calamity 1.9.0 · El clima de Calamity por bioma, solo mientras llueve de verdad en el mundo
 * (World#hasStorm). Encargo de Dosa: en los biomas verdes la lluvia es acida y hace dano; en los
 * rojos no se ve la lluvia, el cielo se oscurece en rojo y cada cierto tiempo arde.
 *
 * Todo pasa en la pantalla de cada jugador (setPlayerWeather, setPlayerTime, particulas suyas):
 * el mundo no se toca, asi que dos jugadores en biomas distintos ven cada uno su cielo. Nada de
 * esto entra en la zona spawn: Hardcore.tick solo llama a segundo() fuera de ella.
 *
 * Lluvia acida: p.isInRain() ya mira el techo, las hojas y si el bioma llueve, asi que bajo techo
 * no pasa nada. Primero un aviso en la barra y un margen; despues quita vida cada pocos segundos.
 * Va por damage(MAGIC) sin entidad y no por DanoVerdadero: no es un combate, asi que no pone la
 * etiqueta "En combate" ni congela la Huella. Sin regeneracion natural cada golpe cuenta, y por
 * eso el aviso y el margen: quien busca refugio a tiempo no pierde nada.
 *
 * Cielo rojo: se le oculta la lluvia (CLEAR), se le pone la hora del cielo de sangre del ciclo de
 * Panacea (recalculada cada segundo, porque el servidor la fija por bloques de 24000 y el dia de
 * Panacea dura 72000: ver offsetHora) y Vineta le suma su borde rojo (vinetaExtra, igual que el Eclipse: nunca un segundo
 * borde). La lluvia de verdad del servidor apaga el fuego vanilla, asi que la quemadura se hace a
 * mano: fuego visual y dano de fuego (ON_FIRE) cada segundo. La resistencia al fuego lo para, y es
 * la forma legitima de aguantar alli.
 *
 * Convivencia: solo se devuelve lo que puso este modulo, y solo si sigue siendo lo suyo (si otro lo
 * cambio despues, no se toca). El cielo de la PARCA manda (Parca.cieloSobre: ella no lo repone si
 * se lo pisan) y la hora del Eclipse tambien. EDM (Lethal Biomes, los efectos de muerte de rip)
 * puede resetear la hora o el clima sin preguntar: cada segundo se mira si sigue puesto y, si no,
 * se repone. En una zona pintada con /lbiomes el bioma deja de ser de Panacea, asi que ahi este
 * modulo no hace nada y no se pelea con EDM.
 *
 * Calamity 1.11: cuando llueve lo decide el plugin (CicloClima, hardcore.clima.ciclo): apaga el ciclo
 * vanilla en el mundo y pone la lluvia con setStorm, asi que World#hasStorm sigue siendo la fuente de
 * verdad de este modulo. CicloClima vive aqui dentro: nace, late (tick) y se para con Clima.
 */
final class Clima implements Listener {

    /** Nombre del golpe en el parte de defuncion (linea del golpe y "por que"). */
    static final String CAUSA_ACIDA = "lluvia ácida";
    static final String CAUSA_CIELO = "cielo rojo";

    /*
     * Los valores de serie. Son los mismos que trae hardcore.clima en el config.yml del jar (el
     * autotest los compara uno a uno): si el servidor no tiene la seccion, da igual de donde se lean.
     */
    static final List<String> BIOMAS_VERDES = List.of("panacea/horsetail_tropics", "panacea/creeper_dominion",
            "panacea/polypore_plains", "panacea/wildflower_bog", "panacea/hungering_jungle",
            "panacea/ravenous_greenwood", "panacea/sweltering_swamp");
    static final List<String> BIOMAS_ROJOS = List.of("panacea/crimson_organism");
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

    /** Lo que le toca al bioma en el que esta: nada, lluvia acida o cielo rojo. */
    enum Tipo { NINGUNO, ACIDA, ROJO }

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
    }

    /** Lo de cada jugador. Lo "puesto" es lo que este modulo le ha cambiado y tiene que devolverle. */
    private static final class Estado {
        final Episodio acida = new Episodio();
        final Episodio rojo = new Episodio();
        /** Bajo el cielo rojo ahora mismo: Vineta le suma su parte mientras dure. */
        boolean bajoCielo;
        /** 1.11: en un bioma de lluvia acida mientras llueve (se le oculta la lluvia azul y caen gotas verdes). */
        boolean bajoAcida;
        boolean climaPuesto;
        boolean horaPuesta;
        /** El offset que le mandamos (no la hora del ciclo: ver offsetHora). */
        long hora;
        boolean fuegoPuesto;
    }

    private final Hardcore hc;
    private final Map<UUID, Estado> estados = new HashMap<>();
    /** A quien se ha mirado este segundo; al que no (spawn, espectador, otro mundo) se le devuelve todo en tick(). */
    private final Set<UUID> vistos = new HashSet<>();
    /** Quien esta recibiendo ahora mismo un golpe del clima, y cual: lo lee ParteDefuncion.onDano. */
    private final Map<UUID, String> enCurso = new HashMap<>();
    /** El polvo que cae (FALLING_DUST toma el color del bloque). Aqui y no estatico: sin servidor no hay BlockData. */
    private final BlockData polvoAcido;
    private final BlockData polvoRojo;
    /** 1.11: el reloj del clima (cuando llueve). */
    private final CicloClima ciclo;

    Clima(Hardcore hc) {
        this.hc = hc;
        this.polvoAcido = Material.LIME_CONCRETE_POWDER.createBlockData();
        this.polvoRojo = Material.RED_CONCRETE_POWDER.createBlockData();
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        this.ciclo = new CicloClima(hc);
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
     * spawn. Quien no pase por aqui en un segundo pierde lo que tuviera en tick().
     */
    void segundo(Player p) {
        ConfigurationSection c = cfg();
        if (!c.getBoolean("activo", true)) return;
        UUID u = p.getUniqueId();
        if (p.isDead()) {
            soltar(p);
            return;
        }
        Tipo tipo = p.getWorld().hasStorm() ? tipo(bioma(p), c) : Tipo.NINGUNO;
        Estado e = estados.get(u);
        if (e == null) {
            if (tipo == Tipo.NINGUNO) return;
            e = new Estado();
            estados.put(u, e);
        }
        vistos.add(u);
        lluviaAcida(p, e, tipo == Tipo.ACIDA && p.isInRain(), seccion(c, "lluvia-acida"));
        cieloRojo(p, e, tipo == Tipo.ROJO, seccion(c, "cielo-rojo"));
        cieloAcido(p, e, tipo == Tipo.ACIDA, seccion(c, "lluvia-acida"));
        // Sin nada puesto y con las dos cuentas olvidadas, no hace falta seguir acordandose de el.
        if (e.acida.dentro == 0 && e.rojo.dentro == 0 && !e.bajoCielo && !e.bajoAcida && !e.climaPuesto
                && !e.horaPuesta && estados.get(u) == e) {
            estados.remove(u);
        }
    }

    /**
     * 1.11 · Encargo de Dosa: en la zona spawn no llueve. Hardcore.tick lo llama alli en vez de
     * segundo(): mientras el mundo llueve se le oculta la lluvia (solo en su pantalla) y nada de lo de
     * fuera (cielo rojo, gotas, fuego) le sigue dentro. Al salir, segundo() se la devuelve.
     */
    void enSpawn(Player p) {
        ConfigurationSection c = cfg();
        if (!c.getBoolean("activo", true) || !c.getBoolean("spawn-sin-lluvia", true) || p.isDead()) return;
        UUID u = p.getUniqueId();
        boolean llueve = p.getWorld().hasStorm();
        Estado e = estados.get(u);
        if (e == null) {
            if (!llueve) return;
            e = new Estado();
            estados.put(u, e);
        }
        vistos.add(u);
        e.bajoCielo = false;
        e.bajoAcida = false;
        quitarFuego(p, e);
        // Encargo de Dosa: que el spawn se vea como fuera. En el bioma rojo, mientras llueve, el mismo
        // cielo de sangre y la misma ceniza, pero sin fuego, sin dano y sin la vineta (es zona segura).
        ConfigurationSection r = seccion(c, "cielo-rojo");
        boolean rojo = llueve && r.getBoolean("en-spawn", true) && tipo(bioma(p), c) == Tipo.ROJO;
        if (rojo) {
            Parca parca = hc.parca();
            boolean deParca = parca != null && hc.valor("parca", () -> parca.cieloSobre(p), false);
            Eclipse eclipse = hc.eclipse();
            boolean deEclipse = eclipse != null && hc.valor("eclipse", eclipse::activo, false);
            ponerHora(p, e, r, deParca, deEclipse);
            if (p.isInRain()) ceniza(p);
        } else {
            soltarHora(p, e);
        }
        if (llueve) ocultarLluvia(p, e);
        else soltarClima(p, e);
        if (!e.climaPuesto && !e.horaPuesta && e.acida.dentro == 0 && e.rojo.dentro == 0 && estados.get(u) == e) {
            estados.remove(u);
        }
    }

    /** Una vez por segundo, despues de los jugadores: quien no se ha visto recupera su cielo. */
    void tick() {
        // 1.11: primero el reloj del clima, que decide si llueve (lo vera el segundo siguiente de cada jugador).
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
        hc.seguro("ciclo-clima", ciclo::parar);
        for (UUID u : new ArrayList<>(estados.keySet())) {
            Estado e = estados.remove(u);
            Player p = Bukkit.getPlayer(u);
            if (p != null && e != null) soltarCielo(p, e);
        }
        estados.clear();
        vistos.clear();
        enCurso.clear();
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
        // llamas para siempre. Se le devuelve todo antes; la hora y el clima no se guardan, pero
        // soltarlos aqui no cuesta nada.
        soltar(ev.getPlayer());
    }

    // ------------------------------------------------------------ lluvia acida

    private void lluviaAcida(Player p, Estado e, boolean bajo, ConfigurationSection a) {
        e.acida.paso(bajo, a.getInt("olvido-segundos", ACIDA_OLVIDO));
        if (!bajo) return;
        int s = e.acida.dentro;
        salpicadura(p);
        if (avisaAcida(s)) {
            hc.cordura().destello(p, Component.text("Lluvia ácida", Paleta.ACIDO)
                    .append(Component.text(": busca un techo, que quema.", Paleta.TEXTO)), 3);
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

    /**
     * 1.11 · Encargo de Dosa: la lluvia azul de Minecraft no pega con la lluvia acida. En un bioma
     * verde, mientras llueve, se le oculta la lluvia (como en el cielo rojo) y en su lugar caen las
     * gotas verdes a su alrededor. La lluvia del servidor sigue ahi: isInRain y el dano no cambian.
     */
    private void cieloAcido(Player p, Estado e, boolean dentro, ConfigurationSection a) {
        e.bajoAcida = dentro;
        if (dentro) {
            gotas(p);
            if (a.getBoolean("ocultar-lluvia", true)) {
                ocultarLluvia(p, e);
                return;
            }
        }
        // Sin acido, la lluvia solo se devuelve si el cielo rojo no la esta ocultando.
        if (!e.bajoCielo) soltarClima(p, e);
    }

    /** Le quita la lluvia de la pantalla, salvo que el cielo sea de la PARCA (el suyo manda entero). */
    private void ocultarLluvia(Player p, Estado e) {
        Parca parca = hc.parca();
        if (parca != null && hc.valor("parca", () -> parca.cieloSobre(p), false)) {
            soltarClima(p, e);
            return;
        }
        if (!e.climaPuesto || p.getPlayerWeather() != WeatherType.CLEAR) {
            p.setPlayerWeather(WeatherType.CLEAR);
            e.climaPuesto = true;
        }
    }

    // --------------------------------------------------------------- cielo rojo

    private void cieloRojo(Player p, Estado e, boolean dentro, ConfigurationSection r) {
        e.rojo.paso(dentro, r.getInt("olvido-segundos", CIELO_OLVIDO));
        if (!dentro) {
            // 1.11: la lluvia no se toca aqui; la devuelve cieloAcido, que sabe si el acido la quiere oculta.
            e.bajoCielo = false;
            soltarHora(p, e);
            quitarFuego(p, e);
            return;
        }
        e.bajoCielo = true;
        ponerCielo(p, e, r);
        // La ceniza solo a cielo abierto: dentro de una casa no tiene de donde caer.
        boolean expuesto = p.isInRain();
        if (expuesto) ceniza(p);

        int s = e.rojo.dentro;
        int cada = r.getInt("arde-cada-segundos", CIELO_CADA);
        int aviso = r.getInt("aviso-segundos", CIELO_AVISO);
        int dura = r.getInt("arde-segundos", CIELO_ARDE);
        if (avisaQuema(s, cada, aviso, dura)) {
            hc.cordura().destello(p, Component.text("El cielo arde", Paleta.FUEGO)
                    .append(Component.text(": te vas a quemar.", Paleta.TEXTO)), Math.max(1, aviso) + 1);
            p.playSound(p.getLocation(), "minecraft:item.firecharge.use", SoundCategory.HOSTILE, 0.7f, 0.6f);
        }
        // Con techo-protege, bajo techo no arde (isInRain mira la lluvia del servidor, que sigue ahi).
        boolean arde = arde(s, cada, aviso, dura) && (!r.getBoolean("techo-protege", false) || expuesto);
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
     * El cielo del jugador: sin lluvia y a la hora del cielo de sangre. Cada segundo se mira si
     * sigue siendo el nuestro y se repone si alguien (EDM, un /ptime) lo ha cambiado. El de la
     * PARCA manda entero; la hora del Eclipse, tambien.
     */
    private void ponerCielo(Player p, Estado e, ConfigurationSection r) {
        Parca parca = hc.parca();
        boolean deParca = parca != null && hc.valor("parca", () -> parca.cieloSobre(p), false);
        Eclipse eclipse = hc.eclipse();
        boolean deEclipse = eclipse != null && hc.valor("eclipse", eclipse::activo, false);

        if (r.getBoolean("ocultar-lluvia", true) && !deParca) {
            if (!e.climaPuesto || p.getPlayerWeather() != WeatherType.CLEAR) {
                p.setPlayerWeather(WeatherType.CLEAR);
                e.climaPuesto = true;
            }
        } else {
            soltarClima(p, e);
        }
        ponerHora(p, e, r, deParca, deEclipse);
    }

    /** La hora del cielo de sangre (sin tocar la lluvia): la del cielo rojo y, 1.11, la del spawn. */
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
        soltarClima(p, e);
        soltarHora(p, e);
        quitarFuego(p, e);
    }

    /** Devuelve la lluvia solo si sigue la nuestra: si la PARCA le ha puesto la suya encima, es suya. */
    private static void soltarClima(Player p, Estado e) {
        if (!e.climaPuesto) return;
        e.climaPuesto = false;
        if (p.isOnline() && p.getPlayerWeather() == WeatherType.CLEAR) p.resetPlayerWeather();
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

    /** El clima de ese bioma ("panacea/<id>", con o sin namespace) segun la config. */
    static Tipo tipo(String bioma, ConfigurationSection c) {
        if (bioma == null || bioma.isBlank()) return Tipo.NINGUNO;
        String b = clave(bioma);
        ConfigurationSection a = seccion(c, "lluvia-acida");
        if (a.getBoolean("activa", true) && contiene(a.isList("biomas") ? a.getStringList("biomas") : BIOMAS_VERDES, b)) {
            return Tipo.ACIDA;
        }
        ConfigurationSection r = seccion(c, "cielo-rojo");
        if (r.getBoolean("activo", true) && contiene(r.isList("biomas") ? r.getStringList("biomas") : BIOMAS_ROJOS, b)) {
            return Tipo.ROJO;
        }
        return Tipo.NINGUNO;
    }

    /** "Bracken:Panacea/Crimson_Organism " -> "panacea/crimson_organism": sin namespace ni mayusculas. */
    static String clave(String bioma) {
        String s = bioma.trim().toLowerCase(Locale.ROOT);
        int i = s.indexOf(':');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    private static boolean contiene(List<String> lista, String clave) {
        for (String s : lista) if (s != null && clave(s).equals(clave)) return true;
        return false;
    }

    // --------------------------------------------------------------- efectos

    /**
     * La lluvia acida que ve: gotas verdes cayendo alrededor, en lugar de la lluvia azul que se le
     * oculta (1.11: mas y mas repartidas, porque ahora son toda la lluvia que hay). Solo las ve el.
     */
    private void gotas(Player p) {
        particula(p, Compat.FALLING_DUST, p.getLocation().add(0, 6, 0), 40, 7, 2.5, 7, polvoAcido);
    }

    /** El acido sobre el: lo que se ve cuando le esta cayendo encima. */
    private void salpicadura(Player p) {
        particula(p, Compat.DUST, p.getLocation().add(0, 1.2, 0), 5, 0.5, 0.7, 0.5,
                Compat.dust(Paleta.ACIDO.value(), 0.9f));
    }

    /** Un chisporroteo suave cuando la lluvia le quema. */
    private void chisporroteo(Player p) {
        particula(p, Compat.SMOKE, p.getLocation().add(0, 1.0, 0), 4, 0.3, 0.5, 0.3, null);
        p.playSound(p.getLocation(), "minecraft:block.fire.extinguish", SoundCategory.PLAYERS, 0.25f, 1.8f);
    }

    /** Ceniza y polvo rojo cayendo mientras esta bajo el cielo rojo; solo lo ve el. */
    private void ceniza(Player p) {
        particula(p, Compat.FALLING_DUST, p.getLocation().add(0, 5, 0), 16, 5, 1.5, 5, polvoRojo);
        particula(p, Compat.DUST, p.getEyeLocation(), 6, 4, 2, 4, Compat.dust(Paleta.CIELO_ROJO, 1.2f));
    }

    private void llamas(Player p) {
        particula(p, Compat.FLAME, p.getLocation().add(0, 1.0, 0), 5, 0.3, 0.6, 0.3, null);
    }

    /** Una particula solo para ese jugador. Si cambia de nombre o de datos, no sale y ya. */
    private static void particula(Player p, Particle tipo, Location l, int n, double ox, double oy, double oz,
                                  Object datos) {
        if (tipo == null || l == null) return;
        try {
            Class<?> clase = tipo.getDataType();
            if (clase == Void.class) p.spawnParticle(tipo, l, n, ox, oy, oz, 0);
            else if (datos != null && clase.isInstance(datos)) p.spawnParticle(tipo, l, n, ox, oy, oz, 0, datos);
        } catch (Throwable ignorado) {
            // Una particula que falle no puede cortar el golpe ni el cielo.
        }
    }

    // ------------------------------------------------------------------ autotest

    /**
     * La logica pura (biomas, ritmos, horas, vineta y parte de defuncion) y que los valores de serie
     * del codigo sean los del config.yml del jar. 'jar' es la config por defecto del jar (la raiz);
     * con null se salta esa comparacion. No toca a ningun jugador ni la config del servidor.
     */
    static List<String> autotest(ConfigurationSection jar) {
        Autotest.Hoja h = new Autotest.Hoja();
        YamlConfiguration vacia = new YamlConfiguration();

        // Biomas: los verdes y el rojo de serie, con o sin namespace; los demas, nada.
        for (String b : BIOMAS_VERDES) h.igual(b + " es lluvia acida", Tipo.ACIDA, tipo(b, vacia));
        h.igual("crimson_organism es cielo rojo", Tipo.ROJO, tipo("panacea/crimson_organism", vacia));
        h.igual("con namespace tambien", Tipo.ROJO, tipo("bracken:panacea/crimson_organism", vacia));
        h.igual("condemned_taiga no tiene clima", Tipo.NINGUNO, tipo("panacea/condemned_taiga", vacia));
        h.igual("bamboo_valley no es verde de serie", Tipo.NINGUNO, tipo("panacea/bamboo_valley", vacia));
        h.igual("sin bioma, nada", Tipo.NINGUNO, tipo(null, vacia));
        YamlConfiguration apagada = new YamlConfiguration();
        apagada.set("lluvia-acida.activa", false);
        apagada.set("cielo-rojo.activo", false);
        h.igual("lluvia acida apagada", Tipo.NINGUNO, tipo("panacea/creeper_dominion", apagada));
        h.igual("cielo rojo apagado", Tipo.NINGUNO, tipo("panacea/crimson_organism", apagada));
        YamlConfiguration otra = new YamlConfiguration();
        otra.set("cielo-rojo.biomas", List.of(" Bracken:Panacea/Bamboo_Valley "));
        otra.set("lluvia-acida.biomas", List.of());
        h.igual("la lista de la config manda (namespace y mayusculas dan igual)", Tipo.ROJO,
                tipo("panacea/bamboo_valley", otra));
        h.igual("con otra lista, crimson ya no", Tipo.NINGUNO, tipo("panacea/crimson_organism", otra));
        h.igual("una lista vacia apaga esos biomas", Tipo.NINGUNO, tipo("panacea/creeper_dominion", otra));

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

        // El offset: el servidor manda base + offset y el timeline lo lee modulo 72000. Con el reloj
        // en el bloque k de 24000 (k = 0, 1, 2 y alguno lejano), lo que ve el jugador es el cielo de sangre.
        long[] relojes = {0L, 5_000L, 23_999L, 24_000L, 30_123L, 47_999L, 48_000L, 60_000L, 71_999L, 72_000L,
                96_500L, 1_000_000L, 123_456_789L};
        for (long reloj : relojes) {
            long off = offsetHora(reloj, CIELO_HORA, CIELO_PERIODO);
            h.igual("reloj " + reloj + ": ve el cielo de sangre", CIELO_HORA,
                    Math.floorMod(vistaServidor(reloj, off), CIELO_PERIODO));
            // Con la hora ya fija, getPlayerTime - offset es la base: da el mismo offset.
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
        h.ok("el offset viejo ya no es el nuestro tras pasar de bloque",
                !esHora(false, offsetHora(23_999L, CIELO_HORA, CIELO_PERIODO),
                        offsetHora(24_000L, CIELO_HORA, CIELO_PERIODO)));
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
            h.igual("jar: activo", true, c.getBoolean("activo", false));
            h.igual("jar: lluvia-acida.activa", true, c.getBoolean("lluvia-acida.activa", false));
            h.igual("jar: lluvia-acida.biomas", BIOMAS_VERDES, c.getStringList("lluvia-acida.biomas"));
            h.cerca("jar: lluvia-acida.dano", ACIDA_DANO, c.getDouble("lluvia-acida.dano", -1), 1e-9);
            h.igual("jar: lluvia-acida.cada-segundos", ACIDA_CADA, c.getInt("lluvia-acida.cada-segundos", -1));
            h.igual("jar: lluvia-acida.margen-segundos", ACIDA_MARGEN, c.getInt("lluvia-acida.margen-segundos", -1));
            h.igual("jar: lluvia-acida.olvido-segundos", ACIDA_OLVIDO, c.getInt("lluvia-acida.olvido-segundos", -1));
            h.igual("jar: cielo-rojo.activo", true, c.getBoolean("cielo-rojo.activo", false));
            h.igual("jar: cielo-rojo.biomas", BIOMAS_ROJOS, c.getStringList("cielo-rojo.biomas"));
            h.igual("jar: cielo-rojo.hora", CIELO_HORA, c.getLong("cielo-rojo.hora", -1));
            h.igual("jar: cielo-rojo.periodo-dia", CIELO_PERIODO, c.getLong("cielo-rojo.periodo-dia", -1));
            h.igual("jar: cielo-rojo.ocultar-lluvia", true, c.getBoolean("cielo-rojo.ocultar-lluvia", false));
            h.cerca("jar: cielo-rojo.vinheta", CIELO_VINETA, c.getDouble("cielo-rojo.vinheta", -1), 1e-9);
            h.igual("jar: cielo-rojo.arde-cada-segundos", CIELO_CADA, c.getInt("cielo-rojo.arde-cada-segundos", -1));
            h.igual("jar: cielo-rojo.arde-segundos", CIELO_ARDE, c.getInt("cielo-rojo.arde-segundos", -1));
            h.igual("jar: cielo-rojo.aviso-segundos", CIELO_AVISO, c.getInt("cielo-rojo.aviso-segundos", -1));
            h.cerca("jar: cielo-rojo.dano-por-segundo", CIELO_DANO, c.getDouble("cielo-rojo.dano-por-segundo", -1), 1e-9);
            h.igual("jar: cielo-rojo.techo-protege", false, c.getBoolean("cielo-rojo.techo-protege", true));
            h.igual("jar: cielo-rojo.olvido-segundos", CIELO_OLVIDO, c.getInt("cielo-rojo.olvido-segundos", -1));
        }
        return h.lineas();
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
