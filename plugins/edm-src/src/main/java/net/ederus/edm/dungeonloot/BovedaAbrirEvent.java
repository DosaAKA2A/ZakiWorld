package net.ederus.edm.dungeonloot;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import net.kyori.adventure.text.Component;

/**
 * 1.78.1 · Alguien va a abrir una boveda: ya lleva la llave buena en la mano, pero todavia no se
 * le ha cobrado. Cancelarlo deja la llave donde estaba y suena el "no" de la boveda; si se pone
 * un motivo, se le dice al jugador tal cual (sin prefijo: lo pone quien lo escribe).
 *
 * Lo usa Calamity para la Boveda Caida (solo el primero) y para no abrirlas en la zona spawn.
 */
public final class BovedaAbrirEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player jugador;
    private final Caja caja;
    private final Boveda boveda;
    private final Block bloque;
    private boolean cancelado;
    private Component motivo;

    public BovedaAbrirEvent(Player jugador, Caja caja, Boveda boveda, Block bloque) {
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

    /** Lo que se le dice al jugador si se cancela; null = solo el sonido. */
    public Component motivo() {
        return motivo;
    }

    public void motivo(Component motivo) {
        this.motivo = motivo;
    }

    @Override
    public boolean isCancelled() {
        return cancelado;
    }

    @Override
    public void setCancelled(boolean cancelar) {
        this.cancelado = cancelar;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
