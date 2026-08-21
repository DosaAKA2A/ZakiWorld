package net.ederus.edm.troll;

import net.ederus.edm.comun.Estilo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import java.util.Map;
import java.util.UUID;

/**
 * Elegir a quien y elegir que.
 *
 * Dos pantallas: la lista de conectados y, dentro, el catalogo de bromas de esa
 * persona agrupado por familia. Misma rejilla de 7 columnas que la tienda y el
 * coinflip, para que el servidor parezca uno solo.
 */
public final class MenuTroll implements Listener {

    private static final int TAM = 54;
    private static final int COLUMNAS = 7;
    private static final int FILAS_UTILES = 4;
    private static final int POR_PAGINA = COLUMNAS * FILAS_UTILES;   // 28

    private static final int RANURA_ANTERIOR = 45;
    private static final int RANURA_VOLVER = 49;
    private static final int RANURA_SIGUIENTE = 53;
    private static final int RANURA_DESHACER = 47;
    private static final int RANURA_FAMILIA = 51;

    /** Marca la ventana y recuerda a quien se le esta haciendo la broma. */
    static final class Vista implements InventoryHolder {
        final UUID victima;                    // null = eligiendo victima
        final Troll.Familia familia;           // null = todas
        final int pagina;
        final Map<Integer, String> ranuraAId = new HashMap<>();
        Inventory inv;

        Vista(UUID victima, Troll.Familia familia, int pagina) {
            this.victima = victima;
            this.familia = familia;
            this.pagina = pagina;
        }

        @Override public Inventory getInventory() { return inv; }
    }

    private final TrollPlugin modulo;

    public MenuTroll(TrollPlugin modulo) {
        this.modulo = modulo;
    }

    // ------------------------------------------------------ elegir victima

    public void abrirJugadores(Player admin, int pagina) {
        List<Player> gente = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(admin)) continue;
            if (modulo.inmune(p)) continue;
            gente.add(p);
        }
        int paginas = Math.max(1, (int) Math.ceil(gente.size() / (double) POR_PAGINA));
        if (pagina < 0) pagina = 0;
        if (pagina >= paginas) pagina = paginas - 1;

        Vista vista = new Vista(null, null, pagina);
        Inventory inv = Bukkit.createInventory(vista, TAM,
                Estilo.legado("&x&0&0&8&3&F&D&lBROMAS &8| &x&D&7&F&3&F&FA quien"));
        vista.inv = inv;

        int desde = pagina * POR_PAGINA;
        List<Player> enPagina = gente.subList(desde, Math.min(gente.size(), desde + POR_PAGINA));
        int i = 0;
        for (int f = 0; f < FILAS_UTILES && i < enPagina.size(); f++) {
            int enEsta = Math.min(COLUMNAS, enPagina.size() - i);
            int hueco = (COLUMNAS - enEsta) / 2;
            for (int col = 0; col < enEsta; col++, i++) {
                int ranura = (1 + f) * 9 + 1 + hueco + col;
                Player p = enPagina.get(i);
                inv.setItem(ranura, cabezaDe(p));
                vista.ranuraAId.put(ranura, p.getUniqueId().toString());
            }
        }

        if (gente.isEmpty()) {
            inv.setItem(22, pieza(Material.GRAY_DYE,
                    Estilo.texto("No hay nadie a quien trollear", Estilo.APAGADO),
                    List.of(Estilo.texto("Estas tu solo", Estilo.CLARO))));
        }
        flechas(inv, pagina, paginas);
        rellenar(inv);
        admin.openInventory(inv);
    }

    private ItemStack cabezaDe(Player p) {
        List<Component> lore = new ArrayList<>();
        lore.add(Estilo.valor(Math.round(p.getHealth()) + " de vida  |  "
                + p.getGameMode().name().toLowerCase(java.util.Locale.ROOT)));
        List<String> activas = modulo.estados().activas(p.getUniqueId());
        if (!activas.isEmpty()) {
            lore.add(Estilo.vacio());
            lore.add(Estilo.etiqueta("Ya tiene encima", Estilo.VENTA));
            for (String clave : activas) {
                Troll t = modulo.catalogo().get(clave.split(":")[0]);
                lore.add(Estilo.valor(t != null ? t.nombre() : clave));
            }
        }
        lore.add(Estilo.vacio());
        lore.add(Estilo.accion("Click para elegir broma", Estilo.ACCION_COMPRA));

        ItemStack pila = new ItemStack(Material.PLAYER_HEAD);
        if (pila.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(p);
            meta.displayName(Estilo.texto(p.getName(), Estilo.CLARO)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            pila.setItemMeta(meta);
        }
        return pila;
    }

    // -------------------------------------------------------- elegir broma

    public void abrirBromas(Player admin, Player victima, Troll.Familia familia, int pagina) {
        List<Troll> lista = new ArrayList<>();
        for (Troll t : modulo.catalogo().values()) {
            if (familia != null && t.familia() != familia) continue;
            if (!modulo.puede(admin, t)) continue;
            lista.add(t);
        }
        int paginas = Math.max(1, (int) Math.ceil(lista.size() / (double) POR_PAGINA));
        if (pagina < 0) pagina = 0;
        if (pagina >= paginas) pagina = paginas - 1;

        Vista vista = new Vista(victima.getUniqueId(), familia, pagina);
        Inventory inv = Bukkit.createInventory(vista, TAM,
                Estilo.legado("&x&0&0&8&3&F&D&lBROMAS &8| &x&D&7&F&3&F&F" + victima.getName()));
        vista.inv = inv;

        int desde = pagina * POR_PAGINA;
        List<Troll> enPagina = lista.subList(desde, Math.min(lista.size(), desde + POR_PAGINA));
        int i = 0;
        for (int f = 0; f < FILAS_UTILES && i < enPagina.size(); f++) {
            int enEsta = Math.min(COLUMNAS, enPagina.size() - i);
            int hueco = (COLUMNAS - enEsta) / 2;
            for (int col = 0; col < enEsta; col++, i++) {
                int ranura = (1 + f) * 9 + 1 + hueco + col;
                Troll t = enPagina.get(i);
                inv.setItem(ranura, pintar(t, admin, victima));
                vista.ranuraAId.put(ranura, t.id());
            }
        }

        inv.setItem(RANURA_DESHACER, pieza(Material.MILK_BUCKET,
                Estilo.texto("Deshacer todo lo suyo", Estilo.ACCION_VENTA),
                List.of(Estilo.texto("Le quita todas las bromas de encima", Estilo.APAGADO))));

        inv.setItem(RANURA_FAMILIA, pieza(Material.CHEST,
                Estilo.texto(familia == null ? "Todas las familias" : familia.nombre(), Estilo.VENTA),
                List.of(Estilo.accion("Click para cambiar de familia", Estilo.ACCION_COMPRA))));

        flechas(inv, pagina, paginas);
        inv.setItem(RANURA_VOLVER, pieza(Material.BARRIER,
                Estilo.texto("Volver", Estilo.CLARO), List.of()));
        rellenar(inv);
        admin.openInventory(inv);
    }

    private ItemStack pintar(Troll t, Player admin, Player victima) {
        List<Component> lore = new ArrayList<>();
        lore.add(Estilo.texto(t.descripcion(), Estilo.APAGADO));
        lore.add(Estilo.vacio());
        if (t.temporal()) {
            lore.add(Estilo.valor("Dura " + t.segundos() + "s y se deshace sola"));
        }
        if (t.destructivo()) {
            lore.add(Estilo.texto("BORRA PROGRESO", NamedTextColor.RED));
            lore.add(Estilo.texto("Pide confirmacion", Estilo.APAGADO));
        }
        lore.add(Estilo.vacio());
        lore.add(Estilo.accion(t.destructivo()
                ? "Click y vuelve a confirmar"
                : "Click para hacersela a " + victima.getName(),
                t.destructivo() ? NamedTextColor.RED : Estilo.ACCION_COMPRA));

        return pieza(t.icono(), Estilo.texto(t.nombre(), t.destructivo() ? NamedTextColor.RED : Estilo.CLARO), lore);
    }

    private void flechas(Inventory inv, int pagina, int paginas) {
        if (pagina > 0) inv.setItem(RANURA_ANTERIOR, pieza(Material.ARROW,
                Estilo.texto("Pagina anterior", Estilo.CLARO), List.of()));
        if (pagina < paginas - 1) inv.setItem(RANURA_SIGUIENTE, pieza(Material.ARROW,
                Estilo.texto("Pagina siguiente", Estilo.CLARO), List.of()));
    }

    // ----------------------------------------------------------------- clics

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Vista) e.setCancelled(true);
    }

    @EventHandler
    public void alPulsar(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Vista vista)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player admin)) return;
        if (e.getClickedInventory() != e.getInventory()) return;

        /* Eligiendo victima */
        if (vista.victima == null) {
            switch (e.getSlot()) {
                case RANURA_ANTERIOR -> { abrirJugadores(admin, vista.pagina - 1); return; }
                case RANURA_SIGUIENTE -> { abrirJugadores(admin, vista.pagina + 1); return; }
                default -> { }
            }
            String id = vista.ranuraAId.get(e.getSlot());
            if (id == null) return;
            Player v = Bukkit.getPlayer(UUID.fromString(id));
            if (v == null) { abrirJugadores(admin, vista.pagina); return; }
            abrirBromas(admin, v, null, 0);
            return;
        }

        /* Eligiendo broma */
        Player victima = Bukkit.getPlayer(vista.victima);
        if (victima == null) { abrirJugadores(admin, 0); return; }

        switch (e.getSlot()) {
            case RANURA_VOLVER -> { abrirJugadores(admin, 0); return; }
            case RANURA_ANTERIOR -> { abrirBromas(admin, victima, vista.familia, vista.pagina - 1); return; }
            case RANURA_SIGUIENTE -> { abrirBromas(admin, victima, vista.familia, vista.pagina + 1); return; }
            case RANURA_DESHACER -> {
                int n = modulo.estados().quitarTodo(victima.getUniqueId());
                modulo.textos().manda(admin, "deshechas", "&fLe quitaste &x&D&7&F&3&F&F%cuantas% &fbromas a %jugador%",
                        "%cuantas%", String.valueOf(n), "%jugador%", victima.getName());
                abrirBromas(admin, victima, vista.familia, vista.pagina);
                return;
            }
            case RANURA_FAMILIA -> {
                abrirBromas(admin, victima, siguienteFamilia(vista.familia), 0);
                return;
            }
            default -> { }
        }

        String id = vista.ranuraAId.get(e.getSlot());
        if (id == null) return;
        Troll t = modulo.catalogo().get(id);
        if (t == null) return;

        modulo.lanzar(admin, victima, t, false);
        abrirBromas(admin, victima, vista.familia, vista.pagina);
    }

    /** Rueda por las familias: todas -> sustos -> movimiento -> ... -> todas. */
    private static Troll.Familia siguienteFamilia(Troll.Familia actual) {
        Troll.Familia[] todas = Troll.Familia.values();
        if (actual == null) return todas[0];
        int i = actual.ordinal() + 1;
        return i >= todas.length ? null : todas[i];
    }

    // --------------------------------------------------------------- adornos

    private void rellenar(Inventory inv) {
        ItemStack panel = pieza(Material.BLACK_STAINED_GLASS_PANE, Estilo.legado("&9mc.ederus.com"), List.of());
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
