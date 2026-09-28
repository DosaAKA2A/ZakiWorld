package net.ederus.edm.boost;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;
import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.MobCoins;
import net.ederus.edm.comun.Textos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Boosts temporales: experiencia, experiencia de habilidades, suerte de pesca,
 * MobCoins y minions.
 *
 * Nacio para las cajas de OneBlock, que repartian "boosters" que no hacian nada. Ahora
 * la caja llama a `boost give <jugador> <tipo> <minutos>` y el boost existe de verdad,
 * se ve en /boost, avisa cuando se acaba y sobrevive a un reinicio.
 *
 * Reglas de diseno, todas pensadas para que no haya sorpresas:
 *   - el multiplicador es el MAYOR entre el personal y el global, nunca el producto;
 *   - cada tipo tiene un tope (multiplicador-maximo) que no se pasa ni por comando;
 *   - el tiempo se guarda como instante de fin, asi que reiniciar no regala minutos;
 *   - dos boosts iguales SUMAN tiempo en vez de pisarse;
 *   - ningun boost multiplica objetos que el jugador pueda soltar y volver a coger:
 *     el de drops se quito en EDM 1.74.0 porque se uso para duplicar;
 *   - un tipo cuyo plugin falta (AuraSkills, PremioPescao, LitMinions) sale como "no
 *     disponible", no se reparte y no rompe nada.
 */
public final class BoostPlugin extends Module {

    /** El modulo en marcha, para {@link BoostApi}. null con el modulo parado. */
    private static volatile BoostPlugin activo;

    static BoostPlugin activo() {
        return activo;
    }

    private static final int MENSAJES_VERSION = 2;

    /** Lo que de verdad multiplica: el de cada tipo, con su tope y sus mundos. */
    interface Fuente {
        double en(Player jugador, Tipo tipo);
    }

    private final Textos textos = new Textos();
    private Servicio servicio;
    private MenuBoost menu;
    private Miniones miniones;
    private Habilidades habilidades;
    private BukkitTask reloj;
    private MobCoins.Boost ganchoMobcoins;

    private final Set<Tipo> habilitados = EnumSet.allOf(Tipo.class);
    private final Set<String> mundosExcluidos = new HashSet<>();
    private final Map<Tipo, Set<String>> mundosPorTipo = new EnumMap<>(Tipo.class);
    private final Map<Tipo, Double> maximos = new EnumMap<>(Tipo.class);
    private double multiplicadorPorDefecto = 2.0;
    private int avisoAntesSegundos = 60;
    private boolean barraDeAccion = true;
    private boolean anunciarGlobales = true;

    public BoostPlugin(EDMPlugin core) {
        super(core, "boost", "Boost");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrar("mensajes.yml", MENSAJES_VERSION);
        textos.cargar(new File(getDataFolder(), "mensajes.yml"));
        leerConfig();

        servicio = new Servicio(new File(getDataFolder(), "data.yml"), getLogger());
        menu = new MenuBoost(this);

        core.getServer().getPluginManager().registerEvents(new Efectos(this::multiplicadorEn), this);
        core.getServer().getPluginManager().registerEvents(menu, this);

        miniones = new Miniones(this::multiplicadorDe, getLogger());
        habilidades = new Habilidades(this::multiplicadorEn, getLogger());

        ganchoMobcoins = ganchoMobcoins(this::multiplicadorEn);
        MobCoins.boost(ganchoMobcoins);

        var cmd = core.getCommand("boost");
        if (cmd != null) {
            ComandoBoost comando = new ComandoBoost(this);
            cmd.setExecutor(comando);
            cmd.setTabCompleter(comando);
        } else {
            getLogger().warning("El comando /boost no esta en el plugin.yml; el modulo solo respondera a la API.");
        }

        activo = this;

        // AuraSkills y LitMinions pueden arrancar despues que EDM: se enganchan cuando el
        // servidor ya ha cargado todos los plugins, en el primer tic.
        core.getServer().getScheduler().runTask(Module.dueno(this), this::enganchar);

        // Un tic por segundo: caducar, avisar y pintar la barra de accion.
        reloj = core.getServer().getScheduler().runTaskTimer(Module.dueno(this), this::tic, 20L, 20L);
    }

    private void enganchar() {
        if (habilitados.contains(Tipo.SKILL_EXP)) habilidades.enganchar(Module.dueno(this));
        if (habilitados.contains(Tipo.MINIONS) && miniones.enganchados() == 0) {
            miniones.enganchar(Module.dueno(this), getConfig().getStringList("minions.eventos"));
        }
        List<String> si = new ArrayList<>(), no = new ArrayList<>();
        for (Tipo t : Tipo.values()) {
            if (!habilitados.contains(t)) continue;
            (disponible(t) ? si : no).add(t.id());
        }
        getLogger().info("[Boost] Disponibles: " + (si.isEmpty() ? "ninguno" : String.join(", ", si))
                + (no.isEmpty() ? "" : " | sin su plugin: " + String.join(", ", no))
                + " | x" + MenuBoost.recorta(multiplicadorPorDefecto) + " por defecto.");
    }

    @Override
    public void onDisable() {
        if (reloj != null) reloj.cancel();
        if (servicio != null) servicio.guardar();
        MobCoins.boost(null);
        if (activo == this) activo = null;
    }

    @Override
    public String recargar() {
        reloadConfig();
        textos.cargar(new File(getDataFolder(), "mensajes.yml"));
        leerConfig();
        if (habilitados.contains(Tipo.SKILL_EXP) && habilidades != null) habilidades.enganchar(Module.dueno(this));
        return habilitados.size() + " tipo(s) activos.";
    }

    private void leerConfig() {
        habilitados.clear();
        maximos.clear();
        mundosPorTipo.clear();
        for (Tipo t : Tipo.values()) {
            if (getConfig().getBoolean(t.id() + ".activado", true)) habilitados.add(t);
            double max = getConfig().getDouble(t.id() + ".multiplicador-maximo", 3.0);
            maximos.put(t, max <= 1.0 ? 3.0 : max);
            Set<String> suyos = new HashSet<>();
            for (String m : getConfig().getStringList(t.id() + ".mundos-excluidos")) {
                suyos.add(m.toLowerCase(Locale.ROOT));
            }
            mundosPorTipo.put(t, suyos);
        }
        multiplicadorPorDefecto = Math.max(1.1, getConfig().getDouble("multiplicador-por-defecto", 2.0));
        avisoAntesSegundos = Math.max(0, getConfig().getInt("aviso-antes-de-acabar", 60));
        barraDeAccion = getConfig().getBoolean("barra-de-accion", true);
        anunciarGlobales = getConfig().getBoolean("anunciar-globales", true);

        mundosExcluidos.clear();
        for (String m : getConfig().getStringList("mundos-excluidos")) {
            mundosExcluidos.add(m.toLowerCase(Locale.ROOT));
        }

        // El config de un servidor que venga de antes puede traer aun la seccion drops.
        // No hace nada: se avisa para que se borre y nadie crea que funciona.
        File suyo = new File(getDataFolder(), "config.yml");
        if (suyo.exists() && org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(suyo)
                .contains(Tipo.RETIRADO_DROPS, true)) {
            getLogger().warning("[Boost] boost/config.yml aun tiene la seccion 'drops'. Ya no hace nada: "
                    + "el boost de drops se quito porque se usaba para duplicar. Puedes borrarla.");
        }
    }

    /* ------------------------------------------------------------ multiplicar */

    /** El gancho de MobCoins: sube lo que paga una baja con el boost del que la cobra. */
    static MobCoins.Boost ganchoMobcoins(Fuente fuente) {
        return (jugador, cantidad) -> {
            double mult = fuente.en(jugador, Tipo.MOBCOINS);
            return mult <= 1.0 ? cantidad : Efectos.escalar(cantidad, mult);
        };
    }

    /**
     * El multiplicador de ese jugador para ese tipo, sin mirar el mundo: el mayor entre
     * personal y global, recortado al tope del tipo. 1.0 si el tipo esta desactivado o
     * su plugin no esta.
     */
    public double multiplicadorDe(UUID jugador, Tipo tipo) {
        return calcular(servicio, jugador, tipo, disponible(tipo), false, maximo(tipo));
    }

    /** Lo mismo mirando donde esta: en un mundo excluido (general o del tipo), 1.0. */
    public double multiplicadorEn(Player jugador, Tipo tipo) {
        if (jugador == null) return 1.0;
        return calcular(servicio, jugador.getUniqueId(), tipo, disponible(tipo),
                mundoExcluido(jugador.getWorld().getName(), tipo), maximo(tipo));
    }

    /**
     * La regla entera, sin estado, para que el selftest la pruebe tal cual: sin tipo
     * disponible o en un mundo excluido, 1.0; si no, lo que diga el servicio (mayor de
     * personal y global) recortado al tope.
     */
    static double calcular(Servicio s, UUID jugador, Tipo tipo, boolean disponible, boolean excluido,
                           double maximo) {
        if (s == null || jugador == null || !disponible || excluido) return 1.0;
        return recortar(s.multiplicador(jugador, tipo), maximo);
    }

    static double recortar(double mult, double maximo) {
        if (mult <= 1.0) return 1.0;
        return maximo > 1.0 ? Math.min(mult, maximo) : mult;
    }

    public double maximo(Tipo tipo) {
        return maximos.getOrDefault(tipo, 3.0);
    }

    /* ------------------------------------------------------------------ altas */

    /**
     * Le da el boost y se lo cuenta: chat, sonido y, si toca, barra de accion. false
     * si el tipo no esta disponible en este servidor (no se da nada).
     */
    public boolean dar(Player jugador, Tipo tipo, long milisegundos, double multiplicador) {
        if (!disponible(tipo)) return false;
        Servicio.Activo a = servicio.dar(jugador.getUniqueId(), tipo, milisegundos,
                recortar(multiplicador, maximo(tipo)));
        textos.manda(jugador, "recibido",
                "&a%tipo% &fx%multiplicador% &7durante &f%tiempo%&7.",
                "%tipo%", tipo.nombre(), "%multiplicador%", MenuBoost.recorta(a.multiplicador()),
                "%tiempo%", Servicio.reloj(a.restanteMs()));
        jugador.playSound(jugador.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        return true;
    }

    public boolean darGlobal(Tipo tipo, long milisegundos, double multiplicador) {
        if (!disponible(tipo)) return false;
        Servicio.Activo a = servicio.darGlobal(tipo, milisegundos, recortar(multiplicador, maximo(tipo)));
        if (!anunciarGlobales) return true;
        Component aviso = textos.de("global-anuncio",
                "{sin-prefijo}&f%tipo% &ax%multiplicador% &fpara todo el servidor durante &a%tiempo%&f.",
                "%tipo%", tipo.nombre(), "%multiplicador%", MenuBoost.recorta(a.multiplicador()),
                "%tiempo%", Servicio.reloj(a.restanteMs()));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(aviso);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.2f);
        }
        return true;
    }

    /* -------------------------------------------------------------------- tic */

    private void tic() {
        for (Tipo t : servicio.globalesCaducados()) {
            if (!anunciarGlobales) continue;
            Component fin = textos.de("global-acabado",
                    "{sin-prefijo}&7Se acabo el boost global de &f%tipo%&7.", "%tipo%", t.nombre());
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(fin));
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            for (Tipo t : servicio.caducados(id)) {
                textos.manda(p, "acabado", "&7Se te acabo el boost de &f%tipo%&7.", "%tipo%", t.nombre());
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
            }
            if (avisoAntesSegundos > 0) {
                for (Tipo t : Tipo.values()) {
                    Servicio.Activo a = servicio.personal(id, t);
                    if (a == null) continue;
                    long queda = a.restanteMs();
                    if (queda <= avisoAntesSegundos * 1000L && servicio.marcarAviso(id, t)) {
                        textos.manda(p, "por-acabar",
                                "&e%tipo% &7se te acaba en &f%tiempo%&7.",
                                "%tipo%", t.nombre(), "%tiempo%", Servicio.reloj(queda));
                    }
                }
            }
            if (barraDeAccion) barra(p);
        }
    }

    /** Tramos de la barra que se vacia. Doce para que 1/4, 1/3 y 1/2 caigan justos. */
    private static final int TRAMOS = 12;

    /**
     * "✦ EXPERIENCIA ×2 ||||||||||||  12m 30s" en la barra de accion, solo si tiene
     * algo. Con varios boosts a la vez la linea se hace larga, asi que a partir de tres
     * cada uno se queda en su ✦ de color, el multiplicador y el tiempo, sin palitos.
     * Solo caracteres que Bedrock pinta igual: ✦, × y la barra vertical.
     */
    private void barra(Player p) {
        List<Tipo> suyos = new ArrayList<>();
        for (Tipo t : Tipo.values()) {
            if (disponible(t) && servicio.efectivo(p.getUniqueId(), t) != null) suyos.add(t);
        }
        if (suyos.isEmpty()) return;
        boolean corto = suyos.size() >= 3;
        Component linea = Component.empty();
        boolean primero = true;
        for (Tipo t : suyos) {
            Servicio.Activo a = servicio.efectivo(p.getUniqueId(), t);
            if (!primero) linea = linea.append(Estilo.texto(corto ? "  " : "   ", NamedTextColor.DARK_GRAY));
            double mult = recortar(a.multiplicador(), maximo(t));
            linea = linea.append(Estilo.texto("✦ ", t.color()));
            if (corto) {
                linea = linea
                        .append(Estilo.texto(t.nombre(), t.color()))
                        .append(Estilo.texto(" ×" + MenuBoost.recorta(mult), NamedTextColor.WHITE))
                        .append(Estilo.texto(" " + Servicio.reloj(a.restanteMs()), NamedTextColor.GRAY));
            } else {
                int llenos = (int) Math.ceil(a.progreso() * TRAMOS);
                llenos = Math.max(1, Math.min(TRAMOS, llenos));
                linea = linea
                        .append(Estilo.texto(t.nombre().toUpperCase(Locale.ROOT), t.color())
                                .decorate(TextDecoration.BOLD))
                        .append(Estilo.texto(" ×" + MenuBoost.recorta(mult) + " ", NamedTextColor.WHITE))
                        .append(Estilo.texto("|".repeat(llenos), t.color()))
                        .append(Estilo.texto("|".repeat(TRAMOS - llenos), NamedTextColor.DARK_GRAY))
                        .append(Estilo.texto("  " + Servicio.reloj(a.restanteMs()), NamedTextColor.GRAY));
            }
            primero = false;
        }
        p.sendActionBar(linea);
    }

    /* --------------------------------------------------------------- consultas */

    public Servicio servicio() {
        return servicio;
    }

    public Textos textos() {
        return textos;
    }

    MenuBoost menu() {
        return menu;
    }

    /** Activado en el config (clave <tipo>.activado). */
    public boolean habilitado(Tipo tipo) {
        return habilitados.contains(tipo);
    }

    /** Activado y con su plugin en marcha: lo unico que se reparte y se aplica. */
    public boolean disponible(Tipo tipo) {
        if (!habilitados.contains(tipo)) return false;
        return switch (tipo) {
            case SKILL_EXP -> habilidades != null && habilidades.enganchado();
            case PESCA -> pluginEnMarcha("PremioPescao");
            case MINIONS -> miniones != null && miniones.enganchados() > 0;
            default -> true;
        };
    }

    /** Por que un tipo activado no esta disponible, para el menu y la consola. */
    public String faltaPara(Tipo tipo) {
        return switch (tipo) {
            case SKILL_EXP -> "AuraSkills";
            case PESCA -> "PremioPescao";
            case MINIONS -> "LitMinions";
            default -> "";
        };
    }

    private static boolean pluginEnMarcha(String nombre) {
        try {
            return Bukkit.getPluginManager().isPluginEnabled(nombre);
        } catch (Throwable t) {
            return false;
        }
    }

    public double multiplicadorPorDefecto() {
        return multiplicadorPorDefecto;
    }

    public boolean mundoExcluido(String mundo, Tipo tipo) {
        String m = mundo.toLowerCase(Locale.ROOT);
        if (mundosExcluidos.contains(m)) return true;
        Set<String> suyos = mundosPorTipo.get(tipo);
        return suyos != null && suyos.contains(m);
    }

    /** Para los placeholders: "x2" o "x1", y el tiempo que queda o "-". */
    public String placeholder(UUID jugador, Tipo tipo, boolean tiempo) {
        if (servicio == null) return tiempo ? "-" : "x1";
        Servicio.Activo a = !disponible(tipo) ? null
                : jugador == null ? servicio.global(tipo) : servicio.efectivo(jugador, tipo);
        if (a == null) return tiempo ? "-" : "x1";
        return tiempo ? Servicio.reloj(a.restanteMs()) : "x" + MenuBoost.recorta(recortar(a.multiplicador(), maximo(tipo)));
    }

    /** Los tipos activados en el config, en el orden del menu. */
    public List<Tipo> tipos() {
        List<Tipo> out = new ArrayList<>();
        for (Tipo t : Tipo.values()) if (habilitados.contains(t)) out.add(t);
        return out;
    }
}
