package net.ederus.edm.biomas;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * /lbiomes: todo por comando, sin menu, para que desde Bedrock se haga igual.
 */
final class ComandoBiomas implements TabExecutor {

    private static final TextColor MARCA = TextColor.color(0x7FD8A6);
    private static final TextColor SUAVE = TextColor.color(0xB8C4BE);

    private final BiomasPlugin modulo;

    ComandoBiomas(BiomasPlugin modulo) {
        this.modulo = modulo;
    }

    private void decir(CommandSender a, Component texto) {
        a.sendMessage(Component.text("Lethal Biomes ", MARCA, TextDecoration.BOLD)
                .append(Component.text("> ", NamedTextColor.DARK_GRAY))
                .append(texto.colorIfAbsent(SUAVE)));
    }

    private void decir(CommandSender a, String texto) {
        decir(a, Component.text(texto));
    }

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        if (!quien.hasPermission("ederus.biomas")) {
            decir(quien, Component.text("No tienes permiso.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            ayuda(quien);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> lista(quien);
            case "zone" -> zona(quien, args);
            case "zones" -> zonas(quien);
            case "paint" -> pintar(quien, args);
            case "clear" -> limpiar(quien, args);
            default -> ayuda(quien);
        }
        return true;
    }

    private void ayuda(CommandSender q) {
        decir(q, "Climas por zona, sin reiniciar.");
        q.sendMessage(Component.text("  /lbiomes list", NamedTextColor.WHITE).append(Component.text("  climas disponibles", SUAVE)));
        q.sendMessage(Component.text("  /lbiomes zones", NamedTextColor.WHITE).append(Component.text("  zonas creadas y su clima", SUAVE)));
        q.sendMessage(Component.text("  /lbiomes zone create <name> region <wgRegion> [world]", NamedTextColor.WHITE));
        q.sendMessage(Component.text("  /lbiomes zone create <name> here <radius>", NamedTextColor.WHITE));
        q.sendMessage(Component.text("  /lbiomes zone create <name> <x1> <y1> <z1> <x2> <y2> <z2> [world]", NamedTextColor.WHITE));
        q.sendMessage(Component.text("  /lbiomes zone delete <name>", NamedTextColor.WHITE));
        q.sendMessage(Component.text("  /lbiomes paint <zone> <biome>", NamedTextColor.WHITE));
        q.sendMessage(Component.text("  /lbiomes clear <zone>", NamedTextColor.WHITE).append(Component.text("  vuelve a su bioma base", SUAVE)));
    }

    private void lista(CommandSender q) {
        decir(q, "Climas:");
        for (String id : modulo.climas()) {
            boolean cargado = Pintor.bioma(id) != null;
            q.sendMessage(Component.text("  " + id, cargado ? NamedTextColor.WHITE : NamedTextColor.GRAY)
                    .append(Component.text("  " + modulo.nombreClima(id), SUAVE))
                    .append(cargado ? Component.empty()
                            : Component.text("  (se carga en el próximo reinicio)", NamedTextColor.GOLD)));
        }
    }

    private void zonas(CommandSender q) {
        List<Zona> todas = modulo.zonas();
        if (todas.isEmpty()) {
            decir(q, "No hay zonas. Crea una con /lbiomes zone create.");
            return;
        }
        decir(q, "Zonas:");
        for (Zona z : todas) {
            q.sendMessage(Component.text("  " + z.nombre(), NamedTextColor.WHITE)
                    .append(Component.text("  " + z.mundo() + "  " + z.medidas() + "  ", SUAVE))
                    .append(z.actual() == null
                            ? Component.text("base " + z.base(), NamedTextColor.GRAY)
                            : Component.text(modulo.nombreClima(z.actual()), MARCA)));
        }
    }

    private void zona(CommandSender q, String[] args) {
        if (args.length >= 3 && args[1].equalsIgnoreCase("delete")) {
            decir(q, modulo.borrarZona(args[2]) ? "Zona " + args[2] + " borrada. El bioma pintado se queda como esta."
                    : "No existe la zona " + args[2] + ".");
            return;
        }
        if (args.length < 4 || !args[1].equalsIgnoreCase("create")) {
            ayuda(q);
            return;
        }
        String nombre = args[2];
        World mundo = q instanceof Player p ? p.getWorld() : null;
        Zona creada;
        if (args[3].equalsIgnoreCase("region")) {
            if (args.length < 5) {
                ayuda(q);
                return;
            }
            if (args.length >= 6) mundo = modulo.getServer().getWorld(args[5]);
            if (mundo == null) {
                decir(q, "Indica el mundo: /lbiomes zone create " + nombre + " region " + args[4] + " <world>");
                return;
            }
            int[] c = modulo.regionWorldGuard(mundo.getName(), args[4]);
            if (c == null) {
                decir(q, "No encuentro la region " + args[4] + " en " + mundo.getName()
                        + " (si es nueva, haz /rg save y repite).");
                return;
            }
            creada = modulo.crearZona(nombre, mundo, c[0], c[1], c[2], c[3], c[4], c[5]);
        } else if (args[3].equalsIgnoreCase("here")) {
            if (!(q instanceof Player p) || args.length < 5) {
                decir(q, "Desde dentro del juego: /lbiomes zone create " + nombre + " here <radius>");
                return;
            }
            int r = entero(args[4], 32);
            Location l = p.getLocation();
            creada = modulo.crearZona(nombre, p.getWorld(), l.getBlockX() - r, l.getBlockY() - r, l.getBlockZ() - r,
                    l.getBlockX() + r, l.getBlockY() + r, l.getBlockZ() + r);
        } else {
            if (args.length < 9) {
                ayuda(q);
                return;
            }
            if (args.length >= 10) mundo = modulo.getServer().getWorld(args[9]);
            if (mundo == null) {
                decir(q, "Indica el mundo al final del comando.");
                return;
            }
            creada = modulo.crearZona(nombre, mundo, entero(args[3], 0), entero(args[4], 0), entero(args[5], 0),
                    entero(args[6], 0), entero(args[7], 0), entero(args[8], 0));
        }
        decir(q, "Zona " + creada.nombre() + " lista en " + creada.mundo() + " (" + creada.medidas()
                + ", base " + creada.base() + ").");
    }

    private void pintar(CommandSender q, String[] args) {
        if (args.length < 3) {
            ayuda(q);
            return;
        }
        String clima = args[2].toLowerCase(Locale.ROOT);
        String error = modulo.pintar(args[1], clima, () -> decir(q, "Zona " + args[1] + " pintada de "
                + modulo.nombreClima(clima) + ". Si no lo ves, sal y entra de la zona."));
        decir(q, error == null ? "Pintando " + args[1] + " de " + modulo.nombreClima(clima) + "..." : error);
    }

    private void limpiar(CommandSender q, String[] args) {
        if (args.length < 2) {
            ayuda(q);
            return;
        }
        String error = modulo.pintar(args[1], null, () -> decir(q, "Zona " + args[1] + " devuelta a su bioma base."));
        decir(q, error == null ? "Limpiando " + args[1] + "..." : error);
    }

    private static int entero(String s, int def) {
        try {
            return (int) Math.floor(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender q, Command cmd, String etiqueta, String[] args) {
        List<String> op = new ArrayList<>();
        if (args.length == 1) {
            op.addAll(List.of("list", "zones", "zone", "paint", "clear"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("zone")) {
            op.addAll(List.of("create", "delete"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("paint") || args[0].equalsIgnoreCase("clear"))) {
            for (Zona z : modulo.zonas()) op.add(z.nombre());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("paint")) {
            op.addAll(modulo.climas());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("zone") && args[1].equalsIgnoreCase("delete")) {
            for (Zona z : modulo.zonas()) op.add(z.nombre());
        } else if (args.length == 4 && args[0].equalsIgnoreCase("zone")) {
            op.addAll(List.of("region", "here"));
        } else if (args.length == 6 && args[3].equalsIgnoreCase("region")) {
            for (World w : modulo.getServer().getWorlds()) op.add(w.getName());
        }
        String ultimo = args[args.length - 1].toLowerCase(Locale.ROOT);
        op.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(ultimo));
        return op;
    }
}
