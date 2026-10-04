package net.ederus.edm.dungeonloot;

import java.util.List;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/**
 * 1.78.1 · Una boveda se acaba de abrir: la llave ya esta cobrada y la apertura apuntada (en las
 * de una por jugador, tambien quien). premio es lo que va a soltar, de uno en uno, y se puede
 * tocar: lo que se meta aqui sale por la boveda como el resto. Lo que no sean objetos (dinero,
 * Esencias de Calamity) lo paga quien escucha por su cuenta.
 */
public final class BovedaAbiertaEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player jugador;
    private final Caja caja;
    private final Boveda boveda;
    private final Block bloque;
    private final List<ItemStack> premio;

    public BovedaAbiertaEvent(Player jugador, Caja caja, Boveda boveda, Block bloque, List<ItemStack> premio) {
        this.jugador = jugador;
        this.caja = caja;
        this.boveda = boveda;
        this.bloque = bloque;
        this.premio = premio;
    }

    public Player jugador() {
        return jugador;
    }

    public Caja caja() {
        return caja;
    }

    public Boveda boveda() {
        return boveda;
    }

    public Block bloque() {
        return bloque;
    }

    /** La lista de verdad, no una copia. */
    public List<ItemStack> premio() {
        return premio;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
