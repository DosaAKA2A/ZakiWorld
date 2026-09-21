package net.ederus.edm.boost;

import java.util.Locale;

import org.bukkit.Material;

import net.kyori.adventure.text.format.TextColor;

/**
 * Los tres boosts que existen. "all" no es un tipo: es dar los tres a la vez.
 *
 * Cada uno lleva su color y su icono aqui y no en el config porque son parte de la
 * identidad del boost: si manana cambia el icono, cambia en el menu, en el aviso del
 * chat y en el placeholder a la vez.
 */
public enum Tipo {

    EXP("exp", "Experiencia", 0x5CFF7A, Material.EXPERIENCE_BOTTLE,
            "Multiplica la experiencia que recoges"),
    DROPS("drops", "Drops", 0xFFC64D, Material.HOPPER,
            "Multiplica lo que sueltan mobs y bloques"),
    MINIONS("minions", "Minions", 0x5CC8FF, Material.ARMOR_STAND,
            "Multiplica lo que producen tus minions");

    private final String id;
    private final String nombre;
    private final TextColor color;
    private final Material icono;
    private final String explicacion;

    Tipo(String id, String nombre, int color, Material icono, String explicacion) {
        this.id = id;
        this.nombre = nombre;
        this.color = TextColor.color(color);
        this.icono = icono;
        this.explicacion = explicacion;
    }

    public String id() {
        return id;
    }

    public String nombre() {
        return nombre;
    }

    public TextColor color() {
        return color;
    }

    public Material icono() {
        return icono;
    }

    public String explicacion() {
        return explicacion;
    }

    /** El tipo con ese id, o null. Acepta mayusculas y minusculas. */
    public static Tipo de(String s) {
        if (s == null) return null;
        String k = s.toLowerCase(Locale.ROOT);
        for (Tipo t : values()) if (t.id.equals(k)) return t;
        return null;
    }
}
