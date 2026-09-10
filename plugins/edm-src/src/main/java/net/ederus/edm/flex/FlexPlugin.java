package net.ederus.edm.flex;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;
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

    public static final TextColor MARCA = TextColor.color(0xFFD966);

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
            quien.sendMessage(aviso("Tu vitrina está vacía. Pon algo antes de mostrarla."));
            return;
        }

        long ahora = System.currentTimeMillis();
        long antes = ultimoAnuncio.getOrDefault(quien.getUniqueId(), 0L);
        long espera = enfriamiento() * 1000L;
        if (!quien.hasPermission("ederus.flex.admin") && ahora - antes < espera) {
            long quedan = (espera - (ahora - antes) + 999) / 1000;
            quien.sendMessage(aviso("Espera " + quedan + " s para volver a mostrarla."));
            return;
        }
        ultimoAnuncio.put(quien.getUniqueId(), ahora);

        Component hover = Component.text("Vitrina de " + vitrina.nombre(), MARCA, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text(vitrina.cuantos() + " objeto(s)", NamedTextColor.GRAY))
                .append(Component.newline());
        for (Component linea : menu.resumen(vitrina)) {
            hover = hover.append(Component.newline()).append(linea);
        }
        hover = hover.append(Component.newline()).append(Component.newline())
                .append(Component.text("Clic para abrirla", NamedTextColor.YELLOW));

        Component boton = Component.text("[Ver vitrina]", MARCA, TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(hover))
                .clickEvent(ClickEvent.runCommand("/flex " + vitrina.nombre()));

        Component linea = Component.text(vitrina.nombre(), NamedTextColor.WHITE, TextDecoration.BOLD)
                .append(Component.text(" ha puesto su vitrina a la vista.  ", NamedTextColor.GRAY))
                .append(boton);

        for (Player p : core.getServer().getOnlinePlayers()) {
            p.sendMessage(linea);
        }
        core.getServer().getConsoleSender().sendMessage(
                vitrina.nombre() + " mostró su vitrina (" + vitrina.cuantos() + " objetos).");
    }

    public Component aviso(Component texto) {
        return Component.text("VITRINA ", MARCA, TextDecoration.BOLD)
                .append(Component.text("» ", NamedTextColor.DARK_GRAY))
                .append(texto);
    }

    public Component aviso(String texto) {
        return aviso(Component.text(texto, NamedTextColor.GRAY));
    }
}
