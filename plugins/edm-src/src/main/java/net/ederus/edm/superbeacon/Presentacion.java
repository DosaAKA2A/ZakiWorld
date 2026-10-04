package net.ederus.edm.superbeacon;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Las piezas con las que se pintan el lore del objeto, el menu y el holograma, sin estado
 * y sin Bukkit (el selftest las prueba fuera del servidor).
 *
 * La paleta es corta a proposito: el color del tipo (el de su nombre en el config) es el
 * UNICO acento, para el nombre, los simbolos y lo que importa; las etiquetas van en gris,
 * el texto en blanco o gris claro, y las rayas en gris oscuro. Nada de un color por efecto.
 */
final class Presentacion {

    /** Lo mas ancho que puede ir una linea de lore, en caracteres visibles. */
    static final int ANCHO = 38;

    static final String ETIQUETA = "&#8A8A8A";
    static final String PROSA = "&#C4C4C4";
    static final String OSCURO = "&#4E4E4E";
    static final String RAYA = "────────────────";

    /** Secciones del lore, en este orden: vida y defensa, movimiento y mineria, boosts. */
    static final int VIDA = 0;
    static final int MOVIMIENTO = 1;
    static final int BOOST = 2;

    private static final Locale ES = Locale.forLanguageTag("es");
    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd/MM", ES);
    private static final DateTimeFormatter LARGA = DateTimeFormatter.ofPattern("EEEE dd/MM, HH:mm", ES);
    private static final DateTimeFormatter LARGA_ANO = DateTimeFormatter.ofPattern("EEEE dd/MM/yyyy, HH:mm", ES);

    private static final Pattern CODIGO = Pattern.compile("(?i)&#[0-9a-f]{6}|&x(?:&[0-9a-f]){6}|&[0-9a-fk-or]");

    private Presentacion() {
    }

    /** 0xFFC857 -> "&#FFC857", para meterlo en un texto con codigos &. */
    static String hex(int rgb) {
        return String.format(Locale.ROOT, "&#%06X", rgb & 0xFFFFFF);
    }

    /** El simbolo de cada seccion. */
    static String simbolo(int seccion) {
        return switch (seccion) {
            case VIDA -> "❤";
            case BOOST -> "✦";
            default -> "▸";
        };
    }

    /** La seccion de un efecto; uno sin clase (o de una clase que no dice) va a movimiento. */
    static int seccion(Efecto e) {
        ClaseEfecto c = e.clase();
        return c == null ? MOVIMIENTO : c.seccion(e);
    }

    /** Los efectos agrupados: vida y defensa, movimiento y mineria, boosts; dentro, el orden del config. */
    static List<Efecto> agrupados(java.util.Collection<Efecto> efectos) {
        List<Efecto> out = new ArrayList<>(efectos);
        out.sort(Comparator.comparingInt(Presentacion::seccion));   // estable: respeta el orden del config
        return out;
    }

    /** I, II, III... hasta X; mas alla, el numero. */
    static String romano(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 1 && n < r.length ? r[n] : String.valueOf(n);
    }

    /** x1,5 -> "50 %"; x1,25 -> "25 %"; x2 -> "100 %". */
    static String porcentaje(double multiplicador) {
        return Numeros.decimal(Math.abs(multiplicador - 1.0) * 100.0) + " %";
    }

    /** Puntos de vida a corazones: 12 -> "6 corazones", 2 -> "1 corazón", 1 -> "0,5 corazones". */
    static String corazones(double puntos) {
        double c = Math.abs(puntos) / 2.0;
        return Numeros.decimal(c) + (c == 1.0 ? " corazón" : " corazones");
    }

    /* ================================================================ fechas */

    /**
     * La semana que acaba de cerrar (lunes a domingo), como el epoch day de su lunes. Un
     * domingo cuenta la de ese mismo domingo: el ranking cierra el domingo por la noche y el
     * trofeo se entrega en ese momento o en los dias siguientes.
     */
    static long semanaCerrada(long ahora, ZoneId zona) {
        LocalDate hoy = Instant.ofEpochMilli(ahora).atZone(zona == null ? ZoneId.systemDefault() : zona).toLocalDate();
        LocalDate domingo = hoy.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        return domingo.minusDays(6).toEpochDay();
    }

    /** [desde, hasta] de esa semana: "28/09" y "04/10". */
    static String[] semana(long lunes) {
        LocalDate l = LocalDate.ofEpochDay(lunes);
        return new String[]{DIA_MES.format(l), DIA_MES.format(l.plusDays(6))};
    }

    /** "domingo 11/10, 22:55"; con el año si no es el de ahora. */
    static String fecha(long epochMs, ZoneId zona, long ahora) {
        ZoneId z = zona == null ? ZoneId.systemDefault() : zona;
        ZonedDateTime f = Instant.ofEpochMilli(epochMs).atZone(z);
        boolean otroAno = f.getYear() != Instant.ofEpochMilli(ahora).atZone(z).getYear();
        return (otroAno ? LARGA_ANO : LARGA).format(f);
    }

    /* ================================================================ lineas */

    /** Lo que se ve de un texto con codigos &: sin los codigos. */
    static int largo(String legado) {
        return CODIGO.matcher(legado == null ? "" : legado).replaceAll("").length();
    }

    /**
     * Parte un texto con codigos & en lineas de como mucho ancho caracteres visibles, por
     * palabras. Cada linea nueva empieza con el ultimo color que iba en marcha, para que un
     * parrafo gris siga gris en la segunda linea. Una palabra mas larga que el ancho va sola.
     */
    static List<String> partir(String texto, int ancho) {
        List<String> out = new ArrayList<>();
        if (texto == null || texto.isBlank()) return out;
        StringBuilder linea = new StringBuilder();
        int visible = 0;
        String color = "";
        for (String palabra : texto.trim().split(" +")) {
            int lp = largo(palabra);
            if (visible > 0 && visible + 1 + lp > ancho) {
                out.add(linea.toString());
                linea = new StringBuilder(color);
                visible = 0;
            }
            if (visible > 0) {
                linea.append(' ');
                visible++;
            }
            linea.append(palabra);
            visible += lp;
            Matcher m = CODIGO.matcher(palabra);
            while (m.find()) color = m.group();
        }
        if (visible > 0) out.add(linea.toString());
        return out;
    }

    /** La primera letra en mayuscula ("semana 28/09..." -> "Semana 28/09..."), saltando codigos. */
    static String mayuscula(String legado) {
        if (legado == null || legado.isEmpty()) return legado;
        Matcher m = CODIGO.matcher(legado);
        int i = 0;
        while (m.find(i) && m.start() == i) i = m.end();
        if (i >= legado.length()) return legado;
        return legado.substring(0, i) + Character.toUpperCase(legado.charAt(i)) + legado.substring(i + 1);
    }

    /** Sin el punto final: la frase corta bajo un nombre es un subtitulo, no una oracion. */
    static String sinPunto(String s) {
        if (s == null) return null;
        String t = s.strip();
        while (t.endsWith(".")) t = t.substring(0, t.length() - 1).strip();
        return t;
    }

    static String plano(String legado) {
        return CODIGO.matcher(legado == null ? "" : legado).replaceAll("");
    }
}
