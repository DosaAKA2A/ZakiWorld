package net.ederus.edm.boost;

import java.io.File;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;
import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.Textos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Boosts temporales: experiencia, drops y minions.
 *
 * Nacio para las cajas de OneBlock, que repartian cuatro "boosters" que no hacian
 * nada: eran objetos decorativos sin comando detras. Ahora la caja llama a
 * `boost give <jugador> <tipo> <minutos>` y el boost existe de verdad, se ve en
 * /boost, avisa cuando se acaba y sobrevive a un reinicio.
 *
 * Reglas de diseno, todas pensadas para que no haya sorpresas:
 *   - el multiplicador es el MAYOR entre el personal y el global, nunca el producto;
 *   - el tiempo se guarda como instante de fin, asi que reiniciar no regala minutos;
 *   - dos boosts iguales SUMAN tiempo en vez de pisarse;
 *   - lo que no se pueda multiplicar (un minion de un tipo raro) se queda igual y
 *     no rompe nada.
 */
public final class BoostPlugin extends Module {

    private static final int MENSAJES_VERSION = 1;

    private final Textos textos = new Textos();
    private Servicio servicio;
    private MenuBoost menu;
    private Miniones miniones;
    private BukkitTask reloj;

    private final Set<Tipo> habilitados = EnumSet.allOf(Tipo.class);
    private final Set<String> mundosExcluidos = new HashSet<>();
    private final Set<Material> materialesExcluidos = EnumSet.noneOf(Material.class);
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

        servicio = new Servicio(new File(getDataFolder(), "data.yml"));
        menu = new MenuBoost(this);

        core.getServer().getPluginManager().registerEvents(new Efectos(this), this);
        core.getServer().getPluginManager().registerEvents(menu, this);

        miniones = new Miniones(this, getLogger());
        if (habilitados.contains(Tipo.MINIONS)) {
            miniones.enganchar(Module.dueno(this), getConfig().getStringList("minions.eventos"));
        }

        var cmd = core.getCommand("boost");
        if (cmd != null) {
            ComandoBoost comando = new ComandoBoost(this);
            cmd.setExecutor(comando);
            cmd.setTabCompleter(comando);
        } else {
            getLogger().warning("El comando /boost no esta en el plugin.yml; el modulo solo respondera a la API.");
        }

        // Un tic por segundo: caducar, avisar y pintar la barra de accion.
        reloj = core.getServer().getScheduler().runTaskTimer(Module.dueno(this), this::tic, 20L, 20L);

        getLogger().info("[Boost] " + habilitados.size() + " tipos activos, x"
                + MenuBoost.recorta(multiplicadorPorDefecto) + " por defecto, "
                + miniones.enganchados() + " evento(s) de minions enganchados.");
    }

    @Override
    public void onDisable() {
        if (reloj != null) reloj.cancel();
        if (servicio != null) servicio.guardar();
    }

    @Override
    public String recargar() {
        reloadConfig();
        textos.cargar(new File(getDataFolder(), "mensajes.yml"));
        leerConfig();
        return habilitados.size() + " tipo(s) activos.";
    }

    private void leerConfig() {
        habilitados.clear();
        for (Tipo t : Tipo.values()) {
            if (getConfig().getBoolean(t.id() + ".activado", true)) habilitados.add(t);
        }
        multiplicadorPorDefecto = Math.max(1.1, getConfig().getDouble("multiplicador-por-defecto", 2.0));
        avisoAntesSegundos = Math.max(0, getConfig().getInt("aviso-antes-de-acabar", 60));
        barraDeAccion = getConfig().getBoolean("barra-de-accion", true);
        anunciarGlobales = getConfig().getBoolean("anunciar-globales", true);

        mundosExcluidos.clear();
        for (String m : getConfig().getStringList("mundos-excluidos")) {
            mundosExcluidos.add(m.toLowerCase(Locale.ROOT));
        }
        materialesExcluidos.clear();
        for (String s : getConfig().getStringList("drops.bloques-excluidos")) {
            Material m = Material.matchMaterial(s);
            if (m != null) materialesExcluidos.add(m);
        }
    }

    /* ------------------------------------------------------------------ altas */

    /** Le da el boost y se lo cuenta: chat, sonido y, si toca, barra de accion. */
    public void dar(Player jugador, Tipo tipo, long milisegundos, double multiplicador) {
        Servicio.Activo a = servicio.dar(jugador.getUniqueId(), tipo, milisegundos, multiplicador);
        textos.manda(jugador, "recibido",
                "&a%tipo% &fx%multiplicador% &7durante &f%tiempo%&7.",
                "%tipo%", tipo.nombre(), "%multiplicador%", MenuBoost.recorta(a.multiplicador()),
                "%tiempo%", Servicio.reloj(a.restanteMs()));
        jugador.playSound(jugador.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
    }

    public void darGlobal(Tipo tipo, long milisegundos, double multiplicador) {
        Servicio.Activo a = servicio.darGlobal(tipo, milisegundos, multiplicador);
        if (!anunciarGlobales) return;
        Component aviso = textos.de("global-anuncio",
                "{sin-prefijo}&f%tipo% &ax%multiplicador% &fpara todo el servidor durante &a%tiempo%&f.",
                "%tipo%", tipo.nombre(), "%multiplicador%", MenuBoost.recorta(a.multiplicador()),
                "%tiempo%", Servicio.reloj(a.restanteMs()));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(aviso);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.2f);
        }
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

    /** "EXP x2 12m 30s · DROPS x2 05m" en la barra de accion, solo si tiene algo. */
    private void barra(Player p) {
        Component linea = Component.empty();
        boolean primero = true;
        for (Tipo t : Tipo.values()) {
            Servicio.Activo a = servicio.efectivo(p.getUniqueId(), t);
            if (a == null) continue;
            if (!primero) linea = linea.append(Estilo.texto("  ", NamedTextColor.DARK_GRAY));
            linea = linea
                    .append(Estilo.texto(t.nombre().toUpperCase(Locale.ROOT) + " ", t.color()))
                    .append(Estilo.texto("x" + MenuBoost.recorta(a.multiplicador()) + " ", NamedTextColor.WHITE))
                    .append(Estilo.texto(Servicio.reloj(a.restanteMs()), NamedTextColor.GRAY));
            primero = false;
        }
        if (!primero) p.sendActionBar(linea);
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

    public boolean habilitado(Tipo tipo) {
        return habilitados.contains(tipo);
    }

    public double multiplicadorPorDefecto() {
        return multiplicadorPorDefecto;
    }

    public boolean mundoExcluido(String mundo) {
        return mundosExcluidos.contains(mundo.toLowerCase(Locale.ROOT));
    }

    public Set<Material> materialesExcluidos() {
        return materialesExcluidos;
    }

    /** Para los placeholders: "x2" o "-", y el tiempo que queda. */
    public String placeholder(UUID jugador, Tipo tipo, boolean tiempo) {
        if (servicio == null) return "-";
        Servicio.Activo a = jugador == null ? servicio.global(tipo) : servicio.efectivo(jugador, tipo);
        if (a == null) return tiempo ? "-" : "x1";
        return tiempo ? Servicio.reloj(a.restanteMs()) : "x" + MenuBoost.recorta(a.multiplicador());
    }

    public List<Tipo> tipos() {
        return List.of(Tipo.values());
    }
}
