package net.ederus.edm.superbeacon;

import java.util.Locale;

/** Numeros como se leen en español: "1,25", "4", "0,02". Nunca "1.25" ni "4.0". */
final class Numeros {

    private Numeros() {
    }

    static String decimal(double d) {
        double r = Math.round(d * 100.0) / 100.0;
        if (r == Math.rint(r)) return String.valueOf((long) r);
        String s = String.format(Locale.ROOT, "%.2f", r);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.replace('.', ',');
    }
}
