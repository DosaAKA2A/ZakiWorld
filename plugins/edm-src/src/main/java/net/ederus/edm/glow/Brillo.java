package net.ederus.edm.glow;

import java.util.Locale;

import com.comphenix.protocol.wrappers.EnumWrappers;

/**
 * Los 16 colores que puede tener un contorno. No hay mas: el cliente pinta el
 * brillo con el color del equipo del marcador, y un equipo solo admite los
 * colores de siempre del chat (nada de hex).
 *
 * Las claves son las de FancyGlow (fancyglow.aqua, fancyglow.dark_red...), asi
 * que los permisos que ya estan en LuckPerms valen tal cual.
 */
enum Brillo {

    RED("red", "Rojo", 0xFF5555, EnumWrappers.ChatFormatting.RED),
    DARK_RED("dark_red", "Rojo oscuro", 0xAA0000, EnumWrappers.ChatFormatting.DARK_RED),
    GOLD("gold", "Dorado", 0xFFAA00, EnumWrappers.ChatFormatting.GOLD),
    YELLOW("yellow", "Amarillo", 0xFFFF55, EnumWrappers.ChatFormatting.YELLOW),
    GREEN("green", "Verde", 0x55FF55, EnumWrappers.ChatFormatting.GREEN),
    DARK_GREEN("dark_green", "Verde oscuro", 0x00AA00, EnumWrappers.ChatFormatting.DARK_GREEN),
    AQUA("aqua", "Celeste", 0x55FFFF, EnumWrappers.ChatFormatting.AQUA),
    DARK_AQUA("dark_aqua", "Turquesa", 0x00AAAA, EnumWrappers.ChatFormatting.DARK_AQUA),
    BLUE("blue", "Azul", 0x5555FF, EnumWrappers.ChatFormatting.BLUE),
    DARK_BLUE("dark_blue", "Azul oscuro", 0x0000AA, EnumWrappers.ChatFormatting.DARK_BLUE),
    LIGHT_PURPLE("light_purple", "Rosa", 0xFF55FF, EnumWrappers.ChatFormatting.LIGHT_PURPLE),
    DARK_PURPLE("dark_purple", "Morado", 0xAA00AA, EnumWrappers.ChatFormatting.DARK_PURPLE),
    WHITE("white", "Blanco", 0xFFFFFF, EnumWrappers.ChatFormatting.WHITE),
    GRAY("gray", "Gris", 0xAAAAAA, EnumWrappers.ChatFormatting.GRAY),
    DARK_GRAY("dark_gray", "Gris oscuro", 0x555555, EnumWrappers.ChatFormatting.DARK_GRAY),
    BLACK("black", "Negro", 0x000000, EnumWrappers.ChatFormatting.BLACK);

    final String clave;
    final String nombre;
    final int rgb;
    final EnumWrappers.ChatFormatting formato;

    Brillo(String clave, String nombre, int rgb, EnumWrappers.ChatFormatting formato) {
        this.clave = clave;
        this.nombre = nombre;
        this.rgb = rgb;
        this.formato = formato;
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
