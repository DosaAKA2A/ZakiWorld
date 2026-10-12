package net.ederus.edm.crates;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

import net.ederus.edm.crates.Calidades.Caja;
import net.ederus.edm.crates.Calidades.Premio;

/**
 * El sorteo en dos pasos, sin nada de Bukkit ni de PhoenixCrates.
 *
 * Primero la calidad, segun su porcentaje; despues el premio dentro de ella,
 * segun su peso. Solo cuentan los premios que se pueden sacar en ese momento
 * (los que PhoenixCrates da como candidatos y, en las calidades de una vez, los
 * que el jugador todavia no tiene). Una calidad que se queda sin premios deja
 * de contar y las demas se reparten su porcentaje en proporcion.
 */
final class Sorteo {

    private Sorteo() {
    }

    /** Las calidades con premios disponibles, en el orden del config de la caja. */
    private static Map<String, List<Premio>> grupos(Caja caja, Collection<String> disponibles) {
        Map<String, List<Premio>> grupos = new LinkedHashMap<>();
        for (Map.Entry<String, Double> q : caja.porcentajes().entrySet()) {
            if (q.getValue() <= 0) continue;
            List<Premio> lista = new ArrayList<>();
            for (Premio p : caja.premios().values()) {
                if (p.calidad().equals(q.getKey()) && disponibles.contains(p.id())) {
                    lista.add(p);
                }
            }
            if (!lista.isEmpty()) grupos.put(q.getKey(), lista);
        }
        return grupos;
    }

    /**
     * La probabilidad final de cada premio disponible, de 0 a 1. Es lo que se
     * pinta en el lore y lo que comprueba /edm crates simulate.
     */
    static Map<String, Double> probabilidades(Caja caja, Collection<String> disponibles) {
        Map<String, List<Premio>> grupos = grupos(caja, disponibles);
        double totalCalidades = 0;
        for (String q : grupos.keySet()) totalCalidades += caja.porcentajes().get(q);

        Map<String, Double> fuera = new LinkedHashMap<>();
        if (totalCalidades <= 0) return fuera;
        for (Map.Entry<String, List<Premio>> g : grupos.entrySet()) {
            double pCalidad = caja.porcentajes().get(g.getKey()) / totalCalidades;
            double totalPeso = 0;
            for (Premio p : g.getValue()) totalPeso += p.peso();
            for (Premio p : g.getValue()) fuera.put(p.id(), pCalidad * p.peso() / totalPeso);
        }
        return fuera;
    }

    /** La probabilidad de cada calidad con lo que hay disponible, de 0 a 1. */
    static Map<String, Double> porCalidad(Caja caja, Collection<String> disponibles) {
        Map<String, List<Premio>> grupos = grupos(caja, disponibles);
        double total = 0;
        for (String q : grupos.keySet()) total += caja.porcentajes().get(q);
        Map<String, Double> fuera = new LinkedHashMap<>();
        if (total <= 0) return fuera;
        for (String q : grupos.keySet()) fuera.put(q, caja.porcentajes().get(q) / total);
        return fuera;
    }

    /**
     * Elige un premio. Devuelve su identifier, o null si no hay nada que sortear
     * (entonces se deja el sorteo a PhoenixCrates).
     *
     * @param azar numeros en [0, 1); en el servidor, ThreadLocalRandom.
     */
    static String elegir(Caja caja, Collection<String> disponibles, DoubleSupplier azar) {
        Map<String, List<Premio>> grupos = grupos(caja, disponibles);
        if (grupos.isEmpty()) return null;

        double total = 0;
        for (String q : grupos.keySet()) total += caja.porcentajes().get(q);
        double tiro = azar.getAsDouble() * total;
        List<Premio> elegidos = null;
        for (Map.Entry<String, List<Premio>> g : grupos.entrySet()) {
            elegidos = g.getValue();
            tiro -= caja.porcentajes().get(g.getKey());
            if (tiro < 0) break;
        }

        double totalPeso = 0;
        for (Premio p : elegidos) totalPeso += p.peso();
        double tiroPeso = azar.getAsDouble() * totalPeso;
        Premio premio = elegidos.get(elegidos.size() - 1);
        for (Premio p : elegidos) {
            tiroPeso -= p.peso();
            if (tiroPeso < 0) {
                premio = p;
                break;
            }
        }
        return premio.id();
    }

    /**
     * Un porcentaje para leerlo: 66,5 · 1,02 · 0,057 · 0,0057. Mas decimales cuanto
     * mas pequeno, sin ceros de relleno y con coma, que es como se lee en el juego.
     *
     * @param fraccion de 0 a 1.
     */
    static String porcentaje(double fraccion) {
        double v = fraccion * 100;
        int decimales;
        if (v >= 10) decimales = 1;
        else if (v >= 1) decimales = 2;
        else if (v <= 0) return "0";
        else decimales = Math.min(5, 1 - (int) Math.floor(Math.log10(v))); // dos cifras con sentido
        String s = String.format(java.util.Locale.ROOT, "%." + decimales + "f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s.replace('.', ',');
    }

    /** "1 de cada 1.750" para una probabilidad de 0 a 1. */
    static String unoDeCada(double fraccion) {
        if (fraccion <= 0) return "nunca";
        long n = Math.round(1 / fraccion);
        return "1 de cada " + String.format(java.util.Locale.ROOT, "%,d", n).replace(',', '.');
    }
}
