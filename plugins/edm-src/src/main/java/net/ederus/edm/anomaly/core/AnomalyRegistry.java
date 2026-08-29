package net.ederus.edm.anomaly.core;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.boss.Ability;
import net.ederus.edm.anomaly.boss.BossFight;
import net.ederus.edm.anomaly.boss.AbyssalChoir;
import net.ederus.edm.anomaly.boss.Aragon;
import net.ederus.edm.anomaly.boss.Bruja;
import net.ederus.edm.anomaly.boss.Cazador;
import net.ederus.edm.anomaly.boss.Piromante;
import net.ederus.edm.anomaly.boss.Keeper;
import net.ederus.edm.anomaly.boss.CopperTwins;
import net.ederus.edm.anomaly.boss.Darkness;
import net.ederus.edm.anomaly.boss.Herbola;
import net.ederus.edm.anomaly.boss.Quimera;
import net.ederus.edm.anomaly.boss.Mimic;
import net.ederus.edm.anomaly.boss.Rabby;
import net.ederus.edm.anomaly.boss.KillerBunny;
import net.ederus.edm.anomaly.boss.SaltLeviathan;
import net.ederus.edm.anomaly.boss.ScreamingGoat;
import net.ederus.edm.anomaly.boss.StormRider;
import net.ederus.edm.anomaly.boss.SepulchralKnight;
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
        register(new DarknessType());
        register(new HerbolaType());
        register(new QuimeraType());
        register(new BrujaType());
        register(new MimicType());
        register(new RabbyType());
        register(new CazadorType());
        register(new AragonType());
        register(new PiromanteType());
        register(new KeeperType());
        register(new CopperTwinsType());
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

    public AnomalyType darkness() {
        return types.get(Darkness.ID);
    }

    public AnomalyType herbola() {
        return types.get(Herbola.ID);
    }

    public AnomalyType quimera() {
        return types.get(Quimera.ID);
    }

    public AnomalyType bruja() {
        return types.get(Bruja.ID);
    }

    public AnomalyType mimic() {
        return types.get(Mimic.ID);
    }

    public AnomalyType rabby() {
        return types.get(Rabby.ID);
    }

    public AnomalyType cazador() {
        return types.get(Cazador.ID);
    }

    public AnomalyType aragon() {
        return types.get(Aragon.ID);
    }

    public AnomalyType piromante() {
        return types.get(Piromante.ID);
    }

    public AnomalyType keeper() {
        return types.get(Keeper.ID);
    }

    public AnomalyType copperTwins() {
        return types.get(CopperTwins.ID);
    }

    /**
     * El catalogo entero, ordenado por clase: primero los Monarcas, despues los
     * Generales y al final los Esbirros. Dentro de una clase se respeta el orden de
     * registro, que es el historico. TODO el que pinte o indexe la lista debe usar
     * este metodo, para que el menu y los clics hablen del mismo orden.
     */
    public List<AnomalyType> all() {
        List<AnomalyType> out = new ArrayList<>(types.values());
        out.sort(java.util.Comparator.comparingInt((AnomalyType t) -> -classOf(t).rank()));
        return out;
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

    /**
     * La clase de la anomalia (Esbirro, General o Monarca). Se guarda en config.yml
     * en cuanto se toca desde el menu; si nunca se ha tocado vale la de diseno.
     */
    public AnomalyClass classOf(AnomalyType type) {
        String raw = plugin.getConfig().getString("anomalias." + type.id() + ".clase", null);
        return AnomalyClass.parse(raw, type.defaultClass());
    }

    public void setClass(AnomalyType type, AnomalyClass clazz) {
        plugin.settings().set("anomalias." + type.id() + ".clase", clazz.name());
    }

    /**
     * El punto fijo de aparicion, si el admin marco uno golpeando un bloque desde el
     * menu. Null significa lo de siempre: el buscador elige un sitio aleatorio valido.
     *
     * Si el mundo guardado ya no existe se devuelve null en vez de romper: la anomalia
     * vuelve a ser aleatoria sola y el menu lo ensena como tal.
     */
    public Location spawnPoint(AnomalyType type) {
        String base = "anomalias." + type.id() + ".spawn";
        var cfg = plugin.getConfig();
        String worldName = cfg.getString(base + ".mundo", null);
        if (worldName == null || worldName.isBlank()) return null;
        org.bukkit.World world = plugin.getServer().getWorld(worldName);
        if (world == null) return null;
        return new Location(world, cfg.getDouble(base + ".x"), cfg.getDouble(base + ".y"), cfg.getDouble(base + ".z"));
    }

    public void setSpawnPoint(AnomalyType type, Location where) {
        if (where == null || where.getWorld() == null) return;
        String base = "anomalias." + type.id() + ".spawn";
        var cfg = plugin.getConfig();
        cfg.set(base + ".mundo", where.getWorld().getName());
        cfg.set(base + ".x", where.getX());
        cfg.set(base + ".y", where.getY());
        cfg.set(base + ".z", where.getZ());
        plugin.saveConfig();
    }

    /** Borra la marca: la anomalia vuelve a aparecer en un sitio aleatorio. */
    public void clearSpawnPoint(AnomalyType type) {
        plugin.getConfig().set("anomalias." + type.id() + ".spawn", null);
        plugin.saveConfig();
    }

    /**
     * De donde viene la anomalia, que es lo que cuenta el hover del anuncio.
     *
     * Se puede reescribir entera desde config.yml sin tocar el plugin: basta con poner
     * `anomalias.<id>.descripcion` como una lista de lineas. Si no esta, se usa la que
     * trae escrita la anomalia.
     */
    public List<String> origin(AnomalyType type) {
        List<String> custom = plugin.getConfig().getStringList("anomalias." + type.id() + ".descripcion");
        return custom.isEmpty() ? type.origin() : custom;
    }

    /** Lo mismo para el aviso de peligro: `anomalias.<id>.amenaza`. */
    public List<String> threat(AnomalyType type) {
        List<String> custom = plugin.getConfig().getStringList("anomalias." + type.id() + ".amenaza");
        return custom.isEmpty() ? type.threat() : custom;
    }

    /** Vida base configurada; si no hay override, la que trae la anomalia. */
    public double health(AnomalyType type) {
        return plugin.getConfig().getDouble("anomalias." + type.id() + ".vida", type.baseHealth());
    }

    /**
     * Tope del ajuste de vida. Da para un x20 largo sobre el jefe mas gordo del
     * catalogo; por encima de 1024 el exceso se cobra en reduccion de dano recibido
     * (ver BossFight#applyHealth), asi que el numero puede crecer sin romper nada.
     */
    public static final double MAX_HEALTH_SETTING = 400000;

    public void setHealth(AnomalyType type, double value) {
        plugin.settings().set("anomalias." + type.id() + ".vida",
                Math.round(Fx.clamp(value, 100, MAX_HEALTH_SETTING)));
    }

    /** Cuantas veces la vida original es la vida configurada. Para el menu. */
    public double healthTimes(AnomalyType type) {
        double base = type.baseHealth();
        if (base <= 0) return 1;
        return Math.round(health(type) / base * 10.0) / 10.0;
    }

    /**
     * Multiplicador del dano de TODAS las habilidades de esta anomalia. 1.0 es el
     * valor de diseno; subirlo o bajarlo mueve el jefe entero de golpe, sin tener que
     * tocar habilidad por habilidad.
     *
     * No afecta al golpe cuerpo a cuerpo normal, que es un atributo de la entidad.
     */
    /** Tope del multiplicador de dano: hasta x20 el dano de diseno. */
    public static final double MAX_DAMAGE_MULTIPLIER = 20.0;

    public double damageMultiplier(AnomalyType type) {
        return Fx.clamp(plugin.getConfig().getDouble("anomalias." + type.id() + ".dano", 1.0),
                0.1, MAX_DAMAGE_MULTIPLIER);
    }

    public void setDamageMultiplier(AnomalyType type, double value) {
        plugin.settings().set("anomalias." + type.id() + ".dano",
                Math.round(Fx.clamp(value, 0.1, MAX_DAMAGE_MULTIPLIER) * 10.0) / 10.0);
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
        // Margen por encima del tope del ajuste: el escalado por jugadores se suma.
        return Fx.clamp(scaled, 100, MAX_HEALTH_SETTING * 5);
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
        add(list, "estocada_fantasma", "Estocadas Fantasma", 2, 220, 105, 5,
                "Se teletransporta a la espalda de hasta tres, uno tras otro, y estoca a cada uno.",
                icon("ENDER_PEARL"), f -> knight(f).phantomThrust());
        add(list, "tajo_descendente", "Tajo Descendente", 2, 240, 80, 4,
                "Parte el suelo en linea recta con un tajo de arriba abajo.",
                icon("CRACKED_DEEPSLATE_BRICKS", "DEEPSLATE"), f -> knight(f).overheadCleave());
        add(list, "cadena_hueso", "Cadenas de Hueso", 2, 210, 55, 3,
                "Engancha a los tres que mas se alejan y los arrastra de vuelta al centro.",
                icon("CHAIN"), f -> knight(f).boneChain());
        add(list, "juramento_roto", "Juramento Roto", 2, 400, 140, 3,
                "Marca hasta a tres: quien se aleje mas de 16 bloques en 7 s recibe un golpe brutal.",
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
                "Engancha con el cuerno a los que tenga pegados y los manda por los aires.",
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
        add(list, "salto_asesino", "Salto Asesino", 1, 220, 125, 5,
                "Dos saltos desde arriba, cada uno sobre un jugador distinto.",
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
                "La horda se reparte: cada copia se lanza a por un jugador distinto.",
                icon("BEEHIVE", "HONEYCOMB"), f -> bunny(f).swarm());
        add(list, "frenesi", "Frenesi", 2, 340, 120, 3,
                "El conejo y todas sus copias se vuelven mucho mas rapidos.",
                icon("SUGAR", "REDSTONE"), f -> bunny(f).frenzy());
        add(list, "mordisco_profundo", "Mordisco Profundo", 2, 200, 45, 4,
                "Dentelladas a los tres mas cercanos; cada una sangra seis segundos.",
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
                    "FASE I: ES un phantom gigante; la espada casi no le hace nada",
                    "FASE II: se estrella en picado y sale su forma terrestre",
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
        // Sin esbirros: los phantoms menores se quitaron porque estorbaban mas que aportaban.
        List<Ability> list = new ArrayList<>();

        // --- Fase I: desde el aire
        add(list, "chillido", "Chillido del Temporal", 1, 170, 70, 5,
                "Un grito desde arriba: cinco ondas que empujan y dejan sin vista.",
                icon("PHANTOM_MEMBRANE", "ECHO_SHARD"), f -> rider(f).stormShriek());
        add(list, "picado", "Picado", 1, 260, 170, 4,
                "Dos picados seguidos, cada uno sobre un jugador distinto.",
                icon("PHANTOM_MEMBRANE", "FEATHER"), f -> rider(f).divebomb());
        add(list, "ojo_huracan", "Ojo del Huracan", 1, 380, 140, 3,
                "Un remolino que arrastra a todos hacia el centro durante siete segundos.",
                icon("WIND_CHARGE", "GLASS_BOTTLE"), f -> rider(f).hurricaneEye());
        add(list, "descarga", "Descarga", 1, 300, 120, 4,
                "Rayos sobre marcas que persiguen a cada jugador.",
                icon("LIGHTNING_ROD", "COPPER_INGOT"), f -> rider(f).discharge());
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
                "Arponea a los dos que mas se alejan y los trae de vuelta.",
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
                "Marca a tres y a cada uno le cae el rayo donde este seis segundos despues.",
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
                    "14 habilidades de haz, corriente y presion, sin esbirros");
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

    /**
     * Las 14 habilidades del Leviatan: haces, corrientes y presion.
     * SIN esbirros a proposito; de eso ya van sobradas las otras anomalias.
     */
    public List<Ability> leviathanAbilities() {
        List<Ability> list = new ArrayList<>();

        add(list, "haz_abisal", "Haz Abisal", 1, 170, 60, 5,
                "Tres rayos de guardian a la vez, cargados y con dos segundos de aviso.",
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
                "El rayo del centro se parte en dos, cada mitad a un jugador.",
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


    // -------------------------------------------------------------------- Darkness

    /** Ficha de Darkness. */
    public final class DarknessType implements AnomalyType {

        @Override
        public String id() {
            return Darkness.ID;
        }

        @Override
        public String display() {
            return plugin.getConfig().getString("anomalias." + id() + ".nombre", "Darkness");
        }

        @Override
        public TextColor color() {
            return Darkness.ACCENT;
        }

        /** Morado casi todo el combate; en la ultima fase el propio jefe lo cambia a blanco. */
        @Override
        public NamedTextColor glowColor() {
            return NamedTextColor.DARK_PURPLE;
        }

        @Override
        public Element element() {
            return Element.TIERRA;
        }

        @Override
        public Material icon() {
            Material m = Material.matchMaterial("ENDER_EYE");
            return m != null ? m : Material.ENDER_PEARL;
        }

        @Override
        public String tagline() {
            return "Enderman colosal; el jefe mas duro del catalogo";
        }

        @Override
        public List<String> origin() {
            return List.of(
                    "No vino de ningun sitio: estaba en el hueco que",
                    "queda cuando se apaga una antorcha y todavia no",
                    "has encendido la siguiente. Ese hueco crecio, y",
                    "un dia se levanto y echo a andar.");
        }

        @Override
        public List<String> threat() {
            return List.of(
                    "Casi todo lo suyo ciega: se pelea a oscuras",
                    "Se cura partiendose en siete; hay que dar con el de verdad",
                    "Su prision de sombra se cierra y no deja salir",
                    "En la ultima fase crece hasta coloso y abre un agujero negro");
        }

        @Override
        public double baseHealth() {
            return 2600;
        }

        @Override
        public int arenaRadius() {
            return 28;
        }

        @Override
        public List<Ability> abilities() {
            return darknessAbilities();
        }

        @Override
        public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
            return new Darkness(plugin, event, where);
        }
    }

    /** Las 15 habilidades de Darkness. Ninguna trae esbirros: los dobles son su cura. */
    public List<Ability> darknessAbilities() {
        List<Ability> list = new ArrayList<>();

        add(list, "septeto", "Septeto", 2, 700, 300, 3,
                "Se parte en siete dobles que vibran mientras el original se cura.",
                icon("ENDER_PEARL", "ENDER_EYE"), f -> darkness(f).septet());
        add(list, "prision", "Prision de Vacio", 0, 520, 220, 4,
                "Una cupula de sombra que se cierra sobre la arena; fuera de ella no se aguanta.",
                icon("OBSIDIAN", "BLACK_CONCRETE"), f -> darkness(f).voidPrison());
        add(list, "campo_cinetico", "Campo Cinetico", 0, 420, 150, 4,
                "Los sujeta, los levanta y revienta mandandolos volando muy lejos.",
                icon("HEAVY_CORE", "ANVIL"), f -> darkness(f).kineticField());

        add(list, "velo", "Velo de Oscuridad", 1, 240, 90, 5,
                "Apaga la vista de todo el que este cerca.",
                icon("BLACK_DYE", "INK_SAC"), f -> darkness(f).veil());
        add(list, "parpadeo", "Parpadeo", 1, 200, 80, 5,
                "Tres apariciones por la espalda, cada una a un jugador distinto.",
                icon("ENDER_PEARL", "CHORUS_FRUIT"), f -> darkness(f).blink());
        add(list, "mirada", "La Mirada", 1, 340, 160, 3,
                "Fija a uno y lo castiga cada vez que le da la espalda.",
                icon("ENDER_EYE", "SPYGLASS"), f -> darkness(f).stare());
        add(list, "pulso_negro", "Pulso Negro", 1, 260, 70, 4,
                "Una onda de vacio que apaga la vista al pasar.",
                icon("OBSIDIAN", "BLACK_CONCRETE"), f -> darkness(f).blackPulse());

        add(list, "agarre_sombrio", "Agarre Sombrio", 2, 240, 70, 4,
                "Dos manos de vacio que arrastran a los que mas se alejan.",
                icon("CHAIN", "STRING"), f -> darkness(f).shadowGrasp());
        add(list, "lluvia_vacio", "Lluvia del Vacio", 2, 320, 150, 4,
                "Motas negras que caen sobre marcas que te persiguen.",
                icon("BLACK_CONCRETE_POWDER", "GUNPOWDER"), f -> darkness(f).voidRain());
        add(list, "eco_vacio", "Eco del Vacio", 2, 300, 140, 3,
                "Sus posiciones pasadas estallan una detras de otra.",
                icon("SCULK_SENSOR", "SCULK"), f -> darkness(f).voidEcho());
        add(list, "fisura", "Fisura", 2, 250, 70, 4,
                "El suelo se abre en una grieta de dieciocho bloques que escupe oscuridad.",
                icon("DEEPSLATE", "CRACKED_DEEPSLATE_BRICKS"), f -> darkness(f).fissure());

        add(list, "ceguera_total", "Ceguera Total", 3, 380, 100, 4,
                "A todo el mundo, sin sitio donde esconderse: se pelea de oido.",
                icon("BLACK_WOOL", "BLACK_DYE"), f -> darkness(f).totalBlindness());
        add(list, "desgarro", "Desgarro", 3, 200, 45, 5,
                "Un zarpazo enorme en arco de ocho bloques.",
                icon("NETHERITE_SCRAP", "IRON_INGOT"), f -> darkness(f).rend());
        add(list, "colapso", "Colapso", 3, 520, 200, 4,
                "Un agujero negro que se lo traga todo durante siete segundos y revienta.",
                icon("CRYING_OBSIDIAN", "OBSIDIAN"), f -> darkness(f).collapse());
        add(list, "singularidad", "Singularidad", 3, 400, 120, 4,
                "Se encoge en un punto y sale con cuatro anillos de golpe.",
                icon("NETHER_STAR", "END_CRYSTAL"), f -> darkness(f).singularity());

        return list;
    }

    private static Darkness darkness(BossFight fight) {
        return (Darkness) fight;
    }

    // --------------------------------------------------------------------- Herbola

    /** Ficha de Herbola. */
    public final class HerbolaType implements AnomalyType {

        @Override
        public String id() {
            return Herbola.ID;
        }

        @Override
        public String display() {
            return plugin.getConfig().getString("anomalias." + id() + ".nombre", "Herbola");
        }

        @Override
        public TextColor color() {
            return Herbola.ACCENT;
        }

        @Override
        public NamedTextColor glowColor() {
            return NamedTextColor.GREEN;
        }

        @Override
        public Element element() {
            return Element.TIERRA;
        }

        @Override
        public Material icon() {
            Material m = Material.matchMaterial("FLOWERING_AZALEA");
            return m != null ? m : Material.MOSS_BLOCK;
        }

        @Override
        public String tagline() {
            return "Bogged que convierte en jardin todo lo que pisa";
        }

        @Override
        public List<String> origin() {
            return List.of(
                    "Se quedo dormida en un bosque humedo y el bosque",
                    "no espero a que despertara: le crecio encima.",
                    "Ahora camina y el musgo va con ella, y en la cabeza",
                    "lleva al unico que se acuerda de como era antes.");
        }

        @Override
        public List<String> threat() {
            return List.of(
                    "Elemento de tierra: convierte el suelo a su paso",
                    "El loro rojo la cura mientras le cante",
                    "FASE II: el loro se suelta, ataca en picado y te AMARRA",
                    "FASE III: bandadas de loros que revientan al caer",
                    "Al morir, el Cantor llora: 12 s para salir de su circulo",
                    "Lo que convierte SE QUEDA convertido");
        }

        @Override
        public double baseHealth() {
            return 1600;
        }

        @Override
        public int arenaRadius() {
            return 22;
        }

        @Override
        public List<Ability> abilities() {
            return herbolaAbilities();
        }

        @Override
        public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
            return new Herbola(plugin, event, where);
        }
    }

    /** Las 14 habilidades de Herbola. */
    public List<Ability> herbolaAbilities() {
        List<Ability> list = new ArrayList<>();

        add(list, "manto", "Manto de Musgo", 1, 240, 80, 4,
                "Convierte un circulo entero en musgo y castiga a quien lo pise.",
                icon("MOSS_BLOCK", "MOSS_CARPET"), f -> herbola(f).mossMantle());
        add(list, "raices", "Raices", 1, 220, 90, 4,
                "El suelo agarra por los pies a cada uno donde este.",
                icon("HANGING_ROOTS", "ROOTED_DIRT"), f -> herbola(f).roots());
        add(list, "esporas", "Esporas", 1, 300, 140, 3,
                "Una nube que envenena y marea durante siete segundos.",
                icon("SPORE_BLOSSOM", "BROWN_MUSHROOM"), f -> herbola(f).spores());
        add(list, "floracion", "Floracion", 1, 260, 80, 4,
                "La azalea revienta desde abajo en anillos de once bloques.",
                icon("FLOWERING_AZALEA", "AZALEA"), f -> herbola(f).bloom());
        add(list, "canto", "Canto del Loro", 1, 400, 120, 3,
                "El loro le canta: regeneracion, resistencia y velocidad.",
                icon("NOTE_BLOCK", "JUKEBOX"), f -> herbola(f).parrotSong());
        add(list, "latigo_liana", "Latigo de Liana", 1, 170, 55, 5,
                "Una liana que barre catorce bloques, arrastra y amarra.",
                icon("VINE", "TWISTING_VINES"), f -> herbola(f).vineWhip());

        add(list, "picado_loro", "Picado del Loro", 2, 260, 170, 5,
                "El loro encadena dos picados sobre jugadores distintos y los amarra al suelo.",
                icon("FEATHER", "RED_DYE"), f -> herbola(f).parrotDive());
        add(list, "zarzal", "Zarzal", 2, 380, 140, 3,
                "Un cerco de espinos que se cierra; fuera del claro se pierde vida.",
                icon("SWEET_BERRIES", "SWEET_BERRY_BUSH"), f -> herbola(f).bramble());
        add(list, "polen", "Polen Cegador", 2, 260, 90, 4,
                "Una nube dorada que no deja ver ni correr.",
                icon("PINK_PETALS", "DANDELION"), f -> herbola(f).pollen());
        add(list, "bosque", "Bosque Subito", 2, 320, 110, 3,
                "Brotan seis arboles de azalea que revientan el suelo al salir.",
                icon("AZALEA", "OAK_SAPLING"), f -> herbola(f).suddenForest());

        add(list, "bandada_explosiva", "Bandada Explosiva", 3, 300, 130, 5,
                "Loros que suben, se tiran en picado y revientan al tocar el suelo.",
                icon("RED_DYE", "FIREWORK_ROCKET"), f -> herbola(f).explosiveFlock());
        add(list, "savia", "Savia Corrosiva", 3, 280, 140, 4,
                "Charcos de savia bajo cada uno que queman y envenenan.",
                icon("HONEY_BOTTLE", "SLIME_BALL"), f -> herbola(f).corrosiveSap());
        add(list, "raiz_madre", "Raiz Madre", 3, 360, 110, 4,
                "Una raiz enorme sale del suelo y revienta ocho bloques a la redonda.",
                icon("BIG_DRIPLEAF", "ROOTED_DIRT"), f -> herbola(f).motherRoot());

        add(list, "siembra", "Siembra", 0, 300, 170, 3,
                "Deja ocho semillas que brotan y agarran a quien pase por encima.",
                icon("WHEAT_SEEDS", "BONE_MEAL"), f -> herbola(f).sowing());

        return list;
    }

    private static Herbola herbola(BossFight fight) {
        return (Herbola) fight;
    }

    // --------------------------------------------------------------------- Quimera

    /** Ficha de la Quimera. */
    public final class QuimeraType implements AnomalyType {

        @Override
        public String id() {
            return Quimera.ID;
        }

        @Override
        public String display() {
            return plugin.getConfig().getString("anomalias." + id() + ".nombre", "Quimera");
        }

        @Override
        public TextColor color() {
            return Quimera.ACCENT;
        }

        @Override
        public NamedTextColor glowColor() {
            return NamedTextColor.GOLD;
        }

        @Override
        public Element element() {
            return Element.TIERRA;
        }

        @Override
        public Material icon() {
            Material m = Material.matchMaterial("RAVAGER_SPAWN_EGG");
            if (m == null) m = Material.matchMaterial("CHISELED_STONE_BRICKS");
            return m != null ? m : Material.BONE;
        }

        @Override
        public String tagline() {
            return "Intocable hasta que caigan sus cinco pilares";
        }

        @Override
        public List<String> origin() {
            return List.of(
                    "Tres animales que no deberian compartir cuerpo",
                    "y lo comparten. Nadie sabe si alguien la coso o",
                    "si nacio asi de un mal sueno; lo que si se sabe",
                    "es que las tres cabezas no se ponen de acuerdo",
                    "en nada salvo en lo que hay que hacer contigo.");
        }

        @Override
        public List<String> threat() {
            return List.of(
                    "Elemento de tierra: terreno firme y rocoso",
                    "ES INTOCABLE mientras quede uno de sus cinco pilares",
                    "Solo cede el ladrillo CINCELADO; el resto se derrumba solo",
                    "LA COLA PETRIFICA: no la mires cuando avise",
                    "El ESCUDO levantado la aguanta, como Perseo",
                    "Y los pilares son la unica cobertura: cada uno menos, peor");
        }

        @Override
        public double baseHealth() {
            return 1900;
        }

        @Override
        public int arenaRadius() {
            return 26;
        }

        @Override
        public List<Ability> abilities() {
            return quimeraAbilities();
        }

        @Override
        public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
            return new Quimera(plugin, event, where);
        }
    }

    /**
     * Las 13 habilidades de la Quimera, repartidas entre sus tres animales: la fiera
     * embiste y pisa, la cabra berrea y cornea, y la cola mira, escupe y envenena.
     * Los cinco pilares NO son una habilidad: son la condicion para poder matarla.
     */
    public List<Ability> quimeraAbilities() {
        List<Ability> list = new ArrayList<>();

        // --- Fase I: la fiera
        add(list, "mirada_petrea", "Mirada de la Cola", 1, 260, 60, 4,
                "La serpiente busca tus ojos: quien la mire al final del aviso se queda de piedra.",
                icon("ENDER_EYE", "SPIDER_EYE"), f -> quimera(f).stoneGaze());
        add(list, "embestida_fiera", "Embestida de la Fiera", 1, 200, 55, 5,
                "Baja la cabeza y arrolla en linea recta.",
                icon("RAVAGER_SPAWN_EGG", "IRON_HORSE_ARMOR"), f -> quimera(f).beastCharge());
        add(list, "berrido_cabra", "Berrido de la Cabra", 1, 220, 35, 4,
                "Un grito en cono que empuja y marea.",
                icon("GOAT_HORN", "NOTE_BLOCK"), f -> quimera(f).goatBleat());
        add(list, "nido_viboras", "Nido de Viboras", 1, 520, 60, 2,
                "Del lomo se descuelgan esbirros que muerden con veneno.",
                icon("CAVE_SPIDER_SPAWN_EGG", "SPIDER_EYE"), f -> quimera(f).viperNest());
        add(list, "escupitajo", "Escupitajo Venenoso", 1, 200, 50, 4,
                "La cabra escupe veneno a tres jugadores distintos.",
                icon("SPIDER_EYE", "SLIME_BALL"), f -> quimera(f).venomSpit());

        // --- Fase II: la cabra rabiosa
        add(list, "mirada_barrida", "Mirada en Barrido", 2, 320, 100, 4,
                "El rayo de la mirada recorre la arena girando una vuelta entera.",
                icon("SPYGLASS", "ENDER_EYE"), f -> quimera(f).sweepingGaze());
        add(list, "zarpazo_triple", "Zarpazo Triple", 2, 170, 40, 5,
                "Las tres cabezas pegan seguidas a lo que tenga delante.",
                icon("FLINT", "IRON_SWORD"), f -> quimera(f).tripleMaul());
        add(list, "cornada", "Cornada Ascendente", 2, 200, 25, 4,
                "Engancha con el cuerno a los que tenga pegados y los manda por los aires.",
                icon("GOAT_HORN", "BONE"), f -> quimera(f).upwardGore());
        add(list, "lluvia_colmillos", "Lluvia de Colmillos", 2, 260, 50, 4,
                "Colmillos de piedra que brotan del suelo bajo cada uno.",
                icon("DEEPSLATE", "POINTED_DRIPSTONE"), f -> quimera(f).fangRain());
        add(list, "veneno_ancestral", "Veneno Ancestral", 2, 300, 70, 3,
                "Una onda de veneno viejo que solo pega en el borde.",
                icon("FERMENTED_SPIDER_EYE", "SPIDER_EYE"), f -> quimera(f).ancientVenom());

        // --- Fase III: la serpiente
        add(list, "mirada_entera", "La Mirada Entera", 3, 420, 110, 4,
                "Cinco segundos avisando y luego la arena entera. Escudo, pilar o espalda.",
                icon("ENDER_EYE", "END_CRYSTAL"), f -> quimera(f).fullGaze());
        add(list, "pisoton_fiera", "Pisoton de la Fiera", 3, 280, 70, 4,
                "Se alza y descarga; la onda barre diez bloques.",
                icon("COARSE_DIRT", "DIRT"), f -> quimera(f).beastStomp());

        // --- Cualquier fase
        add(list, "siseo", "Siseo", 0, 260, 40, 3,
                "Un cono de siseo que marea, frena y empuja.",
                icon("SCULK_SENSOR", "NOTE_BLOCK"), f -> quimera(f).hiss());

        return list;
    }

    private static Quimera quimera(BossFight fight) {
        return (Quimera) fight;
    }

    // ---------------------------------------------------------------------- Bruja

    /** Ficha de la Bruja. */
    public final class BrujaType implements AnomalyType {

        @Override
        public String id() {
            return Bruja.ID;
        }

        @Override
        public String display() {
            return plugin.getConfig().getString("anomalias." + id() + ".nombre", "Bruja");
        }

        @Override
        public TextColor color() {
            return Bruja.ACCENT;
        }

        @Override
        public NamedTextColor glowColor() {
            return NamedTextColor.GOLD;
        }

        @Override
        public Element element() {
            return Element.TIERRA;
        }

        @Override
        public Material icon() {
            Material m = Material.matchMaterial("CAULDRON");
            return m != null ? m : Material.BREWING_STAND;
        }

        @Override
        public String tagline() {
            return "Caldero en la cabeza y un sapo blanco al hombro";
        }

        @Override
        public List<String> origin() {
            return List.of(
                    "Vivia en una choza que nadie encontraba dos",
                    "veces, cocinando cosas que era mejor no oler.",
                    "Un dia el caldero le hablo, y desde entonces",
                    "lo lleva puesto. El sapo opina que fue al reves:",
                    "que el caldero la lleva puesta a ella.");
        }

        @Override
        public List<String> threat() {
            return List.of(
                    "Elemento de tierra: campo abierto para su aquelarre",
                    "El SAPO BLANCO la protege en fase 1; en fase 2 BAJA y pelea",
                    "Matarle el sapo la desata: pega mas y corre mas",
                    "Pocimas, maleficios y un Gran Hechizo con cuenta atras");
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
            return brujaAbilities();
        }

        @Override
        public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
            return new Bruja(plugin, event, where);
        }
    }

    /**
     * Las 17 habilidades de la Bruja. En la fase 1 el sapo es su escudo; en la 2 es
     * su espada; y la 3 es el caldero vaciandose encima de todo el mundo.
     */
    public List<Ability> brujaAbilities() {
        List<Ability> list = new ArrayList<>();

        // --- Fase I: el aquelarre
        add(list, "pocima_virulenta", "Pocima Virulenta", 1, 200, 50, 5,
                "Tres frascos por los aires, cada uno a una marca distinta.",
                icon("SPLASH_POTION", "POTION"), f -> bruja(f).virulentBrew());
        add(list, "caldero_hirviente", "Caldero Hirviente", 1, 300, 140, 3,
                "El caldero rebosa y deja charcos de brebaje que queman al pisarlos.",
                icon("CAULDRON", "BUCKET"), f -> bruja(f).boilingCauldron());
        add(list, "maleficio", "Maleficio", 1, 340, 160, 3,
                "Mal de ojo a tres a la vez; los va royendo ocho segundos.",
                icon("FERMENTED_SPIDER_EYE", "SPIDER_EYE"), f -> bruja(f).hex());
        add(list, "canto_sapo", "Canto del Sapo", 1, 400, 120, 3,
                "El sapo le croa desde el hombro: regeneracion y resistencia.",
                icon("LILY_PAD", "SLIME_BALL"), f -> bruja(f).toadSong());
        add(list, "risa_bruja", "Risa de Bruja", 1, 220, 40, 4,
                "Una carcajada en cono que marea y empuja.",
                icon("NOTE_BLOCK", "JUKEBOX"), f -> bruja(f).witchCackle());
        add(list, "hervor_subito", "Hervor Subito", 1, 260, 60, 4,
                "Una ola de brebaje hirviendo que solo pega en el borde.",
                icon("MAGMA_CREAM", "BLAZE_POWDER"), f -> bruja(f).suddenBoil());

        // --- Fase II: el sapo baja
        add(list, "salto_sapo", "Salto del Sapo", 2, 240, 60, 5,
                "El Sapo de Guerra toma carrerilla y se lanza sobre una marca.",
                icon("SLIME_BLOCK", "SLIME_BALL"), f -> bruja(f).toadSlam());
        add(list, "lengua_latigo", "Lengua Latigo", 2, 260, 100, 4,
                "La lengua engancha uno tras otro a los dos que mas se alejan.",
                icon("LEAD", "STRING"), f -> bruja(f).tongueWhip());
        add(list, "lluvia_sapos", "Lluvia de Sapos", 2, 320, 90, 3,
                "Sapos pequenos que caen del cielo y revientan en veneno.",
                icon("FROGSPAWN", "SLIME_BALL"), f -> bruja(f).toadRain());
        add(list, "brebaje_oscuro", "Brebaje Oscuro", 2, 300, 140, 3,
                "Una nube negra que ciega y envenena a quien se quede dentro.",
                icon("INK_SAC", "BLACK_DYE"), f -> bruja(f).darkBrew());

        // --- Fase III: el caldero rebosa
        add(list, "gran_hechizo", "El Gran Hechizo", 3, 460, 210, 4,
                "Un circulo enorme y diez segundos de cuenta atras. Adentro, nadie.",
                icon("ENCHANTING_TABLE", "BOOK"), f -> bruja(f).grandSpell());
        add(list, "nube_murcielagos", "Nube de Murcielagos", 3, 340, 170, 3,
                "Una bandada que persigue a cada uno y no deja ver.",
                icon("BAT_SPAWN_EGG", "COAL"), f -> bruja(f).batCloud());
        add(list, "pocima_final", "Pocima Final", 3, 320, 110, 4,
                "Marcas bajo todos, tres tandas seguidas, efectos al azar.",
                icon("LINGERING_POTION", "SPLASH_POTION"), f -> bruja(f).finalBrew());

        // --- Magia de verdad, sin frasco de por medio
        add(list, "rayo_arcano", "Rayo Arcano", 1, 200, 45, 5,
                "Un haz que salta de uno a otro encadenando a todo el grupo.",
                icon("BREEZE_ROD", "BLAZE_ROD"), f -> bruja(f).arcaneBolt());
        add(list, "circulo_runas", "Circulo de Runas", 2, 320, 165, 4,
                "Dibuja runas en el suelo y lo que quede dentro se marchita.",
                icon("ENCHANTING_TABLE", "BOOKSHELF"), f -> bruja(f).runeCircle());
        add(list, "mano_bruja", "Mano de Bruja", 3, 260, 75, 5,
                "Agarra con magia a los que tenga cerca, los levanta y los suelta.",
                icon("AMETHYST_SHARD", "ECHO_SHARD"), f -> bruja(f).witchHand());

        // --- Cualquier fase
        add(list, "trago_amargo", "Trago Amargo", 0, 360, 45, 2,
                "Bebe de su propio caldero: aguanta mas un rato y suelta el eructo.",
                icon("GLASS_BOTTLE", "HONEY_BOTTLE"), f -> bruja(f).bitterSip());

        return list;
    }

    private static Bruja bruja(BossFight fight) {
        return (Bruja) fight;
    }

    // ---------------------------------------------------------------------- Mimic

    /** Ficha del Mimic. */
    public final class MimicType implements AnomalyType {

        @Override
        public String id() {
            return Mimic.ID;
        }

        @Override
        public String display() {
            return plugin.getConfig().getString("anomalias." + id() + ".nombre", "Mimic");
        }

        @Override
        public TextColor color() {
            return Mimic.ACCENT;
        }

        /** Sin brillo ni pilar a proposito: el camuflaje es el jefe entero. */
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
            Material m = Material.matchMaterial("CHEST");
            return m != null ? m : Material.BARREL;
        }

        @Override
        public String tagline() {
            return "Uno de esos animales no es un animal";
        }

        @Override
        public List<String> origin() {
            return List.of(
                    "Nadie lo ha visto nunca, y ese es el problema:",
                    "todo el mundo lo ha visto. Era la vaca del",
                    "vecino, el cofre del tesoro, aquel viajero tan",
                    "amable. Cuando la grieta se abrio, ni siquiera",
                    "salio nada... o eso parecio.");
        }

        @Override
        public List<String> threat() {
            return List.of(
                    "Elemento de tierra: aparece entre la fauna del bioma",
                    "NO brilla, NO lleva nombre: hay que pegarle para saber",
                    "FASE II: escondite en cofres; los falsos MUERDEN",
                    "La Codicia hace dano a todos, y crece con el tiempo",
                    "FASE III: se copia a un jugador (cara, armadura, armas)",
                    "A mitad de la fase SE DESATA: berserker puro");
        }

        @Override
        public double baseHealth() {
            return 2000;
        }

        @Override
        public int arenaRadius() {
            return 24;
        }

        @Override
        public List<Ability> abilities() {
            return mimicAbilities();
        }

        @Override
        public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
            return new Mimic(plugin, event, where);
        }
    }

    /**
     * Las 12 habilidades del Mimic. Las de la fase 1 solo funcionan destapado; las de
     * la 2 giran alrededor de los cofres; las de la 3 pegan mas si ya se desato.
     */
    public List<Ability> mimicAbilities() {
        List<Ability> list = new ArrayList<>();

        // --- Fase I: el rebano
        add(list, "camuflaje", "Camuflaje", 1, 400, 40, 3,
                "Destello, cuerpo nuevo y rebano nuevo: vuelve a no ser nadie.",
                icon("ENDER_PEARL", "SNOWBALL"), f -> mimic(f).camouflageCast());
        add(list, "embestida_salvaje", "Embestida Salvaje", 1, 180, 50, 5,
                "Marca una linea y la cruza arrollando. Solo destapado.",
                icon("LEAD", "SADDLE"), f -> mimic(f).wildCharge());
        add(list, "pisoton_creciente", "Pisoton Creciente", 1, 220, 60, 4,
                "Tres ondas desde donde pisa, cada una mas ancha que la anterior.",
                icon("COARSE_DIRT", "DIRT"), f -> mimic(f).growingStomp());
        add(list, "chillido_bestial", "Chillido Bestial", 1, 240, 30, 4,
                "El grito del animal de turno, en cono y con una voz que no es suya.",
                icon("NOTE_BLOCK", "GOAT_HORN"), f -> mimic(f).beastShriek());
        add(list, "estampida_senuelos", "Estampida de Senuelos", 1, 380, 60, 2,
                "El rebano entero embiste a la vez; los senuelos tambien empujan.",
                icon("WHEAT", "HAY_BLOCK"), f -> mimic(f).decoyStampede());

        // --- Fase II: los cofres
        add(list, "ronda_cofres", "Ronda de Cofres", 2, 400, 60, 3,
                "Cinco cofres en circulo y el dentro de uno. La barra delata al verdadero.",
                icon("CHEST", "BARREL"), f -> mimic(f).chestRound());
        add(list, "dentellada", "Dentellada", 2, 120, 20, 5,
                "El cofre del jefe pega un bocado a quien se arrima de mas.",
                icon("BONE", "FLINT"), f -> mimic(f).chestBite());

        // --- Fase III: el robo de rostro
        add(list, "tajo_ladron", "Tajo Ladron", 3, 160, 30, 5,
                "Se lanza y el corte roba la prisa de todos los que pille en el tajo.",
                icon("IRON_SWORD", "STONE_SWORD"), f -> mimic(f).thiefSlash());
        add(list, "sombra_del_otro", "Sombra del Otro", 3, 240, 40, 4,
                "Aparece detras del jugador al que copio y descarga.",
                icon("ENDER_PEARL", "ENDER_EYE"), f -> mimic(f).othersShadow());
        add(list, "frenesi_carnicero", "Frenesi Carnicero", 3, 260, 60, 4,
                "Una tanda que barre a todos los que tenga pegados; desatado, casi el doble.",
                icon("IRON_AXE", "STONE_AXE"), f -> mimic(f).butcherFrenzy());
        add(list, "salto_carnicero", "Salto Carnicero", 3, 300, 60, 4,
                "Salta muy alto y cae de lleno sobre la marca.",
                icon("NETHERITE_BOOTS", "IRON_BOOTS"), f -> mimic(f).butcherLeap());
        add(list, "torbellino_acero", "Torbellino de Acero", 3, 280, 70, 4,
                "Gira repartiendo tajos en tres ondas concentricas.",
                icon("SHEARS", "IRON_SWORD"), f -> mimic(f).steelWhirlwind());

        return list;
    }

    private static Mimic mimic(BossFight fight) {
        return (Mimic) fight;
    }

    // ---------------------------------------------------------------------- Rabby

    /** Ficha de Rabby. */
    public final class RabbyType implements AnomalyType {

        @Override
        public String id() {
            return Rabby.ID;
        }

        @Override
        public String display() {
            return plugin.getConfig().getString("anomalias." + id() + ".nombre", "Rabby");
        }

        @Override
        public TextColor color() {
            return Rabby.ACCENT;
        }

        /**
         * Sin brillo: mientras esta tranquilo es un vecino cualquiera. El unico brillo
         * que tiene es el BLANCO de la concentracion, y se lo pone el solo.
         */
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
            Material m = Material.matchMaterial("PLAYER_HEAD");
            return m != null ? m : Material.GOLDEN_APPLE;
        }

        /** En el menu sale SU cabeza, con la skin puesta, no una cabeza en blanco. */
        @Override
        public org.bukkit.inventory.ItemStack iconItem() {
            return Disguises.head(plugin, Rabby.SKIN, "Rabby");
        }

        /** Y en el arbol de logros, lo mismo. */
        @Override
        public String iconComponentsJson() {
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/"
                    + Rabby.SKIN + "\"}}}";
            String value = java.util.Base64.getEncoder()
                    .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "{\"minecraft:profile\": {\"properties\": [{\"name\": \"textures\", \"value\": \""
                    + value + "\"}]}}";
        }

        @Override
        public String tagline() {
            return "Parece inofensivo. No le pegues";
        }

        @Override
        public List<String> origin() {
            return List.of(
                    "No se sabe de donde vino ni por que se quedo.",
                    "Saluda, se aparta para dejarte pasar y no ha",
                    "hecho nunca nada a nadie. Los que le levantaron",
                    "la mano tampoco lo han contado, asi que la parte",
                    "importante de la historia sigue sin escribirse.");
        }

        @Override
        public List<String> threat() {
            return List.of(
                    "Elemento de tierra: campo abierto para correr",
                    "PASIVO hasta que alguien le pega. Y entonces no para",
                    "Batea al cielo, se teletransporta y remata contra el suelo",
                    "CONCENTRACION: brillo blanco, un rayo y CINCO VECES el dano",
                    "Su carga devastadora revienta a quien no lleve buen equipo");
        }

        @Override
        public double baseHealth() {
            return 2400;
        }

        @Override
        public int arenaRadius() {
            return 30;
        }

        @Override
        public List<Ability> abilities() {
            return rabbyAbilities();
        }

        @Override
        public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
            return new Rabby(plugin, event, where);
        }
    }

    /**
     * Las 14 habilidades de Rabby. Casi todas mueven a alguien de sitio: a el, a ti, o
     * a los dos. Ninguna funciona mientras siga tranquilo.
     */
    public List<Ability> rabbyAbilities() {
        List<Ability> list = new ArrayList<>();

        // --- Fase I: los puños
        add(list, "carrera_fantasma", "Carrera Fantasma", 1, 170, 40, 5,
                "Cruza la arena tan rapido que deja estela, y se lleva por delante a quien pille.",
                icon("FEATHER", "SUGAR"), f -> rabby(f).ghostRun());
        add(list, "batazo", "Batazo", 1, 200, 30, 5,
                "Un solo swing que manda al cielo a todos los que pille en el arco.",
                icon("MACE", "STICK"), f -> rabby(f).homeRun());
        add(list, "pisoton_sonico", "Pisoton Sonico", 1, 260, 70, 4,
                "Salta muy alto y revienta el suelo; la onda saca volando a todos.",
                icon("HEAVY_CORE", "ANVIL"), f -> rabby(f).sonicStomp());
        add(list, "rafaga_golpes", "Rafaga de Golpes", 1, 150, 50, 5,
                "Una tanda de puñetazos a quien tenga delante; concentrado, el doble.",
                icon("IRON_INGOT", "STICK"), f -> rabby(f).punchFlurry());

        // --- Fase II: los combos
        add(list, "combo_aereo", "Combo Aereo", 2, 320, 130, 5,
                "Batea al cielo, se teletransporta arriba, remata y lo clava contra el suelo.",
                icon("NETHERITE_SWORD", "IRON_SWORD"), f -> rabby(f).airCombo());
        add(list, "acoso_relampago", "Acoso Relampago", 2, 240, 60, 4,
                "Se teletransporta a la espalda de cuatro y pega en cada parada.",
                icon("ENDER_PEARL", "CHORUS_FRUIT"), f -> rabby(f).blinkHarass());
        add(list, "puno_cometa", "Puño Cometa", 2, 280, 80, 4,
                "Sube hasta perderse y cae de puño donde mas gente hay junta.",
                icon("FIRE_CHARGE", "MAGMA_CREAM"), f -> rabby(f).cometFist());
        add(list, "patada_giratoria", "Patada Giratoria", 2, 200, 30, 4,
                "Gira sobre si mismo y saca de la arena todo lo que tenga cerca.",
                icon("NETHERITE_BOOTS", "IRON_BOOTS"), f -> rabby(f).spinKick());

        // --- Fase III: se acabo
        add(list, "concentracion", "Concentracion", 3, 600, 40, 4,
                "Un rayo, brillo blanco y CINCO VECES el dano durante quince segundos.",
                icon("NETHER_STAR", "GLOWSTONE_DUST"), f -> rabby(f).concentration());
        add(list, "carga_devastadora", "Carga Devastadora", 3, 520, 190, 4,
                "Se traga las estelas de media arena y lo suelta todo de golpe.",
                icon("DRAGON_BREATH", "END_CRYSTAL"), f -> rabby(f).devastatingCharge());
        add(list, "onda_expansiva", "Onda Expansiva", 3, 240, 70, 4,
                "Un puñetazo al suelo que barre dieciseis bloques a la redonda.",
                icon("MACE", "IRON_BLOCK"), f -> rabby(f).shockwave());
        add(list, "tromba_final", "Tromba Final", 3, 300, 90, 4,
                "Tres embestidas seguidas por toda la arena, sin respirar entre ellas.",
                icon("BLAZE_POWDER", "REDSTONE"), f -> rabby(f).finalRush());

        // --- Cualquier fase
        add(list, "paso_relampago", "Paso Relampago", 0, 220, 30, 3,
                "Aparece detras del que mas se aleja y lo devuelve al grupo de un golpe.",
                icon("ENDER_EYE", "ENDER_PEARL"), f -> rabby(f).lightningStep());
        add(list, "burla", "Burla", 0, 340, 30, 2,
                "Se rie, se estira y se pone todavia mas rapido.",
                icon("NOTE_BLOCK", "JUKEBOX"), f -> rabby(f).taunt());

        return list;
    }

    private static Rabby rabby(BossFight fight) {
        return (Rabby) fight;
    }

    // ------------------------------------------------------------------ El Cazador

    /** Ficha de El Cazador. */
    public final class CazadorType implements AnomalyType {

        @Override
        public String id() {
            return Cazador.ID;
        }

        @Override
        public String display() {
            return plugin.getConfig().getString("anomalias." + id() + ".nombre", "El Cazador");
        }

        @Override
        public TextColor color() {
            return Cazador.ACCENT;
        }

        @Override
        public NamedTextColor glowColor() {
            return NamedTextColor.DARK_RED;
        }

        @Override
        public Element element() {
            return Element.TIERRA;
        }

        @Override
        public Material icon() {
            Material m = Material.matchMaterial("CROSSBOW");
            return m != null ? m : Material.BOW;
        }

        @Override
        public String tagline() {
            return "Mira lo que lleva en la mano";
        }

        @Override
        public List<String> origin() {
            return List.of(
                    "Cazaba en el Nether cosas que no dejan rastro,",
                    "y aprendio que el arma correcta importa mas que",
                    "la fuerza. Cuando la grieta se abrio no vino a",
                    "pelear: vino a cazar, que no es lo mismo, y por",
                    "eso te esta esperando en vez de buscarte.");
        }

        @Override
        public List<String> threat() {
            return List.of(
                    "Elemento de tierra: campo abierto donde tenderte trampas",
                    "CAMBIA DE ARMA constantemente, y el arma dice lo que hara",
                    "FASE I: ballesta y arco; retrocede si te acercas",
                    "FASE II: siembra el suelo de TRAMPAS que revientan en area",
                    "FASE III: tira lo de lejos y saca la lanza de netherita");
        }

        @Override
        public double baseHealth() {
            return 2100;
        }

        @Override
        public int arenaRadius() {
            return 28;
        }

        @Override
        public List<Ability> abilities() {
            return cazadorAbilities();
        }

        @Override
        public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
            return new Cazador(plugin, event, where);
        }
    }

    /**
     * Las 12 habilidades de El Cazador. Cada una le cambia el arma de la mano, y esa
     * es la lectura del jefe: si saca el hacha, es que viene.
     */
    public List<Ability> cazadorAbilities() {
        List<Ability> list = new ArrayList<>();

        // --- Fase I: el acecho
        add(list, "andanada_ballesta", "Andanada de Ballesta", 1, 90, 32, 6,
                "Seis saetas repartidas entre todos los que tenga a tiro.",
                icon("CROSSBOW", "BOW"), f -> cazador(f).crossbowVolley());
        add(list, "lluvia_flechas", "Lluvia de Flechas", 1, 150, 55, 5,
                "Apunta al cielo y caen sobre las marcas.",
                icon("ARROW", "BOW"), f -> cazador(f).arrowRain());
        add(list, "saeta_perforante", "Saeta Perforante", 1, 130, 40, 5,
                "Un disparo cargado que atraviesa a todo el que pille en linea.",
                icon("SPECTRAL_ARROW", "ARROW"), f -> cazador(f).piercingBolt());
        add(list, "marcar_presa", "Marcar la Presa", 1, 260, 30, 3,
                "Elige a uno y le pega mucho mas fuerte mientras dure la marca.",
                icon("TARGET", "REDSTONE"), f -> cazador(f).markPrey());

        // --- Fase II: el cepo
        add(list, "cepo", "Cepo", 2, 150, 30, 5,
                "Siembra el suelo de trampas: revientan al pisarlas y, si no, solas a los 20 s.",
                icon("TRIPWIRE_HOOK", "STRING"), f -> cazador(f).trapField());
        add(list, "cepo_dirigido", "Cepo Dirigido", 2, 130, 22, 5,
                "Pone la trampa justo bajo los pies de cada uno.",
                icon("HEAVY_WEIGHTED_PRESSURE_PLATE", "STONE_PRESSURE_PLATE"),
                f -> cazador(f).aimedTrap());
        add(list, "red_cepos", "Red de Cepos", 2, 240, 40, 3,
                "Un cerco entero de trampas alrededor del grupo.",
                icon("CHAIN", "IRON_BARS"), f -> cazador(f).trapRing());
        add(list, "retirada", "Retirada Calculada", 2, 120, 18, 4,
                "Salta hacia atras y deja una trampa donde estaba.",
                icon("FEATHER", "LEATHER_BOOTS"), f -> cazador(f).calculatedRetreat());

        // --- Fase III: la estocada
        add(list, "estocada_lanza", "Estocada de Lanza", 0, 120, 38, 6,
                "La lanza por delante, en linea y con mucho alcance. La usa en cualquier fase.",
                icon("NETHERITE_SPEAR", "TRIDENT"), f -> cazador(f).spearThrust());
        add(list, "hachazo", "Hachazo Descendente", 3, 130, 35, 5,
                "Cambia al hacha y parte el suelo en linea recta.",
                icon("NETHERITE_AXE", "IRON_AXE"), f -> cazador(f).axeCleave());
        add(list, "danza_espada", "Danza de Espada", 3, 120, 35, 5,
                "Saca la espada y la tanda barre a todos los que tenga pegados.",
                icon("NETHERITE_SWORD", "IRON_SWORD"), f -> cazador(f).bladeDance());

        // --- Cualquier fase
        add(list, "cambio_arma", "Cambio de Arma", 0, 160, 14, 3,
                "Se replantea la pelea y saca otra cosa del cinto.",
                icon("SMITHING_TABLE", "ANVIL"), f -> cazador(f).switchWeapon());

        return list;
    }

    private static Cazador cazador(BossFight fight) {
        return (Cazador) fight;
    }

    // ---------------------------------------------------------------------- Áragon

    /** Ficha de Áragon. */
    public final class AragonType implements AnomalyType {

        @Override
        public String id() {
            return Aragon.ID;
        }

        @Override
        public String display() {
            return plugin.getConfig().getString("anomalias." + id() + ".nombre", "Áragon");
        }

        @Override
        public TextColor color() {
            return Aragon.ACCENT;
        }

        @Override
        public NamedTextColor glowColor() {
            return NamedTextColor.DARK_PURPLE;
        }

        @Override
        public Element element() {
            return Element.TIERRA;
        }

        @Override
        public Material icon() {
            Material m = Material.matchMaterial("COBWEB");
            return m != null ? m : Material.STRING;
        }

        @Override
        public String tagline() {
            return "Ella no corre; corren sus hijas, y son muchas";
        }

        @Override
        public List<String> origin() {
            return List.of(
                    "Llevaba tanto tiempo en el mismo sitio que el",
                    "sitio se hizo a su forma. No caza: espera, teje",
                    "y pone. Todo lo que se le acerca acaba siendo",
                    "comida para una camada que nunca deja de crecer.");
        }

        @Override
        public List<String> threat() {
            return List.of(
                    "Elemento de tierra: cuevas abiertas y campo seco",
                    "ES LENTISIMA: quien te muerde son sus crias",
                    "CRIAS DIMINUTAS Y A MONTONES, hasta sesenta a la vez",
                    "Sus HUEVOS eclosionan si no los rompes a tiempo",
                    "Teje telaraña de verdad; se devuelve al terminar");
        }

        @Override
        public double baseHealth() {
            return 2300;
        }

        @Override
        public int arenaRadius() {
            return 28;
        }

        @Override
        public List<Ability> abilities() {
            return aragonAbilities();
        }

        @Override
        public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
            return new Aragon(plugin, event, where);
        }
    }

    /**
     * Las 10 habilidades de Áragon. Casi todas son poner mas bichos o mas tela: ella
     * pega poco y muy de tarde en tarde, y eso es exactamente el diseño.
     */
    public List<Ability> aragonAbilities() {
        List<Ability> list = new ArrayList<>();

        add(list, "camada", "Camada", 1, 220, 30, 5,
                "Doce crias de golpe, de varios tamanos, corriendo mas de lo que se puede retroceder.",
                icon("SPIDER_SPAWN_EGG", "STRING"), f -> aragon(f).spawnBrood());
        add(list, "puesta", "Puesta", 1, 320, 40, 4,
                "Pone huevos por la arena. Si no se rompen, eclosionan.",
                icon("WHITE_CONCRETE", "SNOWBALL"), f -> aragon(f).eggClutch());
        add(list, "telar", "Telar", 1, 240, 40, 4,
                "Teje una maraña de telaraña alrededor de cada uno.",
                icon("COBWEB", "STRING"), f -> aragon(f).weave());
        add(list, "hilo", "Hilo", 1, 200, 55, 4,
                "Dos hilos a la vez: los que mas se alejan vuelven arrastrados.",
                icon("STRING", "LEAD"), f -> aragon(f).webPull());

        add(list, "guardianas", "Guardianas", 2, 400, 30, 3,
                "Dos aranas grandes que si pegan de verdad.",
                icon("SPIDER_EYE", "SPIDER_SPAWN_EGG"), f -> aragon(f).summonGuardians());
        add(list, "mordisco_madre", "Mordisco de la Madre", 2, 260, 55, 4,
                "Lento, avisado y brutal si te pilla debajo.",
                icon("FERMENTED_SPIDER_EYE", "SPIDER_EYE"), f -> aragon(f).motherBite());
        add(list, "cortina_tela", "Cortina de Tela", 2, 380, 40, 3,
                "Un cerco de telaraña que encierra al grupo con ella.",
                icon("COBWEB", "WHITE_WOOL"), f -> aragon(f).webWall());

        add(list, "marea_crias", "Marea de Crias", 3, 300, 80, 5,
                "Tres camadas seguidas por toda la arena.",
                icon("SPIDER_SPAWN_EGG", "EGG"), f -> aragon(f).broodTide());
        add(list, "veneno_nido", "Veneno de Nido", 3, 280, 130, 4,
                "Charcos pegajosos que envenenan y frenan donde caen.",
                icon("SLIME_BALL", "SPIDER_EYE"), f -> aragon(f).nestVenom());
        add(list, "sacudida", "Sacudida", 3, 260, 65, 4,
                "Se alza sobre las patas y las baja de golpe; la onda barre nueve bloques.",
                icon("COARSE_DIRT", "DIRT"), f -> aragon(f).legSlam());

        return list;
    }

    private static Aragon aragon(BossFight fight) {
        return (Aragon) fight;
    }

    // ------------------------------------------------------------------ El Piromante

    /** Ficha de El Piromante. */
    public final class PiromanteType implements AnomalyType {

        @Override
        public String id() {
            return Piromante.ID;
        }

        @Override
        public String display() {
            return plugin.getConfig().getString("anomalias." + id() + ".nombre", "El Piromante");
        }

        @Override
        public TextColor color() {
            return Piromante.ACCENT;
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
            Material m = Material.matchMaterial("BLAZE_ROD");
            return m != null ? m : Material.FIRE_CHARGE;
        }

        @Override
        public String tagline() {
            return "Todo lo que hace sale ardiendo, y ademas te persigue";
        }

        @Override
        public List<String> origin() {
            return List.of(
                    "Era el armero de una aldea del desierto y se paso",
                    "media vida delante de una fragua. Un dia el fuego",
                    "le respondio, y desde entonces no ha vuelto a",
                    "apagar nada: lo suyo ya no es forjar, es prender.");
        }

        @Override
        public List<String> threat() {
            return List.of(
                    "Elemento de tierra: terreno abierto que pueda arder",
                    "TODO a distancia y TODO fuego; no tiene cuerpo a cuerpo",
                    "PERSIGUE: quedarse lejos no sirve de nada",
                    "QUEMA EL SUELO de verdad, con muy buen radio",
                    "Nunca prende dentro de terreno protegido, y lo apaga al irse");
        }

        @Override
        public double baseHealth() {
            return 1900;
        }

        @Override
        public int arenaRadius() {
            return 26;
        }

        @Override
        public List<Ability> abilities() {
            return piromanteAbilities();
        }

        @Override
        public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
            return new Piromante(plugin, event, where);
        }
    }

    /** Las 12 habilidades del Piromante. Todas arden, y casi todas cubren area. */
    public List<Ability> piromanteAbilities() {
        List<Ability> list = new ArrayList<>();

        // --- Fase I: la chispa
        add(list, "bola_fuego", "Bola de Fuego", 1, 170, 40, 5,
                "Hasta tres bolas a la vez, una por cabeza, cargadas y con aviso.",
                icon("FIRE_CHARGE", "BLAZE_POWDER"), f -> piromante(f).fireball());
        add(list, "andanada_brasas", "Andanada de Brasas", 1, 200, 35, 5,
                "Seis bolas pequenas en abanico.",
                icon("BLAZE_POWDER", "GUNPOWDER"), f -> piromante(f).emberVolley());
        add(list, "aliento", "Aliento de Fuego", 1, 220, 55, 4,
                "Un cono de fuego largo delante de el.",
                icon("CAMPFIRE", "TORCH"), f -> piromante(f).flameBreath());
        add(list, "rastro_brasas", "Rastro de Brasas", 1, 300, 170, 3,
                "Por donde pisa deja el suelo ardiendo un buen rato.",
                icon("MAGMA_BLOCK", "NETHERRACK"), f -> piromante(f).emberTrail());

        // --- Fase II: la hoguera
        add(list, "mar_llamas", "Mar de Llamas", 2, 300, 75, 4,
                "Un circulo enorme de suelo ardiendo que se abre desde el.",
                icon("LAVA_BUCKET", "FIRE_CHARGE"), f -> piromante(f).seaOfFlames());
        add(list, "meteoros", "Meteoros", 2, 320, 110, 4,
                "Bolas que caen del cielo sobre las marcas.",
                icon("FIRE_CHARGE", "MAGMA_CREAM"), f -> piromante(f).meteorShower());
        add(list, "muro_fuego", "Muro de Fuego", 2, 280, 65, 4,
                "Una pared de llamas que avanza y no se puede cruzar de frente.",
                icon("CAMPFIRE", "SOUL_CAMPFIRE"), f -> piromante(f).fireWall());
        add(list, "guardia_blazes", "Guardia de Brasas", 2, 420, 30, 3,
                "Llama a dos blazes que hostigan por su cuenta.",
                icon("BLAZE_SPAWN_EGG", "BLAZE_ROD"), f -> piromante(f).blazeGuard());

        // --- Fase III: el infierno
        add(list, "nova_ignea", "Nova Ignea", 3, 460, 120, 4,
                "Se enciende entero y revienta en catorce bloques a la redonda.",
                icon("FIRE_CHARGE", "NETHER_STAR"), f -> piromante(f).igniteNova());
        add(list, "columna_lava", "Columna de Lava", 3, 260, 60, 4,
                "Una columna que sube y revienta bajo cada uno.",
                icon("MAGMA_BLOCK", "LAVA_BUCKET"), f -> piromante(f).lavaPillar());
        add(list, "anillo_cenizas", "Anillo de Cenizas", 3, 300, 85, 4,
                "Dos anillos que se cruzan: uno sale y otro entra.",
                icon("BLACK_DYE", "CHARCOAL"), f -> piromante(f).ashRings());

        // --- Cualquier fase
        add(list, "marca_ardiente", "Marca Ardiente", 0, 280, 95, 3,
                "Marca a TODOS a la vez; cada marca estalla donde este su dueno.",
                icon("TARGET", "FIRE_CHARGE"), f -> piromante(f).burningMark());

        return list;
    }

    // ------------------------------------------------------------------- KEEPER

    /** Ficha de KEEPER, el Monarca del Silencio. */
    public final class KeeperType implements AnomalyType {

        @Override
        public String id() {
            return Keeper.ID;
        }

        @Override
        public String display() {
            return plugin.getConfig().getString("anomalias." + id() + ".nombre", "KEEPER");
        }

        @Override
        public TextColor color() {
            return Keeper.ACCENT;
        }

        @Override
        public NamedTextColor glowColor() {
            return NamedTextColor.AQUA;
        }

        @Override
        public Element element() {
            return Element.TIERRA;
        }

        @Override
        public Material icon() {
            Material m = Material.matchMaterial("SCULK_SHRIEKER");
            return m != null ? m : Material.ECHO_SHARD;
        }

        @Override
        public String tagline() {
            return "El warden que dejo de ser un guardian";
        }

        @Override
        public net.ederus.edm.anomaly.core.AnomalyClass defaultClass() {
            return net.ederus.edm.anomaly.core.AnomalyClass.MONARCA;
        }

        @Override
        public List<String> origin() {
            return List.of(
                    "Las ciudades del abismo tenian un guardian",
                    "que escuchaba. Escucho tanto que aprendio",
                    "lo que vibra arriba, y decidio subir a",
                    "reclamarlo. Ya no guarda nada: REINA.");
        }

        @Override
        public List<String> threat() {
            return List.of(
                    "MONARCA: pelea a CUATRO fases, no a tres",
                    "Es ciego: caza las VIBRACIONES; agacharse ayuda",
                    "26 habilidades de eco, sculk y oscuridad",
                    "Rompan lo que plante: chirriadores, sensores",
                    "y catalizadores trabajan para el",
                    "Su latido dice cuanta vida le queda");
        }

        @Override
        public double baseHealth() {
            return 3200;
        }

        @Override
        public int arenaRadius() {
            return 30;
        }

        @Override
        public List<Ability> abilities() {
            return keeperAbilities();
        }

        @Override
        public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
            return new Keeper(plugin, event, where);
        }
    }

    public List<Ability> keeperAbilities() {
        List<Ability> list = new ArrayList<>();

        // --- Fase I: el Centinela
        add(list, "bramido_sonico", "Bramido Sonico", 1, 180, 45, 5,
                "La linea perforante: el boom del warden atraviesa a todos los que cruce.",
                icon("ECHO_SHARD"), f -> keeper(f).sonicRoar());
        add(list, "abanico_ecos", "Abanico de Ecos", 1, 260, 55, 4,
                "Cinco bramidos a la vez, uno por cada cabeza cercana.",
                icon("AMETHYST_SHARD", "ECHO_SHARD"), f -> keeper(f).echoFan());
        add(list, "pisoton_sismico", "Pisoton Sismico", 1, 240, 60, 4,
                "Golpea el suelo: la onda barre ocho bloques y empuja.",
                icon("SCULK", "COARSE_DIRT"), f -> keeper(f).seismicStomp());
        add(list, "chirrido_convocador", "Chirrido Convocador", 1, 500, 60, 2,
                "Planta chirriadores que gritan Oscuridad; se rompen a golpes.",
                icon("SCULK_SHRIEKER"), f -> keeper(f).shriekerCall());
        add(list, "sensores_trampa", "Sensores Trampa", 1, 460, 50, 3,
                "Siembra sensores: pasar sin agacharse los detona.",
                icon("SCULK_SENSOR"), f -> keeper(f).sensorTraps());

        // --- Fase II: el Excavador
        add(list, "inmersion", "Inmersion", 2, 320, 90, 4,
                "Se entierra y emerge bajo un jugador reventando el suelo.",
                icon("MUD", "DIRT"), f -> keeper(f).submerge());
        add(list, "tuneles_abismo", "Tuneles del Abismo", 2, 480, 180, 3,
                "Tres emersiones seguidas, cada una bajo un jugador distinto.",
                icon("SCULK_VEIN", "SCULK"), f -> keeper(f).abyssTunnels());
        add(list, "tentaculos_sculk", "Tentaculos de Sculk", 2, 340, 70, 4,
                "Brotan bajo cuatro a la vez y los anclan al suelo.",
                icon("SCULK_VEIN", "CHAIN"), f -> keeper(f).sculkTendrils());
        add(list, "catalizador_voraz", "Catalizador Voraz", 2, 560, 60, 2,
                "Planta un catalizador que drena a los cercanos y LE CURA. Rompanlo.",
                icon("SCULK_CATALYST"), f -> keeper(f).voraciousCatalyst());
        add(list, "marea_sculk", "Marea Sculk", 2, 300, 80, 4,
                "Una ola de sculk que se expande y pega en el frente.",
                icon("SCULK", "KELP"), f -> keeper(f).sculkTide());
        add(list, "latigazo_abismo", "Latigazo del Abismo", 2, 200, 45, 5,
                "Un barrido en cono de 120 grados, apuntado al objetivo.",
                icon("BONE", "STICK"), f -> keeper(f).abyssLash());

        // --- Fase III: la Voz del Abismo
        add(list, "coro_alaridos", "Coro de Alaridos", 3, 400, 70, 4,
                "El grito global: Oscuridad y empujon a toda la arena.",
                icon("SCULK_SHRIEKER", "NOTE_BLOCK"), f -> keeper(f).shriekChorus());
        add(list, "bramido_orbital", "Bramido Orbital", 3, 460, 110, 3,
                "Cuatro brazos sonicos girando dos vueltas; hay que bailarlos.",
                icon("CONDUIT", "HEART_OF_THE_SEA"), f -> keeper(f).orbitalRoar());
        add(list, "ecolocalizacion", "Ecolocalizacion", 3, 400, 90, 3,
                "Los cinco que mas dano le hicieron reciben su eco, con aviso.",
                icon("SPYGLASS", "TARGET"), f -> keeper(f).echolocation());
        add(list, "prision_vibracion", "Prision de Vibracion", 3, 540, 120, 2,
                "Enjaula a uno: o le rompen el cerrojo o la jaula estalla.",
                icon("IRON_BARS"), f -> keeper(f).vibrationPrison());
        add(list, "pulso_devorador", "Pulso Devorador", 3, 380, 130, 3,
                "Ruge y lee la arena: el que se MUEVA recibe su bramido.",
                icon("SCULK_SENSOR", "CLOCK"), f -> keeper(f).devouringPulse());
        add(list, "almas_en_pena", "Almas en Pena", 3, 500, 60, 3,
                "Suelta almas que persiguen cada una a un jugador; un golpe las disipa.",
                icon("SOUL_LANTERN", "SOUL_TORCH"), f -> keeper(f).lostSouls());
        add(list, "terremoto_abismo", "Terremoto del Abismo", 3, 340, 80, 4,
                "Grietas que corren hacia varios y erupcionan lanzandolos al aire.",
                icon("DEEPSLATE", "COBBLED_DEEPSLATE"), f -> keeper(f).abyssQuake());

        // --- Fase IV: el Monarca del Silencio
        add(list, "silencio_absoluto", "Silencio Absoluto", 4, 440, 50, 3,
                "Oscuridad total sostenida: los avisos pasan a ser solo visuales.",
                icon("BLACK_CANDLE", "BLACK_DYE"), f -> keeper(f).absoluteSilence());
        add(list, "detonacion_cadena", "Detonacion en Cadena", 4, 400, 70, 3,
                "Todo lo que planto revienta en secuencia, pieza a pieza.",
                icon("TNT"), f -> keeper(f).chainDetonation());
        add(list, "bramido_cruz", "Bramido en Cruz", 4, 320, 95, 4,
                "Cuatro lineas en cruz; la cruz gira 45 grados y repite.",
                icon("NETHER_STAR", "ECHO_SHARD"), f -> keeper(f).crossRoar());
        add(list, "cataclismo_sonico", "Cataclismo Sonico", 4, 760, 165, 2,
                "La onda global. Agachado y sin moverse se recibe la cuarta parte.",
                icon("BEACON", "ECHO_SHARD"), f -> keeper(f).sonicCataclysm());
        add(list, "vastagos_sculk", "Vastagos del Sculk", 4, 460, 60, 3,
                "Crias brillantes que corren cada una a por un jugador y revientan.",
                icon("SILVERFISH_SPAWN_EGG", "SCULK"), f -> keeper(f).sculkSpawn());
        add(list, "abrazo_abismo", "Abrazo del Abismo", 4, 520, 110, 2,
                "Agarra al mas cercano y lo aprieta; se suelta pegandole AL JEFE.",
                icon("CHAIN", "SLIME_BALL"), f -> keeper(f).abyssEmbrace());

        // --- Cualquier fase
        add(list, "garra_resonante", "Garra Resonante", 0, 140, 25, 5,
                "El zarpazo al que mas vibra: el unico golpe que reserva para uno.",
                icon("ECHO_SHARD", "FLINT"), f -> keeper(f).resonantClaw());

        return list;
    }

    // ------------------------------------------------------- KEM y KAM, los gemelos

    /** Ficha del dueto de cobre. Es UN evento con DOS jefes que hay que tumbar a la vez. */
    public final class CopperTwinsType implements AnomalyType {

        @Override
        public String id() {
            return CopperTwins.ID;
        }

        @Override
        public String display() {
            return plugin.getConfig().getString("anomalias." + id() + ".nombre", "Kem y Kam");
        }

        @Override
        public TextColor color() {
            return CopperTwins.ACCENT;
        }

        @Override
        public NamedTextColor glowColor() {
            // El contorno sale del equipo de marcador y solo admite los dieciseis
            // colores clasicos: el dorado es lo mas parecido al naranja de KEM. KAM
            // lleva el suyo en verde, puesto entidad por entidad.
            return NamedTextColor.GOLD;
        }

        @Override
        public Element element() {
            return Element.TIERRA;
        }

        @Override
        public Material icon() {
            Material m = Material.matchMaterial("COPPER_GOLEM_STATUE");
            if (m == null) m = Material.matchMaterial("COPPER_BLOCK");
            return m != null ? m : Material.RAW_COPPER;
        }

        @Override
        public String tagline() {
            return "Dos golems de cobre que no se dejan matar por separado";
        }

        @Override
        public net.ederus.edm.anomaly.core.AnomalyClass defaultClass() {
            return net.ederus.edm.anomaly.core.AnomalyClass.MONARCA;
        }

        @Override
        public List<String> origin() {
            return List.of(
                    "Se fundieron del mismo lingote y por eso no",
                    "saben estar el uno sin el otro: si uno cae,",
                    "el que queda le presta la mitad de lo que le",
                    "queda de vida y lo levanta otra vez.",
                    "KEM salio naranja y KAM se dejo oxidar.");
        }

        @Override
        public List<String> threat() {
            return List.of(
                    "DOS jefes con DOS barras: tienen que caer los dos",
                    "Si solo cae uno, el otro lo RESUCITA a los 30 s",
                    "KEM: area, debuffs y la Quietud de Oxido",
                    "KAM: cuerpo a cuerpo y la Marca de la Muerte",
                    "Las dos firmadas se juegan igual: QUIETOS",
                    "Juntos se curan; hay que separarlos");
        }

        @Override
        public double baseHealth() {
            // La vida del DUETO: cada gemelo se lleva la mitad.
            return 3600;
        }

        @Override
        public int arenaRadius() {
            return 28;
        }

        @Override
        public List<Ability> abilities() {
            return twinsAbilities();
        }

        @Override
        public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
            return new CopperTwins(plugin, event, where);
        }
    }

    /**
     * El repertorio del dueto. Los id que empiezan por `kam_` los lanza KAM a su
     * propio ritmo; el resto van al motor, que es quien lleva a KEM. Asi los dos
     * gemelos pueden estar haciendo algo a la vez.
     *
     * Ninguna lleva fase: este jefe no tiene fases, solo dos cuerpos que tumbar.
     */
    public List<Ability> twinsAbilities() {
        List<Ability> list = new ArrayList<>();

        // --- Las dos firmadas: las dos se juegan quedandose quieto
        add(list, "kem_quietud", "Quietud de Oxido", 0, 700, 120, 3,
                "KEM lee la arena: quien se mueva queda PETRIFICADO un minuto, en naranja.",
                icon("COPPER_BLOCK", "RAW_COPPER"), f -> twins(f).rustStillness());
        add(list, "kam_marca_muerte", "Marca de la Muerte", 0, 800, 120, 3,
                "KAM marca en verde a quien se mueva: cinco segundos despues, 2000 de dano.",
                icon("OXIDIZED_COPPER", "GREEN_DYE"), f -> twins(f).deathMark());

        // --- KEM: el artillero (area, debuffs, control)
        add(list, "kem_verdin", "Nube de Verdin", 0, 320, 80, 4,
                "Charcos de verdin bajo varios: envenenan y marean siete segundos.",
                icon("GREEN_DYE", "SLIME_BALL"), f -> twins(f).verdigrisCloud());
        add(list, "kem_descarga", "Descarga de Cobre", 0, 220, 45, 5,
                "El rayo salta de uno a otro encadenando a seis; el cobre conduce.",
                icon("LIGHTNING_ROD", "COPPER_INGOT"), f -> twins(f).copperArc());
        add(list, "kem_onda", "Onda Oxidante", 0, 260, 70, 4,
                "Onda radial de diez bloques que reparte lentitud y debilidad.",
                icon("COPPER_BULB", "COPPER_BLOCK"), f -> twins(f).oxidizingWave());
        add(list, "kem_esquirlas", "Lluvia de Esquirlas", 0, 300, 55, 4,
                "Astillas de cobre sobre la marca de TODOS; cada impacto oxida.",
                icon("COPPER_NUGGET", "IRON_NUGGET"), f -> twins(f).shrapnelRain());
        add(list, "kem_iman", "Campo Magnetico", 0, 380, 100, 3,
                "Arrastra a todo el mundo hacia el durante cinco segundos.",
                icon("HEAVY_CORE", "IRON_BLOCK"), f -> twins(f).magneticField());
        add(list, "kem_herrumbre", "Herrumbre", 0, 340, 30, 4,
                "Una capa mas de oxido a cinco jugadores: cada capa sube un 12% lo que duele todo.",
                icon("COPPER_DOOR", "COPPER_INGOT"), f -> twins(f).rustPlague());
        add(list, "kem_pararrayos", "Pararrayos", 0, 520, 50, 2,
                "Planta un poste que llama al rayo cada cuatro segundos. Se tumba a golpes.",
                icon("LIGHTNING_ROD", "COPPER_BLOCK"), f -> twins(f).lightningRod());
        add(list, "kem_estatica", "Estatica", 0, 400, 160, 3,
                "Durante ocho segundos, correr cerca de KEM da calambre.",
                icon("COPPER_BULB", "REDSTONE"), f -> twins(f).staticCharge());
        add(list, "kem_detonacion", "Detonacion de Oxido", 0, 340, 75, 4,
                "Cargas retardadas bajo cuatro jugadores a la vez.",
                icon("TNT", "COPPER_BLOCK"), f -> twins(f).rustDetonation());
        add(list, "kem_salva", "Salva de Cobre", 0, 260, 60, 4,
                "Tres andanadas repartidas entre el grupo entero.",
                icon("CROSSBOW", "COPPER_NUGGET"), f -> twins(f).copperVolley());

        // --- KAM: el yunque (cuerpo a cuerpo, aguante, castigo por huir)
        add(list, "kam_puno", "Puño de Cobre", 0, 200, 45, 5,
                "Mandoble en cono de cien grados: lento, avisado y brutal.",
                icon("IRON_BLOCK", "COPPER_BLOCK"), f -> twins(f).copperFist());
        add(list, "kam_embestida", "Embestida Verdosa", 0, 280, 65, 4,
                "Carga en linea recta arrollando a todo el que pille.",
                icon("OXIDIZED_COPPER", "MOSS_BLOCK"), f -> twins(f).verdantCharge());
        add(list, "kam_pisoton", "Pisoton del Yunque", 0, 240, 55, 4,
                "Onda corta de siete bloques, pero de las que levantan.",
                icon("ANVIL", "IRON_BLOCK"), f -> twins(f).anvilStomp());
        add(list, "kam_muro", "Muro de Cobre", 0, 420, 165, 3,
                "Se cubre: aguanta el doble y devuelve parte de lo que le peguen.",
                icon("SHIELD", "OXIDIZED_COPPER"), f -> twins(f).copperWall());
        add(list, "kam_barrido", "Barrido de Brazos", 0, 220, 65, 5,
                "Gira repartiendo a todos los que tenga pegados.",
                icon("MACE", "IRON_SWORD"), f -> twins(f).armSweep());
        add(list, "kam_agarre", "Agarre y Lanzamiento", 0, 320, 55, 3,
                "Coge al mas cercano y lo lanza contra los suyos.",
                icon("CHAIN", "LEAD"), f -> twins(f).grabAndThrow());
        add(list, "kam_provocacion", "Provocacion", 0, 380, 165, 3,
                "Ocho segundos en los que alejarse mas de nueve bloques duele.",
                icon("GOAT_HORN", "BELL"), f -> twins(f).taunt());
        add(list, "kam_placa", "Placa Reactiva", 0, 340, 125, 3,
                "Se electrifica: durante seis segundos devuelve dano en area.",
                icon("COPPER_BULB", "REDSTONE_BLOCK"), f -> twins(f).reactivePlating());
        add(list, "kam_salto", "Salto de Yunque", 0, 300, 75, 4,
                "Sube y cae de lleno sobre la marca.",
                icon("ANVIL", "OXIDIZED_COPPER"), f -> twins(f).anvilLeap());
        add(list, "kam_muralla", "Muralla", 0, 360, 145, 3,
                "Se planta: quien cruce a su lado se queda pegado al suelo.",
                icon("IRON_BARS", "COPPER_GRATE"), f -> twins(f).bulwark());

        // --- Los dos a la vez
        add(list, "duo_sincronia", "Sincronia", 0, 460, 125, 3,
                "Si estan a menos de doce bloques se curan el uno al otro. SEPARENLOS.",
                icon("HEART_OF_THE_SEA", "COPPER_BLOCK"), f -> twins(f).synchrony());
        add(list, "duo_resonancia", "Resonancia Gemela", 0, 380, 80, 4,
                "Los dos golpean el suelo: dos ondas cruzadas sin hueco comodo.",
                icon("BELL", "COPPER_BULB"), f -> twins(f).twinResonance());
        add(list, "duo_relevo", "Relevo", 0, 300, 25, 3,
                "Se cambian el sitio: el yunque aparece donde estaba el artillero.",
                icon("ENDER_PEARL", "COPPER_INGOT"), f -> twins(f).relay());

        return list;
    }

    private static Piromante piromante(BossFight fight) {
        return (Piromante) fight;
    }

    private static SepulchralKnight knight(BossFight fight) {
        return (SepulchralKnight) fight;
    }

    private static Keeper keeper(BossFight fight) {
        return (Keeper) fight;
    }

    private static CopperTwins twins(BossFight fight) {
        return (CopperTwins) fight;
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
