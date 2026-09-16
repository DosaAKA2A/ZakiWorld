package net.ederus.edm.troll;

import java.io.File;
import java.util.logging.Logger;

import net.ederus.edm.comun.Bitacora;

/**
 * Quien le hizo que a quien, un fichero por dia.
 *
 * En un servidor con gente dentro esto no es opcional: cuando alguien diga que
 * un moderador le tiro el inventario, la respuesta tiene que ser un fichero y no
 * la palabra de nadie.
 *
 * Como en el coinflip, si falla la escritura NO se aborta la broma: la broma ya
 * ha pasado y tirar una excepcion aqui solo dejaria las cosas a medias.
 */
public final class RegistroTroll {

    private final Bitacora bitacora;

    public RegistroTroll(File carpeta, Logger log) {
        this.bitacora = new Bitacora(carpeta, "bromas", log, Bitacora.FECHA_Y_HORA);
    }

    public void anotar(String admin, String victima, String broma,
                       boolean destructiva, int segundos) {
        bitacora.anotar(destructiva ? "DESTRUCTIVA" : "broma", admin, "-> " + victima, broma,
                segundos > 0 ? segundos + "s" : "instantanea");
    }

    public void cerrar() {
        bitacora.cerrar();
    }
}
