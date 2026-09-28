package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El Cronista de la antesala (1.2.0): la historia de Calamity y como se juega, por capitulos
 * cortos. Sale al hacer clic en su NPC y con /calamity cronista [capitulo], que es inofensivo:
 * solo cuenta.
 *
 * Los textos viven en la config (hardcore.cronista.capitulos) para que Dosa los cambie
 * sin recompilar. Si alli no hay ninguno (un config.yml de antes de 1.2.0 no los trae), salen
 * los del config.yml del jar.
 *
 * Las cifras no se escriben a mano: {ruta} se cambia al leerlo por ese valor de hardcore:
 * ({parca.minutos} -> 10), asi que al tocar la PARCA o la Tasacion el Cronista no se queda
 * contando lo de antes. En una lista se entra por posicion (aduana.tramos-mc.0.hasta) o, si
 * sus elementos llevan id, por el id (altar.trueques.cristal.esencias). Lo que no lleve a
 * ningun valor se deja tal cual, con sus llaves, para que se vea (y el autotest lo caza).
 *
 * Todo por texto y comando: en Java el indice y las flechas se pulsan; desde Bedrock se
 * escribe el numero.
 */
final class Cronista {

    /** Un capitulo: su id (la clave en la config), el titulo y sus lineas, aun con las {rutas}. */
    record Capitulo(String id, String titulo, List<String> texto) {
    }

    /** {ruta.de.la.config}: letras, numeros, guiones, guiones bajos y puntos. */
    static final Pattern HUECO = Pattern.compile("\\{([A-Za-z0-9_.\\-]+)}");

    /** Donde van los capitulos, dentro de hardcore:. */
    static final String RUTA = "cronista.capitulos";

    private final Hardcore hc;

    Cronista(Hardcore hc) {
        this.hc = hc;
    }

    // ------------------------------------------------------------------ capitulos

    /** Los de la config; si alli no hay ninguno, los del jar. */
    List<Capitulo> capitulos() {
        List<Capitulo> l = leer(seccion(hc.cfg(), RUTA));
        if (!l.isEmpty()) return l;
        ConfigurationSection f = fabrica(hc.plugin());
        return leer(seccion(f, "hardcore." + RUTA));
    }

    /**
     * La seccion de esa ruta, sin crearla si no esta. getConfigurationSection la crearia vacia
     * en memoria cuando solo existe en el jar, y el siguiente saveConfig (Hardcore.punto) la
     * dejaria escrita, vacia, en el config.yml del servidor.
     */
    static ConfigurationSection seccion(ConfigurationSection raiz, String ruta) {
        Object o = raiz == null ? null : raiz.get(ruta, null);
        return o instanceof ConfigurationSection s ? s : null;
    }

    /** El config.yml del jar, el de fabrica: JavaPlugin lo deja como defaults del config. */
    static ConfigurationSection fabrica(JavaPlugin plugin) {
        Configuration d = plugin.getConfig().getDefaults();
        if (d != null) return d;
        try (InputStream in = plugin.getResource("config.yml")) {
            return in == null ? null : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /** Los capitulos de esa seccion, en su orden. Uno sin lineas no cuenta. */
    static List<Capitulo> leer(ConfigurationSection s) {
        List<Capitulo> out = new ArrayList<>();
        if (s == null) return out;
        for (String id : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(id);
            if (c == null) continue;
            List<String> texto = new ArrayList<>(c.getStringList("texto"));
            if (texto.isEmpty() && c.isString("texto")) texto.add(c.getString("texto"));
            texto.removeIf(t -> t == null || t.isBlank());
            if (texto.isEmpty()) continue;
            out.add(new Capitulo(id.toLowerCase(Locale.ROOT), c.getString("titulo", id), List.copyOf(texto)));
        }
        return out;
    }

    /** La posicion del capitulo por numero (1 = el primero) o por id; -1 si no hay. */
    static int buscar(List<Capitulo> l, String arg) {
        if (arg == null || arg.isBlank()) return -1;
        String a = arg.trim().toLowerCase(Locale.ROOT);
        try {
            int n = Integer.parseInt(a);
            return n >= 1 && n <= l.size() ? n - 1 : -1;
        } catch (NumberFormatException noEsNumero) {
            for (int i = 0; i < l.size(); i++) if (l.get(i).id().equals(a)) return i;
            return -1;
        }
    }

    /** Para el tabulador: los numeros y los ids. */
    List<String> sugerencias() {
        List<Capitulo> l = capitulos();
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= l.size(); i++) out.add(String.valueOf(i));
        for (Capitulo c : l) out.add(c.id());
        return out;
    }

    // ------------------------------------------------------------------ cifras

    /**
     * El valor de una ruta de la config: por secciones y mapas con su clave, y en una lista por
     * posicion (0 = el primero) o por el id de sus elementos. En la config viva, lo que falte se
     * busca solo en el jar (Bukkit mira los defaults). Null si no lleva a nada.
     */
    static Object valor(ConfigurationSection raiz, String ruta) {
        Object nodo = raiz;
        for (String parte : ruta.split("\\.")) {
            if (parte.isEmpty() || nodo == null) return null;
            if (nodo instanceof ConfigurationSection s) nodo = s.get(parte);
            else if (nodo instanceof Map<?, ?> m) nodo = deMapa(m, parte);
            else if (nodo instanceof List<?> l) nodo = deLista(l, parte);
            else return null;
        }
        return nodo;
    }

    private static Object deMapa(Map<?, ?> m, String clave) {
        Object v = m.get(clave);
        if (v != null) return v;
        // Dentro de una lista las claves numericas del YAML llegan como Integer, no como texto.
        for (Map.Entry<?, ?> e : m.entrySet()) if (String.valueOf(e.getKey()).equals(clave)) return e.getValue();
        return null;
    }

    private static Object deLista(List<?> l, String parte) {
        try {
            int i = Integer.parseInt(parte);
            return i >= 0 && i < l.size() ? l.get(i) : null;
        } catch (NumberFormatException porId) {
            for (Object o : l) {
                Object id = o instanceof Map<?, ?> m ? m.get("id") : o instanceof ConfigurationSection s ? s.get("id") : null;
                if (id != null && String.valueOf(id).equalsIgnoreCase(parte)) return o;
            }
            return null;
        }
    }

    /**
     * Un valor como se lee en el chat: enteros con punto de miles (1.500), decimales con coma y
     * como mucho dos (0,2; 1,35), listas separadas por comas. Null si no es un valor suelto
     * (una seccion entera no se pinta).
     */
    static String formato(Object v) {
        if (v == null || v instanceof ConfigurationSection || v instanceof Map<?, ?>) return null;
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return Altar.miles(((Number) v).longValue());
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
            if (Math.abs(d - Math.rint(d)) < 1e-9 && Math.abs(d) < 1e15) return Altar.miles(Math.round(d));
            return BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',');
        }
        if (v instanceof List<?> l) {
            List<String> partes = new ArrayList<>();
            for (Object o : l) {
                String s = formato(o);
                if (s == null) return null;
                partes.add(s);
            }
            return String.join(", ", partes);
        }
        return String.valueOf(v);
    }

    /** El valor ya escrito de una {ruta}, o null si no lleva a nada. */
    static String cifra(ConfigurationSection raiz, String ruta) {
        return formato(valor(raiz, ruta));
    }

    /** La linea con las {rutas} cambiadas por su valor; las que no llevan a nada se quedan. */
    static String resolver(String linea, ConfigurationSection raiz) {
        Matcher m = HUECO.matcher(linea);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = cifra(raiz, m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Las {rutas} de la linea que no llevan a ningun valor (el autotest y el staff las quieren ver). */
    static List<String> sinValor(String linea, ConfigurationSection raiz) {
        List<String> out = new ArrayList<>();
        Matcher m = HUECO.matcher(linea);
        while (m.find()) if (cifra(raiz, m.group(1)) == null) out.add(m.group());
        return out;
    }

    /** La linea para el chat: el texto en el color normal y las cifras en el de las cifras. */
    static Component linea(String linea, ConfigurationSection raiz) {
        TextComponent.Builder out = Component.text();
        Matcher m = HUECO.matcher(linea);
        int desde = 0;
        while (m.find()) {
            if (m.start() > desde) out.append(Component.text(linea.substring(desde, m.start()), Paleta.TEXTO));
            String v = cifra(raiz, m.group(1));
            out.append(v == null ? Component.text(m.group(), Paleta.TENUE) : Component.text(v, Paleta.CIFRA));
            desde = m.end();
        }
        if (desde < linea.length()) out.append(Component.text(linea.substring(desde), Paleta.TEXTO));
        return out.build();
    }

    // ------------------------------------------------------------------ chat

    /** /calamity cronista [capitulo]. */
    void comando(CommandSender quien, String[] args) {
        List<Capitulo> l = capitulos();
        if (l.isEmpty()) {
            quien.sendMessage(ComandoCalamity.mensaje("Ilen no tiene nada que contar todavía."));
            return;
        }
        if (args.length < 2) {
            indice(quien, l);
            return;
        }
        int i = buscar(l, args[1]);
        if (i < 0) {
            quien.sendMessage(ComandoCalamity.mensaje(Component.text("Ese capítulo no existe. Los tienes en ")
                    .append(Component.text("/calamity cronista", Paleta.DETALLE)).append(Component.text("."))));
            return;
        }
        capitulo(quien, l, i);
    }

    /** El indice (el clic en el NPC). */
    void indice(CommandSender quien) {
        List<Capitulo> l = capitulos();
        if (l.isEmpty()) {
            quien.sendMessage(ComandoCalamity.mensaje("Ilen no tiene nada que contar todavía."));
            return;
        }
        indice(quien, l);
    }

    private void indice(CommandSender quien, List<Capitulo> l) {
        quien.sendMessage(ComandoCalamity.mensaje(Component.text("Ilen sabe ")
                .append(Paleta.cifra(l.size())).append(Component.text(l.size() == 1 ? " historia. " : " historias. "))
                .append(Component.text("Pulsa la que quieras oír.", Paleta.TENUE))));
        for (int i = 0; i < l.size(); i++) {
            Capitulo c = l.get(i);
            quien.sendMessage(Component.text("  " + (i + 1) + ". ", Paleta.TENUE)
                    .append(enlace(c.titulo(), Paleta.DETALLE, i + 1, "Leer: " + c.titulo())));
        }
        quien.sendMessage(Component.text("  O escribe /calamity cronista <número>.", Paleta.TENUE));
        sonar(quien);
    }

    private void capitulo(CommandSender quien, List<Capitulo> l, int i) {
        Capitulo c = l.get(i);
        quien.sendMessage(Paleta.prefijo().append(Component.text("Ilen · ", Paleta.TENUE))
                .append(Component.text(c.titulo(), Paleta.DETALLE))
                .append(Component.text("  " + (i + 1) + "/" + l.size(), Paleta.TENUE)));
        ConfigurationSection raiz = hc.cfg();
        for (String t : c.texto()) quien.sendMessage(Component.text("  ").append(linea(t, raiz)));
        // Las flechas: anterior, indice y siguiente, solo las que existen.
        TextComponent.Builder nav = Component.text().append(Component.text("  "));
        if (i > 0) {
            nav.append(enlace("« Anterior", Paleta.DETALLE, i, l.get(i - 1).titulo()))
                    .append(Component.text("   ", Paleta.TENUE));
        }
        nav.append(enlace("Índice", Paleta.DETALLE, 0, "Todas las historias"));
        if (i + 1 < l.size()) {
            nav.append(Component.text("   ", Paleta.TENUE))
                    .append(enlace("Siguiente »", Paleta.DETALLE, i + 2, l.get(i + 1).titulo()));
        }
        quien.sendMessage(nav.build());
        sonar(quien);
    }

    /** Un texto que al pulsarlo lleva a ese capitulo (0 = el indice). */
    private static Component enlace(String texto, TextColor color, int capitulo, String ayuda) {
        String cmd = "/calamity cronista" + (capitulo > 0 ? " " + capitulo : "");
        return Component.text(texto, color)
                .clickEvent(ClickEvent.runCommand(cmd))
                .hoverEvent(HoverEvent.showText(Component.text(ayuda, Paleta.TEXTO)));
    }

    private static void sonar(CommandSender quien) {
        if (quien instanceof Player p) Compat.soundPlayers(p.getWorld(), p.getLocation(), "item.book.page_turn", 0.7f, 1.0f);
    }
}
