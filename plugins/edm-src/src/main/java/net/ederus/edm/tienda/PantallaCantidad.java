package net.ederus.edm.tienda;

import net.ederus.edm.comun.EntradaChat;
import net.ederus.edm.comun.Estilo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * La pantalla de cantidad: "COMPRANDO → Azure Bluet".
 *
 * Es lo unico que le faltaba a la tienda para no quedarse por detras de
 * EconomyShopGUI: elegir CUANTO, ver el total antes de pagar y confirmar. El
 * click de 1 y el shift-click de 64 siguen existiendo; esto es la tercera via,
 * la de la cantidad exacta.
 *
 * NO calcula nada por su cuenta. El precio, el total y el maximo se los pide al
 * Motor, que es el que manda: si la pantalla hiciera sus propias cuentas, el dia
 * que cambie el mercado enseñaria un numero y cobraria otro.
 */
public final class PantallaCantidad implements Listener {

    private static final int TAM = 45;                          // 5 filas

    private static final int RANURA_ITEM = 13;
    private static final int[] RANURAS_MENOS = {19, 20, 21};    // -grande .. -pequeño
    private static final int RANURA_ESCRIBIR = 22;
    private static final int[] RANURAS_MAS = {23, 24, 25};      // +pequeño .. +grande
    private static final int RANURA_MINIMO = 29;
    private static final int RANURA_CONFIRMAR = 31;
    private static final int RANURA_MAXIMO = 33;
    private static final int RANURA_SALDO = 38;
    private static final int RANURA_VOLVER = 40;
    private static final int RANURA_CAMBIAR = 42;

    /** De donde salio el jugador, para devolverlo al mismo sitio. */
    static final class Vista implements InventoryHolder {
        final Catalogo.Articulo art;
        final boolean comprando;
        final String categoria;
        final int pagina;
        int cantidad;
        Inventory inv;

        Vista(Catalogo.Articulo art, boolean comprando, String categoria, int pagina, int cantidad) {
            this.art = art;
            this.comprando = comprando;
            this.categoria = categoria;
            this.pagina = pagina;
            this.cantidad = cantidad;
        }

        @Override public Inventory getInventory() { return inv; }
    }

    private final TiendaPlugin modulo;
    private final Secciones secciones;
    private final EntradaChat chat;

    /* Configurables: los saltos de los botones y lo que multiplica el shift. */
    private int[] pasos = {1, 8, 32};
    private int multiplicadorShift = 8;
    private boolean escribirEnChat = true;

    public PantallaCantidad(TiendaPlugin modulo, Secciones secciones, EntradaChat chat) {
        this.modulo = modulo;
        this.secciones = secciones;
        this.chat = chat;
    }

    public void configurar(org.bukkit.configuration.ConfigurationSection sec) {
        if (sec == null) return;
        List<Integer> lista = sec.getIntegerList("pasos");
        /* Solo tres botones por lado: mas no caben sin comerse el borde. */
        if (lista.size() >= 3) {
            pasos = new int[]{Math.max(1, lista.get(0)), Math.max(1, lista.get(1)), Math.max(1, lista.get(2))};
        }
        multiplicadorShift = Math.max(1, sec.getInt("multiplicador-shift", 8));
        escribirEnChat = sec.getBoolean("escribir-en-chat", true);
    }

    // ------------------------------------------------------------------ abrir

    /**
     * Abre la pantalla. Si ahora mismo no se puede ni una unidad NO la abre:
     * lanza la operacion normal para que el Motor diga el motivo de verdad
     * (te faltan $400, no te cabe nada mas), que es mas util que una ventana
     * con un cero dentro.
     */
    public void abrir(Player jugador, Catalogo.Articulo art, boolean comprando,
                      String categoria, int pagina) {
        Motor motor = modulo.motor();
        if (motor == null) {
            jugador.sendMessage(Estilo.legado("&cLa tienda todavia esta arrancando."));
            return;
        }
        Motor.Limite limite = comprando ? motor.maximoCompra(jugador, art) : motor.maximoVenta(jugador, art);
        if (limite.cantidad() <= 0) {
            Motor.Resultado r = comprando ? motor.comprar(jugador, art, 1)
                                          : motor.vender(jugador, art.material(), 1);
            jugador.sendMessage(r.mensaje());
            secciones.sonar(jugador, "error");
            return;
        }
        pintar(jugador, new Vista(art, comprando, categoria, pagina, 1), true);
    }

    /** Reconstruye la ventana con la cantidad que tenga la vista. */
    private void pintar(Player jugador, Vista vista, boolean nueva) {
        Motor motor = modulo.motor();
        if (motor == null) return;
        Motor.Limite limite = vista.comprando
                ? motor.maximoCompra(jugador, vista.art)
                : motor.maximoVenta(jugador, vista.art);
        int max = Math.max(1, limite.cantidad());
        vista.cantidad = Math.max(1, Math.min(vista.cantidad, max));

        Inventory inv = nueva ? Bukkit.createInventory(vista, TAM, titulo(vista)) : vista.inv;
        if (inv == null) return;
        vista.inv = inv;
        inv.clear();

        inv.setItem(RANURA_ITEM, ficha(jugador, vista, motor, limite));

        for (int i = 0; i < RANURAS_MENOS.length; i++) {
            int menos = pasos[pasos.length - 1 - i];
            inv.setItem(RANURAS_MENOS[i], pieza(Material.RED_STAINED_GLASS_PANE, menos,
                    secciones.texto("cantidad-menos", "&#FF5C5C- %paso%", "%paso%", String.valueOf(menos)),
                    pistaShift(menos)));
            int mas = pasos[i];
            inv.setItem(RANURAS_MAS[i], pieza(Material.LIME_STAINED_GLASS_PANE, mas,
                    secciones.texto("cantidad-mas", "&#4FFF55+ %paso%", "%paso%", String.valueOf(mas)),
                    pistaShift(mas)));
        }

        if (escribirEnChat) {
            inv.setItem(RANURA_ESCRIBIR, pieza(Material.NAME_TAG, 1,
                    secciones.texto("cantidad-escribir", "&#91F4FFEscribir la cantidad"),
                    List.of(secciones.texto("cantidad-escribir-lore", "&8▸ &7Te la pide por el chat"))));
        }

        inv.setItem(RANURA_MINIMO, pieza(Material.PAPER, 1,
                secciones.texto("cantidad-minimo", "&x&D&7&F&3&F&FPoner 1"), List.of()));

        List<Component> loreMax = new ArrayList<>();
        loreMax.add(secciones.texto("cantidad-maximo-valor", "&8▸ &f%maximo%",
                "%maximo%", numero(limite.cantidad())));
        Component porque = motivo(limite.motivo());
        if (porque != null) loreMax.add(porque);
        inv.setItem(RANURA_MAXIMO, pieza(vista.comprando ? Material.GOLD_INGOT : Material.CHEST, 1,
                vista.comprando
                        ? secciones.texto("cantidad-maximo-compra", "&#4FFF55Todo lo que puedas pagar")
                        : secciones.texto("cantidad-maximo-venta", "&#FDFF66Todo lo que llevas"),
                loreMax));

        inv.setItem(RANURA_CONFIRMAR, botonConfirmar(vista, motor));
        inv.setItem(RANURA_SALDO, saldo(jugador, motor));
        inv.setItem(RANURA_VOLVER, pieza(Material.BARRIER, 1,
                secciones.texto("cantidad-volver", "&x&D&7&F&3&F&FVolver"), List.of()));

        /* Solo para los articulos que se compran Y se venden: pasar de una cara
         * a la otra sin salir y volver a entrar. */
        if (vista.art.seCompra() && vista.art.seVende()) {
            inv.setItem(RANURA_CAMBIAR, pieza(Material.COMPARATOR, 1,
                    vista.comprando
                            ? secciones.texto("cantidad-cambiar-a-venta", "&#FDFF66Cambiar a vender")
                            : secciones.texto("cantidad-cambiar-a-compra", "&#91F4FFCambiar a comprar"),
                    List.of()));
        }

        rellenar(inv);
        if (nueva) {
            jugador.openInventory(inv);
            secciones.sonar(jugador, "abrir-categoria");
        }
    }

    private Component titulo(Vista vista) {
        return secciones.texto(vista.comprando ? "titulo-comprando" : "titulo-vendiendo",
                vista.comprando
                        ? "&x&0&0&8&3&F&D&lCOMPRANDO &8→ &x&D&7&F&3&F&F%item%"
                        : "&x&0&0&8&3&F&D&lVENDIENDO &8→ &x&D&7&F&3&F&F%item%",
                "%item%", Motor.nombre(vista.art));
    }

    /** El item con la cantidad puesta y todas las cuentas hechas. */
    private ItemStack ficha(Player jugador, Vista vista, Motor motor, Motor.Limite limite) {
        Catalogo.Articulo art = vista.art;
        int n = vista.cantidad;
        List<Component> lore = new ArrayList<>();

        if (vista.comprando) {
            double total = motor.totalCompraDe(art, n);
            lore.add(Estilo.etiqueta("Precio de compra", Estilo.COMPRA));
            lore.add(Estilo.valor(Estilo.dinero(motor.compraEfectiva(art)) + " por unidad"));
            lore.add(Estilo.vacio());
            lore.add(Estilo.etiqueta("Total", Estilo.COMPRA));
            lore.add(Estilo.valor(Estilo.dinero(total)));
            lore.add(Estilo.vacio());
            double tiene = motor.saldo(jugador);
            lore.add(secciones.texto("cantidad-tienes", "&8▸ &7Tienes &f%saldo%",
                    "%saldo%", Estilo.dinero(tiene)));
            lore.add(tiene >= total
                    ? secciones.texto("cantidad-te-queda", "&8▸ &7Te quedaria &f%saldo%",
                            "%saldo%", Estilo.dinero(tiene - total))
                    : secciones.texto("cantidad-te-falta", "&#FF5C5C▸ Te faltan %falta%",
                            "%falta%", Estilo.dinero(total - tiene)));
        } else {
            double total = motor.totalVentaDe(art, n);
            lore.add(Estilo.etiqueta("Precio de venta", Estilo.VENTA));
            /* La MEDIA, no el precio de la primera unidad: vender 2.000 cañas
             * hunde el precio por el camino y eso se ve aqui, antes de vender. */
            lore.add(Estilo.valor(Estilo.dinero(total / n) + " por unidad"));
            int caida = modulo.mercado() != null ? modulo.mercado().caidaPorCiento(art) : 0;
            if (caida > 0) lore.add(Estilo.texto("   -" + caida + "%, sobrevendido", Estilo.APAGADO));
            lore.add(Estilo.vacio());
            lore.add(Estilo.etiqueta("Total", Estilo.VENTA));
            lore.add(Estilo.valor(Estilo.dinero(total)));
            lore.add(Estilo.vacio());
            int llevas = Motor.contarLimpios(jugador.getInventory(), art.material());
            lore.add(secciones.texto("cantidad-llevas", "&8▸ &7Llevas &f%llevas%",
                    "%llevas%", numero(llevas)));
            lore.add(secciones.texto("cantidad-te-quedarian", "&8▸ &7Te quedarian &f%llevas%",
                    "%llevas%", numero(Math.max(0, llevas - n))));
        }

        lore.add(Estilo.vacio());
        lore.add(secciones.texto("cantidad-maximo-linea", "&8▸ &7Maximo ahora mismo&8: &f%maximo%",
                "%maximo%", numero(limite.cantidad())));

        ItemStack icono = Motor.construir(art, 1);
        if (icono == null) icono = new ItemStack(art.material());
        /* El numero que se ve en el icono no puede pasar del stack: por encima
         * el cliente pinta lo que le da la gana. El de verdad va en el nombre. */
        icono.setAmount(Math.max(1, Math.min(n, art.material().getMaxStackSize())));
        return decorar(icono, secciones.texto("cantidad-nombre", "&x&D&7&F&3&F&F%item% &8x &f%cantidad%",
                "%item%", nombreDe(art), "%cantidad%", numero(n)), lore);
    }

    private ItemStack botonConfirmar(Vista vista, Motor motor) {
        double total = vista.comprando
                ? motor.totalCompraDe(vista.art, vista.cantidad)
                : motor.totalVentaDe(vista.art, vista.cantidad);
        return pieza(vista.comprando ? Material.LIME_CONCRETE : Material.YELLOW_CONCRETE, 1,
                vista.comprando
                        ? secciones.texto("cantidad-confirmar-compra", "&#4FFF55Comprar %cantidad%",
                                "%cantidad%", numero(vista.cantidad))
                        : secciones.texto("cantidad-confirmar-venta", "&#FDFF66Vender %cantidad%",
                                "%cantidad%", numero(vista.cantidad)),
                List.of(secciones.texto("cantidad-confirmar-total", "&8▸ &f%total%",
                        "%total%", Estilo.dinero(total))));
    }

    /** Por que el maximo es ese y no mas: sin esto parece un fallo. */
    private Component motivo(String cual) {
        return switch (cual) {
            case "dinero" -> secciones.texto("limite-dinero", "&8▸ &7Es lo que te da el dinero");
            case "espacio" -> secciones.texto("limite-espacio", "&8▸ &7Es lo que te cabe encima");
            case "stock" -> secciones.texto("limite-stock", "&8▸ &7Es lo que llevas");
            case "tope" -> secciones.texto("limite-tope", "&8▸ &7Es tu tope de ventas");
            case "oferta" -> secciones.texto("limite-oferta", "&8▸ &7Es lo que queda hoy de la oferta");
            case "demanda" -> secciones.texto("limite-demanda", "&8▸ &7Es lo que queda hoy de la demanda");
            case "personal" -> secciones.texto("limite-personal", "&8▸ &7Es tu limite personal");
            case "operacion" -> secciones.texto("limite-operacion", "&8▸ &7Es el maximo por operacion");
            default -> null;
        };
    }

    private List<Component> pistaShift(int paso) {
        if (multiplicadorShift <= 1) return List.of();
        return List.of(secciones.texto("cantidad-shift", "&8▸ &7Shift para %total%",
                "%total%", numero(paso * multiplicadorShift)));
    }

    // ------------------------------------------------------------------ clics

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Vista) e.setCancelled(true);
    }

    @EventHandler
    public void alPulsar(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Vista vista)) return;
        /* Lo primero y sin condiciones: de aqui no sale ni entra un item. */
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player jugador)) return;
        if (e.getClickedInventory() != e.getInventory()) return;

        int ranura = e.getSlot();
        ClickType clic = e.getClick();
        int multi = clic.isShiftClick() ? multiplicadorShift : 1;

        for (int i = 0; i < RANURAS_MENOS.length; i++) {
            if (ranura == RANURAS_MENOS[i]) { cambiar(jugador, vista, -pasos[pasos.length - 1 - i] * multi); return; }
            if (ranura == RANURAS_MAS[i]) { cambiar(jugador, vista, pasos[i] * multi); return; }
        }

        switch (ranura) {
            case RANURA_MINIMO -> cambiar(jugador, vista, -Motor.MAX_POR_OPERACION);
            case RANURA_MAXIMO -> cambiar(jugador, vista, Motor.MAX_POR_OPERACION);
            case RANURA_ESCRIBIR -> { if (escribirEnChat) preguntar(jugador, vista); }
            case RANURA_CONFIRMAR -> confirmar(jugador, vista);
            case RANURA_CAMBIAR -> {
                if (!vista.art.seCompra() || !vista.art.seVende()) return;
                secciones.sonar(jugador, "cambiar-pagina");
                abrir(jugador, vista.art, !vista.comprando, vista.categoria, vista.pagina);
            }
            case RANURA_VOLVER -> { secciones.sonar(jugador, "volver"); volver(jugador, vista); }
            default -> { /* el borde no hace nada */ }
        }
    }

    private void cambiar(Player jugador, Vista vista, int delta) {
        int antes = vista.cantidad;
        /* long para que un shift sobre el paso grande no de la vuelta al entero. */
        long pedido = (long) vista.cantidad + delta;
        vista.cantidad = (int) Math.max(1, Math.min(pedido, Motor.MAX_POR_OPERACION));
        pintar(jugador, vista, false);
        if (vista.cantidad != antes) secciones.sonar(jugador, "elegir-item");
    }

    /** Pide la cantidad por el chat y vuelve a la misma pantalla. */
    private void preguntar(Player jugador, Vista vista) {
        Mensajes m = modulo.mensajes();
        if (m != null) m.manda(jugador, "cantidad-pide",
                "&fEscribe la cantidad en el chat. &7Escribe cancelar para dejarlo.");
        chat.pedir(jugador, texto -> {
            int n;
            try {
                /* Se admiten los miles con punto o con coma: la gente escribe 1.000. */
                n = Integer.parseInt(texto.replace(".", "").replace(",", "").replace(" ", ""));
            } catch (NumberFormatException ex) {
                if (m != null) m.manda(jugador, "cantidad-mala",
                        "&#FF5C5CEso no es un numero&8: &7%texto%", "%texto%", texto);
                pintar(jugador, vista, true);
                return;
            }
            vista.cantidad = (int) Math.max(1, Math.min((long) n, Motor.MAX_POR_OPERACION));
            pintar(jugador, vista, true);
        }, () -> pintar(jugador, vista, true));
    }

    private void confirmar(Player jugador, Vista vista) {
        Motor motor = modulo.motor();
        if (motor == null) {
            jugador.sendMessage(Estilo.legado("&cLa tienda todavia esta arrancando."));
            return;
        }
        Motor.Resultado r = vista.comprando
                ? motor.comprar(jugador, vista.art, vista.cantidad)
                : motor.vender(jugador, vista.art.material(), vista.cantidad);
        jugador.sendMessage(r.mensaje());
        secciones.sonar(jugador, r.ok() ? "elegir-item" : "error");
        volver(jugador, vista);
    }

    private void volver(Player jugador, Vista vista) {
        MenuTienda menu = modulo.menu();
        if (menu == null) { jugador.closeInventory(); return; }
        if (vista.categoria == null) menu.abrirPrincipal(jugador);
        else menu.abrirCategoria(jugador, vista.categoria, vista.pagina);
    }

    // ---------------------------------------------------------------- adornos

    private static String numero(int n) {
        return String.format(Locale.US, "%,d", n);
    }

    private static String nombreDe(Catalogo.Articulo art) {
        return art.tieneNombre() ? art.nombrePropio() : Motor.nombre(art);
    }

    private void rellenar(Inventory inv) {
        ItemStack panel = secciones.relleno();
        if (panel == null) return;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack actual = inv.getItem(i);
            if (actual == null || actual.getType().isAir()) inv.setItem(i, panel.clone());
        }
    }

    private ItemStack saldo(Player jugador, Motor motor) {
        ItemStack pila = new ItemStack(Material.PLAYER_HEAD);
        if (pila.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(jugador);
            meta.displayName(secciones.texto("saldo-nombre", "&x&D&7&F&3&F&F%jugador%",
                    "%jugador%", jugador.getName()).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(secciones.texto("saldo-etiqueta", "&#FDFF66Tu dinero"),
                    secciones.texto("saldo-valor", "&8▸ &f%saldo%",
                            "%saldo%", Estilo.dinero(motor.saldo(jugador)))));
            meta.addItemFlags(ItemFlag.values());
            pila.setItemMeta(meta);
        }
        return pila;
    }

    /** El panel lleva la cantidad puesta: el numerito del stack ya dice cuanto
     *  suma sin tener que leer el nombre. */
    private static ItemStack pieza(Material material, int cuantos, Component titulo, List<Component> lore) {
        return decorar(new ItemStack(material, Math.max(1, Math.min(cuantos, 64))), titulo, lore);
    }

    private static ItemStack decorar(ItemStack pila, Component titulo, List<Component> lore) {
        ItemMeta meta = pila.getItemMeta();
        if (meta != null) {
            meta.displayName(titulo.decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            pila.setItemMeta(meta);
        }
        return pila;
    }
}
