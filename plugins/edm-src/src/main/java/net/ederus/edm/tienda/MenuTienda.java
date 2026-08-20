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
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * El menu de la tienda.
 *
 * Los clics se cancelan TODOS antes de hacer nada. Un menu de tienda del que se
 * puedan sacar items es una duplicadora, y el bug se descubre cuando ya hay
 * cuarenta cofres llenos.
 *
 * LA REJILLA es de 7 columnas con borde, no de 9 a sangre. Con 9 los articulos
 * llegan al filo y la ventana parece un cofre; con el borde relleno y el
 * contenido centrado parece una tienda. Cuando una pagina va a medias, las
 * filas se centran en vertical y la ultima en horizontal, para que no quede un
 * bloque de huecos abajo a la derecha.
 */
public final class MenuTienda implements Listener {

    private static final int FILAS = 6;

    /* Zona util: filas 1..4, columnas 1..7. El resto es borde. */
    private static final int COLUMNAS = 7;
    private static final int FILAS_UTILES = 4;
    private static final int POR_PAGINA = COLUMNAS * FILAS_UTILES;   // 28

    private static final int RANURA_ANTERIOR = 45;
    private static final int RANURA_VOLVER = 49;
    private static final int RANURA_SIGUIENTE = 53;
    /* Una fila por debajo de las categorias. */
    private static final int RANURA_OFERTAS = 39;
    private static final int RANURA_DEMANDAS = 41;
    private static final int RANURA_SALDO = 49;

    static final String OFERTAS = "@ofertas";
    static final String DEMANDAS = "@demandas";

    /** Marca nuestras ventanas. Nunca se identifica un menu por su titulo: el
     *  jugador puede tener un cofre llamado igual y acabariamos operando sobre el. */
    static final class Vista implements InventoryHolder {
        final String categoria;                 // null = menu principal
        final int pagina;
        final Map<Integer, Integer> ranuraAIndice = new HashMap<>();
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

    private void rellenar(Inventory inv) {
        ItemStack panel = secciones.relleno();
        if (panel == null) return;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack actual = inv.getItem(i);
            if (actual == null || actual.getType().isAir()) inv.setItem(i, panel.clone());
        }
    }

    /** La cabeza del jugador con su saldo, como en la tienda de siempre. */
    private ItemStack saldo(Player jugador) {
        ItemStack pila = new ItemStack(Material.PLAYER_HEAD);
        if (pila.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(jugador);
            meta.displayName(secciones.texto("saldo-nombre", "&x&D&7&F&3&F&F%jugador%",
                    "%jugador%", jugador.getName()));
            Motor motor = modulo.motor();
            List<Component> lore = new ArrayList<>();
            lore.add(secciones.texto("saldo-etiqueta", "&#FDFF66Tu dinero"));
            lore.add(secciones.texto("saldo-valor", "&8▸ &f%saldo%",
                    "%saldo%", motor != null ? Estilo.dinero(motor.saldo(jugador)) : "..."));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            pila.setItemMeta(meta);
        }
        return pila;
    }

    public void abrirPrincipal(Player jugador) {
        Vista vista = new Vista(null, 0);
        Inventory inv = Bukkit.createInventory(vista, 54,
                secciones.texto("titulo-principal", "&x&0&0&8&3&F&D&lTIENDA &8| &x&D&7&F&3&F&FEderus"));
        vista.inv = inv;

        for (Secciones.Seccion s : secciones.todas()) {
            if (s.ranura() < 0 || s.ranura() >= inv.getSize()) continue;
            int n = catalogo.categorias().getOrDefault(s.id(), 0);
            if (n == 0) continue;
            ItemStack icono = secciones.icono(s.id());
            if (icono == null) icono = new ItemStack(catalogo.iconoDe(s.id()));
            inv.setItem(s.ranura(), decorarCon(icono, secciones.nombreDe(s.id()),
                    List.of(secciones.texto("categoria-articulos", "&8▸ &f%articulos% articulos",
                                    "%articulos%", String.valueOf(n)),
                            Estilo.vacio(),
                            secciones.texto("categoria-entrar", "&#4FFF55▸ Click para entrar"))));
        }

        Rotacion rot = modulo.rotacion();
        if (rot != null && rot.activo()) {
            inv.setItem(RANURA_OFERTAS, decorarCon(new ItemStack(Material.SUNFLOWER),
                    secciones.texto("ofertas-nombre", "&8→ &#4FFF55Ofertas del dia"),
                    List.of(secciones.texto("ofertas-descripcion", "&7Estos objetos estan rebajados"),
                            Estilo.vacio(),
                            secciones.texto("rota-en", "&#545454Rota en %rota%",
                                    "%rota%", Motor.duracion(Rotacion.hastaManana())),
                            Estilo.vacio(),
                            secciones.texto("categoria-entrar", "&#4FFF55▸ Click para entrar"))));
            inv.setItem(RANURA_DEMANDAS, decorarCon(new ItemStack(Material.EMERALD),
                    secciones.texto("demandas-nombre", "&8→ &#FDFF66Demanda del dia"),
                    List.of(secciones.texto("demandas-descripcion", "&7Estos objetos son muy pedidos"),
                            Estilo.vacio(),
                            secciones.texto("rota-en", "&#545454Rota en %rota%",
                                    "%rota%", Motor.duracion(Rotacion.hastaManana())),
                            Estilo.vacio(),
                            secciones.texto("categoria-entrar", "&#4FFF55▸ Click para entrar"))));
        }

        inv.setItem(RANURA_SALDO, saldo(jugador));
        rellenar(inv);
        jugador.openInventory(inv);
        secciones.sonar(jugador, "abrir-menu");
    }

    /** Las dos secciones del dia no viven en el catalogo: se arman al vuelo. */
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

    /**
     * Coloca los articulos centrados en la zona util. Las filas que sobran se
     * reparten arriba y abajo, y la ultima fila incompleta se centra: asi una
     * pagina de 14 no deja media ventana vacia.
     */
    private void colocar(Inventory inv, Vista vista, List<Catalogo.Articulo> pagina, Player jugador) {
        int n = pagina.size();
        if (n == 0) return;
        int filas = Math.min(FILAS_UTILES, (int) Math.ceil(n / (double) COLUMNAS));
        int filaInicio = 1 + Math.max(0, (FILAS_UTILES - filas) / 2);

        int i = 0;
        for (int f = 0; f < filas && i < n; f++) {
            int enEsta = Math.min(COLUMNAS, n - i);
            int hueco = (COLUMNAS - enEsta) / 2;
            for (int c = 0; c < enEsta; c++, i++) {
                int ranura = (filaInicio + f) * 9 + 1 + hueco + c;
                if (ranura >= inv.getSize()) break;
                inv.setItem(ranura, pintar(pagina.get(i), jugador));
                vista.ranuraAIndice.put(ranura, i);
            }
        }
    }

    public void abrirCategoria(Player jugador, String categoria, int pagina) {
        List<Catalogo.Articulo> items = articulosDe(categoria);
        int paginas = Math.max(1, (int) Math.ceil(items.size() / (double) POR_PAGINA));
        if (pagina < 0) pagina = 0;
        if (pagina >= paginas) pagina = paginas - 1;

        Vista vista = new Vista(categoria, pagina);
        String especial = tituloDe(categoria);
        Inventory inv = Bukkit.createInventory(vista, FILAS * 9,
                secciones.texto("titulo-categoria", "&x&0&0&8&3&F&D&lEDERUS &8| &x&D&7&F&3&F&F%categoria%",
                        "%categoria%", especial != null ? especial : bonito(categoria)));
        vista.inv = inv;

        int desde = pagina * POR_PAGINA;
        List<Catalogo.Articulo> enPagina =
                new ArrayList<>(items.subList(desde, Math.min(items.size(), desde + POR_PAGINA)));
        colocar(inv, vista, enPagina, jugador);
        /* Los indices se guardaron relativos a la pagina: se pasan a absolutos. */
        final int base = desde;
        vista.ranuraAIndice.replaceAll((r, i) -> i + base);

        if (pagina > 0) inv.setItem(RANURA_ANTERIOR, decorarCon(new ItemStack(Material.ARROW),
                secciones.texto("anterior", "&x&D&7&F&3&F&FPagina anterior"), List.of()));
        if (pagina < paginas - 1) inv.setItem(RANURA_SIGUIENTE, decorarCon(new ItemStack(Material.ARROW),
                secciones.texto("siguiente", "&x&D&7&F&3&F&FPagina siguiente"), List.of()));
        inv.setItem(RANURA_VOLVER, decorarCon(new ItemStack(Material.BARRIER),
                secciones.texto("volver", "&x&D&7&F&3&F&FVolver"),
                List.of(secciones.texto("pagina", "&8▸ &fPagina %pagina% de %paginas%",
                        "%pagina%", String.valueOf(pagina + 1), "%paginas%", String.valueOf(paginas)))));

        rellenar(inv);
        jugador.openInventory(inv);
        secciones.sonar(jugador, "abrir-categoria");
    }

    /** Un articulo con su precio, sus topes y lo que se puede hacer con el. */
    private ItemStack pintar(Catalogo.Articulo art, Player jugador) {
        List<Component> lore = new ArrayList<>();
        Motor motor = modulo.motor();
        Rotacion rot = modulo.rotacion();
        Rotacion.Trato oferta = rot == null ? null : rot.oferta(art.clave());
        Rotacion.Trato demanda = rot == null ? null : rot.demanda(art.clave());

        if (art.seCompra()) {
            double efectiva = motor != null ? motor.compraEfectiva(art) : art.compra();
            lore.add(Estilo.etiqueta("Precio de compra", Estilo.COMPRA));
            if (oferta != null) {
                int pc = (int) Math.round((1 - oferta.factor()) * 100);
                /* Con la referencia al lado: un -52% no dice nada a quien no se
                 * sepa el catalogo de memoria. */
                lore.add(Estilo.valor(Estilo.dinero(efectiva))
                        .append(Estilo.texto("   antes " + Estilo.dinero(art.compra()), Estilo.APAGADO)));
                lore.add(Estilo.texto("   " + pc + "% mas barato", Estilo.ACCION_COMPRA));
            } else {
                lore.add(Estilo.valor(Estilo.dinero(efectiva)));
            }
        }

        int llevas = 0;
        if (art.seVende()) {
            double efectiva = motor != null ? motor.ventaEfectiva(art) : art.venta();
            int caida = modulo.mercado() != null ? modulo.mercado().caidaPorCiento(art) : 0;
            lore.add(Estilo.etiqueta("Precio de venta", Estilo.VENTA));
            if (demanda != null) {
                int pc = (int) Math.round((efectiva / art.venta() - 1) * 100);
                lore.add(Estilo.valor(Estilo.dinero(efectiva))
                        .append(Estilo.texto("   normal " + Estilo.dinero(art.venta()), Estilo.APAGADO)));
                if (pc > 0) lore.add(Estilo.texto("   " + pc + "% mas de lo normal", Estilo.VENTA));
            } else {
                lore.add(Estilo.valor(Estilo.dinero(efectiva)));
                if (caida > 0) lore.add(Estilo.texto("   -" + caida + "%, sobrevendido", Estilo.APAGADO));
            }
            /* Lo que la tienda ACEPTA de lo que lleva encima: un MMOItems no
             * cuenta, y verlo a 0 explica solo por que no se puede vender. */
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

        Rotacion.Trato trato = oferta != null ? oferta : demanda;
        if (trato != null && rot != null) {
            lore.add(Estilo.vacio());
            lore.add(Estilo.texto("Quedan hoy " + rot.restanteHoy(trato) + " en el servidor", Estilo.APAGADO));
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
        if (e.getClickedInventory() != e.getInventory()) return;
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
            case RANURA_ANTERIOR -> { secciones.sonar(jugador, "cambiar-pagina");
                                      abrirCategoria(jugador, vista.categoria, vista.pagina - 1); return; }
            case RANURA_SIGUIENTE -> { secciones.sonar(jugador, "cambiar-pagina");
                                       abrirCategoria(jugador, vista.categoria, vista.pagina + 1); return; }
            default -> { /* es un articulo */ }
        }

        Integer indice = vista.ranuraAIndice.get(e.getSlot());
        if (indice == null) return;
        List<Catalogo.Articulo> items = articulosDe(vista.categoria);
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

    private static ItemStack pieza(Material material, String titulo, List<Component> lore) {
        return decorar(new ItemStack(material), titulo, lore);
    }

    private static ItemStack decorar(ItemStack pila, String titulo, List<Component> lore) {
        return decorarCon(pila, Estilo.texto(titulo, Estilo.CLARO), lore);
    }

    private static ItemStack decorarCon(ItemStack pila, Component titulo, List<Component> lore) {
        ItemMeta meta = pila.getItemMeta();
        if (meta != null) {
            meta.displayName(titulo.decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) meta.lore(lore);
            /* Fuera la etiqueta que pone Minecraft sola (daño, velocidad de
             * ataque...): en un icono de tienda es ruido. */
            meta.addItemFlags(ItemFlag.values());
            pila.setItemMeta(meta);
        }
        return pila;
    }

    private static String bonito(String crudo) {
        return Motor.bonito(crudo.replace('_', ' '));
    }
}
