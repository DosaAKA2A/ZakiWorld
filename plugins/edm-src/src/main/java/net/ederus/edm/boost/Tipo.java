package net.ederus.edm.boost;

import java.util.Locale;

import org.bukkit.Material;

import net.kyori.adventure.text.format.TextColor;

/**
 * Los boosts que existen. "all" no es un tipo: es dar todos los disponibles a la vez.
 *
 * Cada uno lleva su color y su icono aqui y no en el config porque son parte de la
 * identidad del boost: si manana cambia el icono, cambia en el menu, en el aviso del
 * chat y en el placeholder a la vez. El orden de la lista es el del menu.
 *
 * El boost de drops ya NO existe (EDM 1.74.0): se uso para duplicar objetos. No hay
 * clave de config que lo devuelva; los que quedaran guardados se descartan al cargar.
 * Ningun tipo de esta lista multiplica objetos salvo MINIONS (ver {@link Miniones}).
 */
public enum Tipo {

    EXP("exp", "Experiencia", 0x5CFF7A, Material.EXPERIENCE_BOTTLE,
            "Multiplica la experiencia que recoges"),
    SKILL_EXP("skill_exp", "Experiencia de habilidades", 0xC08CFF, Material.ENCHANTED_BOOK,
            "Multiplica la experiencia que ganan tus habilidades"),
    PESCA("pesca", "Suerte de pesca", 0x4FD8E8, Material.FISHING_ROD,
            "Mejora la rareza de las cajas que pescas"),
    MOBCOINS("mobcoins", "MobCoins", 0xFFD35C, Material.SUNFLOWER,
            "Multiplica las MobCoins que pagan los jefes y los esbirros"),
    MINIONS("minions", "Minions", 0x5CC8FF, Material.ARMOR_STAND,
            "Multiplica lo que producen tus minions");

    /** Lo que se escribia antes y ya no es un tipo. Solo sirve para avisar con claridad. */
    public static final String RETIRADO_DROPS = "drops";

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

    /**
     * El tipo con ese id, o null. Acepta mayusculas, guion o guion bajo y algun
     * nombre en espanol, que es lo que alguien escribe de memoria en una caja.
     */
    public static Tipo de(String s) {
        if (s == null) return null;
        String k = s.toLowerCase(Locale.ROOT).replace('-', '_');
        for (Tipo t : values()) if (t.id.equals(k)) return t;
        return switch (k) {
            case "xp", "experiencia" -> EXP;
            case "skillexp", "skill_xp", "skillxp", "skills", "habilidades", "auraskills" -> SKILL_EXP;
            case "fish", "fishing", "pescao" -> PESCA;
            case "mc", "coins", "monedas" -> MOBCOINS;
            case "minion" -> MINIONS;
            default -> null;
        };
    }
}
