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
 * El panel de /boost: tus boosts, lo que multiplican y lo que les queda.
 *
 * Es una ventana de solo mirar. No se puede activar nada desde aqui a proposito: los
 * boosts entran por las cajas, la tienda o el staff, y un boton que no puedas pulsar
 * nunca es peor que ningun boton. Lo que si hace es contestar a la unica pregunta que
 * se hace un jugador: "¿lo tengo puesto y cuanto me queda?".
 *
 * Solo salen los tipos activados en el config, en una fila centrada: con cinco van
 * seguidos en el centro, con cuatro o menos van con un hueco entre medias. Un tipo
 * activado cuyo plugin no esta (AuraSkills, PremioPescao...) sale gris y lo dice.
 */
final class MenuBoost implements Listener {

    private static final int TAM = 27;
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

    /** Las casillas de la fila del medio para n iconos, centradas. */
    static int[] casillas(int n) {
        return switch (n) {
            case 0 -> new int[0];
            case 1 -> new int[] {13};
            case 2 -> new int[] {11, 15};
            case 3 -> new int[] {11, 13, 15};
            case 4 -> new int[] {10, 12, 14, 16};
            case 5 -> new int[] {11, 12, 13, 14, 15};
            default -> {
                int m = Math.min(n, 9);
                int[] out = new int[m];
                int desde = 9 + (9 - m) / 2;
                for (int i = 0; i < m; i++) out[i] = desde + i;
                yield out;
            }
        };
    }

    void abrir(Player p) {
        Vista vista = new Vista();
        vista.inv = Bukkit.createInventory(vista, TAM, Estilo.titulo("EDERUS", "Boosts"));
        pintar(vista.inv, p);
        p.openInventory(vista.inv);
    }

    private void pintar(Inventory inv, Player p) {
        for (int i = 0; i < TAM; i++) inv.setItem(i, MenuUtil.pane());

        List<Tipo> tipos = modulo.tipos();
        int[] huecos = casillas(tipos.size());
        for (int i = 0; i < huecos.length; i++) inv.setItem(huecos[i], icono(p, tipos.get(i)));

        inv.setItem(CERRAR, MenuUtil.icon(Material.SPRUCE_DOOR,
                Estilo.texto("Cerrar", NamedTextColor.WHITE),
                List.of(MenuUtil.actionSecondary("Clic para salir")), false));
    }

    /** Un boost: en su color y brillando si esta activo, gris apagado si no. */
    private ItemStack icono(Player p, Tipo tipo) {
        Servicio s = modulo.servicio();
        boolean disponible = modulo.disponible(tipo);
        Servicio.Activo activo = disponible ? s.efectivo(p.getUniqueId(), tipo) : null;
        Servicio.Activo global = disponible ? s.global(tipo) : null;
        boolean encendido = activo != null;
        boolean excluido = modulo.mundoExcluido(p.getWorld().getName(), tipo);

        List<Component> lore = new ArrayList<>();
        lore.add(Estilo.texto(tipo.explicacion() + ".", MenuUtil.SOFT));
        lore.add(MenuUtil.blank());
        if (!disponible) {
            lore.add(modulo.textos().linea("menu-no-disponible",
                    "&8No disponible en este servidor.", "%plugin%", modulo.faltaPara(tipo)));
        } else if (encendido) {
            double mult = BoostPlugin.recortar(activo.multiplicador(), modulo.maximo(tipo));
            lore.add(MenuUtil.field("Multiplicador", "x" + recorta(mult), tipo.color()));
            lore.add(MenuUtil.field("Le queda", Servicio.reloj(activo.restanteMs()), tipo.color()));
            Servicio.Activo personal = s.personal(p.getUniqueId(), tipo);
            if (global != null && (personal == null || global.multiplicador() >= personal.multiplicador())) {
                lore.add(modulo.textos().linea("menu-global", "&7Viene de un boost global del servidor."));
            }
            if (excluido) {
                lore.add(modulo.textos().linea("menu-mundo-excluido", "&#FFB627En este mundo no hace efecto."));
            }
        } else {
            lore.add(modulo.textos().linea("menu-sin-boost", "&8Ahora mismo no lo tienes."));
            lore.add(modulo.textos().linea("menu-como-conseguir", "&7Sale de las cajas y de la tienda."));
        }

        Material material = encendido ? tipo.icono() : Material.GRAY_DYE;
        return MenuUtil.icon(material,
                Component.text(tipo.nombre(), encendido ? tipo.color() : MenuUtil.DIM)
                        .decoration(TextDecoration.ITALIC, false),
                lore, encendido);
    }

    /** x2 en vez de x2.0, pero x1.5 se queda con su decimal. */
    static String recorta(double d) {
        if (d == Math.floor(d)) return String.valueOf((long) d);
        return String.valueOf(Math.round(d * 100) / 100.0);
    }

    @EventHandler
    public void alPulsar(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Vista)) return;
        e.setCancelled(true);
        if (e.getSlot() == CERRAR && e.getWhoClicked() instanceof Player p) p.closeInventory();
    }
}
