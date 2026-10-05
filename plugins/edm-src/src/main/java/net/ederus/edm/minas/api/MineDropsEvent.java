package net.ederus.edm.minas.api;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 1.80.0 · Alguien pico un bloque de una mina y estos son sus drops, ANTES de entregarlos.
 *
 * La lista es la de verdad, no una copia: lo que se cambie, se quite o se meta aqui es lo que
 * recibe el jugador (en la mochila o al suelo, segun "directo-al-inventario"). Asi un evento de
 * doble mineral multiplica lo que realmente sale, con la fortuna del pico ya aplicada.
 *
 * Sale dentro del BlockBreakEvent en MONITOR, con el bloque todavia puesto, y solo cuando la
 * rotura es definitiva (nadie la cancelo), la mina no se esta rellenando, el jugador tiene
 * acceso y no esta en creativo. Cada objeto de la lista se entrega como copia, asi que repetir
 * la misma instancia para duplicar es valido.
 */
public final class MineDropsEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String mineId;
    private final Location blockLocation;
    private final Material originalBlockType;
    private final List<ItemStack> drops;

    public MineDropsEvent(String mineId, Player player, Location blockLocation, Material originalBlockType,
            List<ItemStack> drops) {
        super(player);
        this.mineId = mineId;
        this.blockLocation = blockLocation;
        this.originalBlockType = originalBlockType;
        this.drops = drops;
    }

    /** El id de la mina, el mismo de /mine tp y de los placeholders. */
    public String getMineId() {
        return mineId;
    }

    /** Una copia: moverla no mueve nada. */
    public Location getBlockLocation() {
        return blockLocation.clone();
    }

    /** El bloque tal como estaba antes de romperse. */
    public Material getOriginalBlockType() {
        return originalBlockType;
    }

    /** Editable. Los nulos y el aire se ignoran al entregar. */
    public List<ItemStack> getDrops() {
        return drops;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
