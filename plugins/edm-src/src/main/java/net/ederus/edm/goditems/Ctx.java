package net.ederus.edm.goditems;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Todo lo que sabe una ejecucion: quien, con que, por que y contra quien.
 *
 * Se pasa entero a cada accion y a cada condicion. Es mutable a proposito en dos
 * campos (`objetivo` y `lugar`): hay acciones que apuntan a otra cosa y las que
 * vienen detras tienen que verlo.
 */
public final class Ctx {

    private final GodItemsPlugin modulo;
    private final Player jugador;
    private final GodItem definicion;
    private final Activador activador;
    private final ItemStack item;
    private final EquipmentSlot mano;
    private final Event evento;

    private Entity objetivo;
    private Location lugar;
    private boolean cancelado;
    /** Sube en cuanto la ejecucion pasa por un ESPERAR: ya no es el mismo tick. */
    private boolean diferido;

    public Ctx(GodItemsPlugin modulo, Player jugador, GodItem definicion, Activador activador,
               ItemStack item, EquipmentSlot mano, Event evento) {
        this.modulo = modulo;
        this.jugador = jugador;
        this.definicion = definicion;
        this.activador = activador;
        this.item = item;
        this.mano = mano;
        this.evento = evento;
        this.lugar = jugador == null ? null : jugador.getLocation();
    }

    public GodItemsPlugin modulo() { return this.modulo; }
    public Player jugador() { return this.jugador; }
    public GodItem definicion() { return this.definicion; }
    public Activador activador() { return this.activador; }
    public ItemStack item() { return this.item; }
    public EquipmentSlot mano() { return this.mano; }
    public Event evento() { return this.evento; }

    public Entity objetivo() { return this.objetivo; }

    public Ctx objetivo(Entity e) {
        this.objetivo = e;
        return this;
    }

    public Location lugar() {
        if (this.lugar != null) return this.lugar;
        return this.jugador == null ? null : this.jugador.getLocation();
    }

    public Ctx lugar(Location l) {
        this.lugar = l;
        return this;
    }

    public boolean diferido() { return this.diferido; }

    public void marcarDiferido() { this.diferido = true; }

    /** Corta la ejecucion: lo que quede de la lista de acciones no corre. */
    public void cancelar() { this.cancelado = true; }

    public boolean cancelado() { return this.cancelado; }

    /**
     * CANCELAR_EVENTO. Solo vale en el MISMO tick del evento: pasado un
     * ESPERAR, Bukkit ya lo ha dado por bueno y cancelarlo no deshace nada.
     * Cuando pasa eso se avisa en el log en vez de fallar en silencio, que es
     * justo el fallo que uno se pasa una tarde buscando.
     */
    public boolean cancelarEvento() {
        if (!(this.evento instanceof Cancellable c)) return false;
        if (this.diferido) {
            this.modulo.getLogger().warning("CANCELAR_EVENTO de " + this.definicion.id()
                    + " va DETRAS de un ESPERAR: el evento ya paso y no se puede cancelar."
                    + " Ponlo antes de cualquier espera.");
            return false;
        }
        c.setCancelled(true);
        return true;
    }

    /** Una copia para lanzar una rama aparte (REPETIR con objetivos distintos). */
    public Ctx copia() {
        Ctx c = new Ctx(this.modulo, this.jugador, this.definicion, this.activador,
                this.item, this.mano, this.evento);
        c.objetivo = this.objetivo;
        c.lugar = this.lugar;
        c.diferido = this.diferido;
        return c;
    }
}
