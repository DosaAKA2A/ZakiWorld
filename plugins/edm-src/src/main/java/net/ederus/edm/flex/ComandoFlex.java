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
import net.kyori.adventure.text.format.TextDecoration;

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

    private void ayuda(Player p, String etiqueta) {
        p.sendMessage(net.ederus.edm.comun.Estilo.degradado("VITRINA",
                FlexPlugin.MAGENTA, FlexPlugin.CARMESI));
        linea(p, "/" + etiqueta, "monta la tuya: clic en un objeto y se copia");
        linea(p, "/" + etiqueta + " <jugador>", "mira la de otro");
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
