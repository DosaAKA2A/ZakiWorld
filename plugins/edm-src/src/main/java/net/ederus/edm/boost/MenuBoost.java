package net.ederus.edm.boost;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.menu.MenuUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * El panel de /boost: tus tres boosts, lo que multiplican y lo que les queda.
 *
 * Es una ventana de solo mirar. No se puede activar nada desde aqui a proposito: los
 * boosts entran por las cajas o por el staff, y un boton que no puedas pulsar nunca
 * es peor que ningun boton. Lo que si hace es contestar a la unica pregunta que se
 * hace un jugador: "¿lo tengo puesto y cuanto me queda?".
 */
final class MenuBoost implements Listener {

    private static final int TAM = 27;
    private static final int[] CASILLAS = {11, 13, 15};
    private static final int CERRAR = 22;

    private final BoostPlugin modulo;

    MenuBoost(BoostPlugin modulo) {
        this.modulo = modulo;
    }

    private static final class Vista implements InventoryHolder {
        private Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    void abrir(Player p) {
        Vista vista = new Vista();
        vista.inv = Bukkit.createInventory(vista, TAM, Estilo.titulo("EDERUS", "Boosts"));
        pintar(vista.inv, p);
        p.openInventory(vista.inv);
    }

    private void pintar(Inventory inv, Player p) {
        for (int i = 0; i < TAM; i++) inv.setItem(i, MenuUtil.pane());

        Tipo[] tipos = Tipo.values();
        for (int i = 0; i < tipos.length && i < CASILLAS.length; i++) {
            inv.setItem(CASILLAS[i], icono(p, tipos[i]));
        }

        inv.setItem(CERRAR, MenuUtil.icon(Material.SPRUCE_DOOR,
                Estilo.texto("Cerrar", NamedTextColor.WHITE),
                List.of(MenuUtil.actionSecondary("Clic para salir")), false));
    }

    /** Un boost: verde y brillando si esta activo, gris apagado si no. */
    private ItemStack icono(Player p, Tipo tipo) {
        Servicio s = modulo.servicio();
        Servicio.Activo activo = s.efectivo(p.getUniqueId(), tipo);
        Servicio.Activo global = s.global(tipo);
        boolean encendido = activo != null;

        List<Component> lore = new ArrayList<>();
        lore.add(Estilo.texto(tipo.explicacion() + ".", MenuUtil.SOFT));
        lore.add(MenuUtil.blank());
        if (!modulo.habilitado(tipo)) {
            lore.add(Estilo.texto("Desactivado en este servidor.", MenuUtil.DIM));
        } else if (encendido) {
            lore.add(MenuUtil.field("Multiplicador", "x" + recorta(activo.multiplicador()), tipo.color()));
            lore.add(MenuUtil.field("Le queda", Servicio.reloj(activo.restanteMs()), tipo.color()));
            if (global != null && (s.personal(p.getUniqueId(), tipo) == null
                    || global.multiplicador() >= s.personal(p.getUniqueId(), tipo).multiplicador())) {
                lore.add(Estilo.texto("Viene de un boost global del servidor.", MenuUtil.SOFT));
            }
        } else {
            lore.add(Estilo.texto("Ahora mismo no lo tienes.", MenuUtil.DIM));
            lore.add(Estilo.texto("Sale de las cajas epica y legendaria.", MenuUtil.SOFT));
        }

        ItemStack item = MenuUtil.icon(encendido ? tipo.icono() : Material.GRAY_DYE,
                Component.text(tipo.nombre(), encendido ? tipo.color() : MenuUtil.DIM)
                        .decoration(TextDecoration.ITALIC, false),
                lore, encendido);
        return item;
    }

    /** x2 en vez de x2.0, pero x1.5 se queda con su decimal. */
    static String recorta(double d) {
        return d == Math.floor(d) ? String.valueOf((int) d) : String.valueOf(d);
    }

    @EventHandler
    public void alPulsar(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Vista)) return;
        e.setCancelled(true);
        if (e.getSlot() == CERRAR && e.getWhoClicked() instanceof Player p) p.closeInventory();
    }
}
