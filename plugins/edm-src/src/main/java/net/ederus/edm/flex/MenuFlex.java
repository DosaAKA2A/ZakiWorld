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

import net.ederus.edm.comun.Estilo;
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

    private static final int TAM = 54;

    /** Las casillas de la vitrina: el mismo cuerpo centrado que usa Anomaly. */
    private static final int[] CASILLAS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};

    private static final int SLOT_VACIAR = 48;
    private static final int SLOT_AYUDA = 49;
    private static final int SLOT_ANUNCIAR = 50;

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
        vista.inv = Bukkit.createInventory(vista, TAM, titulo("La tuya"));
        pintar(vista, v);
        p.openInventory(vista.inv);
    }

    /** La de otro, para mirarla. */
    public void abrirAjena(Player quien, Vitrina v) {
        Vista vista = new Vista(v.uuid(), false);
        vista.inv = Bukkit.createInventory(vista, TAM, titulo(v.nombre()));
        pintar(vista, v);
        quien.openInventory(vista.inv);
    }

    /**
     * El titulo, con la misma forma que los de Anomaly y las bovedas: el rombo, el
     * nombre del panel y la seccion detras. La palabra VITRINA va en degradado, que
     * es la firma del modulo, y es lo unico con peso de toda la ventana.
     */
    private Component titulo(String seccion) {
        return Component.text("✦ ", FlexPlugin.MARCA)
                .append(Estilo.degradado("VITRINA", FlexPlugin.MAGENTA, FlexPlugin.CARMESI)
                        .decoration(TextDecoration.BOLD, true))
                .append(Estilo.texto("  " + seccion, FlexPlugin.MARCA));
    }

    private void pintar(Vista vista, Vitrina v) {
        Inventory inv = vista.inv;
        inv.clear();
        // Todo cristal y despues se abren los huecos: asi la vitrina queda
        // enmarcada sin tener que enumerar el marco casilla a casilla.
        for (int i = 0; i < TAM; i++) {
            inv.setItem(i, MenuUtil.pane());
        }
        for (int i = 0; i < CASILLAS.length; i++) {
            inv.setItem(CASILLAS[i], v.objeto(i));
        }

        if (vista.editable) {
            inv.setItem(SLOT_AYUDA, MenuUtil.icon(Material.ITEM_FRAME,
                    MenuUtil.title("Cómo se monta", FlexPlugin.MARCA),
                    List.of(
                            Estilo.linea("Puestos", v.cuantos() + " de " + Vitrina.CASILLAS,
                                    Estilo.CLARO),
                            Estilo.vacio(),
                            Estilo.texto("Clic en un objeto de tu inventario y se", Estilo.APAGADO),
                            Estilo.texto("copia aquí arriba. El tuyo no se mueve:", Estilo.APAGADO),
                            Estilo.texto("esto es un escaparate, no un cofre.", Estilo.APAGADO),
                            Estilo.vacio(),
                            Estilo.texto("Clic en una copia para quitarla.", Estilo.APAGADO)), false));

            inv.setItem(SLOT_VACIAR, MenuUtil.icon(Material.BARRIER,
                    MenuUtil.title("Vaciar la vitrina", NamedTextColor.RED),
                    List.of(
                            Estilo.texto("Quita las copias de golpe. No pierdes", Estilo.APAGADO),
                            Estilo.texto("nada: los objetos son tuyos y siguen", Estilo.APAGADO),
                            Estilo.texto("en tu inventario.", Estilo.APAGADO),
                            Estilo.vacio(),
                            Estilo.accion("Clic para vaciarla", NamedTextColor.RED)), false));

            inv.setItem(SLOT_ANUNCIAR, MenuUtil.icon(Material.GOAT_HORN,
                    MenuUtil.title("Mostrarla en el chat", FlexPlugin.MARCA),
                    List.of(
                            Estilo.texto("Avisa al servidor de que tu vitrina está", Estilo.APAGADO),
                            Estilo.texto("a la vista, con un botón para abrirla.", Estilo.APAGADO),
                            Estilo.vacio(),
                            Estilo.accion("Clic para mostrarla", FlexPlugin.MARCA)), false));
        } else {
            // La ficha del dueno: quien es y poco mas. Explicar aqui que son copias
            // sobra, porque en una vitrina ajena no hay nada que se pueda tocar.
            List<Component> ficha = new ArrayList<>();
            ficha.add(Estilo.linea("Objetos", String.valueOf(v.cuantos()), Estilo.CLARO));
            if (v.actualizada() > 0) {
                ficha.add(Estilo.linea("Actualizada", cuando(v.actualizada()), Estilo.APAGADO));
            }
            inv.setItem(SLOT_AYUDA, MenuUtil.icon(Material.END_CRYSTAL,
                    MenuUtil.title(v.nombre(), FlexPlugin.MARCA), ficha, true));
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
                plugin.di(p, "llena", "La vitrina está llena. Quita algo antes de poner más.");
                return;
            }
            vitrina.poner(hueco, elegido);
            plugin.almacen().guardar(vitrina);
            pintar(v, vitrina);
            return;
        }

        int slot = e.getSlot();
        int casilla = indiceDe(slot);
        if (casilla >= 0) {
            if (vitrina.objeto(casilla) == null) return;
            vitrina.quitar(casilla);
            plugin.almacen().guardar(vitrina);
            pintar(v, vitrina);
            return;
        }
        if (slot == SLOT_VACIAR) {
            for (int i = 0; i < Vitrina.CASILLAS; i++) vitrina.quitar(i);
            plugin.almacen().guardar(vitrina);
            pintar(v, vitrina);
            plugin.di(p, "vaciada", "Vitrina vacía.");
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

    /** Hace cuanto se toco la vitrina, en palabras: "hace 3 h", "hace 2 dias". */
    private static String cuando(long sello) {
        long minutos = Math.max(0, (System.currentTimeMillis() - sello) / 60000);
        if (minutos < 1) return "hace un momento";
        if (minutos < 60) return "hace " + minutos + " min";
        long horas = minutos / 60;
        if (horas < 24) return "hace " + horas + " h";
        long dias = horas / 24;
        return dias == 1 ? "hace un día" : "hace " + dias + " días";
    }

    /** Que casilla de la vitrina es ese hueco del menu, o -1 si es marco. */
    private static int indiceDe(int slot) {
        for (int i = 0; i < CASILLAS.length; i++) {
            if (CASILLAS[i] == slot) return i;
        }
        return -1;
    }

    /** Las tres primeras piezas, para el hover del anuncio. */
    public List<Component> resumen(Vitrina v) {
        List<Component> out = new ArrayList<>();
        int puestos = 0;
        for (int i = 0; i < Vitrina.CASILLAS && puestos < 3; i++) {
            ItemStack it = v.objeto(i);
            if (it == null) continue;
            puestos++;
            out.add(Estilo.texto(" " + Estilo.FLECHA + " ", Estilo.APAGADO)
                    .append(net.ederus.edm.anomaly.drops.DropTable.nameOf(it)
                            .colorIfAbsent(Estilo.CLARO)
                            .decoration(TextDecoration.ITALIC, false)));
        }
        if (v.cuantos() > puestos) {
            out.add(Estilo.texto("   y " + (v.cuantos() - puestos) + " más", Estilo.APAGADO));
        }
        return out;
    }
}
