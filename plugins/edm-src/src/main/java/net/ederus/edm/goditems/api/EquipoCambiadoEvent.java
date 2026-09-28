package net.ederus.edm.goditems.api;

import java.util.Map;
import java.util.Set;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Salta (en el hilo principal) cuando cambia lo que cuenta del equipo de un
 * jugador: una pieza que entra o sale, o un escalon de set que se gana o se
 * pierde. Trae las claves de dominio de antes y de ahora, ya topadas.
 *
 * No hace falta escucharlo para leer las claves (EquipoApi siempre da lo de
 * ahora); sirve a quien quiera reaccionar al cambio, por ejemplo para refrescar
 * una barra o un marcador.
 */
public final class EquipoCambiadoEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Map<String, Double> antes;
    private final Map<String, Double> ahora;
    private final Set<String> piezas;

    public EquipoCambiadoEvent(Player jugador, Map<String, Double> antes, Map<String, Double> ahora,
                               Set<String> piezas) {
        super(jugador);
        this.antes = Map.copyOf(antes);
        this.ahora = Map.copyOf(ahora);
        this.piezas = Set.copyOf(piezas);
    }

    /** Las claves de dominio antes del cambio, topadas. */
    public Map<String, Double> getAntes() {
        return this.antes;
    }

    /** Las claves de dominio ahora, topadas. */
    public Map<String, Double> getAhora() {
        return this.ahora;
    }

    /** Los TIPO.ID que cuentan ahora. */
    public Set<String> getPiezas() {
        return this.piezas;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
