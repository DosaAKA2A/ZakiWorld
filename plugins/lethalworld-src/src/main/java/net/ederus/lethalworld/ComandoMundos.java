package net.ederus.lethalworld;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import net.ederus.edm.comun.Estilo;
import net.ederus.lethalworld.hardcore.Cordura;
import net.ederus.lethalworld.hardcore.Hardcore;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * /lw: todo por comando, sin menu, para que desde Bedrock se haga igual.
 * Subcomandos en ingles, como /lbiomes.
 */
final class ComandoMundos implements TabExecutor {

    private static final TextColor MARCA = TextColor.color(0xE0664A);
    private static final TextColor SUAVE = TextColor.color(0xC9BDB8);

    private final LethalWorldPlugin plugin;

    ComandoMundos(LethalWorldPlugin plugin) {
        this.plugin = plugin;
    }

    private void decir(CommandSender a, Component texto) {
        a.sendMessage(Estilo.aviso(texto.colorIfAbsent(SUAVE)));
    }

    /** El nombre del plugin, una sola vez, para abrir una respuesta larga. */
    private void cabecera(CommandSender a, String que) {
        a.sendMessage(Estilo.cabecera("Lethal World", que, MARCA));
    }

    private void decir(CommandSender a, String texto) {
        decir(a, Component.text(texto));
    }

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        if (!quien.hasPermission("ederus.mundos")) {
            decir(quien, Component.text("No tienes permiso.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            ayuda(quien);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> lista(quien);
            case "generators" -> listaGeneradores(quien);
            case "create" -> crear(quien, args);
            case "delete" -> borrar(quien, args);
            case "tp" -> tp(quien, args);
            case "biomes" -> biomas(quien);
            case "biome" -> irABioma(quien, args);
            case "pregen" -> pregen(quien, args);
            case "level" -> nivel(quien, args);
            case "hardcore" -> hardcore(quien, args);
            case "reload" -> recargar(quien);
            default -> ayuda(quien);
        }
        return true;
    }

    private void ayuda(CommandSender q) {
        cabecera(q, "mundos con el generador de Bracken procesado");
        linea(q, "/lw list", "mundos creados y si ya estan cargados");
        linea(q, "/lw generators", "generadores disponibles");
        linea(q, "/lw create <name> <generator> [seed]", "crea el mundo (existe tras reiniciar)");
        linea(q, "/lw delete <name>", "lo quita (la carpeta del mundo no se borra)");
        linea(q, "/lw tp <name> [player]", "lleva a un sitio seguro del mundo");
        linea(q, "/lw biomes", "biomas del mundo en el que estás (pulsables)");
        linea(q, "/lw biome <biome> [radius]", "busca el más cercano y te lleva");
        linea(q, "/lw pregen start <name> [radius]", "pregenera (sin radio: el borde o el de la config)");
        linea(q, "/lw pregen status|pause|resume|cancel", "");
        linea(q, "/lw level [player]", "de dónde sale el nivel de sus mobs");
        linea(q, "/lw hardcore", "Calamity: portales, objetos y cordura");
        linea(q, "/lw reload", "relee el config del disco");
    }

    /** Relee el config. Antes esto era /edm reload mundos, cuando Lethal World iba dentro. */
    private void recargar(CommandSender q) {
        decir(q, "Config releida: " + plugin.recargar());
    }

    private static void linea(CommandSender q, String uso, String que) {
        q.sendMessage(Component.text("  " + uso, NamedTextColor.WHITE).append(Component.text("  " + que, SUAVE)));
    }

    private void lista(CommandSender q) {
        Map<String, String> mundos = plugin.mundos();
        if (mundos.isEmpty()) {
            decir(q, "No hay mundos. Crea uno con /lw create <name> <generator>.");
            return;
        }
        decir(q, "Mundos:");
        for (Map.Entry<String, String> e : mundos.entrySet()) {
            World w = plugin.mundo(e.getKey());
            q.sendMessage(Component.text("  " + e.getKey(), NamedTextColor.WHITE)
                    .append(Component.text("  " + plugin.nombreGenerador(e.getValue())
                            + (plugin.semillaDe(e.getKey()) != null ? " · semilla " + plugin.semillaDe(e.getKey()) : "")
                            + "  ", SUAVE))
                    .append(w != null
                            ? Component.text("cargado (" + w.getName() + ")", NamedTextColor.GREEN)
                            : Component.text("se carga en el próximo reinicio", NamedTextColor.GOLD)));
        }
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
            linea(q, "/lw level [player]", "el jugador tiene que estar conectado");
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

    private void listaGeneradores(CommandSender q) {
        decir(q, "Generadores:");
        for (String id : plugin.generadores()) {
            List<String> usan = plugin.mismoGenerador(id);
            q.sendMessage(Component.text("  " + id, NamedTextColor.WHITE)
                    .append(Component.text("  " + plugin.nombreGenerador(id), SUAVE))
                    .append(usan.isEmpty() ? Component.empty()
                            : Component.text("  en uso: " + String.join(", ", usan), NamedTextColor.GRAY)));
        }
    }

    private void crear(CommandSender q, String[] args) {
        if (args.length < 3) {
            linea(q, "/lw create <name> <generator> [seed]", "ver /lw generators");
            return;
        }
        Long semilla = null;
        if (args.length >= 4) {
            try {
                semilla = Long.parseLong(args[3]);
            } catch (NumberFormatException e) {
                // Como en vanilla: una semilla de texto vale su hash.
                semilla = (long) args[3].hashCode();
            }
        }
        List<String> previos = plugin.mismoGenerador(args[2].toLowerCase(Locale.ROOT), semilla, true);
        String error = plugin.crear(args[1], args[2], semilla);
        if (error != null) {
            decir(q, Component.text(error, NamedTextColor.RED));
            return;
        }
        decir(q, "Mundo " + args[1].toLowerCase(Locale.ROOT) + " creado con "
                + plugin.nombreGenerador(args[2].toLowerCase(Locale.ROOT))
                + (semilla != null ? " y semilla " + semilla : "") + ". Reinicia el servidor para que exista.");
        if (!previos.isEmpty()) {
            decir(q, Component.text("Ojo: " + String.join(", ", previos) + " usa el mismo generador y la"
                    + " misma semilla, así que el terreno saldrá idéntico. Pon otra semilla al final del comando.", NamedTextColor.GOLD));
        }
    }

    private void borrar(CommandSender q, String[] args) {
        if (args.length < 2) {
            linea(q, "/lw delete <name>", "");
            return;
        }
        String error = plugin.borrar(args[1]);
        decir(q, error != null ? Component.text(error, NamedTextColor.RED)
                : Component.text("Mundo " + args[1].toLowerCase(Locale.ROOT) + " quitado. Deja de cargarse al"
                        + " reiniciar; su carpeta sigue en disco."));
    }

    private void tp(CommandSender q, String[] args) {
        if (args.length < 2) {
            linea(q, "/lw tp <name> [player]", "");
            return;
        }
        Player objetivo;
        if (args.length >= 3) {
            objetivo = plugin.getServer().getPlayerExact(args[2]);
            if (objetivo == null) {
                decir(q, Component.text("No encuentro al jugador " + args[2] + ".", NamedTextColor.RED));
                return;
            }
        } else if (q instanceof Player p) {
            objetivo = p;
        } else {
            decir(q, "Desde la consola indica el jugador: /lw tp <name> <player>");
            return;
        }
        World w = plugin.mundo(args[1]);
        if (w == null) {
            decir(q, Component.text(plugin.mundos().containsKey(args[1].toLowerCase(Locale.ROOT))
                    ? "El mundo aún no está cargado: falta reiniciar."
                    : "No existe el mundo '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }
        Location spawn = w.getSpawnLocation();
        w.getChunkAtAsync(spawn).thenAccept(chunk -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            Location destino = sitioSeguro(w, spawn.getBlockX(), spawn.getBlockZ());
            boolean seguro = destino != null;
            if (!seguro) destino = new Location(w, spawn.getBlockX() + 0.5, Math.min(w.getLogicalHeight() - 2, 200), spawn.getBlockZ() + 0.5);
            if (!seguro) objetivo.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 30, 1));
            objetivo.teleportAsync(destino);
            plugin.bitacora().anotar("tp", objetivo.getName(), w.getName(), seguro ? "suelo" : "caida lenta");
        }));
    }

    private void pregen(CommandSender q, String[] args) {
        Pregenerador pg = plugin.pregen();
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "start" -> {
                if (args.length < 3) {
                    linea(q, "/lw pregen start <name> [radius]", "");
                    return;
                }
                World w = plugin.mundo(args[2]);
                if (w == null) {
                    decir(q, Component.text("El mundo '" + args[2] + "' no existe o aún no está cargado.", NamedTextColor.RED));
                    return;
                }
                int radio = pg.radioAutomatico(w);
                boolean automatico = true;
                if (args.length >= 4) {
                    try {
                        radio = Math.max(16, Math.min(50_000, Integer.parseInt(args[3])));
                        automatico = false;
                    } catch (NumberFormatException e) {
                        decir(q, Component.text("El radio tiene que ser un número de bloques.", NamedTextColor.RED));
                        return;
                    }
                }
                String error = pg.empezar(w, radio);
                if (error != null) {
                    decir(q, Component.text(error, NamedTextColor.RED));
                    return;
                }
                boolean conBorde = w.getWorldBorder().getSize() < 1_000_000;
                decir(q, "Pregenerando " + args[2] + " en un radio de " + radio + " bloques"
                        + (automatico ? (conBorde ? " (el del borde del mundo)" : " (el de la config; pon un borde para fijarlo)") : "")
                        + ". Verás el avance en la barra; /lw pregen status para el detalle.");
            }
            case "pause" -> {
                pg.pausar();
                decir(q, pg.activo() ? "Pregeneración en pausa. /lw pregen resume para seguir." : pg.estado());
            }
            case "resume" -> {
                pg.reanudar();
                decir(q, pg.estado());
            }
            case "cancel" -> {
                boolean habia = pg.activo();
                pg.cancelar();
                decir(q, habia ? "Pregeneración cancelada. Lo ya generado se queda." : "No había ninguna en marcha.");
            }
            default -> decir(q, pg.estado());
        }
    }

    /** Radio por defecto de la busqueda: como el /locate vanilla, pero acotado para no dar tirones. */
    private static final int RADIO_BIOMA = 3200;

    private void biomas(CommandSender q) {
        if (!(q instanceof Player p)) {
            decir(q, "Solo desde el juego: lista los biomas del mundo en el que estás.");
            return;
        }
        String gen = plugin.generadorDe(p.getWorld());
        if (gen == null) {
            decir(q, "No estás en un mundo de Lethal World. Entra con /lw tp <name>.");
            return;
        }
        List<String> ids = plugin.biomasDe(gen);
        decir(q, "Biomas de " + plugin.nombreGenerador(gen) + " (" + ids.size() + "). Pulsa uno para ir:");
        for (String id : ids) {
            String corto = id.substring(id.lastIndexOf('/') + 1);
            q.sendMessage(Component.text("  " + corto.replace('_', ' '), NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.runCommand("/lw biome " + corto))
                    .hoverEvent(HoverEvent.showText(Component.text("Ir al " + id + " más cercano", SUAVE))));
        }
    }

    private void irABioma(CommandSender q, String[] args) {
        if (!(q instanceof Player p)) {
            decir(q, "Solo desde el juego.");
            return;
        }
        if (args.length < 2) {
            linea(q, "/lw biome <biome> [radius]", "ver /lw biomes");
            return;
        }
        World w = p.getWorld();
        String gen = plugin.generadorDe(w);
        Biome bioma = resolverBioma(args[1], gen == null ? List.of() : plugin.biomasDe(gen));
        if (bioma == null) {
            decir(q, Component.text("No conozco el bioma '" + args[1] + "'. Mira /lw biomes.", NamedTextColor.RED));
            return;
        }
        int radio = RADIO_BIOMA;
        if (args.length >= 3) {
            try {
                radio = Math.max(64, Math.min(12800, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
            }
        }
        decir(q, "Buscando " + bioma.getKey().asString() + " a menos de " + radio + " bloques...");
        final int r = radio;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            long inicio = System.currentTimeMillis();
            var resultado = w.locateNearestBiome(p.getLocation(), r, 32, 64, bioma);
            long ms = System.currentTimeMillis() - inicio;
            if (resultado == null) {
                decir(q, Component.text("No hay " + bioma.getKey().getKey() + " a menos de " + r
                        + " bloques. Prueba con un radio mayor: /lw biome " + args[1] + " 8000", NamedTextColor.GOLD));
                plugin.bitacora().anotar("biome", p.getName(), bioma.getKey().asString(), "no encontrado",
                        r + " bloques", ms + " ms");
                return;
            }
            Location hallado = resultado.getLocation();
            int distancia = (int) Math.hypot(hallado.getX() - p.getLocation().getX(), hallado.getZ() - p.getLocation().getZ());
            w.getChunkAtAsync(hallado).thenAccept(ch -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                Location destino = sitioSeguro(w, hallado.getBlockX(), hallado.getBlockZ(), bioma);
                boolean seguro = destino != null;
                if (!seguro) {
                    destino = new Location(w, hallado.getBlockX() + 0.5, hallado.getBlockY() + 2, hallado.getBlockZ() + 0.5);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 30, 1));
                }
                p.teleportAsync(destino);
                decir(q, "Llegaste a " + bioma.getKey().getKey().replace('/', ' ').replace('_', ' ') + " ("
                        + destino.getBlockX() + " " + destino.getBlockY() + " " + destino.getBlockZ() + ", a "
                        + distancia + " bloques)." + (seguro ? "" : " No encontré suelo: caída lenta."));
                plugin.bitacora().anotar("biome", p.getName(), bioma.getKey().asString(),
                        destino.getBlockX() + " " + destino.getBlockY() + " " + destino.getBlockZ(),
                        distancia + " bloques", ms + " ms");
            }));
        });
    }

    /** Acepta el id completo (bracken:panacea/x) o solo el final (x), mirando antes en el mundo actual. */
    private static Biome resolverBioma(String texto, List<String> delMundo) {
        String t = texto.toLowerCase(Locale.ROOT);
        var registro = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
        if (t.contains(":")) {
            var key = org.bukkit.NamespacedKey.fromString(t);
            return key == null ? null : registro.get(key);
        }
        for (String id : delMundo) {
            if (id.endsWith("/" + t) || id.endsWith(":" + t)) {
                return registro.get(org.bukkit.NamespacedKey.fromString(id));
            }
        }
        for (Biome b : registro) {
            String k = b.getKey().getKey();
            if (k.equals(t) || k.endsWith("/" + t)) return b;
        }
        return null;
    }

    /** Suelo seguro buscando en toda la columna, sin mirar el bioma. */
    private static Location sitioSeguro(World w, int x0, int z0) {
        return sitioSeguro(w, x0, z0, null);
    }

    /**
     * Un hueco de dos bloques de aire sobre suelo solido, buscando de arriba abajo desde
     * la altura logica: las dimensiones con techo tienen roca madre arriba y el bloque mas
     * alto no sirve. Prueba la columna del spawn y unas cuantas alrededor.
     */
    private static Location sitioSeguro(World w, int x0, int z0, Biome bioma) {
        int techo = Math.min(w.getLogicalHeight(), w.getMaxHeight()) + w.getMinHeight() - 1;
        int[][] desplazamientos = {{0, 0}, {4, 0}, {0, 4}, {-4, 0}, {0, -4}, {8, 8}, {-8, -8}, {8, -8}, {-8, 8}};
        for (int[] d : desplazamientos) {
            int x = x0 + d[0], z = z0 + d[1];
            for (int y = techo; y > w.getMinHeight() + 1; y--) {
                Block pies = w.getBlockAt(x, y, z);
                Block cabeza = w.getBlockAt(x, y + 1, z);
                Block suelo = w.getBlockAt(x, y - 1, z);
                if (pies.isPassable() && cabeza.isPassable() && !pies.isLiquid() && !cabeza.isLiquid()
                        && suelo.getType().isSolid() && suelo.getType() != Material.BEDROCK
                        && suelo.getType() != Material.MAGMA_BLOCK
                        && (bioma == null || w.getBiome(x, y, z).equals(bioma))) {
                    return new Location(w, x + 0.5, y, z + 0.5);
                }
            }
        }
        return null;
    }

    /**
     * /lw hardcore: lo que hace falta para montar Calamity y para probarlo.
     *
     * Los cuatro puntos (entrada, llegada, salida y puerta de salida) se marcan
     * PISANDOLOS, que es la unica forma comoda de hacerlo desde Bedrock y sin menus.
     */
    private void hardcore(CommandSender q, String[] args) {
        Hardcore hc = plugin.hardcore();
        // El objeto existe siempre; lo que falta con hardcore.activo en false son el
        // panel y la vara, y sin ellos casi todo lo de abajo reventaba con un null.
        if (hc == null || !hc.activo()) {
            decir(q, Component.text("Las reglas hardcore están apagadas en la config.", NamedTextColor.RED));
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";

        switch (sub) {
            case "wand", "vara" -> {
                if (!(q instanceof Player p)) {
                    decir(q, Component.text("La vara se entrega en el juego.", NamedTextColor.RED));
                    return;
                }
                p.getInventory().addItem(hc.vara().vara());
                decir(q, Component.text("Vara entregada: ", NamedTextColor.GREEN)
                        .append(Component.text("golpe = esquina 1, clic derecho = esquina 2.", SUAVE)));
            }
            case "define" -> {
                if (!(q instanceof Player p)) {
                    decir(q, Component.text("Eso se define en el juego.", NamedTextColor.RED));
                    return;
                }
                String cual = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
                if (!cual.equals("entrada") && !cual.equals("salida")) {
                    decir(q, Component.text("Dime cuál: ", NamedTextColor.RED)
                            .append(Component.text("/lw hardcore define entrada|salida", MARCA)));
                    return;
                }
                String hecho = hc.vara().definir(p, cual);
                if (hecho == null) {
                    decir(q, Component.text("Marca las dos esquinas con la vara primero.",
                            NamedTextColor.RED));
                    return;
                }
                decir(q, Component.text("Puerta de " + cual + ": ", NamedTextColor.GREEN)
                        .append(Component.text(hecho, MARCA)));
            }
            case "entrada", "llegada", "salida", "puerta-salida" -> {
                if (!(q instanceof Player p)) {
                    decir(q, Component.text("Ese punto se marca estando en el sitio.", NamedTextColor.RED));
                    return;
                }
                hc.punto(sub, p.getLocation());
                decir(q, Component.text("Marcado ", NamedTextColor.GREEN)
                        .append(Component.text(sub, MARCA))
                        .append(Component.text(" aquí mismo.", SUAVE)));
            }
            case "menu" -> {
                if (!(q instanceof Player p)) {
                    decir(q, Component.text("El panel se abre desde el juego.", NamedTextColor.RED));
                    return;
                }
                hc.menu().abrir(p);
            }
            case "tiempo" -> {
                Player destino = args.length >= 3
                        ? plugin.getServer().getPlayer(args[2])
                        : (q instanceof Player p ? p : null);
                if (destino == null) {
                    decir(q, Component.text("No encuentro a ese jugador.", NamedTextColor.RED));
                    return;
                }
                double horas = hc.horasDe(destino);
                decir(q, Component.text(destino.getName() + " lleva ", SUAVE)
                        .append(Component.text(String.format(Locale.US, "%.1f h", horas), MARCA))
                        .append(Component.text(" en Calamity", SUAVE))
                        .append(Component.text(horas >= 24 ? "  ·  ya tiene el tag." : "  ·  el tag son 24 h.",
                                NamedTextColor.GRAY)));
            }
            case "frasco", "cristal", "esencia" -> {
                Player destino = args.length >= 3
                        ? plugin.getServer().getPlayer(args[2])
                        : (q instanceof Player p ? p : null);
                if (destino == null) {
                    decir(q, Component.text("No encuentro a ese jugador.", NamedTextColor.RED));
                    return;
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
                Player destino = args.length >= 4
                        ? plugin.getServer().getPlayer(args[3])
                        : (q instanceof Player p ? p : null);
                if (destino == null) {
                    decir(q, Component.text("No encuentro a ese jugador.", NamedTextColor.RED));
                    return;
                }
                if (args.length >= 3) {
                    try {
                        hc.cordura().valor(destino, Double.parseDouble(args[2]));
                    } catch (NumberFormatException e) {
                        decir(q, Component.text("Eso no es un número.", NamedTextColor.RED));
                        return;
                    }
                }
                decir(q, Component.text(destino.getName() + " tiene ", SUAVE)
                        .append(Component.text(Math.round(hc.cordura().valor(destino)) + "%",
                                Cordura.color(hc.cordura().valor(destino))))
                        .append(Component.text(" de cordura.", SUAVE)));
            }
            default -> {
                cabecera(q, "Calamity y los mundos hardcore");
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
                linea(q, "/lw hardcore wand", "la vara: dos esquinas marcan la puerta");
                linea(q, "/lw hardcore define entrada|salida", "guarda esa caja como puerta");
                linea(q, "/lw hardcore llegada", "marca aquí donde aparece el que entra");
                linea(q, "/lw hardcore salida", "marca aquí a dónde se vuelve");
                linea(q, "/lw hardcore frasco|cristal|esencia [player]", "entrega uno");
                linea(q, "/lw hardcore cordura [valor] [player]", "consulta o la fija");
                linea(q, "/lw hardcore tiempo [player]", "horas acumuladas y si tiene el tag");
                linea(q, "/lw hardcore menu", "panel de las reglas de dificultad");
            }
        }
    }


    @Override
    public List<String> onTabComplete(CommandSender q, Command cmd, String etiqueta, String[] args) {
        List<String> op = new ArrayList<>();
        if (args.length == 1) {
            op.addAll(List.of("list", "generators", "create", "delete", "tp", "biomes", "biome",
                    "pregen", "level", "hardcore", "reload"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("hardcore")) {
            op.addAll(List.of("status", "menu", "wand", "define", "llegada", "salida",
                    "frasco", "cristal", "esencia", "cordura", "tiempo"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("hardcore")
                && args[1].equalsIgnoreCase("define")) {
            op.addAll(List.of("entrada", "salida"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("hardcore")
                && List.of("frasco", "cristal", "esencia", "cordura", "tiempo")
                        .contains(args[1].toLowerCase(Locale.ROOT))) {
            for (Player p : plugin.getServer().getOnlinePlayers()) op.add(p.getName());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("pregen")) {
            op.addAll(List.of("start", "status", "pause", "resume", "cancel"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("pregen") && args[1].equalsIgnoreCase("start")) {
            op.addAll(plugin.mundos().keySet());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("biome") && q instanceof Player p) {
            String gen = plugin.generadorDe(p.getWorld());
            if (gen != null) for (String id : plugin.biomasDe(gen)) op.add(id.substring(id.lastIndexOf('/') + 1));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("tp"))) {
            op.addAll(plugin.mundos().keySet());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            op.addAll(plugin.generadores());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("tp")) {
            for (Player p : plugin.getServer().getOnlinePlayers()) op.add(p.getName());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("level")) {
            for (Player p : plugin.getServer().getOnlinePlayers()) op.add(p.getName());
        }
        String ultimo = args[args.length - 1].toLowerCase(Locale.ROOT);
        op.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(ultimo));
        return op;
    }
}
