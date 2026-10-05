package net.ederus.edm.minas.api;

/**
 * 1.80.0 · Por que se rellena una mina.
 */
public enum MineResetReason {

    /** Se acabo el reloj de la mina. */
    TIMER,
    /** Se pico el porcentaje del umbral y el reloj se adelanto. */
    THRESHOLD,
    /** Un /mine reset. */
    COMMAND,
    /** El boton "Reiniciar ahora" del menu de /mine. */
    MENU
}
