package net.ederus.edm.boost;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.ederus.edm.comun.Estilo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * /boost: el panel para el jugador y las ordenes para el staff y para las cajas.
 *
 * Los subcomandos van en ingles, como todo lo que se escribe en Modulos; lo que lee
 * el jugador va en espanol. La caja epica llama a:
 *     boost give %player_name% exp 60
 * y la legendaria a lo mismo con 120.
 */
final class ComandoBoost implements CommandExecutor, TabCompleter {

    static final String PERMISO_ADMIN = "ederus.boost.admin";

    private final BoostPlugin modulo;

    ComandoBoost(BoostPlugin modulo) {
        this.modulo = modulo;
    }

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        if (args.length == 0) {
            if (quien instanceof Player p) {
                modulo.menu().abrir(p);
            } else {
                ayuda(quien, etiqueta);
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("help")) {
            ayuda(quien, etiqueta);
            return true;
        }
        if (!quien.hasPermission(PERMISO_ADMIN)) {
            modulo.textos().manda(quien, "sin-permiso", "&cNo puedes usar eso.");
            return true;
        }

        switch (sub) {
            case "give" -> dar(quien, args, etiqueta);
            case "clear" -> limpiar(quien, args, etiqueta);
            case "global" -> global(quien, args, etiqueta);
            case "list" -> listar(quien, args);
            default -> ayuda(quien, etiqueta);
        }
        return true;
    }

    /* -------------------------------------------------------------------- give */

    private void dar(CommandSender quien, String[] args, String etiqueta) {
        if (args.length < 4) {
            quien.sendMessage(uso(etiqueta + " give <player> <exp|drops|minions|all> <minutes> [multiplier]"));
            return;
        }
        Player objetivo = Bukkit.getPlayerExact(args[1]);
        if (objetivo == null) {
            modulo.textos().manda(quien, "no-esta", "&cEse jugador no esta conectado.", "%jugador%", args[1]);
            return;
        }
        List<Tipo> tipos = tipos(args[2]);
        if (tipos.isEmpty()) {
            modulo.textos().manda(quien, "tipo-malo", "&cTipos: exp, drops, minions, all.");
            return;
        }
        long minutos = entero(args[3]);
        if (minutos <= 0) {
            modulo.textos().manda(quien, "minutos-malos", "&cLos minutos tienen que ser un numero mayor que 0.");
            return;
        }
        double mult = args.length > 4 ? decimal(args[4], modulo.multiplicadorPorDefecto())
                : modulo.multiplicadorPorDefecto();
        if (mult <= 1.0) mult = modulo.multiplicadorPorDefecto();

        for (Tipo t : tipos) modulo.dar(objetivo, t, minutos * 60_000L, mult);

        String nombres = nombres(tipos);
        modulo.textos().manda(quien, "dado", "&aLe diste &f%tipos% &aa &f%jugador% &apor &f%tiempo%&a.",
                "%tipos%", nombres, "%jugador%", objetivo.getName(), "%tiempo%", minutos + "m",
                "%multiplicador%", MenuBoost.recorta(mult));
    }

    /* ------------------------------------------------------------------- clear */

    private void limpiar(CommandSender quien, String[] args, String etiqueta) {
        if (args.length < 2) {
            quien.sendMessage(uso(etiqueta + " clear <player> [exp|drops|minions]"));
            return;
        }
        Player objetivo = Bukkit.getPlayerExact(args[1]);
        if (objetivo == null) {
            modulo.textos().manda(quien, "no-esta", "&cEse jugador no esta conectado.", "%jugador%", args[1]);
            return;
        }
        if (args.length > 2) {
            Tipo t = Tipo.de(args[2]);
            if (t == null) {
                modulo.textos().manda(quien, "tipo-malo", "&cTipos: exp, drops, minions, all.");
                return;
            }
            modulo.servicio().quitar(objetivo.getUniqueId(), t);
        } else {
            modulo.servicio().quitarTodo(objetivo.getUniqueId());
        }
        modulo.textos().manda(quien, "limpiado", "&aBoosts retirados a &f%jugador%&a.",
                "%jugador%", objetivo.getName());
    }

    /* ------------------------------------------------------------------ global */

    private void global(CommandSender quien, String[] args, String etiqueta) {
        if (args.length < 3) {
            quien.sendMessage(uso(etiqueta + " global <exp|drops|minions|all> <minutes> [multiplier]"));
            return;
        }
        List<Tipo> tipos = tipos(args[1]);
        if (tipos.isEmpty()) {
            modulo.textos().manda(quien, "tipo-malo", "&cTipos: exp, drops, minions, all.");
            return;
        }
        long minutos = entero(args[2]);
        if (minutos <= 0) {
            modulo.textos().manda(quien, "minutos-malos", "&cLos minutos tienen que ser un numero mayor que 0.");
            return;
        }
        double mult = args.length > 3 ? decimal(args[3], modulo.multiplicadorPorDefecto())
                : modulo.multiplicadorPorDefecto();
        for (Tipo t : tipos) modulo.darGlobal(t, minutos * 60_000L, mult);
        modulo.textos().manda(quien, "global-puesto", "&aBoost global de &f%tipos% &apor &f%tiempo%&a.",
                "%tipos%", nombres(tipos), "%tiempo%", minutos + "m");
    }

    /* -------------------------------------------------------------------- list */

    private void listar(CommandSender quien, String[] args) {
        if (args.length > 1) {
            Player objetivo = Bukkit.getPlayerExact(args[1]);
            if (objetivo == null) {
                modulo.textos().manda(quien, "no-esta", "&cEse jugador no esta conectado.", "%jugador%", args[1]);
                return;
            }
            quien.sendMessage(Estilo.titulo("BOOSTS", objetivo.getName()));
            for (Tipo t : Tipo.values()) {
                Servicio.Activo a = modulo.servicio().personal(objetivo.getUniqueId(), t);
                quien.sendMessage(linea(t.nombre(), a));
            }
            return;
        }
        quien.sendMessage(Estilo.titulo("BOOSTS", "globales"));
        for (Tipo t : Tipo.values()) {
            quien.sendMessage(linea(t.nombre(), modulo.servicio().global(t)));
        }
        int cuantos = modulo.servicio().conBoost().size();
        quien.sendMessage(Estilo.texto("Jugadores con boost personal: " + cuantos, NamedTextColor.GRAY));
    }

    private Component linea(String nombre, Servicio.Activo a) {
        if (a == null) {
            return Estilo.texto("  " + nombre + ": ", NamedTextColor.GRAY)
                    .append(Estilo.texto("nada", NamedTextColor.DARK_GRAY));
        }
        return Estilo.texto("  " + nombre + ": ", NamedTextColor.GRAY)
                .append(Estilo.texto("x" + MenuBoost.recorta(a.multiplicador())
                        + " durante " + Servicio.reloj(a.restanteMs()), NamedTextColor.WHITE));
    }

    /* ----------------------------------------------------------------- ayudas */

    private void ayuda(CommandSender quien, String etiqueta) {
        quien.sendMessage(Estilo.titulo("BOOSTS", "ayuda"));
        quien.sendMessage(Estilo.texto("  /" + etiqueta + "  ", NamedTextColor.WHITE)
                .append(Estilo.texto("tus boosts y lo que les queda", NamedTextColor.GRAY)));
        if (!quien.hasPermission(PERMISO_ADMIN)) return;
        quien.sendMessage(uso(etiqueta + " give <player> <exp|drops|minions|all> <minutes> [multiplier]"));
        quien.sendMessage(uso(etiqueta + " clear <player> [type]"));
        quien.sendMessage(uso(etiqueta + " global <type> <minutes> [multiplier]"));
        quien.sendMessage(uso(etiqueta + " list [player]"));
    }

    private Component uso(String s) {
        return Estilo.texto("  /" + s, NamedTextColor.GRAY);
    }

    private static List<Tipo> tipos(String s) {
        List<Tipo> out = new ArrayList<>();
        if (s == null) return out;
        if (s.equalsIgnoreCase("all") || s.equalsIgnoreCase("todo")) {
            for (Tipo t : Tipo.values()) out.add(t);
            return out;
        }
        Tipo t = Tipo.de(s);
        if (t != null) out.add(t);
        return out;
    }

    private static String nombres(List<Tipo> tipos) {
        if (tipos.size() == Tipo.values().length) return "todos los boosts";
        List<String> n = new ArrayList<>();
        for (Tipo t : tipos) n.add(t.nombre());
        return String.join(", ", n);
    }

    private static long entero(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double decimal(String s, double porDefecto) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return porDefecto;
        }
    }

    /* ------------------------------------------------------------------- tab */

    @Override
    public List<String> onTabComplete(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        List<String> out = new ArrayList<>();
        if (!quien.hasPermission(PERMISO_ADMIN)) return out;
        if (args.length == 1) {
            for (String s : List.of("give", "clear", "global", "list", "help")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("give") || sub.equals("clear") || sub.equals("list"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
            return out;
        }
        boolean pideTipo = (args.length == 3 && sub.equals("give"))
                || (args.length == 3 && sub.equals("clear"))
                || (args.length == 2 && sub.equals("global"));
        if (pideTipo) {
            String prefijo = args[args.length - 1].toLowerCase(Locale.ROOT);
            for (Tipo t : Tipo.values()) if (t.id().startsWith(prefijo)) out.add(t.id());
            if ("all".startsWith(prefijo) && !sub.equals("clear")) out.add("all");
            return out;
        }
        boolean pideMinutos = (args.length == 4 && sub.equals("give")) || (args.length == 3 && sub.equals("global"));
        if (pideMinutos) out.addAll(List.of("30", "60", "120"));
        return out;
    }
}
