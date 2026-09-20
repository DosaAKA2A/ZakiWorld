package net.ederus.edm.tooltip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
        String modo,
        int tope,
        boolean soloSiSePasa,
        boolean lineaEnBlanco,
        boolean lineaEnBlancoAntes,
        boolean absorbidosPrimero,
        String orden,
        String encabezado,
        String vineta,
        TextColor colorVineta,
        TextColor colorNombre,
        TextColor colorMaldicion,
        List<String> paquetes,
        List<Tramo> tramos,
        List<Pattern> absorber) {

    /** "hasta este nivel, este color". El ultimo vale para todo lo que se pase. */
    record Tramo(int hasta, TextColor color) { }

    Component encabezadoComponente() {
        return Estilo.legado(encabezado);
    }

    /**
     * El color del numeral segun lo fuerte que sea el encantamiento: una decena
     * por color, del verde al rojo, igual que las estrellas de los tiers.
     */
    TextColor colorDeNivel(int nivel) {
        TextColor ultimo = NamedTextColor.GRAY;
        for (Tramo t : this.tramos) {
            ultimo = t.color();
            if (nivel <= t.hasta()) {
                return t.color();
            }
        }
        return ultimo;
    }

    static Ajustes de(FileConfiguration c) {
        return new Ajustes(
                c.getString("modo", "clon").toLowerCase(java.util.Locale.ROOT),
                Math.max(1, c.getInt("romanos-hasta", 100)),
                c.getBoolean("solo-los-que-se-rompen", false),
                c.getBoolean("linea-en-blanco", true),
                c.getBoolean("linea-en-blanco-antes", true),
                c.getBoolean("absorbidos-primero", true),
                c.getString("orden", "nombre").toLowerCase(java.util.Locale.ROOT),
                c.getString("encabezado", "&#FFA500▎ Encantamientos:"),
                c.getString("vineta", " · "),
                color(c.getString("colores.vineta"), TextColor.fromHexString("#545454")),
                color(c.getString("colores.nombre"), TextColor.fromHexString("#C8C8C8")),
                color(c.getString("colores.maldicion"), NamedTextColor.RED),
                paquetes(c),
                tramos(c),
                patrones(c));
    }

    /*
     * Que paquetes se enganchan. Existe para poder aislar una averia sin
     * recompilar: quitando uno y recargando se ve al momento si el daño venia
     * por ahi. Vacio = todos.
     */
    private static List<String> paquetes(FileConfiguration c) {
        List<String> l = new ArrayList<>();
        for (String s : c.getStringList("paquetes")) {
            l.add(s.trim().toUpperCase(java.util.Locale.ROOT));
        }
        return List.copyOf(l);
    }

    private static List<Tramo> tramos(FileConfiguration c) {
        List<Tramo> fuera = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("colores.niveles")) {
            Object hasta = m.get("hasta");
            Object col = m.get("color");
            if (hasta instanceof Number n && col != null) {
                TextColor tc = color(String.valueOf(col), null);
                if (tc != null) {
                    fuera.add(new Tramo(n.intValue(), tc));
                }
            }
        }
        if (fuera.isEmpty()) {
            /* Los mismos tramos que trae el config de fabrica, por si alguien
             * borra la lista entera: sin esto todo saldria de un solo color.
             * Diez tramos de diez, del verde al rojo, como las estrellas. */
            int[] hasta = { 10, 20, 30, 40, 50, 60, 70, 80, 90, 100 };
            String[] color = { "#55FF55", "#8CFF4F", "#C4FF4A", "#F2F545", "#FFD23F", "#FFAE3A", "#FF8C35", "#FF6B30", "#FF4A2B", "#FF2626" };
            for (int i = 0; i < hasta.length; i++) {
                fuera.add(new Tramo(hasta[i], TextColor.fromHexString(color[i])));
            }
        }
        fuera.sort(java.util.Comparator.comparingInt(Tramo::hasta));
        return List.copyOf(fuera);
    }

    private static List<Pattern> patrones(FileConfiguration c) {
        List<Pattern> fuera = new ArrayList<>();
        for (String s : c.getStringList("absorber")) {
            try {
                fuera.add(Pattern.compile(s));
            } catch (PatternSyntaxException e) {
                /* Un patron mal escrito no puede tumbar el modulo entero: se
                 * salta y se sigue con los demas. */
                fuera.add(null);
            }
        }
        fuera.removeIf(java.util.Objects::isNull);
        return List.copyOf(fuera);
    }

    private static TextColor color(String hex, TextColor porDefecto) {
        if (hex == null || hex.isBlank()) {
            return porDefecto;
        }
        TextColor c = TextColor.fromHexString(hex.startsWith("#") ? hex : "#" + hex);
        return c == null ? porDefecto : c;
    }
}
