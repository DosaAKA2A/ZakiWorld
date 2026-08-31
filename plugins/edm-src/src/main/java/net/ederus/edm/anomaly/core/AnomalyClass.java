package net.ederus.edm.anomaly.core;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * La clase de una anomalia: su rango dentro de la jerarquia del catalogo.
 *
 * Los Esbirros son la clase mas baja y los Monarcas la mas alta. La clase se elige
 * desde el menu (shift + click derecho sobre la anomalia), se guarda en config.yml
 * y ordena el catalogo: primero los Monarcas, despues los Generales y al final los
 * Esbirros.
 */
public enum AnomalyClass {

    ESBIRRO("Esbirro", "La clase mas baja: amenazas menores.", NamedTextColor.GRAY, 1),
    GENERAL("General", "El grueso del catalogo: jefes de pleno derecho.", NamedTextColor.GOLD, 2),
    MONARCA("Monarca", "La clase mas alta del catalogo comun: las anomalias capitales.", NamedTextColor.LIGHT_PURPLE, 3),

    /**
     * Por encima de todo. No es "una clase mas": es un tier unico y señalado,
     * pensado para UNA anomalia cada vez. El catalogo lo ordena primero.
     */
    DIOS("Dios", "Unico y especial: por encima de los Monarcas.", NamedTextColor.WHITE, 4);

    private final String display;
    private final String help;
    private final NamedTextColor color;
    private final int rank;

    AnomalyClass(String display, String help, NamedTextColor color, int rank) {
        this.display = display;
        this.help = help;
        this.color = color;
        this.rank = rank;
    }

    public String display() {
        return display;
    }

    public String help() {
        return help;
    }

    public TextColor color() {
        return color;
    }

    /** 1 = Esbirro, 3 = Monarca. Lo usa el orden del catalogo. */
    public int rank() {
        return rank;
    }

    public AnomalyClass next() {
        AnomalyClass[] v = values();
        return v[(ordinal() + 1) % v.length];
    }

    /** Lee la clase desde config sin romperse con un valor viejo o mal escrito. */
    public static AnomalyClass parse(String raw, AnomalyClass fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
