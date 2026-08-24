package net.ederus.edm.tienda;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Se lanza DESPUES de cada compra o venta ya cobrada y entregada.
 *
 * Existe por las misiones. La mision i-quests-14 ("Debut en el mercado") no
 * tiene tareas propias: la completaba ConditionalEvents escuchando el
 * PostTransactionEvent de EconomyShopGUI, y al apagar ese plugin
 * (EconomyShopGUI-Premium-6.0.3.jarx) el gancho dejo de registrarse
 * ("Class ... doesn't exists for custom event i-quests-14") y la mision se
 * quedo imposible de terminar.
 *
 * Los nombres de los metodos y de los tipos son los de aquel evento A
 * PROPOSITO: asi el quests.yml de ConditionalEvents solo cambia la linea del
 * 'event' y sus condiciones siguen valiendo tal cual.
 *
 * Es informativo y NO se puede cancelar: cuando llega, el dinero y los items ya
 * se movieron. Si algun dia hace falta impedir una operacion, el sitio es
 * Motor, antes de tocar el banco.
 */
public final class TransaccionTiendaEvent extends Event {

    /** Tipos con los nombres de EconomyShopGUI, para no reescribir las condiciones. */
    public enum Tipo {
        BUY_SCREEN, BUY_COMMAND, SELL_SCREEN, SELL_COMMAND, SELL_ALL_COMMAND
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player jugador;
    private final Tipo tipo;
    private final Material material;
    private final String clave;
    private final String nombre;
    private final int cantidad;
    private final double total;

    public TransaccionTiendaEvent(Player jugador, Tipo tipo, Material material,
                                  String clave, String nombre, int cantidad, double total) {
        this.jugador = jugador;
        this.tipo = tipo;
        this.material = material;
        this.clave = clave;
        this.nombre = nombre;
        this.cantidad = cantidad;
        this.total = total;
    }

    public Player getPlayer() { return jugador; }

    /** String y no el enum: ConditionalEvents guarda lo que salga de aqui tal cual. */
    public String getTransactionType() { return tipo.name(); }

    public Tipo tipo() { return tipo; }

    /** Siempre SUCCESS: el evento no se lanza si la operacion no salio bien. */
    public String getTransactionResult() { return "SUCCESS"; }

    /** Unidades movidas. En un /sellall es el total de piezas de toda la pasada. */
    public int getAmount() { return cantidad; }

    /** Lo cobrado o lo pagado, ya con ofertas, demandas y precio dinamico dentro. */
    public double getPrice() { return total; }

    /** El material, o VARIOS en un /sellall, que mezcla tipos en una sola operacion. */
    public String getMaterial() { return material == null ? "VARIOS" : material.name(); }

    /** La clave del articulo en precios.yml; VARIOS en un /sellall. */
    public String getShopItem() { return clave; }

    /** El nombre en espanol, el mismo que lee el jugador en el chat. */
    public String getItemName() { return nombre; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
