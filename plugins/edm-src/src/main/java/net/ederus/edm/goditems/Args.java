package net.ederus.edm.goditems;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Una linea de accion o de condicion, ya troceada.
 *
 *   DANO @golpeado cantidad:8 tipo:MAGIA
 *   MENSAJE @yo &cTe has quedado sin cargas
 *
 * La regla de lectura, y es la unica que hay que recordar: los `clave:valor` se
 * leen SOLO desde el principio. En cuanto aparece un trozo que no es `@objetivo`
 * ni `clave:valor`, todo lo que queda es texto libre y ya no se parsea nada mas.
 *
 * Por que asi: si se buscaran `clave:valor` por toda la linea, un mensaje tan
 * normal como "Aviso: te queda un uso" se partiria por la mitad y "Aviso"
 * pasaria a ser una clave. Poner el texto al final es una regla que se explica
 * en una frase y no falla nunca.
 */
public final class Args {

    private final String nombre;
    private final String selector;
    private final Map<String, String> claves;
    private final String texto;

    private Args(String nombre, String selector, Map<String, String> claves, String texto) {
        this.nombre = nombre;
        this.selector = selector;
        this.claves = claves;
        this.texto = texto;
    }

    /** Parte una linea entera: el primer trozo es el nombre de la accion. */
    public static Args de(String linea) {
        List<String> trozos = trocear(linea == null ? "" : linea.trim());
        if (trozos.isEmpty()) return new Args("", null, Map.of(), "");
        String nombre = trozos.remove(0).toUpperCase(Locale.ROOT).replace('-', '_');
        return conTrozos(nombre, trozos, false);
    }

    /** Igual, pero el resto es SIEMPRE texto libre (MENSAJE, COMANDO_CONSOLA...). */
    public static Args deTextoLibre(String linea) {
        List<String> trozos = trocear(linea == null ? "" : linea.trim());
        if (trozos.isEmpty()) return new Args("", null, Map.of(), "");
        String nombre = trozos.remove(0).toUpperCase(Locale.ROOT).replace('-', '_');
        return conTrozos(nombre, trozos, true);
    }

    private static Args conTrozos(String nombre, List<String> trozos, boolean todoTexto) {
        String selector = null;
        Map<String, String> claves = new LinkedHashMap<>();
        int i = 0;
        if (!trozos.isEmpty() && trozos.get(0).startsWith("@")) {
            selector = trozos.get(0);
            i = 1;
        }
        if (!todoTexto) {
            while (i < trozos.size() && esClaveValor(trozos.get(i))) {
                String t = trozos.get(i);
                int c = t.indexOf(':');
                claves.put(t.substring(0, c).toLowerCase(Locale.ROOT), desentrecomillar(t.substring(c + 1)));
                i++;
            }
        }
        String texto = String.join(" ", trozos.subList(i, trozos.size()));
        return new Args(nombre, selector, claves, texto);
    }

    /** `clave:valor` con la clave en letras: nada de `&cAviso:` ni de `%var%:`. */
    private static boolean esClaveValor(String t) {
        int c = t.indexOf(':');
        if (c <= 0) return false;
        for (int i = 0; i < c; i++) {
            char ch = t.charAt(i);
            if (!Character.isLetter(ch) && ch != '_' && ch != '-') return false;
        }
        return true;
    }

    /** Trocea por espacios respetando las comillas simples y dobles. */
    private static List<String> trocear(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        char comilla = 0;
        boolean hay = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (comilla != 0) {
                if (c == comilla) comilla = 0;
                else actual.append(c);
                hay = true;
            } else if (c == '\'' || c == '"') {
                comilla = c;
                hay = true;
            } else if (Character.isWhitespace(c)) {
                if (hay) { out.add(actual.toString()); actual.setLength(0); hay = false; }
            } else {
                actual.append(c);
                hay = true;
            }
        }
        if (hay) out.add(actual.toString());
        return out;
    }

    private static String desentrecomillar(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '\'' || s.charAt(0) == '"')
                && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /* ------------------------------------------------------------ lectura */

    public String nombre() {
        return this.nombre;
    }

    /** El `@loquesea`, o null si la linea no lleva ninguno. */
    public String selector() {
        return this.selector;
    }

    public String texto() {
        return this.texto;
    }

    public boolean tiene(String clave) {
        return this.claves.containsKey(clave);
    }

    /** Las `clave:valor` tal cual, en el orden en que venian. Lo usa el editor. */
    public Map<String, String> claves() {
        return java.util.Collections.unmodifiableMap(this.claves);
    }

    public String s(String clave, String pordefecto) {
        String v = this.claves.get(clave);
        return v == null ? pordefecto : v;
    }

    public double d(String clave, double pordefecto) {
        return Numeros.decimal(this.claves.get(clave), pordefecto);
    }

    public int i(String clave, int pordefecto) {
        return (int) Math.round(Numeros.decimal(this.claves.get(clave), pordefecto));
    }

    /** Un tiempo: acepta `30`, `30t`, `30s`, `2m`, `1h`. Devuelve TICKS. */
    public int ticks(String clave, int pordefecto) {
        return Numeros.ticks(this.claves.get(clave), pordefecto);
    }

    public boolean b(String clave, boolean pordefecto) {
        String v = this.claves.get(clave);
        if (v == null) return pordefecto;
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("si") || v.equalsIgnoreCase("sí")
                || v.equalsIgnoreCase("yes") || v.equals("1");
    }

    /** El primer trozo del texto libre, util para `SI VARIABLE cargas > 0`. */
    public List<String> palabras() {
        return trocear(this.texto);
    }

    @Override
    public String toString() {
        return this.nombre + (this.selector == null ? "" : " " + this.selector)
                + (this.claves.isEmpty() ? "" : " " + this.claves)
                + (this.texto.isEmpty() ? "" : " \"" + this.texto + "\"");
    }
}
