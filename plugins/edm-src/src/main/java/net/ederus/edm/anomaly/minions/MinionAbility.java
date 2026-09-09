package net.ederus.edm.anomaly.minions;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

/**
 * Las habilidades de un esbirro. A diferencia de las de un jefe, que son rutinas
 * guionizadas por fases, estas son RASGOS: se encienden y se apagan, no tienen
 * enfriamiento ni aviso previo, y se notan solas mientras el bicho pelea. Un
 * esbirro es tropa, no un combate de cinco minutos.
 *
 * Cada una se activa por su cuenta desde la ficha del esbirro, y se guardan en
 * esbirros.yml como una lista de ids.
 */
public enum MinionAbility {

    FLECHA_PESADA("flecha-pesada", "Flecha pesada", Material.SPECTRAL_ARROW, NamedTextColor.GOLD,
            "Cada tercera flecha que dispara pega el doble.",
            "Sale brillando y suena distinto: se ve venir."),

    AGIL("agil", "Agil", Material.FEATHER, NamedTextColor.AQUA,
            "Se mueve un 25% mas rapido de lo normal.",
            "Cuesta mas dejarlo atras y flanquea antes."),

    FLECHA_HELADA("flecha-helada", "Flecha helada", Material.POWDER_SNOW_BUCKET, NamedTextColor.BLUE,
            "Sus flechas dejan lentitud 3 segundos.",
            "No pega mas, pero te deja a tiro del siguiente.");

    private final String id;
    private final String display;
    private final Material icon;
    private final TextColor color;
    private final String what;
    private final String why;

    MinionAbility(String id, String display, Material icon, TextColor color, String what, String why) {
        this.id = id;
        this.display = display;
        this.icon = icon;
        this.color = color;
        this.what = what;
        this.why = why;
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public Material icon() {
        return icon;
    }

    public TextColor color() {
        return color;
    }

    /** Que hace, en una linea. */
    public String what() {
        return what;
    }

    /** Por que importa en una pelea, en una linea. */
    public String why() {
        return why;
    }

    public static MinionAbility byId(String id) {
        if (id == null) return null;
        for (MinionAbility a : values()) {
            if (a.id.equalsIgnoreCase(id)) return a;
        }
        return null;
    }
}
