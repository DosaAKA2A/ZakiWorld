package net.ederus.edm.coinflip;

import java.security.SecureRandom;

/**
 * La moneda.
 *
 * Va con SecureRandom y no con Random ni con Math.random(): un Random normal se
 * puede predecir viendo unas cuantas tiradas, y aqui cada tirada mueve dinero de
 * verdad. Es barato y quita el problema de raiz.
 */
final class Sorteo {

    private static final SecureRandom AZAR = new SecureRandom();

    private Sorteo() { }

    /** true = gana el que puso la apuesta. 50/50 exacto. */
    static boolean caraOCruz() {
        return AZAR.nextBoolean();
    }
}
