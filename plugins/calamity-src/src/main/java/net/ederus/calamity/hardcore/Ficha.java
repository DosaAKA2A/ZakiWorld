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
 * La plantilla comun del lore de los objetos propios de Calamity (Reliquias, Frasco, Cristal, Esencia,
 * Reclamo, Talisman, Grabado, Salvoconducto, Fragmento de Masamune, llaves de boveda, pergaminos de
 * contrato, trofeos y lo prestado del Kit).
 *
 * Rama lore-items · La de la 1.10 (filetes de rayas, todo en gris y en un ambar apagado, etiquetas
 * sueltas) le parecio a Dosa "deprimente, sin armonia": todos los objetos parecian de categoria baja.
 * Esta es la forma de ahora, la de la maqueta que aprobo:
 *
 * <pre>
 *   Astilla del Umbral                  el nombre: de casi blanco teñido al fuerte encendido (Tono.nombre)
 *   Reliquia · Grado I  ★★★★           cabecera(): categoria en el fuerte apagado, detalle en gris claro
 *
 *   "Se quiebra cada vez que alguien    historia(): entre comillas, en el tono palido
 *    no vuelve."
 *
 *   ◆ Se vende a Oren                   seccion(): el rombo y el titulo en el tono fuerte
 *    5 MobCoins cada una                dato(): gris, {cifras} en blanco, [nombres] en el palido
 *    Hasta 60 al día
 *
 *   ▸ Clic derecho para beber.          accion(): el triangulo en el tono fuerte, el texto en blanco
 *   Si mueres antes de venderla, ...    nota(): gris
 * </pre>
 *
 * Reglas de la casa que se cumplen aqui para que ningun objeto tenga que acordarse:
 *  - un solo color por objeto (su Tono) mas el dorado de las estrellas: nada de arcoiris;
 *  - sin rayas: los bloques se separan con lineas en blanco (hueco(); nunca dos seguidas ni al final);
 *  - sin cursiva ni negrita en ninguna linea del lore (el nombre si va en negrita desde la 1.12.1: Tono.nombre);
 *  - lineas de 38 caracteres como mucho (Bedrock parte mal las largas): todo se corta por palabras.
 *
 * Lo marcado: "{6}" sale en blanco (cifras), "[Esencias]" en el tono palido (monedas y objetos dentro
 * del texto) y "<Llave del Caos>" en el tono fuerte (lo destacado). Lo demas, en el color de la pieza.
 *
 * Es pura (solo Adventure): lineas() devuelve el texto plano y el autotest lo compara sin servidor.
 */
final class Ficha {

    /** Lo mas largo que puede ser una linea, sangria incluida. */
    static final int ANCHO = 38;
    /** Cifras y texto principal: blanco. */
    static final TextColor BLANCO = Paleta.LORE_BLANCO;
    /** Lo secundario y las notas del final: gris. */
    static final TextColor GRIS = Paleta.LORE_GRIS;
    /** El detalle de la cabecera: gris claro. */
    static final TextColor GRIS_CLARO = Paleta.LORE_GRIS_CLARO;
    /** Las casillas vacias de una barra de progreso: un gris mas oscuro que el de las notas. */
    static final TextColor VACIA = Paleta.CASILLA_VACIA;
    static final String ROMBO = "◆";
    static final String ESTRELLA = "★";
    static final String CASILLA = "■";

    private record Trozo(String texto, TextColor color) {
    }

    private final Paleta.Tono tono;
    private final TextColor fuerte;
    private final TextColor palido;
    /** Las lineas; una lista vacia es un hueco (linea en blanco). */
    private final List<List<Trozo>> lineas = new ArrayList<>();

    Ficha(Paleta.Tono tono) {
        this.tono = tono == null ? Paleta.T_CONTRATO : tono;
        this.fuerte = this.tono.fuerte();
        this.palido = this.tono.palido();
    }

    Paleta.Tono tono() {
        return tono;
    }

    // ------------------------------------------------------------------ piezas

    /**
     * "Reliquia · Grado I  ★☆☆☆": la categoria en el tono fuerte apagado hacia gris (Tono.tintado), " · "
     * en gris, el detalle en gris claro y, con estrellas > 0, cuatro estrellas (las conseguidas doradas,
     * las que faltan grises). Calamity 1.12.1 · Antes iba en el fuerte y el palido y le robaba el sitio al
     * nombre: ahora la cabecera se queda atras y lo llamativo es el nombre.
     */
    Ficha cabecera(String categoria, String detalle, int estrellas) {
        List<Trozo> l = new ArrayList<>();
        l.add(new Trozo(categoria, tono.tintado()));
        if (detalle != null && !detalle.isBlank()) {
            l.add(new Trozo(" · ", GRIS));
            l.add(new Trozo(detalle, GRIS_CLARO));
        }
        if (estrellas > 0) {
            int g = Math.min(4, estrellas);
            l.add(new Trozo("  ", GRIS));
            l.add(new Trozo(ESTRELLA.repeat(g), Paleta.ESTRELLA));
            if (g < 4) l.add(new Trozo(ESTRELLA.repeat(4 - g), GRIS));
        }
        lineas.add(l);
        return this;
    }

    /** Una linea en blanco entre bloques (nunca dos seguidas, ni al principio ni al final). */
    Ficha hueco() {
        lineas.add(List.of());
        return this;
    }

    /** La frase de ambiente: entre comillas, en el tono palido, entre huecos. */
    Ficha historia(String texto) {
        if (texto == null || texto.isBlank()) return this;
        hueco();
        anadir("", " ", "\"" + texto.trim() + "\"", palido);
        return hueco();
    }

    /** "◆ Objetivo": un hueco delante y el titulo en el tono fuerte. */
    Ficha seccion(String titulo) {
        return seccion(titulo, null);
    }

    /** "◆ Progreso 6/10": el titulo y, detras, algo marcado en gris ({cifras} en blanco). */
    Ficha seccion(String titulo, String marcado) {
        hueco();
        List<Trozo> l = new ArrayList<>();
        l.add(new Trozo(ROMBO + " " + titulo, fuerte));
        if (marcado != null && !marcado.isBlank()) {
            l.add(new Trozo(" ", GRIS));
            l.addAll(pintar(marcado, GRIS));
        }
        lineas.add(l);
        return this;
    }

    /** Texto principal (blanco), sin sangria: el objetivo de un contrato. */
    Ficha texto(String marcado) {
        return anadir("", "", marcado, BLANCO);
    }

    /** Un dato de una seccion, con un espacio de sangria: gris, {cifras} en blanco, [nombres] en el palido. */
    Ficha dato(String marcado) {
        return anadir(" ", " ", marcado, GRIS);
    }

    /** "▸ Clic derecho para beber.": el triangulo en el tono fuerte, el texto en blanco. Con un hueco delante. */
    Ficha accion(String marcado) {
        if (marcado == null || marcado.isBlank()) return this;
        hueco();
        int antes = lineas.size();
        anadir("  ", "  ", marcado, BLANCO);
        if (lineas.size() > antes) {
            List<Trozo> primera = new ArrayList<>(lineas.get(antes));
            // La sangria de dos espacios de la primera linea se cambia por el triangulo.
            Trozo t = primera.get(0);
            primera.set(0, new Trozo(t.texto().substring(2), t.color()));
            primera.add(0, new Trozo("▸ ", fuerte));
            lineas.set(antes, primera);
        }
        return this;
    }

    /** Lo que se lee al final (de donde salio, la caducidad, el aviso de morir): gris. */
    Ficha nota(String marcado) {
        return anadir("", "", marcado, GRIS);
    }

    /** Una barra de progreso de "total" casillas: las llenas en el tono fuerte, las vacias en gris oscuro. */
    Ficha barra(int llenas, int total) {
        int t = Math.max(1, Math.min(ANCHO, total));
        int n = Math.max(0, Math.min(t, llenas));
        List<Trozo> l = new ArrayList<>();
        if (n > 0) l.add(new Trozo(CASILLA.repeat(n), fuerte));
        if (n < t) l.add(new Trozo(CASILLA.repeat(t - n), VACIA));
        lineas.add(l);
        return this;
    }

    // ------------------------------------------------------------------ salida

    /** Las lineas sin huecos de mas: ninguno al principio ni al final, nunca dos seguidos. */
    private List<List<Trozo>> limpias() {
        List<List<Trozo>> out = new ArrayList<>();
        for (List<Trozo> l : lineas) {
            if (l.isEmpty() && (out.isEmpty() || out.get(out.size() - 1).isEmpty())) continue;
            out.add(l);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    /** El lore en texto plano, linea a linea (lo que compara el autotest). Un hueco es "". */
    List<String> lineas() {
        List<String> out = new ArrayList<>();
        for (List<Trozo> l : limpias()) {
            StringBuilder sb = new StringBuilder();
            for (Trozo t : l) sb.append(t.texto());
            out.add(sb.toString());
        }
        return out;
    }

    /** El lore pintado: sin cursiva ni negrita en ningun trozo. */
    List<Component> lore() {
        List<Component> out = new ArrayList<>();
        for (List<Trozo> l : limpias()) {
            TextComponent.Builder b = Component.text();
            for (Trozo t : l) if (!t.texto().isEmpty()) b.append(Component.text(t.texto(), t.color()));
            out.add(b.build().decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, false));
        }
        return out;
    }

    /** Una linea en blanco suelta, como las de lore() (para quien anade lineas a un lore que ya existe). */
    static Component enBlanco() {
        return Component.text("").decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, false);
    }

    // ------------------------------------------------------------------ objetos que ya circulan

    /** Lo que Entregas.ligar anade al lore: un hueco y "Ligado a Dosa: no se vende ni se cambia." en gris. */
    static List<Component> lineasLigado(String nombre) {
        List<Component> out = new ArrayList<>();
        out.add(enBlanco());
        out.addAll(new Ficha(null).nota("Ligado a " + nombre + ": no se vende ni se cambia.").lore());
        return out;
    }

    /** El nombre de la linea de ligado de un lore ("Ligado a Dosa · ..." de antes o "Ligado a Dosa: ..."), o null. */
    static String ligadoDe(List<Component> lore) {
        if (lore == null) return null;
        var plano = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        for (Component c : lore) {
            String t = plano.serialize(c);
            if (!t.startsWith("Ligado a ")) continue;
            String resto = t.substring("Ligado a ".length());
            int fin = resto.indexOf(':');
            int punto = resto.indexOf(" ·");
            if (fin < 0 || (punto >= 0 && punto < fin)) fin = punto;
            if (fin < 0) fin = resto.indexOf(' ');
            String n = (fin < 0 ? resto : resto.substring(0, fin)).trim();
            return n.isEmpty() ? null : n;
        }
        return null;
    }

    /**
     * Rama lore-items · Un objeto que ya circula, con el nombre y el lore de hoy. Conserva lo que se le anadio
     * despues de crearlo: la linea de ligado (rehecha con el estilo de hoy) y la de prestado del Kit. Devuelve
     * una copia nueva, o null si ya los llevaba (asi quien la llama no toca la casilla).
     */
    static org.bukkit.inventory.ItemStack renovar(org.bukkit.inventory.ItemStack it, Component nombre, List<Component> lore) {
        if (it == null || !it.hasItemMeta()) return null;
        org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
        List<Component> l = new ArrayList<>(lore);
        String dueno = ligadoDe(meta.lore());
        if (dueno != null) l.addAll(lineasLigado(dueno));
        if (Kit.esPrestado(it)) {
            l.add(enBlanco());
            l.addAll(Kit.lineasPrestado());
        }
        if (iguales(l, meta.lore()) && igual(nombre, meta.displayName())) return null;
        org.bukkit.inventory.ItemStack copia = it.clone();
        copia.editMeta(m -> {
            m.displayName(nombre);
            m.lore(l);
        });
        return copia;
    }

    /**
     * Si dos textos se ven igual. Lo que vuelve de un objeto (nombre y lore pasan por NBT) puede traer los
     * trozos agrupados de otra forma: se comparan compactados, no por su estructura.
     */
    static boolean igual(Component a, Component b) {
        if (a == null || b == null) return a == b;
        var g = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson();
        return g.serialize(a.compact()).equals(g.serialize(b.compact()));
    }

    static boolean iguales(List<Component> a, List<Component> b) {
        if (a == null || b == null) return a == b;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!igual(a.get(i), b.get(i))) return false;
        return true;
    }

    // ------------------------------------------------------------------ cortar

    /** Lo marcado en trozos de color: {blanco}, [palido], <fuerte>; lo demas en "base". */
    private List<Trozo> pintar(String marcado, TextColor base) {
        List<Trozo> out = new ArrayList<>();
        StringBuilder plano = new StringBuilder();
        List<TextColor> colores = new ArrayList<>();
        leer(marcado, base, plano, colores);
        StringBuilder run = new StringBuilder();
        TextColor actual = null;
        for (int k = 0; k < plano.length(); k++) {
            TextColor col = colores.get(k);
            if (actual != null && !actual.equals(col)) {
                out.add(new Trozo(run.toString(), actual));
                run.setLength(0);
            }
            actual = col;
            run.append(plano.charAt(k));
        }
        if (run.length() > 0) out.add(new Trozo(run.toString(), actual));
        return out;
    }

    /** Quita las marcas de "marcado" y apunta el color de cada caracter que queda. */
    private void leer(String marcado, TextColor base, StringBuilder plano, List<TextColor> colores) {
        TextColor dentro = null;
        for (int i = 0; i < marcado.length(); i++) {
            char c = marcado.charAt(i);
            switch (c) {
                case '{' -> dentro = BLANCO;
                case '[' -> dentro = palido;
                case '<' -> dentro = fuerte;
                case '}', ']', '>' -> dentro = null;
                default -> {
                    plano.append(c);
                    colores.add(dentro != null ? dentro : base);
                }
            }
        }
    }

    /**
     * Pinta "marcado", lo corta en lineas de ANCHO como mucho sin partir palabras y pone "primera" delante
     * de la primera linea y "resto" delante de las demas. Una palabra mas larga que la linea va sola.
     */
    private Ficha anadir(String primera, String resto, String marcado, TextColor base) {
        if (marcado == null || marcado.isBlank()) return this;
        StringBuilder plano = new StringBuilder();
        List<TextColor> colores = new ArrayList<>();
        leer(marcado, base, plano, colores);
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

    /**
     * El tono de una familia de objetos: el de hardcore.lores.tonos.<familia> de la config viva ("#FFE27A
     * #FF8A2B", desde y hasta) o, si no esta o no se entiende, el de serie de Paleta.TONOS.
     */
    static Paleta.Tono tono(String familia) {
        Paleta.Tono serie = Paleta.TONOS.getOrDefault(familia, Paleta.T_CONTRATO);
        String s = cfg().getString("lores.tonos." + familia);
        if (s == null || s.isBlank()) return serie;
        String[] p = s.trim().split("[\\s,]+");
        if (p.length != 2) return serie;
        try {
            return new Paleta.Tono(Integer.parseInt(p[0].replace("#", ""), 16), Integer.parseInt(p[1].replace("#", ""), 16));
        } catch (NumberFormatException malo) {
            return serie;
        }
    }

    /** "{1.500} [MobCoins]", "{6} [Esencias]": una cantidad marcada (cifra en blanco, moneda en el palido). */
    static String cantidad(long n, String uno, String varios) {
        return "{" + Altar.miles(n) + "} [" + (n == 1 ? uno : varios) + "]";
    }

    /** Las Esencias de una cantidad: 0,2 es "{1} [Esencia] por cada {5}" (la Tasacion suma y redondea abajo). */
    static String esencias(double esencias) {
        if (esencias <= 0) return null;
        if (esencias == Math.rint(esencias)) return cantidad((long) esencias, "Esencia", "Esencias");
        if (esencias < 1 && Math.abs(1 / esencias - Math.rint(1 / esencias)) < 1e-6) {
            return "{1} [Esencia] por cada {" + (long) Math.rint(1 / esencias) + "}";
        }
        return "{" + Marco.numero(esencias) + "} [Esencias]";
    }

    /** Lo que vale algo en Esencias y MobCoins, marcado: "{6} [Esencias] · {100} [MobCoins]". */
    static String valor(double esencias, long mobcoins) {
        String e = esencias(esencias);
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
     * (ItemsCalamity.reclamo, el Fragmento de Masamune, los tonos). Sin plugin (autotest fuera del
     * servidor), vacia: entonces valen los numeros de serie.
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

    /**
     * Lo que un lore de objeto no puede llevar (el autotest "fichas" lo mira en todos): rayas de filete,
     * negrita en algun trozo, cursiva sin apagar, dos huecos seguidos o uno al principio o al final. Vacia
     * si esta bien.
     */
    static List<String> faltas(List<Component> lore) {
        List<String> out = new ArrayList<>();
        if (lore == null) return out;
        var plano = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        String antes = null;
        for (int i = 0; i < lore.size(); i++) {
            Component c = lore.get(i);
            String t = plano.serialize(c);
            if (t.contains("─") || t.contains("━") || t.contains("═")) out.add("raya: " + t);
            if (negrita(c)) out.add("negrita: " + t);
            if (!t.isEmpty() && c.decoration(TextDecoration.ITALIC) != TextDecoration.State.FALSE) out.add("cursiva: " + t);
            if (t.isEmpty() && (i == 0 || i == lore.size() - 1 || "".equals(antes))) out.add("hueco de mas en la linea " + (i + 1));
            antes = t;
        }
        return out;
    }

    /** Si algun trozo de la linea (o sus hijos) lleva la negrita puesta. */
    private static boolean negrita(Component c) {
        if (c.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) return true;
        for (Component h : c.children()) if (negrita(h)) return true;
        return false;
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
     * servidor, la de serie fuera). Comprueba que ninguna linea pasa de 38, que ningun lore lleva rayas ni
     * negrita, y las cifras que salen de la config. Sin Bukkit.
     */
    static List<String> autotestObjetos(ConfigurationSection c) {
        List<String> out = new ArrayList<>(autotest());
        Autotest.Hoja h = new Autotest.Hoja();
        Map<String, Ficha> todas = objetosDePrueba(c);
        List<String> largas = new ArrayList<>(), faltas = new ArrayList<>();
        for (Map.Entry<String, Ficha> e : todas.entrySet()) {
            for (String l : largas(e.getValue().lineas())) largas.add(e.getKey() + ": " + l);
            for (String f : faltas(e.getValue().lore())) faltas.add(e.getKey() + ": " + f);
        }
        h.igual("ninguna linea de ningun objeto pasa de 38", List.of(), largas);
        h.igual("ningun lore de objeto lleva rayas, negrita, cursiva ni huecos de mas", List.of(), faltas);
        boolean conHistoria = true;
        for (Map.Entry<String, Ficha> e : todas.entrySet()) {
            List<String> l = e.getValue().lineas();
            conHistoria &= l.size() > 3 && l.get(1).isEmpty() && l.get(2).startsWith("\"");
        }
        h.ok("todos: cabecera, hueco y la historia entre comillas", conHistoria);

        double e3 = c.getDouble("reliquias.grados.3.esencias", 3);
        long mc3 = c.getLong("reliquias.grados.3.mobcoins", 40);
        List<String> ambar = todas.get("ambar").lineas();
        h.igual("Ambar: grado en estrellas", "Reliquia · Grado III  ★★★★", ambar.get(0));
        h.ok("Ambar: se le vende a Oren, en el spawn", ambar.contains("◆ Se vende a Oren, en el spawn"));
        h.ok("Ambar: su valor sale de la config", ambar.contains(" " + plano(valor(e3, mc3))));
        h.ok("Ambar: de quien cayo", ambar.contains("Cayó del Heraldo Carmesí."));
        h.ok("Ambar: pieza unica y caducidad", ambar.contains("Pieza única. Caduca el 18/10."));
        h.ok("Ambar: si mueres antes de venderla la pierdes", ambar.contains("Si mueres sin venderla, la pierdes."));
        h.igual("Ambar: el aviso del final", "Fuera de Calamity no se puede guardar.", ambar.get(ambar.size() - 1));
        List<String> astilla = todas.get("astilla").lineas();
        h.ok("Astilla: sin origen ni caducidad", astilla.stream().noneMatch(l -> l.startsWith("Caduca") || l.startsWith("La soltó")
                || l.startsWith("Pieza única")));
        h.ok("Astilla: el tope del dia", astilla.contains(" Hasta " + c.getInt("reliquias.tope-dia.1", 60) + " al día"));
        h.ok("Astilla: lo que paga Oren por cada una", astilla.contains(" " + c.getLong("reliquias.grados.1.mobcoins", 5)
                + " MobCoins cada una"));
        List<String> sello = todas.get("sello").lineas();
        h.ok("Sello: dice que pieza desbloquea", sello.contains("◆ Desbloquea en la Forja de Vael")
                && sello.contains(" Yelmo de Calamidad"));
        h.ok("Sello: masculino", sello.contains("Si mueres sin venderlo, lo pierdes."));
        List<String> campana = todas.get("campana").lineas();
        h.ok("Campana alta: Fragmento y Llave", campana.contains(" + 1 Fragmento de Guadaña")
                && campana.stream().anyMatch(l -> l.contains("de una Llave del Caos")));
        h.ok("Campana alta: para que sirven los Fragmentos", campana.stream().anyMatch(l -> l.endsWith("· Guadaña de la Parca")));
        h.ok("Campana alta: de que Parca", campana.contains("Cayó de una Parca de nivel 45."));
        h.ok("Campana baja: dice desde que nivel da Fragmento", todas.get("campana-baja").lineas().stream()
                .anyMatch(l -> l.contains("nivel " + c.getInt("reliquias.especiales.campana-parca.fragmento-nivel-minimo", 40))));
        h.ok("Lagrima valida: Marca de Eco y para que sirve", todas.get("lagrima").lineas().stream()
                .anyMatch(l -> l.startsWith(" + 1 Marca de Eco")) && todas.get("lagrima").lineas().stream()
                .anyMatch(l -> l.endsWith("· Máscara del Eco")));
        h.ok("Lagrima no valida: sin Marca", todas.get("lagrima-no").lineas().stream().noneMatch(l -> l.startsWith(" + 1 Marca")));
        List<String> frasco = todas.get("frasco").lineas();
        h.ok("Frasco: tragos y cordura de la config", frasco.contains(" Le quedan 2 de " + c.getInt("frasco.usos", 3) + " tragos")
                && frasco.contains(" Cada trago devuelve " + c.getInt("frasco.cordura", 40) + " de cordura"));
        h.ok("Frasco: a 1 trago, en singular", ItemsCalamity.fichaFrasco(c, 1).lineas()
                .contains(" Le queda 1 de " + c.getInt("frasco.usos", 3) + " tragos"));
        List<String> reclamo = todas.get("reclamo").lineas();
        h.ok("Reclamo: tope y descanso de la config",
                reclamo.contains(" Hasta " + c.getInt("minijefes.reclamo.tope-dia", 6) + " al día.")
                        && reclamo.contains(" Descanso de " + c.getInt("minijefes.cada-minutos", 10) + " min entre minijefes."));
        List<String> frag = todas.get("fragmento").lineas();
        h.ok("Fragmento: la Masamune y la Crimson, de altar.trueques", frag.contains(" Masamune: 5 Fragmentos")
                && frag.contains(" Crimson Masamune: 5 y una Masamune"));
        h.ok("Cristal: los segundos de la config", todas.get("cristal").lineas()
                .contains(" Quieto " + c.getInt("cristal.segundos", 5) + " s: si te mueves, se apaga."));
        h.ok("Talisman: vida de la config", todas.get("talisman").lineas()
                .contains(" +" + c.getInt("talisman.vida", 3) + " de vida máxima"));
        h.ok("Objetos de uso: el triangulo de la accion", todas.get("cristal").lineas().stream().anyMatch(l -> l.startsWith("▸ ")));
        h.ok("Cada familia con su tono (las Reliquias, una por grado)", !todas.get("astilla").tono().equals(todas.get("nana").tono())
                && !todas.get("nana").tono().equals(todas.get("ambar").tono())
                && !todas.get("ambar").tono().equals(todas.get("mayor").tono())
                && todas.get("campana").tono().equals(tono("campana")) && todas.get("frasco").tono().equals(tono("frasco")));
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

    /** Lo marcado sin las marcas ({}, [], <>). */
    static String plano(String marcado) {
        if (marcado == null) return "";
        StringBuilder sb = new StringBuilder(marcado.length());
        for (int i = 0; i < marcado.length(); i++) {
            char ch = marcado.charAt(i);
            if ("{}[]<>".indexOf(ch) < 0) sb.append(ch);
        }
        return sb.toString();
    }

    /** La luminancia percibida de un color (0..255), para comparar cuanto resalta. */
    static int luz(TextColor c) {
        return (int) Math.round(0.299 * c.red() + 0.587 * c.green() + 0.114 * c.blue());
    }

    /** Lo vivo que es un color (0..255): la distancia entre su canal mas alto y el mas bajo. */
    static int croma(TextColor c) {
        return Math.max(c.red(), Math.max(c.green(), c.blue())) - Math.min(c.red(), Math.min(c.green(), c.blue()));
    }

    /** Pruebas de la plantilla sola (sin servidor): corte, sangrias, colores, huecos y estrellas. */
    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        Paleta.Tono t = Paleta.T_GRADO_III;
        Ficha f = new Ficha(t).cabecera("Reliquia", "Grado III", 3)
                .historia("Resina que el bosque lloró sobre los que no volvieron.")
                .seccion("Se vende a Oren").dato(valor(6, 100))
                .accion("Clic derecho para beber, quieto y lejos de cualquier criatura.")
                .hueco().hueco()
                .nota("Si mueres sin venderla, la pierdes.").hueco();
        List<String> l = f.lineas();
        h.igual("la ficha entera", List.of("Reliquia · Grado III  ★★★★", "", "\"Resina que el bosque lloró sobre los",
                " que no volvieron.\"", "", "◆ Se vende a Oren", " 6 Esencias · 100 MobCoins", "",
                "▸ Clic derecho para beber, quieto y", "  lejos de cualquier criatura.", "",
                "Si mueres sin venderla, la pierdes."), l);
        h.ok("ninguna linea pasa de 38", largas(l).isEmpty());
        h.igual("sin viudas: la palabra suelta se lleva otra", List.of("Una esquirla del umbral. Se quiebra",
                "cada vez que alguien lo cruza y no", "vuelve ya."), new Ficha(t).nota(
                "Una esquirla del umbral. Se quiebra cada vez que alguien lo cruza y no vuelve ya.").lineas());
        h.igual("sin viudas: con la sangria de la accion", List.of("▸ Al cumplirlo recibes el premio en", "  la mano."),
                new Ficha(t).accion("Al cumplirlo recibes el premio en la mano.").lineas());
        h.igual("valor de una Astilla", "{1} [Esencia] por cada {5} · {5} [MobCoins]", valor(0.2, 5));
        h.igual("valor sin MobCoins", "{1} [Esencia]", valor(1, 0));
        h.igual("valor con decimales", "{1,5} [Esencias] · {1.000} [MobCoins]", valor(1.5, 1000));
        h.igual("plano quita las tres marcas", "6 Esencias · Llave", plano("{6} [Esencias] · <Llave>"));
        h.igual("cabecera sin estrellas ni detalle", List.of("Moneda"), new Ficha(t).cabecera("Moneda", null, 0).lineas());
        h.igual("barra", List.of("■■■■■■■■■■"), new Ficha(t).barra(3, 10).lineas());
        h.igual("seccion con algo detras", List.of("◆ Progreso 6/10"), new Ficha(t).seccion("Progreso", "{6}/10").lineas());
        List<Component> lore = f.lore();
        h.igual("sin rayas, negrita, cursiva ni huecos de mas", List.of(), faltas(lore));
        h.ok("el detector ve una raya", !faltas(List.of(Component.text("────"))).isEmpty());
        h.ok("el detector ve la negrita de un hijo", !faltas(List.of(Component.text().append(
                Component.text("x").decoration(TextDecoration.BOLD, true)).build()
                .decoration(TextDecoration.ITALIC, false))).isEmpty());
        // Los colores: la categoria en el fuerte, el detalle en el palido, estrellas doradas y grises.
        List<Component> cab = lore.get(0).children();
        h.igual("categoria en el tono apagado", t.tintado(), cab.get(0).color());
        h.igual("detalle en gris claro", GRIS_CLARO, cab.get(2).color());
        h.ok("la categoria ya no va en el tono fuerte", !t.fuerte().equals(cab.get(0).color()));
        // El nombre resalta: arranca mas claro que el palido y acaba mas luminoso que la categoria.
        Component nom = t.nombre("Ámbar");
        TextColor primera = nom.children().get(0).color(), ultima = nom.children().get(4).color();
        h.ok("el nombre arranca casi en blanco (mas claro que el palido)", luz(primera) > luz(t.palido()));
        h.ok("el nombre acaba en el fuerte encendido (brillo al maximo)", Math.max(ultima.red(), Math.max(ultima.green(), ultima.blue())) == 255);
        for (Paleta.Tono x : Paleta.TONOS.values()) {
            TextColor d = TextColor.color(x.nombreDesde()), fin = TextColor.color(x.nombreHasta()), cat = x.tintado();
            h.ok("tono " + Integer.toHexString(x.hasta()) + ": el nombre resalta sobre la categoria (mas claro al empezar, mas vivo al acabar)",
                    luz(d) > luz(cat) + 60 && croma(fin) > 2 * croma(cat));
        }
        boolean oro = false, gris = false;
        for (Component c : cab) {
            if (c instanceof TextComponent x && x.content().contains(ESTRELLA)) {
                oro |= Paleta.ESTRELLA.equals(x.color());
                gris |= GRIS.equals(x.color());
            }
        }
        h.ok("estrellas conseguidas doradas y las que faltan grises", oro && gris);
        h.igual("la historia en el palido", t.palido(), lore.get(2).children().get(0).color());
        h.igual("el rombo en el fuerte", t.fuerte(), lore.get(5).children().get(0).color());
        List<Component> dato = lore.get(6).children();
        h.ok("dato: cifra en blanco, moneda en el palido, lo demas gris", BLANCO.equals(dato.get(1).color())
                && t.palido().equals(dato.get(3).color()) && GRIS.equals(dato.get(4).color()));
        h.igual("el palido es el inicio un 35 % hacia blanco",
                TextColor.lerp(0.35f, TextColor.color(t.desde()), TextColor.color(0xFFFFFF)), t.palido());
        h.ok("el nombre: degradado en negrita (la unica del objeto)", t.nombre("Ámbar").decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE
                && t.nombre("Ámbar").children().size() == 5);
        h.igual("palabra mas larga que la linea: sola, sin partir", List.of("x".repeat(45)),
                new Ficha(t).texto("x".repeat(45)).lineas());
        h.igual("sin texto no anade nada", 0, new Ficha(t).texto("").nota(null).historia(" ").lineas().size());
        h.igual("tono de serie sin config", Paleta.T_FRASCO, tono("frasco"));
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
