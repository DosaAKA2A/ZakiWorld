package net.ederus.edm.glow;

import java.io.File;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permissible;
import org.bukkit.scheduler.BukkitTask;

import com.comphenix.protocol.wrappers.EnumWrappers;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;
import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.Textos;
import net.kyori.adventure.text.Component;

/**
 * Brillo: el contorno de color de los jugadores. Sustituye a FancyGlow (y a
 * EderusGlow en el Test), con sus permisos.
 *
 * Va en EDS y en EDO. Necesita ProtocolLib: el color no se puede poner con la
 * API de Bukkit sin pelearse con TAB (ver Equipos).
 */
public final class GlowPlugin extends Module implements Listener {

    private static final int CONFIG_VERSION = 1;
    private static final int MENSAJES_VERSION = 1;

    /** Textos con acceso a las lineas sin prefijo, para el menu. */
    static final class TextosBrillo extends Textos {
        private YamlConfiguration yml = new YamlConfiguration();

        @Override
        protected void alCargar(YamlConfiguration y) {
            this.yml = y;
        }

        Component sin(String clave, String respaldo, String... pares) {
            String s = yml.getString(clave, respaldo);
            for (int i = 0; i + 1 < pares.length; i += 2) s = s.replace(pares[i], pares[i + 1]);
            return Estilo.legado(s);
        }

        String crudo(String clave, String respaldo) {
            return yml.getString(clave, respaldo);
        }
    }

    final TextosBrillo textos = new TextosBrillo();
    private Almacen almacen;
    private Equipos equipos;
    private MenuBrillo menu;
    private BukkitTask arcoiris;
    private BukkitTask parpadeo;
    private int paso;
    private boolean encendidoParpadeo = true;

    public GlowPlugin(EDMPlugin core) {
        super(core, "glow", "Glow");
    }

    @Override
    public void onEnable() {
        migrar("config.yml", CONFIG_VERSION);
        saveDefaultConfig();
        reloadConfig();
        migrar("mensajes.yml", MENSAJES_VERSION);
        textos.cargar(new File(getDataFolder(), "mensajes.yml"));

        almacen = new Almacen(new File(getDataFolder(), "jugadores.yml"), getLogger());
        equipos = new Equipos(core);
        equipos.registrar();
        menu = new MenuBrillo(this);

        core.getServer().getPluginManager().registerEvents(this, this);
        core.getServer().getPluginManager().registerEvents(menu, this);

        ComandoGlow comando = new ComandoGlow(this);
        var cmd = core.getCommand("glow");
        if (cmd != null) {
            cmd.setExecutor(comando);
            cmd.setTabCompleter(comando);
        } else {
            getLogger().warning("El comando /glow no esta en el plugin.yml.");
        }

        for (String viejo : new String[]{"FancyGlow", "EderusGlow"}) {
            if (Bukkit.getPluginManager().getPlugin(viejo) != null) {
                getLogger().warning(viejo + " sigue instalado: los dos pelean por /glow y por el brillo. Apartalo a .jarx y reinicia.");
            }
        }

        tareas();
        /* Tras un /edm reload los que ya estan dentro recuperan su brillo. Un tick
         * despues, para que TAB haya mandado los equipos. */
        Bukkit.getScheduler().runTaskLater(core, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) aplicar(p);
        }, 20L);
        getLogger().info("Brillo activo | " + almacen.todos().size() + " jugadores con brillo guardado.");
    }

    @Override
    public void onDisable() {
        if (arcoiris != null) arcoiris.cancel();
        if (parpadeo != null) parpadeo.cancel();
        if (equipos != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (almacen.de(p.getUniqueId()) != null) {
                    p.setGlowing(false);
                    equipos.fijar(p, null);
                }
            }
            equipos.soltar();
        }
    }

    @Override
    public String recargar() {
        reloadConfig();
        textos.cargar(new File(getDataFolder(), "mensajes.yml"));
        almacen.cargar();
        tareas();
        for (Player p : Bukkit.getOnlinePlayers()) aplicar(p);
        return "brillo recargado";
    }

    private void tareas() {
        if (arcoiris != null) arcoiris.cancel();
        if (parpadeo != null) parpadeo.cancel();
        long ticksArco = Math.max(2, getConfig().getInt("arcoiris-ticks", 10));
        long ticksParp = Math.max(2, getConfig().getInt("parpadeo-ticks", 10));
        arcoiris = Bukkit.getScheduler().runTaskTimer(core, () -> {
            paso++;
            Brillo[] todos = Brillo.values();
            for (Map.Entry<UUID, Almacen.Eleccion> e : almacen.todos().entrySet()) {
                if (!e.getValue().arcoiris()) continue;
                Player p = Bukkit.getPlayer(e.getKey());
                if (p == null || !puede(p, e.getValue())) continue;
                /* Solo los 12 colores vivos; el blanco, grises y negro no parecen arcoiris. */
                equipos.fijar(p, todos[paso % 12].formato);
            }
        }, ticksArco, ticksArco);
        parpadeo = Bukkit.getScheduler().runTaskTimer(core, () -> {
            encendidoParpadeo = !encendidoParpadeo;
            for (Map.Entry<UUID, Almacen.Eleccion> e : almacen.todos().entrySet()) {
                if (!e.getValue().parpadeo()) continue;
                Player p = Bukkit.getPlayer(e.getKey());
                if (p == null || !puede(p, e.getValue())) continue;
                p.setGlowing(encendidoParpadeo);
            }
        }, ticksParp, ticksParp);
    }

    /* ---------- eventos ---------- */

    @EventHandler(priority = EventPriority.MONITOR)
    public void alEntrar(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        /* Un tick despues: TAB le asigna el equipo al entrar y hay que conocerlo. */
        Bukkit.getScheduler().runTaskLater(core, () -> {
            if (p.isOnline()) aplicar(p);
        }, 10L);
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        equipos.fijar(e.getPlayer(), null);
    }

    /* ---------- lo que hacen el comando y el menu ---------- */

    Almacen.Eleccion eleccion(Player p) {
        return almacen.de(p.getUniqueId());
    }

    /**
     * Pone al jugador como diga su eleccion. Si ya no tiene permiso (se le acabo el
     * rango) se apaga, pero la eleccion se queda: vuelve con el rango.
     *
     * Quien entra brillando sin eleccion guardada es un resto de FancyGlow (el flag
     * se queda grabado en el jugador): se le apaga, y puede elegirlo otra vez.
     */
    void aplicar(Player p) {
        Almacen.Eleccion e = almacen.de(p.getUniqueId());
        if (e == null || !puede(p, e)) {
            if (p.isGlowing() && !p.hasPotionEffect(org.bukkit.potion.PotionEffectType.GLOWING)) p.setGlowing(false);
            equipos.fijar(p, null);
            return;
        }
        p.setGlowing(true);
        Brillo b = e.brillo();
        equipos.fijar(p, e.arcoiris() ? Brillo.RED.formato : b == null ? EnumWrappers.ChatFormatting.WHITE : b.formato);
    }

    void elegir(Player p, Almacen.Eleccion e) {
        almacen.poner(p.getUniqueId(), e);
        aplicar(p);
    }

    void elegir(UUID id, Almacen.Eleccion e) {
        almacen.poner(id, e);
        Player p = Bukkit.getPlayer(id);
        if (p != null) aplicar(p);
    }

    boolean conoceEquipo(Player p) {
        return equipos.conoceEquipo(p);
    }

    boolean puede(Permissible p, Almacen.Eleccion e) {
        if (p.hasPermission("fancyglow.admin")) return true;
        if (e.parpadeo() && !p.hasPermission("fancyglow.flashing")) return false;
        if (e.arcoiris()) return p.hasPermission("fancyglow.rainbow");
        Brillo b = e.brillo();
        return b != null && puedeColor(p, b);
    }

    boolean puedeColor(Permissible p, Brillo b) {
        return p.hasPermission("fancyglow.admin") || p.hasPermission("fancyglow.all_colors") || p.hasPermission(b.permiso());
    }

    boolean puedeArcoiris(Permissible p) {
        return p.hasPermission("fancyglow.admin") || p.hasPermission("fancyglow.rainbow");
    }

    boolean puedeParpadeo(Permissible p) {
        return p.hasPermission("fancyglow.admin") || p.hasPermission("fancyglow.flashing");
    }

    MenuBrillo menu() {
        return menu;
    }

    void di(CommandSender a, String clave, String respaldo, String... pares) {
        textos.manda(a, clave, respaldo, pares);
    }

    /** "&#55FFFFCeleste" para meter en un mensaje. */
    static String pintado(Brillo b) {
        return String.format("&#%06X%s", b.rgb, b.nombre);
    }
}
