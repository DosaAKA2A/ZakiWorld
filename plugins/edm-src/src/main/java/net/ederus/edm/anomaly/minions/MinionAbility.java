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
            "Se distingue por su brillo y su sonido."),

    AGIL("agil", "Ágil", Material.FEATHER, NamedTextColor.AQUA,
            "Se mueve un 25% más rápido de lo normal.",
            "Cuesta más dejarlo atrás y flanquea antes."),

    FLECHA_HELADA("flecha-helada", "Flecha helada", Material.POWDER_SNOW_BUCKET, NamedTextColor.BLUE,
            "Sus flechas dejan lentitud 3 segundos.",
            "No aumenta el daño, pero prepara el siguiente golpe."),

    VENENOSO("venenoso", "Venenoso", Material.SPIDER_EYE, NamedTextColor.DARK_GREEN,
            "Cada golpe suyo deja veneno 4 segundos.",
            "Con varios a la vez obliga a curarse."),

    IGNEO("igneo", "Ígneo", Material.BLAZE_POWDER, NamedTextColor.GOLD,
            "Al que golpea lo deja ardiendo 4 segundos.",
            "Penaliza el cuerpo a cuerpo prolongado."),

    ACORAZADO("acorazado", "Acorazado", Material.NETHERITE_SCRAP, NamedTextColor.GRAY,
            "Recibe un 35% menos de daño.",
            "Sostiene el frente mientras el resto rodea."),

    ESPINAS("espinas", "Espinas", Material.CACTUS, NamedTextColor.GREEN,
            "Devuelve un 25% del daño cuerpo a cuerpo.",
            "Intercambiar golpes con él resulta costoso."),

    BERSERK("berserk", "Berserk", Material.REDSTONE, NamedTextColor.RED,
            "Por debajo del 30% de vida inflige un 50% más.",
            "El tramo final del combate es el más peligroso."),

    CURANDERO("curandero", "Curandero", Material.GLISTERING_MELON_SLICE, NamedTextColor.LIGHT_PURPLE,
            "Cada 3 segundos cura a los esbirros cercanos.",
            "Conviene eliminarlo primero o la sala no baja."),

    ALARMA("alarma", "Alarma", Material.BELL, NamedTextColor.YELLOW,
            "Al recibir un golpe, las unidades cercanas cambian de objetivo.",
            "Impide pelear de uno en uno."),

    DIVISION("division", "División", Material.SLIME_BALL, NamedTextColor.AQUA,
            "Al morir se divide en dos crías de la mitad de nivel.",
            "Las crías no se vuelven a dividir.");

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
