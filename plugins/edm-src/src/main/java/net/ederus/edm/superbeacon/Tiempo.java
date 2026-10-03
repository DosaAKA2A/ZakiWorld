package net.ederus.edm.superbeacon;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Como se escribe el tiempo de un Super Beacon: la cuenta atras y la fecha.
 *
 * Dos formas a proposito. Lo que se repinta solo (el holograma, el menu, /superbeacon)
 * lleva cuenta atras: "Quedan 12 d 4 h". Lo que esta quieto (el lore del objeto) lleva la
 * FECHA: un lore no se repinta dentro de un inventario, y una cuenta atras congelada
 * mentiria al dia siguiente.
 */
final class Tiempo {

    static final long SEGUNDO = 1000L;
    static final long MINUTO = 60 * SEGUNDO;
    static final long HORA = 60 * MINUTO;
    static final long DIA = 24 * HORA;

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT);

    private Tiempo() {
    }

    /**
     * "12 d 4 h", "4 h 12 min", "12 min 30 s" o "30 s". Solo las dos unidades que
     * importan, y la pequena se calla si es cero: "12 d" se lee mejor que "12 d 0 h".
     */
    static String restante(long ms) {
        long s = Math.max(0, ms) / 1000;
        long d = s / 86_400, h = s % 86_400 / 3_600, m = s % 3_600 / 60, seg = s % 60;
        if (d > 0) return h > 0 ? d + " d " + h + " h" : d + " d";
        if (h > 0) return m > 0 ? h + " h " + m + " min" : h + " h";
        if (m > 0) return seg > 0 ? m + " min " + seg + " s" : m + " min";
        return seg + " s";
    }

    /** "03/11/2026 18:00" en la zona del config. */
    static String fecha(long epochMs, ZoneId zona) {
        return FECHA.withZone(zona == null ? ZoneId.systemDefault() : zona).format(Instant.ofEpochMilli(epochMs));
    }

    /**
     * La zona de superbeacon/config.yml (zona-horaria). Vacia, la del sistema. Mal escrita,
     * null: quien llama avisa y usa la del sistema, porque una zona rota no puede tumbar el
     * modulo por una fecha en un lore.
     */
    static ZoneId zona(String texto) {
        if (texto == null || texto.isBlank()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(texto.trim());
        } catch (DateTimeException e) {
            return null;
        }
    }
}
