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
            "No pega mas, pero te deja a tiro del siguiente."),

    VENENOSO("venenoso", "Venenoso", Material.SPIDER_EYE, NamedTextColor.DARK_GREEN,
            "Cada golpe suyo deja veneno 4 segundos.",
            "Pelear con tres a la vez te obliga a curarte."),

    IGNEO("igneo", "Igneo", Material.BLAZE_POWDER, NamedTextColor.GOLD,
            "Al que golpea lo deja ardiendo 4 segundos.",
            "Castiga quedarse pegado a el."),

    ACORAZADO("acorazado", "Acorazado", Material.NETHERITE_SCRAP, NamedTextColor.GRAY,
            "Recibe un 35% menos de dano.",
            "Aguanta el frente mientras los suyos rodean."),

    ESPINAS("espinas", "Espinas", Material.CACTUS, NamedTextColor.GREEN,
            "Devuelve un 25% del dano cuerpo a cuerpo.",
            "Cambiar golpes con el sale caro."),

    BERSERK("berserk", "Berserk", Material.REDSTONE, NamedTextColor.RED,
            "Por debajo del 30% de vida pega un 50% mas.",
            "El ultimo cuarto de la pelea es el peligroso."),

    CURANDERO("curandero", "Curandero", Material.GLISTERING_MELON_SLICE, NamedTextColor.LIGHT_PURPLE,
            "Cada 3 segundos cura a los esbirros de alrededor.",
            "Hay que matarlo a el primero o la sala no baja."),

    ALARMA("alarma", "Alarma", Material.BELL, NamedTextColor.YELLOW,
            "Al recibir un golpe, la tropa cercana va a por quien se lo dio.",
            "Se acabo pelear de uno en uno."),

    DIVISION("division", "Division", Material.SLIME_BALL, NamedTextColor.AQUA,
            "Al morir se parte en dos crias de la mitad de nivel.",
            "Las crias ya no se dividen: la sala no se desborda.");

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
