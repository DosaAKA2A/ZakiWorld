package net.ederus.edm.coinflip;

import java.io.File;
import java.util.logging.Logger;

import net.ederus.edm.comun.Bitacora;

/**
 * El log de apuestas, un fichero por dia.
 *
 * Aqui no es un extra: es la unica respuesta a "me ha robado el coinflip". Cada
 * linea dice quien, cuanto, contra quien y que se llevo la casa.
 *
 * Si falla la escritura NO revienta la operacion: cuando esto se llama el dinero
 * YA se ha movido, y tirar una excepcion a media jugada seria mucho peor que
 * perder una linea de log.
 */
public final class RegistroCf {

    private final Bitacora bitacora;

    public RegistroCf(File carpeta, Logger log) {
        this.bitacora = new Bitacora(carpeta, "apuestas", log, Bitacora.FECHA_Y_HORA);
    }

    public void anotar(String tipo, String jugador, long id,
                       double apuesta, double premio, String nota) {
        bitacora.anotar(tipo, jugador, "apuesta " + id,
                "puso " + net.ederus.edm.comun.Bitacora.dec(apuesta), "cobro " + net.ederus.edm.comun.Bitacora.dec(premio), nota == null ? "" : nota);
    }

    public void cerrar() {
        bitacora.cerrar();
    }
}
