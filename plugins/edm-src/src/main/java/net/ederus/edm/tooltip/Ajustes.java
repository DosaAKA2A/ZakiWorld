package net.ederus.edm.tooltip;

import org.bukkit.configuration.file.FileConfiguration;

import net.ederus.edm.comun.Estilo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * Lo que se puede tocar sin recompilar. Se lee entero de una vez y se pasa al
 * reescritor como un bloque inmutable: el listener corre en el hilo del paquete
 * y leer el config desde ahi seria pedir una carrera de datos.
 */
record Ajustes(
        int tope,
        boolean soloSiSePasa,
        boolean lineaEnBlanco,
        String orden,
        String encabezado,
        String vineta,
        TextColor colorVineta,
        TextColor colorNombre,
        TextColor colorNivel,
        TextColor colorMaldicion) {

    Component encabezadoComponente() {
        return Estilo.legado(encabezado);
    }

    static Ajustes de(FileConfiguration c) {
        return new Ajustes(
                Math.max(1, c.getInt("romanos-hasta", 20)),
                c.getBoolean("solo-los-que-se-rompen", false),
                c.getBoolean("linea-en-blanco", true),
                c.getString("orden", "nombre").toLowerCase(java.util.Locale.ROOT),
                c.getString("encabezado", "&x&F&F&6&F&D&8▎Encantamientos:"),
                c.getString("vineta", " · "),
                color(c.getString("colores.vineta"), NamedTextColor.DARK_GRAY),
                color(c.getString("colores.nombre"), NamedTextColor.GRAY),
                color(c.getString("colores.nivel"), TextColor.fromHexString("#FF6FD8")),
                color(c.getString("colores.maldicion"), NamedTextColor.RED));
    }

    private static TextColor color(String hex, TextColor porDefecto) {
        if (hex == null || hex.isBlank()) {
            return porDefecto;
        }
        TextColor c = TextColor.fromHexString(hex.startsWith("#") ? hex : "#" + hex);
        return c == null ? porDefecto : c;
    }
}
