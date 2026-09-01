package net.ederus.edm.goditems.mmo;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.Indyuce.mmoitems.api.event.ItemBuildEvent;
import net.Indyuce.mmoitems.api.event.MMOItemsReloadEvent;
import net.Indyuce.mmoitems.api.event.inventory.ItemEquipEvent;
import net.Indyuce.mmoitems.api.event.inventory.ItemUnequipEvent;
import net.ederus.edm.comun.Estilo;
import net.ederus.edm.goditems.Activador;
import net.ederus.edm.goditems.GodItem;
import net.ederus.edm.goditems.GodItemsPlugin;
import net.kyori.adventure.text.Component;

/**
 * Los eventos de MMOItems.
 *
 * Esta clase SOLO se registra si MMOItems esta instalado: referencia sus tipos
 * y sin el jar delante ni siquiera cargaria. Es el mismo cuidado que ya se tuvo
 * con Quests y ProtocolLib, que tumbaron el nucleo entero al arrancar sin ellos.
 */
public final class EscuchasMmo implements Listener {

    private final GodItemsPlugin modulo;
    private final Puente puente;
    private final Conjuntos conjuntos;

    public EscuchasMmo(GodItemsPlugin modulo, Puente puente, Conjuntos conjuntos) {
        this.modulo = modulo;
        this.puente = puente;
        this.conjuntos = conjuntos;
    }

    /**
     * AQUI es donde GodItems deja de ser un plugin aparte.
     *
     * ItemBuildEvent salta cada vez que MMOItems CONSTRUYE un item: /mi browse,
     * /mi give, un drop, una mesa de crafteo o una revision de plantilla. Al
     * engancharse aqui, el item sale ya con lo que GodItems le haya añadido sin
     * que GodItems tenga que entregarlo, que es justo lo que se pedia.
     *
     * Y es ademas el sitio a prueba del Item Revision System: como se reaplica
     * en CADA construccion, incluida la que regenera las copias viejas, nunca
     * hay una version del item sin nuestras lineas.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void alConstruir(ItemBuildEvent e) {
        ItemStack item = e.getItemStack();
        if (item == null || item.getType().isAir()) return;

        String enlace = this.puente.enlaceDe(item);
        if (enlace == null) return;
        int punto = enlace.indexOf('.');
        if (punto <= 0) return;

        String id = this.modulo.registro().porEnlace(
                enlace.substring(0, punto), enlace.substring(punto + 1));
        if (id == null) return;
        GodItem def = this.modulo.registro().porId(id);
        if (def == null || def.loreExtra().isEmpty()) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        for (String linea : def.loreExtra()) lore.add(Estilo.legado(linea));
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    /*
     * Equipar y desequipar por la via de MMOItems.
     *
     * Se usa la suya y no solo PlayerArmorChangeEvent porque MMOItems tiene mas
     * huecos que la armadura de Minecraft (anillos, colgantes, catalizadores) y
     * los de Bukkit no los ven. Los eventos de Bukkit siguen registrados como
     * red de seguridad para los items nativos, que no pasan por aqui.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alEquipar(ItemEquipEvent e) {
        Player j = e.getPlayer();
        ItemStack it = pila(e.getItem());
        GodItem def = this.modulo.identidad().definicionDe(it);
        if (def != null) {
            this.modulo.disparar(j, it, def, Activador.EQUIPAR, e, null, null, null);
        }
        repasarLuego(j);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alDesequipar(ItemUnequipEvent e) {
        Player j = e.getPlayer();
        ItemStack it = pila(e.getItem());
        GodItem def = this.modulo.identidad().definicionDe(it);
        if (def != null) {
            this.modulo.disparar(j, it, def, Activador.DESEQUIPAR, e, null, null, null);
        }
        repasarLuego(j);
    }

    /**
     * El repaso del conjunto va un tick DESPUES a proposito: en el momento del
     * evento el hueco todavia no refleja el cambio, y contar ahi da el numero
     * de antes. Un set de 5 se completaria "a la pieza siguiente".
     */
    private void repasarLuego(Player j) {
        this.modulo.core().getServer().getScheduler().runTaskLater(this.modulo.core(),
                () -> this.conjuntos.repasar(j), 1L);
    }

    /** MMOItems envuelve el item en su EquippedItem; aqui solo interesa la pila. */
    private static ItemStack pila(Object equipado) {
        if (equipado == null) return null;
        try {
            var m = equipado.getClass().getMethod("getItem");
            Object r = m.invoke(equipado);
            if (r instanceof ItemStack is) return is;
            /* En algunas versiones devuelve su NBTItem; ese tiene getItem(). */
            if (r != null) {
                var m2 = r.getClass().getMethod("getItem");
                Object r2 = m2.invoke(r);
                if (r2 instanceof ItemStack is2) return is2;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Tras un /mi reload las plantillas y los sets cambian de tamaño. Se vacian
     * las caches y se vuelven a leer los YAML de GodItems, para que las dos
     * mitades queden en la misma version del mundo.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void alRecargarMmo(MMOItemsReloadEvent e) {
        this.conjuntos.limpiar();
        this.modulo.getLogger().info("[GodItems] MMOItems recargado: "
                + this.modulo.recargar());
    }
}
