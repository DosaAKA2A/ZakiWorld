package net.ederus.edm.minas.api;

import java.util.UUID;

import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.util.BoundingBox;

/**
 * 1.80.0 · Lo comun a {@link MineResetStartEvent} y {@link MineResetCompleteEvent}.
 *
 * El cycleId es el mismo en el inicio y en el final de un relleno, y distinto en cada relleno,
 * tambien entre reinicios del servidor. Si un inicio no tiene su final, ese relleno no termino
 * (el servidor se apago, se recargo EDM, la mina se borro o cambio de zona, o el mundo se
 * descargo a medias).
 *
 * Los limites son de bloques y van incluidos: un bloque de la mina cumple
 * minX <= x <= maxX, y lo mismo en y y en z.
 */
public abstract class MineResetEvent extends Event {

    private final String mineId;
    private final World world;
    private final int minX, minY, minZ, maxX, maxY, maxZ;
    private final UUID cycleId;
    private final MineResetReason reason;

    protected MineResetEvent(String mineId, World world, int minX, int minY, int minZ, int maxX, int maxY,
            int maxZ, UUID cycleId, MineResetReason reason) {
        this.mineId = mineId;
        this.world = world;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.cycleId = cycleId;
        this.reason = reason;
    }

    public String getMineId() {
        return mineId;
    }

    public World getWorld() {
        return world;
    }

    public int getMinX() {
        return minX;
    }

    public int getMinY() {
        return minY;
    }

    public int getMinZ() {
        return minZ;
    }

    public int getMaxX() {
        return maxX;
    }

    public int getMaxY() {
        return maxY;
    }

    public int getMaxZ() {
        return maxZ;
    }

    /** La caja de la mina en coordenadas de mundo: de la esquina min a la cara exterior del bloque max. */
    public BoundingBox getBoundingBox() {
        return new BoundingBox(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
    }

    public UUID getCycleId() {
        return cycleId;
    }

    public MineResetReason getReason() {
        return reason;
    }
}
