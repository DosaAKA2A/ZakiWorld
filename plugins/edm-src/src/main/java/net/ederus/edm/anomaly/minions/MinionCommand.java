package net.ederus.edm.anomaly.minions;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.comun.Fx;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * El mando de los esbirros: /esb.
 *
 * Los jefes viven en /anomaly y la tropa aqui. Sin argumentos abre las carpetas;
 * con el id de un esbirro invoca uno suelto, que es como se prueban sin montar un
 * generador. Desde consola hacen falta las coordenadas, porque no hay "aqui".
 */
public final class MinionCommand implements CommandExecutor, TabCompleter {

    private static final TextColor SOFT = TextColor.color(0x8A8A8A);
    private static final TextColor GOLD = TextColor.color(0xFFD966);

    private final AnomalyPlugin plugin;

    public MinionCommand(AnomalyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!plugin.mayUseGui(sender)) {
            sender.sendMessage(plugin.prefix()
                    .append(Component.text("No tienes permiso. Hace falta ", NamedTextColor.RED))
                    .append(Component.text("anomaly.gui", GOLD)));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.prefix().append(
                        Component.text("El menú solo se abre desde dentro del juego.", NamedTextColor.RED)));
                return true;
            }
            plugin.menus().openMinions(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "lista", "list" -> list(sender);
            case "reload", "recargar" -> reload(sender);
            case "ayuda", "help", "?" -> help(sender, label);
            default -> spawn(sender, args);
        }
        return true;
    }

    /**
     * Relee esbirros.yml sin reiniciar el servidor: es lo que hace falta cuando el
     * fichero se toca a mano. La tropa viva se barre primero, porque un esbirro que
     * quedara apuntando a un tipo renombrado se quedaria sin cartel ni nivel; sus
     * generadores la reponen en cuanto vuelva a pasar alguien.
     */
    private void reload(CommandSender sender) {
        int barridos = plugin.minionManager().sweep();
        plugin.minions().load();
        int carpetas = plugin.minions().categories().size();
        int tipos = plugin.minions().types().size();
        int generadores = plugin.minions().spawners().size();
        sender.sendMessage(plugin.prefix()
                .append(Component.text("Esbirros recargados  ", NamedTextColor.GREEN))
                .append(Component.text(carpetas + " carpeta(s)  ·  " + tipos + " tipo(s)  ·  "
                        + generadores + " generador(es)", SOFT)));
        if (barridos > 0) {
            sender.sendMessage(plugin.prefix().append(Component.text(
                    "Se retiraron " + barridos + " esbirro(s) que estaban en el mapa; sus "
                            + "generadores los reponen solos.", SOFT)));
        }
    }

    /** El catalogo en texto, por carpetas: para verlo desde consola. */
    private void list(CommandSender sender) {
        List<MinionCategory> cats = plugin.minions().categories();
        if (cats.isEmpty()) {
            sender.sendMessage(plugin.prefix().append(Component.text(
                    "Todavía no hay ninguna carpeta de esbirros.", SOFT)));
            return;
        }
        for (MinionCategory cat : cats) {
            List<MinionType> dentro = plugin.minions().typesOf(cat.id());
            sender.sendMessage(Component.text("✦ ", cat.color())
                    .append(Component.text(cat.display(), cat.color(), TextDecoration.BOLD))
                    .append(Component.text("  " + dentro.size() + " esbirro(s)", SOFT)));
            for (MinionType t : dentro) {
                sender.sendMessage(Component.text("   · ", SOFT)
                        .append(Component.text(t.display(), t.color()))
                        .append(Component.text("  " + t.id(), SOFT))
                        .append(Component.text("  Nv. " + t.wandMinLevel() + "-" + t.wandMaxLevel()
                                + "  ·  " + (int) t.baseHealth() + " vida base"
                                + "  ·  " + t.abilities().size() + " rasgo(s)", SOFT)));
            }
        }
    }

    /** /esb <id> [nivel] [x y z]: uno suelto, sin generador, para verlo y pegarle. */
    private void spawn(CommandSender sender, String[] args) {
        MinionType type = plugin.minions().type(args[0].toLowerCase(Locale.ROOT));
        if (type == null) {
            sender.sendMessage(plugin.prefix().append(Component.text(
                    "No hay ningun esbirro con id " + args[0] + ". Mira /esb lista.", NamedTextColor.RED)));
            return;
        }

        int level = type.wandMinLevel();
        if (args.length > 1) {
            try {
                level = Math.max(1, Math.min(1000, Integer.parseInt(args[1])));
            } catch (NumberFormatException ex) {
                sender.sendMessage(plugin.prefix().append(Component.text(
                        "El nivel tiene que ser un número.", NamedTextColor.RED)));
                return;
            }
        }

        Location where;
        if (args.length >= 5) {
            World world = sender instanceof Player p ? p.getWorld() : plugin.getServer().getWorlds().get(0);
            try {
                where = new Location(world, Double.parseDouble(args[2]) + 0.5,
                        Double.parseDouble(args[3]), Double.parseDouble(args[4]) + 0.5);
            } catch (NumberFormatException ex) {
                sender.sendMessage(plugin.prefix().append(Component.text(
                        "Las coordenadas tienen que ser números.", NamedTextColor.RED)));
                return;
            }
        } else if (sender instanceof Player player) {
            where = Fx.ground(player.getLocation().add(player.getLocation().getDirection()
                    .setY(0).normalize().multiply(2.5)), 4);
        } else {
            sender.sendMessage(plugin.prefix().append(Component.text(
                    "Desde la consola hacen falta las coordenadas: /esb <id> <nivel> <x> <y> <z>",
                    NamedTextColor.RED)));
            return;
        }

        LivingEntity mob = plugin.minionManager().spawnAt(type, level, where, null);
        if (mob == null) {
            sender.sendMessage(plugin.prefix().append(Component.text(
                    "No se pudo invocar ahi.", NamedTextColor.RED)));
            return;
        }
        sender.sendMessage(plugin.prefix()
                .append(Component.text("Invocado  ", NamedTextColor.GREEN))
                .append(type.name())
                .append(Component.text("  Nv. " + level + "  con " + (int) type.healthAt(level)
                        + " de vida y x" + type.damageAt(level) + " de daño.", SOFT)));
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage(plugin.prefix().append(Component.text("Los esbirros de mazmorra", GOLD)));
        line(sender, "/" + label, "las carpetas de esbirros");
        line(sender, "/" + label + " lista", "el catálogo en texto, por carpetas");
        line(sender, "/" + label + " reload", "relee esbirros.yml sin reiniciar");
        line(sender, "/" + label + " <id> [nivel] [x y z]", "invoca uno suelto, para verlo");
    }

    private void line(CommandSender sender, String cmd, String what) {
        sender.sendMessage(Component.text("  " + cmd + "  ", GOLD).append(Component.text(what, SOFT)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!plugin.mayUseGui(sender)) return out;
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String s : List.of("menu", "lista", "reload", "ayuda")) {
                if (s.startsWith(prefix)) out.add(s);
            }
            for (MinionType t : plugin.minions().types()) {
                if (t.id().startsWith(prefix)) out.add(t.id());
            }
        } else if (args.length == 2 && plugin.minions().type(args[0].toLowerCase(Locale.ROOT)) != null) {
            MinionType t = plugin.minions().type(args[0].toLowerCase(Locale.ROOT));
            out.add(String.valueOf(t.wandMinLevel()));
            out.add(String.valueOf(t.wandMaxLevel()));
        }
        return out;
    }
}
