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
 * Cada una se activa por su cuenta desde la ficha del esbirro. Se guardan en
 * Skills/Esbirros/<id>.yml, una seccion por rasgo con su 'activa' y sus numeros:
 * los que antes eran constantes del motor (cuanto corre el agil, cuanto aguanta
 * el acorazado...) ahora se ajustan esbirro por esbirro. Los valores de serie son
 * los de siempre, asi que un esbirro sin tocar pelea igual que antes.
 */
public enum MinionAbility {

    FLECHA_PESADA("flecha-pesada", "Flecha pesada", Material.SPECTRAL_ARROW, NamedTextColor.GOLD,
            "Cada tercera flecha que dispara pega el doble.",
            "Se distingue por su brillo y su sonido.",
            new Param("cada-n", 3, "cada cuantas flechas sale la pesada"),
            new Param("multiplicador", 2.0, "cuanto pega la pesada (x2 = el doble)")),

    AGIL("agil", "Ágil", Material.FEATHER, NamedTextColor.AQUA,
            "Se mueve un 25% más rápido de lo normal.",
            "Cuesta más dejarlo atrás y flanquea antes.",
            new Param("velocidad", 0.25, "velocidad extra (0.25 = +25%)")),

    FLECHA_HELADA("flecha-helada", "Flecha helada", Material.POWDER_SNOW_BUCKET, NamedTextColor.BLUE,
            "Sus flechas dejan lentitud 3 segundos.",
            "No aumenta el daño, pero prepara el siguiente golpe.",
            new Param("segundos", 3, "cuanto dura la lentitud"),
            new Param("nivel", 1, "nivel de la lentitud (1 = Lentitud I)")),

    VENENOSO("venenoso", "Venenoso", Material.SPIDER_EYE, NamedTextColor.DARK_GREEN,
            "Cada golpe suyo deja veneno 4 segundos.",
            "Con varios a la vez obliga a curarse.",
            new Param("segundos", 4, "cuanto dura el veneno"),
            new Param("nivel", 1, "nivel del veneno (1 = Veneno I)")),

    IGNEO("igneo", "Ígneo", Material.BLAZE_POWDER, NamedTextColor.GOLD,
            "Al que golpea lo deja ardiendo 4 segundos.",
            "Penaliza el cuerpo a cuerpo prolongado.",
            new Param("segundos", 4, "cuanto arde el golpeado")),

    ACORAZADO("acorazado", "Acorazado", Material.NETHERITE_SCRAP, NamedTextColor.GRAY,
            "Recibe un 35% menos de daño.",
            "Sostiene el frente mientras el resto rodea.",
            new Param("reduccion", 0.35, "daño que se quita (0.35 = -35%)")),

    ESPINAS("espinas", "Espinas", Material.CACTUS, NamedTextColor.GREEN,
            "Devuelve un 25% del daño cuerpo a cuerpo.",
            "Intercambiar golpes con él resulta costoso.",
            new Param("devuelve", 0.25, "parte del golpe que devuelve (0.25 = 25%)")),

    BERSERK("berserk", "Berserk", Material.REDSTONE, NamedTextColor.RED,
            "Por debajo del 30% de vida inflige un 50% más.",
            "El tramo final del combate es el más peligroso.",
            new Param("umbral-vida", 0.30, "por debajo de esta vida se enfurece (0.30 = 30%)"),
            new Param("extra", 0.50, "daño extra enfurecido (0.50 = +50%)")),

    CURANDERO("curandero", "Curandero", Material.GLISTERING_MELON_SLICE, NamedTextColor.LIGHT_PURPLE,
            "Cada 3 segundos cura a los esbirros cercanos.",
            "Conviene eliminarlo primero o la sala no baja.",
            new Param("cada-segundos", 3, "cada cuantos segundos cura"),
            new Param("cura", 0.04, "vida que repone, sobre la maxima del herido (0.04 = 4%)"),
            new Param("radio", 8, "bloques a los que llega")),

    ALARMA("alarma", "Alarma", Material.BELL, NamedTextColor.YELLOW,
            "Al recibir un golpe, las unidades cercanas cambian de objetivo.",
            "Impide pelear de uno en uno.",
            new Param("radio", 12, "bloques a los que llega el aviso")),

    DIVISION("division", "División", Material.SLIME_BALL, NamedTextColor.AQUA,
            "Al morir se divide en dos crías de la mitad de nivel.",
            "Las crías no se vuelven a dividir.",
            new Param("crias", 2, "cuantas crias salen"),
            new Param("fraccion-nivel", 0.5, "nivel de las crias sobre el del padre (0.5 = la mitad)"));

    /**
     * Un numero ajustable de un rasgo: su clave en el yml, su valor de serie (el
     * que tenia antes como constante) y una linea de que es, que sale de comentario.
     */
    public record Param(String key, double def, String desc) {
    }

    private final String id;
    private final String display;
    private final Material icon;
    private final TextColor color;
    private final String what;
    private final String why;
    private final java.util.List<Param> params;

    MinionAbility(String id, String display, Material icon, TextColor color, String what, String why,
                  Param... params) {
        this.id = id;
        this.display = display;
        this.icon = icon;
        this.color = color;
        this.what = what;
        this.why = why;
        this.params = java.util.List.of(params);
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

    /** Sus numeros ajustables, en el orden en que se escriben en el yml. */
    public java.util.List<Param> params() {
        return params;
    }

    /** El parametro con esa clave, o null si el rasgo no lo tiene. */
    public Param param(String key) {
        for (Param p : params) {
            if (p.key().equals(key)) return p;
        }
        return null;
    }

    public static MinionAbility byId(String id) {
        if (id == null) return null;
        for (MinionAbility a : values()) {
            if (a.id.equalsIgnoreCase(id)) return a;
        }
        return null;
    }
}
