package net.ederus.edm.minas.api;

import java.util.UUID;

import org.bukkit.World;
import org.bukkit.event.HandlerList;

/**
 * 1.80.0 · Una mina empieza a rellenarse. Ya se saco a quien estaba dentro y todavia no se ha
 * puesto ningun bloque. Desde aqui y hasta su {@link MineResetCompleteEvent} no se puede picar.
 */
public final class MineResetStartEvent extends MineResetEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public MineResetStartEvent(String mineId, World world, int minX, int minY, int minZ, int maxX, int maxY,
            int maxZ, UUID cycleId, MineResetReason reason) {
        super(mineId, world, minX, minY, minZ, maxX, maxY, maxZ, cycleId, reason);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
