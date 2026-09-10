package net.ederus.edm.flex;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;
import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.Textos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Flex: la vitrina de cada jugador.
 *
 * Un escaparate de hasta 27 objetos que cualquiera puede mirar y nadie puede
 * tocar, ni siquiera su dueño. Lo que se guarda son COPIAS: el objeto de verdad
 * no se mueve del inventario de quien lo puso.
 *
 * Ese es todo el diseño y es deliberado. Un baul compartido seria duplicable con
 * un rollback del mundo (el objeto vuelve al cofre Y sigue guardado en la base);
 * una foto no puede duplicar nada porque no devuelve objetos: de una vitrina no
 * sale un ItemStack por ningun camino del codigo.
 */
public final class FlexPlugin extends Module {

    /*
     * El magenta y el carmesi de la vitrina. No son dos colores fuertes: son dos
     * tonos del mismo lado de la rueda, uno claro y otro profundo, para que el
     * degradado se lea premium en vez de gritar. El texto normal va en el tono
     * suave; el degradado entero queda para el prefijo y para el boton.
     */
    public static final int MAGENTA = 0xE36BC8;
    public static final int CARMESI = 0x8F1144;
    public static final TextColor MARCA = TextColor.color(0xDD92C0);

    private static final int MENSAJES_VERSION = 1;

    private final Textos textos = new Textos();
    private Almacen almacen;
    private MenuFlex menu;
    private BukkitTask volcado;

    /** Cuando anuncio cada uno por ultima vez, para el enfriamiento del chat. */
    private final Map<UUID, Long> ultimoAnuncio = new HashMap<>();

    public FlexPlugin(EDMPlugin core) {
        super(core, "flex", "Flex");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        migrar("mensajes.yml", MENSAJES_VERSION);
        textos.cargar(new File(getDataFolder(), "mensajes.yml"));

        almacen = new Almacen(this);
        almacen.cargar();

        menu = new MenuFlex(this);
        core.getServer().getPluginManager().registerEvents(menu, this);

        ComandoFlex comando = new ComandoFlex(this);
        var cmd = core.getCommand("flex");
        if (cmd != null) {
            cmd.setExecutor(comando);
            cmd.setTabCompleter(comando);
        } else {
            getLogger().warning("El comando /flex no esta en el plugin.yml de EDM.");
        }

        // El volcado va a su ritmo y no en cada clic: montar una vitrina son
        // veinte clics seguidos y no hay por que escribir el fichero veinte veces.
        volcado = core.getServer().getScheduler().runTaskTimerAsynchronously(
                Module.dueno(this), () -> almacen.volcarSiHaceFalta(), 600L, 600L);
    }

    @Override
    public void onDisable() {
        if (volcado != null) volcado.cancel();
        if (almacen != null) almacen.volcar();
    }

    @Override
    public String recargar() {
        textos.cargar(new File(getDataFolder(), "mensajes.yml"));
        almacen.volcarSiHaceFalta();
        almacen.cargar();
        return almacen.cuantas() + " vitrina(s).";
    }

    public Almacen almacen() {
        return almacen;
    }

    public MenuFlex menu() {
        return menu;
    }

    public int enfriamiento() {
        return getConfig().getInt("anuncio.enfriamiento-segundos", 300);
    }

    /* --------------------------------------------------------------- el anuncio */

    /**
     * El anuncio del chat: una linea para todos con un boton que abre la vitrina.
     *
     * El boton ejecuta /flex <jugador>, o sea que pasa por el mismo comando y las
     * mismas comprobaciones que si lo escribiera cada uno a mano. No hay una via
     * corta que se salte nada.
     */
    public void anunciar(Player quien, Vitrina vitrina) {
        if (vitrina.vacia()) {
            di(quien, "vacia-para-mostrar", "Tu vitrina está vacía. Pon algo antes de mostrarla.");
            return;
        }

        long ahora = System.currentTimeMillis();
        long antes = ultimoAnuncio.getOrDefault(quien.getUniqueId(), 0L);
        long espera = enfriamiento() * 1000L;
        if (!quien.hasPermission("ederus.flex.admin") && ahora - antes < espera) {
            long quedan = (espera - (ahora - antes) + 999) / 1000;
            di(quien, "enfriamiento", "Podrás volver a mostrarla en %segundos% s",
                    "%segundos%", String.valueOf(quedan));
            return;
        }
        ultimoAnuncio.put(quien.getUniqueId(), ahora);

        Component hover = Estilo.texto("Vitrina de " + vitrina.nombre(), MARCA)
                .append(Component.newline())
                .append(Estilo.texto(vitrina.cuantos() + " objeto(s)", Estilo.APAGADO));
        for (Component fila : menu.resumen(vitrina)) {
            hover = hover.append(Component.newline()).append(fila);
        }
        hover = hover.append(Component.newline()).append(Component.newline())
                .append(Estilo.texto("Clic para abrirla", MARCA));

        // El boton tambien va plano: en una linea de chat el degradado se come el
        // texto. El degradado se queda para el titulo del menu, que es donde luce.
        Component boton = Estilo.texto("[", Estilo.APAGADO)
                .append(Estilo.texto("Ver vitrina", MARCA))
                .append(Estilo.texto("]", Estilo.APAGADO))
                .hoverEvent(HoverEvent.showText(hover))
                .clickEvent(ClickEvent.runCommand("/flex " + vitrina.nombre()));

        Component linea = texto("anuncio", "%jugador% ha flexeado su vitrina.  ",
                "%jugador%", vitrina.nombre()).append(boton);

        for (Player p : core.getServer().getOnlinePlayers()) {
            p.sendMessage(linea);
        }
        core.getServer().getConsoleSender().sendMessage(
                vitrina.nombre() + " mostró su vitrina (" + vitrina.cuantos() + " objetos).");
    }

    public Textos textos() {
        return textos;
    }

    /** Un mensaje de mensajes.yml, ya con su prefijo. */
    public Component texto(String clave, String respaldo, String... pares) {
        return textos.de(clave, respaldo, pares);
    }

    public void di(org.bukkit.command.CommandSender a, String clave, String respaldo, String... pares) {
        textos.manda(a, clave, respaldo, pares);
    }
}
