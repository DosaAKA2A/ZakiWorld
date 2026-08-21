package net.ederus.edm.coinflip;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * El log de apuestas, un fichero por dia.
 *
 * Aqui no es un extra: es la unica respuesta a "me ha robado el coinflip". Cada
 * linea dice quien, cuanto, contra quien y que se llevo la casa.
 *
 * A diferencia del registro de la tienda, si falla la escritura NO revienta la
 * operacion: cuando esto se llama el dinero YA se ha movido, y tirar una
 * excepcion a media jugada seria mucho peor que perder una linea de log.
 */
public final class RegistroCf {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File carpeta;
    private final Logger log;
    private BufferedWriter salida;
    private LocalDate diaAbierto;

    public RegistroCf(File carpeta, Logger log) {
        this.carpeta = carpeta;
        this.log = log;
    }

    private void asegurarDia() throws IOException {
        LocalDate hoy = LocalDate.now();
        if (salida != null && hoy.equals(diaAbierto)) return;
        cerrar();
        carpeta.mkdirs();
        salida = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(new File(carpeta, "apuestas-" + hoy + ".log"), true),
                StandardCharsets.UTF_8));
        diaAbierto = hoy;
    }

    public synchronized void anotar(String tipo, String jugador, long id,
                                    double apuesta, double premio, String nota) {
        try {
            asegurarDia();
            salida.write(String.join(" | ",
                    LocalDateTime.now().format(HORA),
                    tipo,
                    jugador,
                    "apuesta " + id,
                    "puso " + fmt(apuesta),
                    "cobro " + fmt(premio),
                    nota == null ? "" : nota));
            salida.newLine();
            /* Se vacia en cada linea: a este volumen no cuesta nada y un cierre
             * a lo bruto no se lleva las ultimas jugadas, que son justo las que
             * alguien va a venir a reclamar. */
            salida.flush();
        } catch (IOException e) {
            log.warning("No pude escribir el log de apuestas: " + e.getMessage());
        }
    }

    private static String fmt(double d) { return String.format(Locale.US, "%.2f", d); }

    public synchronized void cerrar() {
        if (salida == null) return;
        try { salida.close(); } catch (IOException ignored) { /* ya da igual */ }
        salida = null;
    }
}
