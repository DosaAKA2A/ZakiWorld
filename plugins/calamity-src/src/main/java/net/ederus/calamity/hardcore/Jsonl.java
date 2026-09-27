package net.ederus.calamity.hardcore;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JSON de una linea para la telemetria: escribir un suceso y volver a leerlo.
 *
 * Hecho a mano y no con Gson a proposito: el formato de telemetria/AAAA-MM.jsonl lo lee
 * un script de Python fuera del servidor, y tiene que salir igual aunque Paper cambie la
 * version de Gson que trae (o deje de traerla). Solo lo que usan los sucesos: mapas,
 * listas, texto, numeros, si/no y null. Lo que no sea nada de eso sale como texto.
 */
final class Jsonl {

    private Jsonl() {
    }

    // ------------------------------------------------------------------ escribir

    /** El valor en JSON, en una sola linea (los saltos de linea van escapados). */
    static String escribir(Object valor) {
        StringBuilder sb = new StringBuilder(128);
        escribir(sb, valor, 0);
        return sb.toString();
    }

    private static void escribir(StringBuilder sb, Object v, int hondo) {
        // Un mapa que se contiene a si mismo no puede colgar el hilo de la telemetria.
        if (hondo > 16) {
            sb.append("null");
            return;
        }
        if (v == null) {
            sb.append("null");
        } else if (v instanceof CharSequence || v instanceof UUID || v instanceof Enum<?> || v instanceof Character) {
            texto(sb, v.toString());
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                // 4.0 y no 4: el lector de Python distingue int y float, y una media que a
                // veces sale entera y a veces no tiene que ser siempre float.
                sb.append((long) d).append(".0");
            } else {
                sb.append(Math.round(d * 10000.0) / 10000.0);
            }
        } else if (v instanceof Number n) {
            sb.append(n.longValue());
        } else if (v instanceof Censo.Foto f) {
            escribir(sb, f.json(), hondo + 1);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean primero = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!primero) sb.append(',');
                primero = false;
                texto(sb, String.valueOf(e.getKey()));
                sb.append(':');
                escribir(sb, e.getValue(), hondo + 1);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            lista(sb, it.iterator(), hondo);
        } else if (v instanceof Object[] arr) {
            lista(sb, List.of(arr).iterator(), hondo);
        } else {
            texto(sb, v.toString());
        }
    }

    private static void lista(StringBuilder sb, Iterator<?> it, int hondo) {
        sb.append('[');
        boolean primero = true;
        while (it.hasNext()) {
            if (!primero) sb.append(',');
            primero = false;
            escribir(sb, it.next(), hondo + 1);
        }
        sb.append(']');
    }

    private static void texto(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // --------------------------------------------------------------------- leer

    /**
     * Lee una linea: Map (LinkedHashMap), List, String, Long, Double, Boolean o null.
     *
     * @throws IllegalArgumentException si no es JSON valido (el autotest lo usa para eso)
     */
    static Object leer(String linea) {
        if (linea == null) throw new IllegalArgumentException("linea nula");
        Lector l = new Lector(linea);
        l.blancos();
        Object v = l.valor(0);
        l.blancos();
        if (l.i != linea.length()) throw l.error("sobra texto");
        return v;
    }

    /** Lo mismo, pero solo si la linea es un objeto; null si no se puede leer. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> objeto(String linea) {
        try {
            Object v = leer(linea);
            return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final class Lector {
        private final String s;
        private int i;

        Lector(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String que) {
            return new IllegalArgumentException(que + " en la posicion " + i);
        }

        void blancos() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object valor(int hondo) {
            if (hondo > 64) throw error("demasiado anidado");
            if (i >= s.length()) throw error("fin inesperado");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> mapa(hondo);
                case '[' -> lista(hondo);
                case '"' -> texto();
                case 't' -> palabra("true", Boolean.TRUE);
                case 'f' -> palabra("false", Boolean.FALSE);
                case 'n' -> palabra("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield numero();
                    throw error("caracter inesperado '" + c + "'");
                }
            };
        }

        Object palabra(String w, Object v) {
            if (!s.startsWith(w, i)) throw error("esperaba " + w);
            i += w.length();
            return v;
        }

        Map<String, Object> mapa(int hondo) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            blancos();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                blancos();
                if (i >= s.length() || s.charAt(i) != '"') throw error("esperaba una clave");
                String k = texto();
                blancos();
                if (i >= s.length() || s.charAt(i) != ':') throw error("esperaba ':'");
                i++;
                blancos();
                m.put(k, valor(hondo + 1));
                blancos();
                if (i >= s.length()) throw error("objeto sin cerrar");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw error("esperaba ',' o '}'");
            }
        }

        List<Object> lista(int hondo) {
            List<Object> l = new ArrayList<>();
            i++;
            blancos();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                blancos();
                l.add(valor(hondo + 1));
                blancos();
                if (i >= s.length()) throw error("lista sin cerrar");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw error("esperaba ',' o ']'");
            }
        }

        String texto() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw error("texto sin cerrar");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c < 0x20) throw error("caracter de control sin escapar");
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) throw error("escape sin terminar");
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw error("\\u corto");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException x) {
                            throw error("\\u mal escrito");
                        }
                        i += 4;
                    }
                    default -> throw error("escape desconocido \\" + e);
                }
            }
        }

        Object numero() {
            int ini = i;
            if (s.charAt(i) == '-') i++;
            boolean decimal = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    decimal = true;
                    i++;
                } else {
                    break;
                }
            }
            String n = s.substring(ini, i);
            try {
                return decimal ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
            } catch (NumberFormatException x) {
                throw error("numero mal escrito '" + n + "'");
            }
        }
    }
}
