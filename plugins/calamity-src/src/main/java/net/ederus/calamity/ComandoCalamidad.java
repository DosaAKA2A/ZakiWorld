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
 * /calamidad (alias /cld): la administracion de Calamity, de staff (ederus.mundos).
 *
 * Es lo que hasta LethalWorld 1.1.1 eran /lw hardcore y /lw level, con el mismo cuerpo: al
 * salir Calamity a plugin propio, /lw se quedo con los mundos y esto vino aqui. Como ya no
 * lleva "hardcore" delante, los argumentos van un puesto antes: "/lw hardcore cordura 50
 * Dosa__" es ahora "/calamidad cordura 50 Dosa__". level es un subcomando mas:
 * "/calamidad level Dosa__". Lo viejo sigue funcionando: RedireccionComandos lo reescribe.
 *
 * Los cuatro puntos (entrada, llegada, salida y puerta de salida) se marcan PISANDOLOS, que
 * es la unica forma comoda de hacerlo desde Bedrock y sin menus.
 *
 * Lo que no esta en el switch lo registran los modulos de Calamity en Subcomandos.lw(): cada
 * uno trae su subcomando sin tocar este fichero, que es lo que deja trabajar en paralelo.
 */
public final class ComandoCalamidad implements TabExecutor {

    private static final TextColor MARCA = TextColor.color(0xE0664A);
    private static final TextColor SUAVE = TextColor.color(0xC9BDB8);

    private final CalamityPlugin plugin;

    public ComandoCalamidad(CalamityPlugin plugin) {
        this.plugin = plugin;
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
        if (!q.hasPermission("ederus.mundos")) {
            decir(q, Component.text("No tienes permiso.", NamedTextColor.RED));
            return true;
        }
        // level y reload NO dependen de que las reglas hardcore esten encendidas: se miran
        // antes del guard de hc, igual que antes /lw level y /lw reload iban aparte de
        // /lw hardcore. Los mobs con nivel salen en todos los mundos de Lethal World.
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
            decir(q, Component.text("Las reglas hardcore están apagadas en la config.", NamedTextColor.RED));
            return true;
        }
        // Antes "/lw hardcore" a secas caia en el default (sub = "status").
        if (args.length == 0) {
            estado(q, hc);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "wand", "vara" -> {
                if (!(q instanceof Player p)) {
                    decir(q, Component.text("La vara se entrega en el juego.", NamedTextColor.RED));
                    return true;
                }
                p.getInventory().addItem(hc.vara().vara());
                decir(q, Component.text("Vara entregada: ", NamedTextColor.GREEN)
                        .append(Component.text("golpe = esquina 1, clic derecho = esquina 2.", SUAVE)));
            }
            case "define" -> {
                if (!(q instanceof Player p)) {
                    decir(q, Component.text("Eso se define en el juego.", NamedTextColor.RED));
                    return true;
                }
                String cual = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
                if (!cual.equals("entrada") && !cual.equals("salida")) {
                    decir(q, Component.text("Dime cuál: ", NamedTextColor.RED)
                            .append(Component.text("/calamidad define entrada|salida", MARCA)));
                    return true;
                }
                String hecho = hc.vara().definir(p, cual);
                if (hecho == null) {
                    decir(q, Component.text("Marca las dos esquinas con la vara primero.",
                            NamedTextColor.RED));
                    return true;
                }
                decir(q, Component.text("Puerta de " + cual + ": ", NamedTextColor.GREEN)
                        .append(Component.text(hecho, MARCA)));
            }
            case "entrada", "llegada", "salida", "puerta-salida" -> {
                if (!(q instanceof Player p)) {
                    decir(q, Component.text("Ese punto se marca estando en el sitio.", NamedTextColor.RED));
                    return true;
                }
                hc.punto(sub, p.getLocation());
                decir(q, Component.text("Marcado ", NamedTextColor.GREEN)
                        .append(Component.text(sub, MARCA))
                        .append(Component.text(" aquí mismo.", SUAVE)));
            }
            case "menu" -> {
                if (!(q instanceof Player p)) {
                    decir(q, Component.text("El panel se abre desde el juego.", NamedTextColor.RED));
                    return true;
                }
                hc.menu().abrir(p);
            }
            case "tiempo" -> {
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
                        .append(Component.text(horas >= 24 ? "  ·  ya tiene el tag." : "  ·  el tag son 24 h.",
                                NamedTextColor.GRAY)));
            }
            case "frasco", "cristal", "esencia" -> {
                Player destino = args.length >= 2
                        ? plugin.getServer().getPlayer(args[1])
                        : (q instanceof Player p ? p : null);
                if (destino == null) {
                    decir(q, Component.text("No encuentro a ese jugador.", NamedTextColor.RED));
                    return true;
                }
                var item = switch (sub) {
                    case "frasco" -> hc.items().frasco(plugin.getConfig().getInt("hardcore.frasco.usos", 3));
                    case "cristal" -> hc.items().cristal();
                    default -> hc.items().esencia(1);
                };
                destino.getInventory().addItem(item);
                decir(q, Component.text("Entregado a " + destino.getName() + ".", NamedTextColor.GREEN));
            }
            case "cordura" -> {
                // /calamidad cordura [valor] [player]: con los dos, el jugador es args[2]
                // (antes, con "hardcore" delante, era args[3]).
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
                // Lo que registran los modulos de Calamity (Subcomandos). Ya no hace falta
                // quitar "hardcore" de delante: args[0] es el nombre del subcomando.
                if (Subcomandos.lw().ejecutar(q, args)) return true;
                estado(q, hc);
            }
        }
        return true;
    }

    /**
     * Sin subcomando (o con uno que no existe): los mundos, las puertas, los puntos y la
     * ayuda. Es lo que antes salia en el default de /lw hardcore.
     */
    private void estado(CommandSender q, Hardcore hc) {
        cabecera(q, "los mundos hardcore");
        linea(q, "Mundos", String.join(", ", hc.mundos()));
        linea(q, "puerta de entrada", hc.vara().describir("entrada"));
        linea(q, "puerta de salida", hc.vara().describir("salida"));
        for (String punto : List.of("llegada", "salida")) {
            var donde = hc.punto(punto);
            linea(q, punto == "llegada" ? "aparece en" : "vuelve a",
                    donde == null ? "sin marcar"
                    : donde.getWorld().getKey() + "  " + donde.getBlockX() + " "
                            + donde.getBlockY() + " " + donde.getBlockZ());
        }
        linea(q, "/calamidad wand", "la vara: dos esquinas marcan la puerta");
        linea(q, "/calamidad define entrada|salida", "guarda esa caja como puerta");
        linea(q, "/calamidad llegada", "marca aquí donde aparece el que entra");
        linea(q, "/calamidad salida", "marca aquí a dónde se vuelve");
        linea(q, "/calamidad frasco|cristal|esencia [player]", "entrega uno");
        linea(q, "/calamidad cordura [valor] [player]", "consulta o la fija");
        linea(q, "/calamidad tiempo [player]", "horas acumuladas y si tiene el tag");
        linea(q, "/calamidad menu", "panel de las reglas de dificultad");
        linea(q, "/calamidad level [player]", "de dónde sale el nivel de sus mobs");
        linea(q, "/calamidad reload", "relee el config de Calamity del disco");
        for (String[] s : Subcomandos.lw().ayuda(q)) linea(q, "/calamidad " + s[0], s[1]);
    }

    /**
     * De donde sale el nivel de los mobs para ese jugador: el marcador del rango tal cual lo
     * devuelve PlaceholderAPI, el poder de AuraSkills y la cuenta con los valores de la config.
     * Sirve para ver de un vistazo cual de los dos dispara un nivel que no cuadra.
     *
     * args[0] es "level", como en /lw level: aqui los indices no se mueven.
     */
    private void nivel(CommandSender q, String[] args) {
        MobsLethal mobs = plugin.mobs();
        if (mobs == null) {
            decir(q, Component.text("Los mobs de Lethal World no están activos.", NamedTextColor.RED));
            return;
        }
        Player p = args.length >= 2 ? plugin.getServer().getPlayerExact(args[1]) : (q instanceof Player j ? j : null);
        if (p == null) {
            linea(q, "/calamidad level [player]", "el jugador tiene que estar conectado");
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
    }

    /**
     * Relee el config de Calamity. Antes lo hacia /lw reload, cuando mobs: y hardcore: iban
     * en el config de LethalWorld; ahora /lw reload solo relee el de los mundos.
     * No rearranca mobs ni reglas: lo que se lee en cada vuelta (topes, niveles, puertas)
     * se entera solo; lo que se monta al arrancar necesita reiniciar.
     */
    private void recargar(CommandSender q) {
        plugin.reloadConfig();
        decir(q, "Config de Calamity releída. Lo que se monta al arrancar necesita reiniciar.");
    }

    @Override
    public List<String> onTabComplete(CommandSender q, Command cmd, String etiqueta, String[] args) {
        List<String> op = new ArrayList<>();
        if (args.length == 1) {
            op.addAll(List.of("level", "reload", "status", "menu", "wand", "define", "llegada", "salida",
                    "frasco", "cristal", "esencia", "cordura", "tiempo"));
            op.addAll(Subcomandos.lw().nombres(q));
        } else if (args.length >= 2 && Subcomandos.lw().nombres(q).contains(args[0].toLowerCase(Locale.ROOT))) {
            op.addAll(Subcomandos.lw().tab(q, args));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("define")) {
            op.addAll(List.of("entrada", "salida"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("level")
                || List.of("frasco", "cristal", "esencia", "cordura", "tiempo")
                        .contains(args[0].toLowerCase(Locale.ROOT)))) {
            for (Player p : plugin.getServer().getOnlinePlayers()) op.add(p.getName());
        }
        String ultimo = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        op.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(ultimo));
        return op;
    }
}
