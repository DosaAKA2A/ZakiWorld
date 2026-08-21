package net.ederus.edm.coinflip;

import net.ederus.edm.comun.Estilo;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /cf — poner, coger y retirar apuestas sin pasar por el menu. */
public final class ComandoCoinflip implements CommandExecutor, TabCompleter {

    private static final String USAR = "ederus.coinflip.usar";
    private static final String ADMIN = "ederus.coinflip.admin";

    private final CoinflipPlugin modulo;

    public ComandoCoinflip(CoinflipPlugin modulo) {
        this.modulo = modulo;
    }

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        if (args.length == 0) {
            if (quien instanceof Player j) {
                if (!puede(j)) return true;
                modulo.menu().abrir(j, 0);
            } else {
                ayuda(quien);
            }
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "crear", "poner", "apostar" -> crear(quien, args);
            case "retar" -> retar(quien, args);
            case "aceptar", "jugar" -> aceptar(quien, args);
            case "cancelar", "retirar" -> cancelar(quien, args);
            case "lista", "ver" -> lista(quien);
            case "recargar" -> recargar(quien);
            case "devolvertodo" -> devolverTodo(quien);
            default -> { ayuda(quien); yield true; }
        };
    }

    private void ayuda(CommandSender q) {
        q.sendMessage("Coinflip de Ederus");
        q.sendMessage("/cf                      abre la mesa");
        q.sendMessage("/cf crear <cantidad>     pone una apuesta abierta (50k, 1.5m valen)");
        if (modulo.mesa().retosActivos()) {
            q.sendMessage("/cf retar <jugador> <cantidad>");
        }
        q.sendMessage("/cf aceptar <numero|jugador>");
        q.sendMessage("/cf cancelar [numero|todas]");
        q.sendMessage("/cf lista");
        if (q.hasPermission(ADMIN)) {
            q.sendMessage("/cf recargar");
            q.sendMessage("/cf devolvertodo          devuelve TODAS las apuestas abiertas");
        }
    }

    private boolean puede(Player j) {
        if (j.hasPermission(USAR)) return true;
        modulo.textos().manda(j, "sin-permiso", "&#FF5C5CNo puedes usar las apuestas.");
        return false;
    }

    // ----------------------------------------------------------------- crear

    private boolean crear(CommandSender quien, String[] args) {
        if (!(quien instanceof Player j)) { quien.sendMessage("Solo desde el juego."); return true; }
        if (!puede(j)) return true;
        if (args.length < 2) {
            /* Sin cantidad no se falla: se la pide por el chat, que es el mismo
             * camino que el boton del menu. */
            modulo.menu().pedirCantidad(j, null);
            return true;
        }
        double cantidad = Mesa.leerCantidad(args[1]);
        if (Double.isNaN(cantidad)) {
            modulo.textos().manda(j, "cantidad-mala", "&#FF5C5CEso no es una cantidad&8: &7%texto%",
                    "%texto%", args[1]);
            return true;
        }
        quien.sendMessage(modulo.mesa().crear(j, cantidad, null).mensaje());
        return true;
    }

    private boolean retar(CommandSender quien, String[] args) {
        if (!(quien instanceof Player j)) { quien.sendMessage("Solo desde el juego."); return true; }
        if (!puede(j)) return true;
        if (!modulo.mesa().retosActivos()) {
            modulo.textos().manda(j, "retos-apagados", "&#FF5C5CLos retos estan desactivados.");
            return true;
        }
        if (args.length < 2) { quien.sendMessage("/cf retar <jugador> <cantidad>"); return true; }

        Player rival = Bukkit.getPlayerExact(args[1]);
        if (rival == null || !rival.isOnline()) {
            modulo.textos().manda(j, "no-esta-conectado", "&#FF5C5C%rival% no esta conectado.",
                    "%rival%", args[1]);
            return true;
        }
        if (args.length < 3) {
            modulo.menu().pedirCantidad(j, rival);
            return true;
        }
        double cantidad = Mesa.leerCantidad(args[2]);
        if (Double.isNaN(cantidad)) {
            modulo.textos().manda(j, "cantidad-mala", "&#FF5C5CEso no es una cantidad&8: &7%texto%",
                    "%texto%", args[2]);
            return true;
        }
        Mesa.Resultado r = modulo.mesa().crear(j, cantidad, rival);
        quien.sendMessage(r.mensaje());
        if (r.ok()) modulo.menu().avisarDelReto(j, rival, cantidad);
        return true;
    }

    // --------------------------------------------------------------- aceptar

    private boolean aceptar(CommandSender quien, String[] args) {
        if (!(quien instanceof Player j)) { quien.sendMessage("Solo desde el juego."); return true; }
        if (!puede(j)) return true;
        if (args.length < 2) { quien.sendMessage("/cf aceptar <numero|jugador>"); return true; }

        Apuesta a = buscar(args[1], j);
        if (a == null) {
            modulo.textos().manda(j, "no-esta", "&#FF5C5CEsa apuesta ya no esta en la mesa.");
            return true;
        }
        modulo.jugar(j, a);
        return true;
    }

    /** Por numero de apuesta o por el nombre del que la puso, que es como la
     *  gente se refiere a ellas de verdad. */
    private Apuesta buscar(String texto, Player quien) {
        try {
            return modulo.mesa().de(Long.parseLong(texto.trim()));
        } catch (NumberFormatException ignored) {
            /* No era un numero: se busca por nombre. */
        }
        for (Apuesta a : modulo.mesa().visiblesPara(quien.getUniqueId())) {
            if (a.nombreCreador().equalsIgnoreCase(texto) && !a.esDe(quien.getUniqueId())) return a;
        }
        return null;
    }

    private boolean cancelar(CommandSender quien, String[] args) {
        if (!(quien instanceof Player j)) { quien.sendMessage("Solo desde el juego."); return true; }
        List<Apuesta> mias = modulo.mesa().deJugador(j.getUniqueId());
        if (mias.isEmpty()) {
            modulo.textos().manda(j, "no-tienes", "&7No tienes ninguna apuesta puesta.");
            return true;
        }
        if (args.length >= 2 && !args[1].equalsIgnoreCase("todas")) {
            Apuesta a = modulo.mesa().de(leerId(args[1]));
            quien.sendMessage(modulo.mesa().cancelar(j, a).mensaje());
            return true;
        }
        double vuelto = 0;
        int n = 0;
        for (Apuesta a : mias) {
            if (modulo.mesa().cancelar(j, a).ok()) { vuelto += a.cantidad(); n++; }
        }
        modulo.textos().manda(j, "retiradas",
                "&fRetiraste &x&D&7&F&3&F&F%cuantas% &fapuestas y se te devolvieron &#4FFF55%total%",
                "%cuantas%", String.valueOf(n), "%total%", Estilo.dinero(vuelto));
        return true;
    }

    private static long leerId(String texto) {
        try { return Long.parseLong(texto.trim()); } catch (NumberFormatException e) { return -1; }
    }

    private boolean lista(CommandSender quien) {
        List<Apuesta> lista = quien instanceof Player j
                ? modulo.mesa().visiblesPara(j.getUniqueId())
                : modulo.mesa().todas();
        if (lista.isEmpty()) { quien.sendMessage("No hay ninguna apuesta en la mesa."); return true; }
        quien.sendMessage("Apuestas en la mesa (" + lista.size() + "):");
        for (Apuesta a : lista) {
            quien.sendMessage("  #" + a.id() + "  " + a.nombreCreador()
                    + "  " + Estilo.dinero(a.cantidad())
                    + (a.esReto() ? "  (reto a " + a.nombreRetado() + ")" : "")
                    + (a.tomada() ? "  [en juego]" : ""));
        }
        return true;
    }

    private boolean recargar(CommandSender quien) {
        if (!quien.hasPermission(ADMIN)) { quien.sendMessage("No puedes."); return true; }
        quien.sendMessage(modulo.recargar());
        return true;
    }

    private boolean devolverTodo(CommandSender quien) {
        if (!quien.hasPermission(ADMIN)) { quien.sendMessage("No puedes."); return true; }
        int n = modulo.mesa().devolverTodo("ADMIN");
        quien.sendMessage("Devueltas " + n + " apuestas.");
        return true;
    }

    // -------------------------------------------------------------- completar

    @Override
    public List<String> onTabComplete(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("crear", "aceptar", "cancelar", "lista"));
            if (modulo.mesa().retosActivos()) base.add("retar");
            if (quien.hasPermission(ADMIN)) { base.add("recargar"); base.add("devolvertodo"); }
            for (String s : base) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("retar")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.equals(quien) && p.getName().toLowerCase(Locale.ROOT)
                        .startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("aceptar") && quien instanceof Player j) {
            for (Apuesta a : modulo.mesa().visiblesPara(j.getUniqueId())) {
                if (!a.esDe(j.getUniqueId())) out.add(String.valueOf(a.id()));
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cancelar") && quien instanceof Player j) {
            out.add("todas");
            for (Apuesta a : modulo.mesa().deJugador(j.getUniqueId())) out.add(String.valueOf(a.id()));
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("crear")) {
            out.addAll(List.of("10k", "50k", "100k", "1m"));
            return out;
        }
        return out;
    }
}
