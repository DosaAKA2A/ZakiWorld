package net.ederus.edm.tienda;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * El menu de la tienda. Lo que ve el jugador: una rejilla de categorias y,
 * dentro, los articulos con su precio.
 *
 * Los clics se cancelan TODOS antes de hacer nada. Un menu de tienda del que se
 * puedan sacar items es una duplicadora, y el bug se descubre cuando ya hay
 * cuarenta cofres llenos.
 *
 * Los controles son los de EconomyShopGUI a proposito, para que a los jugadores
 * no les cambie nada: clic izquierdo compra, derecho vende, con shift por lotes.
 */
public final class MenuTienda implements Listener {

    private static final int FILAS = 6;
    private static final int POR_PAGINA = 45;          // 5 filas; la ultima es la barra
    private static final int RANURA_VOLVER = 49;
    private static final int RANURA_ANTERIOR = 45;
    private static final int RANURA_SIGUIENTE = 53;

    /** Marca nuestras ventanas. Nunca se identifica un menu por su titulo: el
     *  jugador puede tener un cofre llamado igual y acabariamos operando sobre el. */
    static final class Vista implements InventoryHolder {
        final String categoria;       // null = menu principal
        final int pagina;
        Inventory inv;
        Vista(String categoria, int pagina) { this.categoria = categoria; this.pagina = pagina; }
        @Override public Inventory getInventory() { return inv; }
    }

    private final TiendaPlugin modulo;
    private final Catalogo catalogo;
    private final Topes topes;
    private final Iconos iconos;

    public MenuTienda(TiendaPlugin modulo, Catalogo catalogo, Topes topes, Iconos iconos) {
        this.modulo = modulo;
        this.catalogo = catalogo;
        this.topes = topes;
        this.iconos = iconos;
    }

    // ------------------------------------------------------------- construir

    public void abrirPrincipal(Player jugador) {
        List<String> categorias = new ArrayList<>(catalogo.categorias().keySet());
        Vista vista = new Vista(null, 0);
        Inventory inv = Bukkit.createInventory(vista, 54, Estilo.titulo("TIENDA", "Ederus"));
        vista.inv = inv;

        /* Centradas: dos filas de siete, como la tienda que ya conocen. */
        int[] ranuras = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31};
        for (int i = 0; i < categorias.size() && i < ranuras.length; i++) {
            String cat = categorias.get(i);
            int n = catalogo.categorias().getOrDefault(cat, 0);
            List<Component> lore = List.of(Estilo.valor(n + (n == 1 ? " articulo" : " articulos")),
                    Estilo.vacio(), Estilo.accion("Click para entrar", Estilo.ACCION_COMPRA));
            ItemStack icono = iconos.de(cat);
            inv.setItem(ranuras[i], icono != null
                    ? decorar(icono, bonito(cat), lore)
                    : pieza(catalogo.iconoDe(cat), bonito(cat), lore));
        }
        jugador.openInventory(inv);
    }

    public void abrirCategoria(Player jugador, String categoria, int pagina) {
        List<Catalogo.Articulo> items = catalogo.deCategoria(categoria);
        int paginas = Math.max(1, (int) Math.ceil(items.size() / (double) POR_PAGINA));
        if (pagina < 0) pagina = 0;
        if (pagina >= paginas) pagina = paginas - 1;

        Vista vista = new Vista(categoria, pagina);
        Inventory inv = Bukkit.createInventory(vista, FILAS * 9, Estilo.titulo("EDERUS", bonito(categoria)));
        vista.inv = inv;

        int desde = pagina * POR_PAGINA;
        for (int i = 0; i < POR_PAGINA && desde + i < items.size(); i++) {
            inv.setItem(i, pintar(items.get(desde + i), jugador));
        }

        if (pagina > 0) {
            inv.setItem(RANURA_ANTERIOR, pieza(Material.ARROW, "Pagina anterior", List.of()));
        }
        if (pagina < paginas - 1) {
            inv.setItem(RANURA_SIGUIENTE, pieza(Material.ARROW, "Pagina siguiente", List.of()));
        }
        inv.setItem(RANURA_VOLVER, pieza(Material.BARRIER, "Volver",
                List.of(Estilo.valor("Pagina " + (pagina + 1) + " de " + paginas))));

        jugador.openInventory(inv);
    }

    /** Un articulo con su precio, sus topes y lo que se puede hacer con el. */
    /** Un articulo con su precio, sus topes y lo que se puede hacer con el. */
    private ItemStack pintar(Catalogo.Articulo art, Player jugador) {
        List<Component> lore = new ArrayList<>();

        if (art.seCompra()) {
            lore.add(Estilo.etiqueta("Precio de compra", Estilo.COMPRA));
            lore.add(Estilo.valor(Estilo.dinero(art.compra())));
        }

        int llevas = 0;
        if (art.seVende()) {
            lore.add(Estilo.etiqueta("Precio de venta", Estilo.VENTA));
            lore.add(Estilo.valor(Estilo.dinero(art.venta())));
            /* Lo que la tienda ACEPTA de lo que lleva encima, no lo que lleva:
             * un MMOItems no cuenta, y verlo a 0 explica solo por que no se
             * puede vender, sin tener que probarlo a ciegas. */
            llevas = Motor.contarLimpios(jugador.getInventory(), art.material());
            lore.add(Estilo.valor("Llevas " + llevas));
        }

        if (art.tieneTope()) {
            int quedan = topes.restante(jugador.getUniqueId(), art);
            lore.add(Estilo.vacio());
            lore.add(Estilo.etiqueta("Limite", Estilo.CLARO));
            lore.add(Estilo.valor(art.topeVenta() + " cada " + Motor.duracion(art.ventanaMs())));
            lore.add(quedan > 0
                    ? Estilo.valor("Te quedan " + quedan)
                    : Estilo.texto(" " + Estilo.FLECHA + " Agotado, vuelve en "
                        + Motor.duracion(topes.esperaMs(jugador.getUniqueId(), art)), NamedTextColor.RED));
        }

        lore.add(Estilo.vacio());
        if (art.seCompra()) {
            lore.add(Estilo.accion("Click para comprar 1", Estilo.ACCION_COMPRA));
            lore.add(Estilo.accion("Shift + click para 64", Estilo.ACCION_COMPRA));
        }
        if (art.seVende()) {
            lore.add(Estilo.accion("Click derecho para vender 1", Estilo.ACCION_VENTA));
            lore.add(llevas > 0
                    ? Estilo.accion("Shift + derecho para vender " + llevas, Estilo.ACCION_VENTA)
                    : Estilo.accion("Shift + derecho para vender todo", Estilo.APAGADO));
        }
        if (!art.seCompra() && !art.seVende()) lore.add(Estilo.accion("Solo de exposicion", Estilo.APAGADO));

        ItemStack icono = Motor.construir(art, 1);
        if (icono == null) icono = new ItemStack(art.material());
        return decorar(icono, Motor.nombre(art), lore);
    }

    // --------------------------------------------------------------- clics

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Vista) e.setCancelled(true);
    }

    @EventHandler
    public void alPulsar(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Vista vista)) return;

        /* Lo primero y sin condiciones: de este menu no sale ni entra un item.
         * Tambien cubre el shift-clic desde el inventario del jugador. */
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player jugador)) return;
        if (e.getClickedInventory() != e.getInventory()) return;   // clic en su propio inventario
        ItemStack pulsado = e.getCurrentItem();
        if (pulsado == null || pulsado.getType().isAir()) return;

        if (vista.categoria == null) {
            List<String> categorias = new ArrayList<>(catalogo.categorias().keySet());
            int[] ranuras = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31};
            for (int i = 0; i < categorias.size() && i < ranuras.length; i++) {
                if (ranuras[i] == e.getSlot()) { abrirCategoria(jugador, categorias.get(i), 0); return; }
            }
            return;
        }

        switch (e.getSlot()) {
            case RANURA_VOLVER -> { abrirPrincipal(jugador); return; }
            case RANURA_ANTERIOR -> { abrirCategoria(jugador, vista.categoria, vista.pagina - 1); return; }
            case RANURA_SIGUIENTE -> { abrirCategoria(jugador, vista.categoria, vista.pagina + 1); return; }
            default -> { /* es un articulo */ }
        }
        if (e.getSlot() >= POR_PAGINA) return;

        List<Catalogo.Articulo> items = catalogo.deCategoria(vista.categoria);
        int indice = vista.pagina * POR_PAGINA + e.getSlot();
        if (indice >= items.size()) return;
        Catalogo.Articulo art = items.get(indice);

        Motor motor = modulo.motor();
        if (motor == null) { jugador.sendMessage("La tienda aun no esta lista."); return; }

        ClickType clic = e.getClick();
        Motor.Resultado r;
        if (clic.isRightClick()) {
            if (!art.seVende()) return;
            r = motor.vender(jugador, art.material(), clic.isShiftClick() ? Motor.MAX_POR_OPERACION : 1);
        } else if (clic.isLeftClick()) {
            if (!art.seCompra()) return;
            r = motor.comprar(jugador, art, clic.isShiftClick() ? 64 : 1);
        } else {
            return;
        }

        jugador.sendMessage(r.mensaje());
        /* Se repinta: el tope que queda y el saldo acaban de cambiar. */
        abrirCategoria(jugador, vista.categoria, vista.pagina);
    }

    // --------------------------------------------------------------- adornos

    private static ItemStack pieza(Material material, String titulo, List<Component> lore) {
        return decorar(new ItemStack(material), titulo, lore);
    }

    private static ItemStack decorar(ItemStack pila, String titulo, List<Component> lore) {
        ItemMeta meta = pila.getItemMeta();
        if (meta != null) {
            meta.displayName(Estilo.texto(titulo, Estilo.CLARO));
            if (!lore.isEmpty()) meta.lore(lore);
            /* Fuera la etiqueta que pone Minecraft sola (daño, velocidad de
             * ataque, encantamientos...): en un icono de tienda es ruido y
             * empuja el precio fuera de la vista. values() lo cubre todo y no
             * hay que revisarlo cuando salga una version nueva. */
            meta.addItemFlags(ItemFlag.values());
            pila.setItemMeta(meta);
        }
        return pila;
    }

    private static Component gris(String t) {
        return Component.text(t, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static Component verde(String t) {
        return Component.text(t, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false);
    }

    private static String bonito(String crudo) {
        return Motor.bonito(crudo.replace('_', ' '));
    }
}
