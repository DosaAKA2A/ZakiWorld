package net.ederus.calamity.hardcore;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Los contadores de cada jugador en Calamity (DIS sec. 0.4): stats.<uuid>.<clave> de por vida
 * y stats-semana.<anio-semana>.<uuid>.<clave> para los rankings, en hardcore-datos.yml.
 *
 * Claves en uso (las que no esten aqui tambien valen; esta lista es para no inventar dos
 * nombres para lo mismo): extracciones, tasado-mc, tasado-esencias, reliquias,
 * ecos-cerrados, ecos-redimidos, parcas, minijefes, muertes, racha-max, eclipses,
 * contratos, lucidez-min, cazas-validas, expedicion-max-seg, ofrendas, forjas, ambush (1.8.0:
 * contratos de Ambush vencidos por su presa).
 * Las cuenta Hardcore: muertes, extracciones y expedicion-max-seg. El resto, cada modulo.
 *
 * Cada cambio avisa a los Hitos (hitos.revisar), que son los que entregan tags y premios
 * al cruzar un umbral: asi ningun modulo tiene que acordarse de llamarlos.
 */
final class Estadisticas {

    /** Semanas de stats-semana que se guardan; lo demas se poda al arrancar. */
    private static final int SEMANAS_GUARDADAS = 12;

    static final List<String> CLAVES = List.of("extracciones", "tasado-mc", "tasado-esencias", "reliquias",
            "ecos-cerrados", "ecos-redimidos", "parcas", "minijefes", "muertes", "racha-max", "eclipses",
            "contratos", "lucidez-min", "cazas-validas", "expedicion-max-seg", "ofrendas", "forjas", "ambush");

    private final Hardcore hc;
    /** Solo en las pruebas: un yml en memoria en vez de hardcore-datos.yml. */
    private final YamlConfiguration prueba;
    private final Calendario calendarioPrueba;

    Estadisticas(Hardcore hc) {
        this.hc = hc;
        this.prueba = null;
        this.calendarioPrueba = null;
        podarSemanas();
    }

    /** Para los autotest: no toca los datos reales ni avisa a los hitos. */
    Estadisticas(YamlConfiguration datos, Calendario calendario) {
        this.hc = null;
        this.prueba = datos;
        this.calendarioPrueba = calendario;
    }

    private YamlConfiguration datos() {
        return prueba != null ? prueba : hc.datos();
    }

    private String semanaActual() {
        if (calendarioPrueba != null) return calendarioPrueba.semana();
        Calendario c = hc.calendario();
        return c != null ? c.semana() : new Calendario(hc).semana();
    }

    private static String ruta(UUID jugador, String clave) {
        return "stats." + jugador + "." + clave;
    }

    private String rutaSemana(String semana, UUID jugador, String clave) {
        return "stats-semana." + semana + "." + jugador + "." + clave;
    }

    /** Suma n (puede ser negativo) al total y a la semana en curso. */
    void sumar(UUID jugador, String clave, long n) {
        if (jugador == null || clave == null || n == 0) return;
        YamlConfiguration d = datos();
        String r = ruta(jugador, clave);
        d.set(r, d.getLong(r, 0) + n);
        String rs = rutaSemana(semanaActual(), jugador, clave);
        d.set(rs, d.getLong(rs, 0) + n);
        cambio(jugador, clave);
    }

    long de(UUID jugador, String clave) {
        return datos().getLong(ruta(jugador, clave), 0);
    }

    /** Lo de la semana en curso. */
    long semana(UUID jugador, String clave) {
        return semana(jugador, clave, semanaActual());
    }

    /** Lo de una semana concreta ("2026-W39"): el cierre de rankings mira la que acaba de terminar. */
    long semana(UUID jugador, String clave, String cual) {
        return datos().getLong(rutaSemana(cual, jugador, clave), 0);
    }

    /** Los UUID con algo apuntado esa semana. */
    List<UUID> jugadoresDeSemana(String cual) {
        ConfigurationSection s = datos().getConfigurationSection("stats-semana." + cual);
        if (s == null) return List.of();
        List<UUID> out = new ArrayList<>();
        for (String k : s.getKeys(false)) {
            try {
                out.add(UUID.fromString(k));
            } catch (IllegalArgumentException ignorado) {
                // Una clave rara en el yml no es un jugador.
            }
        }
        return out;
    }

    /** Se queda con el mayor: racha-max, expedicion-max-seg. Tambien en la semana. */
    void maximo(UUID jugador, String clave, long valor) {
        if (jugador == null || clave == null) return;
        YamlConfiguration d = datos();
        boolean cambia = false;
        String r = ruta(jugador, clave);
        if (valor > d.getLong(r, Long.MIN_VALUE)) {
            d.set(r, valor);
            cambia = true;
        }
        String rs = rutaSemana(semanaActual(), jugador, clave);
        if (valor > d.getLong(rs, Long.MIN_VALUE)) {
            d.set(rs, valor);
            cambia = true;
        }
        if (cambia) cambio(jugador, clave);
    }

    private void cambio(UUID jugador, String clave) {
        if (hc == null) return;
        hc.marcarSucio();
        Hitos h = hc.hitos();
        if (h != null) hc.seguro("hitos", () -> h.revisar(jugador, clave));
    }

    /** stats-semana crece una seccion por semana: se quedan las ultimas SEMANAS_GUARDADAS. */
    private void podarSemanas() {
        ConfigurationSection s = hc.datos().getConfigurationSection("stats-semana");
        if (s == null) return;
        List<String> semanas = new ArrayList<>(s.getKeys(false));
        if (semanas.size() <= SEMANAS_GUARDADAS) return;
        // "2026-W39" ordena bien como texto: anio de cuatro cifras y semana de dos.
        Collections.sort(semanas);
        for (String vieja : semanas.subList(0, semanas.size() - SEMANAS_GUARDADAS)) s.set(vieja, null);
        hc.marcarSucio();
    }

    void parar() {
    }
}
