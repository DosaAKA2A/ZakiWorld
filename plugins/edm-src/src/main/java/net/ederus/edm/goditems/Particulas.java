package net.ederus.edm.goditems;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Vibration;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

/**
 * El catalogo de particulas de GodItems.
 *
 * DOS decisiones que explican por que este fichero es como es:
 *
 * 1. **La lista viva manda.** El catalogo se construye recorriendo
 *    `Particle.values()` del servidor, NO una lista escrita a mano. La tabla de
 *    abajo solo aporta el nombre en español, el grupo y el icono. Una particula
 *    que Mojang añada en la siguiente version aparece sola en el menu (en
 *    "Otras" y con su nombre bonito automatico) y una que quiten no revienta
 *    nada: simplemente no se pinta. Con una lista fija, cada actualizacion del
 *    servidor rompia el menu en silencio.
 *
 * 2. **Cada particula pide sus datos.** En 1.21.9 varias pasaron a EXIGIR un
 *    dato y llamarlas sin el lanza IllegalArgumentException, que ya reventó
 *    medio plugin Rip una vez (ver la bitacora de Rip: FLASH pide Color y
 *    DRAGON_BREATH pide Float). Aqui se lee `getDataType()` y se fabrica el
 *    dato que toque a partir de las claves de la linea, con valores por defecto
 *    razonables. Nunca se llama a spawnParticle "a pelo".
 *
 * Claves que entiende una linea visual, segun la particula:
 *
 *   color:#FF0000     DUST, DUST_COLOR_TRANSITION, ENTITY_EFFECT, TRAIL,
 *                     EFFECT / INSTANT_EFFECT
 *   color2:#00FF00    solo DUST_COLOR_TRANSITION (el color de destino)
 *   tamano:1.5        tamaño del punto de polvo
 *   bloque:MUD        BLOCK, BLOCK_MARKER, BLOCK_CRUMBLE, FALLING_DUST, DUST_PILLAR
 *   item:DIAMOND      ITEM
 *   valor:0.5         SCULK_CHARGE (el giro) y cualquier otra que pida un decimal
 *   retardo:20        SHRIEK (los ticks que tarda en sonar)
 *   duracion:40       VIBRATION y TRAIL (lo que tarda en llegar)
 *   hacia:@golpeado   VIBRATION y TRAIL: adonde viaja. Por omision, el objetivo.
 */
public final class Particulas {

    private Particulas() { }

    /** Los grupos, en el orden en que salen en el menu. */
    public static final List<String> GRUPOS = List.of(
            "Fuego", "Humo", "Magia", "Combate", "Agua", "Naturaleza",
            "Bloques", "Sculk", "Criaturas", "Estructuras", "Otras");

    /** Una particula ya lista para el menu. */
    public record Info(Particle particula, String clave, String nombre, String grupo,
                       Material icono, String pideDato) {

        /** El nombre que se escribe en el YAML. */
        public String yaml() {
            return this.particula.name();
        }
    }

    private static final Map<String, Info> POR_CLAVE = new LinkedHashMap<>();
    private static final Map<String, List<Info>> POR_GRUPO = new LinkedHashMap<>();

    /*
     * clave|Nombre en español|Grupo|ICONO
     *
     * Estan las 120 de la 1.21.9. Si el servidor trae mas, entran solas en
     * "Otras"; si trae menos, las que falten se descartan al construir.
     */
    private static final String[] TABLA = {
        /* --- fuego --- */
        "flame|Llama|Fuego|BLAZE_POWDER",
        "small_flame|Llama pequeña|Fuego|BLAZE_POWDER",
        "soul_fire_flame|Llama de almas|Fuego|SOUL_TORCH",
        "copper_fire_flame|Llama de cobre|Fuego|COPPER_INGOT",
        "lava|Chispa de lava|Fuego|LAVA_BUCKET",
        "dripping_lava|Gota de lava|Fuego|LAVA_BUCKET",
        "falling_lava|Gota de lava cayendo|Fuego|LAVA_BUCKET",
        "landing_lava|Lava al aterrizar|Fuego|MAGMA_BLOCK",
        "dripping_dripstone_lava|Lava de estalactita|Fuego|POINTED_DRIPSTONE",
        "falling_dripstone_lava|Lava de estalactita cayendo|Fuego|POINTED_DRIPSTONE",
        "flash|Fogonazo|Fuego|FIREWORK_STAR",
        "firework|Chispa de cohete|Fuego|FIREWORK_ROCKET",
        "dragon_breath|Aliento del dragón|Fuego|DRAGON_BREATH",

        /* --- humo --- */
        "smoke|Humo|Humo|CAMPFIRE",
        "large_smoke|Humo grande|Humo|CAMPFIRE",
        "white_smoke|Humo blanco|Humo|WHITE_WOOL",
        "campfire_cosy_smoke|Humo de hoguera|Humo|CAMPFIRE",
        "campfire_signal_smoke|Humo de señal|Humo|HAY_BLOCK",
        "ash|Ceniza|Humo|GRAY_DYE",
        "white_ash|Ceniza blanca|Humo|BONE_MEAL",
        "cloud|Nube|Humo|WHITE_WOOL",
        "poof|Nubecilla|Humo|WHITE_WOOL",
        "dust_plume|Penacho de polvo|Humo|GRAY_DYE",

        /* --- magia --- */
        "dust|Polvo de color|Magia|REDSTONE",
        "dust_color_transition|Polvo que cambia de color|Magia|REDSTONE",
        "effect|Efecto de poción|Magia|POTION",
        "instant_effect|Efecto instantáneo|Magia|SPLASH_POTION",
        "entity_effect|Efecto de entidad|Magia|GLASS_BOTTLE",
        "witch|Bruja|Magia|POTION",
        "enchant|Encantamiento|Magia|ENCHANTING_TABLE",
        "portal|Portal|Magia|OBSIDIAN",
        "reverse_portal|Portal inverso|Magia|CRYING_OBSIDIAN",
        "end_rod|Vara del End|Magia|END_ROD",
        "totem_of_undying|Tótem|Magia|TOTEM_OF_UNDYING",
        "glow|Brillo|Magia|GLOW_INK_SAC",
        "note|Nota musical|Magia|NOTE_BLOCK",
        "heart|Corazón|Magia|RED_DYE",
        "dripping_obsidian_tear|Lágrima de obsidiana|Magia|CRYING_OBSIDIAN",
        "falling_obsidian_tear|Lágrima de obsidiana cayendo|Magia|CRYING_OBSIDIAN",
        "landing_obsidian_tear|Lágrima al aterrizar|Magia|CRYING_OBSIDIAN",

        /* --- combate --- */
        "crit|Crítico|Combate|IRON_SWORD",
        "enchanted_hit|Golpe encantado|Combate|DIAMOND_SWORD",
        "damage_indicator|Indicador de daño|Combate|REDSTONE",
        "sweep_attack|Barrido|Combate|IRON_SWORD",
        "sonic_boom|Estallido sónico|Combate|ECHO_SHARD",
        "explosion|Explosión|Combate|TNT",
        "explosion_emitter|Explosión enorme|Combate|TNT",
        "gust|Ráfaga|Combate|WIND_CHARGE",
        "small_gust|Ráfaga pequeña|Combate|WIND_CHARGE",
        "gust_emitter_large|Emisor de ráfaga grande|Combate|WIND_CHARGE",
        "gust_emitter_small|Emisor de ráfaga pequeño|Combate|WIND_CHARGE",

        /* --- agua --- */
        "bubble|Burbuja|Agua|WATER_BUCKET",
        "bubble_pop|Burbuja que estalla|Agua|WATER_BUCKET",
        "bubble_column_up|Columna de burbujas|Agua|SOUL_SAND",
        "current_down|Corriente hacia abajo|Agua|MAGMA_BLOCK",
        "splash|Salpicadura|Agua|WATER_BUCKET",
        "fishing|Pesca|Agua|FISHING_ROD",
        "underwater|Bajo el agua|Agua|WATER_BUCKET",
        "rain|Lluvia|Agua|WATER_BUCKET",
        "dripping_water|Gota de agua|Agua|WATER_BUCKET",
        "falling_water|Gota de agua cayendo|Agua|WATER_BUCKET",
        "dripping_dripstone_water|Agua de estalactita|Agua|POINTED_DRIPSTONE",
        "falling_dripstone_water|Agua de estalactita cayendo|Agua|POINTED_DRIPSTONE",
        "squid_ink|Tinta de calamar|Agua|INK_SAC",
        "glow_squid_ink|Tinta luminosa|Agua|GLOW_INK_SAC",
        "nautilus|Nautilo|Agua|NAUTILUS_SHELL",
        "dolphin|Delfín|Agua|COD",

        /* --- naturaleza --- */
        "mycelium|Micelio|Naturaleza|MYCELIUM",
        "crimson_spore|Espora carmesí|Naturaleza|CRIMSON_FUNGUS",
        "warped_spore|Espora distorsionada|Naturaleza|WARPED_FUNGUS",
        "spore_blossom_air|Flor de esporas|Naturaleza|SPORE_BLOSSOM",
        "falling_spore_blossom|Pétalo de esporas|Naturaleza|SPORE_BLOSSOM",
        "cherry_leaves|Hojas de cerezo|Naturaleza|CHERRY_LEAVES",
        "pale_oak_leaves|Hojas de roble pálido|Naturaleza|PALE_OAK_LEAVES",
        "tinted_leaves|Hojas teñidas|Naturaleza|OAK_LEAVES",
        "snowflake|Copo de nieve|Naturaleza|SNOWBALL",
        "composter|Compostador|Naturaleza|COMPOSTER",
        "dripping_honey|Gota de miel|Naturaleza|HONEY_BOTTLE",
        "falling_honey|Gota de miel cayendo|Naturaleza|HONEY_BOTTLE",
        "landing_honey|Miel al aterrizar|Naturaleza|HONEY_BLOCK",
        "falling_nectar|Néctar|Naturaleza|HONEYCOMB",
        "firefly|Luciérnaga|Naturaleza|GLOW_INK_SAC",

        /* --- bloques --- */
        "block|Trozo de bloque|Bloques|STONE",
        "block_marker|Marca de bloque|Bloques|LIGHT",
        "block_crumble|Bloque que se desmorona|Bloques|GRAVEL",
        "falling_dust|Polvo que cae|Bloques|SAND",
        "dust_pillar|Columna de polvo|Bloques|MUD",
        "item|Trozo de objeto|Bloques|ITEM_FRAME",
        "electric_spark|Chispa eléctrica|Bloques|LIGHTNING_ROD",
        "wax_on|Cera puesta|Bloques|HONEYCOMB",
        "wax_off|Cera quitada|Bloques|HONEYCOMB",
        "scrape|Raspado|Bloques|IRON_AXE",

        /* --- sculk --- */
        "soul|Alma|Sculk|SOUL_SAND",
        "sculk_soul|Alma de sculk|Sculk|SCULK",
        "sculk_charge|Carga de sculk|Sculk|SCULK_CATALYST",
        "sculk_charge_pop|Carga que estalla|Sculk|SCULK_CATALYST",
        "shriek|Chillido|Sculk|SCULK_SHRIEKER",
        "vibration|Vibración|Sculk|SCULK_SENSOR",

        /* --- criaturas --- */
        "angry_villager|Aldeano enfadado|Criaturas|EMERALD",
        "happy_villager|Aldeano contento|Criaturas|EMERALD",
        "elder_guardian|Guardián anciano|Criaturas|PRISMARINE_SHARD",
        "sneeze|Estornudo de panda|Criaturas|BAMBOO",
        "spit|Escupitajo de llama|Criaturas|LEAD",
        "egg_crack|Huevo roto|Criaturas|EGG",
        "infested|Infestado|Criaturas|INFESTED_STONE",
        "item_slime|Slime|Criaturas|SLIME_BALL",
        "item_snowball|Bola de nieve|Criaturas|SNOWBALL",
        "item_cobweb|Telaraña|Criaturas|COBWEB",
        "pause_mob_growth|Crecimiento pausado|Criaturas|CLOCK",
        "reset_mob_growth|Crecimiento reiniciado|Criaturas|CLOCK",

        /* --- estructuras --- */
        "trial_spawner_detection|Detección del generador|Estructuras|TRIAL_SPAWNER",
        "trial_spawner_detection_ominous|Detección ominosa|Estructuras|TRIAL_SPAWNER",
        "vault_connection|Conexión de cámara|Estructuras|VAULT",
        "ominous_spawning|Aparición ominosa|Estructuras|OMINOUS_BOTTLE",
        "raid_omen|Presagio de asalto|Estructuras|OMINOUS_BOTTLE",
        "trial_omen|Presagio de prueba|Estructuras|OMINOUS_BOTTLE",
        "trail|Estela de objeto|Estructuras|VAULT",
    };

    static {
        Map<String, String[]> meta = new LinkedHashMap<>();
        for (String fila : TABLA) {
            String[] p = fila.split("\\|", 4);
            if (p.length == 4) meta.put(p[0], p);
        }

        for (String g : GRUPOS) POR_GRUPO.put(g, new ArrayList<>());

        for (Particle p : Particle.values()) {
            String clave = clave(p);
            if (clave == null) continue;
            String[] m = meta.get(clave);
            String nombre = m == null ? bonito(clave) : m[1];
            String grupo = m == null ? "Otras" : m[2];
            Material icono = m == null ? Material.FIREWORK_STAR : material(m[3]);
            Info info = new Info(p, clave, nombre, grupo, icono, pideDato(p));
            POR_CLAVE.put(clave, info);
            POR_GRUPO.computeIfAbsent(grupo, k -> new ArrayList<>()).add(info);
        }
        for (List<Info> l : POR_GRUPO.values()) {
            l.sort((a, b) -> a.nombre().compareToIgnoreCase(b.nombre()));
        }
    }

    /* ============================================================ consulta */

    public static List<Info> grupo(String nombre) {
        return Collections.unmodifiableList(POR_GRUPO.getOrDefault(nombre, List.of()));
    }

    /** Los grupos que este servidor realmente tiene llenos. */
    public static List<String> gruposConAlgo() {
        List<String> out = new ArrayList<>();
        for (String g : GRUPOS) {
            if (!POR_GRUPO.getOrDefault(g, List.of()).isEmpty()) out.add(g);
        }
        return out;
    }

    public static int cuantas() {
        return POR_CLAVE.size();
    }

    /** Busca por clave (`soul_fire_flame`) o por nombre de enum (`SOUL_FIRE_FLAME`). */
    public static Info info(String s) {
        if (s == null) return null;
        return POR_CLAVE.get(s.trim().toLowerCase(Locale.ROOT).replace(' ', '_'));
    }

    /**
     * La particula que corresponde a un texto del YAML.
     *
     * Primero el registro (que es lo unico estable entre versiones) y despues el
     * enum, para que sigan valiendo los YAML escritos con el nombre de Bukkit.
     */
    public static Particle particula(String s) {
        if (s == null) return null;
        String v = s.trim();
        try {
            Particle p = Registry.PARTICLE_TYPE.get(
                    NamespacedKey.minecraft(v.toLowerCase(Locale.ROOT).replace(' ', '_')));
            if (p != null) return p;
        } catch (Throwable ignored) {
            // el registro no siempre existe con ese nombre; se cae al enum
        }
        try {
            return Particle.valueOf(v.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /* ============================================================== datos */

    /**
     * Fabrica el dato extra que pide una particula a partir de las claves de la
     * linea. Devuelve null si no pide ninguno (o si no se sabe fabricarlo, en
     * cuyo caso `Compat.spawn` pondra el suyo por defecto y algo se vera).
     */
    public static Object datos(Particle p, Args a, Location origen, Entity hacia) {
        if (p == null) return null;
        Class<?> tipo;
        try {
            tipo = p.getDataType();
        } catch (Throwable e) {
            return null;
        }
        if (tipo == null || tipo == Void.class) return null;

        if (tipo == Particle.DustTransition.class) {
            return new Particle.DustTransition(
                    color(a.s("color", "#FFFFFF"), Color.WHITE),
                    color(a.s("color2", a.s("hasta", "#5555FF")), Color.BLUE),
                    (float) a.d("tamano", 1.2));
        }
        if (tipo == Particle.DustOptions.class) {
            return new Particle.DustOptions(
                    color(a.s("color", "#FFFFFF"), Color.WHITE),
                    (float) a.d("tamano", 1.2));
        }
        if (tipo == Particle.Spell.class) {
            return new Particle.Spell(
                    color(a.s("color", "#FFFFFF"), Color.WHITE),
                    (float) a.d("fuerza", a.d("valor", 1.0)));
        }
        if (tipo == Particle.Trail.class) {
            Location destino = hacia == null ? origen.clone().add(0, 2, 0) : hacia.getLocation();
            return new Particle.Trail(destino,
                    color(a.s("color", "#FFFFFF"), Color.WHITE),
                    a.ticks("duracion", 20));
        }
        if (tipo == Vibration.class) {
            Vibration.Destination destino = hacia == null
                    ? new Vibration.Destination.BlockDestination(origen.clone().add(0, 2, 0))
                    : new Vibration.Destination.EntityDestination(hacia);
            return new Vibration(destino, a.ticks("duracion", 20));
        }
        if (tipo == Color.class) {
            return color(a.s("color", "#FFFFFF"), Color.WHITE);
        }
        if (org.bukkit.block.data.BlockData.class.isAssignableFrom(tipo)) {
            Material m = material(a.s("bloque", a.s("material", "STONE")));
            if (!m.isBlock()) m = Material.STONE;
            return m.createBlockData();
        }
        if (ItemStack.class.isAssignableFrom(tipo)) {
            return new ItemStack(material(a.s("item", a.s("material", "STONE"))));
        }
        if (tipo == Float.class) {
            return (float) a.d("valor", 1.0);
        }
        if (tipo == Integer.class) {
            return a.i("retardo", 0);
        }
        return null;
    }

    /** Que claves pide esta particula, para enseñarlo en el menu. */
    private static String pideDato(Particle p) {
        Class<?> tipo;
        try {
            tipo = p.getDataType();
        } catch (Throwable e) {
            return "";
        }
        if (tipo == null || tipo == Void.class) return "";
        if (tipo == Particle.DustTransition.class) return "color, color2 y tamano";
        if (tipo == Particle.DustOptions.class) return "color y tamano";
        if (tipo == Particle.Spell.class) return "color y fuerza";
        if (tipo == Particle.Trail.class) return "hacia, color y duracion";
        if (tipo == Vibration.class) return "hacia y duracion";
        if (tipo == Color.class) return "color";
        if (org.bukkit.block.data.BlockData.class.isAssignableFrom(tipo)) return "bloque";
        if (ItemStack.class.isAssignableFrom(tipo)) return "item";
        if (tipo == Float.class) return "valor";
        if (tipo == Integer.class) return "retardo";
        return tipo.getSimpleName().toLowerCase(Locale.ROOT);
    }

    /* ============================================================== ayudas */

    private static String clave(Particle p) {
        try {
            return p.getKey().getKey();
        } catch (Throwable e) {
            return p.name().toLowerCase(Locale.ROOT);
        }
    }

    /** "sculk_charge_pop" -> "Sculk charge pop", para las que no estan en la tabla. */
    private static String bonito(String clave) {
        String s = clave.replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Material material(String s) {
        Material m = Material.matchMaterial(s.trim().toUpperCase(Locale.ROOT));
        return m == null ? Material.FIREWORK_STAR : m;
    }

    /** `#FF0000`, `FF0000` o `255,0,0`. */
    public static Color color(String s, Color pordefecto) {
        if (s == null || s.isBlank()) return pordefecto;
        String v = s.trim();
        if (v.indexOf(',') > 0) {
            String[] p = v.split(",");
            if (p.length >= 3) {
                try {
                    return Color.fromRGB(
                            limite(Integer.parseInt(p[0].trim())),
                            limite(Integer.parseInt(p[1].trim())),
                            limite(Integer.parseInt(p[2].trim())));
                } catch (NumberFormatException e) {
                    return pordefecto;
                }
            }
        }
        if (v.startsWith("#")) v = v.substring(1);
        try {
            return Color.fromRGB(Integer.parseInt(v, 16) & 0xFFFFFF);
        } catch (NumberFormatException e) {
            return pordefecto;
        }
    }

    private static int limite(int n) {
        return Math.max(0, Math.min(255, n));
    }
}
