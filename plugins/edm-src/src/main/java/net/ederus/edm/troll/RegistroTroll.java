package net.ederus.edm.troll;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

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

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File carpeta;
    private final Logger log;
    private BufferedWriter salida;
    private LocalDate diaAbierto;

    public RegistroTroll(File carpeta, Logger log) {
        this.carpeta = carpeta;
        this.log = log;
    }

    private void asegurarDia() throws IOException {
        LocalDate hoy = LocalDate.now();
        if (salida != null && hoy.equals(diaAbierto)) return;
        cerrar();
        carpeta.mkdirs();
        salida = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(new File(carpeta, "bromas-" + hoy + ".log"), true),
                StandardCharsets.UTF_8));
        diaAbierto = hoy;
    }

    public synchronized void anotar(String admin, String victima, String broma,
                                    boolean destructiva, int segundos) {
        try {
            asegurarDia();
            salida.write(String.join(" | ",
                    LocalDateTime.now().format(HORA),
                    destructiva ? "DESTRUCTIVA" : "broma",
                    admin,
                    "-> " + victima,
                    broma,
                    segundos > 0 ? segundos + "s" : "instantanea"));
            salida.newLine();
            salida.flush();
        } catch (IOException e) {
            log.warning("No pude escribir el log de bromas: " + e.getMessage());
        }
    }

    public synchronized void cerrar() {
        if (salida == null) return;
        try { salida.close(); } catch (IOException ignored) { /* ya da igual */ }
        salida = null;
    }
}
