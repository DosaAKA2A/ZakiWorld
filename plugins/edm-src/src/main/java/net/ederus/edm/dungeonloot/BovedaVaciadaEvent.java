package net.ederus.edm.dungeonloot;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 1.78.1 · Una boveda termino de soltar todo y volvio a cerrarse. Es el momento de quitarla si
 * era de un solo uso (la Boveda Caida de Calamity): antes, lo que faltara por salir se perderia.
 */
public final class BovedaVaciadaEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player jugador;
    private final Caja caja;
    private final Boveda boveda;
    private final Block bloque;

    public BovedaVaciadaEvent(Player jugador, Caja caja, Boveda boveda, Block bloque) {
        this.jugador = jugador;
        this.caja = caja;
        this.boveda = boveda;
        this.bloque = bloque;
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

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
