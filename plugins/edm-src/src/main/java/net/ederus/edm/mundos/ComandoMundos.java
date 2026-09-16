package net.ederus.edm.mundos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import net.ederus.edm.Module;
import net.kyori.adventure.text.Component;
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
            default -> ayuda(quien);
        }
        return true;
    }

    private void ayuda(CommandSender q) {
        decir(q, "Mundos con el generador de Lethal World.");
        linea(q, "/lw list", "mundos creados y si ya estan cargados");
        linea(q, "/lw generators", "generadores disponibles");
        linea(q, "/lw create <name> <generator>", "crea el mundo (existe tras reiniciar)");
        linea(q, "/lw delete <name>", "lo quita (la carpeta del mundo no se borra)");
        linea(q, "/lw tp <name> [player]", "lleva a un sitio seguro del mundo");
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
                    .append(Component.text("  " + modulo.nombreGenerador(e.getValue()) + "  ", SUAVE))
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
            linea(q, "/lw create <name> <generator>", "ver /lw generators");
            return;
        }
        List<String> previos = modulo.mismoGenerador(args[2]);
        String error = modulo.crear(args[1], args[2]);
        if (error != null) {
            decir(q, Component.text(error, NamedTextColor.RED));
            return;
        }
        decir(q, "Mundo " + args[1].toLowerCase(Locale.ROOT) + " creado con "
                + modulo.nombreGenerador(args[2].toLowerCase(Locale.ROOT))
                + ". Reinicia el servidor para que exista.");
        if (!previos.isEmpty()) {
            decir(q, Component.text("Ojo: " + String.join(", ", previos) + " usa el mismo generador y la"
                    + " misma semilla del servidor, así que el terreno saldrá idéntico.", NamedTextColor.GOLD));
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

    /**
     * Un hueco de dos bloques de aire sobre suelo solido, buscando de arriba abajo desde
     * la altura logica: las dimensiones con techo tienen roca madre arriba y el bloque mas
     * alto no sirve. Prueba la columna del spawn y unas cuantas alrededor.
     */
    private static Location sitioSeguro(World w, int x0, int z0) {
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
                        && suelo.getType() != Material.MAGMA_BLOCK) {
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
            op.addAll(List.of("list", "generators", "create", "delete", "tp"));
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
