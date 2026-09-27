package net.ederus.calamity.hardcore;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Las reglas del Engarzador, sin tocar MMOItems: que pieza y que gema se aceptan, en que hueco
 * entraria una gema y como se le dice al jugador por que no. Lo que sabe de cada objeto lo trae
 * EngarceMmo (Ficha); aqui solo se decide, y por eso el autotest lo puede probar entero con
 * fichas inventadas ademas de con objetos reales.
 *
 * Decisiones de Dosa (2026-09-27): solo gemas de Calamity y solo en piezas de Calamity con un
 * hueco libre de su color; engarzar es gratis; quitar una gema la rompe y deja el hueco libre.
 */
final class Engarce {

    private Engarce() {
    }

    /**
     * Un hueco de gema de una pieza: su color y, si esta ocupado, la gema que lleva (su nombre
     * limpio, su TIPO.ID y el UUID con el que MMOItems la guarda en la pieza; "" si no trae).
     */
    record Hueco(String color, String gema, String gemaEnlace, String uuid) {

        boolean libre() {
            return uuid == null;
        }
    }

    /** Lo que el Engarzador necesita saber de un objeto de MMOItems. colorGema solo en las gemas. */
    record Ficha(String tipo, String id, boolean esGema, String colorGema, List<Hueco> huecos) {

        String enlace() {
            return tipo + "." + id;
        }

        List<Hueco> libres() {
            List<Hueco> out = new ArrayList<>();
            for (Hueco h : huecos) if (h.libre()) out.add(h);
            return out;
        }

        List<Hueco> ocupados() {
            List<Hueco> out = new ArrayList<>();
            for (Hueco h : huecos) if (!h.libre()) out.add(h);
            return out;
        }
    }

    enum Estado { HECHO, ROTA, NADA }

    /**
     * Lo que sale de engarzar o quitar (EngarceMmo). HECHO: pieza es la nueva. ROTA: la gema se
     * rompio al engarzarla (tasa de exito de MMOItems) y la pieza no cambia. NADA: no se toco
     * nada; detalle dice por que. Vive aqui y no en EngarceMmo para que quien lo lee no tenga
     * que cargar la clase que importa MMOItems.
     */
    record Resultado(Estado estado, ItemStack pieza, String detalle) {
    }

    // Por que no se puede. Cada uno tiene su frase en texto().
    static final String SIN_PIEZA = "sin-pieza";
    static final String SIN_GEMA = "sin-gema";
    /** No es de MMOItems (o MMOItems no esta): ni pieza ni gema. */
    static final String NO_MMO = "no-mmo";
    /** Es una gema puesta donde va la pieza. */
    static final String ES_GEMA = "es-gema";
    static final String PIEZA_AJENA = "pieza-ajena";
    static final String SIN_HUECOS = "sin-huecos";
    static final String LLENA = "llena";
    static final String NO_ES_GEMA = "no-es-gema";
    static final String GEMA_AJENA = "gema-ajena";
    static final String COLOR = "color";

    /** Null si la pieza vale para el Engarzador (aunque tenga los huecos llenos: se pueden quitar). */
    static String motivoPieza(Ficha f, Set<String> tiposPieza) {
        if (f == null) return NO_MMO;
        if (f.esGema()) return ES_GEMA;
        if (!contiene(tiposPieza, f.tipo())) return PIEZA_AJENA;
        if (f.huecos().isEmpty()) return SIN_HUECOS;
        return null;
    }

    /** Null si es una gema de Calamity. */
    static String motivoGema(Ficha f, Set<String> tiposGema) {
        if (f == null || !f.esGema()) return NO_ES_GEMA;
        if (!contiene(tiposGema, f.tipo())) return GEMA_AJENA;
        return null;
    }

    /** Null si esa gema entra en esa pieza ahora mismo. sinColor: el hueco "sin color" de MMOItems. */
    static String motivoEngarce(Ficha pieza, Ficha gema, Set<String> tiposPieza, Set<String> tiposGema, String sinColor) {
        String m = motivoPieza(pieza, tiposPieza);
        if (m != null) return m;
        m = motivoGema(gema, tiposGema);
        if (m != null) return m;
        if (pieza.libres().isEmpty()) return LLENA;
        if (hueco(pieza, gema.colorGema(), sinColor) == null) return COLOR;
        return null;
    }

    /**
     * El color del hueco libre donde entraria una gema de ese color, o null. La misma regla que
     * GemSocketsData.getEmptySocket de MMOItems 6.10: una gema sin color entra en cualquiera, un
     * hueco sin color admite cualquier gema y si no, el color tiene que ser el mismo.
     */
    static String hueco(Ficha pieza, String color, String sinColor) {
        for (Hueco h : pieza.huecos()) {
            if (!h.libre()) continue;
            String c = h.color();
            if (color == null || color.isEmpty() || c.equals(sinColor) || color.equals(c)) return c;
        }
        return null;
    }

    private static boolean contiene(Set<String> tipos, String tipo) {
        return tipo != null && tipos.contains(tipo.toUpperCase(Locale.ROOT));
    }

    /** Lo que se le dice al jugador. pieza y gema pueden ser null (se usan para el color). */
    static String texto(String motivo, Ficha pieza, Ficha gema) {
        return switch (motivo) {
            case SIN_PIEZA -> "Primero pon la pieza: tócala en tu inventario.";
            case SIN_GEMA -> "Falta la gema: tócala en tu inventario.";
            case NO_MMO -> "Eso no es ni una pieza de Calamity ni una gema.";
            case ES_GEMA -> "Eso es una gema, no una pieza.";
            case PIEZA_AJENA -> "El Engarzador solo trabaja con piezas de Calamity.";
            case SIN_HUECOS -> "Esta pieza no admite gemas.";
            case LLENA -> "A esta pieza no le quedan huecos libres.";
            case NO_ES_GEMA -> "Eso no es una gema.";
            case GEMA_AJENA -> "Esa gema no es de Calamity.";
            case COLOR -> textoColor(pieza, gema);
            default -> "Ahora mismo no se puede.";
        };
    }

    private static String textoColor(Ficha pieza, Ficha gema) {
        Set<String> colores = new LinkedHashSet<>();
        if (pieza != null) for (Hueco h : pieza.libres()) colores.add(h.color());
        String deGema = gema == null || gema.colorGema() == null || gema.colorGema().isEmpty() ? "otro" : gema.colorGema();
        if (colores.isEmpty()) return "El color de la gema no encaja con esta pieza.";
        return "El color no encaja: la gema es de color " + deGema + " y "
                + (colores.size() == 1 ? "el hueco libre es de color " : "los huecos libres son de color ")
                + String.join(", ", colores) + ".";
    }
}
