package net.ederus.edm.dungeonloot;

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
 * /dl: la puerta de las cajas de mazmorra.
 *
 * Sin argumentos abre el menu, que es como se trabaja. Los subcomandos estan para
 * lo que el menu no puede hacer comodo: dar una llave a otro jugador desde una
 * recompensa o desde el botin de un mob.
 */
public final class ComandoDl implements CommandExecutor, TabCompleter {

    private final DungeonLootPlugin plugin;

    public ComandoDl(DungeonLootPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        if (!quien.hasPermission("ederus.dl")) {
            quien.sendMessage(plugin.aviso("No puedes usar las cajas de mazmorra."));
            return true;
        }

        if (args.length == 0) {
            if (!(quien instanceof Player p)) {
                ayuda(quien, etiqueta);
                return true;
            }
            plugin.menu().abrirLista(p);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "llave" -> llave(quien, args);
            case "boveda", "bóveda" -> boveda(quien, args);
            case "lista" -> lista(quien);
            case "reload" -> {
                quien.sendMessage(plugin.aviso("Recargado: " + plugin.recargar()));
            }
            default -> ayuda(quien, etiqueta);
        }
        return true;
    }

    /** /dl llave <caja> [jugador] [cantidad] — pensado para el botin de un mob. */
    private void llave(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(plugin.aviso("Uso: /dl llave <caja> [jugador] [cantidad]"));
            return;
        }
        Caja caja = plugin.registro().caja(args[1]);
        if (caja == null) {
            quien.sendMessage(plugin.aviso("No hay ninguna caja con el id '" + args[1] + "'."));
            return;
        }
        Player destino = destino(quien, args, 2);
        if (destino == null) {
            quien.sendMessage(plugin.aviso("Di a qué jugador se la das."));
            return;
        }
        int cantidad = entero(args, 3, 1);
        entregar(destino, caja.llave(plugin.claveLlave(), cantidad));
        quien.sendMessage(plugin.aviso(Component.text("Entregadas ", NamedTextColor.GRAY)
                .append(Component.text(cantidad + " llave(s) de ", NamedTextColor.WHITE))
                .append(caja.nombre())
                .append(Component.text(" a " + destino.getName() + ".", NamedTextColor.GRAY))));
    }

    /** /dl boveda <caja> [jugador] [cantidad] — el bloque, para plantarlo. */
    private void boveda(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(plugin.aviso("Uso: /dl boveda <caja> [jugador] [cantidad]"));
            return;
        }
        Caja caja = plugin.registro().caja(args[1]);
        if (caja == null) {
            quien.sendMessage(plugin.aviso("No hay ninguna caja con el id '" + args[1] + "'."));
            return;
        }
        Player destino = destino(quien, args, 2);
        if (destino == null) {
            quien.sendMessage(plugin.aviso("Di a qué jugador se la das."));
            return;
        }
        int cantidad = entero(args, 3, 1);
        entregar(destino, caja.bloque(plugin.claveCaja(), cantidad));
        quien.sendMessage(plugin.aviso("Entregada la bóveda de " + caja.display() + "."));
    }

    private void lista(CommandSender quien) {
        List<Caja> cajas = plugin.registro().cajas();
        if (cajas.isEmpty()) {
            quien.sendMessage(plugin.aviso("Todavía no hay ninguna caja. Créala con /dl."));
            return;
        }
        quien.sendMessage(Component.text("CAJAS DE MAZMORRA", DungeonLootPlugin.MARCA, TextDecoration.BOLD));
        for (Caja c : cajas) {
            int plantadas = plugin.registro().bovedasDe(c.id()).size();
            quien.sendMessage(Component.text("  " + c.id() + "  ", NamedTextColor.DARK_GRAY)
                    .append(c.nombre())
                    .append(Component.text("  " + c.tipo().display(), c.tipo().color()))
                    .append(Component.text("  ·  " + Registro.cuantos(c) + " objeto(s)  ·  "
                            + plantadas + " plantada(s)", NamedTextColor.GRAY)));
        }
    }

    private Player destino(CommandSender quien, String[] args, int i) {
        if (args.length > i) return plugin.core().getServer().getPlayerExact(args[i]);
        return quien instanceof Player p ? p : null;
    }

    private int entero(String[] args, int i, int pordefecto) {
        if (args.length <= i) return pordefecto;
        try {
            return Math.max(1, Math.min(64, Integer.parseInt(args[i])));
        } catch (NumberFormatException e) {
            return pordefecto;
        }
    }

    /** Lo que no cabe en el inventario cae a los pies: nunca se pierde. */
    private void entregar(Player p, org.bukkit.inventory.ItemStack item) {
        for (org.bukkit.inventory.ItemStack sobra : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), sobra);
        }
    }

    private void ayuda(CommandSender quien, String etiqueta) {
        quien.sendMessage(Component.text("CAJAS DE MAZMORRA", DungeonLootPlugin.MARCA, TextDecoration.BOLD));
        linea(quien, "/" + etiqueta, "abre el menú: crear cajas, botín y bóvedas");
        linea(quien, "/" + etiqueta + " lista", "las cajas en texto, con su id");
        linea(quien, "/" + etiqueta + " llave <caja> [jugador] [n]", "entrega llaves");
        linea(quien, "/" + etiqueta + " boveda <caja> [jugador] [n]", "entrega el bloque");
        linea(quien, "/" + etiqueta + " reload", "relee cajas.yml y bovedas.yml");
    }

    private void linea(CommandSender quien, String comando, String que) {
        quien.sendMessage(Component.text("  " + comando + "  ", NamedTextColor.WHITE)
                .append(Component.text(que, NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        List<String> out = new ArrayList<>();
        if (!quien.hasPermission("ederus.dl")) return out;
        if (args.length == 1) {
            for (String s : new String[]{"lista", "llave", "boveda", "reload"}) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("llave") || args[0].equalsIgnoreCase("boveda"))) {
            for (Caja c : plugin.registro().cajas()) {
                if (c.id().startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(c.id());
            }
        }
        return out;
    }
}
