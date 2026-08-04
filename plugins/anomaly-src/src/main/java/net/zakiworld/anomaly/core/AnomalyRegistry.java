package net.zakiworld.anomaly.core;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.zakiworld.anomaly.AnomalyPlugin;
import net.zakiworld.anomaly.boss.Ability;
import net.zakiworld.anomaly.boss.BossFight;
import net.zakiworld.anomaly.boss.AbyssalChoir;
import net.zakiworld.anomaly.boss.KillerBunny;
import net.zakiworld.anomaly.boss.SaltLeviathan;
import net.zakiworld.anomaly.boss.ScreamingGoat;
import net.zakiworld.anomaly.boss.StormRider;
import net.zakiworld.anomaly.boss.SepulchralKnight;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * El catalogo de anomalias. De momento hay una, el Caballero Sepulcral, pero todo
 * lo demas (menu, anuncio, botin, mando) trabaja contra esta lista, asi que meter la
 * siguiente es escribir su AnomalyType y una linea en el constructor.
 */
public final class AnomalyRegistry {

    private final AnomalyPlugin plugin;
    private final Map<String, AnomalyType> types = new LinkedHashMap<>();
    private final Random random = new Random();

    public AnomalyRegistry(AnomalyPlugin plugin) {
        this.plugin = plugin;
        register(new KnightType());
        register(new GoatType());
        register(new BunnyType());
        register(new RiderType());
        register(new LeviathanType());
        register(new ChoirType());
    }

    public void register(AnomalyType type) {
        types.put(type.id(), type);
    }

    public AnomalyType get(String id) {
        return types.get(id);
    }

    public AnomalyType knight() {
        return types.get(SepulchralKnight.ID);
    }

    public AnomalyType goat() {
        return types.get(ScreamingGoat.ID);
    }

    public AnomalyType bunny() {
        return types.get(KillerBunny.ID);
    }

    public AnomalyType rider() {
        return types.get(StormRider.ID);
    }

    public AnomalyType leviathan() {
        return types.get(SaltLeviathan.ID);
    }

    public AnomalyType choir() {
        return types.get(AbyssalChoir.ID);
    }

    public List<AnomalyType> all() {
        return new ArrayList<>(types.values());
    }

    public List<AnomalyType> enabled() {
        List<AnomalyType> out = new ArrayList<>();
        for (AnomalyType t : types.values()) {
            if (isEnabled(t)) out.add(t);
        }
        return out;
    }

    public AnomalyType randomEnabled() {
        List<AnomalyType> pool = enabled();
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }

    // ------------------------------------------------------- ajustes por anomalia

    public boolean isEnabled(AnomalyType type) {
        return plugin.getConfig().getBoolean("anomalias." + type.id() + ".activa", true);
    }

    public void setEnabled(AnomalyType type, boolean value) {
        plugin.settings().set("anomalias." + type.id() + ".activa", value);
    }

    /** Vida base configurada; si no hay override, la que trae la anomalia. */
    public double health(AnomalyType type) {
        return plugin.getConfig().getDouble("anomalias." + type.id() + ".vida", type.baseHealth());
    }

    public void setHealth(AnomalyType type, double value) {
        plugin.settings().set("anomalias." + type.id() + ".vida", Math.round(Fx.clamp(value, 100, 20000)));
    }

    /**
     * Multiplicador del dano de TODAS las habilidades de esta anomalia. 1.0 es el
     * valor de diseno; subirlo o bajarlo mueve el jefe entero de golpe, sin tener que
     * tocar habilidad por habilidad.
     *
     * No afecta al golpe cuerpo a cuerpo normal, que es un atributo de la entidad.
     */
    public double damageMultiplier(AnomalyType type) {
        return Fx.clamp(plugin.getConfig().getDouble("anomalias." + type.id() + ".dano", 1.0), 0.1, 5.0);
    }

    public void setDamageMultiplier(AnomalyType type, double value) {
        plugin.settings().set("anomalias." + type.id() + ".dano",
                Math.round(Fx.clamp(value, 0.1, 5.0) * 10.0) / 10.0);
    }

    /**
     * Vida final segun cuanta gente hay cerca. Con el valor por defecto (0.15) cada
     * jugador extra suma un 15%, asi que un grupo de cinco pelea contra un jefe con
     * un 60% mas de vida en vez de fundirlo en diez segundos.
     */
    public double scaledHealth(AnomalyType type, int players) {
        double base = health(type);
        double perPlayer = plugin.settings().healthPerPlayer();
        double scaled = base * (1 + perPlayer * Math.max(0, players - 1));
        return Fx.clamp(scaled, 100, 100000);
    }

    // ------------------------------------------------------- el Caballero Sepulcral

    /** Ficha del Caballero Sepulcral. */
    public final class KnightType implements AnomalyType {

        @Override
        public String id() {
            return SepulchralKnight.ID;
        }

        @Override
        public String display() {
            return plugin.getConfig().getString("anomalias." + id() + ".nombre", "Caballero Sepulcral");
        }

        @Override
        public TextColor color() {
            return SepulchralKnight.ACCENT;
        }

        @Override
        public NamedTextColor glowColor() {
            return NamedTextColor.RED;
        }

        @Override
        public Element element() {
            return Element.TIERRA;
        }

        @Override
        public Material icon() {
            Material m = Material.matchMaterial("NETHERITE_SPEAR");
            return m != null ? m : Material.NETHERITE_HELMET;
        }

        @Override
        public String tagline() {
            return "Jinete de hueso con lanza de netherita";
        }

        @Override
        public List<String> origin() {
            return List.of(
                    "Cabalgaba el Paramo de Batalla, en el Aether,",
                    "jurado a una corona que ya no existe.",
                    "Cuando la grieta se abrio siguio cabalgando",
                    "hacia este lado, y aqui sigue buscando",
                    "un juramento al que servir.");
        }

        @Override
        public List<String> threat() {
            return List.of(
                    "Elemento de tierra: suelo firme y seco",
                    "Tres fases: montado, a pie y heraldo",
                    "18 habilidades de fuerza bruta, todas con aviso",
                    "Se cura nunca; se enfurece si le fallan");
        }

        @Override
        public double baseHealth() {
            return 1400;
        }

        @Override
        public int arenaRadius() {
            return 24;
        }

        @Override
        public List<Ability> abilities() {
            return knightAbilities();
        }

        @Override
        public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
            return new SepulchralKnight(plugin, event, where);
        }
    }

    /**
     * Las 18 habilidades del Caballero, con su fase, enfriamiento, duracion y peso.
     * El enfriamiento nunca baja de la duracion de la animacion: es la regla que evita
     * que se solapen dos animaciones sobre el mismo jefe.
     */
    public List<Ability> knightAbilities() {
        List<Ability> list = new ArrayList<>();

        // --- Fase I: montado
        add(list, "carga_lanza", "Carga de Lanza", 1, 200, 70, 4,
                "Marca un pasillo y galopa por el arrollando a quien no se aparte.",
                icon("NETHERITE_SPEAR", "TRIDENT"), f -> knight(f).lanceCharge());
        add(list, "barrido", "Barrido de Guadana", 1, 150, 40, 5,
                "Tres barridos concentricos de la lanza; solo pega el borde de cada onda.",
                icon("NETHERITE_SPEAR", "TRIDENT"), f -> knight(f).scytheSweep());
        add(list, "pisoton", "Pisoton de la Montura", 1, 220, 70, 4,
                "El caballo se encabrita y descarga los cascos; la onda barre nueve bloques.",
                icon("IRON_HORSE_ARMOR", "SADDLE"), f -> knight(f).hoofSlam());
        add(list, "estandarte", "Estandarte de Guerra", 1, 520, 300, 2,
                "Planta un estandarte que le da resistencia; hay que derribarlo a golpes.",
                icon("BLACK_BANNER", "WHITE_BANNER"), f -> knight(f).warBanner());
        add(list, "relincho", "Relincho Aterrador", 1, 300, 50, 3,
                "Un cono de miedo que ciega, marea y frena a quien mire de frente.",
                icon("BONE"), f -> knight(f).terrifyingNeigh());
        add(list, "llamada_jinetes", "Llamada de Jinetes", 1, 620, 70, 2,
                "Salen del suelo entre dos y cuatro jinetes menores con arco.",
                icon("SADDLE"), f -> knight(f).ridersCall());

        // --- Fase II: a pie
        add(list, "estocada_fantasma", "Estocada Fantasma", 2, 170, 45, 5,
                "Se teletransporta a la espalda del mas lejano y suelta una estocada.",
                icon("ENDER_PEARL"), f -> knight(f).phantomThrust());
        add(list, "tajo_descendente", "Tajo Descendente", 2, 240, 80, 4,
                "Parte el suelo en linea recta con un tajo de arriba abajo.",
                icon("CRACKED_DEEPSLATE_BRICKS", "DEEPSLATE"), f -> knight(f).overheadCleave());
        add(list, "cadena_hueso", "Cadena de Hueso", 2, 210, 55, 3,
                "Engancha al que mas se aleja y lo arrastra de vuelta al centro.",
                icon("CHAIN"), f -> knight(f).boneChain());
        add(list, "juramento_roto", "Juramento Roto", 2, 400, 140, 3,
                "Marca a alguien: si se aleja mas de 16 bloques en 7 s, recibe un golpe brutal.",
                icon("WRITABLE_BOOK", "BOOK"), f -> knight(f).brokenOath());
        add(list, "circulo_osario", "Circulo de Osario", 2, 480, 120, 2,
                "Un cerco de hueso que se cierra de 18 a 7 bloques; fuera se pierde vida.",
                icon("BONE_BLOCK"), f -> knight(f).ossuaryCircle());
        add(list, "guardia_netherita", "Guardia de Netherita", 2, 540, 160, 2,
                "Se cubre con 160 puntos de escudo que hay que romper a golpes.",
                icon("NETHERITE_INGOT"), f -> knight(f).netheriteWard());

        // --- Fase III: heraldo
        add(list, "sismo", "Sismo del Paramo", 3, 360, 200, 3,
                "Cuatro pisotones seguidos, cada uno con su onda; hay que moverse entre ellas.",
                icon("COARSE_DIRT", "DIRT"), f -> knight(f).earthquake());
        add(list, "salto_demoledor", "Salto Demoledor", 3, 330, 110, 3,
                "Salta muy alto y cae de lleno sobre la marca; el golpe mas bruto que tiene.",
                icon("NETHERITE_BOOTS", "IRON_BOOTS"), f -> knight(f).crushingLeap());
        add(list, "ultima_carga", "Ultima Carga", 3, 380, 120, 3,
                "El fantasma de la montura vuelve para una carga que atraviesa la arena.",
                icon("SKELETON_SKULL"), f -> knight(f).finalCharge());
        add(list, "grito_paramo", "Grito del Paramo", 3, 250, 70, 4,
                "Un haz sonico frontal de 24 bloques que atraviesa todo lo que pilla.",
                icon("ECHO_SHARD", "AMETHYST_SHARD"), f -> knight(f).wastelandScream());

        // --- Cualquier fase
        add(list, "caceria", "Caceria", 0, 340, 120, 2,
                "Le echa el ojo al que mas se aleja y va a por el a la carrera.",
                icon("TARGET", "REDSTONE"), f -> knight(f).hunt());
        add(list, "leva_huesos", "Leva de Huesos", 0, 700, 60, 2,
                "Recluta entre tres y seis caidos que salen del suelo alrededor.",
                icon("SKELETON_SPAWN_EGG", "BONE_MEAL"), f -> knight(f).boneLevy());

        return list;
    }


    // ------------------------------------------------------------ la Cabra Gritona

    /** Ficha de la Cabra Gritona. */
    public final class GoatType implements AnomalyType {

        @Override
        public String id() {
            return ScreamingGoat.ID;
        }

        @Override
        public String display() {
            return plugin.getConfig().getString("anomalias." + id() + ".nombre", "Cabra Gritona");
        }

        @Override
        public TextColor color() {
            return ScreamingGoat.ACCENT;
        }

        @Override
        public NamedTextColor glowColor() {
            return NamedTextColor.WHITE;
        }

        @Override
        public Element element() {
            return Element.VIENTO;
        }

        @Override
        public Material icon() {
            Material m = Material.matchMaterial("GOAT_HORN");
            return m != null ? m : Material.BONE;
        }

        @Override
        public String tagline() {
            return "Cabra chillona del tamano de una casa";
        }

        @Override
        public List<String> origin() {
            return List.of(
                    "Bajaba de los riscos del Aether a gritarle",
                    "a las tormentas, y una de ellas le contesto.",
                    "Desde entonces el trueno le hace caso: grita,",
                    "y el cielo se parte donde ella mira.");
        }

        @Override
        public List<String> threat() {
            return List.of(
                    "Elemento de viento: cumbres y cielo abierto",
                    "Cada grito empuja, hace dano y trae rayos",
                    "15 habilidades, ninguna a distancia sin aviso",
                    "Embiste, salta y no se deja mover");
        }

        @Override
        public double baseHealth() {
            return 1600;
        }

        @Override
        public int arenaRadius() {
            return 26;
        }

        @Override
        public List<Ability> abilities() {
            return goatAbilities();
        }

        @Override
        public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
            return new ScreamingGoat(plugin, event, where);
        }
    }

    /**
     * Las 15 habilidades de la Cabra. Todo lo suyo nace del grito: el berrido empuja,
     * llama al rayo y la deja ardiendo en blanco. Lo demas es embestir y saltar.
     */
    public List<Ability> goatAbilities() {
        List<Ability> list = new ArrayList<>();

        // --- Fase I: la embestida
        add(list, "grito_atronador", "Grito Atronador", 1, 200, 66, 5,
                "Cono de grito: empuja, marea y deja caer tres rayos sobre quien lo pille.",
                icon("GOAT_HORN", "BONE"), f -> goat(f).thunderScream());
        add(list, "embestida", "Embestida de Cuernos", 1, 190, 70, 5,
                "Retrocede, baja la cabeza y sale disparada en linea recta.",
                icon("IRON_HORSE_ARMOR", "SADDLE"), f -> goat(f).hornCharge());
        add(list, "pisoton_pezunas", "Pisoton de Pezunas", 1, 170, 60, 4,
                "Se alza y descarga las cuatro patas; la onda barre ocho bloques.",
                icon("COARSE_DIRT", "DIRT"), f -> goat(f).hoofStomp());
        add(list, "berrido", "Berrido", 1, 120, 52, 4,
                "Grito corto y circular, sin rayos, para quitarse gente de encima.",
                icon("NOTE_BLOCK", "BONE"), f -> goat(f).bleat());
        add(list, "salto_montanes", "Salto Montanes", 1, 260, 90, 3,
                "Salta como en el cerro y cae encima de donde estabas.",
                icon("FEATHER"), f -> goat(f).mountainLeap());

        // --- Fase II: la rabia
        add(list, "tormenta_balidos", "Tormenta de Balidos", 2, 300, 225, 4,
                "Cuatro gritos girando sobre si misma; no hay lado seguro.",
                icon("GOAT_HORN", "BONE"), f -> goat(f).bleatStorm());
        add(list, "rebote", "Rebote", 2, 220, 100, 4,
                "Va rebotando de uno a otro embistiendo a cada uno.",
                icon("SLIME_BALL"), f -> goat(f).ricochet());
        add(list, "manada", "Manada", 2, 600, 60, 2,
                "Baja el resto del rebano y embiste con ella.",
                icon("GOAT_SPAWN_EGG", "WHITE_WOOL"), f -> goat(f).herd());
        add(list, "cornada", "Cornada Ascendente", 2, 200, 40, 4,
                "Engancha al mas cercano con el cuerno y lo manda por los aires.",
                icon("GOAT_HORN", "BONE"), f -> goat(f).upwardGore());
        add(list, "pelaje_blanco", "Pelaje Blanco", 2, 420, 140, 3,
                "Arde en blanco: aguanta mucho mas y grita cada dos segundos.",
                icon("WHITE_WOOL"), f -> goat(f).whiteCoat());

        // --- Fase III: el trueno
        add(list, "grito_del_trueno", "Grito del Trueno", 3, 280, 80, 5,
                "Grito circular de dieciseis bloques con ocho rayos alrededor.",
                icon("LIGHTNING_ROD", "COPPER_INGOT"), f -> goat(f).thunderCry());
        add(list, "estampida", "Estampida", 3, 340, 180, 4,
                "Tres embestidas seguidas por toda la arena, sin descanso.",
                icon("IRON_HORSE_ARMOR", "SADDLE"), f -> goat(f).stampede());
        add(list, "cielo_partido", "Cielo Partido", 3, 320, 140, 4,
                "Siete segundos de rayos persiguiendo a cada uno por separado.",
                icon("TRIDENT", "COPPER_INGOT"), f -> goat(f).splitSky());
        add(list, "aullido_final", "Aullido Final", 3, 400, 90, 3,
                "El grito mas grande que tiene: veintidos bloques y doce rayos.",
                icon("BEACON", "GLOWSTONE"), f -> goat(f).finalHowl());

        // --- Cualquier fase
        add(list, "tozudez", "Tozudez", 0, 380, 70, 2,
                "Se planta, no la mueve nadie, y al soltarse embiste sin avisar.",
                icon("ANVIL", "IRON_BLOCK"), f -> goat(f).stubbornness());

        return list;
    }

    private static ScreamingGoat goat(BossFight fight) {
        return (ScreamingGoat) fight;
    }


    // ----------------------------------------------------------- el Conejo Asesino

    /** Ficha del Conejo Asesino. */
    public final class BunnyType implements AnomalyType {

        @Override
        public String id() {
            return KillerBunny.ID;
        }

        @Override
        public String display() {
            return plugin.getConfig().getString("anomalias." + id() + ".nombre", "Conejo Asesino");
        }

        @Override
        public TextColor color() {
            return KillerBunny.ACCENT;
        }

        /** Sin brillo a proposito: esta anomalia no se ve venir. */
        @Override
        public NamedTextColor glowColor() {
            return null;
        }

        @Override
        public Element element() {
            return Element.TIERRA;
        }

        @Override
        public Material icon() {
            Material m = Material.matchMaterial("RABBIT_FOOT");
            return m != null ? m : Material.RABBIT_HIDE;
        }

        @Override
        public String tagline() {
            return "Muerde y se multiplica; no brilla, no avisa";
        }

        @Override
        public List<String> origin() {
            return List.of(
                    "En el Aether corria una liebre blanca que nadie",
                    "conseguia contar dos veces igual. Cada mordisco",
                    "que daba le salia otra, y otra, y otra.",
                    "Aqui hace lo mismo, y aqui tampoco se deja contar.");
        }

        @Override
        public List<String> threat() {
            return List.of(
                    "Elemento de tierra: campo abierto y seco",
                    "SE MULTIPLICA cada vez que muerde, hasta 20 copias",
                    "Cuantas mas copias vivas, menos dano recibe el grande",
                    "No brilla, no avisa y no lleva nombre encima",
                    "Sus copias son identicas: mismo tamano y mismo nombre",
                    "Solo se delata, en ROJO, al lanzar sus golpes grandes");
        }

        @Override
        public double baseHealth() {
            return 1500;
        }

        @Override
        public int arenaRadius() {
            return 22;
        }

        @Override
        public List<Ability> abilities() {
            return bunnyAbilities();
        }

        @Override
        public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
            return new KillerBunny(plugin, event, where);
        }
    }

    /**
     * Las 16 habilidades del Conejo. Casi todas giran alrededor de lo mismo: llenar la
     * arena de copias y obligar al grupo a repartirse entre limpiarlas y pegarle al grande.
     */
    public List<Ability> bunnyAbilities() {
        List<Ability> list = new ArrayList<>();

        // --- Fase I: la plaga
        add(list, "camada", "Camada", 1, 200, 30, 4,
                "Se parte en tres de golpe, sin necesidad de morder a nadie.",
                icon("RABBIT_HIDE", "WHITE_WOOL"), f -> bunny(f).litter());
        add(list, "salto_asesino", "Salto Asesino", 1, 170, 60, 5,
                "Se lanza sobre alguien desde arriba y cae encima.",
                icon("RABBIT_FOOT", "FEATHER"), f -> bunny(f).killerLeap());
        add(list, "madriguera", "Madriguera", 1, 260, 70, 3,
                "Se hunde en el suelo y sale al lado del que mas se ha alejado.",
                icon("ROOTED_DIRT", "DIRT"), f -> bunny(f).burrow());
        add(list, "zigzag", "Carrera en Zigzag", 1, 190, 80, 4,
                "Cruza la arena a saltos cortos y sin linea recta.",
                icon("SUGAR", "FEATHER"), f -> bunny(f).zigzag());
        add(list, "patada_trasera", "Patada Trasera", 1, 150, 35, 4,
                "Una coz que manda al mas cercano al otro lado de la arena.",
                icon("LEATHER_BOOTS", "IRON_BOOTS"), f -> bunny(f).backKick());

        // --- Fase II: la marea
        add(list, "enjambre", "Enjambre", 2, 260, 70, 4,
                "Todas las copias se lanzan a la vez sobre el mismo jugador.",
                icon("BEEHIVE", "HONEYCOMB"), f -> bunny(f).swarm());
        add(list, "frenesi", "Frenesi", 2, 340, 120, 3,
                "El conejo y todas sus copias se vuelven mucho mas rapidos.",
                icon("SUGAR", "REDSTONE"), f -> bunny(f).frenzy());
        add(list, "mordisco_profundo", "Mordisco Profundo", 2, 200, 45, 4,
                "Un bocado que sigue sangrando seis segundos.",
                icon("RABBIT", "BEEF"), f -> bunny(f).deepBite());
        add(list, "campo_madrigueras", "Campo de Madrigueras", 2, 380, 160, 3,
                "Nueve agujeros por la arena; pisar uno duele y frena.",
                icon("ROOTED_DIRT", "COARSE_DIRT"), f -> bunny(f).burrowField());
        add(list, "zarpazo", "Zarpazo Giratorio", 2, 170, 50, 4,
                "Gira sobre si mismo repartiendo zarpazos a todo lo que toca.",
                icon("SHEARS", "FLINT"), f -> bunny(f).spinClaw());

        // --- Fase III: la horda
        add(list, "estampida_pelaje", "Estampida de Pelaje", 3, 300, 70, 4,
                "La horda entera cruza la arena en linea recta.",
                icon("WHITE_WOOL", "RABBIT_HIDE"), f -> bunny(f).furStampede());
        add(list, "salto_lunar", "Salto Lunar", 3, 320, 100, 4,
                "Sube hasta perderse de vista y cae con una onda de nueve bloques.",
                icon("PHANTOM_MEMBRANE", "FEATHER"), f -> bunny(f).moonLeap());
        add(list, "division_final", "Division Final", 3, 420, 60, 3,
                "Se parte hasta llenar el tope de veinte copias de una sentada.",
                icon("RABBIT_STEW", "RABBIT_HIDE"), f -> bunny(f).finalDivision());
        add(list, "mordida_final", "Mordida Final", 3, 260, 80, 4,
                "Se agarra a uno y le va arrancando trozos hasta que lo suelten.",
                icon("RABBIT_FOOT", "BONE"), f -> bunny(f).finalBite());

        // --- Cualquier fase
        add(list, "devorar", "Devorar", 0, 300, 50, 2,
                "Se come una de sus copias y se cura un 5% con ella.",
                icon("COOKED_RABBIT", "RABBIT"), f -> bunny(f).devour());
        add(list, "cambiazo", "Cambiazo", 0, 240, 60, 4,
                "Se cambia de sitio con sus copias varias veces; despues ya no sabes cual era.",
                icon("ENDER_PEARL", "SNOWBALL"), f -> bunny(f).swapPlaces());

        return list;
    }

    private static KillerBunny bunny(BossFight fight) {
        return (KillerBunny) fight;
    }


    // -------------------------------------------------------------- el Storm Rider

    /** Ficha del Storm Rider. */
    public final class RiderType implements AnomalyType {

        @Override
        public String id() {
            return StormRider.ID;
        }

        @Override
        public String display() {
            return plugin.getConfig().getString("anomalias." + id() + ".nombre", "Storm Rider");
        }

        @Override
        public TextColor color() {
            return StormRider.ACCENT;
        }

        @Override
        public NamedTextColor glowColor() {
            return NamedTextColor.AQUA;
        }

        /** Viento: necesita cielo abierto y altura, que es donde pelea la primera fase. */
        @Override
        public Element element() {
            return Element.VIENTO;
        }

        @Override
        public Material icon() {
            Material m = Material.matchMaterial("TRIDENT");
            return m != null ? m : Material.PHANTOM_MEMBRANE;
        }

        @Override
        public String tagline() {
            return "Ahogado con tridente sobre un phantom gigante";
        }

        @Override
        public List<String> origin() {
            return List.of(
                    "Se ahogo persiguiendo una tormenta y la tormenta",
                    "no lo solto: le devolvio el tridente y le dio",
                    "algo con lo que volar. Desde entonces cabalga",
                    "el frente de cada temporal buscando la orilla",
                    "en la que se quedo sin aire.");
        }

        @Override
        public List<String> threat() {
            return List.of(
                    "Elemento de viento: cumbres y cielo abierto",
                    "FASE I: vuela alto, la espada casi no le hace nada",
                    "FASE II: cae al suelo y pelea con el tridente",
                    "FASE III: dos tridentes y modo berserker, fragil pero brutal");
        }

        @Override
        public double baseHealth() {
            return 1700;
        }

        @Override
        public int arenaRadius() {
            return 26;
        }

        @Override
        public List<Ability> abilities() {
            return riderAbilities();
        }

        @Override
        public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
            return new StormRider(plugin, event, where);
        }
    }

    /**
     * Las 15 habilidades del Storm Rider, repartidas muy desigualmente a proposito:
     * la fase del aire es larga y a distancia, la del suelo es corta y directa, y la
     * berserker es una tromba.
     */
    public List<Ability> riderAbilities() {
        List<Ability> list = new ArrayList<>();

        // --- Fase I: desde el aire
        add(list, "lanza_tormenta", "Lanza de Tormenta", 1, 160, 50, 5,
                "Arroja el tridente contra hasta tres jugadores.",
                icon("TRIDENT", "ARROW"), f -> rider(f).stormJavelin());
        add(list, "picado", "Picado", 1, 240, 80, 4,
                "Se lanza en vertical sobre una marca y vuelve a subir.",
                icon("PHANTOM_MEMBRANE", "FEATHER"), f -> rider(f).divebomb());
        add(list, "ojo_huracan", "Ojo del Huracan", 1, 380, 140, 3,
                "Un remolino que arrastra a todos hacia el centro durante siete segundos.",
                icon("WIND_CHARGE", "GLASS_BOTTLE"), f -> rider(f).hurricaneEye());
        add(list, "descarga", "Descarga", 1, 300, 120, 4,
                "Rayos sobre marcas que persiguen a cada jugador.",
                icon("LIGHTNING_ROD", "COPPER_INGOT"), f -> rider(f).discharge());
        add(list, "bandada", "Bandada", 1, 560, 60, 2,
                "Llama de tres a cinco phantoms menores que hostigan desde arriba.",
                icon("PHANTOM_SPAWN_EGG", "PHANTOM_MEMBRANE"), f -> rider(f).flock());
        add(list, "viento_cortante", "Viento Cortante", 1, 260, 90, 4,
                "Cuchillas de aire que barren el suelo desde el cielo.",
                icon("SHEARS", "FLINT"), f -> rider(f).windBlades());

        // --- Fase II: a pie (lote corto a proposito)
        add(list, "barrido_tridente", "Barrido de Tridente", 2, 150, 40, 5,
                "Un arco amplio a ras de suelo con el tridente por delante.",
                icon("TRIDENT", "IRON_SWORD"), f -> rider(f).tridentSweep());
        add(list, "maremoto", "Maremoto", 2, 260, 70, 4,
                "Una ola que sale de el y barre once bloques a la redonda.",
                icon("WATER_BUCKET", "PRISMARINE_SHARD"), f -> rider(f).tidalWave());
        add(list, "ancla", "Ancla de Tormenta", 2, 220, 50, 4,
                "Arponea al que mas se aleja y lo trae de vuelta.",
                icon("CHAIN", "IRON_INGOT"), f -> rider(f).stormAnchor());
        add(list, "carga_marea", "Carga de Marea", 2, 230, 60, 4,
                "Embiste en linea recta con el tridente por delante.",
                icon("HEART_OF_THE_SEA", "PRISMARINE_CRYSTALS"), f -> rider(f).tideCharge());

        // --- Fase III: berserker
        add(list, "frenesi_tridentes", "Frenesi de Tridentes", 3, 200, 80, 5,
                "Una tanda de golpes rapidisimos con los dos tridentes a la vez.",
                icon("TRIDENT", "IRON_SWORD"), f -> rider(f).tridentFrenzy());
        add(list, "doble_tajo", "Doble Tajo", 3, 180, 50, 5,
                "Dos cortes cruzados, uno con cada mano.",
                icon("TRIDENT", "IRON_AXE"), f -> rider(f).crossSlash());
        add(list, "tormenta_perfecta", "Tormenta Perfecta", 3, 400, 150, 3,
                "Rayos, viento y embestidas a la vez durante siete segundos y medio.",
                icon("BEACON", "LIGHTNING_ROD"), f -> rider(f).perfectStorm());
        add(list, "salto_trueno", "Salto del Trueno", 3, 260, 80, 4,
                "Salta y cae sobre la marca con un rayo encima.",
                icon("NETHERITE_BOOTS", "IRON_BOOTS"), f -> rider(f).thunderJump());

        // --- Cualquier fase
        add(list, "relampago_guia", "Relampago Guia", 0, 300, 70, 3,
                "Marca a uno y le cae el rayo donde este seis segundos despues.",
                icon("TARGET", "REDSTONE"), f -> rider(f).guidingBolt());

        return list;
    }

    private static StormRider rider(BossFight fight) {
        return (StormRider) fight;
    }


    // ------------------------------------------------------------ el Leviatan de Sal

    /** Ficha del Leviatan de Sal. */
    public final class LeviathanType implements AnomalyType {

        @Override
        public String id() {
            return SaltLeviathan.ID;
        }

        @Override
        public String display() {
            return plugin.getConfig().getString("anomalias." + id() + ".nombre", "Leviatan de Sal");
        }

        @Override
        public TextColor color() {
            return SaltLeviathan.ACCENT;
        }

        @Override
        public NamedTextColor glowColor() {
            return NamedTextColor.DARK_AQUA;
        }

        @Override
        public Element element() {
            return Element.AGUA;
        }

        @Override
        public Material icon() {
            Material m = Material.matchMaterial("HEART_OF_THE_SEA");
            return m != null ? m : Material.PRISMARINE_SHARD;
        }

        @Override
        public String tagline() {
            return "Guardian anciano descomunal en el fondo del mar";
        }

        @Override
        public List<String> origin() {
            return List.of(
                    "Los monumentos del oceano no se construyeron solos,",
                    "y lo que los mando levantar sigue ahi abajo.",
                    "Duerme en la sal desde antes de que hubiera orilla,",
                    "y cuando alguien baja demasiado, abre un ojo.");
        }

        @Override
        public List<String> threat() {
            return List.of(
                    "Elemento de agua: se pelea en el FONDO, sumergido",
                    "Dentro de su arena el abismo te deja respirar",
                    "Fuera de ella no hay aire: huir es peor que quedarse",
                    "15 habilidades de haz, corriente y presion");
        }

        @Override
        public double baseHealth() {
            return 1800;
        }

        @Override
        public int arenaRadius() {
            return 22;
        }

        @Override
        public List<Ability> abilities() {
            return leviathanAbilities();
        }

        @Override
        public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
            return new SaltLeviathan(plugin, event, where);
        }
    }

    /** Las 15 habilidades del Leviatan: haces, corrientes y presion. */
    public List<Ability> leviathanAbilities() {
        List<Ability> list = new ArrayList<>();

        add(list, "haz_abisal", "Haz Abisal", 1, 170, 60, 5,
                "El rayo de guardian, cargado y con dos segundos de aviso.",
                icon("PRISMARINE_CRYSTALS", "GLOWSTONE_DUST"), f -> leviathan(f).abyssalBeam());
        add(list, "coro_espinas", "Coro de Espinas", 1, 220, 90, 4,
                "El fondo se eriza de espinas de prismarina en anillos.",
                icon("PRISMARINE_SHARD", "QUARTZ"), f -> leviathan(f).thornChoir());
        add(list, "remolino", "Remolino", 1, 340, 140, 3,
                "Un embudo que arrastra a todos hacia el fondo y hacia el.",
                icon("WATER_BUCKET", "BUCKET"), f -> leviathan(f).whirlpool());
        add(list, "columna_ascendente", "Columna Ascendente", 1, 260, 70, 3,
                "Chorros que te disparan hacia arriba, lejos de el y del aire.",
                icon("MAGMA_BLOCK", "SOUL_SAND"), f -> leviathan(f).risingColumn());
        add(list, "banco_guardianes", "Banco de Guardianes", 1, 520, 60, 2,
                "Llama de tres a cinco guardianes que hostigan desde los lados.",
                icon("GUARDIAN_SPAWN_EGG", "PRISMARINE"), f -> leviathan(f).guardianShoal());

        add(list, "haces_encadenados", "Haces Encadenados", 2, 280, 100, 4,
                "El rayo salta de un jugador al siguiente hasta acabar la fila.",
                icon("CHAIN", "PRISMARINE_CRYSTALS"), f -> leviathan(f).chainedBeams());
        add(list, "tinta_abismo", "Tinta del Abismo", 2, 300, 120, 3,
                "Una nube negra que ciega y frena a quien se queda dentro.",
                icon("INK_SAC", "BLACK_DYE"), f -> leviathan(f).abyssalInk());
        add(list, "presion", "Presion", 2, 320, 140, 3,
                "Castiga a quien intenta huir hacia la superficie: mas alto, mas duele.",
                icon("PISTON", "ANVIL"), f -> leviathan(f).pressure());
        add(list, "latigo_marea", "Latigo de Marea", 2, 190, 60, 5,
                "Un latigazo de agua que barre dieciseis bloques en linea.",
                icon("KELP", "SEAGRASS"), f -> leviathan(f).tideWhip());
        add(list, "torbellino_espinas", "Torbellino de Espinas", 2, 200, 70, 4,
                "Gira soltando cuatro brazos de espinas a su alrededor.",
                icon("PRISMARINE_BRICKS", "PRISMARINE"), f -> leviathan(f).thornSpin());

        add(list, "rayo_abismo", "Rayo del Abismo", 3, 360, 140, 4,
                "Un haz gigantesco que barre la arena girando; hay que ponerse detras.",
                icon("BEACON", "SEA_LANTERN"), f -> leviathan(f).abyssRay());
        add(list, "implosion", "Implosion", 3, 340, 100, 4,
                "Succiona a todo el mundo al centro y revienta.",
                icon("HEART_OF_THE_SEA", "NAUTILUS_SHELL"), f -> leviathan(f).implosion());
        add(list, "maelstrom", "Maelstrom", 3, 400, 160, 3,
                "La arena entera gira y te hace dar vueltas sin control.",
                icon("CONDUIT", "NAUTILUS_SHELL"), f -> leviathan(f).maelstrom());
        add(list, "mordida_abisal", "Mordida Abisal", 3, 240, 70, 4,
                "Se lanza y muerde con todo lo que tiene.",
                icon("COD", "SALMON"), f -> leviathan(f).abyssalBite());

        add(list, "canto_sal", "Canto de Sal", 0, 400, 80, 2,
                "La maldicion del guardian anciano, esta vez con aviso.",
                icon("SPONGE", "WET_SPONGE"), f -> leviathan(f).saltSong());

        return list;
    }

    private static SaltLeviathan leviathan(BossFight fight) {
        return (SaltLeviathan) fight;
    }

    // --------------------------------------------------------------- el Coro Abisal

    /** Ficha del Coro Abisal. */
    public final class ChoirType implements AnomalyType {

        @Override
        public String id() {
            return AbyssalChoir.ID;
        }

        @Override
        public String display() {
            return plugin.getConfig().getString("anomalias." + id() + ".nombre", "Coro Abisal");
        }

        @Override
        public TextColor color() {
            return AbyssalChoir.ACCENT;
        }

        @Override
        public NamedTextColor glowColor() {
            return NamedTextColor.LIGHT_PURPLE;
        }

        @Override
        public Element element() {
            return Element.AGUA;
        }

        @Override
        public Material icon() {
            Material m = Material.matchMaterial("AMETHYST_CLUSTER");
            return m != null ? m : Material.AMETHYST_SHARD;
        }

        @Override
        public String tagline() {
            return "Tres cantores, un nucleo intocable y un orden que romper";
        }

        @Override
        public List<String> origin() {
            return List.of(
                    "Bajo el agua hay una nota que nunca ha dejado",
                    "de sonar. Tres voces la sostienen y una cuarta",
                    "la escucha desde el centro, sin tocar nunca nada.",
                    "Callar a las tres en el orden equivocado solo",
                    "consigue que vuelvan a empezar.");
        }

        @Override
        public List<String> threat() {
            return List.of(
                    "Elemento de agua: se pelea en el FONDO, sumergido",
                    "El nucleo es INTOCABLE mientras cante el coro",
                    "Hay que apagar a los cantores en el orden de sus luces",
                    "Fallar el orden lo revive todo y castiga a los presentes");
        }

        @Override
        public double baseHealth() {
            return 1300;
        }

        @Override
        public int arenaRadius() {
            return 20;
        }

        @Override
        public List<Ability> abilities() {
            return choirAbilities();
        }

        @Override
        public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
            return new AbyssalChoir(plugin, event, where);
        }
    }

    /**
     * Las 12 habilidades del Coro. Son menos que las de los demas a proposito: aqui
     * el contenido de la pelea es el puzzle del orden, y llenarla de golpes lo taparia.
     */
    public List<Ability> choirAbilities() {
        List<Ability> list = new ArrayList<>();

        add(list, "orden_coro", "Orden del Coro", 1, 400, 20, 3,
                "Baraja el orden sin rehacer los cantores: hay que volver a mirar las luces.",
                icon("AMETHYST_SHARD", "QUARTZ"), f -> choir(f).reorder());
        add(list, "haz_nucleo", "Haz del Nucleo", 1, 190, 55, 5,
                "Un rayo largo desde el centro, con aviso.",
                icon("SEA_LANTERN", "GLOWSTONE"), f -> choir(f).coreBeam());
        add(list, "pulso_armonico", "Pulso Armonico", 1, 220, 60, 4,
                "Un anillo de luz que se expande dieciseis bloques.",
                icon("AMETHYST_CLUSTER", "AMETHYST_SHARD"), f -> choir(f).harmonicPulse());
        add(list, "marea_baja", "Marea Baja", 1, 300, 100, 3,
                "Arrastra a todos hacia el centro durante cinco segundos.",
                icon("WATER_BUCKET", "BUCKET"), f -> choir(f).undertow());

        add(list, "contracanto", "Contracanto", 2, 240, 80, 4,
                "Cada cantor dispara su propio haz a un jugador distinto.",
                icon("NOTE_BLOCK", "AMETHYST_SHARD"), f -> choir(f).counterSong());
        add(list, "disonancia", "Disonancia", 2, 300, 70, 3,
                "Una nota desafinada que ciega, marea y entumece.",
                icon("BELL", "AMETHYST_BLOCK"), f -> choir(f).dissonance());
        add(list, "enjambre_abisal", "Enjambre Abisal", 2, 460, 60, 2,
                "Ecos que hostigan y que NO cuentan para el orden del coro.",
                icon("GUARDIAN_SPAWN_EGG", "PRISMARINE"), f -> choir(f).abyssalSwarm());

        add(list, "crescendo", "Crescendo", 3, 340, 110, 4,
                "La nota sube seis segundos y revienta en catorce bloques.",
                icon("BEACON", "AMETHYST_CLUSTER"), f -> choir(f).crescendo());
        add(list, "nota_final", "Nota Final", 3, 320, 120, 4,
                "Un haz que barre girando por toda la arena.",
                icon("SEA_LANTERN", "GLOWSTONE"), f -> choir(f).finalNote());
        add(list, "coro_completo", "Coro Completo", 3, 300, 80, 4,
                "Nucleo y cantores disparan a la vez, cada uno a uno.",
                icon("AMETHYST_BLOCK", "AMETHYST_CLUSTER"), f -> choir(f).fullChoir());

        add(list, "eco", "Eco", 0, 260, 20, 3,
                "Los cantores se intercambian el sitio; el turno de cada uno no cambia.",
                icon("ENDER_PEARL", "AMETHYST_SHARD"), f -> choir(f).echo());
        add(list, "silencio", "Silencio", 0, 320, 80, 3,
                "Un instante de calma que termina en golpe.",
                icon("SCULK_SENSOR", "WOOL"), f -> choir(f).silence());

        return list;
    }

    private static AbyssalChoir choir(BossFight fight) {
        return (AbyssalChoir) fight;
    }

    private static SepulchralKnight knight(BossFight fight) {
        return (SepulchralKnight) fight;
    }

    private static void add(List<Ability> list, String id, String display, int phase,
                            int cooldown, int cast, int weight, String description,
                            Material icon, Consumer<BossFight> action) {
        list.add(new Ability(id, display, description, phase, cooldown, cast, weight, icon, action));
    }

    /** Primer material de la lista que exista en esta version. */
    private static Material icon(String... names) {
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null) return m;
        }
        return Material.PAPER;
    }
}
