package net.ederus.edm.anomaly.core;

/**
 * Senal para cortar una animacion antes de tiempo sin que cuente como error.
 *
 * La lanza una habilidad cuando su condicion deja de tener sentido (le rompieron el
 * estandarte, el objetivo se desconecto, cayeron las tres anclas). Anim la distingue
 * de una excepcion de verdad: cierra la animacion, ejecuta la limpieza y no ensucia
 * el log con avisos.
 */
public final class Stop extends RuntimeException {

    private static final Stop INSTANCE = new Stop();

    private Stop() {
        super(null, null, false, false);
    }

    public static Stop now() {
        return INSTANCE;
    }
}
