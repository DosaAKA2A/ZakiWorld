package net.ederus.edm.tienda;

import java.io.File;
import java.util.logging.Logger;

import net.ederus.edm.comun.Bitacora;

/**
 * El log de transacciones. No es un extra: cuando el 19-ago se perdio la config
 * de la tienda, los precios se reconstruyeron leyendo el log del plugin viejo.
 *
 * Si falla la escritura NO se aborta nada: esto se llama DESPUES de haber cobrado
 * o pagado, y romper una compra que ya ocurrio seria peor que perder la linea.
 * La Bitacora avisa una vez y sigue.
 */
public final class Registro {

    private final Bitacora bitacora;

    public Registro(File carpeta, Logger log) {
        this.bitacora = new Bitacora(carpeta, "transacciones", log, Bitacora.FECHA_Y_HORA);
    }

    public void anotar(String tipo, String jugador, int cantidad, String material,
                       double unitario, double total, double saldo) {
        bitacora.anotar(tipo, jugador, cantidad + " x " + material,
                "ud " + net.ederus.edm.comun.Bitacora.dec(unitario), "total " + net.ederus.edm.comun.Bitacora.dec(total), "saldo " + net.ederus.edm.comun.Bitacora.dec(saldo));
    }

    public void cerrar() {
        bitacora.cerrar();
    }
}
