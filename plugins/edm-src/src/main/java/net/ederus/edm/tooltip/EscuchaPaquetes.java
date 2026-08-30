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

    /** Todos los caminos por los que un item llega a la pantalla. */
    private static final Map<String, PacketType> TODOS = Map.of(
            "SET_SLOT", PacketType.Play.Server.SET_SLOT,                         // un hueco suelto
            "WINDOW_ITEMS", PacketType.Play.Server.WINDOW_ITEMS,                 // el inventario al abrirlo
            "SET_CURSOR_ITEM", PacketType.Play.Server.SET_CURSOR_ITEM,           // lo que llevas en el raton
            "SET_PLAYER_INVENTORY", PacketType.Play.Server.SET_PLAYER_INVENTORY, // un hueco propio
            "ENTITY_EQUIPMENT", PacketType.Play.Server.ENTITY_EQUIPMENT,         // lo que lleva puesto otro
            "SET_CREATIVE_SLOT", PacketType.Play.Client.SET_CREATIVE_SLOT);      // ENTRANTE: el eco del creativo

    static PacketType[] tipos(List<String> pedidos) {
        if (pedidos == null || pedidos.isEmpty()) {
            return TODOS.values().toArray(new PacketType[0]);
        }
        List<PacketType> fuera = new ArrayList<>();
        for (String s : pedidos) {
            PacketType t = TODOS.get(s);
            if (t != null) {
                fuera.add(t);
            }
        }
        return fuera.isEmpty() ? TODOS.values().toArray(new PacketType[0]) : fuera.toArray(new PacketType[0]);
    }

    EscuchaPaquetes(Plugin plugin, Reescritor reescritor, PacketType... tipos) {
        super(plugin, ListenerPriority.HIGH, tipos);
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

    /*
     * EL ECO DEL CREATIVO, la fuga que sobrevivio al modo clon.
     *
     * Un jugador en modo creativo no le pide al servidor que mueva items: le
     * manda el item ENTERO, NBT incluida, cada vez que lo toca. Como lo que el
     * cliente tiene es nuestra version dibujada, al tocarla nos la devolvia con
     * el bloque dentro y el servidor la guardaba tal cual. Por eso los items se
     * ensuciaban "solos" al manosearlos, aunque la salida ya fuera limpia, y
     * por eso en survival no pasaba.
     *
     * La solucion es limpiar el paquete entrante: si el item que llega trae
     * nuestro bloque, se le quita antes de que el servidor lo guarde.
     */
    @Override
    public void onPacketReceiving(PacketEvent evento) {
        try {
            var mod = evento.getPacket().getItemModifier();
            for (int i = 0; i < mod.size(); i++) {
                ItemStack item = mod.read(i);
                if (item == null || item.isEmpty()) {
                    continue;
                }
                if (Limpiador.limpiarItem(item, this.reescritor.ajustesActuales())) {
                    mod.write(i, item);
                }
            }
        } catch (Throwable t) {
            this.plugin.getLogger().warning("[tooltip] no se pudo limpiar un paquete entrante: " + t);
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
