package net.ederus.edm.mundos;

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

import net.ederus.edm.Module;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * /lw: todo por comando, sin menu, para que desde Bedrock se haga igual.
 * Subcomandos en ingles, como /lbiomes.
 */
final class ComandoMundos implements TabExecutor {

    private static final TextColor MARCA = TextColor.color(0xE0664A);
    private static final TextColor SUAVE = TextColor.color(0xC9BDB8);

    private final MundosPlugin modulo;

    ComandoMundos(MundosPlugin modulo) {
        this.modulo = modulo;
    }

    private void decir(CommandSender a, Component texto) {
        a.sendMessage(Component.text("Lethal World ", MARCA, TextDecoration.BOLD)
                .append(Component.text("> ", NamedTextColor.DARK_GRAY))
                .append(texto.colorIfAbsent(SUAVE)));
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
            default -> ayuda(quien);
        }
        return true;
    }

    private void ayuda(CommandSender q) {
        decir(q, "Mundos con el generador de Lethal World.");
        linea(q, "/lw list", "mundos creados y si ya estan cargados");
        linea(q, "/lw generators", "generadores disponibles");
        linea(q, "/lw create <name> <generator> [seed]", "crea el mundo (existe tras reiniciar)");
        linea(q, "/lw delete <name>", "lo quita (la carpeta del mundo no se borra)");
        linea(q, "/lw tp <name> [player]", "lleva a un sitio seguro del mundo");
        linea(q, "/lw biomes", "biomas del mundo en el que estás (pulsables)");
        linea(q, "/lw biome <biome> [radius]", "busca el más cercano y te lleva");
        linea(q, "/lw pregen start <name> [radius]", "pregenera (sin radio: el borde o el de la config)");
        linea(q, "/lw pregen status|pause|resume|cancel", "");
    }

    private static void linea(CommandSender q, String uso, String que) {
        q.sendMessage(Component.text("  " + uso, NamedTextColor.WHITE).append(Component.text("  " + que, SUAVE)));
    }

    private void lista(CommandSender q) {
        Map<String, String> mundos = modulo.mundos();
        if (mundos.isEmpty()) {
            decir(q, "No hay mundos. Crea uno con /lw create <name> <generator>.");
            return;
        }
        decir(q, "Mundos:");
        for (Map.Entry<String, String> e : mundos.entrySet()) {
            World w = modulo.mundo(e.getKey());
            q.sendMessage(Component.text("  " + e.getKey(), NamedTextColor.WHITE)
                    .append(Component.text("  " + modulo.nombreGenerador(e.getValue())
                            + (modulo.semillaDe(e.getKey()) != null ? " · semilla " + modulo.semillaDe(e.getKey()) : "")
                            + "  ", SUAVE))
                    .append(w != null
                            ? Component.text("cargado (" + w.getName() + ")", NamedTextColor.GREEN)
                            : Component.text("se carga en el próximo reinicio", NamedTextColor.GOLD)));
        }
    }

    private void listaGeneradores(CommandSender q) {
        decir(q, "Generadores:");
        for (String id : modulo.generadores()) {
            List<String> usan = modulo.mismoGenerador(id);
            q.sendMessage(Component.text("  " + id, NamedTextColor.WHITE)
                    .append(Component.text("  " + modulo.nombreGenerador(id), SUAVE))
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
        List<String> previos = modulo.mismoGenerador(args[2].toLowerCase(Locale.ROOT), semilla, true);
        String error = modulo.crear(args[1], args[2], semilla);
        if (error != null) {
            decir(q, Component.text(error, NamedTextColor.RED));
            return;
        }
        decir(q, "Mundo " + args[1].toLowerCase(Locale.ROOT) + " creado con "
                + modulo.nombreGenerador(args[2].toLowerCase(Locale.ROOT))
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
        String error = modulo.borrar(args[1]);
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
            objetivo = modulo.getServer().getPlayerExact(args[2]);
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
        World w = modulo.mundo(args[1]);
        if (w == null) {
            decir(q, Component.text(modulo.mundos().containsKey(args[1].toLowerCase(Locale.ROOT))
                    ? "El mundo aún no está cargado: falta reiniciar."
                    : "No existe el mundo '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }
        Location spawn = w.getSpawnLocation();
        w.getChunkAtAsync(spawn).thenAccept(chunk -> modulo.getServer().getScheduler().runTask(Module.dueno(modulo), () -> {
            Location destino = sitioSeguro(w, spawn.getBlockX(), spawn.getBlockZ());
            boolean seguro = destino != null;
            if (!seguro) destino = new Location(w, spawn.getBlockX() + 0.5, Math.min(w.getLogicalHeight() - 2, 200), spawn.getBlockZ() + 0.5);
            if (!seguro) objetivo.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 30, 1));
            objetivo.teleportAsync(destino);
            modulo.bitacora().anotar("tp", objetivo.getName(), w.getName(), seguro ? "suelo" : "caida lenta");
        }));
    }

    private void pregen(CommandSender q, String[] args) {
        Pregenerador pg = modulo.pregen();
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "start" -> {
                if (args.length < 3) {
                    linea(q, "/lw pregen start <name> [radius]", "");
                    return;
                }
                World w = modulo.mundo(args[2]);
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
        String gen = modulo.generadorDe(p.getWorld());
        if (gen == null) {
            decir(q, "No estás en un mundo de Lethal World. Entra con /lw tp <name>.");
            return;
        }
        List<String> ids = modulo.biomasDe(gen);
        decir(q, "Biomas de " + modulo.nombreGenerador(gen) + " (" + ids.size() + "). Pulsa uno para ir:");
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
        String gen = modulo.generadorDe(w);
        Biome bioma = resolverBioma(args[1], gen == null ? List.of() : modulo.biomasDe(gen));
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
        modulo.getServer().getScheduler().runTask(Module.dueno(modulo), () -> {
            long inicio = System.currentTimeMillis();
            var resultado = w.locateNearestBiome(p.getLocation(), r, 32, 64, bioma);
            long ms = System.currentTimeMillis() - inicio;
            if (resultado == null) {
                decir(q, Component.text("No hay " + bioma.getKey().getKey() + " a menos de " + r
                        + " bloques. Prueba con un radio mayor: /lw biome " + args[1] + " 8000", NamedTextColor.GOLD));
                modulo.bitacora().anotar("biome", p.getName(), bioma.getKey().asString(), "no encontrado",
                        r + " bloques", ms + " ms");
                return;
            }
            Location hallado = resultado.getLocation();
            int distancia = (int) Math.hypot(hallado.getX() - p.getLocation().getX(), hallado.getZ() - p.getLocation().getZ());
            w.getChunkAtAsync(hallado).thenAccept(ch -> modulo.getServer().getScheduler().runTask(Module.dueno(modulo), () -> {
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
                modulo.bitacora().anotar("biome", p.getName(), bioma.getKey().asString(),
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

    @Override
    public List<String> onTabComplete(CommandSender q, Command cmd, String etiqueta, String[] args) {
        List<String> op = new ArrayList<>();
        if (args.length == 1) {
            op.addAll(List.of("list", "generators", "create", "delete", "tp", "biomes", "biome", "pregen"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("pregen")) {
            op.addAll(List.of("start", "status", "pause", "resume", "cancel"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("pregen") && args[1].equalsIgnoreCase("start")) {
            op.addAll(modulo.mundos().keySet());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("biome") && q instanceof Player p) {
            String gen = modulo.generadorDe(p.getWorld());
            if (gen != null) for (String id : modulo.biomasDe(gen)) op.add(id.substring(id.lastIndexOf('/') + 1));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("tp"))) {
            op.addAll(modulo.mundos().keySet());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            op.addAll(modulo.generadores());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("tp")) {
            for (Player p : modulo.getServer().getOnlinePlayers()) op.add(p.getName());
        }
        String ultimo = args[args.length - 1].toLowerCase(Locale.ROOT);
        op.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(ultimo));
        return op;
    }
}
