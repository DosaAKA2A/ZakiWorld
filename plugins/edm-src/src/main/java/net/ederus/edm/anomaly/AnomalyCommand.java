package net.ederus.edm.anomaly;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.edm.anomaly.boss.Ability;
import net.ederus.edm.anomaly.core.ActiveAnomaly;
import net.ederus.edm.anomaly.core.AnomalyType;
import net.ederus.edm.anomaly.core.Compat;
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

/** El mando de consola y de chat. Todo lo que hace tiene su equivalente en el menu. */
public final class AnomalyCommand implements CommandExecutor, TabCompleter {

    private static final TextColor SOFT = TextColor.color(0x8A8A8A);
    private static final TextColor DIM = TextColor.color(0x555555);
    private static final TextColor GOLD = TextColor.color(0xFFD966);

    private final AnomalyPlugin plugin;

    public AnomalyCommand(AnomalyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!plugin.mayUseGui(sender)) {
            sender.sendMessage(plugin.prefix().append(
                    Component.text("No tienes permiso. Hace falta ", NamedTextColor.RED))
                    .append(Component.text("anomaly.gui", GOLD)));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.prefix().append(
                        Component.text("El menu solo se abre desde dentro del juego.", NamedTextColor.RED)));
                return true;
            }
            plugin.menus().openHub(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start", "iniciar" -> start(sender, args);
            case "here", "aqui" -> here(sender, args);
            case "at", "en" -> at(sender, args);
            case "stop", "parar", "detener" -> stop(sender);
            case "info", "estado" -> info(sender);
            case "abilities", "habilidades" -> abilities(sender, args);
            case "test", "probar" -> test(sender, args);
            case "hurt", "danar" -> hurt(sender, args);
            case "logros", "advancements" -> trophies(sender);
            case "reload", "recargar" -> reload(sender);
            default -> help(sender);
        }
        return true;
    }

    private void start(CommandSender sender, String[] args) {
        AnomalyType type = resolve(sender, args, 1);
        if (type == null) return;
        if (plugin.manager().active()) {
            sender.sendMessage(plugin.prefix().append(
                    Component.text("Ya hay una anomalia abierta. Cierrala con /anomaly stop.", NamedTextColor.RED)));
            return;
        }
        sender.sendMessage(plugin.prefix().append(Component.text("Buscando un sitio libre...", SOFT)));
        plugin.manager().start(type, ok -> {
            if (ok) return;
            sender.sendMessage(plugin.prefix().append(Component.text(
                    "No se encontro ningun sitio valido tras " + plugin.settings().searchAttempts()
                            + " intentos. Baja la distancia minima o el margen de proteccion.", NamedTextColor.RED)));
        });
    }

    /** Version de prueba: la abre donde estas, sin buscar ni mirar protecciones. */
    private void here(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.prefix().append(
                    Component.text("Este subcomando necesita un jugador.", NamedTextColor.RED)));
            return;
        }
        AnomalyType type = resolve(sender, args, 1);
        if (type == null) return;
        if (plugin.manager().active()) {
            sender.sendMessage(plugin.prefix().append(
                    Component.text("Ya hay una anomalia abierta.", NamedTextColor.RED)));
            return;
        }
        plugin.manager().open(type, player.getLocation());
        sender.sendMessage(plugin.prefix().append(Component.text("Abierta aqui mismo, sin comprobar protecciones.", SOFT)));
    }

    /**
     * La abre en unas coordenadas concretas, sin buscar sitio ni mirar protecciones.
     * Funciona desde consola, que es lo que permite colocarla a dedo o probarla sin
     * depender de que el terreno cumpla el elemento de la anomalia.
     */
    private void at(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(plugin.prefix().append(
                    Component.text("Uso: /anomaly at <x> <y> <z> [id] [mundo]", SOFT)));
            return;
        }
        double x, y, z;
        try {
            x = Double.parseDouble(args[1]);
            y = Double.parseDouble(args[2]);
            z = Double.parseDouble(args[3]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(plugin.prefix().append(
                    Component.text("Las coordenadas tienen que ser numeros.", NamedTextColor.RED)));
            return;
        }

        AnomalyType type = resolve(sender, args, 4);
        if (type == null) return;

        World world = null;
        if (args.length > 5) {
            world = plugin.getServer().getWorld(args[5]);
            if (world == null) {
                sender.sendMessage(plugin.prefix().append(
                        Component.text("No existe el mundo '" + args[5] + "'.", NamedTextColor.RED)));
                return;
            }
        } else if (sender instanceof Player player) {
            world = player.getWorld();
        } else {
            List<String> allowed = plugin.settings().allowedWorlds();
            world = allowed.isEmpty()
                    ? plugin.getServer().getWorlds().get(0)
                    : plugin.getServer().getWorld(allowed.get(0));
        }
        if (world == null) {
            sender.sendMessage(plugin.prefix().append(
                    Component.text("No se pudo determinar el mundo. Pasalo como ultimo argumento.",
                            NamedTextColor.RED)));
            return;
        }
        if (plugin.manager().active()) {
            sender.sendMessage(plugin.prefix().append(
                    Component.text("Ya hay una anomalia abierta.", NamedTextColor.RED)));
            return;
        }
        plugin.manager().open(type, new Location(world, x, y, z));
        sender.sendMessage(plugin.prefix().append(Component.text(
                "Abierta en " + world.getName() + " " + (int) x + " " + (int) y + " " + (int) z
                        + ", sin comprobar protecciones.", SOFT)));
    }

    private void stop(CommandSender sender) {
        if (!plugin.manager().active()) {
            sender.sendMessage(plugin.prefix().append(
                    Component.text("No hay ninguna anomalia abierta.", NamedTextColor.RED)));
            return;
        }
        plugin.manager().stop(false);
        sender.sendMessage(plugin.prefix().append(Component.text("Anomalia cerrada y escena limpiada.", SOFT)));
    }

    private void info(CommandSender sender) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("✦ ", GOLD)
                .append(Component.text("ANOMALY ", NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text("v" + AnomalyPlugin.VERSION, GOLD))
                .append(Component.text("   Iris Studio", DIM)));
        sender.sendMessage(field("Catalogo", plugin.registry().all().size() + " anomalias, "
                + plugin.registry().enabled().size() + " activas"));
        sender.sendMessage(field("Protecciones", plugin.protection().hasWorldGuard()
                ? "WorldGuard enganchado" : "sin WorldGuard, solo heuristica"));
        sender.sendMessage(field("Particulas ausentes", String.valueOf(Compat.missingParticles())));
        sender.sendMessage(field("Automaticas", plugin.settings().autoEnabled()
                ? ("cada " + plugin.settings().autoIntervalMinutes() + " min") : "apagadas"));

        ActiveAnomaly ev = plugin.manager().current();
        if (ev == null) {
            sender.sendMessage(field("Ahora mismo", plugin.manager().searching() ? "buscando sitio" : "en calma"));
        } else {
            sender.sendMessage(Component.empty());
            sender.sendMessage(field("Abierta", ev.type().display()));
            sender.sendMessage(field("Donde", ev.where().getBlockX() + " " + ev.where().getBlockY() + " "
                    + ev.where().getBlockZ() + "  en " + (ev.where().getWorld() == null ? "?" : ev.where().getWorld().getName())));
            if (ev.fight() != null) {
                sender.sendMessage(field("Fase", ev.fight().phase() + " de 3"));
                sender.sendMessage(field("Vida", ((int) (ev.fight().healthFraction() * 100)) + "%"));
            }
            sender.sendMessage(field("Peleando", ev.participants() + " jugador(es)"));
            sender.sendMessage(field("Lleva abierta", ev.elapsedSeconds() + "s"));
        }
        sender.sendMessage(Component.empty());
    }

    private void abilities(CommandSender sender, String[] args) {
        AnomalyType type = resolve(sender, args, 1);
        if (type == null) return;
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("✦ ", type.color())
                .append(Component.text(type.display(), type.color(), TextDecoration.BOLD))
                .append(Component.text("   " + type.abilities().size() + " habilidades", DIM)));
        for (Ability a : type.abilities()) {
            sender.sendMessage(Component.text("  Fase " + (a.phase() == 0 ? "-" : a.phase()) + "  ", DIM)
                    .append(Component.text(a.display(), type.color()))
                    .append(Component.text("   " + a.description(), SOFT)));
        }
        sender.sendMessage(Component.empty());
    }

    /**
     * Dispara una habilidad de la anomalia abierta sin esperar a su fase ni a su
     * enfriamiento. Con "all" las encadena separadas por su propia duracion, que es
     * la forma rapida de revisar que ninguna animacion se rompe.
     */
    private void test(CommandSender sender, String[] args) {
        ActiveAnomaly ev = plugin.manager().current();
        if (ev == null || ev.fight() == null) {
            sender.sendMessage(plugin.prefix().append(Component.text(
                    "Necesitas una anomalia abierta. Usa /anomaly here primero.", NamedTextColor.RED)));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.prefix().append(Component.text("Uso: /anomaly test <id|all>", SOFT)));
            for (Ability a : ev.type().abilities()) {
                sender.sendMessage(Component.text("  " + a.id(), GOLD)
                        .append(Component.text("   " + a.display(), SOFT)));
            }
            return;
        }
        if (args[1].equalsIgnoreCase("all")) {
            int delay = 0;
            for (Ability a : ev.type().abilities()) {
                final String id = a.id();
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (plugin.manager().current() == null) return;
                    plugin.getLogger().info("[test] " + id);
                    plugin.manager().current().fight().castNow(id);
                }, delay);
                delay += a.castTicks() + 25;
            }
            sender.sendMessage(plugin.prefix().append(Component.text(
                    "Encadenando " + ev.type().abilities().size() + " habilidades ("
                            + (delay / 20) + "s en total).", SOFT)));
            return;
        }
        if (ev.fight().castNow(args[1])) {
            sender.sendMessage(plugin.prefix().append(Component.text("Lanzada " + args[1] + ".", SOFT)));
        } else {
            sender.sendMessage(plugin.prefix().append(Component.text(
                    "No existe la habilidad '" + args[1] + "'.", NamedTextColor.RED)));
        }
    }

    /**
     * Baja la vida del jefe a mano. Sirve para ver los cambios de fase y la muerte
     * sin tener que juntar a medio servidor cada vez que se toca una animacion.
     */
    private void hurt(CommandSender sender, String[] args) {
        ActiveAnomaly ev = plugin.manager().current();
        if (ev == null || ev.fight() == null || !ev.fight().alive()) {
            sender.sendMessage(plugin.prefix().append(
                    Component.text("No hay ningun jefe vivo.", NamedTextColor.RED)));
            return;
        }
        double amount = 100;
        if (args.length > 1) {
            try {
                amount = Double.parseDouble(args[1]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(plugin.prefix().append(
                        Component.text("'" + args[1] + "' no es un numero.", NamedTextColor.RED)));
                return;
            }
        }
        LivingEntity boss = ev.fight().entity();
        boolean shielded = boss.isInvulnerable();
        boss.setInvulnerable(false);
        boss.setHealth(Math.max(0, boss.getHealth() - amount));
        if (shielded && boss.getHealth() > 0) boss.setInvulnerable(true);
        sender.sendMessage(plugin.prefix().append(Component.text(
                "Vida del jefe: " + ((int) boss.getHealth()) + "  ("
                        + ((int) (ev.fight().healthFraction() * 100)) + "%)", SOFT)));
    }

    /** Cuantas anomalias lleva derrotadas quien lo pregunta. */
    private void trophies(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.prefix().append(
                    Component.text("Este subcomando necesita un jugador.", NamedTextColor.RED)));
            return;
        }
        int owned = plugin.advancements().owned(player);
        int total = plugin.registry().all().size();
        List<String> missing = plugin.advancements().missing(player);

        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("✦ ", GOLD)
                .append(Component.text("Anomalias derrotadas  ", NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(owned + " / " + total,
                        owned == total ? NamedTextColor.GREEN : GOLD, TextDecoration.BOLD)));
        if (missing.isEmpty()) {
            sender.sendMessage(Component.text("  Las has visto todas.", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("  Te faltan  ", TextColor.color(0x404040))
                    .append(Component.text(String.join(", ", missing), SOFT)));
        }
        sender.sendMessage(Component.empty());
    }

    private void reload(CommandSender sender) {
        plugin.reloadEverything();
        sender.sendMessage(plugin.prefix().append(Component.text(
                "Recargado: config.yml y drops.yml. La anomalia abierta, si la habia, se cerro.", SOFT)));
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("✦ ", GOLD).append(Component.text("ANOMALY", NamedTextColor.WHITE, TextDecoration.BOLD)));
        line(sender, "/anomaly", "abre el panel");
        line(sender, "/anomaly start [id]", "busca sitio y abre la anomalia");
        line(sender, "/anomaly here [id]", "la abre donde estas, sin comprobaciones");
        line(sender, "/anomaly at <x> <y> <z> [id]", "la abre en esas coordenadas");
        line(sender, "/anomaly stop", "la cierra y limpia la escena");
        line(sender, "/anomaly info", "estado del plugin y del evento");
        line(sender, "/anomaly abilities [id]", "lista las habilidades");
        line(sender, "/anomaly test <id|all>", "lanza una habilidad ya, para revisarla");
        line(sender, "/anomaly hurt <vida>", "le baja vida a mano, para ver las fases");
        line(sender, "/anomaly logros", "cuantas anomalias llevas derrotadas");
        line(sender, "/anomaly reload", "recarga la configuracion");
        sender.sendMessage(Component.empty());
    }

    private void line(CommandSender sender, String cmd, String what) {
        sender.sendMessage(Component.text("  " + cmd, GOLD).append(Component.text("   " + what, SOFT)));
    }

    private Component field(String label, String value) {
        return Component.text("  " + label + "  ", TextColor.color(0x404040)).append(Component.text(value, SOFT));
    }

    /** Coge el id del argumento; si no hay, usa la anomalia elegida en el menu. */
    private AnomalyType resolve(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            AnomalyType type = plugin.registry().get(args[index].toLowerCase(Locale.ROOT));
            if (type == null) {
                sender.sendMessage(plugin.prefix().append(
                        Component.text("No existe la anomalia '" + args[index] + "'.", NamedTextColor.RED)));
                return null;
            }
            return type;
        }
        AnomalyType type = plugin.selected();
        if (type == null) {
            sender.sendMessage(plugin.prefix().append(
                    Component.text("No hay ninguna anomalia elegida. Usa /anomaly start <id>.", NamedTextColor.RED)));
        }
        return type;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!plugin.mayUseGui(sender)) return out;
        if (args.length == 1) {
            for (String s : List.of("menu", "start", "here", "at", "stop", "info", "abilities", "test", "hurt", "logros", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        if (args.length == 5 && List.of("at", "en").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (AnomalyType t : plugin.registry().all()) {
                if (t.id().startsWith(args[4].toLowerCase(Locale.ROOT))) out.add(t.id());
            }
            return out;
        }
        if (args.length == 2 && List.of("start", "here", "abilities").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (AnomalyType t : plugin.registry().all()) {
                if (t.id().startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(t.id());
            }
        }
        if (args.length == 2 && List.of("test", "probar").contains(args[0].toLowerCase(Locale.ROOT))) {
            AnomalyType t = plugin.selected();
            if (t != null) {
                out.add("all");
                for (Ability a : t.abilities()) {
                    if (a.id().startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(a.id());
                }
            }
        }
        return out;
    }
}
