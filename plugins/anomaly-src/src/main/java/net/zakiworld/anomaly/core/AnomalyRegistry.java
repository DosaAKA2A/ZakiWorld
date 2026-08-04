package net.zakiworld.anomaly.core;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.zakiworld.anomaly.AnomalyPlugin;
import net.zakiworld.anomaly.boss.Ability;
import net.zakiworld.anomaly.boss.BossFight;
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
                    "18 habilidades, todas con aviso previo",
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
                icon("IRON_SWORD"), f -> knight(f).scytheSweep());
        add(list, "lanzas_paramo", "Lanzas del Paramo", 1, 240, 90, 3,
                "Bajo los pies de cada uno brotan cinco lanzas del suelo.",
                icon("SPECTRAL_ARROW", "ARROW"), f -> knight(f).wastelandSpears());
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
        add(list, "muro_lanzas", "Muro de Lanzas", 2, 260, 80, 3,
                "Una hilera de nueve lanzas atraviesa la arena de lado a lado.",
                icon("IRON_BARS"), f -> knight(f).spearWall());
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
        add(list, "lluvia_acero", "Lluvia de Acero", 3, 320, 160, 4,
                "Ocho segundos de lanzas cayendo del cielo sobre marcas que te persiguen.",
                icon("NETHERITE_SCRAP"), f -> knight(f).steelRain());
        add(list, "tormenta_espectral", "Tormenta Espectral", 3, 340, 110, 3,
                "Se eleva y descarga rayos sobre marcas en el suelo.",
                icon("LIGHTNING_ROD", "COPPER_INGOT"), f -> knight(f).spectralStorm());
        add(list, "ultima_carga", "Ultima Carga", 3, 380, 120, 3,
                "El fantasma de la montura vuelve para una carga que atraviesa la arena.",
                icon("SKELETON_SKULL"), f -> knight(f).finalCharge());
        add(list, "grito_paramo", "Grito del Paramo", 3, 250, 70, 4,
                "Un haz sonico frontal de 24 bloques que atraviesa todo lo que pilla.",
                icon("ECHO_SHARD", "AMETHYST_SHARD"), f -> knight(f).wastelandScream());

        // --- Cualquier fase
        add(list, "marca_sepulcro", "Marca del Sepulcro", 0, 360, 80, 2,
                "Senala al que se aleja y le deja caer una lanza encima.",
                icon("TARGET", "REDSTONE"), f -> knight(f).graveMark());
        add(list, "leva_huesos", "Leva de Huesos", 0, 700, 60, 2,
                "Recluta entre tres y seis caidos que salen del suelo alrededor.",
                icon("SKELETON_SPAWN_EGG", "BONE_MEAL"), f -> knight(f).boneLevy());

        return list;
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
