package net.ederus.edm.goditems.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import net.ederus.edm.goditems.equipo.Calculo.Resultado;
import net.ederus.edm.goditems.equipo.Definicion.Clave;
import net.ederus.edm.goditems.equipo.Definicion.Config;
import net.ederus.edm.goditems.equipo.Definicion.Conjunto;
import net.ederus.edm.goditems.equipo.Definicion.Pieza;
import net.ederus.edm.goditems.equipo.Equipo;
import net.ederus.edm.goditems.equipo.Informe;

/**
 * La API de los efectos de equipo de GodItems, para los plugins de modo.
 *
 * Calamity lee aqui sus `calamity.*` y PremioPescao sus `pesca.*`, en vez de
 * tener cada uno su propio fichero de equipo y su propio lector de MMOItems. Los
 * valores ya vienen SUMADOS (piezas + escalones de set) y TOPADOS con el tope y
 * el minimo de cada clave, que se declaran en un solo sitio: los YAML de
 * `plugins/EDM/goditems/equipo/`.
 *
 * Contrato (estable; si cambia, sube {@link #VERSION}):
 *   - todo son metodos estaticos con tipos del JDK y de Bukkit, para que se
 *     puedan llamar tambien por reflexion sin depender de EDM al compilar
 *     (es lo que hace PremioPescao);
 *   - se llaman desde el hilo principal;
 *   - nunca lanzan: sin GodItems en marcha, {@link #disponible()} es false,
 *     los numeros son 0 y los mapas y listas, vacios;
 *   - las claves van en minusculas ("pesca.cajas").
 *
 * Cuando cambia lo que cuenta del equipo de alguien salta
 * {@link EquipoCambiadoEvent}, por si hace falta reaccionar al momento.
 */
public final class EquipoApi {

    /** La version del contrato. 1 = la primera (EDM 1.72.2); 2 = carnadas de pesca (EDM 1.75.0). */
    public static final int VERSION = 2;

    private static volatile Equipo equipo;

    private EquipoApi() { }

    /** Lo engancha GodItems al arrancar y lo suelta al apagar. No es para otros plugins. */
    public static void enganchar(Equipo e) {
        equipo = e;
    }

    /** True si GodItems esta en marcha con sus efectos de equipo. */
    public static boolean disponible() {
        return equipo != null;
    }

    public static int version() {
        return VERSION;
    }

    /* ============================================================ claves */

    /** Lo que suma el equipo de ese jugador en esa clave, ya topado; 0 si nada. */
    public static double efecto(Player p, String clave) {
        Equipo e = equipo;
        if (e == null || p == null || clave == null) return 0;
        try {
            return e.resultado(p).de(clave.toLowerCase(Locale.ROOT));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Lo mismo sin topar (para ensenar "sin tope seria..."). */
    public static double efectoSinTope(Player p, String clave) {
        Equipo e = equipo;
        if (e == null || p == null || clave == null) return 0;
        try {
            Double v = e.resultado(p).bruto().get(clave.toLowerCase(Locale.ROOT));
            return v == null ? 0 : v;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Todas las claves que suma su equipo, topadas. */
    public static Map<String, Double> efectos(Player p) {
        return efectos(p, "");
    }

    /** Las claves que empiezan por ese prefijo ("pesca."), topadas. */
    public static Map<String, Double> efectos(Player p, String prefijo) {
        Equipo e = equipo;
        if (e == null || p == null) return Map.of();
        try {
            return filtrar(e.resultado(p).topado(), prefijo);
        } catch (Throwable t) {
            return Map.of();
        }
    }

    /** El tope de una clave, o null si no tiene (o no esta declarada). */
    public static Double tope(String clave) {
        Clave k = clave(clave);
        return k == null ? null : k.tope();
    }

    /** El minimo de una clave, o null. */
    public static Double minimo(String clave) {
        Clave k = clave(clave);
        return k == null ? null : k.minimo();
    }

    /** True si la clave esta declarada en algun YAML de equipo/. */
    public static boolean declarada(String clave) {
        return clave(clave) != null;
    }

    /** True si alguna pieza o escalon de set usa alguna clave con ese prefijo. Sirve de atajo: sin nada, ni se mira. */
    public static boolean hayClaves(String prefijo) {
        Equipo e = equipo;
        if (e == null) return false;
        String pre = prefijo == null ? "" : prefijo.toLowerCase(Locale.ROOT);
        Config c = e.config();
        for (Pieza p : c.piezas().values()) {
            for (String k : p.efectos().claves().keySet()) if (k.startsWith(pre)) return true;
        }
        for (Conjunto s : c.sets()) {
            for (var esc : s.escalones()) for (String k : esc.efectos().claves().keySet()) if (k.startsWith(pre)) return true;
        }
        return false;
    }

    /* ============================================================== sets */

    /** True si lleva al menos el escalon mas bajo de ese set. */
    public static boolean llevaSet(Player p, String set) {
        Equipo e = equipo;
        if (e == null || p == null || set == null) return false;
        try {
            return e.resultado(p).activos().containsKey(set.toLowerCase(Locale.ROOT));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Cuantas piezas de ese set cuentan ahora. */
    public static int piezasDelSet(Player p, String set) {
        Equipo e = equipo;
        if (e == null || p == null || set == null) return 0;
        try {
            Integer n = e.resultado(p).piezasPorSet().get(set.toLowerCase(Locale.ROOT));
            return n == null ? 0 : n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Los ids de los sets con algun escalon alcanzado, los que dan alguna clave con ese prefijo ("" = todos). */
    public static List<String> setsActivos(Player p, String prefijo) {
        Equipo e = equipo;
        if (e == null || p == null) return List.of();
        try {
            Resultado r = e.resultado(p);
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, List<net.ederus.edm.goditems.equipo.Definicion.Escalon>> x : r.activos().entrySet()) {
                if (setUsa(e.config().set(x.getKey()), prefijo)) out.add(x.getKey());
            }
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** Cuantas piezas suyas cuentan y dan (o completan un set que da) alguna clave con ese prefijo. */
    public static int piezasContadas(Player p, String prefijo) {
        Equipo e = equipo;
        if (e == null || p == null) return 0;
        try {
            Config c = e.config();
            Resultado r = e.resultado(p);
            int n = 0;
            for (var h : r.huecos()) {
                if (!h.cuenta()) continue;
                if (piezaUsa(c.piezas().get(h.id()), prefijo)) {
                    n++;
                    continue;
                }
                for (Conjunto s : c.sets()) {
                    if (s.tiene(h.id(), h.setMmo()) && setUsa(s, prefijo)) {
                        n++;
                        break;
                    }
                }
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /* =========================================================== informe */

    /**
     * Lo de /gi equipo en lineas de texto con codigos & (para Bedrock tambien):
     * cada hueco, lo que cuenta y por que no, los sets y el total con topes.
     * prefijo acota las claves que se ensenan ("calamity.", "pesca."; "" = todas).
     */
    public static List<String> informe(Player p, String prefijo) {
        Equipo e = equipo;
        if (e == null || p == null) return List.of();
        try {
            return Informe.lineas(e, p, prefijo == null ? "" : prefijo);
        } catch (Throwable t) {
            return List.of("&cNo se pudo hacer el informe: " + t);
        }
    }

    /* ======================================================== simulacion */

    /**
     * La cuenta para un equipo cualquiera, sin jugador: las claves con ese
     * prefijo, topadas. Es lo que usan los autotest de Calamity y PremioPescao
     * con items reales generados por MMOItems.
     */
    public static Map<String, Double> simular(Map<EquipmentSlot, ItemStack> equipo, String prefijo) {
        Equipo e = EquipoApi.equipo;
        if (e == null || equipo == null) return Map.of();
        try {
            return filtrar(e.calcular(e.config(), equipo).topado(), prefijo);
        } catch (Throwable t) {
            return Map.of();
        }
    }

    /** Las piezas (TIPO.ID) que dan alguna clave con ese prefijo, o que estan en un set que la da. */
    public static List<String> piezas(String prefijo) {
        Equipo e = equipo;
        if (e == null) return List.of();
        Config c = e.config();
        List<String> out = new ArrayList<>();
        for (Pieza p : c.piezas().values()) if (piezaUsa(p, prefijo)) out.add(p.id());
        for (Conjunto s : c.sets()) {
            if (!setUsa(s, prefijo)) continue;
            for (String id : s.piezas()) if (!out.contains(id)) out.add(id);
        }
        return out;
    }

    /** Lo que declara una pieza por si sola (sin sets ni topes) en las claves con ese prefijo. */
    public static Map<String, Double> efectosDePieza(String tipoId, String prefijo) {
        Equipo e = equipo;
        if (e == null || tipoId == null) return Map.of();
        Pieza p = e.config().piezas().get(tipoId.toUpperCase(Locale.ROOT));
        return p == null ? Map.of() : filtrar(p.efectos().claves(), prefijo);
    }

    /* =========================================================== carnadas */

    /** Los ids de las carnadas declaradas (en minusculas, en el orden de los YAML). Desde VERSION 2. */
    public static List<String> carnadas() {
        Equipo e = equipo;
        return e == null ? List.of() : List.copyOf(e.config().carnadas().keySet());
    }

    /** El nombre de una carnada ("Carnada basica"), o null si no esta declarada. */
    public static String nombreCarnada(String id) {
        Equipo e = equipo;
        var c = e == null ? null : e.config().carnada(id);
        return c == null ? null : c.nombre();
    }

    /** Lo que da una carnada por si sola (sin topes) en las claves con ese prefijo. */
    public static Map<String, Double> efectosDeCarnada(String id, String prefijo) {
        Equipo e = equipo;
        var c = e == null ? null : e.config().carnada(id);
        return c == null ? Map.of() : filtrar(c.efectos().claves(), prefijo);
    }

    /** La linea de lore de una clave con ese valor, como la escribe GodItems en las piezas ("" = no sale). */
    public static String loreDeClave(String clave, double valor) {
        Equipo e = equipo;
        if (e == null || clave == null) return "";
        try {
            return net.ederus.edm.goditems.equipo.Redaccion.lineaClave(e.config(), clave.toLowerCase(Locale.ROOT), valor);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Un item nuevo de MMOItems ("TIPO.ID"), o null sin MMOItems o si no existe. */
    public static ItemStack crear(String tipoId) {
        Equipo e = equipo;
        if (e == null || tipoId == null) return null;
        int p = tipoId.indexOf('.');
        if (p <= 0) return null;
        try {
            return e.crear(tipoId.substring(0, p), tipoId.substring(p + 1));
        } catch (Throwable t) {
            return null;
        }
    }

    /** El "TIPO.ID" de MMOItems de un item, o null. Leido como lo lee GodItems. */
    public static String identidad(ItemStack item) {
        Equipo e = equipo;
        if (e == null || item == null) return null;
        try {
            return e.identidad(item);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Resumen de lo cargado ("5 piezas, 1 set y 14 claves"). */
    public static String resumen() {
        Equipo e = equipo;
        return e == null ? "GodItems no está en marcha" : e.resumen();
    }

    /* ============================================================ ayudas */

    private static Clave clave(String clave) {
        Equipo e = equipo;
        return e == null || clave == null ? null : e.config().claves().get(clave.toLowerCase(Locale.ROOT));
    }

    private static Map<String, Double> filtrar(Map<String, Double> m, String prefijo) {
        String pre = prefijo == null ? "" : prefijo.toLowerCase(Locale.ROOT);
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> x : m.entrySet()) if (x.getKey().startsWith(pre)) out.put(x.getKey(), x.getValue());
        return Collections.unmodifiableMap(out);
    }

    private static boolean piezaUsa(Pieza p, String prefijo) {
        if (p == null) return false;
        String pre = prefijo == null ? "" : prefijo.toLowerCase(Locale.ROOT);
        if (pre.isEmpty()) return !p.efectos().vacio();
        for (String k : p.efectos().claves().keySet()) if (k.startsWith(pre)) return true;
        return false;
    }

    private static boolean setUsa(Conjunto s, String prefijo) {
        if (s == null) return false;
        String pre = prefijo == null ? "" : prefijo.toLowerCase(Locale.ROOT);
        for (var esc : s.escalones()) {
            if (pre.isEmpty() && !esc.efectos().vacio()) return true;
            for (String k : esc.efectos().claves().keySet()) if (k.startsWith(pre)) return true;
        }
        return pre.isEmpty();
    }
}
