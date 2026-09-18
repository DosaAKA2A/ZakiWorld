package net.ederus.edm.minas;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.ederus.edm.comun.Estilo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * /mina: el menu, y unos pocos atajos para quien prefiere escribir.
 *
 * Para el jugador, /mina es la lista de minas a las que puede ir y /mina tp
 * <mina> el viaje directo. Para el staff, /mina es el editor entero; wand, crear
 * y reset existen para no tener que abrir el menu por una cosa suelta.
 */
public final class ComandoMinas implements CommandExecutor, TabCompleter {

    private final MinasPlugin plugin;

    public ComandoMinas(MinasPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        if (!(quien instanceof Player p)) {
            if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
                quien.sendMessage("Recargado: " + plugin.recargar());
                return true;
            }
            quien.sendMessage(plugin.texto("solo-en-juego", "Las minas se manejan dentro del juego."));
            return true;
        }

        if (args.length == 0) {
            if (plugin.esAdmin(p)) plugin.menu().abrirLista(p);
            else plugin.menu().abrirJugador(p);
            return true;
        }

        String uno = args[0].toLowerCase(Locale.ROOT);
        switch (uno) {
            case "tp", "ir" -> {
                if (args.length < 2) {
                    plugin.menu().abrirJugador(p);
                    return true;
                }
                Mina m = plugin.minas().de(args[1]);
                if (m == null) {
                    plugin.di(p, "sin-mina", "No hay ninguna mina llamada %mina%.", "%mina%", args[1]);
                    return true;
                }
                plugin.viajar(p, m);
                return true;
            }
            case "lista", "list" -> {
                lista(p);
                return true;
            }
            case "ayuda", "help" -> {
                ayuda(p, etiqueta);
                return true;
            }
            default -> { }
        }

        if (!plugin.esAdmin(p)) {
            plugin.di(p, "sin-permiso", "No puedes administrar las minas.");
            return true;
        }
        switch (uno) {
            case "wand", "pico" -> {
                p.getInventory().addItem(plugin.varita().crear());
                plugin.di(p, "pico-entregado", "Tienes el pico de minas. Izquierdo: esquina 1. Derecho: esquina 2.");
            }
            case "crear", "create", "nueva" -> {
                if (args.length < 2) {
                    ayuda(p, etiqueta);
                    return true;
                }
                String nombre = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                Mina m = crearConSeleccion(p, nombre);
                if (m != null) plugin.menu().abrirFicha(p, m.id());
            }
            case "reset", "reiniciar" -> {
                if (args.length < 2 || args[1].equalsIgnoreCase("todas") || args[1].equalsIgnoreCase("all")) {
                    int n = 0;
                    for (Mina m : plugin.minas().todas()) if (plugin.reinicio().reiniciar(m, "comando")) n++;
                    p.sendMessage(Estilo.aviso(Estilo.texto("Reiniciando " + n + " mina(s).", NamedTextColor.WHITE)));
                    return true;
                }
                Mina m = plugin.minas().de(args[1]);
                if (m == null) {
                    plugin.di(p, "sin-mina", "No hay ninguna mina llamada %mina%.", "%mina%", args[1]);
                    return true;
                }
                if (plugin.reinicio().reiniciar(m, "comando")) {
                    plugin.di(p, "reinicio-lanzado", "Reiniciando %mina%.", "%mina%", m.nombre());
                } else {
                    plugin.di(p, "no-se-pudo", "No se pudo reiniciar %mina%: sin zona, sin bloques o ya en marcha.", "%mina%", m.nombre());
                }
            }
            case "reload" -> p.sendMessage(plugin.texto("recargado", "Recargado: %que%", "%que%", plugin.recargar()));
            default -> {
                Mina m = plugin.minas().de(uno);
                if (m != null) plugin.menu().abrirFicha(p, m.id());
                else ayuda(p, etiqueta);
            }
        }
        return true;
    }

    /**
     * Crea una mina con la seleccion del pico. Es lo mismo que "Nueva mina" del
     * menu: nace con una mezcla de piedra, carbon y hierro para que se pueda
     * reiniciar al momento, y luego se cambia lo que haga falta desde su ficha.
     */
    public Mina crearConSeleccion(Player p, String nombre) {
        Varita.Seleccion s = plugin.varita().de(p);
        if (s == null || !s.completa()) {
            plugin.di(p, "falta-zona", "Primero marca las dos esquinas con el pico. Lo tienes en /mina o con /mina wand.");
            return null;
        }
        Mina m = plugin.minas().crear(nombre);
        m.zona(s.mundo(), s.a()[0], s.a()[1], s.a()[2], s.b()[0], s.b()[1], s.b()[2]);
        m.poner(Material.STONE, 70);
        m.poner(Material.COAL_ORE, 20);
        m.poner(Material.IRON_ORE, 10);
        m.intervalo(600);
        plugin.minas().guardar();
        plugin.anotar("creada", m.id(), p.getName(), s.mundo(), s.medidas());
        plugin.di(p, "creada", "Mina creada: %mina% (%id%)", "%mina%", m.nombre(), "%id%", m.id());
        return m;
    }

    private void lista(Player p) {
        List<Mina> todas = plugin.minas().todas();
        p.sendMessage(Component.empty());
        p.sendMessage(Estilo.cabecera("MINAS", todas.isEmpty() ? "ninguna todavía" : todas.size() + " en total", MinasPlugin.MARCA));
        for (Mina m : todas) {
            boolean puede = plugin.puedeEntrar(p, m);
            p.sendMessage(Component.text("  " + Estilo.FLECHA + " ", Estilo.APAGADO)
                    .append(Component.text(m.nombre(), puede ? MinasPlugin.MARCA : Estilo.APAGADO))
                    .append(Component.text("  " + Math.round(100 - m.porcentajeMinado()) + "% por picar", NamedTextColor.WHITE))
                    .append(Component.text("  " + (m.segundos() < 0 ? "" : "reinicia en " + m.cuentaAtras()), Estilo.APAGADO))
                    .append(Component.text(puede ? "" : "  sin acceso", Estilo.APAGADO)));
        }
        p.sendMessage(Component.empty());
    }

    private void ayuda(Player p, String etiqueta) {
        p.sendMessage(Estilo.cabecera("MINAS", null, MinasPlugin.MARCA));
        linea(p, "/" + etiqueta, plugin.esAdmin(p) ? "el editor de minas" : "las minas a las que puedes ir");
        linea(p, "/" + etiqueta + " tp <mina>", "viaja a una mina");
        linea(p, "/" + etiqueta + " lista", "todas, con lo que queda por picar");
        if (plugin.esAdmin(p)) {
            linea(p, "/" + etiqueta + " wand", "el pico de selección");
            linea(p, "/" + etiqueta + " crear <nombre>", "una mina con la zona marcada");
            linea(p, "/" + etiqueta + " reset [mina|todas]", "rellena ahora");
            linea(p, "/" + etiqueta + " reload", "vuelve a leer minas.yml y los mensajes");
        }
    }

    private void linea(Player p, String comando, String que) {
        p.sendMessage(Component.text("  " + comando + "  ", NamedTextColor.WHITE)
                .append(Component.text(que, NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        List<String> out = new ArrayList<>();
        if (!(quien instanceof Player p)) return out;
        String pref = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("tp", "lista", "ayuda"));
            if (plugin.esAdmin(p)) subs.addAll(List.of("wand", "crear", "reset", "reload"));
            for (String s : subs) if (s.startsWith(pref)) out.add(s);
            if (plugin.esAdmin(p)) {
                for (Mina m : plugin.minas().todas()) if (m.id().startsWith(pref)) out.add(m.id());
            }
            return out;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("tp") || args[0].equalsIgnoreCase("reset"))) {
            for (Mina m : plugin.minas().todas()) {
                if (m.id().startsWith(pref) && (plugin.esAdmin(p) || plugin.puedeEntrar(p, m))) out.add(m.id());
            }
            if (args[0].equalsIgnoreCase("reset") && "todas".startsWith(pref)) out.add("todas");
        }
        return out;
    }
}
