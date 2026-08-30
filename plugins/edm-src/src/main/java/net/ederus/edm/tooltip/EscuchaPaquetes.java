package net.ederus.edm.tooltip;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.Pair;

/**
 * El unico sitio del modulo que habla con ProtocolLib.
 *
 * Se engancha a los paquetes que llevan items hacia el cliente y les pasa el
 * reescritor. Nada de esto persiste: en cuanto el paquete sale, la copia se
 * tira. El item guardado sigue siendo el mismo byte a byte, que es la razon de
 * hacerlo aqui y no sobre el inventario.
 *
 * Los cinco paquetes son los cinco caminos por los que un item llega a la
 * pantalla; si faltara uno, ese sitio concreto (el cursor, la armadura de otro
 * jugador) seguiria mostrando el texto roto y pareceria que falla al azar.
 */
final class EscuchaPaquetes extends PacketAdapter {

    private final Reescritor reescritor;

    EscuchaPaquetes(Plugin plugin, Reescritor reescritor) {
        super(plugin, ListenerPriority.HIGH,
                PacketType.Play.Server.SET_SLOT,             // un hueco suelto
                PacketType.Play.Server.WINDOW_ITEMS,         // el inventario entero al abrirlo
                PacketType.Play.Server.SET_CURSOR_ITEM,      // lo que llevas en el raton
                PacketType.Play.Server.SET_PLAYER_INVENTORY, // un hueco de tu propio inventario
                PacketType.Play.Server.ENTITY_EQUIPMENT);    // lo que lleva puesto otro
        this.reescritor = reescritor;
    }

    @Override
    public void onPacketSending(PacketEvent evento) {
        try {
            PacketContainer p = evento.getPacket();
            PacketType tipo = evento.getPacketType();

            if (tipo == PacketType.Play.Server.ENTITY_EQUIPMENT) {
                equipo(p);
                return;
            }
            if (tipo == PacketType.Play.Server.WINDOW_ITEMS) {
                lista(p);
            }
            /* WINDOW_ITEMS ademas lleva el item del cursor en el hueco 0 del
             * modificador de items, asi que este bloque tambien le toca. */
            uno(p);
        } catch (Throwable t) {
            /* Un fallo aqui no puede cortar el paquete: el jugador se quedaria
             * con el inventario en blanco. Se deja pasar el original. */
            this.plugin.getLogger().warning("[tooltip] no se pudo reescribir un paquete "
                    + evento.getPacketType() + ": " + t);
        }
    }

    private void uno(PacketContainer p) {
        var mod = p.getItemModifier();
        for (int i = 0; i < mod.size(); i++) {
            ItemStack item = mod.read(i);
            ItemStack nuevo = this.reescritor.reescribir(item);
            if (nuevo != null) {
                mod.write(i, nuevo);
            }
        }
    }

    private void lista(PacketContainer p) {
        var mod = p.getItemListModifier();
        for (int i = 0; i < mod.size(); i++) {
            List<ItemStack> items = mod.read(i);
            if (items == null || items.isEmpty()) {
                continue;
            }
            List<ItemStack> copia = null;
            for (int j = 0; j < items.size(); j++) {
                ItemStack nuevo = this.reescritor.reescribir(items.get(j));
                if (nuevo == null) {
                    continue;
                }
                if (copia == null) {
                    copia = new ArrayList<>(items);
                }
                copia.set(j, nuevo);
            }
            /* Si no habia nada que cambiar no se reescribe la lista: en un
             * inventario normal esto es lo habitual y ahorra el viaje entero. */
            if (copia != null) {
                mod.write(i, copia);
            }
        }
    }

    private void equipo(PacketContainer p) {
        var mod = p.getSlotStackPairLists();
        for (int i = 0; i < mod.size(); i++) {
            List<Pair<EnumWrappers.ItemSlot, ItemStack>> pares = mod.read(i);
            if (pares == null || pares.isEmpty()) {
                continue;
            }
            List<Pair<EnumWrappers.ItemSlot, ItemStack>> copia = null;
            for (int j = 0; j < pares.size(); j++) {
                Pair<EnumWrappers.ItemSlot, ItemStack> par = pares.get(j);
                ItemStack nuevo = this.reescritor.reescribir(par.getSecond());
                if (nuevo == null) {
                    continue;
                }
                if (copia == null) {
                    copia = new ArrayList<>(pares);
                }
                copia.set(j, new Pair<>(par.getFirst(), nuevo));
            }
            if (copia != null) {
                mod.write(i, copia);
            }
        }
    }
}
