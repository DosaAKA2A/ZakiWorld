package net.ederus.calamity.hardcore;

import net.Indyuce.mmoitems.api.Type;
import net.Indyuce.mmoitems.api.event.MMOItemReforgeFinishEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Lote de gemas (Calamity 1.12.3) · Que una pieza de Calamity siga ligada a su dueno cuando MMOItems
 * la actualiza.
 *
 * Para que las piezas que ya tienen los jugadores reciban su hueco de gema, en el servidor se sube el
 * revision-id de su plantilla y MMOItems las rehace al entrar, al recogerlas o al tocarlas
 * (item-revision.disable-on). Ese rehacer (MMOItemReforger) construye un ItemStack NUEVO con el
 * ItemStackBuilder (leido con javap en 6.10.1): conserva gemas, mejoras y encantamientos, pero no
 * las marcas de otros plugins del PersistentDataContainer. Sin esto, lethal_world:ligado se perderia
 * y la pieza se podria vender o cambiar.
 *
 * Al acabar (MMOItemReforgeFinishEvent, que deja cambiar el resultado) se copian a la pieza nueva
 * las marcas de la vieja con EngarceMmo.conservarMarcas, lo mismo que hace el Engarzador al
 * engarzar o quitar, y su linea "Ligado a X" del lore si la nueva no la trae (Ligado.copiarLineaLigado).
 * Solo en los tipos de Calamity (piezas y gemas): lo demas de MMOItems no se toca.
 * Se apaga con engarzador.conservar-marcas-al-actualizar: false.
 *
 * Importa MMOItems: como EngarceMmo, solo se crea si PuenteMmo.disponible() (MenuEngarzador).
 */
final class ActualizacionMmo implements Listener {

    private final Hardcore hc;
    /** Los tipos de MMOItems de Calamity, en mayusculas (MenuEngarzador.tiposPieza y tiposGema). */
    private final Supplier<Set<String>> tipos;

    ActualizacionMmo(Hardcore hc, Supplier<Set<String>> tipos) {
        this.hc = hc;
        this.tipos = tipos;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
    }

    void parar() {
        HandlerList.unregisterAll(this);
    }

    /** Si esta encendido (engarzador.conservar-marcas-al-actualizar, true de serie). */
    boolean activo() {
        return hc.cfg().getBoolean("engarzador.conservar-marcas-al-actualizar", true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alActualizar(MMOItemReforgeFinishEvent e) {
        hc.seguro("engarzador", () -> {
            if (!activo()) return;
            Type t = e.getType();
            if (t == null || !tipos.get().contains(t.getId().toUpperCase(Locale.ROOT))) return;
            ItemStack vieja = e.getReforger() == null ? null : e.getReforger().getStack();
            ItemStack nueva = e.getFinishedItem();
            if (vieja == null || nueva == null || nueva.getType().isAir()) return;
            UUID dueno = Ligado.duenoDe(vieja);
            EngarceMmo.conservarMarcas(vieja, nueva);
            Ligado.copiarLineaLigado(vieja, nueva);
            e.setFinishedItem(nueva);
            // Una vez por pieza y revision: que quede constancia de que el ligado ha sobrevivido.
            if (dueno != null) {
                hc.plugin().bitacora().anotar("actualizacion-mmo", t.getId() + "." + e.getID(), dueno.toString(),
                        dueno.equals(Ligado.duenoDe(nueva)) ? "sigue-ligado" : "ligado-perdido");
            }
        });
    }
}
