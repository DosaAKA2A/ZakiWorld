package net.ederus.edm.anomaly.core;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

/**
 * El elemento de una anomalia. Decide DONDE puede aparecer, que es lo que separa a
 * una anomalia de tierra de una de agua: no es un adorno, es el filtro de terreno.
 *
 * Cada anomalia nueva declara el suyo y el buscador de sitios hace el resto.
 */
public enum Element {

    /** Suelo firme y seco. Nada de agua ni de lava en la arena. */
    TIERRA("Tierra", "Suelo firme y seco, lejos del agua", NamedTextColor.GOLD, Material.DIRT),

    /** Sobre el agua o pegada a la orilla. Aqui el agua es requisito, no estorbo. */
    AGUA("Agua", "Sobre el agua o en la orilla", NamedTextColor.AQUA, Material.WATER_BUCKET),

    /** A cielo abierto y en alto. Exige altura y nada por encima. */
    VIENTO("Viento", "A cielo abierto y en alto", NamedTextColor.WHITE, Material.FEATHER);

    private final String display;
    private final String terrain;
    private final TextColor color;
    private final Material icon;

    Element(String display, String terrain, TextColor color, Material icon) {
        this.display = display;
        this.terrain = terrain;
        this.color = color;
        this.icon = icon;
    }

    public String display() {
        return display;
    }

    /** Frase corta para el menu y el hover: que clase de sitio busca. */
    public String terrain() {
        return terrain;
    }

    public TextColor color() {
        return color;
    }

    public Material icon() {
        return icon;
    }
}
