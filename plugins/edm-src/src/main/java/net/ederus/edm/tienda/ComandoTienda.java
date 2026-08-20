package net.ederus.edm.tienda;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /etienda — la cara del modulo mientras no hay interfaz grafica. */
public final class ComandoTienda implements CommandExecutor, TabCompleter {

    private final TiendaPlugin modulo;
    private final Catalogo catalogo;
    private final Topes topes;

    public ComandoTienda(TiendaPlugin modulo, Catalogo catalogo, Topes topes) {
        this.modulo = modulo;
        this.catalogo = catalogo;
        this.topes = topes;
    }

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        if (args.length == 0) { ayuda(quien); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "vender" -> { return vender(quien, args); }
            case "comprar" -> { return comprar(quien, args); }
            case "precio" -> { return precio(quien, args); }
            case "topes" -> { return verTopes(quien); }
            case "recargar" -> { return recargar(quien); }
            default -> { ayuda(quien); return true; }
        }
    }

    private void ayuda(CommandSender q) {
        q.sendMessage("Tienda de Ederus");
        q.sendMessage("/etienda vender <item|mano> [cantidad|todo]");
        q.sendMessage("/etienda comprar <item> <cantidad>   (ej: spawner:pig)");
        q.sendMessage("/etienda precio <item>");
        q.sendMessage("/etienda topes");
        if (q.hasPermission("ederus.tienda.admin")) q.sendMessage("/etienda recargar");
    }

    /** 'mano' evita escribir el nombre del material, que es lo que mas se falla. */
    private Material material(Player jugador, String texto) {
        if (texto.equalsIgnoreCase("mano")) {
            ItemStack enMano = jugador.getInventory().getItemInMainHand();
            return enMano.getType().isAir() ? null : enMano.getType();
        }
        return Material.matchMaterial(texto);
    }

    private boolean vender(CommandSender quien, String[] args) {
        if (!(quien instanceof Player jugador)) { quien.sendMessage("Solo desde el juego."); return true; }
        if (args.length < 2) { quien.sendMessage("/etienda vender <item|mano> [cantidad|todo]"); return true; }

        Material material = material(jugador, args[1]);
        if (material == null) { quien.sendMessage("No conozco el item '" + args[1] + "'."); return true; }

        int cantidad = Motor.MAX_POR_OPERACION;
        if (args.length >= 3 && !args[2].equalsIgnoreCase("todo")) {
            try { cantidad = Integer.parseInt(args[2]); }
            catch (NumberFormatException e) { quien.sendMessage("Cantidad invalida."); return true; }
        }

        Motor motor = modulo.motor();
        if (motor == null) { quien.sendMessage("La tienda aun no esta lista."); return true; }
        quien.sendMessage(motor.vender(jugador, material, cantidad).mensaje());
        return true;
    }

    private boolean comprar(CommandSender quien, String[] args) {
        if (!(quien instanceof Player jugador)) { quien.sendMessage("Solo desde el juego."); return true; }
        if (args.length < 3) { quien.sendMessage("/etienda comprar <item> <cantidad>"); return true; }

        /* Aqui se busca por CLAVE, no por material: es lo que permite pedir
         * spawner:pig y distinguirlo de spawner:zombie. */
        Catalogo.Articulo art = catalogo.de(args[1]);
        if (art == null && quien instanceof Player p) {
            Material m = material(p, args[1]);
            if (m != null) art = catalogo.de(m);
        }
        if (art == null) { quien.sendMessage("No conozco el item '" + args[1] + "'."); return true; }

        int cantidad;
        try { cantidad = Integer.parseInt(args[2]); }
        catch (NumberFormatException e) { quien.sendMessage("Cantidad invalida."); return true; }

        Motor motor = modulo.motor();
        if (motor == null) { quien.sendMessage("La tienda aun no esta lista."); return true; }
        quien.sendMessage(motor.comprar(jugador, art, cantidad).mensaje());
        return true;
    }

    private boolean precio(CommandSender quien, String[] args) {
        if (args.length < 2) { quien.sendMessage("/etienda precio <item>"); return true; }

        Catalogo.Articulo a = catalogo.de(args[1]);
        if (a == null) {
            Material m = quien instanceof Player p ? material(p, args[1]) : Material.matchMaterial(args[1]);
            if (m != null) a = catalogo.de(m);
        }
        if (a == null) { quien.sendMessage("'" + args[1] + "' no esta en la tienda."); return true; }

        quien.sendMessage(Motor.nombre(a) + "  [" + a.categoria() + "]");
        quien.sendMessage("  compra: " + (a.seCompra() ? Motor.fmt(a.compra()) : "no se vende"));
        quien.sendMessage("  venta:  " + (a.seVende() ? Motor.fmt(a.venta()) : "no se compra"));
        if (a.tieneTope()) {
            String resto = quien instanceof Player p ? " | te quedan " + topes.restante(p.getUniqueId(), a) : "";
            quien.sendMessage("  tope:   " + a.topeVenta() + " cada " + Motor.duracion(a.ventanaMs()) + resto);
        }
        return true;
    }

    private boolean verTopes(CommandSender quien) {
        if (!(quien instanceof Player jugador)) { quien.sendMessage("Solo desde el juego."); return true; }
        var resumen = topes.resumen(jugador.getUniqueId());
        if (resumen.isEmpty()) { quien.sendMessage("No tienes ninguna ventana de venta abierta."); return true; }
        quien.sendMessage("Ventanas abiertas:");
        resumen.forEach((clave, v) -> {
            Catalogo.Articulo a = catalogo.de(clave);
            if (a == null) return;
            quien.sendMessage("  " + Motor.nombre(a) + ": " + v[0] + "/" + a.topeVenta()
                    + " | se reinicia en " + Motor.duracion(topes.esperaMs(jugador.getUniqueId(), a)));
        });
        return true;
    }

    private boolean recargar(CommandSender quien) {
        if (!quien.hasPermission("ederus.tienda.admin")) { quien.sendMessage("No puedes."); return true; }
        if (modulo.cargarCatalogo()) {
            quien.sendMessage("Catalogo recargado: " + catalogo.total() + " articulos.");
        } else {
            quien.sendMessage("El catalogo tiene errores; se mantiene el anterior. Mira la consola.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("vender", "comprar", "precio", "topes", "recargar")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("topes")) {
            String p = args[1].toUpperCase(Locale.ROOT);
            if ("MANO".startsWith(p)) out.add("mano");
            for (String clave : catalogo.claves()) {
                if (clave.startsWith(p)) out.add(clave.toLowerCase(Locale.ROOT));
                if (out.size() > 40) break;
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("vender")) {
            out.add("todo");
        }
        return out;
    }
}
