package net.ederus.edm.tienda;

import net.ederus.edm.comun.Estilo;

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

    /** Declarado en el plugin.yml desde siempre, pero no se miraba en ningun
     *  sitio: quitarselo a alguien no le cerraba la tienda. */
    private static final String USAR = "ederus.tienda.usar";

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        /* Los subcomandos de admin traen su propia comprobacion; lo que se le
         * cierra a un jugador sin permiso es comprar y vender. */
        if (quien instanceof Player && !quien.hasPermission(USAR)
                && !quien.hasPermission("ederus.tienda.admin")) {
            quien.sendMessage(Estilo.legado("&cNo puedes usar la tienda."));
            return true;
        }
        /* /shop es solo la puerta del menu: no lleva subcomandos. */
        if (cmd.getName().equalsIgnoreCase("sellall")) {
            if (!(quien instanceof Player j)) { quien.sendMessage("Solo desde el juego."); return true; }
            Motor m = modulo.motor();
            if (m == null) { quien.sendMessage(Estilo.legado("&cLa tienda todavia esta arrancando.")); return true; }
            quien.sendMessage(m.venderTodo(j).mensaje());
            return true;
        }
        boolean esShop = cmd.getName().equalsIgnoreCase("shop");
        if (esShop || args.length == 0) {
            if (quien instanceof Player jugador) {
                MenuTienda menu = modulo.menu();
                if (menu == null) { quien.sendMessage(Estilo.legado("&cLa tienda todavia esta arrancando.")); return true; }
                /* /shop diamante busca directamente: es mas rapido que abrir el
                 * menu y ponerse a pasar paginas. */
                if (esShop && args.length > 0) menu.abrirBusqueda(jugador, String.join(" ", args));
                else menu.abrirPrincipal(jugador);
            } else {
                ayuda(quien);
            }
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "buscar" -> { return buscar(quien, args); }
            case "vender" -> { return vender(quien, args); }
            case "comprar" -> { return comprar(quien, args); }
            case "precio" -> { return precio(quien, args); }
            case "topes" -> { return verTopes(quien); }
            case "mercado" -> { return mercado(quien, args); }
            case "rotacion" -> { return rotacion(quien); }
            case "recargar" -> { return recargar(quien); }
            default -> { ayuda(quien); return true; }
        }
    }

    private void ayuda(CommandSender q) {
        q.sendMessage("Tienda de Ederus");
        q.sendMessage("/etienda buscar <texto>          (o /shop <texto>)");
        q.sendMessage("/etienda vender <item|mano> [cantidad|todo]");
        q.sendMessage("/etienda comprar <item> <cantidad>   (ej: spawner:pig)");
        q.sendMessage("/etienda precio <item>");
        q.sendMessage("/etienda topes");
        q.sendMessage("/etienda mercado <item> [simular <cantidad>]");
        q.sendMessage("/etienda rotacion");
        if (q.hasPermission("ederus.tienda.admin")) q.sendMessage("/etienda recargar");
    }

    private boolean buscar(CommandSender quien, String[] args) {
        if (!(quien instanceof Player jugador)) { quien.sendMessage("Solo desde el juego."); return true; }
        if (args.length < 2) { quien.sendMessage("/etienda buscar <texto>"); return true; }
        MenuTienda menu = modulo.menu();
        if (menu == null) { quien.sendMessage(Estilo.legado("&cLa tienda todavia esta arrancando.")); return true; }
        menu.abrirBusqueda(jugador, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
        return true;
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
        if (motor == null) { quien.sendMessage(Estilo.legado("&cLa tienda todavia esta arrancando.")); return true; }
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
        if (motor == null) { quien.sendMessage(Estilo.legado("&cLa tienda todavia esta arrancando.")); return true; }
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
        if (topes.activo() && a.tieneTope()) {
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

    /** Ver como esta el precio de un item y simular cuanto caeria. */
    private boolean mercado(CommandSender quien, String[] args) {
        if (args.length < 2) { quien.sendMessage("/etienda mercado <item> [simular <cantidad>]"); return true; }
        Catalogo.Articulo a = catalogo.de(args[1]);
        if (a == null) {
            Material m = quien instanceof Player p ? material(p, args[1]) : Material.matchMaterial(args[1]);
            if (m != null) a = catalogo.de(m);
        }
        if (a == null) { quien.sendMessage("'" + args[1] + "' no esta en la tienda."); return true; }
        if (!a.seVende()) { quien.sendMessage(Motor.nombre(a) + " no se vende, no tiene precio dinamico."); return true; }

        Mercado mk = modulo.mercado();
        if (mk == null) { quien.sendMessage("El mercado aun no esta listo."); return true; }

        quien.sendMessage(Motor.nombre(a) + "  [" + a.categoria() + "]");
        quien.sendMessage("  base " + Motor.fmt(a.venta())
                + "  ->  ahora " + Motor.fmt(mk.ventaEfectiva(a, a.compra()))
                + "  (-" + mk.caidaPorCiento(a) + "%)");
        quien.sendMessage("  suelo " + Motor.fmt(a.venta() * mk.suelo())
                + "  |  margen contra la compra " + Math.round(mk.margen() * 100) + "%");

        if (args.length >= 4 && args[2].equalsIgnoreCase("vender")) {
            int n;
            try { n = Integer.parseInt(args[3]); }
            catch (NumberFormatException ex) { quien.sendMessage("Cantidad invalida."); return true; }
            Motor mt = modulo.motor();
            double compra = mt != null ? mt.compraEfectiva(a) : a.compra();
            quien.sendMessage("  vender " + String.format("%,d", n) + " ahora mismo:");
            int[] cortes = {1, n / 8, n / 4, n / 2, n};
            for (int c : cortes) {
                if (c <= 0) continue;
                double total = mk.totalVenta(a, c, compra);
                quien.sendMessage("    " + String.format("%,7d", c) + " -> " + Motor.fmt(total)
                        + "   (" + Motor.fmt(total / c) + " por unidad)");
            }
            quien.sendMessage("  y si el servidor YA hubiera vendido antes:");
            /* En long y acotado: con un n grande, n * 100 se sale del entero y
             * la tabla pasaba a enseñar presiones negativas. */
            for (long antes : new long[]{n, n * 5L, n * 25L, n * 100L}) {
                double conPrevia = mk.totalVentaDesde(a, n, compra, antes);
                quien.sendMessage("    tras " + String.format("%,d", antes) + " -> " + Motor.fmt(conPrevia)
                        + "   (" + Motor.fmt(conPrevia / n) + " por unidad)");
            }
            return true;
        }

        if (args.length >= 4 && args[2].equalsIgnoreCase("simular")) {
            int n;
            try { n = Integer.parseInt(args[3]); }
            catch (NumberFormatException e) { quien.sendMessage("Cantidad invalida."); return true; }
            quien.sendMessage("  si el servidor vendiera " + n + " mas:");
            for (int paso : new int[]{n / 4, n / 2, n, n * 2, n * 4}) {
                if (paso <= 0) continue;
                quien.sendMessage("    " + String.format("%,d", paso) + " -> "
                        + Motor.fmt(mk.simular(a, paso)));
            }
        }
        return true;
    }

    /** Que le toco hoy a Ofertas y a Demandas. */
    private boolean rotacion(CommandSender quien) {
        Rotacion rot = modulo.rotacion();
        if (rot == null || !rot.activo()) { quien.sendMessage("La rotacion esta desactivada."); return true; }
        quien.sendMessage("Rotacion del " + rot.dia() + " (cambia en " + Motor.duracion(Rotacion.hastaManana()) + ")");

        quien.sendMessage("OFERTAS (baja la compra):");
        int n = 0;
        for (Rotacion.Trato t : rot.ofertas()) {
            Catalogo.Articulo a = catalogo.de(t.clave());
            if (a == null) continue;
            quien.sendMessage("  " + Motor.nombre(a) + ": " + Motor.fmt(a.compra())
                    + " -> " + Motor.fmt(a.compra() * t.factor())
                    + "  (-" + Math.round((1 - t.factor()) * 100) + "%)  quedan " + quedan(rot, t));
            n++;
        }
        if (n == 0) quien.sendMessage("  (ninguna)");

        quien.sendMessage("DEMANDAS (sube la venta):");
        n = 0;
        for (Rotacion.Trato t : rot.demandas()) {
            Catalogo.Articulo a = catalogo.de(t.clave());
            if (a == null) continue;
            Motor m = modulo.motor();
            String ahora = m != null ? Motor.fmt(m.ventaEfectiva(a)) : Motor.fmt(a.venta() * t.factor());
            quien.sendMessage("  " + Motor.nombre(a) + ": " + Motor.fmt(a.venta())
                    + " -> " + ahora + "  (+" + Math.round((t.factor() - 1) * 100) + "%)  quedan " + quedan(rot, t));
            n++;
        }
        if (n == 0) quien.sendMessage("  (ninguna)");
        return true;
    }

    /** Sin tope no se imprime el numero: 2.147.483.647 no se lo cree nadie. */
    private static String quedan(Rotacion rot, Rotacion.Trato t) {
        return Rotacion.sinTope(t) ? "sin tope" : String.valueOf(rot.restanteHoy(t));
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
            for (String s : List.of("buscar", "vender", "comprar", "precio", "topes", "mercado", "rotacion", "recargar")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("topes")
                && !args[0].equalsIgnoreCase("buscar")) {
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
