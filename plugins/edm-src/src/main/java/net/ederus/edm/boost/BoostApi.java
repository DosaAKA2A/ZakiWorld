package net.ederus.edm.boost;

import java.util.UUID;

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
