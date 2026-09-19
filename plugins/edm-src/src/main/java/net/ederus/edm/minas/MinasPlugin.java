package net.ederus.edm.minas;

import java.io.File;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;
import net.ederus.edm.comun.Bitacora;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.Textos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Minas: las minas de prision.
 *
 * Una mina es una caja del mundo que se rellena sola con una mezcla de bloques
 * y que cualquiera con acceso puede picar, aunque este dentro de una region de
 * WorldGuard. Se crean con el pico de seleccion y se editan enteras desde el
 * menu de /mine: la zona, la mezcla, el reloj, el umbral, la salida y el permiso.
 *
 * El jugador ve otra cosa: /mine le abre la lista de minas a las que puede ir,
 * con cuanto queda por picar y cuanto falta para el reinicio.
 */
public final class MinasPlugin extends Module {

    /*
     * El ambar de las minas: cobre y oro viejo, dos tonos del mismo lado.
     *
     * Dos juegos de tonos a proposito. MARCA y CLARO van en el chat y en los lores,
     * que se leen sobre fondo oscuro, y por eso son claros. El titulo de la ventana
     * se pinta sobre la barra GRIS CLARO del cofre: ahi un ambar claro se lava y
     * no se lee, asi que el degradado y la seccion del titulo bajan a cobre y
     * marron oscuro.
     */
    public static final TextColor MARCA = TextColor.color(0xE8A25C);
    public static final TextColor CLARO = TextColor.color(0xF3D2A8);
    /** Los del titulo: sobre la barra clara del cofre. */
    public static final int DESDE = 0xB8651C;
    public static final int HASTA = 0x6B330E;
    public static final TextColor TITULO = TextColor.color(0x5E3A1C);

    private static final int MENSAJES_VERSION = 1;

    private final Textos textos = new Textos();
    private Bitacora bitacora;
    private Minas minas;
    private Reinicio reinicio;
    private Varita varita;
    private Guardia guardia;
    private MenuMinas menu;

    public MinasPlugin(EDMPlugin core) {
        super(core, "minas", "Minas");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        migrar("mensajes.yml", MENSAJES_VERSION);
        textos.cargar(new File(getDataFolder(), "mensajes.yml"));
        bitacora = core.bitacora("minas");

        minas = new Minas(this);
        minas.cargar();

        varita = new Varita(this);
        guardia = new Guardia(this);
        menu = new MenuMinas(this);
        core.getServer().getPluginManager().registerEvents(varita, this);
        core.getServer().getPluginManager().registerEvents(guardia, this);
        core.getServer().getPluginManager().registerEvents(menu, this);

        reinicio = new Reinicio(this);
        reinicio.arrancar();

        ComandoMinas comando = new ComandoMinas(this);
        var cmd = core.getCommand("mine");
        if (cmd != null) {
            cmd.setExecutor(comando);
            cmd.setTabCompleter(comando);
        } else {
            getLogger().warning("El comando /mine no esta en el plugin.yml de EDM.");
        }
        getLogger().info("Minas: " + minas.cuantas() + " mina(s) cargadas.");
    }

    @Override
    public void onDisable() {
        if (reinicio != null) reinicio.parar();
        if (minas != null) minas.guardar();
    }

    @Override
    public String recargar() {
        reloadConfig();
        textos.cargar(new File(getDataFolder(), "mensajes.yml"));
        minas.guardar();
        minas.cargar();
        return minas.cuantas() + " mina(s).";
    }

    /* ------------------------------------------------------------- accesos */

    public Minas minas() {
        return minas;
    }

    public Reinicio reinicio() {
        return reinicio;
    }

    public Varita varita() {
        return varita;
    }

    public MenuMinas menu() {
        return menu;
    }

    public boolean esAdmin(Player p) {
        return p.hasPermission("ederus.minas");
    }

    /** Puede entrar y picar: sin permiso en la mina entra cualquiera. */
    public boolean puedeEntrar(Player p, Mina m) {
        if (esAdmin(p)) return true;
        String permiso = m.permiso();
        return permiso.isEmpty() || p.hasPermission(permiso);
    }

    /** Lleva a alguien a una mina, con sus comprobaciones. */
    public boolean viajar(Player p, Mina m) {
        if (!m.conZona()) {
            di(p, "sin-zona", "La mina %mina% todavía no tiene zona.", "%mina%", m.nombre());
            return false;
        }
        if (!puedeEntrar(p, m)) {
            di(p, "sin-acceso", "No tienes acceso a la mina %mina%.", "%mina%", m.nombre());
            return false;
        }
        Location a = m.salida();
        if (a == null) {
            di(p, "sin-zona", "La mina %mina% todavía no tiene zona.", "%mina%", m.nombre());
            return false;
        }
        p.teleport(a);
        di(p, "viaje", "Estás en %mina%. Pica lo que quieras: se rellena sola.", "%mina%", m.nombre());
        Compat.sound(a.getWorld(), a, "entity.enderman.teleport", 0.6f, 1.3f);
        return true;
    }

    /** El titulo de las ventanas: el rombo, MINAS en degradado y la seccion detras. */
    public Component titulo(String seccion) {
        return titulo(Estilo.texto(seccion, TITULO));
    }

    /** Igual, con la seccion ya compuesta (el nombre de una mina con sus colores). */
    public Component titulo(Component seccion) {
        return Component.text("✦ ", TITULO)
                .append(Estilo.degradado("MINAS", DESDE, HASTA).decoration(TextDecoration.BOLD, true))
                .append(Estilo.texto("  ", TITULO))
                .append(seccion.colorIfAbsent(TITULO).decoration(TextDecoration.ITALIC, false));
    }

    public void anotar(String... campos) {
        if (bitacora != null) bitacora.anotar(campos);
    }

    public Textos textos() {
        return textos;
    }

    /** Un mensaje de mensajes.yml, ya con su prefijo. */
    public Component texto(String clave, String respaldo, String... pares) {
        return textos.de(clave, respaldo, pares);
    }

    public void di(CommandSender a, String clave, String respaldo, String... pares) {
        textos.manda(a, clave, respaldo, pares);
    }
}
