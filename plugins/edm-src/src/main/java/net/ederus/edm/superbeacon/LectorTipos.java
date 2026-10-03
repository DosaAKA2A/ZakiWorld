package net.ederus.edm.superbeacon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Estilo;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Lee los tipos de superbeacon/config.yml.
 *
 * Nunca revienta y nunca se calla: todo lo que esta mal va a la lista de errores con su
 * ruta ("tipos.hogar.efectos.vida.atributo: ..."), que el modulo saca por consola al
 * arrancar y en /superbeacon reload. Lo que se puede arreglar con un valor por defecto se
 * arregla y se avisa; lo que no (un bloque que no existe, una clase de efecto
 * desconocida) deja fuera ese tipo o ese efecto y nada mas.
 */
final class LectorTipos {

    /**
     * El alcance mas grande que se acepta. Cada baliza se apunta en todos los chunks que
     * cubre su alcance (ver {@link Alcance}): con 128 son unos 17x17 chunks por baliza,
     * que sigue siendo poco, y ya es un circulo de 256 bloques de diametro.
     */
    static final int RADIO_MAX = 128;

    /** Los que caben en el menu: dos filas de siete. */
    static final int MAX_EFECTOS = 14;

    private LectorTipos() {
    }

    static Map<String, TipoBaliza> leer(ConfigurationSection raiz, Map<String, ClaseEfecto> clases,
                                        List<String> errores) {
        Map<String, TipoBaliza> out = new LinkedHashMap<>();
        if (raiz == null) {
            errores.add("tipos: no hay seccion de tipos; no hay ningun Super Beacon");
            return out;
        }
        for (String clave : raiz.getKeys(false)) {
            String donde = "tipos." + clave;
            String id = clave.toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_-]+")) {
                errores.add(donde + ": el nombre del tipo solo admite letras sin tilde, numeros, - y _; no se carga");
                continue;
            }
            if (out.containsKey(id)) {
                errores.add(donde + ": ya hay un tipo '" + id + "' (las mayusculas no cuentan); no se carga");
                continue;
            }
            ConfigurationSection s = raiz.getConfigurationSection(clave);
            if (s == null) {
                errores.add(donde + ": no es una seccion; no se carga");
                continue;
            }
            TipoBaliza t = tipo(id, donde, s, clases, errores);
            if (t != null) out.put(id, t);
        }
        return out;
    }

    private static TipoBaliza tipo(String id, String donde, ConfigurationSection s, Map<String, ClaseEfecto> clases,
                                   List<String> errores) {
        String nombre = s.getString("nombre", "&f" + id);

        String bloqueTxt = s.getString("bloque", "BEACON");
        Material bloque = Material.matchMaterial(bloqueTxt == null ? "" : bloqueTxt.trim());
        if (bloque == null || bloque.isAir() || !bloque.isBlock() || !bloque.isItem()) {
            errores.add(donde + ".bloque: '" + bloqueTxt + "' no es un bloque que se pueda colocar; el tipo no se carga");
            return null;
        }

        int radio = s.getInt("radio", 24);
        if (radio < 1 || radio > RADIO_MAX) {
            int arreglado = Math.max(1, Math.min(RADIO_MAX, radio));
            errores.add(donde + ".radio: " + radio + " esta fuera de 1.." + RADIO_MAX + "; se usa " + arreglado);
            radio = arreglado;
        }

        // getInt() se comia los decimales sin avisar: un "0.5" (12 h de prueba) quedaba en 0,
        // que es "no caduca", y un "30d" tambien. Una duracion que no es un numero deja el tipo
        // fuera: mejor que /superbeacon give falle a que reparta permanentes por un error.
        Object diasTxt = s.get("duracion-dias");
        if (diasTxt != null && !(diasTxt instanceof Number)) {
            errores.add(donde + ".duracion-dias: '" + diasTxt + "' no es un numero de dias; el tipo no se carga");
            return null;
        }
        double dias = s.getDouble("duracion-dias", 0);
        if (dias < 0 || Double.isNaN(dias) || Double.isInfinite(dias)) {
            errores.add(donde + ".duracion-dias: " + diasTxt + " no sirve; se usa 0 (no caduca)");
            dias = 0;
        }

        TipoBaliza.AlCaducar alCaducar = switch (s.getString("al-caducar", "apagar").trim().toLowerCase(Locale.ROOT)) {
            case "apagar" -> TipoBaliza.AlCaducar.APAGAR;
            case "destruir" -> TipoBaliza.AlCaducar.DESTRUIR;
            default -> {
                errores.add(donde + ".al-caducar: '" + s.getString("al-caducar") + "' no sirve (apagar o destruir); se usa apagar");
                yield TipoBaliza.AlCaducar.APAGAR;
            }
        };

        TipoBaliza.Beneficia beneficia = switch (s.getString("beneficia", "dueno").trim().toLowerCase(Locale.ROOT)) {
            case "dueno", "dueño", "owner" -> TipoBaliza.Beneficia.DUENO;
            case "clan" -> TipoBaliza.Beneficia.CLAN;
            case "todos", "all" -> TipoBaliza.Beneficia.TODOS;
            default -> {
                errores.add(donde + ".beneficia: '" + s.getString("beneficia") + "' no sirve (dueno, clan o todos); se usa dueno");
                yield TipoBaliza.Beneficia.DUENO;
            }
        };

        int elegibles = s.getInt("elegibles", 0);
        if (elegibles < 0) {
            errores.add(donde + ".elegibles: " + elegibles + " es negativo; se usa 0 (todos activos)");
            elegibles = 0;
        }

        Map<String, Efecto> efectos = new LinkedHashMap<>();
        ConfigurationSection es = s.getConfigurationSection("efectos");
        if (es == null || es.getKeys(false).isEmpty()) {
            errores.add(donde + ".efectos: no tiene ningun efecto; se carga, pero no hace nada");
        } else {
            for (String k : es.getKeys(false)) {
                Efecto e = efecto(donde + ".efectos." + k, k, es.getConfigurationSection(k), clases, errores);
                if (e != null) efectos.put(e.clave(), e);
            }
            if (efectos.size() > MAX_EFECTOS) {
                errores.add(donde + ".efectos: tiene " + efectos.size() + "; el menu muestra los " + MAX_EFECTOS
                        + " primeros (dos filas de siete)");
            }
        }

        Particle particula = null;
        int cantidad = 1, cada = 4;
        ConfigurationSection ps = s.getConfigurationSection("particula");
        if (ps != null) {
            String n = ps.getString("tipo", "").trim();
            if (n.equalsIgnoreCase("note") || n.equalsIgnoreCase("minecraft:note")) {
                errores.add(donde + ".particula: la nota musical no se usa en Ederus; va sin particula");
            } else if (!n.isEmpty()) {
                particula = Compat.particleByName(n.toLowerCase(Locale.ROOT).startsWith("minecraft:") ? n.substring(10) : n);
                if (particula == null) errores.add(donde + ".particula.tipo: '" + n + "' no existe en esta version; va sin particula");
            }
            cantidad = Math.max(1, Math.min(20, ps.getInt("cantidad", 1)));
            cada = Math.max(1, Math.min(60, ps.getInt("cada-segundos", 4)));
        }

        return new TipoBaliza(id, nombre, bloque, radio, dias, alCaducar, beneficia,
                s.getBoolean("transferible", true), s.getBoolean("cuenta-en-el-maximo", true), elegibles,
                s.getStringList("descripcion"), efectos, particula, cantidad, cada);
    }

    private static Efecto efecto(String donde, String clave, ConfigurationSection e, Map<String, ClaseEfecto> clases,
                                 List<String> errores) {
        if (e == null) {
            errores.add(donde + ": no es una seccion; se salta");
            return null;
        }
        String k = clave.toLowerCase(Locale.ROOT);
        if (!k.matches("[a-z0-9_-]+")) {
            errores.add(donde + ": la clave del efecto solo admite letras sin tilde, numeros, - y _; se salta");
            return null;
        }
        String claseTxt = e.getString("tipo", "").trim().toLowerCase(Locale.ROOT);
        ClaseEfecto clase = clases.get(claseTxt);
        if (clase == null) {
            errores.add(donde + ".tipo: '" + e.getString("tipo", "") + "' no es una clase de efecto (hay: "
                    + String.join(", ", clases.keySet()) + "); se salta");
            return null;
        }
        Material icono = clase.icono();
        String iconoTxt = e.getString("icono");
        if (iconoTxt != null && !iconoTxt.isBlank()) {
            Material m = Material.matchMaterial(iconoTxt.trim());
            if (m == null || m.isAir() || !m.isItem()) {
                errores.add(donde + ".icono: '" + iconoTxt + "' no es un objeto; se usa " + icono.name());
            } else {
                icono = m;
            }
        }
        String nombre = e.getString("nombre", k);
        return clase.leer(k, nombre, icono, e, m -> errores.add(donde + ": " + m));
    }

    /** Un texto con colores &, sin ellos: para la consola, los placeholders y comparar. */
    static String plano(String legado) {
        if (legado == null || legado.isEmpty()) return "";
        return PlainTextComponentSerializer.plainText().serialize(Estilo.legado(legado));
    }
}
