package net.ederus.calamity.hardcore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Los tops de los hologramas (1.3.2): la parte de %lethalworld_top_...% que no toca Bukkit.
 *
 *   %lethalworld_top_<clave>_<n>_nombre|valor|texto[_semana]%   el n.o de la clasificacion (1-10)
 *   %lethalworld_top_<clave>_pos[_semana]%                       el puesto del que mira, o "—"
 *   %lethalworld_top_<clave>_yo[_semana]%                        lo que lleva el que mira, en texto
 *
 * <clave> es cualquier clave de Estadisticas (tasado-mc, cazas-validas, parcas,
 * expedicion-max-seg, ecos-cerrados, extracciones...), horas-activas o esencias: el saldo de
 * fuera (Saldo), que no se apunta por semanas y solo tiene total. valor es el numero crudo, como
 * antes de 1.3.2 (horas-activas en horas enteras); texto es como se lee, igual que en el menu
 * del Cazador (Npcs.valorRanking): miles con punto, "1.234 MC", "2 h 14 min". Un hueco sin nadie:
 * nombre y texto "—", valor "0".
 *
 * Orden: de mas a menos y, a igual valor, por UUID, el mismo desempate que el reparto de premios
 * (Rankings.reparto): el 1.o del holograma es el que cobraria. El puesto es el de la lista y no se
 * comparte: con dos empatados, uno es el #2 y otro el #3, y el "Tu posicion" de cada uno dice lo
 * mismo que la linea en la que sale. Quien tiene 0 no puntua (puesto "—").
 *
 * Rankings rehace las clasificaciones en su tarea (hilo principal, cada recalcular-segundos) y
 * las deja inmutables; PlaceholderAPI pregunta desde cualquier hilo y aqui solo se leen.
 */
final class Tops {

    /** El saldo de Esencias: no es una clave de Estadisticas, lo pone Rankings desde Saldo. */
    static final String ESENCIAS = "esencias";
    /** Hueco sin nadie, puesto de quien no puntua y peticiones sin jugador. */
    static final String NADIE = "—";

    /** Una clasificacion hecha: los primeros con nombre, y el puesto y el valor de todos los que puntuan. */
    record Clasificacion(List<Rankings.Fila> top, Map<UUID, Integer> puestos, Map<UUID, Long> valores) {
        static final Clasificacion VACIA = new Clasificacion(List.of(), Map.of(), Map.of());
    }

    /** Lo que pide un %lethalworld_top_...%. n = 0 en pos y yo. */
    record Peticion(String clave, int n, String que, boolean semana) {
    }

    /** De mas a menos; a igual valor, por UUID (como Rankings.reparto). */
    private static final Comparator<Map.Entry<UUID, Long>> ORDEN =
            Map.Entry.<UUID, Long>comparingByValue().reversed().thenComparing(e -> e.getKey().toString());

    private Tops() {
    }

    // ------------------------------------------------------------------ clasificar

    /** La clasificacion de jugador -> valor. nombres solo se llama para los top primeros. */
    static Clasificacion clasificacion(Map<UUID, Long> valores, int top, Function<UUID, String> nombres) {
        List<Map.Entry<UUID, Long>> l = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : valores.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) l.add(e);
        }
        l.sort(ORDEN);
        List<Rankings.Fila> primeros = new ArrayList<>();
        Map<UUID, Integer> puestos = new HashMap<>();
        Map<UUID, Long> vals = new HashMap<>();
        for (int i = 0; i < l.size(); i++) {
            UUID u = l.get(i).getKey();
            long v = l.get(i).getValue();
            puestos.put(u, i + 1);
            vals.put(u, v);
            if (i < top) primeros.add(new Rankings.Fila(u, nombres.apply(u), v));
        }
        return new Clasificacion(List.copyOf(primeros), Map.copyOf(puestos), Map.copyOf(vals));
    }

    /** Una clasificacion por clave de un bloque de stats (jugador -> clave -> valor). Mutable, para anadir. */
    static Map<String, Clasificacion> clasificar(Map<UUID, Map<String, Long>> stats, int top, Function<UUID, String> nombres) {
        Map<String, Map<UUID, Long>> porClave = new HashMap<>();
        for (Map.Entry<UUID, Map<String, Long>> e : stats.entrySet()) {
            for (Map.Entry<String, Long> v : e.getValue().entrySet()) {
                porClave.computeIfAbsent(v.getKey(), k -> new HashMap<>()).put(e.getKey(), v.getValue());
            }
        }
        Map<String, Clasificacion> out = new HashMap<>();
        for (Map.Entry<String, Map<UUID, Long>> e : porClave.entrySet()) {
            out.put(e.getKey(), clasificacion(e.getValue(), top, nombres));
        }
        return out;
    }

    // ------------------------------------------------------------------ peticion

    /**
     * Lo que va detras de "top_". La clave puede llevar guiones (tasado-mc), no guiones bajos:
     * se lee desde el final. Null si no es una peticion valida (PAPI deja el texto tal cual).
     */
    static Peticion leer(String resto, int maximo) {
        if (resto == null || resto.isEmpty()) return null;
        String r = resto.toLowerCase(Locale.ROOT);
        boolean semana = r.endsWith("_semana");
        if (semana) r = r.substring(0, r.length() - "_semana".length());
        String[] t = r.split("_");
        if (t.length < 2) return null;
        String que = t[t.length - 1];
        if (que.equals("pos") || que.equals("yo")) {
            String clave = String.join("_", Arrays.copyOfRange(t, 0, t.length - 1));
            return clave.isEmpty() ? null : new Peticion(clave, 0, que, semana);
        }
        if (t.length < 3 || !(que.equals("nombre") || que.equals("valor") || que.equals("texto"))) return null;
        int n;
        try {
            n = Integer.parseInt(t[t.length - 2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (n < 1 || n > maximo) return null;
        String clave = String.join("_", Arrays.copyOfRange(t, 0, t.length - 2));
        return clave.isEmpty() ? null : new Peticion(clave, n, que, semana);
    }

    /** La respuesta, de las clasificaciones del periodo que pide (total o semana). yo puede ser null. */
    static String responder(Peticion p, Map<String, Clasificacion> clasificaciones, UUID yo) {
        Clasificacion c = clasificaciones.getOrDefault(p.clave(), Clasificacion.VACIA);
        return switch (p.que()) {
            case "pos" -> {
                Integer puesto = yo == null ? null : c.puestos().get(yo);
                yield puesto == null ? NADIE : String.valueOf(puesto);
            }
            case "yo" -> yo == null ? NADIE : texto(p.clave(), c.valores().getOrDefault(yo, 0L));
            default -> {
                Rankings.Fila f = p.n() <= c.top().size() ? c.top().get(p.n() - 1) : null;
                yield switch (p.que()) {
                    case "nombre" -> f == null ? NADIE : f.nombre();
                    case "texto" -> f == null ? NADIE : texto(p.clave(), f.valor());
                    default -> String.valueOf(f == null ? 0 : crudo(p.clave(), f.valor()));
                };
            }
        };
    }

    /** El valor como lo daba %lethalworld_top_..._valor% antes de 1.3.2: horas-activas en horas enteras. */
    static long crudo(String clave, long v) {
        return clave.equals("horas-activas") ? v / 3600 : v;
    }

    /** Como se lee: el formato del menu del Cazador. horas-activas va en segundos, como la expedicion. */
    static String texto(String clave, long v) {
        return Npcs.valorRanking(clave.equals("horas-activas") ? "expedicion-max-seg" : clave, v);
    }

    // ------------------------------------------------------------------ autotest

    /** El nucleo sin datos reales; las dos ultimas pasan por el placeholder registrado de verdad. */
    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();

        // Leer la peticion.
        h.igual("nombre del 1.o", new Peticion("tasado-mc", 1, "nombre", false), leer("tasado-mc_1_nombre", 10));
        h.igual("texto de la semana, en mayusculas", new Peticion("cazas-validas", 3, "texto", true),
                leer("CAZAS-VALIDAS_3_TEXTO_SEMANA", 10));
        h.igual("valor, como antes", new Peticion("parcas", 10, "valor", false), leer("parcas_10_valor", 10));
        h.igual("puesto del que mira", new Peticion("expedicion-max-seg", 0, "pos", false), leer("expedicion-max-seg_pos", 10));
        h.igual("lo suyo de la semana", new Peticion("extracciones", 0, "yo", true), leer("extracciones_yo_semana", 10));
        h.igual("esencias", new Peticion(ESENCIAS, 2, "texto", false), leer("esencias_2_texto", 10));
        h.igual("n fuera de rango", null, leer("parcas_11_nombre", 10));
        h.igual("n cero", null, leer("parcas_0_nombre", 10));
        h.igual("n que no es numero", null, leer("parcas_uno_nombre", 10));
        h.igual("que desconocido", null, leer("parcas_1_cara", 10));
        h.igual("sin n", null, leer("parcas_nombre", 10));
        h.igual("sin clave", null, leer("_pos", 10));
        h.igual("solo _semana", null, leer("_semana", 10));
        h.igual("vacio", null, leer("", 10));

        // Como se lee.
        h.igual("miles con punto", "12.345", texto("ecos-cerrados", 12_345));
        h.igual("MobCoins", "1.234.567 MC", texto("tasado-mc", 1_234_567));
        h.igual("expedicion larga", "2 h 14 min", texto("expedicion-max-seg", 2 * 3600 + 14 * 60 + 30));
        h.igual("expedicion con minutos de una cifra", "1 h 05 min", texto("expedicion-max-seg", 3900));
        h.igual("expedicion corta", "45 min", texto("expedicion-max-seg", 45 * 60));
        h.igual("horas activas como la expedicion", "12 h 05 min", texto("horas-activas", 12 * 3600 + 5 * 60));
        h.igual("saldo de esencias", "1.500", texto(ESENCIAS, 1500));
        h.igual("cero", "0", texto("parcas", 0));

        // Puestos y empates: A 500, B/C/D 300 (empate), E 0 (no puntua), F 100.
        UUID a = Autotest.sintetico(1), b = Autotest.sintetico(2), c = Autotest.sintetico(3);
        UUID d = Autotest.sintetico(4), e = Autotest.sintetico(5), f = Autotest.sintetico(6);
        Map<String, UUID> jugadores = Map.of("A", a, "B", b, "C", c, "D", d, "E", e, "F", f);
        Function<UUID, String> nombres = u -> jugadores.entrySet().stream()
                .filter(x -> x.getValue().equals(u)).map(Map.Entry::getKey).findFirst().orElse("?");
        // Metidos al reves para ver que el orden no depende de como llegan.
        Map<UUID, Long> v = new LinkedHashMap<>();
        v.put(f, 100L);
        v.put(e, 0L);
        v.put(d, 300L);
        v.put(c, 300L);
        v.put(b, 300L);
        v.put(a, 500L);
        Clasificacion cl = clasificacion(v, 3, nombres);
        h.igual("con nombre solo los 3 primeros", List.of("A", "B", "C"), cl.top().stream().map(Rankings.Fila::nombre).toList());
        h.igual("puntuan 5: el de 0 no", 5, cl.puestos().size());
        h.igual("empate a 300: B #2, C #3, D #4 (por UUID)", List.of(2, 3, 4),
                List.of(cl.puestos().get(b), cl.puestos().get(c), cl.puestos().get(d)));
        h.igual("el de 100, el ultimo", 5, cl.puestos().get(f));
        h.igual("el de 0, sin puesto", null, cl.puestos().get(e));
        Map<UUID, Long> alDerecho = new LinkedHashMap<>();
        for (UUID u : List.of(a, b, c, d, e, f)) alDerecho.put(u, v.get(u));
        h.igual("mismo orden venga como venga", cl.puestos(), clasificacion(alDerecho, 3, nombres).puestos());

        // Las respuestas.
        Map<String, Clasificacion> m = Map.of("tasado-mc", cl);
        h.igual("nombre del 2.o", "B", responder(leer("tasado-mc_2_nombre", 10), m, null));
        h.igual("texto del 1.o", "500 MC", responder(leer("tasado-mc_1_texto", 10), m, null));
        h.igual("valor crudo del 3.o", "300", responder(leer("tasado-mc_3_valor", 10), m, null));
        h.igual("hueco vacio: nombre", NADIE, responder(leer("tasado-mc_4_nombre", 10), m, null));
        h.igual("hueco vacio: texto", NADIE, responder(leer("tasado-mc_4_texto", 10), m, null));
        h.igual("hueco vacio: valor", "0", responder(leer("tasado-mc_4_valor", 10), m, null));
        h.igual("puesto de D, fuera del top", "4", responder(leer("tasado-mc_pos", 10), m, d));
        h.igual("lo de D", "300 MC", responder(leer("tasado-mc_yo", 10), m, d));
        h.igual("puesto de quien no puntua", NADIE, responder(leer("tasado-mc_pos", 10), m, e));
        h.igual("lo de quien no puntua", "0 MC", responder(leer("tasado-mc_yo", 10), m, e));
        h.igual("puesto sin jugador", NADIE, responder(leer("tasado-mc_pos", 10), m, null));
        h.igual("lo mio sin jugador", NADIE, responder(leer("tasado-mc_yo", 10), m, null));
        h.igual("clave que nadie tiene", NADIE, responder(leer("parcas_1_nombre", 10), m, a));
        Clasificacion horas = clasificacion(Map.of(a, 12L * 3600 + 5 * 60), 10, nombres);
        h.igual("horas activas: valor en horas, como antes", "12",
                responder(leer("horas-activas_1_valor", 10), Map.of("horas-activas", horas), null));
        h.igual("horas activas: texto", "12 h 05 min",
                responder(leer("horas-activas_1_texto", 10), Map.of("horas-activas", horas), null));

        // Por el registro de verdad (Rankings), con una clave que nadie tiene.
        h.igual("placeholder: puesto sin datos", NADIE, PlaceholdersLethal.resolver(null, "top_autotest-sin-datos_pos"));
        h.igual("placeholder: texto de la semana sin datos", NADIE,
                PlaceholdersLethal.resolver(null, "top_autotest-sin-datos_1_texto_semana"));
        return h.lineas();
    }
}
