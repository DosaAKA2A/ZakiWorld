package net.ederus.calamity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.ederus.calamity.hardcore.Cordura;
import net.ederus.calamity.hardcore.Hardcore;
import net.ederus.calamity.hardcore.Subcomandos;
import net.ederus.edm.comun.Estilo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * /calamity (alias /cal): el unico comando de Calamity, solo de staff (op o calamity.admin) y
 * todo en ingles desde la 1.12.
 *
 * Hasta la 1.11 habia dos: /calamity para los jugadores (saldo, eco, contratos...) y otro de staff
 * en espanol, que a su vez venia de /lw hardcore y /lw level. Dosa: "TODOS los comandos deberian ser
 * con /calamity y no deberia haber comandos en espanol" y "nadie sin op o admin/owner/dev deberia
 * tener permiso de usar /calamity ni ningun subcomando: solo deben poder ver y recibir cosas desde
 * los NPCs". Lo de los jugadores lo abren ahora los NPCs con "calamity open <player> <id>" desde la
 * consola (Npcs). Lo viejo escrito en la consola, en los NPCs o en las recompensas lo traduce
 * ComandosViejos mientras dura la transicion.
 *
 * Sin el permiso, /calamity no sale en el tab (Paper no lo manda al cliente) y contesta como un
 * comando que no existe. La consola lo tiene siempre.
 *
 * Las puertas se marcan con la vara y los dos puntos PISANDOLOS, que es la unica forma comoda de
 * hacerlo desde Bedrock y sin menus.
 *
 * Lo que no esta en el switch lo registran los modulos de Calamity en Subcomandos.staff(): cada
 * uno trae su subcomando sin tocar este fichero, que es lo que deja trabajar en paralelo.
 */
public final class ComandoRaiz implements TabExecutor {

    private static final TextColor MARCA = TextColor.color(0xE0664A);
    private static final TextColor SUAVE = TextColor.color(0xC9BDB8);

    /** Los subcomandos de este fichero, en el orden del tab. */
    public static final List<String> PROPIOS = List.of("status", "level", "reload", "menu", "wand", "define", "item", "sanity", "time");
    /** Lo que se escribe en define: las tres cajas de la vara y los dos puntos que se pisan. */
    public static final List<String> DEFINE = List.of("entry", "exit", "spawn", "arrival", "return");
    /** Lo que se escribe en item. */
    public static final List<String> ITEMS = List.of("flask", "crystal", "essence");

    private final CalamityPlugin plugin;

    public ComandoRaiz(CalamityPlugin plugin) {
        this.plugin = plugin;
    }

    /** Lo que ve quien no tiene permiso: lo mismo que con un comando que no existe. */
    public static Component desconocido() {
        return Component.translatable("command.unknown.command", NamedTextColor.RED);
    }

    private void decir(CommandSender a, Component texto) {
        a.sendMessage(Estilo.aviso(texto.colorIfAbsent(SUAVE)));
    }

    /** El nombre del plugin, una sola vez, para abrir una respuesta larga. */
    private void cabecera(CommandSender a, String que) {
        a.sendMessage(Estilo.cabecera("Calamity", que, MARCA));
    }

    private void decir(CommandSender a, String texto) {
        decir(a, Component.text(texto));
    }

    private static void linea(CommandSender q, String uso, String que) {
        q.sendMessage(Component.text("  " + uso, NamedTextColor.WHITE).append(Component.text("  " + que, SUAVE)));
    }

    @Override
    public boolean onCommand(CommandSender q, Command cmd, String etiqueta, String[] args) {
        if (!q.hasPermission(Subcomandos.PERMISO)) {
            q.sendMessage(desconocido());
            return true;
        }
        // level y reload NO dependen de que las reglas hardcore esten encendidas: se miran
        // antes del guard de hc. Los mobs con nivel salen en todos los mundos de Lethal World.
        if (args.length >= 1 && args[0].equalsIgnoreCase("level")) {
            nivel(q, args);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            recargar(q);
            return true;
        }
        Hardcore hc = plugin.hardcore();
        // El objeto existe siempre; lo que falta con hardcore.activo en false son el
        // panel y la vara, y sin ellos casi todo lo de abajo reventaba con un null.
        if (hc == null || !hc.activo()) {
            decir(q, Component.text("Las reglas hardcore están apagadas en la configuración.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            estado(q, hc);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "status" -> estado(q, hc);
            case "wand" -> {
                if (!(q instanceof Player p)) {
                    decir(q, Component.text("La vara se entrega en el juego.", NamedTextColor.RED));
                    return true;
                }
                p.getInventory().addItem(hc.vara().vara());
                decir(q, Component.text("Vara entregada: ", NamedTextColor.GREEN)
                        .append(Component.text("golpe = esquina 1, clic derecho = esquina 2.", SUAVE)));
            }
            case "define" -> definir(q, hc, args);
            case "menu" -> {
                if (!(q instanceof Player p)) {
                    decir(q, Component.text("El panel solo se abre dentro del juego.", NamedTextColor.RED));
                    return true;
                }
                hc.menu().abrir(p);
            }
            case "time" -> {
                Player destino = args.length >= 2
                        ? plugin.getServer().getPlayer(args[1])
                        : (q instanceof Player p ? p : null);
                if (destino == null) {
                    decir(q, Component.text("No encuentro a ese jugador.", NamedTextColor.RED));
                    return true;
                }
                double horas = hc.horasDe(destino);
                decir(q, Component.text(destino.getName() + " lleva ", SUAVE)
                        .append(Component.text(String.format(Locale.US, "%.1f h", horas), MARCA))
                        .append(Component.text(" en Calamity", SUAVE))
                        .append(Component.text(horas >= 24 ? "  ·  ya tiene el tag." : "  ·  el tag se gana a las 24 h.",
                                NamedTextColor.GRAY)));
            }
            case "item" -> {
                String cual = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
                if (!ITEMS.contains(cual)) {
                    decir(q, Component.text("¿Cuál? Usa ", NamedTextColor.RED)
                            .append(Component.text("/calamity item flask|crystal|essence [player]", MARCA)));
                    return true;
                }
                Player destino = args.length >= 3
                        ? plugin.getServer().getPlayer(args[2])
                        : (q instanceof Player p ? p : null);
                if (destino == null) {
                    decir(q, Component.text("No encuentro a ese jugador.", NamedTextColor.RED));
                    return true;
                }
                var item = switch (cual) {
                    case "flask" -> hc.items().frasco(plugin.getConfig().getInt("hardcore.frasco.usos", 3));
                    case "crystal" -> hc.items().cristal();
                    default -> hc.items().esencia(1);
                };
                destino.getInventory().addItem(item);
                decir(q, Component.text("Entregado a " + destino.getName() + ".", NamedTextColor.GREEN));
            }
            case "sanity" -> {
                // /calamity sanity [value] [player]: con los dos, el jugador es args[2].
                Player destino = args.length >= 3
                        ? plugin.getServer().getPlayer(args[2])
                        : (q instanceof Player p ? p : null);
                if (destino == null) {
                    decir(q, Component.text("No encuentro a ese jugador.", NamedTextColor.RED));
                    return true;
                }
                if (args.length >= 2) {
                    try {
                        hc.cordura().valor(destino, Double.parseDouble(args[1]));
                    } catch (NumberFormatException e) {
                        decir(q, Component.text("Eso no es un número.", NamedTextColor.RED));
                        return true;
                    }
                }
                decir(q, Component.text(destino.getName() + " tiene ", SUAVE)
                        .append(Component.text(Math.round(hc.cordura().valor(destino)) + "%",
                                Cordura.color(hc.cordura().valor(destino))))
                        .append(Component.text(" de cordura.", SUAVE)));
            }
            default -> {
                // Lo que registran los modulos de Calamity (Subcomandos).
                if (Subcomandos.staff().ejecutar(q, args)) return true;
                estado(q, hc);
            }
        }
        return true;
    }

    /**
     * define entry|exit|spawn guarda la caja de la vara como puerta de entrada, de salida o zona del
     * spawn; define arrival|return marca donde estas el punto donde aparece quien entra y adonde se
     * vuelve al salir. En la config siguen con sus nombres de siempre (puertas.entrada, puertas.salida,
     * puertas.spawn, llegada y salida): son datos del servidor.
     */
    private void definir(CommandSender q, Hardcore hc, String[] args) {
        if (!(q instanceof Player p)) {
            decir(q, Component.text("Eso se hace dentro del juego.", NamedTextColor.RED));
            return;
        }
        String cual = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (cual) {
            case "entry", "exit", "spawn" -> {
                String clave = cual.equals("entry") ? "entrada" : cual.equals("exit") ? "salida" : "spawn";
                String hecho = hc.vara().definir(p, clave);
                if (hecho == null) {
                    decir(q, Component.text("Primero marca las dos esquinas con la vara (/calamity wand).",
                            NamedTextColor.RED));
                    return;
                }
                decir(q, Component.text(cual.equals("spawn") ? "Zona del spawn (Grieta): "
                                : cual.equals("entry") ? "Puerta de entrada: " : "Puerta de salida: ", NamedTextColor.GREEN)
                        .append(Component.text(hecho, MARCA)));
            }
            case "arrival", "return" -> {
                hc.punto(cual.equals("arrival") ? "llegada" : "salida", p.getLocation());
                decir(q, Component.text(cual.equals("arrival") ? "Marcado aquí el punto de llegada"
                        : "Marcado aquí el punto de vuelta", NamedTextColor.GREEN)
                        .append(Component.text(cual.equals("arrival") ? " (donde aparece quien entra)."
                                : " (adonde se vuelve al salir).", SUAVE)));
            }
            default -> decir(q, Component.text("¿Cuál? Usa ", NamedTextColor.RED)
                    .append(Component.text("/calamity define entry|exit|spawn|arrival|return", MARCA)));
        }
    }

    /** Sin subcomando (o con uno que no existe): los mundos, las puertas, los puntos y la ayuda. */
    private void estado(CommandSender q, Hardcore hc) {
        cabecera(q, "los mundos hardcore");
        linea(q, "Mundos", String.join(", ", hc.mundos()));
        linea(q, "puerta de entrada", hc.vara().describir("entrada"));
        linea(q, "puerta de salida", hc.vara().describir("salida"));
        linea(q, "zona del spawn", hc.describirSpawn());
        for (String punto : List.of("llegada", "salida")) {
            var donde = hc.punto(punto);
            linea(q, "llegada".equals(punto) ? "aparece en" : "vuelve a",
                    donde == null ? "sin marcar"
                    : donde.getWorld().getKey() + "  " + donde.getBlockX() + " "
                            + donde.getBlockY() + " " + donde.getBlockZ());
        }
        for (String[] s : ayudaPropia()) linea(q, "/calamity " + s[0], s[1]);
        for (String[] s : Subcomandos.staff().ayuda(q)) linea(q, "/calamity " + s[0], s[1]);
    }

    /** La ayuda de los subcomandos de este fichero: [uso, que hace]. Tambien la mira el autotest. */
    public static List<String[]> ayudaPropia() {
        return List.of(
                new String[]{"status", "los mundos, las puertas, los puntos y esta ayuda"},
                new String[]{"wand", "la vara: dos esquinas marcan la puerta"},
                new String[]{"define entry|exit|spawn", "guarda esa caja como puerta o como zona del spawn"},
                new String[]{"define arrival|return", "marca aquí donde aparece quien entra o adonde se vuelve al salir"},
                new String[]{"item flask|crystal|essence [player]", "le da uno"},
                new String[]{"sanity [value] [player]", "consulta la cordura o la fija"},
                new String[]{"time [player]", "horas acumuladas y si ya tiene el tag"},
                new String[]{"menu", "panel de las reglas de dificultad"},
                new String[]{"level [player]", "de dónde sale el nivel de sus mobs"},
                new String[]{"reload", "relee la configuración de Calamity (el equipo es de GodItems: /gi reload)"});
    }

    /**
     * De donde sale el nivel de los mobs para ese jugador: el marcador del rango tal cual lo
     * devuelve PlaceholderAPI, el poder de AuraSkills y la cuenta con los valores de la config.
     * Sirve para ver de un vistazo cual de los dos dispara un nivel que no cuadra.
     */
    private void nivel(CommandSender q, String[] args) {
        MobsLethal mobs = plugin.mobs();
        if (mobs == null) {
            decir(q, Component.text("Los mobs de Lethal World no están activos.", NamedTextColor.RED));
            return;
        }
        Player p = args.length >= 2 ? plugin.getServer().getPlayerExact(args[1]) : (q instanceof Player j ? j : null);
        if (p == null) {
            linea(q, "/calamity level [player]", "el jugador tiene que estar conectado");
            return;
        }
        var n = plugin.getConfig().getConfigurationSection("mobs.nivel");
        double porRango = n == null ? 2.0 : n.getDouble("por-rango", 2.0);
        double porNivel = Math.max(1.0, n == null ? 20.0 : n.getDouble("poder-por-nivel", 20.0));
        double variacion = n == null ? 0.10 : n.getDouble("variacion", 0.10);
        int maximo = n == null ? 100 : n.getInt("maximo", 100);
        int rango = mobs.rango(p), poder = mobs.poder(p);
        double base = rango * porRango + poder / porNivel;
        int bajo = (int) Math.max(1, Math.min(maximo, Math.round(base * (1 - variacion))));
        int alto = (int) Math.max(1, Math.min(maximo, Math.round(base * (1 + variacion))));

        decir(q, "Nivel de los mobs para " + p.getName() + ":");
        linea(q, "  rango " + rango, "marcador: «" + mobs.rangoCrudo(p) + "» × " + porRango + " = " + (rango * porRango));
        linea(q, "  poder " + poder, "AuraSkills ÷ " + porNivel + " = " + Math.round(poder / porNivel * 10) / 10.0);
        linea(q, "  nivel " + bajo + "-" + alto, "base " + Math.round(base * 10) / 10.0 + ", variación ±"
                + Math.round(variacion * 100) + " %, tope " + maximo);
        // 1.7: dentro de Calamity, lo que se suma encima (cordura, minutos, distancia, racha, eclipse).
        Hardcore hc = plugin.hardcore();
        if (hc == null || !hc.activo() || !hc.esHardcore(p)) return;
        int extra = hc.bonusNivel(p) + hc.bonusDistancia(p);
        decir(q, "En Calamity, encima de la base: +" + extra + " niveles"
                + " (nivel " + Math.max(1, Math.min(maximo, Math.round(base) + extra)) + " sin variación)");
        for (String[] d : hc.desgloseNivel(p)) linea(q, "  " + d[0], d[1]);
    }

    /**
     * Relee el config de Calamity (el equipo ya no: lo lleva GodItems desde la 1.6). /lw reload solo
     * relee el de los mundos. No rearranca mobs ni reglas: lo que se lee en cada vuelta (topes,
     * niveles, puertas) se entera solo; lo que se monta al arrancar necesita reiniciar.
     */
    private void recargar(CommandSender q) {
        plugin.reloadConfig();
        String equipo = plugin.hardcore() == null ? null : plugin.hardcore().recargarEquipo();
        decir(q, "Configuración de Calamity releída" + (equipo == null ? "" : ". Equipo: " + equipo + " (se relee con /gi reload)")
                + ". Lo que se carga al arrancar necesita un reinicio.");
    }

    @Override
    public List<String> onTabComplete(CommandSender q, Command cmd, String etiqueta, String[] args) {
        List<String> op = new ArrayList<>();
        if (!q.hasPermission(Subcomandos.PERMISO)) return op;
        if (args.length == 1) {
            op.addAll(PROPIOS);
            op.addAll(Subcomandos.staff().nombres(q));
        } else if (args.length >= 2 && Subcomandos.staff().nombres(q).contains(args[0].toLowerCase(Locale.ROOT))) {
            op.addAll(Subcomandos.staff().tab(q, args));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("define")) {
            op.addAll(DEFINE);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("item")) {
            op.addAll(ITEMS);
        } else if ((args.length == 2 && List.of("level", "time").contains(args[0].toLowerCase(Locale.ROOT)))
                || (args.length == 3 && List.of("item", "sanity").contains(args[0].toLowerCase(Locale.ROOT)))) {
            for (Player p : plugin.getServer().getOnlinePlayers()) op.add(p.getName());
        }
        String ultimo = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        op.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(ultimo));
        return op;
    }
}
