package net.ederus.edm.glow;

import java.util.Locale;

/**
 * Los 16 colores que puede tener un contorno. No hay mas: el cliente pinta el
 * brillo con el color del equipo del marcador, y un equipo solo admite los
 * colores de siempre del chat (nada de hex).
 *
 * Las claves son las de FancyGlow (fancyglow.aqua, fancyglow.dark_red...), asi
 * que los permisos que ya estan en LuckPerms valen tal cual.
 */
enum Brillo {

    RED("red", "Rojo", 0xFF5555, 'c'),
    DARK_RED("dark_red", "Rojo oscuro", 0xAA0000, '4'),
    GOLD("gold", "Dorado", 0xFFAA00, '6'),
    YELLOW("yellow", "Amarillo", 0xFFFF55, 'e'),
    GREEN("green", "Verde", 0x55FF55, 'a'),
    DARK_GREEN("dark_green", "Verde oscuro", 0x00AA00, '2'),
    AQUA("aqua", "Celeste", 0x55FFFF, 'b'),
    DARK_AQUA("dark_aqua", "Turquesa", 0x00AAAA, '3'),
    BLUE("blue", "Azul", 0x5555FF, '9'),
    DARK_BLUE("dark_blue", "Azul oscuro", 0x0000AA, '1'),
    LIGHT_PURPLE("light_purple", "Rosa", 0xFF55FF, 'd'),
    DARK_PURPLE("dark_purple", "Morado", 0xAA00AA, '5'),
    WHITE("white", "Blanco", 0xFFFFFF, 'f'),
    GRAY("gray", "Gris", 0xAAAAAA, '7'),
    DARK_GRAY("dark_gray", "Gris oscuro", 0x555555, '8'),
    BLACK("black", "Negro", 0x000000, '0');

    final String clave;
    final String nombre;
    final int rgb;
    /** El codigo de color de siempre (&b): es lo que TAB convierte en el color del equipo. */
    final char codigo;

    Brillo(String clave, String nombre, int rgb, char codigo) {
        this.clave = clave;
        this.nombre = nombre;
        this.rgb = rgb;
        this.codigo = codigo;
    }

    String permiso() {
        return "fancyglow." + clave;
    }

    /** Acepta la clave (aqua, dark_red) y el nombre en español sin tildes (celeste). */
    static Brillo de(String s) {
        if (s == null) return null;
        String k = s.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        for (Brillo b : values()) {
            if (b.clave.equals(k) || b.nombre.toLowerCase(Locale.ROOT).replace(' ', '_').equals(k)) return b;
        }
        return null;
    }
}
