package net.ederus.edm.tienda;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * El log de transacciones. No es un extra: cuando el 19-ago se perdio la config
 * de la tienda, los precios se reconstruyeron leyendo el log del plugin viejo.
 * Se escribe y se vacia en cada operacion — a este volumen no cuesta nada y
 * asi un cierre a lo bruto no se lleva las ultimas lineas.
 */
public final class Registro {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File carpeta;
    private final java.util.logging.Logger log;
    private BufferedWriter salida;
    private LocalDate diaAbierto;
    private boolean yaAvisado;

    public Registro(File carpeta, java.util.logging.Logger log) {
        this.carpeta = carpeta;
        this.log = log;
    }

    /** Un fichero por dia: buscar en el log de una fecha concreta es lo que se acaba haciendo. */
    private synchronized void asegurarDia() throws IOException {
        LocalDate hoy = LocalDate.now();
        if (salida != null && hoy.equals(diaAbierto)) return;
        cerrar();
        carpeta.mkdirs();
        File f = new File(carpeta, "transacciones-" + hoy + ".log");
        salida = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(f, true), StandardCharsets.UTF_8));
        diaAbierto = hoy;
    }

    public synchronized void anotar(String tipo, String jugador, int cantidad, String material,
                                    double unitario, double total, double saldo) {
        try {
            asegurarDia();
            salida.write(String.join(" | ",
                    LocalDateTime.now().format(HORA),
                    tipo,
                    jugador,
                    cantidad + " x " + material,
                    "ud " + fmt(unitario),
                    "total " + fmt(total),
                    "saldo " + fmt(saldo)));
            salida.newLine();
            salida.flush();
            yaAvisado = false;
        } catch (IOException e) {
            /* Esto se llama DESPUES de haber cobrado o pagado. Si revienta, la
             * excepcion sube hasta el clic del menu: el jugador se queda sin
             * mensaje y sin ver el menu repintado, con la operacion ya hecha.
             * Un log que no se puede escribir es grave, pero no es motivo para
             * romper una compra que ya ocurrio. */
            if (!yaAvisado) {
                yaAvisado = true;
                log.severe("No puedo escribir el log de transacciones de la tienda ("
                        + e.getMessage() + "). Las operaciones siguen, pero SIN registro.");
            }
        }
    }

    private static String fmt(double d) { return String.format(java.util.Locale.US, "%.2f", d); }

    public synchronized void cerrar() {
        if (salida == null) return;
        try { salida.close(); } catch (IOException ignored) { /* ya da igual */ }
        salida = null;
    }
}
