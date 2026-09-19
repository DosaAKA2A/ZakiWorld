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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * El editor de precios: "EDITANDO → Diamante".
 *
 * Se llega desde /shop edit (solo ederus.tienda.admin), que abre el menu de
 * siempre en modo edicion: pulsar un articulo trae esta pantalla en vez de la
 * de cantidad. Aqui se escribe por el chat el precio de compra y el de venta,
 * y al guardar se reescribe SOLO ese articulo en precios.yml y se recarga el
 * catalogo, asi que el cambio se ve al momento sin tocar ningun fichero a mano.
 *
 * Las reglas son las mismas que aplica el catalogo al cargar: la venta nunca
 * iguala ni supera la compra (seria dinero infinito) y una variante (spawner)
 * no se vende. Se comprueban ANTES de escribir; si aun asi el fichero no carga,
 * se deja como estaba y se dice por que.
 */
public final class EditorPrecio implements Listener {

    private static final int TAM = 45;                          // 5 filas

    private static final int RANURA_ITEM = 13;
    private static final int RANURA_COMPRA = 20;
    private static final int RANURA_VENTA = 24;
    private static final int RANURA_MERCADO = 31;
    private static final int RANURA_VOLVER = 40;

    /** De donde salio el operador, para devolverlo al mismo sitio del menu. */
    static final class Vista implements InventoryHolder {
        final String clave;
        final String categoria;
        final int pagina;
        Inventory inv;

        Vista(String clave, String categoria, int pagina) {
            this.clave = clave;
            this.categoria = categoria;
            this.pagina = pagina;
        }

        @Override public Inventory getInventory() { return inv; }
    }

    private final TiendaPlugin modulo;
    private final Catalogo catalogo;
    private final Secciones secciones;
    private final EntradaChat chat;

    public EditorPrecio(TiendaPlugin modulo, Catalogo catalogo, Secciones secciones, EntradaChat chat) {
        this.modulo = modulo;
        this.catalogo = catalogo;
        this.secciones = secciones;
        this.chat = chat;
    }

    // ------------------------------------------------------------------ abrir

    public void abrir(Player jugador, Catalogo.Articulo art, String categoria, int pagina) {
        pintar(jugador, new Vista(art.clave(), categoria, pagina));
    }

    private void pintar(Player jugador, Vista vista) {
        /* Siempre el articulo VIVO del catalogo, no el que se pulso: despues de
         * guardar, el que se pulso ya tiene los precios viejos. */
        Catalogo.Articulo art = catalogo.de(vista.clave);
        if (art == null) { volver(jugador, vista); return; }

        Inventory inv = Bukkit.createInventory(vista, TAM,
                secciones.texto("titulo-editando", "&x&F&F&9&E&3&D&lEDITANDO &8→ &x&D&7&F&3&F&F%item%",
                        "%item%", Motor.nombre(art)));
        vista.inv = inv;

        inv.setItem(RANURA_ITEM, ficha(art));

        inv.setItem(RANURA_COMPRA, decorar(new ItemStack(Material.GOLD_INGOT),
                secciones.texto("editor-compra", "&#91F4FFPrecio de compra"),
                List.of(Estilo.valor(art.seCompra() ? Estilo.dinero(art.compra()) : "No se compra"),
                        Estilo.vacio(),
                        secciones.texto("editor-clic-escribir", "&8▸ &7Clic para escribirlo en el chat"),
                        secciones.texto("editor-clic-quitar", "&8▸ &7Clic derecho para quitarlo"))));

        List<Component> loreVenta = new ArrayList<>();
        loreVenta.add(Estilo.valor(art.seVende() ? Estilo.dinero(art.venta()) : "No se vende"));
        loreVenta.add(Estilo.vacio());
        if (art.esVariante()) {
            loreVenta.add(secciones.texto("editor-variante", "&8▸ &7Los spawners no se venden"));
        } else {
            loreVenta.add(secciones.texto("editor-clic-escribir", "&8▸ &7Clic para escribirlo en el chat"));
            loreVenta.add(secciones.texto("editor-clic-quitar", "&8▸ &7Clic derecho para quitarlo"));
        }
        inv.setItem(RANURA_VENTA, decorar(new ItemStack(Material.EMERALD),
                secciones.texto("editor-venta", "&#FDFF66Precio de venta"), loreVenta));

        /* La regla que va a aplicar el guardado, a la vista antes de escribir. */
        inv.setItem(RANURA_MERCADO, decorar(new ItemStack(Material.BOOK),
                secciones.texto("editor-reglas", "&x&D&7&F&3&F&FReglas"),
                List.of(secciones.texto("editor-regla-1", "&8▸ &7La venta va por debajo de la compra"),
                        secciones.texto("editor-regla-2", "&8▸ &7Un 0 apaga esa cara del artículo"),
                        secciones.texto("editor-regla-3", "&8▸ &7Se guarda en precios.yml al momento"),
                        Estilo.vacio(),
                        secciones.texto("editor-categoria", "&8▸ &7Categoría&8: &f%categoria%",
                                "%categoria%", art.categoria()))));

        inv.setItem(RANURA_VOLVER, decorar(new ItemStack(Material.BARRIER),
                secciones.texto("cantidad-volver", "&x&D&7&F&3&F&FVolver"), List.of()));

        rellenar(inv);
        jugador.openInventory(inv);
        secciones.sonar(jugador, "abrir-categoria");
    }

    private ItemStack ficha(Catalogo.Articulo art) {
        List<Component> lore = new ArrayList<>();
        lore.add(Estilo.etiqueta("Compra", Estilo.COMPRA));
        lore.add(Estilo.valor(art.seCompra() ? Estilo.dinero(art.compra()) : "—"));
        lore.add(Estilo.etiqueta("Venta", Estilo.VENTA));
        lore.add(Estilo.valor(art.seVende() ? Estilo.dinero(art.venta()) : "—"));
        if (art.seCompra() && art.seVende()) {
            lore.add(Estilo.vacio());
            lore.add(Estilo.texto("   la venta es el " + Math.round(art.venta() / art.compra() * 100)
                    + "% de la compra", Estilo.APAGADO));
        }
        lore.add(Estilo.vacio());
        lore.add(secciones.texto("editor-clave", "&8▸ &7Clave&8: &f%clave%", "%clave%", art.clave()));

        ItemStack icono = Motor.construir(art, 1);
        if (icono == null) icono = new ItemStack(art.material());
        return decorar(icono, secciones.texto("editor-nombre", "&x&D&7&F&3&F&F%item%",
                "%item%", art.tieneNombre() ? art.nombrePropio() : Motor.nombre(art)), lore);
    }

    // ------------------------------------------------------------------ clics

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Vista) e.setCancelled(true);
    }

    @EventHandler
    public void alPulsar(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Vista vista)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player jugador)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        /* El permiso se mira en cada clic, no solo al abrir: si se lo quitan
         * con la ventana abierta, no sigue guardando precios. */
        if (!jugador.hasPermission("ederus.tienda.admin")) { jugador.closeInventory(); return; }

        Catalogo.Articulo art = catalogo.de(vista.clave);
        if (art == null) { volver(jugador, vista); return; }
        ClickType clic = e.getClick();

        switch (e.getSlot()) {
            case RANURA_COMPRA -> {
                if (clic.isRightClick()) guardar(jugador, vista, art, 0, art.venta());
                else preguntar(jugador, vista, art, true);
            }
            case RANURA_VENTA -> {
                if (art.esVariante()) { secciones.sonar(jugador, "error"); return; }
                if (clic.isRightClick()) guardar(jugador, vista, art, art.compra(), 0);
                else preguntar(jugador, vista, art, false);
            }
            case RANURA_VOLVER -> { secciones.sonar(jugador, "volver"); volver(jugador, vista); }
            default -> { /* el borde no hace nada */ }
        }
    }

    private void preguntar(Player jugador, Vista vista, Catalogo.Articulo art, boolean compra) {
        Mensajes m = modulo.mensajes();
        if (m != null) m.manda(jugador, compra ? "editor-pide-compra" : "editor-pide-venta",
                compra ? "&fEscribe el precio de compra de &x&D&7&F&3&F&F%item%&f. &7Escribe cancelar para dejarlo."
                       : "&fEscribe el precio de venta de &x&D&7&F&3&F&F%item%&f. &7Escribe cancelar para dejarlo.",
                "%item%", Motor.nombre(art));
        chat.pedir(jugador, texto -> {
            Double n = leerPrecio(texto);
            if (n == null) {
                if (m != null) m.manda(jugador, "editor-mal-numero",
                        "&#FF5C5CEso no es un precio&8: &7%texto%", "%texto%", texto);
                secciones.sonar(jugador, "error");
                pintar(jugador, vista);
                return;
            }
            if (compra) guardar(jugador, vista, art, n, art.venta());
            else guardar(jugador, vista, art, art.compra(), n);
        }, () -> pintar(jugador, vista));
    }

    /**
     * Un precio escrito por una persona: "1500", "1.500", "1,5", "0.25".
     * El punto es de miles si va seguido de exactamente tres cifras y no hay
     * coma; si no, es decimal. La coma siempre es decimal.
     */
    static Double leerPrecio(String texto) {
        String t = texto.trim().replace(" ", "").replace("$", "");
        if (t.isEmpty()) return null;
        if (t.contains(",")) {
            t = t.replace(".", "").replace(',', '.');
        } else if (t.matches("\\d{1,3}(\\.\\d{3})+")) {
            t = t.replace(".", "");
        }
        try {
            double d = Double.parseDouble(t);
            if (d < 0 || Double.isNaN(d) || Double.isInfinite(d)) return null;
            /* Dos decimales: lo que cobra y paga la tienda. */
            return Math.round(d * 100) / 100.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void guardar(Player jugador, Vista vista, Catalogo.Articulo art, double compra, double venta) {
        Mensajes m = modulo.mensajes();
        String fallo = modulo.cambiarPrecio(art, compra, venta);
        if (fallo != null) {
            if (m != null) m.manda(jugador, "editor-no-guardado", "&#FF5C5C%motivo%", "%motivo%", fallo);
            secciones.sonar(jugador, "error");
        } else {
            if (m != null) m.manda(jugador, "editor-guardado",
                    "&x&D&7&F&3&F&F%item% &7→ compra &f%compra% &7venta &f%venta%",
                    "%item%", Motor.nombre(art),
                    "%compra%", compra > 0 ? Estilo.dinero(compra) : "—",
                    "%venta%", venta > 0 ? Estilo.dinero(venta) : "—");
            secciones.sonar(jugador, "elegir-item");
        }
        pintar(jugador, vista);
    }

    private void volver(Player jugador, Vista vista) {
        MenuTienda menu = modulo.menu();
        if (menu == null) { jugador.closeInventory(); return; }
        if (vista.categoria == null) menu.abrirPrincipal(jugador, true);
        else menu.abrirCategoria(jugador, vista.categoria, vista.pagina, true);
    }

    // ---------------------------------------------------------------- adornos

    private void rellenar(Inventory inv) {
        ItemStack panel = secciones.relleno();
        if (panel == null) return;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack actual = inv.getItem(i);
            if (actual == null || actual.getType().isAir()) inv.setItem(i, panel.clone());
        }
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

    /** Como se escribe un precio en precios.yml: entero si lo es, si no con
     *  hasta dos decimales y punto, que es lo que lee el YAML. */
    static String numero(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.format(Locale.US, "%.2f", d).replaceAll("0+$", "");
    }
}
