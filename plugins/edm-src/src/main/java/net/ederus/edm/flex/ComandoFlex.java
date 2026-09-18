package net.ederus.edm.flex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * /flex, /flex showcase y /flex jugador.
 *
 * Tres formas y ninguna mas: la tuya para montarla, el anuncio, y la de otro
 * para mirarla. Un nombre que coincida con un subcomando pierde: quien se llame
 * "showcase" tendra que abrir su vitrina desde el menu.
 */
public final class ComandoFlex implements CommandExecutor, TabCompleter {

    private final FlexPlugin plugin;

    public ComandoFlex(FlexPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        if (!(quien instanceof Player p)) {
            quien.sendMessage(plugin.texto("solo-en-juego", "Las vitrinas se ven dentro del juego."));
            return true;
        }
        if (!p.hasPermission("ederus.flex")) {
            plugin.di(p, "sin-permiso", "No puedes usar las vitrinas.");
            return true;
        }

        if (args.length == 0) {
            plugin.menu().abrirPropia(p);
            return true;
        }

        String uno = args[0].toLowerCase(Locale.ROOT);
        if (uno.equals("showcase") || uno.equals("mostrar")) {
            plugin.anunciar(p, plugin.almacen().de(p.getUniqueId(), p.getName()));
            return true;
        }
        if (uno.equals("power") || uno.equals("poder")) {
            poder(p, args.length >= 2 ? args[1] : null);
            return true;
        }
        if (uno.equals("ayuda") || uno.equals("help")) {
            ayuda(p, etiqueta);
            return true;
        }
        if (uno.equals("reload") && p.hasPermission("ederus.flex.admin")) {
            p.sendMessage(plugin.texto("recargado", "Recargado: %que%", "%que%", plugin.recargar()));
            return true;
        }

        Vitrina suya = plugin.almacen().porNombre(args[0]);
        if (suya == null || suya.vacia()) {
            plugin.di(p, "sin-vitrina", "%jugador% no tiene nada en su vitrina.",
                    "%jugador%", args[0]);
            return true;
        }
        plugin.menu().abrirAjena(p, suya);
        return true;
    }

    /**
     * /flex power: el PODER de un jugador, desglosado.
     *
     * Vive en la vitrina y no en Lethal World porque es una cifra del JUGADOR, no de
     * un mundo: lo que ha jugado (rango y habilidades) mas lo que lleva puesto. Los
     * pesos de cada parte estan en el config de flex.
     */
    private void poder(Player quien, String nombre) {
        Player de = nombre == null ? quien : plugin.getServer().getPlayer(nombre);
        if (de == null) {
            plugin.di(quien, "sin-jugador", "%jugador% no está conectado.", "%jugador%", nombre);
            return;
        }
        var d = net.ederus.edm.comun.Poder.calcular(plugin, de);
        quien.sendMessage(net.ederus.edm.comun.Estilo.degradado("PODER",
                FlexPlugin.MAGENTA, FlexPlugin.CARMESI));
        linea(quien, "Jugador", de.getName());
        linea(quien, "Rango de rankup", String.valueOf(d.rango()));
        linea(quien, "AuraSkills", String.format(java.util.Locale.US, "%.0f", d.auraskills()));
        linea(quien, "Armadura", String.format(java.util.Locale.US, "%.1f", d.armadura()));
        linea(quien, "Dureza", String.format(java.util.Locale.US, "%.1f", d.dureza()));
        linea(quien, "Vida de más", String.format(java.util.Locale.US, "%.1f", d.vida()));
        linea(quien, "Daño de más", String.format(java.util.Locale.US, "%.1f", d.dano()));
        linea(quien, "TOTAL", String.format(java.util.Locale.US, "%.0f", d.total()));
    }

    private void ayuda(Player p, String etiqueta) {
        p.sendMessage(net.ederus.edm.comun.Estilo.degradado("VITRINA",
                FlexPlugin.MAGENTA, FlexPlugin.CARMESI));
        linea(p, "/" + etiqueta, "monta la tuya: clic en un objeto y se copia");
        linea(p, "/" + etiqueta + " <jugador>", "mira la de otro");
        linea(p, "/" + etiqueta + " power [jugador]", "de qué se compone su Poder");
        linea(p, "/" + etiqueta + " showcase", "la enseña en el chat");
        p.sendMessage(Component.text("  De una vitrina no sale nada: lo que se ve son copias.",
                NamedTextColor.DARK_GRAY));
    }

    private void linea(Player p, String comando, String que) {
        p.sendMessage(Component.text("  " + comando + "  ", NamedTextColor.WHITE)
                .append(Component.text(que, NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1 || !quien.hasPermission("ederus.flex")) return out;
        String pref = args[0].toLowerCase(Locale.ROOT);
        if ("showcase".startsWith(pref)) out.add("showcase");
        for (Vitrina v : plugin.almacen().todas()) {
            if (!v.vacia() && v.nombre().toLowerCase(Locale.ROOT).startsWith(pref)) out.add(v.nombre());
        }
        return out;
    }
}
