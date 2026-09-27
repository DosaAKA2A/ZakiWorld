package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * M13 · Eclipse de Calamidad (P2, apagado de serie: eclipse.activo false).
 *
 * Dos citas al dia (18:00 y 22:00 en hardcore.zona) de 20 minutos en las que Calamity se
 * vuelve peor y paga mas: noche para todos los de dentro, cordura x2, mobs +10 niveles,
 * Esencias y Reliquias x2 en las tiradas del Grifo y de los minijefes, PvP x1,25 y minijefe de
 * cordura cero cada 5 min. Existe para JUNTAR gente a la misma hora: el PvP de extraccion
 * no pasa si cada uno entra cuando le viene bien (DIS M13, "gancho").
 *
 * Esta clase no toca nada ajeno: los demas preguntan por su factor (Hardcore.drenar,
 * bonusNivel y minijefeSiTocaCordura; Grifo y Minijefes el botin; Combate el PvP) y sin
 * eclipse todos devuelven el neutro.
 *
 * La Reliquia Eclipsada (grado III, origen "eclipse") se ENTREGA al cumplir los 10 minutos
 * dentro durante el eclipse, no al salir: asi la cobra la Tasacion de siempre al cruzar la
 * puerta y, si mueres antes, se la queda tu Eco como cualquier otra. Esperar fuera y entrar
 * al final no la da (pide los 10 minutos dentro) y es una por eclipse y jugador.
 *
 * El estado vive en hardcore-datos.yml (eclipse.hasta y compania) para que un reinicio a
 * mitad no corte el eclipse ni regale una segunda Reliquia al mismo jugador.
 *
 * "/lw hardcore eclipse iniciar" funciona aunque eclipse.activo sea false: es la forma de
 * probarlo (y de montar uno a mano en un evento) sin encender el horario.
 */
final class Eclipse implements Listener {

    /** Rojo de la muerte de Calamity para la ceniza (particulas); barra y titulo van por la Paleta. */
    private static final int ROJO_RGB = 0x8B1A1A;
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    private final Hardcore hc;
    private final Reloj reloj;
    /** La barra roja de la cuenta atras; una sola para todos los de dentro. */
    private final BossBar barra = BossBar.bossBar(Paleta.muerte("Eclipse de Calamidad"),
            1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    /** Quien ve la barra ahora mismo (para quitarsela al salir o al acabar). */
    private final Set<UUID> conBarra = new HashSet<>();
    /** A quien le hemos fijado la noche con setPlayerTime (para devolverle su hora). */
    private final Set<UUID> conNoche = new HashSet<>();
    /** Avisos P-X01 ya dados: "<inicio>:<minutos>". Se vacia al empezar cada eclipse. */
    private final Set<String> avisados = new HashSet<>();
    /**
     * Horarios mal escritos ya avisados en consola (una vez por valor, no cada segundo).
     * Concurrente: el placeholder tambien lee el horario, desde el hilo de PlaceholderAPI.
     */
    private final Set<String> horarioAvisado = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private int segundos;

    Eclipse(Hardcore hc) {
        this.hc = hc;
        this.reloj = new Reloj(hc.datos());
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("eclipse", this::autotest);
        Subcomandos.lw().registrar("eclipse",
                "eclipse iniciar [minutos]|parar|info: el Eclipse de Calamidad a mano", "ederus.mundos",
                this::comando, args -> args.length == 2 ? List.of("iniciar", "parar", "info") : List.of());
        Subcomandos.calamity().registrar("eclipse", "cuándo es el próximo Eclipse", "lethalworld.calamity",
                (quien, args) -> quien.sendMessage(ComandoCalamity.mensaje(resumen(System.currentTimeMillis()))), null);
        PlaceholdersLethal.registrar("eclipse", (jugador, resto) -> placeholder(System.currentTimeMillis()));
        if (reloj.activo(System.currentTimeMillis())) {
            // Un reinicio a mitad: sigue donde estaba; el tick lo termina a su hora.
            hc.plugin().bitacora().anotar("eclipse", "reanuda", "hasta " + hora(reloj.hasta()),
                    "jugadores " + reloj.segundosPorJugador().size());
        }
    }

    // ----------------------------------------------------------- consultas (ganchos)

    /** Si hay un eclipse en curso. Puede llamarse desde otro hilo (placeholder): solo lee. */
    boolean activo() {
        return reloj.activo(System.currentTimeMillis());
    }

    /** Multiplica el drenaje de cordura (Hardcore.drenar). */
    double factorCordura() {
        return factorCordura(activo(), hc.cfg());
    }

    /** Niveles de mas para los mobs (Hardcore.bonusNivel). */
    int nivelesExtra() {
        return nivelesExtra(activo(), hc.cfg());
    }

    /** Multiplica la probabilidad de Esencias y Reliquias (Grifo, Minijefes). No las MobCoins. */
    double factorBotin() {
        return factorBotin(activo(), hc.cfg());
    }

    /** Multiplica el dano PvP (Combate.alPvp, detras del Frenesi). */
    double factorPvp() {
        return factorPvp(activo(), hc.cfg());
    }

    /** Minutos entre minijefes de cordura cero; def fuera del eclipse. */
    int minutosMinijefe(int def) {
        return minutosMinijefe(activo(), hc.cfg(), def);
    }

    /** Multiplica el radio al que despierta un Eco (lo lee Ecos.tick). */
    double factorDespertarEcos() {
        return activo() ? Math.max(1.0, hc.cfg().getDouble("eclipse.ecos-despertar", 2.0)) : 1.0;
    }

    /** Intensidad de mas para la vineta de cordura de M21 (la lee Sentidos). */
    double vinetaExtra() {
        return activo() ? Math.max(0.0, hc.cfg().getDouble("eclipse.vinheta-extra", 0.2)) : 0.0;
    }

    static double factorCordura(boolean activo, ConfigurationSection c) {
        return activo ? Math.max(0.0, c.getDouble("eclipse.cordura", 2.0)) : 1.0;
    }

    static int nivelesExtra(boolean activo, ConfigurationSection c) {
        return activo ? Math.max(0, c.getInt("eclipse.niveles", 10)) : 0;
    }

    static double factorBotin(boolean activo, ConfigurationSection c) {
        return activo ? Math.max(0.0, c.getDouble("eclipse.botin", 2.0)) : 1.0;
    }

    static double factorPvp(boolean activo, ConfigurationSection c) {
        return activo ? Math.max(0.0, c.getDouble("eclipse.pvp", 1.25)) : 1.0;
    }

    /**
     * Con eclipse, el menor de los dos: si Dosa baja minijefes.cada-minutos por debajo de 5,
     * el eclipse no puede hacer que vengan MENOS minijefes que fuera de el.
     */
    static int minutosMinijefe(boolean activo, ConfigurationSection c, int def) {
        if (!activo) return def;
        int cada = c.getInt("eclipse.minijefe-cada-minutos", 5);
        return cada <= 0 ? def : Math.min(def, cada);
    }

    // --------------------------------------------------------------------- ganchos

    /** Al entrar por la puerta (Hardcore.meter): con eclipse, lo nota desde el primer paso. */
    void alEntrar(Player p) {
        long ahora = System.currentTimeMillis();
        if (!reloj.activo(ahora)) return;
        ponerNoche(p);
        verBarra(p, ahora);
        p.showTitle(titulo(reloj.hasta() - ahora));
    }

    /** Una vez por segundo desde Hardcore.tick. */
    void tick() {
        long ahora = System.currentTimeMillis();
        segundos++;
        if (reloj.hasta() > 0 && ahora >= reloj.hasta()) {
            terminar("hora", ahora);
            return;
        }
        if (reloj.activo(ahora)) {
            durante(ahora);
            return;
        }
        if (!hc.cfg().getBoolean("eclipse.activo", false)) return;
        List<LocalTime> horario = horario();
        if (horario.isEmpty()) return;
        long duracion = duracionMillis();
        ZoneId zona = zona();
        long enCurso = inicioEnCurso(horario, zona, duracion, ahora);
        // atendido = el inicio programado que ya se hizo (o se paro a mano): no se repite.
        if (enCurso > 0 && enCurso != reloj.atendido()) {
            reloj.atender(enCurso);
            iniciar(ahora, enCurso + duracion, "horario");
            return;
        }
        avisar(proximoInicio(horario, zona, ahora), ahora);
    }

    void parar() {
        HandlerList.unregisterAll(this);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (conBarra.contains(p.getUniqueId())) p.hideBossBar(barra);
            if (conNoche.contains(p.getUniqueId())) p.resetPlayerTime();
        }
        conBarra.clear();
        conNoche.clear();
        avisados.clear();
        // eclipse.hasta se queda en datos: al arrancar, si no ha pasado la hora, sigue.
        hc.marcarSucio();
    }

    // -------------------------------------------------------------- inicio y fin

    /** Empieza un eclipse que dura hasta 'hasta'. motivo: horario o admin:<nombre>. */
    private void iniciar(long ahora, long hasta, String motivo) {
        reloj.iniciar(ahora, hasta);
        reloj.podarSalidas(ahora);
        avisados.clear();
        // Sincrono: si el servidor cae en el minuto siguiente, el eclipse tiene que seguir al volver.
        hc.guardarYa();
        Title titulo = titulo(hasta - ahora);
        int dentro = 0;
        for (Player p : dentro()) {
            dentro++;
            p.playSound(p.getLocation(), "minecraft:event.raid.horn", SoundCategory.HOSTILE, 2.0f, 1.0f);
            p.showTitle(titulo);
            ponerNoche(p);
            verBarra(p, ahora);
        }
        long minutos = minutosQuedan(hasta - ahora);
        hc.plugin().bitacora().anotar("eclipse", "inicio", motivo, "hasta " + hora(hasta), "min " + minutos,
                "dentro " + dentro);
        suceso("inicio", null, Map.of("motivo", motivo, "minutos", minutos, "dentro", dentro));
    }

    /** Acaba el eclipse: hora de los de dentro, barra fuera, P-X03 a todo Calamity. */
    private void terminar(String motivo, long ahora) {
        int jugadores = reloj.segundosPorJugador().size();
        int reliquias = reloj.ganadas().size();
        // El hueco programado en el que estemos queda atendido: un eclipse a mano que acaba a
        // las 18:10 no puede hacer que el de las 18:00 arranque entonces por su cuenta.
        List<LocalTime> horario = horario();
        long enCurso = horario.isEmpty() ? -1 : inicioEnCurso(horario, zona(), duracionMillis(), ahora);
        if (enCurso > 0) reloj.atender(enCurso);
        reloj.terminar();
        hc.guardarYa();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (conBarra.contains(p.getUniqueId())) p.hideBossBar(barra);
            if (conNoche.contains(p.getUniqueId())) p.resetPlayerTime();
        }
        conBarra.clear();
        conNoche.clear();
        Component fin = ComandoCalamity.mensaje("El eclipse pasa. Lo que no hayas sacado sigue sin ser tuyo.");
        for (Player p : dentro()) {
            p.sendMessage(fin);
            p.playSound(p.getLocation(), "minecraft:block.beacon.deactivate", SoundCategory.HOSTILE, 1.0f, 0.6f);
        }
        hc.plugin().bitacora().anotar("eclipse", "fin", motivo, "jugadores " + jugadores, "reliquias " + reliquias);
        suceso("fin", null, Map.of("motivo", motivo, "jugadores", jugadores, "reliquias", reliquias));
    }

    /** Cada segundo del eclipse: cuenta atras, noche, ceniza roja y los 10 minutos de cada uno. */
    private void durante(long ahora) {
        actualizarBarra(ahora);
        int minimos = Math.max(1, hc.cfg().getInt("eclipse.minutos-minimos", 10)) * 60;
        Set<UUID> vistos = new HashSet<>();
        boolean cambio = false;
        for (Player p : dentro()) {
            UUID u = p.getUniqueId();
            vistos.add(u);
            verBarra(p, ahora);
            // Cada 30 s se vuelve a fijar: otro plugin (o /ptime) puede haberle movido la hora.
            if (!conNoche.contains(u) || segundos % 30 == 0) ponerNoche(p);
            ceniza(p);
            if (!hc.cuenta(p) || p.isDead()) continue;
            cambio = true;
            if (reloj.sumarSegundo(u, minimos)) darReliquia(p);
        }
        // Quien ya no esta dentro por una via que no avisa (murio y reaparecio fuera): sin barra.
        conBarra.removeIf(u -> {
            if (vistos.contains(u)) return false;
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.hideBossBar(barra);
            return true;
        });
        // Los segundos de cada uno van a datos en memoria; a disco, con el volcado del minuto.
        if (cambio && segundos % 30 == 0) hc.marcarSucio();
    }

    /**
     * La Reliquia Eclipsada: una por eclipse y jugador, al cumplir los minutos minimos. La
     * entrega la Aduana (regla 7: nadie mete botin en un inventario por su cuenta).
     */
    private void darReliquia(Player p) {
        UUID u = p.getUniqueId();
        long limite = reloj.hasta() + Math.max(0, hc.cfg().getInt("eclipse.ventana-despues", 10)) * 60_000L;
        reloj.apuntarSalida(u, limite);
        // Sincrono: "ganadas" tiene que estar en disco antes que el objeto, o un reinicio la repite.
        hc.guardarYa();
        Reliquias rel = hc.reliquias();
        Aduana ad = hc.aduana();
        if (rel == null || ad == null || !rel.activas()) {
            hc.plugin().bitacora().anotar("eclipse", "sin-reliquia", p.getName(),
                    rel == null || ad == null ? "sin modulo" : "reliquias apagadas");
            return;
        }
        int grado = Math.max(1, Math.min(4, hc.cfg().getInt("eclipse.reliquia-grado", 3)));
        ItemStack r = rel.crear(grado, "eclipse", Reliquias.ECLIPSADA, 0, null, false);
        ad.pagar(p, "eclipse", 0, 0, List.of(r), "reliquia eclipsada");
        hc.cordura().destello(p, Component.text("Reliquia Eclipsada · sácala viva", Reliquias.AMBAR), 3);
        p.playSound(p.getLocation(), "minecraft:block.respawn_anchor.charge", SoundCategory.PLAYERS, 0.8f, 0.7f);
        hc.plugin().bitacora().anotar("eclipse", "reliquia", p.getName(), String.valueOf(rel.id(r)));
        suceso("reliquia", p, Map.of("grado", grado));
    }

    // --------------------------------------------------------------------- avisos

    /** P-X01 a todo el servidor a los minutos de avisos-minutos, una vez cada uno. */
    private void avisar(long proximo, long ahora) {
        if (proximo <= 0) return;
        List<Integer> avisos = hc.cfg().isList("eclipse.avisos-minutos")
                ? hc.cfg().getIntegerList("eclipse.avisos-minutos") : List.of(10, 5, 1);
        int n = avisoToca(proximo - ahora, avisos);
        if (n <= 0 || !avisados.add(proximo + ":" + n)) return;
        Bukkit.broadcast(ComandoCalamity.mensaje("Eclipse de Calamidad en " + n + (n == 1 ? " minuto" : " minutos")
                + ". Lo que ganes dentro vale el doble. Lo que pierdas, también."));
        hc.plugin().bitacora().anotar("eclipse", "aviso", n + " min", "inicio " + hora(proximo));
    }

    /**
     * El aviso que toca con 'falta' millis para el inicio: n si falta esta en (n-1, n] minutos,
     * 0 si ninguno. Con una franja de un minuto y no un segundo exacto, un tick perdido (lag,
     * reinicio) no se come el aviso; el conjunto 'avisados' impide repetirlo.
     */
    static int avisoToca(long falta, List<Integer> avisos) {
        if (falta <= 0) return 0;
        for (Integer n : avisos) {
            if (n == null || n <= 0) continue;
            long borde = n * 60_000L;
            if (falta <= borde && falta > borde - 60_000L) return n;
        }
        return 0;
    }

    // ------------------------------------------------------------------- jugador

    private void ponerNoche(Player p) {
        // Medianoche fija y no relativa: el cielo no avanza durante el eclipse.
        p.setPlayerTime(18_000L, false);
        conNoche.add(p.getUniqueId());
    }

    private void verBarra(Player p, long ahora) {
        if (conBarra.add(p.getUniqueId())) {
            actualizarBarra(ahora);
            p.showBossBar(barra);
        }
    }

    private void actualizarBarra(long ahora) {
        long queda = Math.max(0, reloj.hasta() - ahora);
        long total = Math.max(1, reloj.hasta() - reloj.inicio());
        barra.name(Paleta.muerte("Eclipse de Calamidad").append(Component.text(" · ", Paleta.SEPARADOR))
                .append(Component.text(cuenta(queda / 1000), Paleta.CIFRA)));
        barra.progress((float) Math.max(0.0, Math.min(1.0, (double) queda / total)));
    }

    /** P-X02: "ECLIPSE / <n> minutos". */
    private static Title titulo(long quedaMillis) {
        long n = minutosQuedan(quedaMillis);
        return Title.title(Paleta.muerte("ECLIPSE"),
                Component.text(n + (n == 1 ? " minuto" : " minutos"), Paleta.TEXTO));
    }

    private static long minutosQuedan(long quedaMillis) {
        return Math.max(1, (quedaMillis + 59_999) / 60_000);
    }

    /** La ceniza roja del eclipse, ademas de la gris de la noche (DIS M13). */
    private void ceniza(Player p) {
        Compat.spawn(p.getWorld(), Compat.DUST, p.getEyeLocation(), 8, 4.0, 3.0, 4.0, 0,
                Compat.dust(ROJO_RGB, 1.3f));
    }

    /** Los que estan en un mundo hardcore ahora mismo (espectadores incluidos: ven la barra). */
    private List<Player> dentro() {
        List<Player> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            if (hc.esHardcore(w)) out.addAll(w.getPlayers());
        }
        return out;
    }

    /**
     * Sale de Calamity: se le devuelve su hora y su pantalla. Si gano la Reliquia y sale vivo
     * a tiempo (durante el eclipse o en los ventana-despues minutos), cuenta un eclipse para
     * el hito [ECLIPSADO]. Morir borra la salida pendiente antes (onMuerte), asi que reaparecer
     * fuera no cuenta como salir vivo.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCambiarMundo(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (!hc.esHardcore(e.getFrom()) || hc.esHardcore(p)) return;
        UUID u = p.getUniqueId();
        if (conBarra.remove(u)) p.hideBossBar(barra);
        if (conNoche.remove(u)) p.resetPlayerTime();
        long limite = reloj.quitarSalida(u);
        if (limite <= 0) return;
        hc.marcarSucio();
        if (!cuentaSalida(System.currentTimeMillis(), limite)) return;
        Estadisticas st = hc.estadisticas();
        if (st != null) hc.seguro("estadisticas", () -> st.sumar(u, "eclipses", 1));
        hc.plugin().bitacora().anotar("eclipse", "sale", p.getName());
        suceso("sale", p, Map.of());
    }

    /** Morir dentro: la Reliquia se la queda el Eco y el eclipse no cuenta como superado. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMuerte(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!hc.esHardcore(p)) return;
        if (reloj.quitarSalida(p.getUniqueId()) > 0) hc.marcarSucio();
    }

    @EventHandler
    public void onSalir(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        // La hora del jugador no se guarda al desconectar; al volver dentro se le fija otra vez.
        conBarra.remove(u);
        conNoche.remove(u);
    }

    static boolean cuentaSalida(long ahora, long limite) {
        return limite > 0 && ahora <= limite;
    }

    // -------------------------------------------------------------------- horario

    private List<LocalTime> horario() {
        List<String> crudo = hc.cfg().isList("eclipse.horario") ? hc.cfg().getStringList("eclipse.horario")
                : List.of("18:00", "22:00");
        List<LocalTime> out = new ArrayList<>();
        for (String s : crudo) {
            LocalTime t = leerHora(s);
            if (t != null) out.add(t);
            else if (horarioAvisado.add(String.valueOf(s))) {
                hc.plugin().getLogger().warning("[Calamity] eclipse.horario: \"" + s + "\" no es una hora HH:mm; se ignora.");
            }
        }
        return out;
    }

    /** "18:00" o "9:30"; null si no es una hora. */
    static LocalTime leerHora(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        try {
            return LocalTime.parse(t.length() == 4 ? "0" + t : t);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private long duracionMillis() {
        return Math.max(1, hc.cfg().getInt("eclipse.minutos", 20)) * 60_000L;
    }

    private ZoneId zona() {
        Calendario c = hc.calendario();
        return c == null ? ZoneId.systemDefault() : c.zona();
    }

    /**
     * Los inicios programados alrededor de 'ahora' (ayer, hoy y manana en la zona), para que
     * un eclipse de las 23:50 siga "en curso" a las 00:05 del dia siguiente.
     */
    private static List<Long> inicios(List<LocalTime> horario, ZoneId zona, long ahora) {
        LocalDate hoy = Instant.ofEpochMilli(ahora).atZone(zona).toLocalDate();
        List<Long> out = new ArrayList<>();
        for (int d = -1; d <= 1; d++) {
            LocalDate dia = hoy.plusDays(d);
            for (LocalTime t : horario) out.add(dia.atTime(t).atZone(zona).toInstant().toEpochMilli());
        }
        return out;
    }

    /** El inicio programado cuyo eclipse abarca 'ahora', o -1. */
    static long inicioEnCurso(List<LocalTime> horario, ZoneId zona, long duracion, long ahora) {
        long mejor = -1;
        for (long s : inicios(horario, zona, ahora)) {
            if (s <= ahora && ahora < s + duracion && s > mejor) mejor = s;
        }
        return mejor;
    }

    /** El siguiente inicio programado estrictamente despues de 'ahora', o -1 sin horario. */
    static long proximoInicio(List<LocalTime> horario, ZoneId zona, long ahora) {
        long mejor = -1;
        for (long s : inicios(horario, zona, ahora)) {
            if (s > ahora && (mejor < 0 || s < mejor)) mejor = s;
        }
        return mejor;
    }

    // ------------------------------------------------------- comando y placeholder

    /** /lw hardcore eclipse iniciar [minutos] | parar | info. */
    private void comando(CommandSender quien, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "info";
        long ahora = System.currentTimeMillis();
        switch (sub) {
            case "iniciar" -> {
                if (reloj.activo(ahora)) {
                    quien.sendMessage(ComandoCalamity.mensaje("Ya hay un eclipse. Acaba a las " + hora(reloj.hasta()) + "."));
                    return;
                }
                int minutos = Math.max(1, hc.cfg().getInt("eclipse.minutos", 20));
                if (args.length >= 3) {
                    try {
                        minutos = Math.max(1, Math.min(240, Integer.parseInt(args[2])));
                    } catch (NumberFormatException e) {
                        quien.sendMessage(ComandoCalamity.mensaje("Los minutos son un número."));
                        return;
                    }
                }
                iniciar(ahora, ahora + minutos * 60_000L, "admin:" + quien.getName());
                quien.sendMessage(ComandoCalamity.mensaje("Eclipse de " + minutos + " min, hasta las "
                        + hora(reloj.hasta()) + (hc.cfg().getBoolean("eclipse.activo", false) ? "."
                        : ". El horario sigue apagado (eclipse.activo: false).")));
            }
            case "parar" -> {
                if (!reloj.activo(ahora)) {
                    quien.sendMessage(ComandoCalamity.mensaje("No hay ningún eclipse en curso."));
                    return;
                }
                terminar("admin:" + quien.getName(), ahora);
                quien.sendMessage(ComandoCalamity.mensaje("Eclipse terminado."));
            }
            case "info" -> {
                quien.sendMessage(ComandoCalamity.mensaje(resumen(ahora)));
                if (reloj.activo(ahora)) {
                    quien.sendMessage(ComandoCalamity.mensaje("En este eclipse: " + reloj.segundosPorJugador().size()
                            + " jugadores dentro, " + reloj.ganadas().size() + " Reliquias entregadas. Cordura ×"
                            + factorCordura() + ", +" + nivelesExtra() + " niveles, botín ×" + factorBotin()
                            + ", PvP ×" + factorPvp() + "."));
                }
            }
            default -> quien.sendMessage(ComandoCalamity.mensaje("Uso: /lw hardcore eclipse iniciar [minutos]|parar|info"));
        }
    }

    /** Una frase para /calamity eclipse y /lw hardcore eclipse info. */
    private String resumen(long ahora) {
        if (reloj.activo(ahora)) {
            return "Eclipse de Calamidad en curso. Quedan " + cuenta((reloj.hasta() - ahora) / 1000) + ".";
        }
        if (!hc.cfg().getBoolean("eclipse.activo", false)) return "No hay eclipses programados.";
        long prox = proximoInicio(horario(), zona(), ahora);
        if (prox <= 0) return "No hay eclipses programados.";
        return "Próximo Eclipse de Calamidad a las " + hora(prox) + " (en " + cuenta((prox - ahora) / 1000) + ").";
    }

    /**
     * %lethalworld_eclipse%: "activo" o la cuenta hasta el proximo; vacio sin horario. Puede
     * llamarse desde otro hilo: solo lee la config y el reloj (hasta es volatile).
     */
    private String placeholder(long ahora) {
        if (reloj.activo(ahora)) return "activo";
        if (!hc.cfg().getBoolean("eclipse.activo", false)) return "";
        long prox = proximoInicio(horario(), zona(), ahora);
        return prox <= 0 ? "" : cuenta((prox - ahora) / 1000);
    }

    /** mm:ss; con una hora o mas, h:mm:ss (a las 09:00 faltan nueve horas para las 18:00). */
    static String cuenta(long segundosTotales) {
        long s = Math.max(0, segundosTotales);
        long h = s / 3600, m = (s % 3600) / 60, seg = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, seg) : String.format("%02d:%02d", m, seg);
    }

    private String hora(long millis) {
        return HORA.format(Instant.ofEpochMilli(millis).atZone(zona()));
    }

    private void suceso(String fase, OfflinePlayer quien, Map<String, Object> extra) {
        Telemetria t = hc.telemetria();
        if (t == null) return;
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("fase", fase);
        campos.putAll(extra);
        hc.seguro("telemetria", () -> t.suceso("eclipse", quien, campos));
    }

    // -------------------------------------------------------------------- estado

    /**
     * El estado del eclipse sobre una YamlConfiguration: la de hardcore-datos.yml en marcha y
     * una en memoria en el autotest (que nunca escribe en los datos reales).
     *
     * eclipse.hasta / eclipse.inicio: el eclipse en curso (solo existen mientras dura).
     * eclipse.segundos.<uuid>: segundos dentro durante ESTE eclipse.
     * eclipse.ganadas: quien ya tiene su Reliquia Eclipsada de este eclipse (no se repite
     *   aunque el servidor reinicie a mitad).
     * eclipse.atendido: el ultimo inicio programado ya hecho (o parado a mano).
     * eclipse.salidas.<uuid>: hasta cuando cuenta su salida viva para stats.eclipses.
     */
    static final class Reloj {

        private final YamlConfiguration datos;
        /** volatile: el placeholder pregunta activo() desde el hilo de PlaceholderAPI. */
        private volatile long hasta;
        private long inicio;
        private final Map<UUID, Integer> segundos = new LinkedHashMap<>();
        private final Set<UUID> ganadas = new HashSet<>();

        Reloj(YamlConfiguration datos) {
            this.datos = datos;
            this.hasta = datos.getLong("eclipse.hasta", 0);
            this.inicio = datos.getLong("eclipse.inicio", 0);
            if (hasta > 0 && inicio <= 0) inicio = hasta - 20 * 60_000L;
            ConfigurationSection s = datos.getConfigurationSection("eclipse.segundos");
            if (s != null) {
                for (String k : s.getKeys(false)) {
                    UUID u = uuid(k);
                    if (u != null) segundos.put(u, s.getInt(k));
                }
            }
            for (String k : datos.getStringList("eclipse.ganadas")) {
                UUID u = uuid(k);
                if (u != null) ganadas.add(u);
            }
        }

        long hasta() {
            return hasta;
        }

        long inicio() {
            return inicio;
        }

        boolean activo(long ahora) {
            long h = hasta;
            return h > 0 && ahora < h;
        }

        long atendido() {
            return datos.getLong("eclipse.atendido", 0);
        }

        void atender(long inicioProgramado) {
            datos.set("eclipse.atendido", inicioProgramado);
        }

        void iniciar(long ahora, long hastaNuevo) {
            inicio = ahora;
            segundos.clear();
            ganadas.clear();
            datos.set("eclipse.inicio", inicio);
            datos.set("eclipse.hasta", hastaNuevo);
            datos.set("eclipse.segundos", null);
            datos.set("eclipse.ganadas", null);
            hasta = hastaNuevo;
        }

        void terminar() {
            hasta = 0;
            inicio = 0;
            segundos.clear();
            ganadas.clear();
            datos.set("eclipse.hasta", null);
            datos.set("eclipse.inicio", null);
            datos.set("eclipse.segundos", null);
            datos.set("eclipse.ganadas", null);
        }

        /**
         * Un segundo mas dentro para u. true SOLO la vez que llega a 'minimos' sin tener aun la
         * Reliquia: ese es el momento de entregarla, una vez por eclipse.
         */
        boolean sumarSegundo(UUID u, int minimos) {
            int n = segundos.merge(u, 1, Integer::sum);
            datos.set("eclipse.segundos." + u, n);
            if (n < minimos || ganadas.contains(u)) return false;
            ganadas.add(u);
            List<String> lista = new ArrayList<>();
            for (UUID g : ganadas) lista.add(g.toString());
            datos.set("eclipse.ganadas", lista);
            return true;
        }

        Map<UUID, Integer> segundosPorJugador() {
            return segundos;
        }

        Set<UUID> ganadas() {
            return ganadas;
        }

        void apuntarSalida(UUID u, long limite) {
            datos.set("eclipse.salidas." + u, limite);
        }

        /** Quita la salida pendiente de u y devuelve su limite (0 si no tenia). */
        long quitarSalida(UUID u) {
            String ruta = "eclipse.salidas." + u;
            long l = datos.getLong(ruta, 0);
            if (l > 0) datos.set(ruta, null);
            return l;
        }

        /** Las salidas que ya no pueden contar no se arrastran de un eclipse a otro. */
        void podarSalidas(long ahora) {
            ConfigurationSection s = datos.getConfigurationSection("eclipse.salidas");
            if (s == null) return;
            for (String k : new ArrayList<>(s.getKeys(false))) {
                if (s.getLong(k, 0) < ahora) s.set(k, null);
            }
        }

        private static UUID uuid(String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        ZoneId madrid = ZoneId.of("Europe/Madrid");
        List<LocalTime> serie = List.of(LocalTime.of(18, 0), LocalTime.of(22, 0));
        long dur = 20 * 60_000L;
        // Sabado 26/09/2026, horario de verano en Madrid (UTC+2): 18:00 en Madrid = 16:00Z.
        long a18 = Instant.parse("2026-09-26T16:00:00Z").toEpochMilli();
        long a22 = Instant.parse("2026-09-26T20:00:00Z").toEpochMilli();
        long manana18 = Instant.parse("2026-09-27T16:00:00Z").toEpochMilli();

        h.igual("17:59:59 en Madrid: sin eclipse", -1L, inicioEnCurso(serie, madrid, dur, a18 - 1000));
        h.igual("17:59:59 en Madrid: el proximo a las 18:00", a18, proximoInicio(serie, madrid, a18 - 1000));
        h.igual("18:00 en Madrid: empieza", a18, inicioEnCurso(serie, madrid, dur, a18));
        h.igual("18:19:59 en Madrid: sigue", a18, inicioEnCurso(serie, madrid, dur, a18 + dur - 1000));
        h.igual("18:20 en Madrid: se acabo", -1L, inicioEnCurso(serie, madrid, dur, a18 + dur));
        h.igual("18:20 en Madrid: el proximo a las 22:00", a22, proximoInicio(serie, madrid, a18 + dur));
        h.igual("22:10 en Madrid: en curso el de las 22:00", a22, inicioEnCurso(serie, madrid, dur, a22 + 10 * 60_000L));
        h.igual("22:30 en Madrid: el proximo manana a las 18:00", manana18,
                proximoInicio(serie, madrid, a22 + 30 * 60_000L));
        h.igual("16:00Z en UTC no es eclipse (las 18:00 son en Madrid)", -1L,
                inicioEnCurso(serie, ZoneOffset.UTC, dur, a18));
        h.igual("en UTC el proximo es a las 18:00Z", Instant.parse("2026-09-26T18:00:00Z").toEpochMilli(),
                proximoInicio(serie, ZoneOffset.UTC, a18));
        long a2350 = Instant.parse("2026-09-26T21:50:00Z").toEpochMilli();
        h.igual("uno de las 23:50 sigue a las 00:05", a2350, inicioEnCurso(List.of(LocalTime.of(23, 50)), madrid, dur,
                Instant.parse("2026-09-26T22:05:00Z").toEpochMilli()));
        h.igual("hora \"18:00\" se lee", LocalTime.of(18, 0), leerHora("18:00"));
        h.igual("hora \"9:30\" se lee", LocalTime.of(9, 30), leerHora("9:30"));
        h.ok("hora \"25:99\" se ignora", leerHora("25:99") == null);

        // Avisos 10, 5, 1: franja de un minuto, ninguno fuera.
        List<Integer> avisos = List.of(10, 5, 1);
        h.igual("aviso a 10:00", 10, avisoToca(10 * 60_000L, avisos));
        h.igual("aviso a 5:00", 5, avisoToca(5 * 60_000L, avisos));
        h.igual("aviso a 0:30", 1, avisoToca(30_000L, avisos));
        h.igual("sin aviso a 7:00", 0, avisoToca(7 * 60_000L, avisos));
        h.igual("sin aviso ya empezado", 0, avisoToca(0, avisos));

        // Inicio y fin sobre datos en memoria; un "reinicio" es otro Reloj sobre el mismo yml.
        YamlConfiguration yml = new YamlConfiguration();
        Reloj r = new Reloj(yml);
        long ahora = a18;
        r.iniciar(ahora, ahora + dur);
        h.igual("iniciar escribe eclipse.hasta", ahora + dur, yml.getLong("eclipse.hasta", 0));
        h.ok("iniciado: activo", r.activo(ahora + 1000));
        UUID u = Autotest.sintetico(1);
        int minimos = 600;
        boolean antes = false;
        for (int i = 1; i < minimos; i++) antes |= r.sumarSegundo(u, minimos);
        h.ok("antes de 10 min no hay Reliquia", !antes);
        h.ok("a los 10 min, la Reliquia", r.sumarSegundo(u, minimos));
        h.ok("el segundo 601 no da otra", !r.sumarSegundo(u, minimos));
        Reloj tras = new Reloj(yml);
        h.ok("reinicio a mitad: sigue activo", tras.activo(ahora + 5 * 60_000L));
        h.igual("reinicio a mitad: misma hora de fin", ahora + dur, tras.hasta());
        h.igual("reinicio a mitad: conserva los segundos", 601, tras.segundosPorJugador().get(u));
        h.ok("reinicio a mitad: no da otra Reliquia", !tras.sumarSegundo(u, minimos));
        h.ok("pasada la hora: no activo", !tras.activo(ahora + dur));
        tras.apuntarSalida(u, ahora + dur + 10 * 60_000L);
        tras.terminar();
        h.ok("terminar borra eclipse.hasta", !yml.isSet("eclipse.hasta"));
        h.ok("terminar borra los segundos", !yml.isSet("eclipse.segundos"));
        h.ok("terminado: no activo", !tras.activo(ahora + 1000));
        h.ok("sin eclipse al cargar: no activo", !new Reloj(yml).activo(ahora));
        h.ok("la salida viva sigue apuntada tras el fin", yml.getLong("eclipse.salidas." + u, 0) > 0);
        h.ok("salir a tiempo cuenta", cuentaSalida(ahora + dur + 5 * 60_000L, ahora + dur + 10 * 60_000L));
        h.ok("salir tarde no cuenta", !cuentaSalida(ahora + dur + 11 * 60_000L, ahora + dur + 10 * 60_000L));
        h.igual("quitar la salida devuelve el limite", ahora + dur + 10 * 60_000L, tras.quitarSalida(u));
        h.igual("y no se cuenta dos veces", 0L, tras.quitarSalida(u));
        tras.apuntarSalida(u, ahora - 1);
        tras.podarSalidas(ahora);
        h.ok("las salidas caducadas se podan", !yml.isSet("eclipse.salidas." + u));

        // Factores sin eclipse (neutros) y con los de serie (DIS sec. 4).
        YamlConfiguration vacia = new YamlConfiguration();
        h.cerca("sin eclipse: cordura x1", 1.0, factorCordura(false, vacia), 1e-9);
        h.igual("sin eclipse: +0 niveles", 0, nivelesExtra(false, vacia));
        h.cerca("sin eclipse: botin x1", 1.0, factorBotin(false, vacia), 1e-9);
        h.cerca("sin eclipse: PvP x1", 1.0, factorPvp(false, vacia), 1e-9);
        h.igual("sin eclipse: minijefe cada 10", 10, minutosMinijefe(false, vacia, 10));
        h.cerca("con eclipse: cordura x2", 2.0, factorCordura(true, vacia), 1e-9);
        h.igual("con eclipse: +10 niveles", 10, nivelesExtra(true, vacia));
        h.cerca("con eclipse: botin x2", 2.0, factorBotin(true, vacia), 1e-9);
        h.cerca("con eclipse: PvP x1,25", 1.25, factorPvp(true, vacia), 1e-9);
        h.igual("con eclipse: minijefe cada 5", 5, minutosMinijefe(true, vacia, 10));
        h.igual("con eclipse: nunca menos minijefes que fuera", 3, minutosMinijefe(true, vacia, 3));
        YamlConfiguration otra = new YamlConfiguration();
        otra.set("eclipse.cordura", 3.0);
        otra.set("eclipse.niveles", 15);
        h.cerca("la config manda: cordura x3", 3.0, factorCordura(true, otra), 1e-9);
        h.igual("la config manda: +15 niveles", 15, nivelesExtra(true, otra));
        h.ok("apagado de serie", !vacia.getBoolean("eclipse.activo", false));

        h.igual("cuenta 125 s", "02:05", cuenta(125));
        h.igual("cuenta 3725 s", "1:02:05", cuenta(3725));
        h.sinExcepcion("el placeholder sin jugador no revienta", () -> PlaceholdersLethal.resolver(null, "eclipse"));
        return h.lineas();
    }
}
