package net.ederus.edm.glow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * /glow                      el menu
 * /glow <color>              ponerse un color (aqua, dark_red...)
 * /glow rainbow              el arcoiris
 * /glow flash                parpadeo si/no
 * /glow off                  apagarlo
 * /glow list                 los colores que tienes
 * /glow set <jugador> <color|rainbow|off>   (fancyglow.admin o fancyglow.command.set)
 * /glow reload               (fancyglow.admin)
 *
 * Comandos en ingles, como todos los del servidor.
 */
final class ComandoGlow implements TabExecutor {

    private final GlowPlugin glow;

    ComandoGlow(GlowPlugin glow) {
        this.glow = glow;
    }

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu", "gui" -> {
                if (quien instanceof Player p) glow.menu().abrir(p);
                else glow.di(quien, "uso", "&7Uso: /glow");
            }
            case "off", "disable", "none" -> {
                if (!(quien instanceof Player p)) return true;
                glow.elegir(p, null);
                glow.di(p, "apagado", "&7Ya no brillas.");
            }
            case "rainbow" -> {
                if (!(quien instanceof Player p)) return true;
                if (!glow.puedeArcoiris(p)) {
                    glow.di(p, "sin-permiso", "&cTodavía no tienes ese brillo.");
                    return true;
                }
                Almacen.Eleccion antes = glow.eleccion(p);
                glow.elegir(p, new Almacen.Eleccion("rainbow", antes != null && antes.parpadeo()));
                glow.di(p, "arcoiris", "&7Ahora brillas en arcoíris.");
                avisarSinEquipo(p);
            }
            case "flash", "flashing" -> {
                if (!(quien instanceof Player p)) return true;
                if (!glow.puedeParpadeo(p)) {
                    glow.di(p, "sin-permiso", "&cTodavía no tienes ese brillo.");
                    return true;
                }
                Almacen.Eleccion antes = glow.eleccion(p);
                if (antes == null) {
                    glow.di(p, "sin-brillo", "&7Primero elige un color con /glow.");
                    return true;
                }
                glow.elegir(p, new Almacen.Eleccion(antes.color(), !antes.parpadeo()));
                glow.di(p, antes.parpadeo() ? "parpadeo-off" : "parpadeo-on", "");
            }
            case "list" -> {
                StringJoiner j = new StringJoiner("&7, ");
                for (Brillo b : Brillo.values()) if (glow.puedeColor(quien, b)) j.add(GlowPlugin.pintado(b));
                if (glow.puedeArcoiris(quien)) j.add("&frainbow");
                glow.di(quien, "lista", "&7Colores: %lista%", "%lista%", j.length() == 0 ? "&8-" : j.toString());
            }
            case "set" -> set(quien, args);
            case "reload" -> {
                if (!quien.hasPermission("fancyglow.admin")) {
                    glow.di(quien, "sin-permiso", "&cNo puedes.");
                    return true;
                }
                glow.recargar();
                glow.di(quien, "recargado", "&7Brillo recargado.");
            }
            case "help" -> {
                glow.di(quien, "uso", "&7Uso: /glow");
                if (quien.hasPermission("fancyglow.admin")) glow.di(quien, "uso-admin", "");
            }
            default -> color(quien, sub);
        }
        return true;
    }

    private void color(CommandSender quien, String nombre) {
        if (!(quien instanceof Player p)) return;
        Brillo b = Brillo.de(nombre);
        if (b == null) {
            glow.di(p, "no-existe", "&cNo conozco ese color.", "%color%", nombre);
            return;
        }
        if (!glow.puedeColor(p, b)) {
            glow.di(p, "sin-permiso", "&cTodavía no tienes ese brillo.");
            return;
        }
        Almacen.Eleccion antes = glow.eleccion(p);
        glow.elegir(p, new Almacen.Eleccion(b.clave, antes != null && antes.parpadeo()));
        glow.di(p, "puesto", "&7Ahora brillas en %color%&7.", "%color%", GlowPlugin.pintado(b));
        avisarSinEquipo(p);
    }

    private void avisarSinEquipo(Player p) {
        if (!glow.conoceEquipo(p)) glow.di(p, "sin-equipo", "");
    }

    private void set(CommandSender quien, String[] args) {
        if (!quien.hasPermission("fancyglow.admin") && !quien.hasPermission("fancyglow.command.set")) {
            glow.di(quien, "sin-permiso", "&cNo puedes.");
            return;
        }
        if (args.length < 3) {
            glow.di(quien, "uso-admin", "&7/glow set <jugador> <color|rainbow|off>");
            return;
        }
        OfflinePlayer o = Bukkit.getPlayerExact(args[1]);
        if (o == null) o = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (o == null) {
            glow.di(quien, "no-jugador", "&cNo encuentro a ese jugador.", "%jugador%", args[1]);
            return;
        }
        String nombre = o.getName() == null ? args[1] : o.getName();
        String que = args[2].toLowerCase(Locale.ROOT);
        if (que.equals("off") || que.equals("none")) {
            glow.elegir(o.getUniqueId(), null);
            glow.di(quien, "otro-apagado", "&7%jugador% ya no brilla.", "%jugador%", nombre);
            return;
        }
        if (que.equals("rainbow")) {
            glow.elegir(o.getUniqueId(), new Almacen.Eleccion("rainbow", false));
            glow.di(quien, "otro-puesto", "&7%jugador% ahora brilla.", "%jugador%", nombre, "%color%", "&farcoíris");
            return;
        }
        Brillo b = Brillo.de(que);
        if (b == null) {
            glow.di(quien, "no-existe", "&cNo conozco ese color.", "%color%", args[2]);
            return;
        }
        glow.elegir(o.getUniqueId(), new Almacen.Eleccion(b.clave, false));
        glow.di(quien, "otro-puesto", "&7%jugador% ahora brilla.", "%jugador%", nombre, "%color%", GlowPlugin.pintado(b));
    }

    @Override
    public List<String> onTabComplete(CommandSender quien, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("off", "rainbow", "flash", "list"));
            for (Brillo b : Brillo.values()) out.add(b.clave);
            if (quien.hasPermission("fancyglow.admin") || quien.hasPermission("fancyglow.command.set")) out.add("set");
            if (quien.hasPermission("fancyglow.admin")) out.add("reload");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            out.addAll(List.of("off", "rainbow"));
            for (Brillo b : Brillo.values()) out.add(b.clave);
        }
        String ultimo = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(ultimo));
        return out;
    }
}
