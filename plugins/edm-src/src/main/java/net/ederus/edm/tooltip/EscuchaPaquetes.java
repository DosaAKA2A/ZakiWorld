package net.ederus.edm.tooltip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * reescritor. Los cinco son los cinco caminos por los que un item llega a la
 * pantalla; si faltara uno, ese sitio concreto (el cursor, la armadura de otro
 * jugador) seguiria mostrando el texto roto y pareceria que falla al azar.
 *
 * ATENCION, ESTO TIENE HISTORIA. La primera version escribia directamente sobre
 * el paquete y el bloque acabo metido DENTRO de los items guardados: lo que
 * ProtocolLib entrega esta pegado al ItemStack vivo del inventario y escribir
 * ahi cambia el item de verdad. Se comprobo dos veces con la NBT de un item
 * recien creado por consola.
 *
 * Por eso el modo por defecto es "clon": el paquete se duplica entero antes de
 * tocar nada y lo que sale hacia el cliente es el duplicado. El modo "directo"
 * es el de antes y se deja solo para comparar; "lectura" calcula todo y no
 * escribe nada, que es como se aisla si el problema esta en leer o en escribir.
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

    /** Lo que habria que cambiar, calculado leyendo y sin haber escrito todavia. */
    private record Cambios(
            Map<Integer, ItemStack> sueltos,
            Map<Integer, List<ItemStack>> listas,
            Map<Integer, List<Pair<EnumWrappers.ItemSlot, ItemStack>>> equipos) {

        boolean vacio() {
            return this.sueltos.isEmpty() && this.listas.isEmpty() && this.equipos.isEmpty();
        }
    }

    @Override
    public void onPacketSending(PacketEvent evento) {
        try {
            PacketContainer p = evento.getPacket();
            Cambios cambios = calcular(p, evento.getPacketType());
            if (cambios.vacio()) {
                return;
            }

            String modo = this.reescritor.modo();
            if (modo.equals("lectura")) {
                return;
            }

            /*
             * El duplicado es la pieza clave: se escribe sobre el, no sobre el
             * paquete que trae los items vivos del inventario.
             */
            PacketContainer destino = modo.equals("directo") ? p : p.deepClone();
            aplicar(destino, cambios);
            if (destino != p) {
                evento.setPacket(destino);
            }
        } catch (Throwable t) {
            /* Un fallo aqui no puede cortar el paquete: el jugador se quedaria
             * con el inventario en blanco. Se deja pasar el original. */
            this.plugin.getLogger().warning("[tooltip] no se pudo reescribir un paquete "
                    + evento.getPacketType() + ": " + t);
        }
    }

    private Cambios calcular(PacketContainer p, PacketType tipo) {
        Map<Integer, ItemStack> sueltos = new LinkedHashMap<>();
        Map<Integer, List<ItemStack>> listas = new LinkedHashMap<>();
        Map<Integer, List<Pair<EnumWrappers.ItemSlot, ItemStack>>> equipos = new LinkedHashMap<>();

        if (tipo == PacketType.Play.Server.ENTITY_EQUIPMENT) {
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
                    equipos.put(i, copia);
                }
            }
            return new Cambios(sueltos, listas, equipos);
        }

        if (tipo == PacketType.Play.Server.WINDOW_ITEMS) {
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
                if (copia != null) {
                    listas.put(i, copia);
                }
            }
        }

        /* WINDOW_ITEMS ademas lleva el item del cursor como campo suelto. */
        var mod = p.getItemModifier();
        for (int i = 0; i < mod.size(); i++) {
            ItemStack nuevo = this.reescritor.reescribir(mod.read(i));
            if (nuevo != null) {
                sueltos.put(i, nuevo);
            }
        }
        return new Cambios(sueltos, listas, equipos);
    }

    private static void aplicar(PacketContainer destino, Cambios c) {
        if (!c.sueltos().isEmpty()) {
            var mod = destino.getItemModifier();
            c.sueltos().forEach(mod::write);
        }
        if (!c.listas().isEmpty()) {
            var mod = destino.getItemListModifier();
            c.listas().forEach(mod::write);
        }
        if (!c.equipos().isEmpty()) {
            var mod = destino.getSlotStackPairLists();
            c.equipos().forEach(mod::write);
        }
    }
}
