package net.ederus.edm.mundos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La semilla propia de un mundo de Lethal World.
 *
 * Las dimensiones de datapack comparten la semilla del servidor, pero cada ruido de Minecraft
 * saca su aleatoriedad de la semilla Y de su propio id ("minecraft:continentalness"...). Asi
 * que copiar los ajustes de ruido del generador, sus funciones de densidad y sus ruidos bajo
 * ids nuevos que llevan la semilla da otro terreno y otro reparto de biomas, igual que otra
 * semilla: misma semilla, mismo mundo; otra semilla, otro mundo.
 *
 * Lo vanilla se lee del propio servidor (no va en el jar); lo de Bracken, del datapack del jar.
 */
final class Resembrador {

    /** "clave": "ns:ruta". Las claves type y Name son tipos y bloques, nunca referencias. */
    private static final Pattern PAR = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([a-z0-9_.-]+:[a-z0-9_./-]+)\"");
    private static final Pattern AJUSTES = Pattern.compile("\"settings\"\\s*:\\s*\"([a-z0-9_.-]+:[a-z0-9_./-]+)\"");

    private final Function<String, byte[]> delJar;
    private final Map<String, String> cache = new HashMap<>();

    Resembrador(Function<String, byte[]> delJar) {
        this.delJar = delJar;
    }

    static String prefijo(long semilla) {
        return "s" + (semilla < 0 ? "m" + (-semilla) : String.valueOf(semilla));
    }

    /**
     * La plantilla de la dimension apuntando a sus ajustes resembrados, y los ficheros que
     * necesita (ruta dentro del datapack -> contenido). Lanza IOException si falta algo.
     */
    Map<String, byte[]> resembrar(String plantilla, long semilla, StringBuilder dimension) throws IOException {
        Matcher m = AJUSTES.matcher(plantilla);
        if (!m.find()) throw new IOException("el generador no tiene ajustes de ruido con nombre");
        String prefijo = prefijo(semilla);
        Map<String, byte[]> out = new LinkedHashMap<>();
        Deque<String[]> cola = new ArrayDeque<>();
        Set<String> hechos = new java.util.HashSet<>();
        cola.add(new String[]{"noise_settings", m.group(1)});

        while (!cola.isEmpty()) {
            String[] item = cola.poll();
            String kind = item[0], id = item[1];
            if (!hechos.add(kind + "|" + id)) continue;
            String texto = leer(kind, id);
            if (texto == null) throw new IOException("no encuentro " + kind + " " + id);
            StringBuilder sb = new StringBuilder();
            Matcher p = PAR.matcher(texto);
            while (p.find()) {
                String clave = p.group(1), valor = p.group(2), reemplazo = p.group(0);
                if (!clave.equals("type") && !clave.equals("Name")) {
                    String[] orden = clave.equals("noise")
                            ? new String[]{"noise", "density_function"}
                            : new String[]{"density_function", "noise"};
                    for (String k : orden) {
                        if (leer(k, valor) != null) {
                            cola.add(new String[]{k, valor});
                            reemplazo = "\"" + clave + "\": \"" + nuevoId(prefijo, valor) + "\"";
                            break;
                        }
                    }
                }
                p.appendReplacement(sb, Matcher.quoteReplacement(reemplazo));
            }
            p.appendTail(sb);
            out.put("data/" + MundosPlugin.NAMESPACE + "/worldgen/" + kind + "/" + nuevaRuta(prefijo, id) + ".json",
                    sb.toString().getBytes(StandardCharsets.UTF_8));
        }

        dimension.setLength(0);
        dimension.append(plantilla, 0, m.start(1))
                .append(nuevoId(prefijo, m.group(1)))
                .append(plantilla.substring(m.end(1)));
        return out;
    }

    private static String nuevaRuta(String prefijo, String id) {
        int dos = id.indexOf(':');
        return prefijo + "/" + id.substring(0, dos) + "/" + id.substring(dos + 1);
    }

    private static String nuevoId(String prefijo, String id) {
        return MundosPlugin.NAMESPACE + ":" + nuevaRuta(prefijo, id);
    }

    /** El fichero de worldgen: primero el datapack del jar, luego los datos del servidor. */
    private String leer(String kind, String id) {
        String clave = kind + "|" + id;
        if (cache.containsKey(clave)) return cache.get(clave);
        int dos = id.indexOf(':');
        String rel = "data/" + id.substring(0, dos) + "/worldgen/" + kind + "/" + id.substring(dos + 1) + ".json";
        byte[] datos = delJar.apply("datapack/" + rel);
        if (datos == null) datos = delServidor(rel);
        String texto = datos == null ? null : new String(datos, StandardCharsets.UTF_8);
        cache.put(clave, texto);
        return texto;
    }

    private static byte[] delServidor(String rel) {
        for (ClassLoader cl : new ClassLoader[]{
                org.bukkit.Bukkit.getServer().getClass().getClassLoader(), ClassLoader.getSystemClassLoader()}) {
            try (InputStream in = cl.getResourceAsStream(rel)) {
                if (in != null) return in.readAllBytes();
            } catch (IOException ignored) {
            }
        }
        return null;
    }
}
