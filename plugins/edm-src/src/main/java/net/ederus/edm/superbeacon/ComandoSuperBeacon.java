package net.ederus.edm.superbeacon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.ederus.edm.comun.Estilo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * /superbeacon (alias /sbeacon).
 *
 * Los subcomandos y sus argumentos fijos van en ingles, como todos los comandos de
 * Ederus; lo que se lee, en español. Sin argumentos, cada jugador ve sus balizas
 * colocadas (y recibe lo que tenga pendiente). El resto es del staff y esta pensado para
 * la consola: la tienda, las cajas y el futuro ranking de clanes llaman a
 *
 *     superbeacon give %player_name% granja
 *     superbeacon give %player_name% trofeo - ABC
 *     superbeacon give %player_name% guerra - ABC      (- = los dias del tipo)
 */
final class ComandoSuperBeacon implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMANDOS =
            List.of("give", "renew", "types", "list", "info", "remove", "reload", "selftest", "help");

    private final SuperBeaconPlugin plugin;

    ComandoSuperBeacon(SuperBeaconPlugin plugin) {
        this.plugin = plugin;
    }

    private SuperBeaconPlugin.TextosBaliza tx() {
        return plugin.textos();
    }

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        if (args.length == 0) {
            if (quien instanceof Player p) propias(p);
            else ayuda(quien, etiqueta);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("help")) {
            ayuda(quien, etiqueta);
            return true;
        }
        if (!plugin.esAdmin(quien)) {
            tx().manda(quien, "sin-permiso", "&#FF5C5CNo puedes usar eso.");
            return true;
        }
        switch (sub) {
            case "give" -> dar(quien, args, etiqueta);
            case "renew" -> renovar(quien, args, etiqueta);
            case "types" -> tipos(quien);
            case "list" -> listar(quien, args);
            case "info" -> info(quien);
            case "remove" -> quitar(quien, args, etiqueta);
            case "reload" -> tx().manda(quien, "recargado", "&fRecargado&8: &7%que%", "%que%", plugin.recargar());
            case "selftest" -> autotest(quien);
            default -> ayuda(quien, etiqueta);
        }
        return true;
    }

    /* ============================================================= jugador */

    private void propias(Player p) {
        List<Baliza> suyas = plugin.registro().de(p.getUniqueId());
        if (suyas.isEmpty()) {
            tx().manda(p, "lista-vacia", "&7No tienes Super Beacons colocados.");
        } else {
            tx().manda(p, "lista-cabecera", "&7Tus Super Beacons colocados: &f%n%", "%n%", String.valueOf(suyas.size()));
            long ahora = System.currentTimeMillis();
            for (Baliza b : suyas) {
                tx().manda(p, "lista-linea", "{sin-prefijo} &#545454▸ %nombre% &#8A8A8A%donde% &8· &f%estado%",
                        "%nombre%", nombre(b), "%donde%", b.donde(), "%estado%", plugin.restante(b, ahora));
            }
        }
        plugin.entregas().entregarTodo(p);
    }

    /* ================================================================ give */

    private void dar(CommandSender quien, String[] args, String etiqueta) {
        if (args.length < 3) {
            tx().manda(quien, "give-uso", "&7Uso: &f/%comando% give <player> <type> [days|-] [clan]",
                    "%comando%", etiqueta);
            return;
        }
        TipoBaliza t = plugin.tipo(args[2].toLowerCase(Locale.ROOT));
        if (t == null) {
            tx().manda(quien, "tipo-malo", "&#FF5C5CEse tipo no existe. &7Tipos: &f%tipos%",
                    "%tipos%", String.join(", ", plugin.tipos().keySet()));
            return;
        }
        // Dias con decimales tambien (0.01 son unos 14 minutos): asi se prueba un
        // vencimiento sin esperar un dia entero ni tocar data.yml.
        Double dias = null;
        if (args.length > 3 && !args[3].equals("-")) {
            try {
                dias = Double.parseDouble(args[3].replace(',', '.'));
            } catch (NumberFormatException e) {
                dias = -1.0;
            }
            if (dias < 0 || dias.isNaN() || dias.isInfinite() || dias > 36_500) {
                tx().manda(quien, "dias-malos", "&#FF5C5CLos días tienen que ser un número, 0 o más (o - para los del tipo).");
                return;
            }
        }
        // Limpio igual que el clan que sale de PlaceholderAPI (Clanes.limpiar): si el ranking
        // pasa el tag con colores ("&#FF0000ABC", el mismo %uclans_tag_color%), sin esto no
        // coincidiria nunca con el de los jugadores y el trofeo no le daria nada a su clan.
        String clan = args.length > 4 ? Clanes.limpiar(String.join(" ", Arrays.copyOfRange(args, 4, args.length))) : null;
        if (clan != null && clan.isEmpty()) clan = null;
        plugin.entregas().dar(quien, args[1], t, dias, clan);
    }

    /* =============================================================== renew */

    /**
     * 1.78.1 · /superbeacon renew <clan> <days> [type]: renueva la caducidad de las balizas con ese
     * clan fijado (el trofeo del clan que vuelve a ganar). Lo llama el ranking de clanes de Calamity.
     */
    private void renovar(CommandSender quien, String[] args, String etiqueta) {
        if (args.length < 3) {
            tx().manda(quien, "renew-uso", "&7Uso: &f/%comando% renew <clan> <days> [type]", "%comando%", etiqueta);
            return;
        }
        double dias;
        try {
            dias = Double.parseDouble(args[2].replace(',', '.'));
        } catch (NumberFormatException e) {
            dias = -1;
        }
        if (dias < 0 || Double.isNaN(dias) || Double.isInfinite(dias) || dias > 36_500) {
            tx().manda(quien, "dias-malos", "&#FF5C5CLos días tienen que ser un número, 0 o más (o - para los del tipo).");
            return;
        }
        String tipo = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : null;
        int n = plugin.renovar(args[1], tipo, dias, "comando");
        if (n == 0) {
            tx().manda(quien, "renew-nada", "&7El clan &f%clan% &7no tiene ningún Super Beacon que renovar.", "%clan%", args[1]);
        } else {
            tx().manda(quien, "renew-hecho", "&fRenovados &#D7F3FF%n% &fSuper Beacon(s) del clan &#D7F3FF%clan%&f.",
                    "%n%", String.valueOf(n), "%clan%", args[1]);
        }
    }

    /* ======================================================= types y list */

    private void tipos(CommandSender quien) {
        tx().manda(quien, "tipos-cabecera", "&7Tipos de Super Beacon: &f%n%", "%n%", String.valueOf(plugin.tipos().size()));
        for (TipoBaliza t : plugin.tipos().values()) {
            String duracion = t.duracionDias > 0 ? Numeros.decimal(t.duracionDias) + " d"
                    : tx().crudo("permanente", "Permanente");
            String efectos = t.fijo() ? t.efectos.size() + " efectos, todos" : t.elegibles + " de " + t.efectos.size() + " efectos";
            tx().manda(quien, "tipos-linea",
                    "{sin-prefijo} &#545454▸ &f%id% &8· %nombre% &#8A8A8A· radio %radio% · %beneficia% · %duracion% · %efectos%",
                    "%id%", t.id, "%nombre%", t.nombre, "%radio%", String.valueOf(t.radio),
                    "%beneficia%", plugin.beneficiaTexto(t.beneficia), "%duracion%", duracion, "%efectos%", efectos);
        }
    }

    private static final int MAX_LINEAS = 40;

    private void listar(CommandSender quien, String[] args) {
        String filtro = args.length > 1 ? args[1] : null;
        UUID filtroId = null;
        if (filtro != null) {
            Player p = Bukkit.getPlayerExact(filtro);
            if (p != null) filtroId = p.getUniqueId();
        }
        List<Baliza> lista = new ArrayList<>();
        for (Baliza b : plugin.registro().todas()) {
            if (filtro == null || (filtroId != null && b.esDe(filtroId))
                    || (b.duenoNombre != null && b.duenoNombre.equalsIgnoreCase(filtro))) {
                lista.add(b);
            }
        }
        tx().manda(quien, "admin-cabecera", "&7Super Beacons colocados%de%: &f%n%",
                "%de%", filtro == null ? "" : " de " + filtro, "%n%", String.valueOf(lista.size()));
        long ahora = System.currentTimeMillis();
        int n = 0;
        for (Baliza b : lista) {
            if (n++ >= MAX_LINEAS) {
                tx().manda(quien, "admin-mas", "{sin-prefijo} &#8A8A8A... y %n% más.", "%n%", String.valueOf(lista.size() - MAX_LINEAS));
                break;
            }
            tx().manda(quien, "admin-linea",
                    "{sin-prefijo} &#545454▸ &#8A8A8A%id% %nombre% &#8A8A8A%dueno% · %donde% &8· &f%estado%",
                    "%id%", b.idCorto(), "%nombre%", nombre(b), "%dueno%", b.duenoTexto(), "%donde%", b.donde(),
                    "%estado%", plugin.restante(b, ahora));
        }
        List<Pendiente> pendientes = new ArrayList<>();
        for (Pendiente pe : plugin.registro().pendientes()) {
            if (filtro == null || (filtroId != null && filtroId.equals(pe.para))
                    || (pe.paraNombre != null && pe.paraNombre.equalsIgnoreCase(filtro))) {
                pendientes.add(pe);
            }
        }
        if (pendientes.isEmpty()) return;
        tx().manda(quien, "admin-pendientes", "&7Pendientes de entregar: &f%n%", "%n%", String.valueOf(pendientes.size()));
        n = 0;
        for (Pendiente pe : pendientes) {
            if (n++ >= MAX_LINEAS) break;
            tx().manda(quien, "admin-pendiente",
                    "{sin-prefijo} &#545454▸ &#8A8A8A%id% %nombre% &#8A8A8Apara %para% · %motivo%",
                    "%id%", pe.ficha.id().toString().substring(0, 8), "%nombre%", nombreTipo(pe.ficha.tipo()),
                    "%para%", pe.paraTexto(), "%motivo%", pe.motivo);
        }
    }

    /* ================================================================ info */

    private void info(CommandSender quien) {
        if (!(quien instanceof Player p)) {
            tx().manda(quien, "solo-en-juego", "&7Eso se usa dentro del juego.");
            return;
        }
        Block bl = p.getTargetBlockExact(8);
        Baliza b = bl == null ? null : plugin.registro().en(bl);
        if (b == null) {
            tx().manda(p, "info-nada", "&7No estás mirando ningún Super Beacon.");
            return;
        }
        TipoBaliza t = plugin.tipo(b.tipo);
        long ahora = System.currentTimeMillis();
        p.sendMessage(Estilo.cabecera("Super Beacon", b.idCorto(), Estilo.MARCA));
        linea(p, "Id", b.id.toString());
        linea(p, "Tipo", b.tipo + (t == null ? " (ya no existe)" : ""));
        linea(p, "Dueño", b.duenoTexto() + (b.dueno == null ? "" : " (" + b.dueno + ")"));
        linea(p, "Clan", b.clan != null ? b.clan + " (fijado al darlo)"
                : "sin fijar" + (t != null && t.beneficia == TipoBaliza.Beneficia.CLAN
                        ? ", el actual de su dueño: " + valor(plugin.motor().clanDe(b)) : ""));
        linea(p, "Sitio", b.donde() + " · " + b.material.name());
        linea(p, "Colocado", Tiempo.fecha(b.colocada, plugin.zona()));
        linea(p, "Vence", b.vence <= 0 ? "no caduca" : Tiempo.fecha(b.vence, plugin.zona()) + " · " + plugin.restante(b, ahora));
        if (t != null) {
            List<String> activos = new ArrayList<>();
            for (Efecto e : plugin.motor().activos(b, t)) {
                activos.add(e.clave() + (e.falta() != null ? " (" + e.falta() + ")" : ""));
            }
            linea(p, "Activos", activos.isEmpty() ? "ninguno" : String.join(", ", activos));
            linea(p, "Alcance", t.radio + " bloques · beneficia " + plugin.beneficiaTexto(t.beneficia));
        }
    }

    private static String valor(String s) {
        return s == null ? "ninguno" : s;
    }

    private static void linea(CommandSender a, String etiqueta, String valor) {
        a.sendMessage(Estilo.linea(etiqueta, valor, NamedTextColor.WHITE));
    }

    /* ============================================================== remove */

    private void quitar(CommandSender quien, String[] args, String etiqueta) {
        if (args.length < 2) {
            tx().manda(quien, "remove-uso", "&7Uso: &f/%comando% remove <id>", "%comando%", etiqueta);
            return;
        }
        String id = args[1].trim().toLowerCase(Locale.ROOT);
        List<Baliza> encontradas = id.length() < 4 ? List.of() : plugin.registro().buscar(id);
        if (encontradas.isEmpty()) {
            tx().manda(quien, "no-encontrado", "&#FF5C5CNo hay ningún Super Beacon colocado con el id &f%id%&#FF5C5C.",
                    "%id%", args[1]);
            return;
        }
        if (encontradas.size() > 1) {
            tx().manda(quien, "ambiguo", "&#FF5C5CHay varios Super Beacons que empiezan por &f%id%&#FF5C5C: escribe más letras.",
                    "%id%", args[1]);
            return;
        }
        plugin.entregas().retirar(quien, encontradas.get(0));
    }

    /* ============================================================ selftest */

    private void autotest(CommandSender quien) {
        AutotestSuperBeacon t = AutotestSuperBeacon.correr(plugin);
        quien.sendMessage(Estilo.cabecera("Super Beacon", "selftest", Estilo.MARCA));
        for (String l : t.lineas()) quien.sendMessage(Estilo.legado(l));
        quien.sendMessage(Estilo.legado(t.fallos() == 0 ? "&aTodo bien: " + t.oks() + " comprobaciones."
                : "&c" + t.fallos() + " fallo(s) de " + (t.oks() + t.fallos()) + "."));
        plugin.getLogger().info("[SuperBeacon] selftest: " + t.oks() + " OK, " + t.fallos() + " fallos");
    }

    /* ============================================================== ayudas */

    private void ayuda(CommandSender quien, String etiqueta) {
        quien.sendMessage(Estilo.cabecera("Super Beacon", "ayuda", Estilo.MARCA));
        quien.sendMessage(Estilo.texto("  /" + etiqueta + "  ", NamedTextColor.WHITE)
                .append(Estilo.texto("tus Super Beacons colocados", NamedTextColor.GRAY)));
        if (!plugin.esAdmin(quien)) return;
        quien.sendMessage(uso(etiqueta + " give <player> <type> [days|-] [clan]"));
        quien.sendMessage(uso(etiqueta + " renew <clan> <days> [type]"));
        quien.sendMessage(uso(etiqueta + " types"));
        quien.sendMessage(uso(etiqueta + " list [player]"));
        quien.sendMessage(uso(etiqueta + " info"));
        quien.sendMessage(uso(etiqueta + " remove <id>"));
        quien.sendMessage(uso(etiqueta + " reload"));
        quien.sendMessage(uso(etiqueta + " selftest"));
    }

    private static Component uso(String s) {
        return Estilo.texto("  /" + s, NamedTextColor.GRAY);
    }

    private String nombre(Baliza b) {
        return nombreTipo(b.tipo);
    }

    private String nombreTipo(String tipo) {
        TipoBaliza t = plugin.tipo(tipo);
        return t != null ? t.nombre : "&7" + tipo;
    }

    /* ================================================================= tab */

    @Override
    public List<String> onTabComplete(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        List<String> out = new ArrayList<>();
        if (!plugin.esAdmin(quien)) return out;
        String ultimo = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            for (String s : SUBCOMANDOS) if (s.startsWith(ultimo)) out.add(s);
            return out;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("give") || sub.equals("list"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(ultimo)) out.add(p.getName());
            }
            return out;
        }
        if (sub.equals("give") && args.length == 3) {
            for (String t : plugin.tipos().keySet()) if (t.startsWith(ultimo)) out.add(t);
            return out;
        }
        if (sub.equals("renew") && args.length == 3) {
            for (String s : List.of("7", "30")) if (s.startsWith(ultimo)) out.add(s);
            return out;
        }
        if (sub.equals("renew") && args.length == 4) {
            for (String t : plugin.tipos().keySet()) if (t.startsWith(ultimo)) out.add(t);
            return out;
        }
        if (sub.equals("give") && args.length == 4) {
            for (String s : List.of("-", "0", "7", "30")) if (s.startsWith(ultimo)) out.add(s);
            return out;
        }
        if (sub.equals("remove") && args.length == 2) {
            for (Baliza b : plugin.registro().todas()) {
                if (out.size() >= 50) break;
                if (b.idCorto().startsWith(ultimo)) out.add(b.idCorto());
            }
        }
        return out;
    }
}
