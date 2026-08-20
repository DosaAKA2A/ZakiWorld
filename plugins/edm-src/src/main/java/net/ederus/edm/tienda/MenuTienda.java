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
    /* Fila 4, centradas bajo las 14 categorias. */
    private static final int RANURA_OFERTAS = 30;
    private static final int RANURA_DEMANDAS = 32;

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
    private final Secciones secciones;

    public MenuTienda(TiendaPlugin modulo, Catalogo catalogo, Topes topes, Secciones secciones) {
        this.modulo = modulo;
        this.catalogo = catalogo;
        this.topes = topes;
        this.secciones = secciones;
    }

    // ------------------------------------------------------------- construir

    /** Los huecos libres llevan el mismo panel separador que ya usan sus
     *  paginas: sin el, el menu se ve a medio hacer. */
    private void rellenar(Inventory inv) {
        ItemStack panel = secciones.relleno();
        if (panel == null) return;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack actual = inv.getItem(i);
            if (actual == null || actual.getType().isAir()) inv.setItem(i, panel.clone());
        }
    }

    public void abrirPrincipal(Player jugador) {
        Vista vista = new Vista(null, 0);
        Inventory inv = Bukkit.createInventory(vista, 54, Estilo.titulo("TIENDA", "Ederus"));
        vista.inv = inv;

        for (Secciones.Seccion s : secciones.todas()) {
            if (s.ranura() < 0 || s.ranura() >= inv.getSize()) continue;
            int n = catalogo.categorias().getOrDefault(s.id(), 0);
            if (n == 0) continue;
            ItemStack icono = secciones.icono(s.id());
            if (icono == null) icono = new ItemStack(catalogo.iconoDe(s.id()));
            inv.setItem(s.ranura(), decorarCon(icono, secciones.nombreDe(s.id()),
                    List.of(Estilo.valor(n + (n == 1 ? " articulo" : " articulos")),
                            Estilo.vacio(),
                            Estilo.accion("Click para entrar", Estilo.ACCION_COMPRA))));
        }

        Rotacion rot = modulo.rotacion();
        if (rot != null && rot.activo()) {
            inv.setItem(RANURA_OFERTAS, decorarCon(new ItemStack(Material.SUNFLOWER),
                    Estilo.texto("→ ", NamedTextColor.DARK_GRAY)
                        .append(Estilo.texto("Ofertas del dia", Estilo.ACCION_COMPRA)),
                    List.of(Estilo.valor(contar(rot.ofertas()) + " articulos rebajados"),
                            Estilo.texto("   entre un 15% y un 65%", Estilo.APAGADO),
                            Estilo.vacio(),
                            Estilo.texto("   rota en " + Motor.duracion(Rotacion.hastaManana()), Estilo.APAGADO),
                            Estilo.vacio(),
                            Estilo.accion("Click para entrar", Estilo.ACCION_COMPRA))));
            inv.setItem(RANURA_DEMANDAS, decorarCon(new ItemStack(Material.EMERALD),
                    Estilo.texto("→ ", NamedTextColor.DARK_GRAY)
                        .append(Estilo.texto("Demanda del dia", Estilo.VENTA)),
                    List.of(Estilo.valor(contar(rot.demandas()) + " articulos que se pagan mas"),
                            Estilo.texto("   entre un 25% y un 75% mas", Estilo.APAGADO),
                            Estilo.vacio(),
                            Estilo.texto("   rota en " + Motor.duracion(Rotacion.hastaManana()), Estilo.APAGADO),
                            Estilo.vacio(),
                            Estilo.accion("Click para entrar", Estilo.ACCION_VENTA))));
        }

        rellenar(inv);
        jugador.openInventory(inv);
        secciones.sonar(jugador, "abrir-menu");
    }

    private static int contar(Iterable<?> it) {
        int n = 0; for (Object o : it) n++; return n;
    }

    /** Las dos secciones del dia no viven en el catalogo: se arman al vuelo. */
    static final String OFERTAS = "@ofertas";
    static final String DEMANDAS = "@demandas";

    private List<Catalogo.Articulo> articulosDe(String categoria) {
        Rotacion rot = modulo.rotacion();
        if (OFERTAS.equals(categoria) || DEMANDAS.equals(categoria)) {
            List<Catalogo.Articulo> out = new ArrayList<>();
            if (rot == null) return out;
            for (Rotacion.Trato tr : (OFERTAS.equals(categoria) ? rot.ofertas() : rot.demandas())) {
                Catalogo.Articulo a = catalogo.de(tr.clave());
                if (a != null) out.add(a);
            }
            return out;
        }
        return catalogo.deCategoria(categoria);
    }

    private static String tituloDe(String categoria) {
        if (OFERTAS.equals(categoria)) return "Ofertas del dia";
        if (DEMANDAS.equals(categoria)) return "Demanda del dia";
        return null;
    }

    public void abrirCategoria(Player jugador, String categoria, int pagina) {
        List<Catalogo.Articulo> items = articulosDe(categoria);
        int paginas = Math.max(1, (int) Math.ceil(items.size() / (double) POR_PAGINA));
        if (pagina < 0) pagina = 0;
        if (pagina >= paginas) pagina = paginas - 1;

        Vista vista = new Vista(categoria, pagina);
        String especial = tituloDe(categoria);
        Inventory inv = Bukkit.createInventory(vista, FILAS * 9,
                Estilo.titulo("EDERUS", especial != null ? especial : bonito(categoria)));
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

        rellenar(inv);
        jugador.openInventory(inv);
        secciones.sonar(jugador, "abrir-categoria");
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
            Mercado mercado = modulo.mercado();
            double efectiva = mercado != null ? mercado.ventaEfectiva(art, art.compra()) : art.venta();
            int caida = mercado != null ? mercado.caidaPorCiento(art) : 0;
            lore.add(Estilo.etiqueta("Precio de venta", Estilo.VENTA));
            /* Un precio que baja en silencio parece un bug: se dice cuanto y por que. */
            lore.add(caida > 0
                    ? Estilo.valor(Estilo.dinero(efectiva)).append(Estilo.texto("  -" + caida + "%", Estilo.ACCION_VENTA))
                    : Estilo.valor(Estilo.dinero(efectiva)));
            if (caida > 0) lore.add(Estilo.texto("   sobrevendido, se recupera solo", Estilo.APAGADO));
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
            if (e.getSlot() == RANURA_OFERTAS) { abrirCategoria(jugador, OFERTAS, 0); return; }
            if (e.getSlot() == RANURA_DEMANDAS) { abrirCategoria(jugador, DEMANDAS, 0); return; }
            for (Secciones.Seccion s : secciones.todas()) {
                if (s.ranura() == e.getSlot()) { abrirCategoria(jugador, s.id(), 0); return; }
            }
            return;
        }

        switch (e.getSlot()) {
            case RANURA_VOLVER -> { secciones.sonar(jugador, "volver"); abrirPrincipal(jugador); return; }
            case RANURA_ANTERIOR -> { secciones.sonar(jugador, "cambiar-pagina"); abrirCategoria(jugador, vista.categoria, vista.pagina - 1); return; }
            case RANURA_SIGUIENTE -> { secciones.sonar(jugador, "cambiar-pagina"); abrirCategoria(jugador, vista.categoria, vista.pagina + 1); return; }
            default -> { /* es un articulo */ }
        }
        if (e.getSlot() >= POR_PAGINA) return;

        List<Catalogo.Articulo> items = articulosDe(vista.categoria);
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
        secciones.sonar(jugador, r.ok() ? "elegir-item" : "error");
        /* Se repinta: el tope que queda y el saldo acaban de cambiar. */
        abrirCategoria(jugador, vista.categoria, vista.pagina);
    }

    // --------------------------------------------------------------- adornos

    private static ItemStack decorarCon(ItemStack pila, Component titulo, List<Component> lore) {
        ItemMeta meta = pila.getItemMeta();
        if (meta != null) {
            meta.displayName(titulo.decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            pila.setItemMeta(meta);
        }
        return pila;
    }

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
