package net.ederus.edm.troll;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /troll — el menu, una broma suelta, o deshacer lo que quedara puesto. */
public final class ComandoTroll implements CommandExecutor, TabCompleter {

    private final TrollPlugin modulo;

    public ComandoTroll(TrollPlugin modulo) {
        this.modulo = modulo;
    }

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        if (!quien.hasPermission("ederus.troll.usar")) {
            modulo.textos().manda(quien, "sin-permiso", "&#FF5C5CNo puedes usar las bromas.");
            return true;
        }

        if (args.length == 0) {
            if (quien instanceof Player j) modulo.menu().abrirJugadores(j, 0);
            else ayuda(quien);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "lista" -> { return lista(quien); }
            case "deshacer" -> { return deshacer(quien, args); }
            case "activas" -> { return activas(quien); }
            case "recargar" -> { return recargar(quien); }
            case "ayuda" -> { ayuda(quien); return true; }
            default -> { /* es un nombre de jugador */ }
        }

        Player victima = Bukkit.getPlayerExact(args[0]);
        if (victima == null) {
            modulo.textos().manda(quien, "no-esta-conectado", "&#FF5C5C%rival% no esta conectado.",
                    "%rival%", args[0]);
            return true;
        }
        if (!(quien instanceof Player admin)) {
            quien.sendMessage("Las bromas se lanzan desde el juego.");
            return true;
        }

        if (args.length == 1) {
            modulo.menu().abrirBromas(admin, victima, null, 0);
            return true;
        }

        String id = args[1].toLowerCase(Locale.ROOT);
        if (id.equals("azar")) {
            List<Troll> pool = modulo.sorteables();
            if (pool.isEmpty()) return true;
            modulo.lanzar(admin, victima, pool.get(Contexto.azar().nextInt(pool.size())), false);
            return true;
        }
        Troll t = modulo.catalogo().get(id);
        if (t == null) {
            modulo.textos().manda(admin, "no-existe", "&#FF5C5CNo tengo ninguna broma que se llame %broma%.",
                    "%broma%", args[1]);
            return true;
        }
        modulo.lanzar(admin, victima, t, false);
        return true;
    }

    private void ayuda(CommandSender q) {
        q.sendMessage("Bromas de Ederus");
        q.sendMessage("/troll                       abre el menu");
        q.sendMessage("/troll <jugador>             sus bromas");
        q.sendMessage("/troll <jugador> <broma>     directa (azar para una al azar)");
        q.sendMessage("/troll deshacer <jugador>    le quita todo lo que tenga encima");
        q.sendMessage("/troll activas               quien tiene algo puesto");
        q.sendMessage("/troll lista                 el catalogo entero");
        if (q.hasPermission("ederus.troll.admin")) q.sendMessage("/troll recargar");
    }

    private boolean lista(CommandSender q) {
        /* Se agrupa de verdad por familia: las destructivas se registran al
         * final y con el orden del catalogo salian cabeceras repetidas. */
        for (Troll.Familia familia : Troll.Familia.values()) {
            List<Troll> suyas = new ArrayList<>();
            for (Troll t : modulo.catalogo().values()) if (t.familia() == familia) suyas.add(t);
            if (suyas.isEmpty()) continue;
            q.sendMessage("--- " + familia.nombre());
            for (Troll t : suyas) {
                q.sendMessage("  " + t.id()
                        + (t.destructivo() ? "  [BORRA PROGRESO]" : "")
                        + (t.temporal() ? "  (" + t.segundos() + "s)" : "")
                        + "  - " + t.descripcion());
            }
        }
        return true;
    }

    /**
     * La salida de emergencia. Si algo se queda pegado (o un admin se pasa),
     * esto lo quita sin tener que reiniciar nada.
     */
    private boolean deshacer(CommandSender q, String[] args) {
        if (args.length < 2) {
            q.sendMessage("/troll deshacer <jugador|todos>");
            return true;
        }
        if (args[1].equalsIgnoreCase("todos")) {
            int n = modulo.estados().quitarTodo();
            q.sendMessage("Deshechas " + n + " bromas de todo el servidor.");
            return true;
        }
        Player v = Bukkit.getPlayerExact(args[1]);
        if (v == null) {
            modulo.textos().manda(q, "no-esta-conectado", "&#FF5C5C%rival% no esta conectado.",
                    "%rival%", args[1]);
            return true;
        }
        int n = modulo.estados().quitarTodo(v.getUniqueId());
        modulo.textos().manda(q, "deshechas", "&fLe quitaste &x&D&7&F&3&F&F%cuantas% &fbromas a %jugador%",
                "%cuantas%", String.valueOf(n), "%jugador%", v.getName());
        return true;
    }

    private boolean activas(CommandSender q) {
        int total = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            List<String> suyas = modulo.estados().activas(p.getUniqueId());
            if (suyas.isEmpty()) continue;
            q.sendMessage("  " + p.getName() + ": " + String.join(", ", suyas));
            total++;
        }
        if (total == 0) q.sendMessage("Nadie tiene ninguna broma encima.");
        return true;
    }

    private boolean recargar(CommandSender q) {
        if (!q.hasPermission("ederus.troll.admin")) { q.sendMessage("No puedes."); return true; }
        q.sendMessage(modulo.recargar());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            for (String s : List.of("lista", "deshacer", "activas", "ayuda")) {
                if (s.startsWith(p)) out.add(s);
            }
            if (quien.hasPermission("ederus.troll.admin") && "recargar".startsWith(p)) out.add("recargar");
            for (Player j : Bukkit.getOnlinePlayers()) {
                if (j.getName().toLowerCase(Locale.ROOT).startsWith(p)) out.add(j.getName());
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("deshacer")) {
            out.add("todos");
            for (Player j : Bukkit.getOnlinePlayers()) out.add(j.getName());
            return out;
        }
        if (args.length == 2) {
            String p = args[1].toLowerCase(Locale.ROOT);
            if ("azar".startsWith(p)) out.add("azar");
            for (Troll t : modulo.catalogo().values()) {
                if (t.id().startsWith(p)) out.add(t.id());
            }
        }
        return out;
    }
}
