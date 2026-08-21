package net.ederus.edm.coinflip;

import net.ederus.edm.comun.EntradaChat;
import net.ederus.edm.comun.Estilo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
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
import java.util.Locale;
import java.util.Map;

/**
 * La mesa vista por dentro: las apuestas abiertas, con la cara del que las puso.
 *
 * Misma rejilla que la tienda (7 columnas con borde) para que el servidor no
 * parezca dos servidores distintos segun el menu que abras.
 */
public final class MenuCoinflip implements Listener {

    private static final int TAM = 54;
    private static final int COLUMNAS = 7;
    private static final int FILAS_UTILES = 4;
    private static final int POR_PAGINA = COLUMNAS * FILAS_UTILES;   // 28

    private static final int RANURA_ANTERIOR = 45;
    private static final int RANURA_CREAR = 48;
    private static final int RANURA_SALDO = 49;
    private static final int RANURA_MIAS = 50;
    private static final int RANURA_SIGUIENTE = 53;

    /** Marca la ventana como nuestra y recuerda que apuesta hay en cada hueco. */
    static final class Vista implements InventoryHolder {
        final int pagina;
        final Map<Integer, Long> ranuraAApuesta = new HashMap<>();
        Inventory inv;
        Vista(int pagina) { this.pagina = pagina; }
        @Override public Inventory getInventory() { return inv; }
    }

    private final CoinflipPlugin modulo;
    private final EntradaChat chat;

    public MenuCoinflip(CoinflipPlugin modulo, EntradaChat chat) {
        this.modulo = modulo;
        this.chat = chat;
    }

    // ------------------------------------------------------------- construir

    public void abrir(Player jugador, int pagina) {
        Mesa mesa = modulo.mesa();
        List<Apuesta> lista = mesa.visiblesPara(jugador.getUniqueId());
        int paginas = Math.max(1, (int) Math.ceil(lista.size() / (double) POR_PAGINA));
        if (pagina < 0) pagina = 0;
        if (pagina >= paginas) pagina = paginas - 1;

        Vista vista = new Vista(pagina);
        Inventory inv = Bukkit.createInventory(vista, TAM,
                modulo.textos().de("titulo-menu", "{sin-prefijo}&x&0&0&8&3&F&D&lCOINFLIP &8| &x&D&7&F&3&F&FMesas abiertas"));
        vista.inv = inv;

        int desde = pagina * POR_PAGINA;
        List<Apuesta> enPagina = lista.subList(desde, Math.min(lista.size(), desde + POR_PAGINA));
        colocar(inv, vista, enPagina, jugador);

        if (pagina > 0) {
            inv.setItem(RANURA_ANTERIOR, pieza(Material.ARROW,
                    Estilo.texto("Pagina anterior", Estilo.CLARO), List.of()));
        }
        if (pagina < paginas - 1) {
            inv.setItem(RANURA_SIGUIENTE, pieza(Material.ARROW,
                    Estilo.texto("Pagina siguiente", Estilo.CLARO), List.of()));
        }

        inv.setItem(RANURA_CREAR, pieza(Material.EMERALD,
                Estilo.texto("Poner una apuesta", Estilo.ACCION_COMPRA),
                List.of(Estilo.texto("Entre " + Estilo.dinero(mesa.minima())
                                + " y " + Estilo.dinero(mesa.maxima()), Estilo.APAGADO),
                        Estilo.vacio(),
                        Estilo.accion("Te pide la cantidad por el chat", Estilo.ACCION_COMPRA))));

        inv.setItem(RANURA_SALDO, saldo(jugador));

        int mias = mesa.deJugador(jugador.getUniqueId()).size();
        inv.setItem(RANURA_MIAS, pieza(Material.BARRIER,
                Estilo.texto("Retirar mis apuestas", Estilo.VENTA),
                mias == 0
                        ? List.of(Estilo.texto("No tienes ninguna puesta", Estilo.APAGADO))
                        : List.of(Estilo.valor("Tienes " + mias + " en la mesa"),
                                  Estilo.vacio(),
                                  Estilo.accion("Click para retirarlas todas", Estilo.ACCION_VENTA))));

        if (lista.isEmpty()) {
            inv.setItem(22, pieza(Material.GRAY_DYE,
                    Estilo.texto("No hay ninguna apuesta", Estilo.APAGADO),
                    List.of(Estilo.texto("Pon tu la primera", Estilo.CLARO))));
        }

        rellenar(inv);
        jugador.openInventory(inv);
    }

    /** Las apuestas centradas en la zona util, igual que las paginas de la tienda. */
    private void colocar(Inventory inv, Vista vista, List<Apuesta> pagina, Player jugador) {
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
                Apuesta a = pagina.get(i);
                inv.setItem(ranura, pintar(a, jugador));
                vista.ranuraAApuesta.put(ranura, a.id());
            }
        }
    }

    private ItemStack pintar(Apuesta a, Player jugador) {
        boolean mia = a.esDe(jugador.getUniqueId());
        double comision = a.cantidad() * 2 * modulo.mesa().comisionPorCiento() / 100.0;
        double premio = a.cantidad() * 2 - comision;

        List<Component> lore = new ArrayList<>();
        lore.add(Estilo.etiqueta("Apuesta", Estilo.COMPRA));
        lore.add(Estilo.valor(Estilo.dinero(a.cantidad())));
        lore.add(Estilo.vacio());
        lore.add(Estilo.etiqueta("Si ganas te llevas", Estilo.VENTA));
        lore.add(Estilo.valor(Estilo.dinero(premio)));
        if (comision > 0) {
            lore.add(Estilo.texto("   la casa se queda "
                    + Estilo.dinero(comision) + " (" + redondo(modulo.mesa().comisionPorCiento()) + "%)",
                    Estilo.APAGADO));
        }
        lore.add(Estilo.vacio());

        if (a.esReto()) {
            lore.add(a.esPara(jugador.getUniqueId())
                    ? Estilo.texto("Te reto a ti", Estilo.VENTA)
                    : Estilo.texto("Reto a " + a.nombreRetado(), Estilo.APAGADO));
        } else {
            lore.add(Estilo.texto("Mesa abierta", Estilo.APAGADO));
        }
        lore.add(Estilo.vacio());
        if (mia) {
            lore.add(Estilo.accion("Click para retirarla", Estilo.ACCION_VENTA));
        } else if (a.tomada()) {
            lore.add(Estilo.accion("La esta cogiendo alguien", Estilo.APAGADO));
        } else {
            lore.add(Estilo.accion("Click para jugar", Estilo.ACCION_COMPRA));
        }

        ItemStack pila = new ItemStack(Material.PLAYER_HEAD);
        if (pila.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(a.creador()));
            meta.displayName(Estilo.texto(a.nombreCreador(), mia ? Estilo.VENTA : Estilo.CLARO)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            pila.setItemMeta(meta);
        }
        return pila;
    }

    private ItemStack saldo(Player jugador) {
        ItemStack pila = new ItemStack(Material.PLAYER_HEAD);
        if (pila.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(jugador);
            meta.displayName(Estilo.texto(jugador.getName(), Estilo.CLARO)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Estilo.etiqueta("Tu dinero", Estilo.VENTA),
                    Estilo.valor(modulo.mesa().lista()
                            ? Estilo.dinero(modulo.mesa().saldo(jugador)) : "...")));
            meta.addItemFlags(ItemFlag.values());
            pila.setItemMeta(meta);
        }
        return pila;
    }

    // ----------------------------------------------------------------- clics

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

        switch (e.getSlot()) {
            case RANURA_ANTERIOR -> { abrir(jugador, vista.pagina - 1); return; }
            case RANURA_SIGUIENTE -> { abrir(jugador, vista.pagina + 1); return; }
            case RANURA_CREAR -> { pedirCantidad(jugador, null); return; }
            case RANURA_MIAS -> { retirarMias(jugador); return; }
            default -> { /* sera una apuesta */ }
        }

        Long id = vista.ranuraAApuesta.get(e.getSlot());
        if (id == null) return;
        Apuesta a = modulo.mesa().de(id);
        if (a == null) { abrir(jugador, vista.pagina); return; }

        if (a.esDe(jugador.getUniqueId())) {
            Mesa.Resultado r = modulo.mesa().cancelar(jugador, a);
            jugador.sendMessage(r.mensaje());
            abrir(jugador, vista.pagina);
            return;
        }
        modulo.jugar(jugador, a);
    }

    private void retirarMias(Player jugador) {
        List<Apuesta> mias = modulo.mesa().deJugador(jugador.getUniqueId());
        if (mias.isEmpty()) {
            modulo.textos().manda(jugador, "no-tienes", "&7No tienes ninguna apuesta puesta.");
            return;
        }
        double vuelto = 0;
        int n = 0;
        for (Apuesta a : mias) {
            Mesa.Resultado r = modulo.mesa().cancelar(jugador, a);
            if (r.ok()) { vuelto += a.cantidad(); n++; }
        }
        modulo.textos().manda(jugador, "retiradas",
                "&fRetiraste &x&D&7&F&3&F&F%cuantas% &fapuestas y se te devolvieron &#4FFF55%total%",
                "%cuantas%", String.valueOf(n), "%total%", Estilo.dinero(vuelto));
        abrir(jugador, 0);
    }

    /**
     * Pide la cantidad por el chat. Si 'retado' no es null, lo que sale es un
     * reto a esa persona y no una mesa abierta.
     */
    public void pedirCantidad(Player jugador, Player retado) {
        if (chat == null) return;
        modulo.textos().manda(jugador, "pide-cantidad",
                "&fEscribe cuanto quieres apostar. &7Escribe cancelar para dejarlo.");
        chat.pedir(jugador, texto -> {
            /* El mismo lector que el comando: 50000, 50.000, 50k, 1.5m. */
            double cantidad = Mesa.leerCantidad(texto);
            if (Double.isNaN(cantidad)) {
                modulo.textos().manda(jugador, "cantidad-mala", "&#FF5C5CEso no es una cantidad&8: &7%texto%",
                        "%texto%", texto);
                abrir(jugador, 0);
                return;
            }
            Mesa.Resultado r = modulo.mesa().crear(jugador, cantidad,
                    retado != null && retado.isOnline() ? retado : null);
            jugador.sendMessage(r.mensaje());
            if (r.ok() && retado != null && retado.isOnline()) avisarDelReto(jugador, retado, cantidad);
            abrir(jugador, 0);
        }, () -> abrir(jugador, 0));
    }

    /** Al retado hay que decirselo: si no, el reto se queda ahi hasta que caduca. */
    public void avisarDelReto(Player quien, Player retado, double cantidad) {
        modulo.textos().manda(retado, "te-retaron",
                "&x&D&7&F&3&F&F%rival% &fte reto por &#4FFF55%cantidad%&f. &7Abrelo con /cf",
                "%rival%", quien.getName(), "%cantidad%", Estilo.dinero(cantidad));
    }

    // --------------------------------------------------------------- adornos

    private static String redondo(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.US, "%.1f", d);
    }

    private void rellenar(Inventory inv) {
        ItemStack panel = pieza(Material.BLACK_STAINED_GLASS_PANE,
                Estilo.legado("&9mc.ederus.com"), List.of());
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack actual = inv.getItem(i);
            if (actual == null || actual.getType().isAir()) inv.setItem(i, panel.clone());
        }
    }

    private static ItemStack pieza(Material material, Component titulo, List<Component> lore) {
        ItemStack pila = new ItemStack(material);
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
