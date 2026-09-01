package net.ederus.edm.goditems;

import java.util.Locale;

/** Numeros y tiempos escritos a mano en un YAML, leidos sin reventar. */
public final class Numeros {

    private Numeros() { }

    public static double decimal(String s, double pordefecto) {
        if (s == null || s.isBlank()) return pordefecto;
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return pordefecto;
        }
    }

    /**
     * `30` (ticks), `30t`, `30s`, `2m`, `1h`. Sin sufijo son TICKS, porque en un
     * plugin de Minecraft es la unidad en la que se piensa todo lo demas.
     */
    public static int ticks(String s, int pordefecto) {
        if (s == null || s.isBlank()) return pordefecto;
        String v = s.trim().toLowerCase(Locale.ROOT);
        double mult = 1;
        char u = v.charAt(v.length() - 1);
        if (!Character.isDigit(u)) {
            v = v.substring(0, v.length() - 1);
            switch (u) {
                case 't' -> mult = 1;
                case 's' -> mult = 20;
                case 'm' -> mult = 20 * 60;
                case 'h' -> mult = 20 * 60 * 60;
                default -> { return pordefecto; }
            }
        }
        double n = decimal(v, Double.NaN);
        if (Double.isNaN(n)) return pordefecto;
        return (int) Math.round(n * mult);
    }

    /** true si el valor viene escrito como porcentaje (`35%`). */
    public static boolean esPorcentaje(String s) {
        return s != null && s.trim().endsWith("%");
    }

    /** `35%` -> 0.35 ; `35` -> 35. Quien llama decide que hacer con cada caso. */
    public static double porcentaje(String s, double pordefecto) {
        if (s == null) return pordefecto;
        String v = s.trim();
        if (v.endsWith("%")) return decimal(v.substring(0, v.length() - 1), pordefecto * 100) / 100.0;
        return decimal(v, pordefecto);
    }

    /** Un tiempo en ticks contado en `1h 20m 5s`, para las cuentas atras. */
    public static String reloj(int ticks) {
        int total = Math.max(0, ticks) / 20;
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        /* Por debajo del minuto interesa el decimal: un cooldown de 3 s que va
         * saltando de "3s" a "2s" parece congelado medio segundo de cada uno. */
        if (ticks < 200) return String.format(Locale.US, "%.1fs", ticks / 20.0);
        return s + "s";
    }
}
