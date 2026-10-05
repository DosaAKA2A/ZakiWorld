package net.ederus.calamity.hardcore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Calamity 1.12 · Lo que se escribia antes, traducido al /calamity de ahora. TEMPORAL.
 *
 * Hasta la 1.11 el staff tenia un comando en espanol con su alias corto, y antes /lw hardcore y
 * /lw level. Desde la 1.12 todo es /calamity y en ingles, pero en el servidor quedan lineas
 * guardadas con lo viejo que no tienen por que romperse el dia del cambio:
 *  - los NPCs de Citizens (su clic, "... abrir <p> mercader");
 *  - las recompensas del config.yml de Calamity (hitos, contratos, rankings: "lw hardcore dar ...");
 *  - cualquier crate, menu o script que lance "lw hardcore ..." por consola.
 *
 * traducir() lo pasa a la forma nueva: la raiz, el subcomando y los argumentos fijos que han
 * cambiado de nombre (los ids de objetos, de trueques, de minijefes y demas datos no cambian).
 * Lo usan RedireccionComandos (lo escrito y el puente de /lw), el comando oculto de consola que
 * monta CalamityPlugin y los modulos que lanzan comandos de la config.
 *
 * Cuando la consola deje de avisar "comando viejo" (ver RedireccionComandos), esto se puede quitar
 * junto con el comando oculto.
 */
public final class ComandosViejos {

    private ComandosViejos() {
    }

    /** Las raices de antes que van enteras a /calamity (con y sin el prefijo del plugin). */
    public static final Set<String> RAICES = Set.of("calamidad", "cld", "calamity:calamidad", "calamity:cld");

    /** Como se puede escribir /lw: su nombre, el alias del plugin.yml y los dos con prefijo. */
    public static final Set<String> RAICES_LW = Set.of("lw", "lethalworld", "lethalworld:lw", "lethalworld:lethalworld");

    /** El subcomando viejo y el de ahora. Lo que no esta aqui se queda igual (level, reload, vault...). */
    static final Map<String, String> SUBS = Map.ofEntries(
            Map.entry("vara", "wand"), Map.entry("tiempo", "time"), Map.entry("cordura", "sanity"),
            Map.entry("aduana", "customs"), Map.entry("amenazas", "threats"), Map.entry("autotest", "selftest"),
            Map.entry("combate", "combat"), Map.entry("contratos", "contracts"), Map.entry("deseos", "wishes"),
            Map.entry("eco", "echo"), Map.entry("encuesta", "poll"), Map.entry("voto", "lootvote"),
            Map.entry("dar", "give"), Map.entry("saldo", "balance"), Map.entry("creditos", "credits"),
            Map.entry("equipo", "gear"), Map.entry("exento", "exempt"), Map.entry("hitos", "milestones"),
            Map.entry("horas", "hours"), Map.entry("ligado", "bind"), Map.entry("engarzador", "npcs"),
            Map.entry("minijefe", "miniboss"), Map.entry("piedad", "pity"), Map.entry("abrir", "open"),
            Map.entry("objetos", "items"), Map.entry("parca", "reaper"), Map.entry("racha", "streak"),
            Map.entry("reliquia", "relic"), Map.entry("tasar", "appraise"), Map.entry("telemetria", "telemetry"));

    /** Los argumentos fijos de cada subcomando (ya traducido) que han cambiado, por posicion. */
    private static final Map<String, Map<String, String>> ARGS = Map.ofEntries(
            Map.entry("altar", Map.of("probar", "test", "abrir", "open", "umbral", "altar", "forja", "forge",
                    "camino", "path")),
            Map.entry("threats", Map.of("contar", "count", "limpiar", "clear", "prueba", "test")),
            Map.entry("selftest", Map.of("todo", "all")),
            Map.entry("combat", Map.of("etiquetar", "tag", "llegada", "arrival", "cable", "disconnect", "borrar", "clear")),
            Map.entry("eclipse", Map.of("iniciar", "start", "parar", "stop")),
            Map.entry("echo", Map.of("crear", "create", "lista", "list", "borrar", "delete", "prueba", "test",
                    "despertar", "wake", "matar", "kill", "--forzar-valida", "--force-valid")),
            Map.entry("poll", Map.of("abrir", "open", "cerrar", "close", "simular", "simulate")),
            Map.entry("exempt", Map.of("parca", "reaper", "aduana", "customs", "todo", "all", "todos", "all",
                    "ambas", "all", "si", "on", "no", "off")),
            Map.entry("bind", Map.of("poner", "set", "quitar", "remove")),
            Map.entry("miniboss", Map.of("muerte", "death")),
            Map.entry("open", Map.of("umbral", "altar", "forja", "forge", "mercader", "merchant", "tasador", "merchant",
                    "cronista", "chronicler", "cazador", "hunter", "engarzador", "gemsetter", "sentencia", "bounty")),
            Map.entry("items", Map.of("probar", "test")),
            Map.entry("reaper", Map.of("vida", "health", "habilidad", "ability", "retirar", "remove", "prueba", "test",
                    "anomalia", "anomaly")),
            Map.entry("ranking", Map.of("ver", "view", "cerrar", "close")),
            Map.entry("define", Map.of("entrada", "entry", "salida", "exit")));

    /**
     * En que posiciones de cada subcomando van esos argumentos fijos (1 = justo detras del
     * subcomando). Fuera de ellas no se toca nada: un jugador que se llame "si" o "lista" sigue
     * siendo un nombre. Sin entrada, solo la 1.
     */
    private static final Map<String, Set<Integer>> POSICIONES = Map.of(
            "altar", Set.of(1, 2),
            "combat", Set.of(1, 3),
            "echo", Set.of(1, 3, 4),
            "exempt", Set.of(2, 3),
            "open", Set.of(2));

    /** Los atajos viejos que eran un subcomando suelto y ahora van debajo de otro. */
    private static final Map<String, String[]> ATAJOS = Map.of(
            "frasco", new String[]{"item", "flask"},
            "cristal", new String[]{"item", "crystal"},
            "esencia", new String[]{"item", "essence"},
            "llegada", new String[]{"define", "arrival"},
            "salida", new String[]{"define", "return"});

    /**
     * La linea (sin la barra o con ella, se respeta) en la forma de ahora, o la misma si no era
     * nada viejo. Nunca null salvo que entre null.
     */
    public static String traducir(String linea) {
        String nueva = viejo(linea);
        return nueva == null ? linea : nueva;
    }

    /** La linea traducida, o null si no era una forma vieja de Calamity. */
    public static String viejo(String linea) {
        if (linea == null) return null;
        String l = linea.strip();
        boolean barra = l.startsWith("/");
        if (barra) l = l.substring(1);
        if (l.isEmpty()) return null;
        List<String> t = new ArrayList<>(Arrays.asList(l.split(" +")));
        String raiz = t.get(0).toLowerCase(Locale.ROOT);
        List<String> args;
        if (RAICES.contains(raiz)) {
            args = t.subList(1, t.size());
        } else if (RAICES_LW.contains(raiz) && t.size() >= 2 && t.get(1).equalsIgnoreCase("hardcore")) {
            args = t.subList(2, t.size());
        } else if (RAICES_LW.contains(raiz) && t.size() >= 2 && t.get(1).equalsIgnoreCase("level")) {
            args = t.subList(1, t.size());
        } else {
            return null;
        }
        List<String> out = new ArrayList<>();
        out.add("calamity");
        out.addAll(argumentos(args));
        return (barra ? "/" : "") + String.join(" ", out);
    }

    /** Los argumentos de despues de la raiz, ya en la forma de ahora. */
    static List<String> argumentos(List<String> args) {
        List<String> a = new ArrayList<>(args);
        if (a.isEmpty()) return a;
        String sub = a.get(0).toLowerCase(Locale.ROOT);
        String[] atajo = ATAJOS.get(sub);
        if (atajo != null) {
            a.remove(0);
            a.add(0, atajo[1]);
            a.add(0, atajo[0]);
            return a;
        }
        String nuevo = SUBS.getOrDefault(sub, sub);
        a.set(0, nuevo);
        if (nuevo.equals("selftest") && a.size() > 1) {
            String m = a.get(1).toLowerCase(Locale.ROOT);
            a.set(1, m.equals("todo") ? "all" : Autotest.nombre(m));
            return a;
        }
        Map<String, String> fijos = ARGS.get(nuevo);
        if (fijos == null) return a;
        Set<Integer> donde = POSICIONES.getOrDefault(nuevo, Set.of(1));
        for (int i = 1; i < a.size(); i++) {
            if (!donde.contains(i)) continue;
            String v = fijos.get(a.get(i).toLowerCase(Locale.ROOT));
            if (v != null) a.set(i, v);
        }
        return a;
    }
}
