package net.ederus.edm.comun;

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
 * La bitacora de un modulo: un fichero de texto por dia en plugins/EDM/logs/,
 * pensado para LEERLO, no para parsearlo.
 *
 * Nace de una tarde entera discutiendo por que una anomalia salto a fase III sin
 * que nadie la tocara: el log del servidor decia que se abrio y que se cerro, y
 * nada mas. Con esto, cuando algo raro pase, la respuesta esta escrita.
 *
 * Reglas de la casa:
 *   - UNA linea por suceso, con hora delante y campos separados por " | ".
 *     Nada de JSON ni de varias lineas por suceso: se lee con el visor del panel.
 *   - Se vacia el buffer en cada linea. A este volumen no cuesta nada y un cierre
 *     a lo bruto no se lleva lo ultimo, que es justo lo que interesa.
 *   - Si el disco falla, se avisa UNA vez y el servidor sigue. Un log que no se
 *     puede escribir no es motivo para tumbar una pelea que ya esta en marcha.
 *   - Se poda sola: los ficheros mas viejos que los dias configurados se borran
 *     al arrancar, para que la carpeta no crezca sin que nadie la mire.
 */
public final class Bitacora {

    /** La fecha ya va en el nombre del fichero: dentro basta la hora. */
    public static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Para los registros viejos (tienda, coinflip, bromas), que siempre la llevaron. */
    public static final DateTimeFormatter FECHA_Y_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File carpeta;
    private final String nombre;
    private final Logger log;
    private final DateTimeFormatter marca;

    private boolean activa = true;
    private BufferedWriter salida;
    private LocalDate diaAbierto;
    private boolean yaAvisado;

    /**
     * @param carpeta donde viven los ficheros (lo normal: plugins/EDM/logs)
     * @param nombre  prefijo del fichero, sin fecha ni extension (ej. "anomalias")
     */
    public Bitacora(File carpeta, String nombre, Logger log) {
        this(carpeta, nombre, log, HORA);
    }

    /** @param marca como se escribe el momento al principio de cada linea */
    public Bitacora(File carpeta, String nombre, Logger log, DateTimeFormatter marca) {
        this.carpeta = carpeta;
        this.nombre = nombre;
        this.log = log;
        this.marca = marca;
    }

    /** En false no se escribe nada y no se toca el disco. */
    public synchronized void activa(boolean valor) {
        if (!valor) cerrar();
        this.activa = valor;
    }

    public boolean activa() {
        return activa;
    }

    public String nombre() {
        return nombre;
    }

    /** Escribe una linea suelta, ya montada por quien llama. */
    public synchronized void anotar(String linea) {
        if (!activa || linea == null) return;
        try {
            asegurarDia();
            salida.write(LocalDateTime.now().format(marca));
            salida.write(" | ");
            salida.write(linea);
            salida.newLine();
            salida.flush();
            yaAvisado = false;
        } catch (IOException e) {
            if (!yaAvisado) {
                yaAvisado = true;
                log.severe("No puedo escribir la bitacora '" + nombre + "' (" + e.getMessage()
                        + "). El servidor sigue, pero SIN registro.");
            }
        }
    }

    /** Lo mismo, pero uniendo campos con el separador de la casa. */
    public void anotar(String... campos) {
        if (!activa || campos == null || campos.length == 0) return;
        anotar(String.join(" | ", campos));
    }

    /**
     * Un titulo con aire delante, para separar sucesos gordos (una anomalia que
     * abre, un despliegue). Ayuda mas de lo que parece cuando el fichero crece.
     */
    public synchronized void seccion(String titulo) {
        if (!activa) return;
        try {
            asegurarDia();
            salida.newLine();
        } catch (IOException ignored) {
            /* Si falla aqui, anotar() ya avisara del problema de verdad. */
        }
        anotar("=== " + titulo + " ===");
    }

    /**
     * Un numero como se quiere LEER, no como lo escupe Java.
     *
     * Nada de "12400.0" ni de "0.08264462809917356": mas decimales cuanto mas
     * pequeno es el numero, y sin ceros de relleno al final.
     */
    /** Un decimal con dos cifras y punto, para los registros de tienda y coinflip. */
    public static String dec(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    public static String num(double v) {
        double abs = Math.abs(v);
        String s = abs >= 100 ? String.format(java.util.Locale.US, "%.0f", v)
                : abs >= 1 ? String.format(java.util.Locale.US, "%.2f", v)
                : String.format(java.util.Locale.US, "%.4f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Un fichero por dia: buscar por fecha es lo que se acaba haciendo siempre. */
    private void asegurarDia() throws IOException {
        LocalDate hoy = LocalDate.now();
        if (salida != null && hoy.equals(diaAbierto)) return;
        cerrar();
        carpeta.mkdirs();
        salida = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(new File(carpeta, nombre + "-" + hoy + ".log"), true),
                StandardCharsets.UTF_8));
        diaAbierto = hoy;
    }

    /**
     * Borra los ficheros de esta bitacora con mas de tantos dias. Con dias <= 0 no
     * borra nada: hay quien prefiere guardarlo todo y podar a mano.
     */
    public synchronized void podar(int dias) {
        if (dias <= 0) return;
        File[] hijos = carpeta.listFiles();
        if (hijos == null) return;
        long limite = System.currentTimeMillis() - (long) dias * 24L * 60L * 60L * 1000L;
        for (File f : hijos) {
            String n = f.getName();
            if (!n.startsWith(nombre + "-") || !n.endsWith(".log")) continue;
            if (f.equals(ficheroDeHoy())) continue;
            if (f.lastModified() < limite && !f.delete()) {
                log.warning("No pude borrar la bitacora vieja " + n);
            }
        }
    }

    private File ficheroDeHoy() {
        return new File(carpeta, nombre + "-" + LocalDate.now() + ".log");
    }

    public synchronized void cerrar() {
        if (salida == null) return;
        try {
            salida.close();
        } catch (IOException ignored) {
            /* Se esta cerrando: ya da igual. */
        }
        salida = null;
        diaAbierto = null;
    }
}
