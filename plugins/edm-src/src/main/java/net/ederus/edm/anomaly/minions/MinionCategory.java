package net.ederus.edm.anomaly.minions;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

/**
 * Una carpeta de esbirros: la mazmorra o el proposito al que pertenecen. Al abrir
 * /esb no se ve una lista larga de bichos sueltos, se ven las carpetas —Mina,
 * Test, lo que haga falta— y dentro de cada una su tropa.
 *
 * La carpeta no cambia nada de la pelea: es organizacion. Cada una elige su icono
 * (cualquier objeto del juego) y su color, para reconocerla de un vistazo.
 */
public final class MinionCategory {

    /** La carpeta a la que van los esbirros que no tienen otra. Nunca se borra. */
    public static final String GENERAL = "general";

    private final String id;
    private String display;
    private Material icon = Material.CHEST;
    private int color = 0xFFD966;

    /** Si su _carpeta.yml tiene cambios sin escribir. Nace sucia. */
    private boolean sucia = true;

    boolean sucia() {
        return sucia;
    }

    void limpia() {
        sucia = false;
    }

    public MinionCategory(String id, String display) {
        this.id = id;
        this.display = display;
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public void display(String display) {
        sucia = true;
        this.display = display;
    }

    public Material icon() {
        return icon;
    }

    public void icon(Material icon) {
        sucia = true;
        if (icon != null && icon.isItem()) this.icon = icon;
    }

    public TextColor color() {
        return TextColor.color(color);
    }

    public int colorRgb() {
        return color;
    }

    public void colorRgb(int rgb) {
        sucia = true;
        this.color = rgb;
    }

    /** Avanza (o retrocede) por la misma paleta que usan los esbirros. */
    public void cycleColor(boolean forward) {
        sucia = true;
        int at = 0;
        for (int i = 0; i < MinionType.PALETA.length; i++) {
            if (MinionType.PALETA[i] == color) {
                at = i;
                break;
            }
        }
        this.color = MinionType.PALETA[Math.floorMod(at + (forward ? 1 : -1), MinionType.PALETA.length)];
    }

    public boolean isGeneral() {
        return GENERAL.equals(id);
    }
}
