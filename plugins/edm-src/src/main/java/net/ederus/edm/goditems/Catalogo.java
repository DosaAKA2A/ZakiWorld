package net.ederus.edm.goditems;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Material;

/**
 * La FICHA TECNICA de cada accion y de cada condicion: que grupo, que hace, que
 * argumentos acepta y de que tipo es cada uno.
 *
 * Por que existe: el motor (`Acciones`, `Condiciones`) sabe EJECUTAR una linea,
 * pero no sabe nada de ella. Sin esta capa, la unica forma de crear una accion
 * es escribirla de memoria en el chat, y eso es exactamente lo que hacia que la
 * interfaz pareciera un bloc de notas al lado de la de ExecutableItems. Con
 * esto, el menu puede pintar UNA CASILLA POR ARGUMENTO, con su valor actual,
 * su tipo y su ayuda, y el jugador no tiene que recordar ni una clave.
 *
 * Esto es SOLO metadatos. Si un dia se añade una accion al motor y se olvida
 * declararla aqui, no se rompe nada: sigue funcionando escrita a mano y
 * `/gi info` avisa de que le falta la ficha.
 */
public final class Catalogo {

    private Catalogo() { }

    /* =============================================================== tipos */

    /** De que es un argumento. Decide como lo pide el menu. */
    public enum Clase {
        /** Texto libre por chat. */
        TEXTO,
        /** Un decimal. */
        NUMERO,
        /** Un tiempo: `40t`, `3s`, `2m`. */
        TICKS,
        /** Interruptor: se gira a clic, no se escribe. */
        BOOL,
        /** Una lista cerrada: se gira entre las opciones. */
        OPCIONES,
        /** Un material: chat, o el item que lleves en la mano. */
        MATERIAL,
        /** Igual pero solo bloques. */
        BLOQUE,
        /** Abre el catalogo de particulas. */
        PARTICULA,
        /** Abre el catalogo de sonidos, con escucha previa. */
        SONIDO,
        /** Un efecto de pocion, del registro del servidor. */
        POCION,
        /** Un tipo de criatura. */
        CRIATURA,
        /** Un tipo de proyectil de los que acepta PROYECTIL. */
        PROYECTIL,
        /** `#RRGGBB`. */
        COLOR,
        /** Un activador del enum. */
        ACTIVADOR,
        /** El id de otro GodItem. */
        GODITEM
    }

    /** Un argumento de una linea. */
    public record Param(String clave, String etiqueta, Clase clase, String pordefecto,
                        String ayuda, Material icono, List<String> opciones) {

        public static Param de(String clave, String etiqueta, Clase clase, String pordefecto,
                               String ayuda, String icono) {
            return new Param(clave, etiqueta, clase, pordefecto, ayuda, mat(icono), List.of());
        }

        public static Param opciones(String clave, String etiqueta, String pordefecto,
                                     String ayuda, String icono, String... valores) {
            return new Param(clave, etiqueta, Clase.OPCIONES, pordefecto, ayuda, mat(icono),
                    List.of(valores));
        }
    }

    /** Una accion del catalogo. */
    public record Accion(String nombre, String grupo, String descripcion, Material icono,
                         String selectorPorDefecto, String etiquetaTexto, String ayudaTexto,
                         List<Param> params) {

        /** Si lleva `@objetivo`. Null en las que no apuntan a nadie. */
        public boolean llevaSelector() {
            return this.selectorPorDefecto != null;
        }

        public boolean llevaTexto() {
            return this.etiquetaTexto != null;
        }

        public Param param(String clave) {
            for (Param p : this.params) {
                if (p.clave().equals(clave)) return p;
            }
            return null;
        }
    }

    /** Como se escribe una condicion. */
    public enum Forma {
        /** Sin valor: `AGACHADO`. */
        BANDERA,
        /** `VIDA menor 50%`: operador + valor. */
        COMPARACION,
        /** `MUNDO survival`: un valor suelto. */
        VALOR
    }

    public record Cond(String nombre, String grupo, String descripcion, Material icono,
                       Forma forma, boolean llevaSelector, String etiquetaValor,
                       String ayudaValor, Clase claseValor, List<Param> params) {

        public Param param(String clave) {
            for (Param p : this.params) {
                if (p.clave().equals(clave)) return p;
            }
            return null;
        }
    }

    /** Los operadores que entiende `Condiciones`, en el orden en que se giran. */
    public static final List<String> OPERADORES = List.of("igual", "distinto", "mayor", "menor",
            "mayor_igual", "menor_igual");

    /** Los objetivos que entiende `Objetivos`, en el orden en que se giran. */
    public static final List<String> SELECTORES = List.of(
            "@yo", "@golpeado", "@mirada", "@cercanos{r=6}", "@cercanos{r=8,enemigos}",
            "@cercanos{r=8,jugadores}", "@todos");

    /* ============================================================== grupos */

    public record Grupo(String nombre, String descripcion, Material icono) { }

    public static final List<Grupo> GRUPOS_ACCION = List.of(
            new Grupo("Vida y daño", "Pegar, curar, absorción, invulnerabilidad.", Material.RED_DYE),
            new Grupo("Movimiento", "Empujar, atraer, dash, saltar, teletransportar.", Material.FEATHER),
            new Grupo("Efectos", "Pociones, fuego, congelación, brillo.", Material.BREWING_STAND),
            new Grupo("Visual", "Partículas, anillos, rayos, explosiones.", Material.FIREWORK_STAR),
            new Grupo("Textos", "Chat, actionbar, título y bossbar.", Material.OAK_SIGN),
            new Grupo("Sonido", "Un sonido, uno global o una secuencia en capas.", Material.NOTE_BLOCK),
            new Grupo("Mundo", "Bloques temporales, invocar y proyectiles.", Material.GRASS_BLOCK),
            new Grupo("Juego", "Items, dinero, experiencia y comandos.", Material.CHEST),
            new Grupo("Flujo", "Variables, cortar la lista, enfriamientos.", Material.REPEATER));

    public static final List<Grupo> GRUPOS_CONDICION = List.of(
            new Grupo("Cuerpo", "Vida y comida del portador.", Material.GOLDEN_APPLE),
            new Grupo("Estado", "Agachado, corriendo, volando, ardiendo...", Material.LEATHER_BOOTS),
            new Grupo("Sitio", "Mundo, región, bioma, luz, altura, hora y clima.", Material.COMPASS),
            new Grupo("Inventario", "Llevar encima un item o un GodItem.", Material.CHEST),
            new Grupo("Datos", "Permisos, variables, placeholders y suerte.", Material.COMPARATOR),
            new Grupo("Conjuntos", "Piezas del set de MMOItems.", Material.IRON_CHESTPLATE));

    /* ============================================================ catalogo */

    private static final Map<String, Accion> ACCIONES = new LinkedHashMap<>();
    private static final Map<String, Cond> CONDICIONES = new LinkedHashMap<>();

    public static Accion accion(String nombre) {
        return nombre == null ? null
                : ACCIONES.get(nombre.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    public static Cond condicion(String nombre) {
        return nombre == null ? null
                : CONDICIONES.get(nombre.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    public static List<Accion> accionesDe(String grupo) {
        List<Accion> out = new ArrayList<>();
        for (Accion a : ACCIONES.values()) {
            if (a.grupo().equals(grupo)) out.add(a);
        }
        return out;
    }

    public static List<Cond> condicionesDe(String grupo) {
        List<Cond> out = new ArrayList<>();
        for (Cond c : CONDICIONES.values()) {
            if (c.grupo().equals(grupo)) out.add(c);
        }
        return out;
    }

    /** Busca por texto en el nombre y en la descripcion. */
    public static List<Accion> buscarAcciones(String q) {
        String v = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Accion> out = new ArrayList<>();
        for (Accion a : ACCIONES.values()) {
            if (a.nombre().toLowerCase(Locale.ROOT).contains(v)
                    || a.descripcion().toLowerCase(Locale.ROOT).contains(v)) {
                out.add(a);
            }
        }
        return out;
    }

    public static List<Cond> buscarCondiciones(String q) {
        String v = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Cond> out = new ArrayList<>();
        for (Cond c : CONDICIONES.values()) {
            if (c.nombre().toLowerCase(Locale.ROOT).contains(v)
                    || c.descripcion().toLowerCase(Locale.ROOT).contains(v)) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Las que el motor sabe ejecutar pero aqui no tienen ficha. Lo enseña
     * `/gi info`: es la señal de que alguien añadio una accion y se olvido de
     * declararla, no un fallo que rompa nada.
     */
    public static Set<String> sinFicha() {
        Set<String> out = new LinkedHashSet<>();
        for (String n : Acciones.nombres()) {
            if (!ACCIONES.containsKey(n)) out.add("accion " + n);
        }
        for (String n : Condiciones.nombres()) {
            if (!CONDICIONES.containsKey(n)) out.add("condicion " + n);
        }
        return out;
    }

    public static int cuantasAcciones() {
        return ACCIONES.size();
    }

    public static int cuantasCondiciones() {
        return CONDICIONES.size();
    }

    /* ============================================================= declarar */

    private static void acc(String nombre, String grupo, String desc, String icono,
                            String selector, String etiquetaTexto, String ayudaTexto,
                            Param... params) {
        ACCIONES.put(nombre, new Accion(nombre, grupo, desc, mat(icono), selector,
                etiquetaTexto, ayudaTexto, List.of(params)));
    }

    private static void con(String nombre, String grupo, String desc, String icono,
                            Forma forma, boolean selector, String etiquetaValor,
                            String ayudaValor, Clase claseValor, Param... params) {
        CONDICIONES.put(nombre, new Cond(nombre, grupo, desc, mat(icono), forma, selector,
                etiquetaValor, ayudaValor, claseValor, List.of(params)));
    }

    /**
     * Las claves que entienden TODAS las visuales: donde se pinta y como.
     *
     * Van en un metodo y no copiadas ocho veces porque son literalmente el mismo
     * `pintar()` del motor: si un dia se le añade una clave, se añade aqui y
     * aparece sola en las ocho pantallas.
     */
    private static List<Param> particula(boolean conTipo) {
        List<Param> l = new ArrayList<>();
        if (conTipo) {
            l.add(Param.de("tipo", "Partícula", Clase.PARTICULA, "FLAME",
                    "Cuál se pinta. Hay " + Particulas.cuantas() + " en esta versión.", "FIREWORK_STAR"));
        }
        l.add(Param.de("cantidad", "Cantidad", Clase.NUMERO, "1",
                "Cuántas por punto. Tope 500.", "GUNPOWDER"));
        l.add(Param.de("ancho", "Dispersión X", Clase.NUMERO, "0",
                "Cuánto se abren a los lados.", "PISTON"));
        l.add(Param.de("altura", "Dispersión Y", Clase.NUMERO, "",
                "En alto. Vacío = la misma que X.", "PISTON"));
        l.add(Param.de("fondo", "Dispersión Z", Clase.NUMERO, "",
                "En profundidad. Vacío = la misma que X.", "PISTON"));
        l.add(Param.de("velocidad", "Velocidad", Clase.NUMERO, "0",
                "El `extra` de Minecraft: en muchas es velocidad, en otras tamaño.", "SUGAR"));
        l.add(Param.de("color", "Color", Clase.COLOR, "#FFFFFF",
                "Para las de polvo, de efecto y de estela.", "RED_DYE"));
        l.add(Param.de("color2", "Color de destino", Clase.COLOR, "",
                "Solo DUST_COLOR_TRANSITION: a qué color vira.", "BLUE_DYE"));
        l.add(Param.de("tamano", "Tamaño del polvo", Clase.NUMERO, "1.2",
                "Solo las de polvo.", "SLIME_BALL"));
        l.add(Param.de("bloque", "Bloque", Clase.BLOQUE, "",
                "Para BLOCK, BLOCK_MARKER, FALLING_DUST, DUST_PILLAR...", "STONE"));
        l.add(Param.de("item", "Objeto", Clase.MATERIAL, "",
                "Solo ITEM: de qué objeto son los trozos.", "ITEM_FRAME"));
        l.add(Param.de("hacia", "Viaja hacia", Clase.TEXTO, "",
                "Solo VIBRATION y TRAIL. Un @objetivo.", "SPECTRAL_ARROW"));
        l.add(Param.de("duracion", "Duración del viaje", Clase.TICKS, "20",
                "Solo VIBRATION y TRAIL.", "CLOCK"));
        l.add(Param.de("retardo", "Retardo", Clase.NUMERO, "0",
                "Solo SHRIEK: ticks hasta que suena.", "CLOCK"));
        l.add(Param.de("valor", "Valor", Clase.NUMERO, "1",
                "Solo SCULK_CHARGE (el giro) y las que pidan un decimal suelto.", "PAPER"));
        l.add(Param.de("alto", "Alto sobre el punto", Clase.NUMERO, "0",
                "Sube o baja el sitio donde se pinta.", "LADDER"));
        l.add(Param.de("dx", "Desplazar X", Clase.NUMERO, "0", "Mueve el punto.", "COMPASS"));
        l.add(Param.de("dy", "Desplazar Y", Clase.NUMERO, "0", "Mueve el punto.", "COMPASS"));
        l.add(Param.de("dz", "Desplazar Z", Clase.NUMERO, "0", "Mueve el punto.", "COMPASS"));
        return l;
    }

    private static Param[] visual(Param... propios) {
        List<Param> l = new ArrayList<>(List.of(propios));
        l.addAll(particula(true));
        return l.toArray(new Param[0]);
    }

    static {
        /* ------------------------------------------------------ vida y daño */

        acc("DANO", "Vida y daño", "Pega el daño que le digas.", "IRON_SWORD",
                "@golpeado", null, null,
                Param.de("cantidad", "Daño", Clase.NUMERO, "1",
                        "En puntos de vida. 2 = un corazón.", "REDSTONE"));

        acc("DANO_SIN_EMPUJE", "Vida y daño", "Pega sin mover al que recibe.", "IRON_SWORD",
                "@golpeado", null, null,
                Param.de("cantidad", "Daño", Clase.NUMERO, "1", "En puntos de vida.", "REDSTONE"));

        acc("DANO_PORCENTAJE", "Vida y daño", "Pega un porcentaje de su vida.", "GOLDEN_SWORD",
                "@golpeado", null, null,
                Param.de("cantidad", "Porcentaje", Clase.NUMERO, "10",
                        "10 = el 10 %.", "REDSTONE"),
                Param.opciones("de", "Calculado sobre", "max",
                        "Vida máxima o la que le queda ahora.", "GOLDEN_APPLE", "max", "actual"));

        acc("CURAR", "Vida y daño", "Devuelve vida.", "GOLDEN_APPLE", "@yo", null, null,
                Param.de("cantidad", "Vida", Clase.NUMERO, "2", "2 = un corazón.", "RED_DYE"));

        acc("CURAR_PORCENTAJE", "Vida y daño", "Devuelve un porcentaje de la vida máxima.",
                "ENCHANTED_GOLDEN_APPLE", "@yo", null, null,
                Param.de("cantidad", "Porcentaje", Clase.NUMERO, "10", "10 = el 10 %.", "RED_DYE"));

        acc("ABSORCION", "Vida y daño", "Corazones amarillos de escudo.", "GOLDEN_CHESTPLATE",
                "@yo", null, null,
                Param.de("cantidad", "Absorción", Clase.NUMERO, "4", "En puntos.", "GOLD_INGOT"),
                Param.de("sumar", "Sumar a la que ya tenga", Clase.BOOL, "false",
                        "Apagado la reemplaza.", "LIME_DYE"));

        acc("INVULNERABLE", "Vida y daño", "No recibe daño durante un rato.", "NETHERITE_CHESTPLATE",
                "@yo", null, null,
                Param.de("duracion", "Duración", Clase.TICKS, "40", "`40t`, `2s`, `1m`.", "CLOCK"));

        acc("SET_VIDA", "Vida y daño", "Deja la vida en un valor exacto.", "REDSTONE_BLOCK",
                "@yo", null, null,
                Param.de("cantidad", "Vida", Clase.NUMERO, "20", "Nunca baja de 0.5.", "RED_DYE"));

        /* -------------------------------------------------------- movimiento */

        acc("EMPUJAR", "Movimiento", "Lanza lejos del que usa el item.", "PISTON",
                "@golpeado", null, null,
                Param.de("fuerza", "Fuerza", Clase.NUMERO, "1.0", "Horizontal.", "SUGAR"),
                Param.de("alto", "Componente vertical", Clase.NUMERO, "0.3",
                        "Cuánto lo levanta al empujar.", "LADDER"));

        acc("ATRAER", "Movimiento", "Lo trae hacia ti.", "STICKY_PISTON", "@golpeado", null, null,
                Param.de("fuerza", "Fuerza", Clase.NUMERO, "1.0", "Horizontal.", "SUGAR"),
                Param.de("alto", "Componente vertical", Clase.NUMERO, "0.3", "", "LADDER"));

        acc("DASH", "Movimiento", "Impulso hacia donde miras.", "FEATHER", "@yo", null, null,
                Param.de("fuerza", "Fuerza", Clase.NUMERO, "1.4", "", "SUGAR"),
                Param.de("alto", "Alto mínimo", Clase.NUMERO, "0.25",
                        "Para que no te clave contra el suelo.", "LADDER"));

        acc("SALTO", "Movimiento", "Salto vertical.", "RABBIT_FOOT", "@yo", null, null,
                Param.de("fuerza", "Fuerza", Clase.NUMERO, "1.0", "", "SUGAR"));

        acc("LEVANTAR", "Movimiento", "Lo sube en el sitio.", "ELYTRA", "@yo", null, null,
                Param.de("fuerza", "Fuerza", Clase.NUMERO, "1.2", "", "SUGAR"),
                Param.de("vuelo", "Permiso de vuelo prestado", Clase.TICKS, "80",
                        "Sin esto el servidor te expulsa por volar.", "PHANTOM_MEMBRANE"));

        acc("TELETRANSPORTE", "Movimiento", "Te lleva a un objetivo o a unas coordenadas.",
                "ENDER_PEARL", "@golpeado", null, null,
                Param.de("x", "X", Clase.NUMERO, "", "Deja las tres vacías para ir al objetivo.", "COMPASS"),
                Param.de("y", "Y", Clase.NUMERO, "", "", "COMPASS"),
                Param.de("z", "Z", Clase.NUMERO, "", "", "COMPASS"),
                Param.de("mundo", "Mundo", Clase.TEXTO, "", "Solo con coordenadas.", "GRASS_BLOCK"));

        acc("TP_MIRADA", "Movimiento", "Te lleva adonde estás mirando.", "ENDER_EYE",
                null, null, null,
                Param.de("alcance", "Alcance", Clase.NUMERO, "25", "En bloques.", "SPYGLASS"));

        acc("ANCLAR", "Movimiento", "Lo clava en el sitio (lentitud extrema).", "CHAIN",
                "@golpeado", null, null,
                Param.de("duracion", "Duración", Clase.TICKS, "40", "", "CLOCK"));

        /* ----------------------------------------------------------- efectos */

        acc("POCION", "Efectos", "Da un efecto de poción.", "POTION", "@yo", null, null,
                Param.de("tipo", "Efecto", Clase.POCION, "speed", "Del registro del servidor.", "BREWING_STAND"),
                Param.de("duracion", "Duración", Clase.TICKS, "100", "", "CLOCK"),
                Param.de("nivel", "Nivel", Clase.NUMERO, "1", "1 = I, 2 = II...", "EXPERIENCE_BOTTLE"));

        acc("QUITAR_POCION", "Efectos", "Le quita un efecto, o todos.", "MILK_BUCKET",
                "@yo", null, null,
                Param.de("tipo", "Efecto", Clase.POCION, "TODAS",
                        "`TODAS` los quita todos.", "BREWING_STAND"));

        acc("FUEGO", "Efectos", "Lo prende.", "FLINT_AND_STEEL", "@golpeado", null, null,
                Param.de("duracion", "Duración", Clase.TICKS, "60", "", "CLOCK"));

        acc("APAGAR", "Efectos", "Le apaga el fuego.", "WATER_BUCKET", "@yo", null, null);

        acc("CONGELAR", "Efectos", "Le sube la escarcha como en la nieve polvo.", "POWDER_SNOW_BUCKET",
                "@golpeado", null, null,
                Param.de("duracion", "Duración", Clase.TICKS, "140", "", "CLOCK"));

        acc("BRILLO", "Efectos", "Contorno luminoso a través de las paredes.", "GLOW_INK_SAC",
                "@yo", null, null,
                Param.de("duracion", "Duración", Clase.TICKS, "100", "", "CLOCK"));

        /* ------------------------------------------------------------ visual */

        acc("PARTICULA", "Visual", "Pinta partículas en un punto.", "FIREWORK_STAR",
                "@yo", null, null, visual());

        acc("ANILLO", "Visual", "Un anillo de partículas alrededor del punto.", "END_CRYSTAL",
                "@yo", null, null, visual(
                Param.de("radio", "Radio", Clase.NUMERO, "3", "En bloques.", "SPYGLASS"),
                Param.de("puntos", "Puntos", Clase.NUMERO, "",
                        "Vacío = los que hagan falta para el radio.", "GUNPOWDER")));

        acc("ESFERA", "Visual", "Una esfera de partículas.", "HEART_OF_THE_SEA",
                "@yo", null, null, visual(
                Param.de("radio", "Radio", Clase.NUMERO, "3", "", "SPYGLASS"),
                Param.de("puntos", "Puntos", Clase.NUMERO, "80", "", "GUNPOWDER")));

        acc("LINEA", "Visual", "Un haz desde ti hasta el objetivo.", "SPECTRAL_ARROW",
                "@golpeado", null, null, visual(
                Param.opciones("desde", "Sale de", "ojos",
                        "De los ojos o de los pies.", "ENDER_EYE", "ojos", "pies"),
                Param.de("paso", "Separación", Clase.NUMERO, "0.4",
                        "Bloques entre punto y punto.", "STRING")));

        acc("HELICE", "Visual", "Una espiral que sube.", "TWISTING_VINES",
                "@yo", null, null, visual(
                Param.de("radio", "Radio", Clase.NUMERO, "1.2", "", "SPYGLASS"),
                Param.de("alto", "Alto", Clase.NUMERO, "3", "", "LADDER"),
                Param.de("puntos", "Puntos", Clase.NUMERO, "60", "", "GUNPOWDER"),
                Param.de("vueltas", "Vueltas", Clase.NUMERO, "3", "", "COMPASS")));

        acc("RAYO_BEACON", "Visual", "La columna de luz que sale del subsuelo.", "BEACON",
                "@yo", null, null,
                Param.de("funda", "Bloque de la funda", Clase.BLOQUE, "YELLOW_STAINED_GLASS",
                        "El haz de fuera.", "YELLOW_STAINED_GLASS"),
                Param.de("nucleo", "Bloque del núcleo", Clase.BLOQUE, "OCHRE_FROGLIGHT",
                        "El haz de dentro.", "OCHRE_FROGLIGHT"),
                Param.de("ancho", "Ancho", Clase.NUMERO, "0.6", "", "SPYGLASS"),
                Param.de("duracion", "Duración", Clase.TICKS, "60", "", "CLOCK"));

        acc("ESPADA_CAIDA", "Visual", "Espadas que caen del cielo y se clavan.", "NETHERITE_SWORD",
                "@yo", null, null,
                Param.de("cantidad", "Cuántas", Clase.NUMERO, "12", "Tope 48.", "GUNPOWDER"),
                Param.de("radio", "Radio", Clase.NUMERO, "5", "", "SPYGLASS"),
                Param.de("material", "Espada", Clase.MATERIAL, "NETHERITE_SWORD", "", "IRON_SWORD"),
                Param.de("dano", "Daño al clavarse", Clase.NUMERO, "0", "0 = solo decorativas.", "REDSTONE"),
                Param.de("clavadas", "Tiempo clavadas", Clase.TICKS, "60", "", "CLOCK"));

        acc("CIELO", "Visual", "Cambia la hora que ve el cliente, no la del mundo.", "CLOCK",
                "@todos", null, null,
                Param.de("hora", "Hora", Clase.NUMERO, "18000",
                        "18000 = medianoche. 6000 = mediodía.", "CLOCK"),
                Param.de("duracion", "Duración", Clase.TICKS, "100",
                        "Siempre vuelve sola.", "CLOCK"));

        acc("RELAMPAGO", "Visual", "Un rayo, con o sin daño.", "LIGHTNING_ROD",
                "@golpeado", null, null,
                Param.de("dano", "Que haga daño", Clase.BOOL, "false",
                        "Apagado es solo el efecto.", "LIME_DYE"));

        acc("EXPLOSION", "Visual", "Explosión que NO rompe bloques.", "TNT", "@yo", null, null,
                Param.de("radio", "Radio", Clase.NUMERO, "5", "", "SPYGLASS"),
                Param.de("anillos", "Anillos de humo", Clase.NUMERO, "3", "", "GUNPOWDER"),
                Param.de("dano", "Daño en el centro", Clase.NUMERO, "0",
                        "Baja con la distancia.", "REDSTONE"),
                Param.de("empuje", "Empujar", Clase.BOOL, "true", "", "LIME_DYE"));

        /* ------------------------------------------------------------ textos */

        acc("MENSAJE", "Textos", "Una línea en el chat.", "PAPER", "@yo",
                "Mensaje", "Acepta códigos & y %placeholders%.");

        acc("ACTIONBAR", "Textos", "Texto sobre la barra de inventario.", "NAME_TAG", "@yo",
                "Texto", "Acepta códigos & y %placeholders%.");

        acc("TITULO", "Textos", "Título grande en pantalla.", "OAK_SIGN", "@yo",
                "Título|Subtítulo", "La barra vertical separa las dos líneas.",
                Param.de("entrada", "Entrada", Clase.TICKS, "10", "", "CLOCK"),
                Param.de("duracion", "Duración", Clase.TICKS, "40", "", "CLOCK"),
                Param.de("salida", "Salida", Clase.TICKS, "10", "", "CLOCK"));

        acc("BOSSBAR", "Textos", "Barra de jefe arriba de la pantalla.", "DRAGON_EGG", "@yo",
                "Texto de la barra", "Acepta códigos &.",
                Param.opciones("color", "Color", "YELLOW", "", "YELLOW_DYE",
                        "PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE"),
                Param.de("progreso", "Relleno", Clase.NUMERO, "1.0", "De 0 a 1.", "PAPER"),
                Param.de("duracion", "Duración", Clase.TICKS, "60", "", "CLOCK"));

        /* ------------------------------------------------------------ sonido */

        acc("SONIDO", "Sonido", "Un sonido, solo para quien lo recibe.", "NOTE_BLOCK",
                "@yo", null, null,
                Param.de("sonido", "Sonido", Clase.SONIDO, "entity.experience_orb.pickup",
                        "Del registro del servidor.", "JUKEBOX"),
                Param.de("volumen", "Volumen", Clase.NUMERO, "1.0",
                        "Por encima de 1 solo aumenta el alcance.", "BELL"),
                Param.de("tono", "Tono", Clase.NUMERO, "1.0", "De 0.5 a 2.0.", "NOTE_BLOCK"));

        acc("SONIDO_GLOBAL", "Sonido", "Un sonido que oyen todos los de alrededor.", "JUKEBOX",
                "@yo", null, null,
                Param.de("sonido", "Sonido", Clase.SONIDO, "entity.warden.roar", "", "JUKEBOX"),
                Param.de("volumen", "Volumen", Clase.NUMERO, "1.0", "", "BELL"),
                Param.de("tono", "Tono", Clase.NUMERO, "1.0", "", "NOTE_BLOCK"),
                Param.de("alto", "Alto sobre el punto", Clase.NUMERO, "0", "", "LADDER"));

        acc("SECUENCIA", "Sonido", "Varios sonidos en capas: así se fabrica uno propio sin pack.",
                "MUSIC_DISC_PIGSTEP", "@yo",
                "Lista de sonidos",
                "clave|volumen|tono|retardo, separados por coma. "
                        + "Ej: entity.warden.roar|1.4|0.5|0, block.bell.use|1|0.4|6");

        /* ------------------------------------------------------------- mundo */

        acc("BLOQUE_TEMPORAL", "Mundo", "Pone bloques que se quitan solos. Nunca pisa lo que ya hay.",
                "GLASS", "@yo", null, null,
                Param.de("material", "Bloque", Clase.BLOQUE, "GLASS", "", "GLASS"),
                Param.de("duracion", "Duración", Clase.TICKS, "60", "", "CLOCK"),
                Param.de("radio", "Radio", Clase.NUMERO, "0", "0 = un solo bloque. Tope 6.", "SPYGLASS"),
                Param.de("alto", "Alto sobre el punto", Clase.NUMERO, "0", "", "LADDER"));

        acc("INVOCAR", "Mundo", "Saca criaturas.", "ZOMBIE_HEAD", "@yo", null, null,
                Param.de("tipo", "Criatura", Clase.CRIATURA, "ZOMBIE", "", "ZOMBIE_SPAWN_EGG"),
                Param.de("cantidad", "Cuántas", Clase.NUMERO, "1", "Tope 20.", "GUNPOWDER"),
                Param.de("radio", "Dispersión", Clase.NUMERO, "2", "", "SPYGLASS"),
                Param.de("vida", "Vida", Clase.NUMERO, "-1", "-1 = la suya.", "RED_DYE"),
                Param.de("duracion", "Que dure", Clase.TICKS, "0", "0 = para siempre.", "CLOCK"),
                Param.de("nombre", "Nombre encima", Clase.TEXTO, "", "Acepta códigos &.", "NAME_TAG"));

        acc("PROYECTIL", "Mundo", "Dispara un proyectil marcado con este item.", "ARROW",
                null, null, null,
                Param.opciones("tipo", "Proyectil", "ARROW", "", "ARROW",
                        "ARROW", "SNOWBALL", "EGG", "ENDER_PEARL", "FIREBALL", "SMALL_FIREBALL",
                        "DRAGON_FIREBALL", "WITHER_SKULL", "SHULKER_BULLET", "TRIDENT",
                        "LLAMA_SPIT", "SPLASH_POTION"),
                Param.de("velocidad", "Velocidad", Clase.NUMERO, "1.6", "", "SUGAR"),
                Param.de("fuego", "Que arda", Clase.BOOL, "false", "", "LIME_DYE"));

        /* ------------------------------------------------------------- juego */

        acc("DAR_ITEM", "Juego", "Le da un item normal.", "CHEST", "@yo", null, null,
                Param.de("material", "Item", Clase.MATERIAL, "STONE", "", "STONE"),
                Param.de("cantidad", "Cuántos", Clase.NUMERO, "1", "", "GUNPOWDER"),
                Param.de("nombre", "Nombre", Clase.TEXTO, "", "Acepta códigos &.", "NAME_TAG"));

        acc("DAR_GODITEM", "Juego", "Le da otro GodItem.", "NETHER_STAR", "@yo", null, null,
                Param.de("id", "GodItem", Clase.GODITEM, "", "", "NETHER_STAR"),
                Param.de("cantidad", "Cuántos", Clase.NUMERO, "1", "", "GUNPOWDER"));

        acc("QUITAR_ITEM", "Juego", "Le quita items del inventario.", "HOPPER", "@yo", null, null,
                Param.de("material", "Item", Clase.MATERIAL, "", "Deja vacío si quitas un GodItem.", "STONE"),
                Param.de("goditem", "GodItem", Clase.GODITEM, "", "", "NETHER_STAR"),
                Param.de("cantidad", "Cuántos", Clase.NUMERO, "1", "", "GUNPOWDER"));

        acc("DURABILIDAD", "Juego", "Gasta o repara el propio item. Solo en NATIVOS.", "ANVIL",
                null, null, null,
                Param.de("cantidad", "Cambio", Clase.NUMERO, "-1",
                        "Negativo gasta, positivo repara.", "REDSTONE"),
                Param.de("romper", "Romperlo al llegar a 0", Clase.BOOL, "true", "", "LIME_DYE"));

        acc("DINERO", "Juego", "Suma o resta dinero de Vault.", "GOLD_INGOT", "@yo", null, null,
                Param.de("cantidad", "Cantidad", Clase.NUMERO, "0",
                        "Negativo cobra.", "GOLD_NUGGET"));

        acc("EXP", "Juego", "Da experiencia o niveles.", "EXPERIENCE_BOTTLE", "@yo", null, null,
                Param.de("cantidad", "Cantidad", Clase.NUMERO, "0", "", "EXPERIENCE_BOTTLE"),
                Param.de("niveles", "Contar en niveles", Clase.BOOL, "false",
                        "Apagado son puntos sueltos.", "LIME_DYE"));

        acc("COMANDO_CONSOLA", "Juego", "Ejecuta un comando como la consola.", "COMMAND_BLOCK",
                null, "Comando", "Sin la barra. Acepta %placeholders%.");

        acc("COMANDO_JUGADOR", "Juego", "Ejecuta un comando como el jugador.", "REPEATING_COMMAND_BLOCK",
                "@yo", "Comando", "Sin la barra. Acepta %placeholders%.");

        /* ------------------------------------------------------------- flujo */

        acc("VARIABLE", "Flujo", "Guarda un dato en el item o en el jugador.", "WRITABLE_BOOK",
                null, "nombre operación valor",
                "Operaciones: poner, sumar, restar, multiplicar, borrar. Ej: `cargas sumar 1`",
                Param.opciones("ambito", "Guardar en", "item",
                        "En el item (viaja con él) o en el jugador.", "ENDER_CHEST",
                        "item", "jugador"));

        acc("CANCELAR_EVENTO", "Flujo", "Anula el evento que disparó esto.", "BARRIER",
                null, null, null);

        acc("PARAR", "Flujo", "Corta la lista aquí: lo de abajo no se ejecuta.", "REDSTONE_TORCH",
                null, null, null);

        acc("COOLDOWN_DE", "Flujo", "Pone o quita el enfriamiento de un activador.", "CLOCK",
                "@yo", null, null,
                Param.de("activador", "Activador", Clase.ACTIVADOR, "",
                        "Vacío = el que está corriendo.", "LEVER"),
                Param.de("item", "De qué GodItem", Clase.GODITEM, "",
                        "Vacío = este mismo.", "NETHER_STAR"),
                Param.de("tiempo", "Tiempo", Clase.TICKS, "0", "0 lo quita.", "CLOCK"),
                Param.de("visible", "Cuenta atrás visible", Clase.BOOL, "true",
                        "En la actionbar.", "LIME_DYE"));

        acc("REPONER_USOS", "Flujo", "Devuelve los usos gastados.", "EXPERIENCE_BOTTLE",
                null, null, null);

        /* ========================================================= condiciones */

        con("VIDA", "Cuerpo", "La vida del portador (o del objetivo).", "RED_DYE",
                Forma.COMPARACION, true, "Vida",
                "Un número o un porcentaje: `50%`.", Clase.TEXTO);

        con("COMIDA", "Cuerpo", "El hambre del portador.", "COOKED_BEEF",
                Forma.COMPARACION, false, "Comida", "De 0 a 20.", Clase.NUMERO);

        con("AGACHADO", "Estado", "Va agachado.", "LEATHER_BOOTS", Forma.BANDERA, false, null, null, null);
        con("CORRIENDO", "Estado", "Va corriendo.", "FEATHER", Forma.BANDERA, false, null, null, null);
        con("VOLANDO", "Estado", "Está volando.", "ELYTRA", Forma.BANDERA, false, null, null, null);
        con("PLANEANDO", "Estado", "Está planeando con élitros.", "PHANTOM_MEMBRANE",
                Forma.BANDERA, false, null, null, null);
        con("EN_EL_AIRE", "Estado", "No toca el suelo y no vuela.", "FEATHER",
                Forma.BANDERA, false, null, null, null);
        con("ARDIENDO", "Estado", "Está ardiendo.", "FLINT_AND_STEEL",
                Forma.BANDERA, true, null, null, null);
        con("MONTADO", "Estado", "Va montado en algo.", "SADDLE", Forma.BANDERA, false, null, null, null);

        con("EFECTO", "Estado", "Tiene un efecto de poción activo.", "POTION",
                Forma.VALOR, true, "Efecto", "Del registro del servidor.", Clase.POCION);

        con("GAMEMODE", "Estado", "Está en ese modo de juego.", "GRASS_BLOCK",
                Forma.VALOR, false, "Modo", "SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR.", Clase.TEXTO);

        con("MANO", "Estado", "En qué mano lleva el item.", "SHIELD",
                Forma.VALOR, false, "Mano", "`principal` o `secundaria`.", Clase.TEXTO);

        con("MUNDO", "Sitio", "Está en uno de esos mundos.", "GRASS_BLOCK",
                Forma.VALOR, false, "Mundos", "Separados por espacios o comas.", Clase.TEXTO);

        con("REGION", "Sitio", "Está dentro de una región de WorldGuard.", "STONE_BRICKS",
                Forma.VALOR, false, "Región", "El id de la región.", Clase.TEXTO);

        con("BIOMA", "Sitio", "Está en uno de esos biomas.", "OAK_SAPLING",
                Forma.VALOR, false, "Biomas", "En minúsculas: `desert`, `plains`...", Clase.TEXTO);

        con("LUZ", "Sitio", "El nivel de luz del bloque.", "TORCH",
                Forma.COMPARACION, false, "Luz", "De 0 a 15.", Clase.NUMERO);

        con("ALTURA", "Sitio", "La altura Y.", "LADDER",
                Forma.COMPARACION, false, "Altura", "La Y del bloque.", Clase.NUMERO);

        con("HORA", "Sitio", "La hora del mundo.", "CLOCK",
                Forma.COMPARACION, false, "Hora",
                "`dia`, `noche` o un número de 0 a 24000.", Clase.TEXTO);

        con("LLUVIA", "Sitio", "Está lloviendo.", "WATER_BUCKET",
                Forma.VALOR, false, "Clima", "Vacío = lluvia. `tormenta` = tormenta.", Clase.TEXTO);

        con("TIENE_ITEM", "Inventario", "Lleva encima ese item.", "CHEST",
                Forma.VALOR, false, "Item", "Un material.", Clase.MATERIAL,
                Param.de("cantidad", "Cuántos", Clase.NUMERO, "1", "", "GUNPOWDER"));

        con("TIENE_GODITEM", "Inventario", "Lleva encima ese GodItem.", "NETHER_STAR",
                Forma.VALOR, false, "GodItem", "El id.", Clase.GODITEM,
                Param.de("cantidad", "Cuántos", Clase.NUMERO, "1", "", "GUNPOWDER"));

        con("PERMISO", "Datos", "Tiene ese permiso.", "NAME_TAG",
                Forma.VALOR, false, "Permiso", "Ej: `ederus.vip`.", Clase.TEXTO);

        con("VARIABLE", "Datos", "Compara una variable del item o del jugador.", "WRITABLE_BOOK",
                Forma.COMPARACION, false, "nombre y valor",
                "Ej: `cargas mayor 0`.", Clase.TEXTO,
                Param.opciones("ambito", "Guardada en", "item", "", "ENDER_CHEST",
                        "item", "jugador"));

        con("PLACEHOLDER", "Datos", "Compara un placeholder de PAPI.", "COMPARATOR",
                Forma.COMPARACION, false, "placeholder y valor",
                "Ej: `%player_level% mayor 30`.", Clase.TEXTO);

        con("PROBABILIDAD", "Datos", "Sale por suerte.", "GOLD_NUGGET",
                Forma.VALOR, false, "Porcentaje", "0 a 100.", Clase.NUMERO);

        con("PIEZAS_DEL_SET", "Conjuntos", "Cuántas piezas del set lleva puestas.", "IRON_CHESTPLATE",
                Forma.COMPARACION, false, "Piezas", "Un número.", Clase.NUMERO,
                Param.de("set", "Set", Clase.TEXTO, "",
                        "Vacío = el del propio item.", "IRON_CHESTPLATE"));

        con("SET", "Conjuntos", "El item pertenece a ese set de MMOItems.", "DIAMOND_CHESTPLATE",
                Forma.VALOR, false, "Sets", "Separados por espacios. Vacío = cualquiera.", Clase.TEXTO);
    }

    /* ============================================================ activadores */

    /** Que hace saltar cada activador, para el selector. */
    public record FichaActivador(Activador activador, String grupo, String descripcion, Material icono) { }

    private static final Map<Activador, FichaActivador> ACTIVADORES = new LinkedHashMap<>();

    public static final List<String> GRUPOS_ACTIVADOR = List.of(
            "Gestos", "Combate", "Llevarlo encima", "Conjuntos", "Cada X", "Inventario", "Mundo", "Otros");

    static {
        act(Activador.CLIC_DERECHO, "Gestos", "Clic derecho con el item en la mano.", "STICK");
        act(Activador.CLIC_IZQUIERDO, "Gestos", "Clic izquierdo al aire o a un bloque.", "STICK");
        act(Activador.CLIC, "Gestos", "Cualquiera de los dos clics.", "BLAZE_ROD");

        act(Activador.GOLPEAR, "Combate", "Golpear a cualquier criatura.", "IRON_SWORD");
        act(Activador.GOLPEAR_JUGADOR, "Combate", "Golpear a un jugador.", "DIAMOND_SWORD");
        act(Activador.RECIBIR_GOLPE, "Combate", "Que te peguen llevándolo.", "SHIELD");
        act(Activador.MATAR, "Combate", "Matar a una criatura.", "BONE");
        act(Activador.MATAR_JUGADOR, "Combate", "Matar a un jugador.", "PLAYER_HEAD");
        act(Activador.ANTES_DE_MORIR, "Combate",
                "Justo antes de morir. Puede CANCELAR_EVENTO y salvarte.", "TOTEM_OF_UNDYING");
        act(Activador.MORIR, "Combate", "Al morir el portador.", "SKELETON_SKULL");

        act(Activador.EQUIPAR, "Llevarlo encima", "Al ponértelo (armadura, anillo, colgante).",
                "IRON_CHESTPLATE");
        act(Activador.DESEQUIPAR, "Llevarlo encima", "Al quitártelo.", "LEATHER_CHESTPLATE");
        act(Activador.EMPUNAR, "Llevarlo encima", "Al pasarlo a la mano principal.", "IRON_SWORD");
        act(Activador.GUARDAR, "Llevarlo encima", "Al sacarlo de la mano principal.", "BUNDLE");

        act(Activador.SET_COMPLETO, "Conjuntos", "Al completar el conjunto de MMOItems.",
                "DIAMOND_CHESTPLATE");
        act(Activador.SET_ROTO, "Conjuntos", "Al quitarse una pieza y romperlo.", "CRACKED_STONE_BRICKS");

        act(Activador.EN_MANO, "Cada X", "Cada X mientras lo lleves en la mano.", "CLOCK");
        act(Activador.PUESTO, "Cada X", "Cada X mientras lo lleves puesto.", "CLOCK");
        act(Activador.EN_INVENTARIO, "Cada X", "Cada X mientras esté en el inventario.", "CLOCK");

        act(Activador.CONSUMIR, "Inventario", "Al comérselo o bebérselo.", "COOKED_BEEF");
        act(Activador.TIRAR, "Inventario", "Al soltarlo al suelo.", "DROPPER");
        act(Activador.RECOGER, "Inventario", "Al recogerlo del suelo.", "HOPPER");

        act(Activador.ROMPER_BLOQUE, "Mundo", "Al romper un bloque con él.", "IRON_PICKAXE");
        act(Activador.COLOCAR_BLOQUE, "Mundo", "Al colocar un bloque con él.", "BRICKS");
        act(Activador.PROYECTIL_IMPACTA, "Mundo", "Cuando su proyectil impacta.", "ARROW");
        act(Activador.REAPARECER, "Mundo", "Al reaparecer tras morir.", "RESPAWN_ANCHOR");

        act(Activador.DISPARADOR, "Otros",
                "Solo por `/gi trigger`: lo llaman ConditionalEvents, DeluxeMenus o misiones.",
                "LEVER");
    }

    private static void act(Activador a, String grupo, String desc, String icono) {
        ACTIVADORES.put(a, new FichaActivador(a, grupo, desc, mat(icono)));
    }

    /**
     * Las dos condiciones que llevan SUJETO delante del operador
     * (`VARIABLE cargas mayor 0`, `PLACEHOLDER %player_level% mayor 30`). Las
     * demas comparan algo que ya se sabe cual es y no lo escriben.
     */
    public static boolean llevaSujeto(Cond c) {
        return c != null && (c.nombre().equals("VARIABLE") || c.nombre().equals("PLACEHOLDER"));
    }

    public static FichaActivador ficha(Activador a) {
        FichaActivador f = ACTIVADORES.get(a);
        return f == null
                ? new FichaActivador(a, "Otros", "Sin descripción.", Material.LEVER)
                : f;
    }

    public static List<FichaActivador> activadoresDe(String grupo) {
        List<FichaActivador> out = new ArrayList<>();
        for (FichaActivador f : ACTIVADORES.values()) {
            if (f.grupo().equals(grupo)) out.add(f);
        }
        return out;
    }

    /* ============================================================== ayudas */

    private static Material mat(String s) {
        if (s == null) return Material.PAPER;
        Material m = Material.matchMaterial(s.toUpperCase(Locale.ROOT));
        return m == null ? Material.PAPER : m;
    }
}
