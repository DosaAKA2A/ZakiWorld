package net.ederus.edm.boost;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;

/**
 * Lo que otros plugins pueden preguntar al modulo de boosts sin depender de sus tripas.
 *
 * PremioPescao lo llama por reflexion (net.ederus.edm.boost.BoostApi) para leer la
 * suerte de pesca de un jugador, asi que la firma de estos metodos es un contrato:
 * no se cambia sin tocar PremioPescao a la vez.
 *
 * Todo devuelve 1.0 cuando no hay nada que multiplicar: el modulo apagado, el tipo
 * desactivado en el config, el jugador en un mundo excluido o sin boost.
 */
public final class BoostApi {

    private BoostApi() {
    }

    /* ------------------------------------------------------------------ zonas */

    /**
     * Quien da un boost por ESTAR en un sitio y no por tenerlo comprado: los Super Beacon
     * (modulo superbeacon) y lo que venga despues.
     *
     * Al calcular, el modulo de boosts se queda con el MAYOR entre el personal, el global y
     * lo que digan las zonas, recortado al tope del tipo. Nunca el producto: la misma regla
     * que ya habia entre personal y global, para que una zona no dispare la economia.
     */
    @FunctionalInterface
    public interface FuenteZona {
        /** Lo que esta zona le da a ese jugador, donde esta ahora, para ese tipo; 1.0 si nada. */
        double en(Player jugador, Tipo tipo);
    }

    /**
     * Las fuentes apuntadas, por nombre. Concurrente porque PlaceholderAPI y algun plugin
     * pueden preguntar fuera del hilo principal; casi siempre esta vacio.
     */
    private static final Map<String, FuenteZona> ZONAS = new ConcurrentHashMap<>();

    /** Apunta (o sustituye) una fuente con ese nombre. Quien la apunta la quita al parar. */
    public static void registrarZona(String id, FuenteZona fuente) {
        if (id == null || fuente == null) return;
        ZONAS.put(id, fuente);
    }

    public static void quitarZona(String id) {
        if (id != null) ZONAS.remove(id);
    }

    /**
     * El mayor multiplicador que dan las zonas a ese jugador para ese tipo. 1.0 sin fuentes,
     * que es el caso de siempre: sin ninguna apuntada el calculo es exactamente el de antes.
     * Una fuente que revienta no cuenta y no se lleva el boost de nadie.
     */
    static double zona(Player jugador, Tipo tipo) {
        if (ZONAS.isEmpty() || jugador == null || tipo == null) return 1.0;
        double mejor = 1.0;
        for (FuenteZona f : ZONAS.values()) {
            try {
                double v = f.en(jugador, tipo);
                if (v > mejor) mejor = v;
            } catch (Throwable t) {
                // una zona rota se ignora: el boost personal y el global siguen igual
            }
        }
        return mejor;
    }

    /** El multiplicador que se le aplica ahora a ese jugador, donde esta. tipo: "pesca", "exp"... */
    public static double multiplicador(Player jugador, String tipo) {
        BoostPlugin m = BoostPlugin.activo();
        Tipo t = Tipo.de(tipo);
        if (m == null || t == null || jugador == null) return 1.0;
        return m.multiplicadorEn(jugador, t);
    }

    /** Lo mismo sin mirar el mundo (para jugadores desconectados o paneles). */
    public static double multiplicador(UUID jugador, String tipo) {
        BoostPlugin m = BoostPlugin.activo();
        Tipo t = Tipo.de(tipo);
        if (m == null || t == null || jugador == null) return 1.0;
        return m.multiplicadorDe(jugador, t);
    }

    /** Lo que le queda del boost que manda, en milisegundos; 0 si no tiene. */
    public static long restanteMs(UUID jugador, String tipo) {
        BoostPlugin m = BoostPlugin.activo();
        Tipo t = Tipo.de(tipo);
        if (m == null || t == null || jugador == null || m.servicio() == null) return 0;
        Servicio.Activo a = m.servicio().efectivo(jugador, t);
        return a == null ? 0 : a.restanteMs();
    }
}
