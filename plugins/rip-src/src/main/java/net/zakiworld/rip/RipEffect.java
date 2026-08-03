/*
 * Decompiled with CFR 0.152.
 */
package net.zakiworld.rip;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.zakiworld.rip.Rarity;
import org.bukkit.Material;

public enum RipEffect {
    K_BLOOD(Type.KILL, "blood", "Sangre", Rarity.COMUN, Material.REDSTONE, "Explosion de sangre sobre la victima.", 0, false),
    K_HEARTS(Type.KILL, "hearts", "Corazones", Rarity.COMUN, Material.POPPY, "Corazones... un final con carino.", 12, false),
    K_SPARK(Type.KILL, "spark", "Chispas", Rarity.COMUN, Material.COPPER_INGOT, "Anillo de chispas electricas.", 12, false),
    K_COINS(Type.KILL, "coins", "Botin", Rarity.COMUN, Material.GOLD_NUGGET, "Una lluvia de monedas paga tu victoria.", 0, false),
    K_POOF(Type.KILL, "poof", "Puf", Rarity.COMUN, Material.WHITE_WOOL, "La victima desaparece en una nubecita.", 0, true),
    K_FIREWORK(Type.KILL, "firework", "Fuegos", Rarity.POCO_COMUN, Material.FIREWORK_ROCKET, "Cohete festivo en el punto de la baja.", 0, false),
    K_NOTES(Type.KILL, "notes", "Marcha Funebre", Rarity.POCO_COMUN, Material.NOTE_BLOCK, "Notas musicales y melodia burlona.", 18, false),
    K_SLASH(Type.KILL, "slash", "Tajo Maestro", Rarity.POCO_COMUN, Material.IRON_SWORD, "Cortes criticos en espiral con destellos.", 16, false),
    K_CONFETTI(Type.KILL, "confetti", "Confeti", Rarity.POCO_COMUN, Material.PAPER, "Explosion de confeti multicolor.", 20, false),
    K_PIERCE(Type.KILL, "pierce", "Estocada", Rarity.POCO_COMUN, Material.TRIDENT, "Una estocada fantasmal cruza hasta tu presa.", 10, false),
    K_EXPLOSION(Type.KILL, "explosion", "Explosion", Rarity.RARO, Material.TNT, "Mecha encendida y gran estallido.", 28, true),
    K_FROST(Type.KILL, "frost", "Congelacion", Rarity.RARO, Material.BLUE_ICE, "La victima se congela... y se hace anicos.", 30, true),
    K_INFERNO(Type.KILL, "inferno", "Infierno", Rarity.RARO, Material.FIRE_CHARGE, "Columna de llamas con anillo de fuego final.", 32, true),
    K_SHOCKWAVE(Type.KILL, "shockwave", "Onda Sismica", Rarity.RARO, Material.ANVIL, "Doble onda de choque a ras de suelo.", 24, false),
    K_METEOR(Type.KILL, "meteor", "Meteorito", Rarity.RARO, Material.MAGMA_BLOCK, "Un meteorito ardiente cae sobre la victima.", 38, true),
    K_GEYSER(Type.KILL, "geyser", "Geiser", Rarity.RARO, Material.WATER_BUCKET, "Un geiser revienta bajo sus pies.", 34, false),
    K_SOULS(Type.KILL, "souls", "Cosecha de Almas", Rarity.MITICO, Material.SOUL_LANTERN, "Las almas escapan en espiral y te rinden tributo.", 48, false),
    K_DRAGON(Type.KILL, "dragon", "Aliento de Dragon", Rarity.MITICO, Material.DRAGON_BREATH, "Un dragon invisible devora los restos.", 50, true),
    K_TOTEM(Type.KILL, "totem", "Falso Totem", Rarity.MITICO, Material.TOTEM_OF_UNDYING, "Destello de totem... que no salva a nadie.", 44, false),
    K_CHAINS(Type.KILL, "chains", "Cadenas Espectrales", Rarity.MITICO, Material.CHAIN, "Cadenas de fuego de alma apresan a la victima.", 56, false),
    K_ECLIPSE(Type.KILL, "eclipse", "Eclipse", Rarity.MITICO, Material.TINTED_GLASS, "La oscuridad cubre la zona antes del destello final.", 60, false),
    K_LIGHTNING(Type.KILL, "lightning", "Tormenta Divina", Rarity.LEGENDARIO, Material.LIGHTNING_ROD, "La tormenta se forma, ruge y fulmina tres veces.", 96, false),
    K_BLACKHOLE(Type.KILL, "blackhole", "Agujero Negro", Rarity.LEGENDARIO, Material.OBSIDIAN, "Un disco de acrecion devora la luz y colapsa.", 92, true),
    K_LASER(Type.KILL, "laser", "Canon Laser", Rarity.LEGENDARIO, Material.END_ROD, "Carga, dispara y funde el suelo bajo tu presa.", 84, true),
    K_SWORDFALL(Type.KILL, "swordfall", "Juicio de Espadas", Rarity.LEGENDARIO, Material.NETHERITE_SWORD, "La victima queda petrificada bajo una lluvia de espadas.", 190, true),
    K_ORBITAL(Type.KILL, "orbital", "Canon Celestial", Rarity.LEGENDARIO, Material.BEACON, "Un pilar de luz orbital aniquila la zona marcada.", 112, true),
    K_BLASTOFF(Type.KILL, "blastoff", "Despegue Estelar", Rarity.INMORTAL, Material.ELYTRA, "La victima sale disparada al cielo... y se apaga con un destello.", 96, true),
    D_SMOKE(Type.DEATH, "smoke", "Humo", Rarity.COMUN, Material.CAMPFIRE, "Columna de humo negro.", 14, false),
    D_BONES(Type.DEATH, "bones", "Huesos", Rarity.COMUN, Material.BONE, "Estallas en una nube de huesos.", 0, true),
    D_BLOODPOOL(Type.DEATH, "bloodpool", "Charco de Sangre", Rarity.COMUN, Material.RED_DYE, "Un charco carmesi se extiende.", 14, false),
    D_SPLAT(Type.DEATH, "splat", "Splat", Rarity.COMUN, Material.SLIME_BALL, "Revientas como un slime.", 0, true),
    D_DUST(Type.DEATH, "dust", "Polvo", Rarity.COMUN, Material.BONE_MEAL, "Polvo eres y en polvo te conviertes.", 14, true),
    D_FIREWORK(Type.DEATH, "firework", "Despedida Festiva", Rarity.POCO_COMUN, Material.FIREWORK_STAR, "Cohete de despedida multicolor.", 0, false),
    D_CHERRY(Type.DEATH, "cherry", "Petalos", Rarity.POCO_COMUN, Material.PINK_TULIP, "Lluvia serena de petalos de cerezo.", 22, false),
    D_RAIN(Type.DEATH, "rain", "Lluvia Triste", Rarity.POCO_COMUN, Material.WATER_BUCKET, "Una nube personal llora tu perdida.", 24, false),
    D_BALLOONS(Type.DEATH, "balloons", "Globos", Rarity.POCO_COMUN, Material.RED_WOOL, "Globos de colores escapan... y van explotando.", 26, false),
    D_SPORES(Type.DEATH, "spores", "Esporas", Rarity.POCO_COMUN, Material.SPORE_BLOSSOM, "Una nube de esporas te devuelve a la tierra.", 24, false),
    D_GRAVE(Type.DEATH, "grave", "Tumba", Rarity.RARO, Material.SOUL_SAND, "Ceniza, almas y una campana que dobla por ti.", 34, false),
    D_SHATTER(Type.DEATH, "shatter", "Cristal Roto", Rarity.RARO, Material.AMETHYST_SHARD, "Te cristalizas y te rompes en amatista.", 26, true),
    D_VOID(Type.DEATH, "void", "Vacio", Rarity.RARO, Material.BLACK_CONCRETE, "Tinta y oscuridad te reclaman.", 28, true),
    D_ENDER(Type.DEATH, "ender", "Eco del End", Rarity.RARO, Material.ENDER_EYE, "Particulas de teletransporte y un eco.", 26, true),
    D_STATUE(Type.DEATH, "statue", "Petrificacion", Rarity.RARO, Material.STONE, "Te vuelves piedra... y la piedra se desmorona.", 40, true),
    D_WHIRLPOOL(Type.DEATH, "whirlpool", "Remolino", Rarity.RARO, Material.PRISMARINE_SHARD, "Un remolino de agua te arrastra al fondo.", 36, true),
    D_ANGEL(Type.DEATH, "angel", "Ascension", Rarity.MITICO, Material.FEATHER, "Doble espiral de luz, halo final y plumas.", 52, true),
    D_SOULS(Type.DEATH, "souls", "Vortice de Almas", Rarity.MITICO, Material.SOUL_TORCH, "Almas girando en espiral descendente.", 46, true),
    D_GLITCH(Type.DEATH, "glitch", "Glitch Arcano", Rarity.MITICO, Material.ENCHANTING_TABLE, "La realidad parpadea en runas corruptas.", 42, false),
    D_PHOENIX(Type.DEATH, "phoenix", "Fenix", Rarity.MITICO, Material.BLAZE_POWDER, "Ardes en llamas que baten alas y estallan en brasas.", 64, true),
    D_SPECTERS(Type.DEATH, "specters", "Espectros", Rarity.MITICO, Material.PHANTOM_MEMBRANE, "Cinco espectros huyen de tu cuerpo entre lamentos.", 56, false),
    D_IMPLOSION(Type.DEATH, "implosion", "Singularidad", Rarity.LEGENDARIO, Material.CRYING_OBSIDIAN, "Un pozo de gravedad absorbe el mundo y detona.", 92, true),
    D_SUPERNOVA(Type.DEATH, "supernova", "Supernova", Rarity.LEGENDARIO, Material.NETHER_STAR, "Una estrella nace sobre ti, colapsa y estalla.", 104, true),
    D_LIGHTNING(Type.DEATH, "lightning", "Ira del Cielo", Rarity.LEGENDARIO, Material.LIGHTNING_ROD, "La tormenta llora y descarga su furia sobre tu caida.", 90, false),
    D_PUFFERFISH(Type.DEATH, "pufferfish", "Peces Globo", Rarity.LEGENDARIO, Material.PUFFERFISH, "Explotas en peces globo que quedan saltando... puff.", 84, true),
    D_REQUIEM(Type.DEATH, "requiem", "Requiem Celestial", Rarity.LEGENDARIO, Material.AMETHYST_CLUSTER, "Un pilar de luz desciende y canta tu funeral.", 112, false),
    D_SIXTYSEVEN(Type.DEATH, "sixtyseven", "SESENTA Y SIETE", Rarity.INMORTAL, Material.GOLD_BLOCK, "El numero prohibido preside tu funeral entre fuegos y fanfarrias. 67.", 130, false);

    private final Type type;
    private final String id;
    private final String display;
    private final Rarity rarity;
    private final Material fallbackIcon;
    private final String description;
    private final int durationTicks;
    private final boolean hidesBody;
    public static final String RANDOM_ID = "random";
    private static final int MIN_COOLDOWN_TICKS = 20;
    private static final RipEffect[] VALUES;
    private static final Map<Type, List<RipEffect>> BY_TYPE;

    private RipEffect(Type type, String id, String display, Rarity rarity, Material fallbackIcon, String description, int durationTicks, boolean hidesBody) {
        this.type = type;
        this.id = id;
        this.display = display;
        this.rarity = rarity;
        this.fallbackIcon = fallbackIcon;
        this.description = description;
        this.durationTicks = durationTicks;
        this.hidesBody = hidesBody;
    }

    /*
     * Si el efecto destruye el cuerpo, conviene esconder el cadaver del jugador
     * en el mismo tick de la muerte para que no se vea la caida de lado vanilla
     * por debajo de la animacion. Se puede ajustar en config.yml.
     */
    public boolean hidesBodyByDefault() {
        return this.hidesBody;
    }

    public String key() {
        return this.type.lower() + "." + this.id;
    }

    public Type type() {
        return this.type;
    }

    public String id() {
        return this.id;
    }

    public String display() {
        return this.display;
    }

    public Rarity rarity() {
        return this.rarity;
    }

    public Material fallbackIcon() {
        return this.fallbackIcon;
    }

    public String description() {
        return this.description;
    }

    /*
     * Cuanto dura la animacion en el mundo. El enfriamiento sale de aqui: no se
     * puede lanzar otra hasta que la anterior haya terminado.
     */
    public int durationTicks() {
        return this.durationTicks;
    }

    public int cooldownTicks() {
        return Math.max(MIN_COOLDOWN_TICKS, this.durationTicks);
    }

    public long cooldownMillis() {
        return (long)this.cooldownTicks() * 50L;
    }

    public String cooldownLabel() {
        long tenths = (this.cooldownMillis() + 50L) / 100L;
        return tenths % 10L == 0L ? tenths / 10L + "s" : tenths / 10L + "," + tenths % 10L + "s";
    }

    public String permission() {
        return "rip." + this.type.lower() + "." + this.id;
    }

    public String headKey() {
        return this.type.lower() + "." + this.id;
    }

    public static List<RipEffect> of(Type type) {
        return BY_TYPE.get((Object)type);
    }

    public static RipEffect byId(Type type, String id) {
        if (id == null) {
            return null;
        }
        for (RipEffect e : BY_TYPE.get((Object)type)) {
            if (!e.id.equalsIgnoreCase(id)) continue;
            return e;
        }
        return null;
    }

    public static int count(Type type) {
        return BY_TYPE.get((Object)type).size();
    }

    static {
        VALUES = RipEffect.values();
        BY_TYPE = new EnumMap<Type, List<RipEffect>>(Type.class);
        for (Type t : Type.values()) {
            ArrayList<RipEffect> list = new ArrayList<RipEffect>();
            for (RipEffect e : VALUES) {
                if (e.type != t) continue;
                list.add(e);
            }
            BY_TYPE.put(t, List.copyOf(list));
        }
    }

    public static enum Type {
        KILL,
        DEATH;


        public String lower() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }
}

