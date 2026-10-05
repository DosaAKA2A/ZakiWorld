package net.ederus.calamity.hardcore;

import net.ederus.calamity.ComandoRaiz;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calamity 1.12 · /calamity selftest commands: que no vuelva un comando en espanol ni un comando
 * de jugador.
 *
 * Dosa: "TODOS los comandos deberian ser con /calamity y no deberia haber comandos en espanol" y
 * "nadie sin op o admin/owner/dev deberia tener permiso de usar /calamity ni ningun subcomando".
 * Esto lo comprueba sobre lo que hay de verdad al arrancar:
 *  - el plugin.yml: un solo comando, /calamity, con calamity.admin; ni rastro del de antes;
 *  - cada subcomando registrado (y los de ComandoRaiz): nombre en ingles, pide calamity.admin, y su
 *    uso, su tab y los argumentos fijos sin palabras de los comandos de antes;
 *  - lo que abren los NPCs y los nombres de /calamity selftest, en ingles;
 *  - los textos de fabrica (config.yml, objetos-calamity.yml, plugin.yml) y la config viva del
 *    servidor: ninguna linea con la raiz vieja, /lw hardcore o un subcomando en espanol. En la
 *    config viva no rompe nada (ComandosViejos lo traduce), pero se senala para cambiarlo;
 *  - ComandosViejos traduce bien lo que puede quedar guardado en el servidor.
 *
 * Los ids de datos (objetos de give, minijefes, trueques, encuestas, especiales de las Reliquias)
 * no son comandos y se quedan como estan.
 */
final class PruebaComandos {

    private PruebaComandos() {
    }

    /** Los subcomandos de antes (staff y jugador) que no pueden volver como nombre de subcomando. */
    static final Set<String> SUBS_VIEJOS = Set.of("saldo", "eco", "contratos", "cambiar", "camino", "cronista",
            "tablero", "encuesta", "deseos", "dar", "abrir", "autotest", "vara", "tiempo", "frasco", "cristal",
            "esencia", "cordura", "aduana", "amenazas", "combate", "voto", "creditos", "equipo", "exento", "hitos",
            "horas", "ligado", "engarzador", "minijefe", "piedad", "objetos", "parca", "racha", "reliquia", "tasar",
            "telemetria", "llegada", "salida", "entrada", "puerta-salida");

    /** Los argumentos fijos y las palabras de uso de antes (no son ids de datos). */
    static final Set<String> ARGS_VIEJOS = Set.of("contar", "limpiar", "prueba", "etiquetar", "llegada", "cable",
            "borrar", "iniciar", "parar", "crear", "lista", "despertar", "matar", "abrir", "cerrar", "simular",
            "poner", "quitar", "muerte", "probar", "vida", "habilidad", "retirar", "anomalia", "ver", "entrada",
            "salida", "umbral", "forja", "mercader", "tasador", "cronista", "cazador", "engarzador", "sentencia",
            "camino", "todo", "parca", "aduana", "si", "--forzar-valida", "valida", "jugador", "objeto", "origen",
            "tipo", "minutos", "segundos", "días", "dias", "módulo", "modulo", "nombre", "valor", "presa", "pagador",
            "especial", "grado", "fracción", "capítulo", "escalón", "trueque", "cantidad");

    /** Un subcomando o un id que se escribe: minusculas, sin acentos; guiones solo en medio. */
    private static final Pattern NOMBRE = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    /** Una linea de texto con un comando viejo: la raiz en espanol, su alias o /lw hardcore|level. */
    private static final Pattern VIEJO = Pattern.compile(
            "(/calamidad\\b|(^|[\\s\"'\\[,])calamidad\\s+[a-z]|(^|[\\s\"'/\\[,])cld\\s|\\blw\\s+(hardcore|level)\\b)");

    /** "calamity <palabra>" en un texto: la palabra es el subcomando. */
    private static final Pattern CALAMITY = Pattern.compile("(?:^|[\\s\"'/\\[,])(?:calamity|cal)\\s+([a-z][a-z-]*)");

    static List<String> autotest(Hardcore hc) {
        Autotest.Hoja h = new Autotest.Hoja();

        // --- plugin.yml: un solo comando, el de staff.
        Map<String, Map<String, Object>> cmds = hc.plugin().getDescription().getCommands();
        h.igual("plugin.yml: un solo comando", Set.of("calamity"), cmds.keySet());
        PluginCommand cal = hc.plugin().getCommand("calamity");
        h.ok("/calamity existe", cal != null);
        if (cal != null) {
            h.igual("/calamity pide calamity.admin", Subcomandos.PERMISO, cal.getPermission());
            h.igual("/calamity: su unico alias es /cal", List.of("cal"), cal.getAliases());
        }
        h.ok("calamity.admin declarado (default op)",
                hc.plugin().getDescription().getPermissions().stream().anyMatch(p -> p.getName().equals(Subcomandos.PERMISO)));
        h.ok("lethalworld.calamity ya no se declara",
                hc.plugin().getDescription().getPermissions().stream().noneMatch(p -> p.getName().equals("lethalworld.calamity")));

        // --- los subcomandos: nombre, permiso, uso y tab.
        // Lo que sale en el tab y no es una palabra de comando: los jugadores conectados y los ids de
        // datos, que no se traducen (las habilidades de la Parca, "reaper ability umbral|sentencia", y
        // las preguntas de la encuesta, "poll open camino").
        Set<String> saltar = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) saltar.add(p.getName().toLowerCase(Locale.ROOT));
        for (String a : HabilidadParca.nombres()) saltar.add(a.toLowerCase(Locale.ROOT));
        Encuesta enc = hc.encuesta();
        if (enc != null) {
            try {
                for (String id : enc.preguntas().keySet()) saltar.add(id.toLowerCase(Locale.ROOT));
            } catch (Throwable ignorado) {
                // Sin preguntas legibles no hay nada que saltar.
            }
        }
        List<String> todos = new ArrayList<>(ComandoRaiz.PROPIOS);
        todos.addAll(Subcomandos.staff().nombres(null));
        for (String s : todos) nombreIngles(h, "subcomando " + s, s);
        for (String s : Subcomandos.staff().nombres(null)) {
            h.igual("/calamity " + s + " pide calamity.admin", Subcomandos.PERMISO, Subcomandos.staff().permiso(s));
            usoIngles(h, "/calamity " + s, Subcomandos.staff().ayuda(s));
            List<String> fijos = Subcomandos.staff().tab(null, new String[]{s, ""});
            for (String f : fijos) {
                if (saltar.contains(f.toLowerCase(Locale.ROOT))) continue;
                h.ok("tab de /calamity " + s + ": '" + f + "' no es de antes", !ARGS_VIEJOS.contains(f.toLowerCase(Locale.ROOT)));
                for (String g : Subcomandos.staff().tab(null, new String[]{s, f, ""})) {
                    if (saltar.contains(g.toLowerCase(Locale.ROOT))) continue;
                    h.ok("tab de /calamity " + s + " " + f + ": '" + g + "' no es de antes",
                            !ARGS_VIEJOS.contains(g.toLowerCase(Locale.ROOT)));
                }
            }
        }
        for (String[] a : ComandoRaiz.ayudaPropia()) usoIngles(h, "/calamity " + a[0], a[0] + ": " + a[1]);
        for (String v : ComandoRaiz.DEFINE) nombreIngles(h, "define " + v, v);
        for (String v : ComandoRaiz.ITEMS) nombreIngles(h, "item " + v, v);

        // --- lo que abren los NPCs (open) y los modulos de selftest.
        for (String d : Npcs.destinos()) nombreIngles(h, "open <player> " + d, d);
        h.ok("los jugadores no tienen subcomandos: lo suyo va por open", !Subcomandos.jugador().nombres(null).isEmpty());
        for (String m : Autotest.modulos()) {
            h.ok("selftest: el modulo " + m + " tiene nombre en ingles", Autotest.NOMBRES.containsKey(m));
            nombreIngles(h, "selftest " + Autotest.nombre(m), Autotest.nombre(m));
        }

        // --- los textos de fabrica y la config viva.
        for (String f : List.of("config.yml", "objetos-calamity.yml", "plugin.yml")) {
            String texto = recurso(hc, f);
            h.ok(f + " del jar se lee", texto != null);
            if (texto != null) textoSinViejos(h, f, texto);
        }
        textoSinViejos(h, "config del servidor", hc.plugin().getConfig().saveToString());

        // --- ComandosViejos: lo que puede quedar guardado en el servidor.
        h.igual("viejo: premio de hito", "calamity give llave-hito Dosa__ 1",
                ComandosViejos.traducir("lw hardcore dar llave-hito Dosa__ 1"));
        h.igual("viejo: clic de Citizens", "calamity open Dosa__ merchant", ComandosViejos.traducir("calamidad abrir Dosa__ mercader"));
        h.igual("viejo: alias corto y prefijo", "calamity selftest all", ComandosViejos.traducir("calamity:cld autotest todo"));
        h.igual("viejo: autotest de un modulo", "calamity selftest fallen-vault", ComandosViejos.traducir("cld autotest boveda-caida"));
        h.igual("viejo: exento", "calamity exempt si reaper on", ComandosViejos.traducir("calamidad exento si parca si"));
        h.igual("viejo: /lw level", "/calamity level Dosa__", ComandosViejos.traducir("/lw level Dosa__"));
        h.igual("viejo: atajo del frasco", "calamity item flask Dosa__", ComandosViejos.traducir("calamidad frasco Dosa__"));
        h.igual("viejo: punto de llegada", "calamity define arrival", ComandosViejos.traducir("lw hardcore llegada"));
        h.igual("viejo: define", "calamity define entry", ComandosViejos.traducir("calamidad define entrada"));
        h.igual("viejo: parca", "calamity reaper health 0.5", ComandosViejos.traducir("calamidad parca vida 0.5"));
        h.igual("viejo: Eco", "calamity echo kill e-1 Dosa__ --force-valid",
                ComandosViejos.traducir("calamidad eco matar e-1 Dosa__ --forzar-valida"));
        h.igual("lo que no es viejo no se toca", "lw list", ComandosViejos.traducir("lw list"));
        h.igual("lo nuevo no se toca", "calamity give esencia Dosa__ 10", ComandosViejos.traducir("calamity give esencia Dosa__ 10"));
        h.igual("un nombre de jugador no se traduce", "calamity give esencia todo 1", ComandosViejos.traducir("calamidad dar esencia todo 1"));
        Set<String> existen = new HashSet<>(todos);
        for (Map.Entry<String, String> e : ComandosViejos.SUBS.entrySet()) {
            h.ok("viejo " + e.getKey() + " lleva a un subcomando que existe (" + e.getValue() + ")", existen.contains(e.getValue()));
        }
        return h.lineas();
    }

    private static void nombreIngles(Autotest.Hoja h, String que, String nombre) {
        String n = nombre == null ? "" : nombre;
        h.ok(que + ": se escribe en minusculas y sin acentos", NOMBRE.matcher(n).matches());
        h.ok(que + ": no es una palabra de los comandos de antes",
                !SUBS_VIEJOS.contains(n) && !ARGS_VIEJOS.contains(n));
    }

    /** El uso es lo de antes de ": " en la ayuda; sus palabras no pueden ser de los comandos de antes. */
    private static void usoIngles(Autotest.Hoja h, String que, String ayuda) {
        int dos = ayuda == null ? -1 : ayuda.indexOf(": ");
        String uso = dos < 0 ? (ayuda == null ? "" : ayuda) : ayuda.substring(0, dos);
        List<String> malas = new ArrayList<>();
        for (String w : uso.toLowerCase(Locale.ROOT).split("[^\\p{L}\\-]+")) {
            if (w.isEmpty()) continue;
            if (SUBS_VIEJOS.contains(w) || ARGS_VIEJOS.contains(w)) malas.add(w);
        }
        h.ok(que + ": uso en ingles (" + uso + ")" + (malas.isEmpty() ? "" : " sobra " + malas), malas.isEmpty());
    }

    /** Cada linea con la raiz vieja, /lw hardcore o "calamity <subcomando de antes>", un fallo. */
    static void textoSinViejos(Autotest.Hoja h, String donde, String texto) {
        int n = 0, malas = 0;
        for (String linea : texto.split("\\R")) {
            n++;
            boolean mala = VIEJO.matcher(linea).find();
            Matcher m = CALAMITY.matcher(linea);
            while (!mala && m.find()) {
                String w = m.group(1).toLowerCase(Locale.ROOT);
                mala = SUBS_VIEJOS.contains(w) || ARGS_VIEJOS.contains(w);
            }
            if (mala) {
                malas++;
                h.ok(donde + ", linea " + n + ": comando de antes: " + linea.strip(), false);
            }
        }
        if (malas == 0) h.ok(donde + ": sin comandos de antes", true);
    }

    private static String recurso(Hardcore hc, String nombre) {
        try (InputStream in = hc.plugin().getResource(nombre)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
