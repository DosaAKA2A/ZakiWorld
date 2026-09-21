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

    /*
     * Los datos del golpe. Se sacan SOLOS del evento en el constructor (daño,
     * critico de vanilla, bloque, proyectil) y quien sepa mas los pisa despues:
     * el critico de MythicLib, por ejemplo, no viene marcado en el evento de
     * Bukkit y lo pone a mano la escucha que lo detecta.
     */
    private double dano;
    private boolean critico;
    private String bloque = "";
    private String proyectil = "";

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
        leerDelEvento(evento);
        /* En CRITICO y RECIBIR_CRITICO el critico se da por hecho: ahi llega el
         * de MMOItems, que MythicLib resuelve en SU evento y no deja marcado en
         * el de Bukkit. Sin esto, ES_CRITICO daria falso dentro del propio
         * activador del critico, que es el sitio donde mas raro quedaria. */
        if (activador == Activador.CRITICO || activador == Activador.RECIBIR_CRITICO) {
            this.critico = true;
        }
    }

    /**
     * Lo que el propio evento ya sabe contar.
     *
     * Va aqui y no repartido por las escuchas para que CUALQUIER activador que
     * nazca de un evento de daño traiga `%dano%` sin que haya que acordarse de
     * pasarlo, que es justo como se pierden estas cosas.
     */
    private void leerDelEvento(Event e) {
        if (e instanceof org.bukkit.event.entity.EntityDamageEvent d) {
            this.dano = d.getFinalDamage();
            if (d instanceof org.bukkit.event.entity.EntityDamageByEntityEvent dd) {
                this.critico = dd.isCritical();
                if (dd.getDamager() instanceof org.bukkit.entity.Projectile pr) {
                    this.proyectil = pr.getType().name();
                }
            }
        } else if (e instanceof org.bukkit.event.block.BlockBreakEvent b) {
            this.bloque = b.getBlock().getType().name();
        } else if (e instanceof org.bukkit.event.block.BlockPlaceEvent b) {
            this.bloque = b.getBlock().getType().name();
        } else if (e instanceof org.bukkit.event.player.PlayerInteractEvent pi) {
            if (pi.getClickedBlock() != null) this.bloque = pi.getClickedBlock().getType().name();
        } else if (e instanceof org.bukkit.event.entity.ProjectileHitEvent ph) {
            this.proyectil = ph.getEntity().getType().name();
            if (ph.getHitBlock() != null) this.bloque = ph.getHitBlock().getType().name();
        } else if (e instanceof org.bukkit.event.entity.ProjectileLaunchEvent pl) {
            this.proyectil = pl.getEntity().getType().name();
        }
    }

    /** El evento que lo disparo, para las acciones que tocan el golpe. */
    public Event evento() { return this.evento; }

    public double dano() { return this.dano; }

    public Ctx dano(double d) {
        this.dano = d;
        return this;
    }

    public boolean critico() { return this.critico; }

    public Ctx critico(boolean c) {
        this.critico = c;
        return this;
    }

    /** El material del bloque del activador, o "" si el activador no va de bloques. */
    public String bloque() { return this.bloque; }

    public Ctx bloque(String b) {
        this.bloque = b == null ? "" : b;
        return this;
    }

    public String proyectil() { return this.proyectil; }

    public Ctx proyectil(String p) {
        this.proyectil = p == null ? "" : p;
        return this;
    }

    public GodItemsPlugin modulo() { return this.modulo; }
    public Player jugador() { return this.jugador; }
    public GodItem definicion() { return this.definicion; }
    public Activador activador() { return this.activador; }
    public ItemStack item() { return this.item; }
    public EquipmentSlot mano() { return this.mano; }

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
        c.dano = this.dano;
        c.critico = this.critico;
        c.bloque = this.bloque;
        c.proyectil = this.proyectil;
        return c;
    }
}
