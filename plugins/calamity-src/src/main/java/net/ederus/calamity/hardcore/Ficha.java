package net.ederus.calamity.hardcore;

import net.ederus.calamity.CalamityPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calamity 1.10 · La plantilla comun del lore de los objetos propios de Calamity (Reliquias, Frasco,
 * Cristal, Esencia, Reclamo, Talisman, Grabado, Salvoconducto, Fragmento de Masamune, pergaminos de
 * contrato y trofeos). Dosa vio los de antes en el juego y le parecieron "sosos" y "ambiguos"; esta es
 * la forma unica de todos:
 *
 * <pre>
 *   Nombre                                (lo pone quien crea el objeto, en su color)
 *   Categoria · detalle ★★☆☆              tipo()
 *   ────────────────────────              filete()
 *   Una linea de historia en gris.        historia()
 *   Cayo del Heraldo Carmesi              texto()
 *   ────────────────────────
 *   Valor al salir vivo                   etiqueta()
 *    6 Esencias · 100 MobCoins            dato()
 *   ────────────────────────
 *   ▸ Que hacer con el.                   accion()
 *   Si mueres antes de salir, la pierdes. nota()
 * </pre>
 *
 * Reglas de la casa que se cumplen aqui para que ningun objeto tenga que acordarse:
 *  - un solo acento por objeto (el color de su nombre): estrellas, el ▸ y lo que va entre llaves en el
 *    texto ("{6} Esencias" pinta el 6 con el acento); lo demas, blanco hueso y grises;
 *  - sin cursiva ni negrita en ninguna linea;
 *  - lineas de 38 caracteres como mucho (Bedrock parte mal las largas): todo se corta por palabras;
 *  - el filete es una fila fija de rayas en gris oscuro.
 *
 * Es pura (solo Adventure): lineas() devuelve el texto plano y el autotest lo compara sin servidor.
 */
final class Ficha {

    /** Lo mas largo que puede ser una linea, sangria incluida. */
    static final int ANCHO = 38;
    /** Rayas del filete: con las medidas de Marco.ancho, lo mismo que una linea llena de texto. */
    static final int RAYAS = 24;
    static final String RAYA = "─";
    /** Etiquetas y avisos: gris neutro, el que pidio Dosa (#8A8A8A). */
    static final TextColor ETIQUETA = TextColor.color(0x8A8A8A);
    /** La historia de cada objeto: el gris calido de la Paleta, mas claro que las etiquetas. */
    static final TextColor HISTORIA = Paleta.TENUE;
    static final TextColor TEXTO = Paleta.TEXTO;

    private record Trozo(String texto, TextColor color) {
    }

    private final TextColor acento;
    private final List<List<Trozo>> lineas = new ArrayList<>();

    Ficha(TextColor acento) {
        this.acento = acento == null ? Paleta.MARCA : acento;
    }

    // ------------------------------------------------------------------ piezas

    /** "Reliquia · Grado III {★★★}☆": la categoria en gris, lo que va entre llaves en el acento. */
    Ficha tipo(String marcado) {
        return anadir("", "", marcado, ETIQUETA);
    }

    Ficha filete() {
        lineas.add(List.of(new Trozo(RAYA.repeat(RAYAS), Paleta.FILETE)));
        return this;
    }

    /** La linea de historia de cada tipo: gris, sin acento. */
    Ficha historia(String texto) {
        return anadir("", "", texto, HISTORIA);
    }

    /** Texto normal (blanco hueso), con {cifras} en el acento. */
    Ficha texto(String marcado) {
        return anadir("", "", marcado, TEXTO);
    }

    /** El titulo de un bloque de datos ("Valor al salir vivo", "Objetivo"): gris. */
    Ficha etiqueta(String texto) {
        return anadir("", "", texto, ETIQUETA);
    }

    /** Un dato bajo su etiqueta, con un espacio de sangria. */
    Ficha dato(String marcado) {
        return anadir(" ", " ", marcado, TEXTO);
    }

    /** "▸ Clic derecho para beber.": el triangulo en el acento, lo que sigue en blanco hueso. */
    Ficha accion(String marcado) {
        int antes = lineas.size();
        anadir("  ", "  ", marcado, TEXTO);
        if (lineas.size() > antes) {
            List<Trozo> primera = new ArrayList<>(lineas.get(antes));
            // La sangria de dos espacios de la primera linea se cambia por el triangulo.
            Trozo t = primera.get(0);
            primera.set(0, new Trozo(t.texto().substring(2), t.color()));
            primera.add(0, new Trozo("▸ ", acento));
            lineas.set(antes, primera);
        }
        return this;
    }

    /** Lo que se lee al final o al margen (pieza unica, caducidad, el aviso de morir): gris. */
    Ficha nota(String marcado) {
        return anadir("", "", marcado, ETIQUETA);
    }

    // ------------------------------------------------------------------ salida

    /** El lore en texto plano, linea a linea (lo que compara el autotest). */
    List<String> lineas() {
        List<String> out = new ArrayList<>();
        for (List<Trozo> l : lineas) {
            StringBuilder sb = new StringBuilder();
            for (Trozo t : l) sb.append(t.texto());
            out.add(sb.toString());
        }
        return out;
    }

    /** El lore pintado: sin cursiva ni negrita en ningun trozo. */
    List<Component> lore() {
        List<Component> out = new ArrayList<>();
        for (List<Trozo> l : lineas) {
            TextComponent.Builder b = Component.text();
            for (Trozo t : l) if (!t.texto().isEmpty()) b.append(Component.text(t.texto(), t.color()));
            out.add(b.build().decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, false));
        }
        return out;
    }

    // ------------------------------------------------------------------ cortar

    /**
     * Pinta "marcado" (las {llaves} en el acento, lo demas en "base"), lo corta en lineas de ANCHO como
     * mucho sin partir palabras y pone "primera" delante de la primera linea y "resto" delante de las
     * demas. Una palabra mas larga que la linea va sola (no se parte).
     */
    private Ficha anadir(String primera, String resto, String marcado, TextColor base) {
        if (marcado == null || marcado.isBlank()) return this;
        StringBuilder plano = new StringBuilder();
        List<TextColor> colores = new ArrayList<>();
        boolean dentro = false;
        for (int i = 0; i < marcado.length(); i++) {
            char c = marcado.charAt(i);
            if (c == '{') {
                dentro = true;
                continue;
            }
            if (c == '}') {
                dentro = false;
                continue;
            }
            plano.append(c);
            colores.add(dentro ? acento : base);
        }
        String s = plano.toString();
        // Primero los cortes (inicio, fin) de cada linea; luego se pinta.
        List<int[]> cortes = new ArrayList<>();
        int inicio = 0;
        while (inicio < s.length()) {
            while (inicio < s.length() && s.charAt(inicio) == ' ') inicio++;
            if (inicio >= s.length()) break;
            int cabe = Math.max(1, ANCHO - (cortes.isEmpty() ? primera : resto).length());
            int fin;
            if (s.length() - inicio <= cabe) {
                fin = s.length();
            } else {
                int corte = s.lastIndexOf(' ', inicio + cabe);
                if (corte <= inicio) {
                    corte = s.indexOf(' ', inicio);
                    if (corte < 0) corte = s.length();
                }
                fin = corte;
            }
            cortes.add(new int[]{inicio, fin});
            inicio = fin;
        }
        // Sin viudas: si la ultima linea es una palabra suelta ("mano.", "50"), se le pasa la ultima palabra
        // de la anterior, siempre que quepa y a la anterior le quede algo.
        if (cortes.size() >= 2) {
            int[] ult = cortes.get(cortes.size() - 1), pen = cortes.get(cortes.size() - 2);
            if (s.indexOf(' ', ult[0]) < 0 || s.indexOf(' ', ult[0]) >= ult[1]) {
                int espacio = s.lastIndexOf(' ', pen[1] - 1);
                int cabe = Math.max(1, ANCHO - resto.length());
                if (espacio > pen[0] && ult[1] - (espacio + 1) <= cabe) {
                    int finPen = espacio;
                    while (finPen > pen[0] && s.charAt(finPen - 1) == ' ') finPen--;
                    cortes.set(cortes.size() - 2, new int[]{pen[0], finPen});
                    cortes.set(cortes.size() - 1, new int[]{espacio + 1, ult[1]});
                }
            }
        }
        for (int n = 0; n < cortes.size(); n++) {
            int desde = cortes.get(n)[0], hasta = cortes.get(n)[1];
            List<Trozo> linea = new ArrayList<>();
            linea.add(new Trozo(n == 0 ? primera : resto, base));
            StringBuilder run = new StringBuilder();
            TextColor actual = null;
            for (int k = desde; k < hasta; k++) {
                TextColor col = colores.get(k);
                if (actual != null && !actual.equals(col)) {
                    linea.add(new Trozo(run.toString(), actual));
                    run.setLength(0);
                }
                actual = col;
                run.append(s.charAt(k));
            }
            if (run.length() > 0) linea.add(new Trozo(run.toString(), actual));
            lineas.add(linea);
        }
        return this;
    }

    // ------------------------------------------------------------------ ayudas

    /** "{★★★}☆": el grado en estrellas, de 4. Las llenas en el acento, la vacia en el gris de la linea. */
    static String estrellas(int grado) {
        int g = Math.max(0, Math.min(4, grado));
        return "{" + "★".repeat(g) + "}" + "☆".repeat(4 - g);
    }

    /** "{1.500} MobCoins", "{6} Esencias": una cantidad marcada para el acento. */
    static String cantidad(long n, String uno, String varios) {
        return "{" + Altar.miles(n) + "} " + (n == 1 ? uno : varios);
    }

    /**
     * Lo que vale algo en Esencias y MobCoins, marcado: "{6} Esencias · {100} MobCoins". Las fracciones de
     * Esencia se dicen como se pagan: 0,2 es "{1} Esencia por cada {5}" (la Tasacion suma y redondea abajo).
     */
    static String valor(double esencias, long mobcoins) {
        String e = null;
        if (esencias > 0) {
            if (esencias == Math.rint(esencias)) {
                e = cantidad((long) esencias, "Esencia", "Esencias");
            } else if (esencias < 1 && Math.abs(1 / esencias - Math.rint(1 / esencias)) < 1e-6) {
                e = "{1} Esencia por cada {" + (long) Math.rint(1 / esencias) + "}";
            } else {
                e = "{" + Marco.numero(esencias) + "} Esencias";
            }
        }
        String mc = mobcoins > 0 ? cantidad(mobcoins, "MobCoin", "MobCoins") : null;
        if (e != null && mc != null) return e + " · " + mc;
        if (e != null) return e;
        return mc != null ? mc : "sin valor";
    }

    /** "{25 %}": una probabilidad 0..1 marcada. */
    static String probabilidad(double p) {
        return "{" + Marco.porcentaje(Math.max(0, Math.min(1, p))) + "}";
    }

    /**
     * La seccion hardcore de la config viva, para los objetos que se crean sin un Hardcore a mano
     * (ItemsCalamity.reclamo, el Fragmento de Masamune). Sin plugin (autotest fuera del servidor), vacia:
     * entonces valen los numeros de serie.
     */
    static ConfigurationSection cfg() {
        try {
            ConfigurationSection s = JavaPlugin.getPlugin(CalamityPlugin.class).getConfig().getConfigurationSection("hardcore");
            if (s != null) return s;
        } catch (Throwable sinPlugin) {
            // Fuera del servidor: los de serie.
        }
        return new YamlConfiguration();
    }

    /**
     * Un trueque de altar.trueques que pide un credito o un objeto: lo que da, cuantos pide y, si pide
     * algo mas de entregar, que ("una Masamune"; vacio si nada).
     */
    record Uso(String da, int cantidad, String con) {
    }

    /**
     * Para que sirve un credito (marca, fragmento, sello:<id>) en la Forja, leido de altar.trueques: las
     * piezas que pide con cuantos. En el orden de la config.
     */
    static List<Uso> usosDeCredito(ConfigurationSection c, String tipo) {
        List<Uso> out = new ArrayList<>();
        String t = Creditos.tipo(tipo);
        for (Map<?, ?> m : c.getMapList("altar.trueques")) {
            Object cr = m.get("credito");
            if (cr == null || !Creditos.tipo(String.valueOf(cr)).equals(t)) continue;
            int n = 1;
            Object v = m.get("creditos");
            if (v instanceof Number num) n = Math.max(1, num.intValue());
            out.add(new Uso(pieza(m), n, ""));
        }
        return out;
    }

    /** Lo mismo para un objeto que se entrega (fragmento-masamune): las piezas que lo piden y cuantos. */
    static List<Uso> usosDeEntrega(ConfigurationSection c, String objeto) {
        List<Uso> out = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("altar.trueques")) {
            List<Altar.Entrega> pide = Altar.entregas(m.get("entregar"));
            for (Altar.Entrega e : pide) {
                if (!e.objeto().equalsIgnoreCase(objeto)) continue;
                List<String> otros = new ArrayList<>();
                for (Altar.Entrega o : pide) {
                    if (o.objeto().equalsIgnoreCase(objeto)) continue;
                    String n = Forja.nombrePieza(o.objeto());
                    otros.add(o.cantidad() == 1 ? (n.startsWith("Yelmo") || n.startsWith("Filo") || n.startsWith("Hacha") ? "un " : "una ") + n : o.cantidad() + " " + n);
                }
                out.add(new Uso(pieza(m), e.cantidad(), String.join(" y ", otros)));
            }
        }
        return out;
    }

    /** "forja:yelmo" -> "Yelmo de Calamidad"; si no es una pieza de forja, el nombre del trueque. */
    private static String pieza(Map<?, ?> m) {
        Object da = m.get("da");
        String d = da == null ? "" : String.valueOf(da).toLowerCase(Locale.ROOT);
        if (d.startsWith("forja:")) return Forja.nombrePieza(d.substring(6));
        Object n = m.get("nombre");
        return n == null ? d : String.valueOf(n);
    }

    /** Si una lista de lineas cumple las reglas: ninguna de mas de ANCHO. La lista de las que se pasan. */
    static List<String> largas(List<String> lineas) {
        List<String> out = new ArrayList<>();
        for (String l : lineas) if (l.length() > ANCHO) out.add(l);
        return out;
    }

    // ------------------------------------------------------------------ autotest

    /** La seccion hardcore del config.yml de serie (el del jar), para probar fuera del servidor. */
    static ConfigurationSection deSerie() {
        try (java.io.InputStream in = Ficha.class.getResourceAsStream("/config.yml")) {
            if (in == null) return new YamlConfiguration();
            YamlConfiguration y = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            ConfigurationSection s = y.getConfigurationSection("hardcore");
            return s == null ? new YamlConfiguration() : s;
        } catch (java.io.IOException e) {
            return new YamlConfiguration();
        }
    }

    /**
     * "fichas": la plantilla y el lore de cada objeto propio, con la config que se le de (la viva en el
     * servidor, la de serie fuera). Comprueba que ninguna linea pasa de 38 y las cifras que salen de la
     * config. Sin Bukkit.
     */
    static List<String> autotestObjetos(ConfigurationSection c) {
        List<String> out = new ArrayList<>(autotest());
        Autotest.Hoja h = new Autotest.Hoja();
        Map<String, Ficha> todas = objetosDePrueba(c);
        List<String> largas = new ArrayList<>();
        for (Map.Entry<String, Ficha> e : todas.entrySet()) {
            for (String l : largas(e.getValue().lineas())) largas.add(e.getKey() + ": " + l);
        }
        h.igual("ninguna linea de ningun objeto pasa de 38", List.of(), largas);
        boolean filetes = true;
        for (Ficha f : todas.values()) filetes &= f.lineas().contains(RAYA.repeat(RAYAS));
        h.ok("todos llevan filete", filetes);

        double e3 = c.getDouble("reliquias.grados.3.esencias", 3);
        long mc3 = c.getLong("reliquias.grados.3.mobcoins", 40);
        List<String> ambar = todas.get("ambar").lineas();
        h.igual("Ambar: grado en estrellas", "Reliquia · Grado III ★★★☆", ambar.get(0));
        h.ok("Ambar: de quien cayo", ambar.contains("Cayó del Heraldo Carmesí"));
        h.ok("Ambar: su valor sale de la config", ambar.contains(" " + plano(valor(e3, mc3))));
        h.ok("Ambar: pieza unica y caducidad", ambar.contains("Pieza única · no se apila") && ambar.contains("Caduca el 18/10"));
        h.igual("Ambar: el aviso del final", "Si mueres antes de salir, la pierdes.", ambar.get(ambar.size() - 1));
        List<String> astilla = todas.get("astilla").lineas();
        h.ok("Astilla: sin origen ni caducidad", astilla.stream().noneMatch(l -> l.startsWith("Caduca") || l.startsWith("La soltó")));
        h.ok("Astilla: el tope del dia", astilla.contains("Se pagan hasta " + c.getInt("reliquias.tope-dia.1", 60) + " al día"));
        h.ok("Astilla: valor de cada una", astilla.contains("Valor de cada una al salir vivo"));
        List<String> sello = todas.get("sello").lineas();
        h.ok("Sello: dice que pieza desbloquea", sello.contains("Desbloquea en la Forja de Vael")
                && sello.contains(" Yelmo de Calamidad"));
        h.igual("Sello: masculino", "Si mueres antes de salir, lo pierdes.", sello.get(sello.size() - 1));
        List<String> campana = todas.get("campana").lineas();
        h.ok("Campana alta: Fragmento y Llave", campana.contains(" + 1 Fragmento de Guadaña")
                && campana.stream().anyMatch(l -> l.contains("de ganar una Llave del Caos")));
        h.ok("Campana alta: para que sirven los Fragmentos", campana.stream().anyMatch(l -> l.endsWith("· Guadaña de la Parca")));
        h.ok("Campana baja: dice desde que nivel da Fragmento", todas.get("campana-baja").lineas().stream()
                .anyMatch(l -> l.contains("nivel " + c.getInt("reliquias.especiales.campana-parca.fragmento-nivel-minimo", 40))));
        h.ok("Lagrima valida: Marca de Eco y para que sirve", todas.get("lagrima").lineas().stream()
                .anyMatch(l -> l.startsWith(" + 1 Marca de Eco")) && todas.get("lagrima").lineas().stream()
                .anyMatch(l -> l.endsWith("· Máscara del Eco")));
        h.ok("Lagrima no valida: sin Marca", todas.get("lagrima-no").lineas().stream().noneMatch(l -> l.startsWith(" + 1 Marca")));
        List<String> frasco = todas.get("frasco").lineas();
        h.ok("Frasco: tragos y cordura de la config", frasco.contains("Le quedan 2 de " + c.getInt("frasco.usos", 3) + " tragos")
                && frasco.contains("Cada trago devuelve " + c.getInt("frasco.cordura", 40) + " de cordura"));
        h.ok("Frasco: a 1 trago, en singular", ItemsCalamity.fichaFrasco(c, 1).lineas()
                .contains("Le queda 1 de " + c.getInt("frasco.usos", 3) + " tragos"));
        List<String> reclamo = todas.get("reclamo").lineas();
        h.ok("Reclamo: tope y descanso de la config",
                reclamo.contains("Hasta " + c.getInt("minijefes.reclamo.tope-dia", 6) + " al día.")
                        && reclamo.contains("Descanso de " + c.getInt("minijefes.cada-minutos", 10) + " min entre minijefes."));
        List<String> frag = todas.get("fragmento").lineas();
        h.ok("Fragmento: la Masamune y la Crimson, de altar.trueques", frag.contains(" Masamune: 5 Fragmentos")
                && frag.contains(" Crimson Masamune: 5 y una Masamune"));
        h.ok("Cristal: los segundos de la config", todas.get("cristal").lineas()
                .contains("Quieto " + c.getInt("cristal.segundos", 5) + " s: si te mueves, se apaga."));
        h.ok("Talisman: vida de la config", todas.get("talisman").lineas()
                .contains("+" + c.getInt("talisman.vida", 3) + " de vida máxima mientras lo lleves."));
        h.ok("Objetos de uso: el triangulo de la accion", todas.get("cristal").lineas().stream().anyMatch(l -> l.startsWith("▸ ")));
        out.addAll(h.lineas());
        return out;
    }

    /** Un ejemplar de cada objeto, para el autotest y para enseñarlos (el arnes los imprime). */
    static Map<String, Ficha> objetosDePrueba(ConfigurationSection c) {
        Map<String, Ficha> todas = new java.util.LinkedHashMap<>();
        todas.put("astilla", Reliquias.ficha(c, 1, null, 0, null, false, null, false, null));
        todas.put("nana", Reliquias.ficha(c, 2, null, 0, null, false, null, false, null));
        todas.put("ambar", Reliquias.ficha(c, 3, null, 45, "heraldo-carmes", false, "minijefe", true, "18/10"));
        todas.put("mayor", Reliquias.ficha(c, 4, null, 80, null, false, "mob", true, "18/10"));
        todas.put("campana", Reliquias.ficha(c, 4, Reliquias.CAMPANA, 45, null, false, "parca", true, "18/10"));
        todas.put("campana-baja", Reliquias.ficha(c, 3, Reliquias.CAMPANA, 20, null, false, "parca", true, "18/10"));
        todas.put("lagrima", Reliquias.ficha(c, 3, Reliquias.LAGRIMA, 30, null, true, "eco", true, "18/10"));
        todas.put("lagrima-no", Reliquias.ficha(c, 3, Reliquias.LAGRIMA, 30, null, false, "eco", true, "18/10"));
        todas.put("sello", Reliquias.ficha(c, 4, Reliquias.SELLO, 50, "custodio-de-las-ruinas", false, "minijefe", true, "18/10"));
        todas.put("eclipsada", Reliquias.ficha(c, 3, Reliquias.ECLIPSADA, 0, null, false, "eclipse", true, "18/10"));
        todas.put("frasco", ItemsCalamity.fichaFrasco(c, 2));
        todas.put("cristal", ItemsCalamity.fichaCristal(c));
        todas.put("esencia", ItemsCalamity.fichaEsencia());
        todas.put("fragmento", ItemsCalamity.fichaFragmento(c));
        todas.put("reclamo", ItemsCalamity.fichaReclamo(c));
        todas.put("llave-umbral", PuenteBovedas.ficha(PuenteBovedas.LLAVE_UMBRAL));
        todas.put("llave-ominosa", PuenteBovedas.ficha(PuenteBovedas.LLAVE_OMINOSA));
        todas.put("talisman", Entregas.fichaTalisman(c));
        todas.put("grabado", Entregas.fichaGrabado(c, "Filo, Protección, Eficiencia, Poder, Irrompibilidad, Botín o Fortuna"));
        todas.put("salvoconducto", Entregas.fichaSalvoconducto());
        todas.put("trofeo", Ecos.fichaTrofeo("Dosa", "04/10/2026"));
        return todas;
    }

    /** Lo marcado sin las llaves. */
    static String plano(String marcado) {
        return marcado == null ? "" : marcado.replace("{", "").replace("}", "");
    }

    /** Pruebas de la plantilla sola (sin servidor): corte, sangrias, acento y filete. */
    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        TextColor a = TextColor.color(0xE8A33D);
        Ficha f = new Ficha(a).tipo("Reliquia · Grado III " + estrellas(3)).filete()
                .historia("Resina que el bosque lloró sobre los que no volvieron.")
                .etiqueta("Valor al salir vivo").dato(valor(6, 100))
                .accion("Se vende sola al salir de Calamity por la puerta o con un Cristal.")
                .nota("Si mueres antes de salir, la pierdes.");
        List<String> l = f.lineas();
        h.igual("tipo con estrellas", "Reliquia · Grado III ★★★☆", l.get(0));
        h.igual("filete fijo", RAYA.repeat(RAYAS), l.get(1));
        h.igual("historia cortada por palabras", List.of("Resina que el bosque lloró sobre los", "que no volvieron."),
                l.subList(2, 4));
        h.igual("dato con sangria", " 6 Esencias · 100 MobCoins", l.get(5));
        h.igual("accion: triangulo y sangria de dos", List.of("▸ Se vende sola al salir de Calamity", "  por la puerta o con un Cristal."),
                l.subList(6, 8));
        h.ok("ninguna linea pasa de 38", largas(l).isEmpty());
        h.igual("sin viudas: la palabra suelta se lleva otra", List.of("Una esquirla del umbral. Se quiebra",
                "cada vez que alguien lo cruza y no", "vuelve ya."), new Ficha(a).historia(
                "Una esquirla del umbral. Se quiebra cada vez que alguien lo cruza y no vuelve ya.").lineas());
        h.igual("sin viudas: con la sangria de la accion", List.of("▸ Al cumplirlo recibes el premio en", "  la mano."),
                new Ficha(a).accion("Al cumplirlo recibes el premio en la mano.").lineas());
        h.igual("valor de una Astilla", "{1} Esencia por cada {5} · {5} MobCoins", valor(0.2, 5));
        h.igual("valor sin MobCoins", "{1} Esencia", valor(1, 0));
        h.igual("valor con decimales", "{1,5} Esencias · {1.000} MobCoins", valor(1.5, 1000));
        h.igual("estrellas I", "{★}☆☆☆", estrellas(1));
        h.igual("estrellas IV", "{★★★★}", estrellas(4));
        List<Component> lore = f.lore();
        boolean limpio = true;
        for (Component c : lore) {
            limpio &= c.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE
                    && c.decoration(TextDecoration.BOLD) == TextDecoration.State.FALSE;
        }
        h.ok("sin cursiva ni negrita", limpio);
        // Las estrellas llenas van en el acento; la vacia, en el gris de la etiqueta.
        boolean acentoEnEstrellas = false, vaciaGris = false;
        for (Component c : lore.get(0).children()) {
            if (c instanceof TextComponent t) {
                if (t.content().contains("★") && a.equals(t.color())) acentoEnEstrellas = true;
                if (t.content().contains("☆") && ETIQUETA.equals(t.color())) vaciaGris = true;
            }
        }
        h.ok("estrellas llenas en el acento", acentoEnEstrellas);
        h.ok("estrella vacia en gris", vaciaGris);
        h.igual("palabra mas larga que la linea: sola, sin partir", List.of("x".repeat(45)),
                new Ficha(a).texto("x".repeat(45)).lineas());
        h.igual("sin texto no anade nada", 0, new Ficha(a).texto("").nota(null).lineas().size());
        YamlConfiguration c = new YamlConfiguration();
        c.set("altar.trueques", List.of(
                Map.of("id", "yelmo", "credito", "sello:custodio-de-las-ruinas", "da", "forja:yelmo"),
                Map.of("id", "mascara", "credito", "marca", "creditos", 5, "da", "forja:mascara"),
                Map.of("id", "masamune", "da", "forja:masamune",
                        "entregar", List.of(Map.of("objeto", "fragmento-masamune", "cantidad", 5))),
                Map.of("id", "crimson", "da", "forja:crimson", "entregar", List.of(
                        Map.of("objeto", "masamune", "cantidad", 1), Map.of("objeto", "fragmento-masamune", "cantidad", 5)))));
        h.igual("el Sello del Custodio desbloquea el Yelmo", List.of(new Uso("Yelmo de Calamidad", 1, "")),
                usosDeCredito(c, "sello:custodio-de-las-ruinas"));
        h.igual("las Marcas, la Mascara con 5", List.of(new Uso("Máscara del Eco", 5, "")), usosDeCredito(c, "marca"));
        h.igual("los Fragmentos de Masamune: la Masamune con 5, la Crimson con 5 y una Masamune",
                List.of(new Uso("Masamune", 5, ""), new Uso("Crimson Masamune", 5, "una Masamune")),
                usosDeEntrega(c, FragmentosMasamune.OBJETO));
        return h.lineas();
    }
}
