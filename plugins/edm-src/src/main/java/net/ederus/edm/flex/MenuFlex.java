package net.ederus.edm.flex;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import net.ederus.edm.comun.menu.MenuUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Las dos caras de una vitrina: la del dueño, que la monta, y la de todos los
 * demas, que solo la miran.
 *
 * Ninguna de las dos mueve un objeto de sitio. TODO clic se cancela siempre, sin
 * excepciones ni ramas: montar la vitrina es COPIAR lo que se pincha, y mirarla
 * es mirarla. Ese "siempre" es la garantia de que esto no puede duplicar nada,
 * y por eso no hay un solo camino en el que un ItemStack salga del inventario.
 */
public final class MenuFlex implements Listener {

    /** Las 27 casillas de la vitrina, arriba; abajo el marco y los botones. */
    private static final int TAM = 45;
    private static final int SLOT_AYUDA = 40;
    private static final int SLOT_ANUNCIAR = 44;
    private static final int SLOT_VACIAR = 36;

    private final FlexPlugin plugin;

    public MenuFlex(FlexPlugin plugin) {
        this.plugin = plugin;
    }

    /** La vista: de quien es la vitrina y si quien mira puede tocarla. */
    private static final class Vista implements InventoryHolder {
        private final UUID dueno;
        private final boolean editable;
        private Inventory inv;

        Vista(UUID dueno, boolean editable) {
            this.dueno = dueno;
            this.editable = editable;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    /* ------------------------------------------------------------------ abrir */

    /** La tuya, para montarla. */
    public void abrirPropia(Player p) {
        Vitrina v = plugin.almacen().de(p.getUniqueId(), p.getName());
        Vista vista = new Vista(p.getUniqueId(), true);
        vista.inv = Bukkit.createInventory(vista, TAM,
                Component.text("Tu vitrina", FlexPlugin.MARCA, TextDecoration.BOLD));
        pintar(vista, v);
        p.openInventory(vista.inv);
    }

    /** La de otro, para mirarla. */
    public void abrirAjena(Player quien, Vitrina v) {
        Vista vista = new Vista(v.uuid(), false);
        vista.inv = Bukkit.createInventory(vista, TAM,
                Component.text("Vitrina de " + v.nombre(), FlexPlugin.MARCA, TextDecoration.BOLD));
        pintar(vista, v);
        quien.openInventory(vista.inv);
    }

    private void pintar(Vista vista, Vitrina v) {
        Inventory inv = vista.inv;
        inv.clear();
        for (int i = 0; i < Vitrina.CASILLAS; i++) {
            inv.setItem(i, v.objeto(i));
        }
        for (int i = 27; i < TAM; i++) {
            inv.setItem(i, MenuUtil.pane());
        }

        if (vista.editable) {
            inv.setItem(SLOT_AYUDA, MenuUtil.icon(Material.ITEM_FRAME,
                    MenuUtil.title("Cómo se monta", MenuUtil.GOLD),
                    List.of(
                            MenuUtil.line("Clic en un objeto de tu inventario y se"),
                            MenuUtil.line("COPIA aquí arriba. El tuyo no se mueve:"),
                            MenuUtil.line("esto es un escaparate, no un cofre."),
                            MenuUtil.blank(),
                            MenuUtil.line("Clic en una copia de arriba para quitarla."),
                            MenuUtil.blank(),
                            MenuUtil.field("Puestos", v.cuantos() + " / " + Vitrina.CASILLAS,
                                    NamedTextColor.WHITE)), false));

            inv.setItem(SLOT_VACIAR, MenuUtil.icon(Material.BARRIER,
                    MenuUtil.title("Vaciar la vitrina", NamedTextColor.RED),
                    List.of(
                            MenuUtil.line("Quita las 27 copias de golpe."),
                            MenuUtil.line("No pierdes nada: son copias."),
                            MenuUtil.blank(),
                            MenuUtil.action("Clic para vaciarla")), false));

            inv.setItem(SLOT_ANUNCIAR, MenuUtil.icon(Material.GOAT_HORN,
                    MenuUtil.title("Mostrarla en el chat", MenuUtil.GOLD),
                    List.of(
                            MenuUtil.line("Avisa al servidor de que tu vitrina"),
                            MenuUtil.line("está a la vista, con un botón para abrirla."),
                            MenuUtil.blank(),
                            MenuUtil.action("Clic para mostrarla")), false));
        } else {
            inv.setItem(SLOT_AYUDA, MenuUtil.icon(Material.ITEM_FRAME,
                    MenuUtil.title("Vitrina de " + v.nombre(), FlexPlugin.MARCA),
                    List.of(
                            MenuUtil.field("Objetos", String.valueOf(v.cuantos()), NamedTextColor.WHITE),
                            MenuUtil.blank(),
                            MenuUtil.line("Solo se mira. Lo que hay aquí son copias:"),
                            MenuUtil.line("los objetos de verdad los tiene su dueño.")), false));
        }
    }

    /* ------------------------------------------------------------------ clics */

    @EventHandler
    public void alHacerClic(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Vista v)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        // Sin peros: de una vitrina no sale ni entra un objeto por arrastre, por
        // shift, por tecla de numero ni por ninguna otra via.
        e.setCancelled(true);
        if (!v.editable || !p.getUniqueId().equals(v.dueno)) return;

        Vitrina vitrina = plugin.almacen().de(p.getUniqueId(), p.getName());

        // Clic en el inventario propio: se copia arriba.
        if (e.getClickedInventory() != null && e.getClickedInventory() == p.getInventory()) {
            ItemStack elegido = e.getCurrentItem();
            if (elegido == null || elegido.getType().isAir()) return;
            int hueco = vitrina.hueco();
            if (hueco < 0) {
                p.sendMessage(plugin.aviso("La vitrina está llena: quita algo primero."));
                return;
            }
            vitrina.poner(hueco, elegido);
            plugin.almacen().guardar(vitrina);
            pintar(v, vitrina);
            return;
        }

        int slot = e.getSlot();
        if (slot < Vitrina.CASILLAS) {
            if (vitrina.objeto(slot) == null) return;
            vitrina.quitar(slot);
            plugin.almacen().guardar(vitrina);
            pintar(v, vitrina);
            return;
        }
        if (slot == SLOT_VACIAR) {
            for (int i = 0; i < Vitrina.CASILLAS; i++) vitrina.quitar(i);
            plugin.almacen().guardar(vitrina);
            pintar(v, vitrina);
            p.sendMessage(plugin.aviso("Vitrina vacía."));
            return;
        }
        if (slot == SLOT_ANUNCIAR) {
            p.closeInventory();
            plugin.anunciar(p, vitrina);
        }
    }

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Vista) e.setCancelled(true);
    }

    @EventHandler
    public void alCerrar(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Vista) plugin.almacen().volcarSiHaceFalta();
    }

    /** Las tres primeras piezas, para el hover del anuncio. */
    public List<Component> resumen(Vitrina v) {
        List<Component> out = new ArrayList<>();
        int puestos = 0;
        for (int i = 0; i < Vitrina.CASILLAS && puestos < 3; i++) {
            ItemStack it = v.objeto(i);
            if (it == null) continue;
            puestos++;
            out.add(Component.text("· ", NamedTextColor.DARK_GRAY)
                    .append(net.ederus.edm.anomaly.drops.DropTable.nameOf(it)
                            .colorIfAbsent(NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (v.cuantos() > puestos) {
            out.add(Component.text("y " + (v.cuantos() - puestos) + " más", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return out;
    }
}
