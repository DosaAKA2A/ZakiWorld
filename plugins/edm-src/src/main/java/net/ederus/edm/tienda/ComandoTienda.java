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
            if (m == null) { quien.sendMessage(Estilo.legado("&cLa tienda todavía está arrancando.")); return true; }
            quien.sendMessage(m.venderTodo(j).mensaje());
            return true;
        }
        boolean esShop = cmd.getName().equalsIgnoreCase("shop");
        if (esShop || args.length == 0) {
            if (quien instanceof Player jugador) {
                MenuTienda menu = modulo.menu();
                if (menu == null) { quien.sendMessage(Estilo.legado("&cLa tienda todavía está arrancando.")); return true; }
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
            case "webhook" -> { return webhook(quien); }
            case "recargar" -> { return recargar(quien); }
            default -> { ayuda(quien); return true; }
        }
    }

    private void ayuda(CommandSender q) {
        q.sendMessage(Estilo.regla());
        q.sendMessage(Estilo.cabecera("TIENDA", "Ederus"));
        q.sendMessage(Estilo.regla());
        q.sendMessage(Estilo.linea("/tienda", "abre la tienda", Estilo.CLARO));
        q.sendMessage(Estilo.linea("/tienda buscar <texto>", "encuentra un artículo", Estilo.CLARO));
        q.sendMessage(Estilo.linea("/tienda vender mano todo", "vende lo que llevas en la mano", Estilo.VENTA));
        q.sendMessage(Estilo.linea("/tienda vender <artículo> <cuántos>", null, Estilo.VENTA));
        q.sendMessage(Estilo.linea("/tienda comprar <artículo> <cuántos>", null, Estilo.COMPRA));
        q.sendMessage(Estilo.linea("/tienda precio <artículo>", "a cómo está", Estilo.CLARO));
        q.sendMessage(Estilo.linea("/tienda mercado <artículo>", "cuánto ha bajado y por qué", Estilo.CLARO));
        q.sendMessage(Estilo.linea("/tienda rotacion", "los tratos de hoy", Estilo.CLARO));
        q.sendMessage(Estilo.linea("/venderotodo", "vende todo lo vendible que lleves", Estilo.VENTA));
        if (q.hasPermission("ederus.tienda.admin")) {
            q.sendMessage(Estilo.regla());
            q.sendMessage(Estilo.linea("/etienda recargar", "recarga el catálogo", Estilo.APAGADO));
            q.sendMessage(Estilo.linea("/etienda webhook", "prueba el aviso de Discord", Estilo.APAGADO));
            q.sendMessage(Estilo.linea("/etienda mercado <art> vender <n>", "la tabla de presión", Estilo.APAGADO));
        }
        q.sendMessage(Estilo.regla());
    }

    private boolean buscar(CommandSender quien, String[] args) {
        if (!(quien instanceof Player jugador)) { quien.sendMessage("Solo desde el juego."); return true; }
        if (args.length < 2) { quien.sendMessage("/etienda buscar <texto>"); return true; }
        MenuTienda menu = modulo.menu();
        if (menu == null) { quien.sendMessage(Estilo.legado("&cLa tienda todavía está arrancando.")); return true; }
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
        if (material == null) { quien.sendMessage("No conozco el objeto '" + args[1] + "'."); return true; }

        int cantidad = Motor.MAX_POR_OPERACION;
        if (args.length >= 3 && !args[2].equalsIgnoreCase("todo")) {
            try { cantidad = Integer.parseInt(args[2]); }
            catch (NumberFormatException e) { quien.sendMessage("Cantidad invalida."); return true; }
        }

        Motor motor = modulo.motor();
        if (motor == null) { quien.sendMessage(Estilo.legado("&cLa tienda todavía está arrancando.")); return true; }
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
        if (art == null) { quien.sendMessage("No conozco el objeto '" + args[1] + "'."); return true; }

        int cantidad;
        try { cantidad = Integer.parseInt(args[2]); }
        catch (NumberFormatException e) { quien.sendMessage("Cantidad invalida."); return true; }

        Motor motor = modulo.motor();
        if (motor == null) { quien.sendMessage(Estilo.legado("&cLa tienda todavía está arrancando.")); return true; }
        quien.sendMessage(motor.comprar(jugador, art, cantidad).mensaje());
        return true;
    }

    /**
     * La ficha corta de un articulo.
     *
     * Enseña el precio EFECTIVO, que es lo que se va a cobrar de verdad, no el
     * de precios.yml. Antes enseñaba el base y le mentia al jugador: veia $55 y
     * al vender cobraba $40 porque el mercado estaba sobrevendido.
     */
    private boolean precio(CommandSender quien, String[] args) {
        if (args.length < 2) { uso(quien, "precio <artículo>"); return true; }

        Catalogo.Articulo a = articuloDe(quien, args[1]);
        if (a == null) { noConozco(quien, args[1]); return true; }

        Motor motor = modulo.motor();
        quien.sendMessage(Estilo.regla());
        quien.sendMessage(Estilo.cabecera("TIENDA", Motor.nombre(a)));
        quien.sendMessage(Estilo.regla());
        quien.sendMessage(Estilo.linea("Categoría", a.categoria(), Estilo.CLARO));

        if (a.seCompra()) {
            double ef = motor != null ? motor.compraEfectiva(a) : a.compra();
            quien.sendMessage(Estilo.linea("Te cuesta", Estilo.dineroCorto(ef), Estilo.COMPRA));
            if (Math.round(ef) != Math.round(a.compra())) {
                quien.sendMessage(Estilo.nota("normal " + Estilo.dineroCorto(a.compra()) + ", en oferta hoy"));
            }
        } else {
            quien.sendMessage(Estilo.linea("Te cuesta", "no se vende aquí", Estilo.APAGADO));
        }

        if (a.seVende()) {
            double ef = motor != null ? motor.ventaEfectiva(a) : a.venta();
            quien.sendMessage(Estilo.linea("Te pagan", Estilo.dineroCorto(ef), Estilo.VENTA));
            int caida = modulo.mercado() != null ? modulo.mercado().caidaPorCiento(a) : 0;
            if (caida > 0) {
                quien.sendMessage(Estilo.nota("normal " + Estilo.dineroCorto(a.venta())
                        + ", -" + caida + "% por sobrevendido"));
            } else if (Math.round(ef) > Math.round(a.venta())) {
                quien.sendMessage(Estilo.nota("normal " + Estilo.dineroCorto(a.venta()) + ", muy pedido hoy"));
            }
        } else {
            quien.sendMessage(Estilo.linea("Te pagan", "no se compra aquí", Estilo.APAGADO));
        }

        if (topes.activo() && a.tieneTope()) {
            String resto = quien instanceof Player pl
                    ? "  te quedan " + topes.restante(pl.getUniqueId(), a) : "";
            quien.sendMessage(Estilo.linea("Límite",
                    a.topeVenta() + " cada " + Motor.duracion(a.ventanaMs()) + resto, Estilo.CLARO));
        }
        quien.sendMessage(Estilo.regla());
        return true;
    }

    /**
     * Las ventanas de venta abiertas.
     *
     * Con los topes apagados NO se dice "no tienes ninguna ventana abierta":
     * eso suena a que el jugador hizo algo mal. Se dice que no hay topes, que
     * es la verdad y ademas es una buena noticia.
     */
    private boolean verTopes(CommandSender quien) {
        if (!(quien instanceof Player jugador)) { quien.sendMessage("Solo desde el juego."); return true; }

        quien.sendMessage(Estilo.regla());
        quien.sendMessage(Estilo.cabecera("TIENDA", "Tus límites de venta"));
        quien.sendMessage(Estilo.regla());
        if (!topes.activo()) {
            quien.sendMessage(Estilo.linea("Sin límite", "vende lo que quieras", Estilo.ACCION_COMPRA));
            quien.sendMessage(Estilo.nota("lo único que frena es el precio: baja cuanto más se vende"));
            quien.sendMessage(Estilo.regla());
            return true;
        }
        var resumen = topes.resumen(jugador.getUniqueId());
        if (resumen.isEmpty()) {
            quien.sendMessage(Estilo.linea("Nada vendido todavía", "los tienes enteros", Estilo.CLARO));
        } else {
            resumen.forEach((clave, v) -> {
                Catalogo.Articulo a = catalogo.de(clave);
                if (a == null) return;
                quien.sendMessage(Estilo.linea(Motor.nombre(a),
                        v[0] + " de " + a.topeVenta(), Estilo.VENTA));
                quien.sendMessage(Estilo.nota("se reinicia en "
                        + Motor.duracion(topes.esperaMs(jugador.getUniqueId(), a))));
            });
        }
        quien.sendMessage(Estilo.regla());
        return true;
    }

    /** Como esta hoy el precio dinamico de un articulo. */
    private boolean mercado(CommandSender quien, String[] args) {
        if (args.length < 2) { uso(quien, "mercado <artículo>"); return true; }
        Catalogo.Articulo a = articuloDe(quien, args[1]);
        if (a == null) { noConozco(quien, args[1]); return true; }
        if (!a.seVende()) {
            quien.sendMessage(Estilo.linea(Motor.nombre(a), "no se compra aquí", Estilo.APAGADO));
            quien.sendMessage(Estilo.nota("solo tienen precio dinámico las cosas que la tienda te compra"));
            return true;
        }

        Mercado mk = modulo.mercado();
        if (mk == null) { quien.sendMessage(Estilo.legado("&cEl mercado todavía está arrancando.")); return true; }
        Motor mt = modulo.motor();
        double compra = mt != null ? mt.compraEfectiva(a) : a.compra();
        double ahora = mk.ventaEfectiva(a, compra);
        int caida = mk.caidaPorCiento(a);

        quien.sendMessage(Estilo.regla());
        quien.sendMessage(Estilo.cabecera("MERCADO", Motor.nombre(a)));
        quien.sendMessage(Estilo.regla());
        quien.sendMessage(Estilo.linea("Se paga ahora", Estilo.dineroCorto(ahora), Estilo.VENTA));
        quien.sendMessage(Estilo.nota(caida > 0
                ? "normal " + Estilo.dineroCorto(a.venta()) + ", va -" + caida + "% de tanto venderse"
                : "es su precio normal, nadie lo está inundando"));
        quien.sendMessage(Estilo.linea("Nunca baja de", Estilo.dineroCorto(a.venta() * mk.suelo()), Estilo.CLARO));
        quien.sendMessage(Estilo.linea("Se recupera",
                mk.olvidoTotalMs() > 0 ? "en " + Motor.duracion(mk.olvidoTotalMs()) + " sin ventas"
                                       : "poco a poco, sin tope", Estilo.CLARO));
        quien.sendMessage(Estilo.regla());

        /* Las dos tablas son herramienta de calibrar la economia, no algo que
         * un jugador vaya a leer: cinco cortes y cuatro escenarios. Ademas van
         * con relleno de espacios, que en el chat no alinea nada. Solo staff. */
        if (!quien.hasPermission("ederus.tienda.admin")) return true;

        if (args.length >= 4 && args[2].equalsIgnoreCase("vender")) {
            int n;
            try { n = Integer.parseInt(args[3]); }
            catch (NumberFormatException ex) { cantidadMala(quien); return true; }
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
            catch (NumberFormatException e) { cantidadMala(quien); return true; }
            quien.sendMessage("  si el servidor vendiera " + n + " mas:");
            for (int paso : new int[]{n / 4, n / 2, n, n * 2, n * 4}) {
                if (paso <= 0) continue;
                quien.sendMessage("    " + String.format("%,d", paso) + " -> "
                        + Motor.fmt(mk.simular(a, paso)));
            }
        }
        return true;
    }

    /**
     * Que le toco hoy a Ofertas y a Demandas.
     *
     * En el juego solo el RESUMEN. La lista entera son veinte articulos y el
     * chat no es sitio para eso: las lineas se parten por la mitad y en un
     * telefono con Bedrock no hay quien lo lea. Las dos secciones ya son
     * pantallas del menu (@ofertas y @demandas), asi que ahi se manda.
     *
     * En consola SI se imprime la lista: ahi la fuente es monoespaciada, no hay
     * menu al que mandar a nadie, y es donde se revisa que la rotacion del dia
     * salio bien.
     */
    private boolean rotacion(CommandSender quien) {
        Rotacion rot = modulo.rotacion();
        if (rot == null || !rot.activo()) {
            quien.sendMessage(Estilo.linea("La rotación está desactivada", null, Estilo.APAGADO));
            return true;
        }

        if (!(quien instanceof Player)) {
            quien.sendMessage("Rotacion del " + rot.dia()
                    + " (cambia en " + Motor.duracion(Rotacion.hastaManana()) + ")");
            quien.sendMessage("OFERTAS (baja la compra):");
            for (Rotacion.Trato t : rot.ofertas()) {
                Catalogo.Articulo a = catalogo.de(t.clave());
                if (a == null) continue;
                quien.sendMessage("  " + Motor.nombre(a) + ": " + Motor.fmt(a.compra())
                        + " -> " + Motor.fmt(a.compra() * t.factor())
                        + "  (-" + Math.round((1 - t.factor()) * 100) + "%)  quedan " + quedan(rot, t));
            }
            quien.sendMessage("DEMANDAS (sube la venta):");
            Motor m = modulo.motor();
            for (Rotacion.Trato t : rot.demandas()) {
                Catalogo.Articulo a = catalogo.de(t.clave());
                if (a == null) continue;
                String ahora = m != null ? Motor.fmt(m.ventaEfectiva(a)) : Motor.fmt(a.venta() * t.factor());
                quien.sendMessage("  " + Motor.nombre(a) + ": " + Motor.fmt(a.venta())
                        + " -> " + ahora + "  (+" + Math.round((t.factor() - 1) * 100) + "%)  quedan " + quedan(rot, t));
            }
            return true;
        }

        int ofertas = 0, demandas = 0;
        for (Rotacion.Trato t : rot.ofertas()) if (catalogo.de(t.clave()) != null) ofertas++;
        for (Rotacion.Trato t : rot.demandas()) if (catalogo.de(t.clave()) != null) demandas++;

        quien.sendMessage(Estilo.regla());
        quien.sendMessage(Estilo.cabecera("MERCADO", "Los tratos de hoy"));
        quien.sendMessage(Estilo.regla());
        quien.sendMessage(Estilo.linea("Ofertas", ofertas + (ofertas == 1 ? " artículo" : " artículos"),
                Estilo.ACCION_COMPRA));
        quien.sendMessage(Estilo.nota("te cuestan menos de lo normal"));
        quien.sendMessage(Estilo.linea("Demandas", demandas + (demandas == 1 ? " artículo" : " artículos"),
                Estilo.VENTA));
        quien.sendMessage(Estilo.nota("te los pagan mejor de lo normal"));
        quien.sendMessage(Estilo.linea("Cambian", "en " + Motor.duracion(Rotacion.hastaManana()), Estilo.CLARO));
        quien.sendMessage(Estilo.regla());
        quien.sendMessage(Estilo.linea("Ábrelos en /tienda", null, Estilo.MARCA));
        quien.sendMessage(Estilo.regla());
        return true;
    }

    // -------------------------------------------------- pequenas ayudas

    /** El articulo por su clave o por su nombre de material. */
    private Catalogo.Articulo articuloDe(CommandSender quien, String texto) {
        Catalogo.Articulo a = catalogo.de(texto);
        if (a != null) return a;
        Material m = quien instanceof Player p ? material(p, texto) : Material.matchMaterial(texto);
        return m != null ? catalogo.de(m) : null;
    }

    private void uso(CommandSender quien, String resto) {
        quien.sendMessage(Estilo.linea("Se usa así", "/tienda " + resto, Estilo.CLARO));
    }

    private void noConozco(CommandSender quien, String texto) {
        quien.sendMessage(Estilo.linea("No tengo nada llamado", texto, Estilo.APAGADO));
        quien.sendMessage(Estilo.nota("prueba a buscarlo: /tienda buscar " + texto));
    }

    private void cantidadMala(CommandSender quien) {
        quien.sendMessage(Estilo.linea("Eso no es una cantidad", null, Estilo.APAGADO));
    }

    /** Sin tope no se imprime el numero: 2.147.483.647 no se lo cree nadie. */
    private static String quedan(Rotacion rot, Rotacion.Trato t) {
        return Rotacion.sinTope(t) ? "sin tope" : String.valueOf(rot.restanteHoy(t));
    }

    /**
     * Lanza el aviso de Discord al momento.
     *
     * Existe por lo mismo que el /main aviso del core: el de verdad solo sale
     * al rotar el dia, y esperar a medianoche para descubrir que la URL estaba
     * mal copiada no es forma de probar nada.
     */
    private boolean webhook(CommandSender quien) {
        if (!quien.hasPermission("ederus.tienda.admin")) { quien.sendMessage("No puedes."); return true; }
        Mensajes m = modulo.mensajes();
        if (m == null || !m.hayWebhook()) {
            quien.sendMessage("No hay webhook configurado.");
            quien.sendMessage("Pon la URL en 'rotacion.webhook' de mensajes.yml y usa /etienda recargar.");
            return true;
        }
        m.aDiscord(modulo.rotacion(), catalogo);
        quien.sendMessage("Aviso mandado a Discord. Si no aparece, mira la consola:");
        quien.sendMessage("va por su cuenta, asi que un fallo sale ahi y no aqui.");
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
            for (String s : List.of("buscar", "vender", "comprar", "precio", "topes", "mercado", "rotacion")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            /* Los de admin solo se sugieren a quien puede usarlos. */
            if (quien.hasPermission("ederus.tienda.admin")) {
                for (String s : List.of("webhook", "recargar")) {
                    if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
                }
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
