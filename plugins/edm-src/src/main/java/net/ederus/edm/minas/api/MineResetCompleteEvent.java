package net.ederus.edm.minas.api;

import java.util.UUID;

import org.bukkit.World;
import org.bukkit.event.HandlerList;

/**
 * 1.80.0 · La mina termino de rellenarse entera: el ultimo bloque ya esta puesto y se puede
 * volver a picar. Lleva el mismo cycleId que su {@link MineResetStartEvent}.
 *
 * Solo sale cuando el relleno llega al final. Si se corta a medias no sale nunca.
 */
public final class MineResetCompleteEvent extends MineResetEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final long blocksPlaced;
    private final long durationMillis;

    public MineResetCompleteEvent(String mineId, World world, int minX, int minY, int minZ, int maxX, int maxY,
            int maxZ, UUID cycleId, MineResetReason reason, long blocksPlaced, long durationMillis) {
        super(mineId, world, minX, minY, minZ, maxX, maxY, maxZ, cycleId, reason);
        this.blocksPlaced = blocksPlaced;
        this.durationMillis = durationMillis;
    }

    /** Bloques puestos en este relleno: el volumen entero de la mina. */
    public long getBlocksPlaced() {
        return blocksPlaced;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
