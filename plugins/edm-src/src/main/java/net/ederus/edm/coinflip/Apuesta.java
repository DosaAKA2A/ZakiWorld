package net.ederus.edm.coinflip;

import java.util.UUID;

/**
 * Una apuesta puesta sobre la mesa.
 *
 * El dinero del creador YA ESTA RETIRADO cuando existe este objeto. Es la regla
 * que sostiene todo lo demas: si se cobrara al resolver, cualquiera podria crear
 * una apuesta de un millon, gastarselo, y ganar sin haber puesto nada.
 */
public final class Apuesta {

    private final long id;
    private final UUID creador;
    private final String nombreCreador;
    /** null = mesa abierta, la coge el primero que quiera. */
    private final UUID retado;
    private final String nombreRetado;
    private final double cantidad;
    private final long creada;

    /** Se marca en cuanto alguien la coge, ANTES de tocar el dinero: es lo que
     *  impide que dos clics casi a la vez la acepten dos veces. */
    private boolean tomada;

    public Apuesta(long id, UUID creador, String nombreCreador,
                   UUID retado, String nombreRetado, double cantidad, long creada) {
        this.id = id;
        this.creador = creador;
        this.nombreCreador = nombreCreador;
        this.retado = retado;
        this.nombreRetado = nombreRetado;
        this.cantidad = cantidad;
        this.creada = creada;
    }

    public long id() { return id; }
    public UUID creador() { return creador; }
    public String nombreCreador() { return nombreCreador; }
    public UUID retado() { return retado; }
    public String nombreRetado() { return nombreRetado; }
    public double cantidad() { return cantidad; }
    public long creada() { return creada; }

    public boolean esReto() { return retado != null; }
    public boolean esPara(UUID quien) { return retado != null && retado.equals(quien); }
    public boolean esDe(UUID quien) { return creador.equals(quien); }

    public boolean tomada() { return tomada; }
    public boolean tomar() {
        if (tomada) return false;
        tomada = true;
        return true;
    }
    public void soltar() { tomada = false; }

    /** Milisegundos que lleva en la mesa. */
    public long edadMs() { return System.currentTimeMillis() - creada; }
}
