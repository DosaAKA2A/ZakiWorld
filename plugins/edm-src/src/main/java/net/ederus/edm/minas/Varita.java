package net.ederus.edm.minas;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.menu.MenuUtil;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * El pico de seleccion: clic izquierdo en un bloque marca una esquina, clic
 * derecho la otra. Con las dos marcadas, "Nueva mina" o "Aplicar zona" del menu
 * usan esa caja. El pico no rompe ni coloca nada, nunca.
 */
public final class Varita implements Listener {

    /** Las dos esquinas de uno; una puede faltar. */
    public record Seleccion(String mundo, int[] a, int[] b) {
        public boolean completa() {
            return a != null && b != null;
        }

        public long volumen() {
            if (!completa()) return 0;
            return (long) (Math.abs(a[0] - b[0]) + 1) * (Math.abs(a[1] - b[1]) + 1) * (Math.abs(a[2] - b[2]) + 1);
        }

        public String medidas() {
            if (!completa()) return "";
            return (Math.abs(a[0] - b[0]) + 1) + "x" + (Math.abs(a[1] - b[1]) + 1) + "x" + (Math.abs(a[2] - b[2]) + 1);
        }
    }

    private final MinasPlugin plugin;
    private final NamespacedKey clave;
    private final Map<UUID, Seleccion> selecciones = new ConcurrentHashMap<>();

    public Varita(MinasPlugin plugin) {
        this.plugin = plugin;
        this.clave = new NamespacedKey(plugin, "pico_minas");
    }

    public ItemStack crear() {
        ItemStack pico = new ItemStack(Material.GOLDEN_PICKAXE);
        ItemMeta meta = pico.getItemMeta();
        meta.displayName(MenuUtil.title("Pico de minas", MinasPlugin.MARCA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Estilo.texto("Clic izquierdo en un bloque: esquina 1.", Estilo.APAGADO),
                Estilo.texto("Clic derecho en otro: esquina 2.", Estilo.APAGADO),
                Estilo.vacio(),
                Estilo.texto("Con las dos, en /mine: Nueva mina o", Estilo.APAGADO),
                Estilo.texto("Aplicar zona en la ficha de una mina.", Estilo.APAGADO),
                Estilo.vacio(),
                Estilo.texto("No rompe ni coloca nada. Tíralo cuando acabes.", MenuUtil.DIM)));
        MenuUtil.hideAll(meta);
        if (Compat.glow() != null) meta.addEnchant(Compat.glow(), 1, true);
        meta.getPersistentDataContainer().set(clave, PersistentDataType.BYTE, (byte) 1);
        pico.setItemMeta(meta);
        return pico;
    }

    public boolean esPico(ItemStack it) {
        return it != null && it.getType() == Material.GOLDEN_PICKAXE && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(clave, PersistentDataType.BYTE);
    }

    public Seleccion de(Player p) {
        return selecciones.get(p.getUniqueId());
    }

    public void olvidar(Player p) {
        selecciones.remove(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void alUsar(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !esPico(e.getItem())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!plugin.esAdmin(p)) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        boolean primera;
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) primera = true;
        else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) primera = false;
        else return;

        int[] punto = {b.getX(), b.getY(), b.getZ()};
        Seleccion antes = selecciones.get(p.getUniqueId());
        String mundo = b.getWorld().getName();
        // Cambiar de mundo a mitad tira la otra esquina: una caja no cruza mundos.
        if (antes == null || !antes.mundo().equals(mundo)) antes = new Seleccion(mundo, null, null);
        Seleccion ahora = primera ? new Seleccion(mundo, punto, antes.b()) : new Seleccion(mundo, antes.a(), punto);
        selecciones.put(p.getUniqueId(), ahora);

        plugin.di(p, primera ? "esquina-1" : "esquina-2", "Esquina %n%: %x% %y% %z%",
                "%n%", primera ? "1" : "2",
                "%x%", String.valueOf(b.getX()), "%y%", String.valueOf(b.getY()), "%z%", String.valueOf(b.getZ()));
        if (ahora.completa()) {
            plugin.di(p, "zona-lista", "Zona lista: %medidas%, %bloques% bloques. Ahora /mine y Nueva mina, o Aplicar zona en una ficha.",
                    "%medidas%", ahora.medidas(), "%bloques%", String.format(java.util.Locale.US, "%,d", ahora.volumen()).replace(',', '.'));
        }
        Compat.sound(b.getWorld(), b.getLocation(), "block.note_block.pling", 0.6f, primera ? 1.2f : 1.6f);
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        selecciones.remove(e.getPlayer().getUniqueId());
    }
}
