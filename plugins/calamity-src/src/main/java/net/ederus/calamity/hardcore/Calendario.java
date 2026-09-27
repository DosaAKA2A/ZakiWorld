package net.ederus.calamity.hardcore;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.IsoFields;

/**
 * El dia, la semana ISO y el mes de Calamity, en la zona de hardcore.zona.
 *
 * Todo lo que tiene tope "por dia" o "por semana" (Aduana, altar, contratos, encuesta,
 * rankings, llaves) pregunta aqui y no a LocalDate.now(). Si cada modulo mirase la hora
 * a su manera, el tope de la Aduana cambiaria de dia a las 00:00 del sistema y el del
 * altar a las 00:00 de Madrid, y en la hora de diferencia se cobraria dos veces.
 *
 * Formatos (son claves de hardcore-datos.yml, no se cambian a la ligera):
 * dia "2026-09-26", semana "2026-W39" (ISO: empieza el lunes), mes "2026-09".
 */
final class Calendario {

    private final Hardcore hc;
    /** Solo para las pruebas: una zona fija que no depende de la config. */
    private final ZoneId fija;
    private String zonaAvisada;

    Calendario(Hardcore hc) {
        this.hc = hc;
        this.fija = null;
    }

    /** Con una zona fija, sin config: lo usan los autotest. */
    Calendario(ZoneId zona) {
        this.hc = null;
        this.fija = zona;
    }

    /**
     * La zona de hoy. Se lee en cada llamada para que un /lw reload con otra zona se note
     * sin reiniciar; es un String de la config, no cuesta nada.
     */
    ZoneId zona() {
        if (fija != null) return fija;
        String z = hc == null ? "" : hc.cfg().getString("zona", "");
        if (z == null || z.isBlank()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(z.trim());
        } catch (DateTimeException e) {
            // Una zona mal escrita no puede parar los topes: se usa la del sistema y se
            // avisa una vez por valor, no en cada pago.
            if (!z.equals(zonaAvisada) && hc != null) {
                zonaAvisada = z;
                hc.plugin().getLogger().warning("[Calamity] hardcore.zona \"" + z
                        + "\" no es una zona valida; uso la del sistema (" + ZoneId.systemDefault() + ").");
            }
            return ZoneId.systemDefault();
        }
    }

    private LocalDate fecha(long millis) {
        return Instant.ofEpochMilli(millis).atZone(zona()).toLocalDate();
    }

    String dia() {
        return dia(System.currentTimeMillis());
    }

    String dia(long millis) {
        return fecha(millis).toString();
    }

    String semana() {
        return semana(System.currentTimeMillis());
    }

    /** Semana ISO: el anio es el de la semana, no el del calendario (el 1 de enero puede caer en la W53). */
    String semana(long millis) {
        LocalDate d = fecha(millis);
        return String.format("%d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /** La semana ISO anterior a la de ese instante (cierre de rankings). */
    String semanaAnterior(long millis) {
        return semana(millis - 7L * 24 * 3600 * 1000);
    }

    String mes() {
        return mes(System.currentTimeMillis());
    }

    String mes(long millis) {
        return YearMonth.from(fecha(millis)).toString();
    }

    /** Millis del 00:00 de hoy en la zona. */
    long inicioDia() {
        return inicioDia(System.currentTimeMillis());
    }

    long inicioDia(long millis) {
        ZoneId z = zona();
        return fecha(millis).atStartOfDay(z).toInstant().toEpochMilli();
    }

    void parar() {
    }
}
