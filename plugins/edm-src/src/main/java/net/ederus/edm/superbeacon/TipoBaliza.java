package net.ederus.edm.superbeacon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Material;
import org.bukkit.Particle;

/**
 * Un tipo de Super Beacon, leido de superbeacon/config.yml (ver {@link LectorTipos}).
 *
 * Es la plantilla: nombre, bloque, alcance, a quien beneficia, cuanto dura y que efectos
 * ofrece. Lo de cada baliza concreta (dueño, clan, vencimiento, eleccion) va en su
 * {@link Ficha}. Por eso un /superbeacon reload cambia el alcance o los efectos de todas
 * las balizas de ese tipo a la vez, incluidas las que ya estan colocadas.
 */
final class TipoBaliza {

    enum Beneficia { DUENO, CLAN, TODOS }

    enum AlCaducar { APAGAR, DESTRUIR }

    final String id;
    /** Con sus colores, tal cual el config. */
    final String nombre;
    final Material bloque;
    final int radio;
    final int duracionDias;
    final AlCaducar alCaducar;
    final Beneficia beneficia;
    final boolean transferible;
    final boolean cuentaEnElMaximo;
    /** Cuantos efectos a la vez; 0 = todos, siempre. */
    final int elegibles;
    final List<String> descripcion;
    /** En el orden del config, que es el del menu. */
    final Map<String, Efecto> efectos;
    /** null: sin particulas, que es lo de serie. */
    final Particle particula;
    final int particulaCantidad;
    final int particulaCada;

    TipoBaliza(String id, String nombre, Material bloque, int radio, int duracionDias, AlCaducar alCaducar,
               Beneficia beneficia, boolean transferible, boolean cuentaEnElMaximo, int elegibles,
               List<String> descripcion, Map<String, Efecto> efectos, Particle particula,
               int particulaCantidad, int particulaCada) {
        this.id = id;
        this.nombre = nombre;
        this.bloque = bloque;
        this.radio = radio;
        this.duracionDias = duracionDias;
        this.alCaducar = alCaducar;
        this.beneficia = beneficia;
        this.transferible = transferible;
        this.cuentaEnElMaximo = cuentaEnElMaximo;
        this.elegibles = elegibles;
        this.descripcion = List.copyOf(descripcion);
        this.efectos = efectos;
        this.particula = particula;
        this.particulaCantidad = particulaCantidad;
        this.particulaCada = particulaCada;
    }

    /** Todos sus efectos van activos y el menu no deja elegir. */
    boolean fijo() {
        return elegibles <= 0;
    }

    /**
     * Los efectos en marcha con esa eleccion. Con elegibles 0, todos. Si no, los elegidos
     * que existen en el tipo, en el orden del config y sin pasar del tope: un config que
     * baja el tope o quita un efecto no deja balizas con mas de la cuenta.
     */
    List<Efecto> activos(Collection<String> elegidos) {
        if (fijo()) return List.copyOf(efectos.values());
        List<Efecto> out = new ArrayList<>();
        for (Efecto e : efectos.values()) {
            if (out.size() >= elegibles) break;
            if (elegidos.contains(e.clave())) out.add(e);
        }
        return out;
    }

    /** true si es uno de los activos con esa eleccion. */
    boolean activo(Collection<String> elegidos, String clave) {
        for (Efecto e : activos(elegidos)) {
            if (e.clave().equals(clave)) return true;
        }
        return false;
    }

    /** Tiene algun efecto que actua sobre la zona (cultivos, sin-mobs). */
    boolean conZona() {
        for (Efecto e : efectos.values()) {
            if (e.clase() != null && !e.clase().porJugador()) return true;
        }
        return false;
    }

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})|&x((?:&[0-9a-fA-F]){6})");

    /**
     * El color del tipo: el primero de su nombre. Lo usan el borde de "Ver alcance" y poco
     * mas; sin color en el nombre, el azul claro de Ederus.
     */
    int color() {
        Matcher m = HEX.matcher(nombre);
        if (m.find()) {
            String hex = m.group(1) != null ? m.group(1) : m.group(2).replace("&", "");
            try {
                return Integer.parseInt(hex, 16);
            } catch (NumberFormatException ignorado) {
                // cae al de siempre
            }
        }
        return 0xD7F3FF;
    }

    String nombrePlano() {
        return LectorTipos.plano(nombre);
    }
}
