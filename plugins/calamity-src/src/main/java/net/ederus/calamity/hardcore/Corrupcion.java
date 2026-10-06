package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.block.Chest;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.block.data.type.Wall;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * La corrupción (pedido de Dosa, 2026-10-06): en Calamity nada de lo que construye un jugador dura. Sin
 * granjas AFK ni bases que aguanten: todo bloque que un jugador pone fuera de la zona spawn se corrompe a los
 * pocos minutos.
 *
 * Como funciona:
 *  1. Se anota cada bloque que pone un jugador que cuenta (ni creativo ni espectador: Hardcore.cuenta) fuera de
 *     la zona spawn: BlockPlaceEvent (tambien BlockMultiPlaceEvent, y la otra mitad de puertas, camas y plantas
 *     altas) y el agua, la lava o la nieve polvo que vacia con un cubo. Se apunta que bloque era y cuando.
 *  2. Cada corrupcion.cada-minutos se hace un barrido: lo anotado hace mas de corrupcion.edad-minutos se
 *     corrompe, como mucho por-tick bloques por tick. Lo que esta en un chunk descargado espera: se hace en
 *     cuanto el chunk se carga, sin forzar ninguna carga.
 *  3. Segun la forma del bloque y el color del bioma donde esta:
 *       entero -> un entero de la paleta del color (orientacion y hojas persistentes conservadas);
 *       losa   -> la losa de la paleta (arriba, abajo o doble, y con agua o sin ella);
 *       escalera -> la escalera de la paleta (orientacion, mitad, forma y agua);
 *       muro   -> el muro de la paleta, o un entero si la paleta no tiene;
 *       lo funcional (corrupcion.funcionales) y lo que no es solido -> se pudre: desaparece sin soltar nada,
 *       con el inventario vaciado antes.
 *     Lo exento (corrupcion.exentos: antorchas, fogatas, que son las hogueras de calma, y desde la 1.16.4 los
 *     faroles, el de siempre, el de almas y los ocho de cobre) no se toca nunca.
 *  4. Lo corrompido lleva una marca en el chunk (Marcas.CORRUPCION, un INTEGER_ARRAY de posiciones): no se
 *     vuelve a anotar y al romperlo no suelta nada. Si una explosion, un wither o un enderman se lo llevan,
 *     desaparece sin soltar nada; si un piston lo mueve, la marca va con el, y si lo rompe (las hojas),
 *     desaparece sin soltar nada.
 *
 * Si el bloque anotado se rompe o lo cambia otra cosa antes del barrido, se olvida. Lo que el propio jugador
 * le hace al bloque (descortezarlo, ararlo, encerarlo, que se oxide, que el polvo de hormigon frague, que caiga
 * la arena) no cuenta como cambio: se sigue la pista y se corrompe igual.
 *
 * Lo que pone el propio plugin (ruinas, bovedas) no pasa por BlockPlaceEvent y no se anota.
 *
 * Reinicios: lo anotado vive en plugins/Calamity/corrupcion/<mundo>.txt, agrupado por chunk, con la hora en
 * que se puso (en tiempo real: el servidor apagado tambien cuenta). Cada fichero lleva el UID de su mundo: si el
 * mundo se ha regenerado con el mismo nombre, lo anotado de antes se descarta. Las marcas de lo corrompido van
 * con el mundo (en el chunk).
 *
 * Bedrock (Geyser): poner bloques y vaciar cubos llega igual; las particulas son BLOCK y el sonido es vanilla.
 *
 * Staff: /calamity corruption info | now | clear <radio>. Autotest: /calamity selftest corruption.
 */
final class Corrupcion implements Listener {

    private static final String SECCION = "corrupcion";
    private static final String CARPETA = "corrupcion";

    // ================================================================== valores de serie

    /**
     * Lo que no se toca nunca: las antorchas, las fogatas (las hogueras de calma son fogatas de almas) y, desde la
     * 1.16.4, los faroles (Dosa: "los faroles no se pudren"): el de siempre, el de almas y los ocho de cobre
     * (normal, expuesto, erosionado y oxidado, con cera y sin ella). Los Faroles de Tranquilidad no se colocan.
     */
    static final List<String> EXENTOS = List.of("torch", "wall_torch", "soul_torch", "soul_wall_torch",
            "copper_torch", "copper_wall_torch", "#campfires", "lantern", "soul_lantern", "*copper_lantern");

    /** Si la lista nombra ese bloque, por su nombre o por un "*final" (las "#etiquetas" piden servidor: no se miran). */
    static boolean cubre(List<String> lista, String bloque) {
        String b = bloque.toLowerCase(Locale.ROOT);
        for (String x : lista) {
            String s = x == null ? "" : x.trim().toLowerCase(Locale.ROOT);
            if (s.equals(b) || s.startsWith("*") && s.length() > 1 && b.endsWith(s.substring(1))) return true;
        }
        return false;
    }

    /** Los diez faroles que se pueden poner (1.16.4: exentos). */
    static final List<String> FAROLES = List.of("lantern", "soul_lantern", "copper_lantern", "exposed_copper_lantern",
            "weathered_copper_lantern", "oxidized_copper_lantern", "waxed_copper_lantern", "waxed_exposed_copper_lantern",
            "waxed_weathered_copper_lantern", "waxed_oxidized_copper_lantern");

    /**
     * Lo que se pudre en vez de corromperse. "#tag" es una etiqueta de bloques de Minecraft y "*fin" todo
     * bloque cuyo nombre acaba asi (los ocho bulbos de cobre). Lo que no existe en el servidor se ignora.
     */
    static final List<String> FUNCIONALES = List.of(
            // Contenedores
            "hopper", "chest", "trapped_chest", "#copper_chests", "barrel", "#shulker_boxes", "ender_chest",
            "furnace", "blast_furnace", "smoker", "dispenser", "dropper", "crafter",
            // Redstone
            "piston", "sticky_piston", "observer", "redstone_wire", "redstone_torch", "redstone_wall_torch",
            "repeater", "comparator", "redstone_block", "redstone_lamp", "*copper_bulb", "daylight_detector",
            "target", "note_block", "jukebox", "tnt", "#lightning_rods", "sculk_sensor", "calibrated_sculk_sensor",
            "sculk_shrieker", "sculk_catalyst", "#rails", "lever", "#buttons", "#pressure_plates", "tripwire_hook",
            "tripwire",
            // Puertas, trampillas, vallas, camas y lo que se trepa (escaleras de mano, andamios, enredaderas)
            "#trapdoors", "#doors", "#fences", "#fence_gates", "#beds", "#climbable",
            // Mesas de crafteo y de trabajo
            "crafting_table", "cartography_table", "fletching_table", "smithing_table", "loom", "stonecutter",
            "grindstone", "brewing_stand", "lectern", "composter", "#cauldrons", "enchanting_table", "#anvil", "bell",
            "beacon", "conduit", "respawn_anchor", "lodestone", "#beehives", "#wooden_shelves", "chiseled_bookshelf",
            "decorated_pot",
            // Camas elasticas, carteles y alfombras
            "slime_block", "honey_block", "#all_signs", "#banners", "#wool_carpets", "moss_carpet", "pale_moss_carpet",
            // Flores y cultivos
            "#flowers", "#crops", "attached_pumpkin_stem", "attached_melon_stem", "#saplings", "sugar_cane", "cactus",
            "bamboo", "bamboo_sapling", "sweet_berry_bush", "nether_wart", "cocoa", "kelp", "kelp_plant", "#cave_vines",
            "#candles", "#candle_cakes", "cake", "powder_snow",
            // Generadores de mobs (si alguien llegara a ponerlos)
            "spawner", "trial_spawner", "vault");

    /** El color de cada bioma de Panacea. "*" = cualquier otro bioma. */
    static final Map<String, List<String>> BIOMAS = mapa(
            "rojo", List.of("panacea/crimson_organism"),
            "verde", List.of("panacea/creeper_dominion", "panacea/horsetail_tropics", "panacea/polypore_plains",
                    "panacea/wildflower_bog", "panacea/hungering_jungle", "panacea/conure_conclave",
                    "panacea/ravenous_greenwood"),
            "amarillo", List.of("panacea/bamboo_valley", "panacea/honeybee_biome", "panacea/sweltering_swamp",
                    "panacea/quicksand_springs"),
            "negro", List.of("panacea/condemned_taiga", "*"));

    /**
     * Las paletas de las capturas de Dosa. "a|b" = a, y si a no existe en este servidor, b: el cinabrio llega
     * con Minecraft 26.2 y en 26.1.2 se usa lo mas parecido. Sin luz y sin nada funcional.
     */
    static final Map<String, Map<String, List<String>>> PALETAS = mapa(
            "rojo", mapa(
                    "enteros", List.of("netherrack", "cinnabar|red_terracotta", "nether_wart_block"),
                    "losa", List.of("cinnabar_slab|red_nether_brick_slab", "polished_cinnabar_slab|red_nether_brick_slab"),
                    "escalera", List.of("cinnabar_stairs|red_nether_brick_stairs",
                            "polished_cinnabar_stairs|red_nether_brick_stairs"),
                    "muro", List.of("cinnabar_wall|red_nether_brick_wall", "polished_cinnabar_wall|red_nether_brick_wall")),
            "verde", mapa(
                    "enteros", List.of("moss_block", "green_wool", "mossy_cobblestone", "warped_planks", "azalea_leaves"),
                    "losa", List.of("warped_slab"),
                    "escalera", List.of("warped_stairs", "mossy_cobblestone_stairs"),
                    "muro", List.of("mossy_cobblestone_wall")),
            "amarillo", mapa(
                    "enteros", List.of("yellow_terracotta", "stripped_bamboo_block", "honeycomb_block"),
                    "losa", List.of("bamboo_mosaic_slab"),
                    "escalera", List.of("bamboo_mosaic_stairs"),
                    "muro", List.of()),
            "negro", mapa(
                    "enteros", List.of("deepslate", "cobbled_deepslate", "tuff", "polished_tuff"),
                    "losa", List.of("tuff_slab"),
                    "escalera", List.of("tuff_stairs"),
                    "muro", List.of("tuff_wall", "cobbled_deepslate_wall")));

    static final String TEXTO_AVISO = "En Calamity nada de lo que construyes dura. La corrupción lo reclama.";

    private static <V> Map<String, V> mapa(Object... kv) {
        Map<String, V> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            @SuppressWarnings("unchecked") V v = (V) kv[i + 1];
            m.put((String) kv[i], v);
        }
        return Collections.unmodifiableMap(m);
    }

    // ================================================================== estado

    private final Hardcore hc;
    final Registro registro = new Registro();
    private final File carpeta;
    /** El UID de cada mundo tal como se guardo; si al verlo cargado no coincide, es otro mundo con el mismo nombre. */
    private final Map<String, UUID> uids = new HashMap<>();
    private final Set<String> comprobados = new HashSet<>();
    /** Lo que falta por hacer de este barrido (o de un chunk que se acaba de cargar). */
    private final ArrayDeque<Tarea> cola = new ArrayDeque<>();
    /** Mundo -> chunks con algo vencido que estaban descargados: se hacen al cargarse. */
    private final Map<String, Set<Long>> atrasados = new HashMap<>();
    /** Quien ya ha leido el aviso en esta entrada a Calamity. */
    private final Set<UUID> avisados = new HashSet<>();
    /** Un sonido por chunk y barrido: una base entera no suena quinientas veces. */
    private final Set<String> sonados = new HashSet<>();
    private final Object cerrojo = new Object();
    private long secuencia;
    private long escrita;
    private BukkitTask trabajo;
    private long proximo;
    /** Tras parar(): ni barridos ni comandos (el registro de subcomandos sigue apuntando aqui). */
    private boolean parado;
    private boolean sucio;
    private int segundosSinGuardar;
    private Object vista;
    private Ajustes cache;
    private Barrido actual;
    private Barrido ultimo;
    /** Lo hecho al cargarse un chunk desde el ultimo barrido: va en la telemetria del siguiente. */
    private int alCargar;

    Corrupcion(Hardcore hc) {
        this.hc = hc;
        this.carpeta = new File(hc.plugin().getDataFolder(), CARPETA);
        cargar();
        // Lo guardado de un mundo que ya esta cargado se compara ya con su UID (un mundo regenerado no hereda nada).
        for (World w : hc.plugin().getServer().getWorlds()) if (registro.hay(w.getName())) comprobarMundo(w);
        proximo = System.currentTimeMillis() + ajustes().cadaMs();
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("corrupcion", this::autotest);
        Subcomandos.staff().registrar("corruption",
                "corruption <info|now|clear <radius>>: la corrupción de lo que construyen los jugadores (estado, barrido ya, olvidar lo anotado alrededor)",
                Subcomandos.PERMISO, this::comando, args -> {
                    if (args.length == 2) return List.of("info", "now", "clear");
                    if (args.length == 3 && "clear".equalsIgnoreCase(args[1])) return List.of("16", "32", "64");
                    return List.of();
                });
    }

    // ================================================================== nucleo (puro)

    /** Un bloque anotado: que se puso (nombre del Material), cuando (millis) y si es un fluido de cubo. */
    record Anotado(String material, long cuando, boolean fluido) {
    }

    /** Una posicion pendiente de este barrido. */
    record Tarea(String mundo, long pos) {
    }

    enum Forma { ENTERO, LOSA, ESCALERA, MURO }

    /** Lo que le toca a un bloque. */
    enum Destino { EXENTO, PUDRIR, ENTERO, LOSA, ESCALERA, MURO }

    enum Resultado { CORROMPIDO, PODRIDO, OLVIDADO }

    /** BlockPos.asLong de Minecraft: x 26 bits, z 26 bits, y 12 bits (de -2048 a 2047). */
    static long pos(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    static int px(long p) {
        return (int) (p >> 38);
    }

    static int py(long p) {
        return (int) (p << 52 >> 52);
    }

    static int pz(long p) {
        return (int) (p << 26 >> 38);
    }

    /** La clave de chunk de Paper (Chunk.getChunkKey). */
    static long chunk(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) | ((long) cz & 0xFFFFFFFFL) << 32;
    }

    static int cx(long k) {
        return (int) k;
    }

    static int cz(long k) {
        return (int) (k >>> 32);
    }

    /** La posicion dentro de su chunk, para la marca de lo corrompido: x 4 bits, z 4 bits, y + 2048 12 bits. */
    static int local(int x, int y, int z) {
        return (x & 15) | (z & 15) << 4 | ((y + 2048) & 0xFFF) << 8;
    }

    /** SplitMix64: mezcla una semilla para elegir de la paleta sin Random (regla de la casa en hardcore/). */
    static long mezcla(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** La semilla de un bloque: su posicion y cuando se puso. Al azar para el jugador, repetible para la prueba. */
    static long semilla(long pos, long cuando) {
        return mezcla(pos * 0x9E3779B97F4A7C15L + cuando);
    }

    /** Uno de la lista, segun la semilla; null si esta vacia. */
    static <T> T elegir(List<T> lista, long semilla) {
        if (lista == null || lista.isEmpty()) return null;
        return lista.get((int) Math.floorMod(mezcla(semilla), (long) lista.size()));
    }

    /** "a|b|c": la primera que el resolutor reconoce; null si ninguna. */
    static <T> T primera(String cadena, Function<String, T> resolutor) {
        if (cadena == null) return null;
        for (String alt : cadena.split("\\|")) {
            String n = alt.trim();
            if (n.isEmpty()) continue;
            T t = resolutor.apply(n);
            if (t != null) return t;
        }
        return null;
    }

    private static String sinNamespace(String s) {
        if (s == null) return "";
        int dos = s.indexOf(':');
        return (dos < 0 ? s : s.substring(dos + 1)).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * El color de un bioma ("bracken:panacea/crimson_organism" o sin namespace). Gana el mas concreto: el bioma
     * exacto, luego "panacea/*", luego "*". Null si no casa con nada.
     */
    static String colorDe(Map<String, List<String>> biomas, String bioma) {
        String b = sinNamespace(bioma);
        String prefijo = null, comodin = null;
        for (Map.Entry<String, List<String>> e : biomas.entrySet()) {
            if (e.getValue() == null) continue;
            for (String v : e.getValue()) {
                String x = sinNamespace(v);
                if (x.equals(b)) return e.getKey();
                if (x.equals("*")) {
                    if (comodin == null) comodin = e.getKey();
                } else if (x.endsWith("/*") && b.startsWith(x.substring(0, x.length() - 1)) && prefijo == null) {
                    prefijo = e.getKey();
                }
            }
        }
        return prefijo != null ? prefijo : comodin;
    }

    /** Lo anotado, por mundo y chunk. Sin Bukkit: lo prueba el autotest sin servidor. Solo hilo principal. */
    static final class Registro {
        /** Mundo (nombre) -> chunk -> posicion -> anotado. */
        final Map<String, Map<Long, Map<Long, Anotado>>> mundos = new HashMap<>();
        private int total;

        Anotado de(String mundo, int x, int y, int z) {
            Map<Long, Anotado> m = enChunk(mundo, chunk(x >> 4, z >> 4));
            return m == null ? null : m.get(pos(x, y, z));
        }

        Anotado de(String mundo, long p) {
            return de(mundo, px(p), py(p), pz(p));
        }

        Map<Long, Anotado> enChunk(String mundo, long ck) {
            Map<Long, Map<Long, Anotado>> c = mundos.get(mundo);
            return c == null ? null : c.get(ck);
        }

        void anotar(String mundo, int x, int y, int z, Anotado a) {
            Map<Long, Anotado> m = mundos.computeIfAbsent(mundo, k -> new HashMap<>())
                    .computeIfAbsent(chunk(x >> 4, z >> 4), k -> new HashMap<>());
            if (m.put(pos(x, y, z), a) == null) total++;
        }

        Anotado olvidar(String mundo, int x, int y, int z) {
            Map<Long, Map<Long, Anotado>> c = mundos.get(mundo);
            if (c == null) return null;
            long ck = chunk(x >> 4, z >> 4);
            Map<Long, Anotado> m = c.get(ck);
            if (m == null) return null;
            Anotado a = m.remove(pos(x, y, z));
            if (a != null) {
                total--;
                if (m.isEmpty()) c.remove(ck);
                if (c.isEmpty()) mundos.remove(mundo);
            }
            return a;
        }

        boolean hay(String mundo) {
            Map<Long, Map<Long, Anotado>> c = mundos.get(mundo);
            return c != null && !c.isEmpty();
        }

        int total() {
            return total;
        }

        int total(String mundo) {
            Map<Long, Map<Long, Anotado>> c = mundos.get(mundo);
            if (c == null) return 0;
            int n = 0;
            for (Map<Long, Anotado> m : c.values()) n += m.size();
            return n;
        }

        /** Lo de ese mundo, entero (un mundo regenerado). Devuelve cuantos. */
        int olvidarMundo(String mundo) {
            int n = total(mundo);
            mundos.remove(mundo);
            total -= n;
            return n;
        }

        /** Lo que esta a radio o menos (en horizontal, a cualquier altura) de x, z. Devuelve cuantos. */
        int olvidarRadio(String mundo, int x, int z, int radio) {
            Map<Long, Map<Long, Anotado>> c = mundos.get(mundo);
            if (c == null || radio < 0) return 0;
            long r2 = (long) radio * radio;
            int n = 0;
            for (Iterator<Map.Entry<Long, Map<Long, Anotado>>> it = c.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Long, Map<Long, Anotado>> e = it.next();
                int bx = cx(e.getKey()) << 4, bz = cz(e.getKey()) << 4;
                // Del borde del chunk mas cercano: si ni ese llega, el chunk entero queda fuera.
                long dx = Math.max(0, Math.max(bx - x, x - (bx + 15))), dz = Math.max(0, Math.max(bz - z, z - (bz + 15)));
                if (dx * dx + dz * dz > r2) continue;
                for (Iterator<Long> ip = e.getValue().keySet().iterator(); ip.hasNext(); ) {
                    long p = ip.next();
                    long ex = px(p) - x, ez = pz(p) - z;
                    if (ex * ex + ez * ez > r2) continue;
                    ip.remove();
                    n++;
                }
                if (e.getValue().isEmpty()) it.remove();
            }
            if (c.isEmpty()) mundos.remove(mundo);
            total -= n;
            return n;
        }

        /** Las posiciones de un chunk que ya llevan edadMs o mas anotadas. */
        static List<Long> vencidos(Map<Long, Anotado> enChunk, long ahora, long edadMs) {
            List<Long> out = new ArrayList<>();
            if (enChunk == null) return out;
            for (Map.Entry<Long, Anotado> e : enChunk.entrySet()) {
                if (ahora - e.getValue().cuando() >= edadMs) out.add(e.getKey());
            }
            return out;
        }

        /**
         * El fichero de un mundo: una cabecera con su nombre y su UID, y lo anotado agrupado por chunk.
         *   mundo <uid|-> <nombre>
         *   c <cx> <cz>
         *   <x> <y> <z> <cuando> <MATERIAL> <b|f>
         */
        String texto(String mundo, UUID uid) {
            StringBuilder sb = new StringBuilder(4096);
            sb.append("# Calamity · la corrupción: bloques puestos por jugadores que aún no se han corrompido.\n");
            sb.append("# Lo escribe el plugin; no lo edites con el servidor encendido.\n");
            sb.append("mundo ").append(uid == null ? "-" : uid.toString()).append(' ').append(mundo).append('\n');
            Map<Long, Map<Long, Anotado>> c = mundos.get(mundo);
            if (c == null) return sb.toString();
            for (Map.Entry<Long, Map<Long, Anotado>> e : c.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                sb.append("c ").append(cx(e.getKey())).append(' ').append(cz(e.getKey())).append('\n');
                for (Map.Entry<Long, Anotado> b : e.getValue().entrySet()) {
                    long p = b.getKey();
                    Anotado a = b.getValue();
                    sb.append(px(p)).append(' ').append(py(p)).append(' ').append(pz(p)).append(' ')
                            .append(a.cuando()).append(' ').append(a.material()).append(' ')
                            .append(a.fluido() ? 'f' : 'b').append('\n');
                }
            }
            return sb.toString();
        }

        /** Lee un fichero escrito por texto() en r. Lo que no se entiende se salta. Devuelve cuantos bloques. */
        static int leer(Registro r, String texto, Map<String, UUID> uids) {
            String mundo = null;
            int n = 0;
            for (String linea : texto.split("\\R")) {
                String l = linea.strip();
                if (l.isEmpty() || l.startsWith("#") || l.startsWith("c ")) continue;
                if (l.startsWith("mundo ")) {
                    String[] p = l.split(" ", 3);
                    if (p.length < 3 || p[2].isBlank()) {
                        mundo = null;
                        continue;
                    }
                    mundo = p[2].strip();
                    if (!"-".equals(p[1])) {
                        try {
                            uids.put(mundo, UUID.fromString(p[1]));
                        } catch (IllegalArgumentException ignorada) {
                            // Sin UID valido: se comprobara contra el mundo que haya.
                        }
                    }
                    continue;
                }
                if (mundo == null) continue;
                String[] p = l.split("\\s+");
                if (p.length != 6 || p[4].isEmpty()) continue;
                try {
                    int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
                    long cuando = Long.parseLong(p[3]);
                    boolean fluido = "f".equals(p[5]);
                    if (!fluido && !"b".equals(p[5])) continue;
                    if (r.de(mundo, x, y, z) == null) n++;
                    r.anotar(mundo, x, y, z, new Anotado(p[4].toUpperCase(Locale.ROOT), cuando, fluido));
                } catch (NumberFormatException ignorada) {
                    // Una linea rota no tumba el resto.
                }
            }
            return n;
        }
    }

    /** Lo hecho en un barrido, para /calamity corruption info y la telemetria. */
    private static final class Barrido {
        final long inicio;
        final String motivo;
        int encolados, corrompidos, podridos, olvidados, esperan;
        long fin;

        Barrido(long inicio, String motivo) {
            this.inicio = inicio;
            this.motivo = motivo;
        }
    }

    // ================================================================== ajustes

    /** Una paleta resuelta en este servidor. */
    record Gama(List<Material> enteros, List<Material> losas, List<Material> escaleras, List<Material> muros) {

        List<Material> de(Forma f) {
            return switch (f) {
                case ENTERO -> enteros;
                case LOSA -> losas;
                case ESCALERA -> escaleras;
                case MURO -> muros;
            };
        }

        List<Material> todos() {
            List<Material> out = new ArrayList<>(enteros);
            out.addAll(losas);
            out.addAll(escaleras);
            out.addAll(muros);
            return out;
        }
    }

    /** Lo que se lee de hardcore.corrupcion, con los valores de serie de arriba para lo que falte. */
    record Ajustes(boolean activa, long cadaMs, long edadMs, int porTick, boolean particulas, String sonido,
                   double radioSonido, boolean aviso, String textoAviso, Set<Material> exentos,
                   Set<Material> funcionales, Map<String, List<String>> biomas, Map<String, Gama> gamas,
                   Set<Material> corruptos, List<String> avisos) {

        static Ajustes de(ConfigurationSection s) {
            if (s == null) s = new YamlConfiguration();
            List<String> avisos = new ArrayList<>();
            Set<Material> exentos = materiales(lista(s, "exentos", EXENTOS), avisos, "exentos");
            Set<Material> funcionales = materiales(lista(s, "funcionales", FUNCIONALES), avisos, "funcionales");
            // Lo exento gana: una fogata no se pudre aunque alguien la meta en funcionales.
            funcionales.removeAll(exentos);

            Map<String, List<String>> biomas = new LinkedHashMap<>();
            ConfigurationSection b = s.getConfigurationSection("biomas");
            Set<String> colores = new LinkedHashSet<>(BIOMAS.keySet());
            if (b != null) colores.addAll(b.getKeys(false));
            for (String color : colores) biomas.put(color, lista(b, color, BIOMAS.get(color)));

            Map<String, Gama> gamas = new LinkedHashMap<>();
            ConfigurationSection p = s.getConfigurationSection("paletas");
            Set<String> conPaleta = new LinkedHashSet<>(PALETAS.keySet());
            if (p != null) conPaleta.addAll(p.getKeys(false));
            Set<Material> corruptos = EnumSet.noneOf(Material.class);
            for (String color : conPaleta) {
                ConfigurationSection pc = p == null ? null : p.getConfigurationSection(color);
                Map<String, List<String>> def = PALETAS.getOrDefault(color, Map.of());
                Gama g = new Gama(
                        resueltos(lista(pc, "enteros", def.get("enteros")), Forma.ENTERO, funcionales, avisos, color + ".enteros"),
                        resueltos(lista(pc, "losa", def.get("losa")), Forma.LOSA, funcionales, avisos, color + ".losa"),
                        resueltos(lista(pc, "escalera", def.get("escalera")), Forma.ESCALERA, funcionales, avisos, color + ".escalera"),
                        resueltos(lista(pc, "muro", def.get("muro")), Forma.MURO, funcionales, avisos, color + ".muro"));
                gamas.put(color, g);
                corruptos.addAll(g.todos());
            }
            String sonido = s.getString("sonido", "block.sculk.spread");
            return new Ajustes(
                    s.getBoolean("activa", true),
                    Math.max(6_000L, Math.round(s.getDouble("cada-minutos", 5) * 60_000)),
                    Math.max(0L, Math.round(s.getDouble("edad-minutos", 5) * 60_000)),
                    Math.max(1, Math.min(2000, s.getInt("por-tick", 50))),
                    s.getBoolean("particulas", true),
                    sonido == null ? "" : sonido.trim(),
                    Math.max(0, s.getDouble("radio-sonido", 12)),
                    s.getBoolean("aviso", true),
                    s.getString("texto-aviso", TEXTO_AVISO),
                    exentos, funcionales, biomas, gamas, corruptos, avisos);
        }

        static Ajustes defecto() {
            return de(null);
        }

        /** La paleta del color de ese bioma; la negra (o la primera con algo) si su color no tiene. */
        Gama gama(String bioma) {
            String color = colorDe(biomas, bioma);
            Gama g = color == null ? null : gamas.get(color);
            if (g != null && !g.enteros().isEmpty()) return g;
            Gama negro = gamas.get("negro");
            if (negro != null && !negro.enteros().isEmpty()) return negro;
            for (Gama otra : gamas.values()) if (!otra.enteros().isEmpty()) return otra;
            return null;
        }
    }

    /** La lista de esa clave; sin la clave, la de serie. Una lista vacia ([]) es vacia de verdad. */
    private static List<String> lista(ConfigurationSection s, String clave, List<String> def) {
        if (s != null && s.isList(clave)) return s.getStringList(clave);
        if (s != null && s.isString(clave)) return List.of(s.getString(clave));
        return def == null ? List.of() : def;
    }

    /** Nombres, "#etiquetas" y "*finales" a Materials de bloque. Lo que no existe va a avisos. */
    static Set<Material> materiales(List<String> nombres, List<String> avisos, String donde) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String n : nombres) {
            if (n == null || n.isBlank()) continue;
            String s = n.trim().toLowerCase(Locale.ROOT);
            if (s.startsWith("#")) {
                NamespacedKey k = NamespacedKey.fromString(s.substring(1));
                Tag<Material> t = k == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, k, Material.class);
                if (t == null) avisos.add(donde + ": la etiqueta " + s + " no existe");
                else out.addAll(t.getValues());
            } else if (s.startsWith("*")) {
                String fin = s.substring(1).toUpperCase(Locale.ROOT);
                int antes = out.size();
                for (Material m : Material.values()) {
                    if (m.isBlock() && !m.name().startsWith("LEGACY_") && m.name().endsWith(fin)) out.add(m);
                }
                if (out.size() == antes) avisos.add(donde + ": ningún bloque acaba en " + fin.toLowerCase(Locale.ROOT));
            } else {
                Material m = Material.matchMaterial(s);
                if (m == null || !m.isBlock()) avisos.add(donde + ": " + s + " no es un bloque de este servidor");
                else out.add(m);
            }
        }
        return out;
    }

    /** Cada cadena de una paleta a su Material, si es de la forma pedida, no da luz y no es funcional. */
    private static List<Material> resueltos(List<String> cadenas, Forma forma, Set<Material> funcionales, List<String> avisos,
                                       String donde) {
        List<Material> out = new ArrayList<>();
        for (String c : cadenas) {
            Material m = primera(c, n -> {
                Material x = Material.matchMaterial(n);
                return x != null && sirve(x, forma, funcionales) ? x : null;
            });
            if (m == null) avisos.add(donde + ": " + c + " no sirve en este servidor (no existe, da luz, es funcional o no es "
                    + forma.name().toLowerCase(Locale.ROOT) + ")");
            else out.add(m);
        }
        return Collections.unmodifiableList(out);
    }

    /** Si ese bloque vale para esa forma de una paleta. */
    static boolean sirve(Material m, Forma forma, Set<Material> funcionales) {
        if (m == null || !m.isBlock() || m.isAir() || funcionales.contains(m)) return false;
        BlockData d = m.createBlockData();
        if (d.getLightEmission() > 0) return false;
        return switch (forma) {
            case LOSA -> d instanceof Slab;
            case ESCALERA -> d instanceof Stairs;
            case MURO -> d instanceof Wall;
            case ENTERO -> !(d instanceof Slab) && !(d instanceof Stairs) && !(d instanceof Wall) && m.isSolid();
        };
    }

    /** Los ajustes de la config viva; se releen solo cuando cambia (un /calamity reload la cambia entera). */
    Ajustes ajustes() {
        Object v = hc.plugin().getConfig();
        if (cache == null || v != vista) {
            vista = v;
            cache = Ajustes.de(hc.cfg().getConfigurationSection(SECCION));
            if (!cache.avisos().isEmpty()) {
                hc.plugin().getLogger().warning("[Calamity] Corrupción: " + String.join("; ", cache.avisos()));
            }
        }
        return cache;
    }

    // ================================================================== forma y paleta

    /** Que le toca a un bloque de ese tipo y esa forma. */
    static Destino destino(Material m, BlockData d, Ajustes a) {
        if (a.exentos().contains(m)) return Destino.EXENTO;
        if (a.funcionales().contains(m)) return Destino.PUDRIR;
        if (d instanceof Slab) return Destino.LOSA;
        if (d instanceof Stairs) return Destino.ESCALERA;
        if (d instanceof Wall) return Destino.MURO;
        return m.isSolid() ? Destino.ENTERO : Destino.PUDRIR;
    }

    /**
     * El bloque corrompido: el de la paleta con la forma del viejo. Una forma que la paleta no tiene pasa a bloque
     * entero. Null si la paleta no tiene ni enteros (entonces se pudre).
     */
    static BlockData corrupta(BlockData viejo, Destino d, Gama g, long semilla) {
        if (g == null) return null;
        switch (d) {
            case LOSA -> {
                Material m = elegir(g.losas(), semilla);
                if (m != null && m.createBlockData() instanceof Slab s && viejo instanceof Slab v) {
                    s.setType(v.getType());
                    s.setWaterlogged(v.isWaterlogged() && v.getType() != Slab.Type.DOUBLE);
                    return s;
                }
            }
            case ESCALERA -> {
                Material m = elegir(g.escaleras(), semilla);
                if (m != null && m.createBlockData() instanceof Stairs s && viejo instanceof Stairs v) {
                    s.setFacing(v.getFacing());
                    s.setHalf(v.getHalf());
                    s.setShape(v.getShape());
                    s.setWaterlogged(v.isWaterlogged());
                    return s;
                }
            }
            case MURO -> {
                Material m = elegir(g.muros(), semilla);
                if (m != null && m.createBlockData() instanceof Wall w && viejo instanceof Wall v) {
                    w.setUp(v.isUp());
                    for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
                        w.setHeight(f, v.getHeight(f));
                    }
                    w.setWaterlogged(v.isWaterlogged());
                    return w;
                }
            }
            default -> {
                // ENTERO: abajo.
            }
        }
        // Un entero; tambien la forma que la paleta no tiene (el muro amarillo).
        Material m = elegir(g.enteros(), semilla);
        if (m == null) return null;
        BlockData n = m.createBlockData();
        if (n instanceof Orientable o && viejo instanceof Orientable vo && o.getAxes().contains(vo.getAxis())) {
            o.setAxis(vo.getAxis());
        }
        // Las hojas de la paleta no se caen solas: no hay tronco al lado.
        if (n instanceof Leaves l) l.setPersistent(true);
        if (n instanceof Waterlogged w && viejo instanceof Waterlogged vw) w.setWaterlogged(vw.isWaterlogged());
        return n;
    }

    /** La otra mitad de una puerta o una planta alta, o la otra parte de una cama; null si es de un solo bloque. */
    static Block otraParte(Block b, BlockData d) {
        if (d instanceof Bed bed) {
            return b.getRelative(bed.getPart() == Bed.Part.FOOT ? bed.getFacing() : bed.getFacing().getOppositeFace());
        }
        // Escaleras y trampillas tambien son Bisected, pero de un solo bloque.
        if (d instanceof Bisected bi && !(d instanceof Stairs) && !(d instanceof TrapDoor)) {
            return b.getRelative(bi.getHalf() == Bisected.Half.BOTTOM ? BlockFace.UP : BlockFace.DOWN);
        }
        return null;
    }

    /** El fluido que deja un cubo (agua tambien los de peces, ajolote y renacuajo); null si no deja nada. */
    static Material fluidoDe(Material cubo) {
        if (cubo == null) return null;
        if (cubo == Material.LAVA_BUCKET) return Material.LAVA;
        if (cubo == Material.POWDER_SNOW_BUCKET) return Material.POWDER_SNOW;
        if (cubo == Material.BUCKET || cubo == Material.MILK_BUCKET) return null;
        return cubo.name().endsWith("_BUCKET") ? Material.WATER : null;
    }

    /** Las plantas que crecen en columna desde lo que se planto: al pudrirse la base se va la columna entera. */
    private static final class Columnas {
        static final Map<Material, Set<Material>> ARRIBA = new HashMap<>();
        static final Map<Material, Set<Material>> ABAJO = new HashMap<>();
        static final Set<Material> DE_AGUA = EnumSet.of(Material.KELP, Material.KELP_PLANT, Material.SEAGRASS,
                Material.TALL_SEAGRASS);

        static {
            poner(ARRIBA, EnumSet.of(Material.SUGAR_CANE));
            poner(ARRIBA, EnumSet.of(Material.CACTUS, Material.CACTUS_FLOWER));
            poner(ARRIBA, EnumSet.of(Material.BAMBOO, Material.BAMBOO_SAPLING));
            poner(ARRIBA, EnumSet.of(Material.KELP, Material.KELP_PLANT));
            poner(ARRIBA, EnumSet.of(Material.TWISTING_VINES, Material.TWISTING_VINES_PLANT));
            poner(ABAJO, EnumSet.of(Material.WEEPING_VINES, Material.WEEPING_VINES_PLANT));
            poner(ABAJO, EnumSet.of(Material.CAVE_VINES, Material.CAVE_VINES_PLANT));
        }

        private static void poner(Map<Material, Set<Material>> mapa, Set<Material> familia) {
            for (Material m : familia) mapa.put(m, familia);
        }
    }

    // ================================================================== anotar

    /** Si ese bloque (de ese mundo) esta marcado como corrompido en su chunk, sea lo que sea ahora. */
    private static boolean marcado(Block b) {
        int[] a = b.getChunk().getPersistentDataContainer().get(Marcas.CORRUPCION, PersistentDataType.INTEGER_ARRAY);
        if (a == null) return false;
        int k = local(b.getX(), b.getY(), b.getZ());
        for (int v : a) if (v == k) return true;
        return false;
    }

    /** Marcado y todavia es de una paleta: lo que no suelta nada al romperse. */
    private boolean marcaValida(Block b) {
        return ajustes().corruptos().contains(b.getType()) && marcado(b);
    }

    private static void marcar(Block b) {
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        int[] a = pdc.get(Marcas.CORRUPCION, PersistentDataType.INTEGER_ARRAY);
        int k = local(b.getX(), b.getY(), b.getZ());
        if (a == null) {
            pdc.set(Marcas.CORRUPCION, PersistentDataType.INTEGER_ARRAY, new int[]{k});
            return;
        }
        for (int v : a) if (v == k) return;
        int[] n = java.util.Arrays.copyOf(a, a.length + 1);
        n[a.length] = k;
        pdc.set(Marcas.CORRUPCION, PersistentDataType.INTEGER_ARRAY, n);
    }

    private static void desmarcar(Block b) {
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        int[] a = pdc.get(Marcas.CORRUPCION, PersistentDataType.INTEGER_ARRAY);
        if (a == null) return;
        int k = local(b.getX(), b.getY(), b.getZ());
        int i = -1;
        for (int j = 0; j < a.length; j++) {
            if (a[j] == k) {
                i = j;
                break;
            }
        }
        if (i < 0) return;
        if (a.length == 1) {
            pdc.remove(Marcas.CORRUPCION);
            return;
        }
        int[] n = new int[a.length - 1];
        System.arraycopy(a, 0, n, 0, i);
        System.arraycopy(a, i + 1, n, i, a.length - i - 1);
        pdc.set(Marcas.CORRUPCION, PersistentDataType.INTEGER_ARRAY, n);
    }

    /**
     * Anota un bloque (fluido null) o un fluido de cubo. reemplazado: lo que habia antes en ese sitio (null si no
     * se sabe). False si no se anota: aire, exento, en la zona spawn o ya corrompido (un bloque corrompido no se
     * vuelve a anotar: la losa que se dobla sobre una corrompida sigue corrompida). Un fluido sobre un bloque suyo
     * anotado tampoco: se va con el bloque.
     */
    private boolean anotar(Block b, Ajustes a, long ahora, Material fluido, Material reemplazado) {
        Material m = fluido != null ? fluido : b.getType();
        if (m.isAir()) return false;
        if (fluido == null && a.exentos().contains(m)) return false;
        if (hc.enSpawn(b.getLocation().add(0.5, 0.5, 0.5))) return false;
        if (fluido == null && marcado(b)) {
            if (a.corruptos().contains(m) && (reemplazado == null || reemplazado == m)) return false;
            // Una marca que se quedo de un bloque que ya no esta (antes aqui habia aire u otra cosa): fuera.
            desmarcar(b);
        }
        String mundo = b.getWorld().getName();
        Anotado antes = registro.de(mundo, b.getX(), b.getY(), b.getZ());
        if (fluido != null && antes != null && !antes.fluido()) return false;
        long cuando = ahora;
        // El mismo bloque otra vez (una vela mas, una losa que se dobla): cuenta desde el primero.
        if (antes != null && antes.fluido() == (fluido != null) && antes.material().equals(m.name())) {
            cuando = Math.min(antes.cuando(), ahora);
        }
        registro.anotar(mundo, b.getX(), b.getY(), b.getZ(), new Anotado(m.name(), cuando, fluido != null));
        sucio = true;
        return true;
    }

    /** Lo que se quita de la lista porque el bloque ya no esta (roto, quemado, explotado). */
    private void quitado(Block b) {
        String mundo = b.getWorld().getName();
        if (registro.hay(mundo) && registro.olvidar(mundo, b.getX(), b.getY(), b.getZ()) != null) sucio = true;
    }

    /** El bloque anotado ha pasado a ser otro sin que nadie lo rompa: se sigue la pista (o se olvida si es aire). */
    private void cambiado(Block b, Material nuevo) {
        String mundo = b.getWorld().getName();
        if (!registro.hay(mundo)) return;
        Anotado an = registro.de(mundo, b.getX(), b.getY(), b.getZ());
        if (an == null || an.fluido() || an.material().equals(nuevo.name())) return;
        if (nuevo.isAir()) registro.olvidar(mundo, b.getX(), b.getY(), b.getZ());
        else registro.anotar(mundo, b.getX(), b.getY(), b.getZ(), new Anotado(nuevo.name(), an.cuando(), false));
        sucio = true;
    }

    /** Si ese mundo es el que se guardo (mismo UID); si es otro con el mismo nombre, lo de antes se descarta. */
    private void comprobarMundo(World w) {
        String mundo = w.getName();
        if (comprobados.contains(mundo)) return;
        comprobados.add(mundo);
        UUID antes = uids.put(mundo, w.getUID());
        if (antes != null && !antes.equals(w.getUID())) {
            int n = registro.olvidarMundo(mundo);
            atrasados.remove(mundo);
            hc.plugin().getLogger().info("[Calamity] Corrupción: " + mundo + " es un mundo nuevo; se olvidan " + n
                    + " bloques anotados del anterior.");
        }
        sucio = true;
    }

    private void avisar(Player p, Ajustes a) {
        if (!a.aviso() || a.textoAviso() == null || a.textoAviso().isBlank()) return;
        if (!avisados.add(p.getUniqueId())) return;
        p.sendMessage(Paleta.mensaje(a.textoAviso()));
    }

    // ------------------------------------------------------------------ eventos: lo que se pone

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPoner(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (!hc.esHardcore(b.getWorld())) return;
        Ajustes a = ajustes();
        if (!a.activa() || !hc.cuenta(e.getPlayer())) return;
        // Cada bloque puesto con lo que habia antes en su sitio.
        Map<Block, Material> bloques = new LinkedHashMap<>();
        bloques.put(b, e.getBlockReplacedState().getType());
        if (e instanceof BlockMultiPlaceEvent multi) {
            for (BlockState s : multi.getReplacedBlockStates()) bloques.putIfAbsent(s.getBlock(), s.getType());
        }
        Block otra = otraParte(b, b.getBlockData());
        if (otra != null && otra.getType() == b.getType()) bloques.putIfAbsent(otra, null);
        comprobarMundo(b.getWorld());
        long ahora = System.currentTimeMillis();
        boolean alguno = false;
        for (Map.Entry<Block, Material> x : bloques.entrySet()) alguno |= anotar(x.getKey(), a, ahora, null, x.getValue());
        if (alguno) avisar(e.getPlayer(), a);
    }

    /** El agua, la lava o la nieve polvo de un cubo. Sobre un bloque suyo anotado, se pudre con el bloque. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCubo(PlayerBucketEmptyEvent e) {
        Block b = e.getBlock();
        if (!hc.esHardcore(b.getWorld())) return;
        Ajustes a = ajustes();
        if (!a.activa() || !hc.cuenta(e.getPlayer())) return;
        Material fluido = fluidoDe(e.getBucket());
        if (fluido == null) return;
        comprobarMundo(b.getWorld());
        if (anotar(b, a, System.currentTimeMillis(), fluido, null)) avisar(e.getPlayer(), a);
    }

    /** Un dispensador puesto por un jugador que vacia un cubo: ese fluido tambien es suyo. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispensar(BlockDispenseEvent e) {
        Block d = e.getBlock();
        String mundo = d.getWorld().getName();
        if (!registro.hay(mundo) || registro.de(mundo, d.getX(), d.getY(), d.getZ()) == null) return;
        Material fluido = fluidoDe(e.getItem().getType());
        if (fluido == null || !(d.getBlockData() instanceof Directional dir)) return;
        Ajustes a = ajustes();
        if (a.activa()) anotar(d.getRelative(dir.getFacing()), a, System.currentTimeMillis(), fluido, null);
    }

    /**
     * Agua o lava que se vuelve fuente junto a una fuente anotada (la piscina infinita de dos cubos): es del mismo
     * jugador y se seca con ella.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluido(FluidLevelChangeEvent e) {
        Block b = e.getBlock();
        String mundo = b.getWorld().getName();
        if (!registro.hay(mundo)) return;
        BlockData nd = e.getNewData();
        if (!(nd instanceof Levelled lv) || lv.getLevel() != 0) return;
        Material fl = nd.getMaterial();
        if (fl != Material.WATER && fl != Material.LAVA) return;
        if (registro.de(mundo, b.getX(), b.getY(), b.getZ()) != null) return;
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            Block v = b.getRelative(f);
            Anotado an = registro.de(mundo, v.getX(), v.getY(), v.getZ());
            if (an != null && an.fluido() && an.material().equals(fl.name())) {
                registro.anotar(mundo, b.getX(), b.getY(), b.getZ(), new Anotado(fl.name(), an.cuando(), true));
                sucio = true;
                return;
            }
        }
    }

    // ------------------------------------------------------------------ eventos: lo que se rompe

    /** Lo corrompido no suelta nada: ni el bloque ni experiencia. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRomperSinBotin(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!hc.esHardcore(b.getWorld()) || !marcaValida(b)) return;
        e.setDropItems(false);
        e.setExpToDrop(0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRomper(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!hc.esHardcore(b.getWorld())) return;
        quitado(b);
        Block otra = otraParte(b, b.getBlockData());
        if (otra != null && otra.getType() == b.getType()) quitado(otra);
        if (marcado(b)) desmarcar(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent e) {
        explotar(e.getEntity().getWorld(), e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosionBloque(BlockExplodeEvent e) {
        explotar(e.getBlock().getWorld(), e.blockList());
    }

    /** Lo anotado se olvida (suelta lo suyo, como cualquier bloque); lo corrompido desaparece sin soltar nada. */
    private void explotar(World w, List<Block> lista) {
        if (w == null || !hc.esHardcore(w)) return;
        Ajustes a = ajustes();
        for (Iterator<Block> it = lista.iterator(); it.hasNext(); ) {
            Block b = it.next();
            quitado(b);
            if (!marcado(b)) continue;
            desmarcar(b);
            if (!a.corruptos().contains(b.getType())) continue;
            it.remove();
            b.setType(Material.AIR, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuemar(BlockBurnEvent e) {
        if (!hc.esHardcore(e.getBlock().getWorld())) return;
        quitado(e.getBlock());
        if (marcado(e.getBlock())) desmarcar(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecaer(LeavesDecayEvent e) {
        quitado(e.getBlock());
    }

    // ------------------------------------------------------------------ eventos: lo que cambia

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDesvanecer(BlockFadeEvent e) {
        cambiado(e.getBlock(), e.getNewState().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFormar(BlockFormEvent e) {
        cambiado(e.getBlock(), e.getNewState().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExtender(BlockSpreadEvent e) {
        cambiado(e.getBlock(), e.getNewState().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrecer(BlockGrowEvent e) {
        cambiado(e.getBlock(), e.getNewState().getType());
    }

    /**
     * Clic derecho en un bloque anotado: si al tick siguiente es otro (descortezado, arado, encerado, una vela
     * mas en la tarta), se sigue la pista. Asi una herramienta no lo salva.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClic(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        String mundo = b.getWorld().getName();
        if (!registro.hay(mundo)) return;
        Anotado an = registro.de(mundo, b.getX(), b.getY(), b.getZ());
        if (an == null || an.fluido()) return;
        hc.plugin().getServer().getScheduler().runTask(hc.plugin(), () -> {
            Material m = b.getType();
            if (m == Material.WATER || m == Material.LAVA) m = Material.AIR;
            cambiado(b, m);
        });
    }

    /**
     * Un wither, un enderman o lo que sea que se lleve un bloque corrompido: desaparece sin soltar nada (el
     * enderman no se lo lleva). La arena y la grava que caen se siguen: el bloque de donde cae se olvida y el
     * sitio donde aterriza se anota con la hora de antes.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntidadSeLleva(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof FallingBlock || !e.getTo().isAir()) return;
        Block b = e.getBlock();
        if (!hc.esHardcore(b.getWorld()) || !marcaValida(b)) return;
        e.setCancelled(true);
        desmarcar(b);
        b.setType(Material.AIR, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntidadCambia(EntityChangeBlockEvent e) {
        Block b = e.getBlock();
        String mundo = b.getWorld().getName();
        if (e.getEntity() instanceof FallingBlock fb) {
            boolean empieza = b.getType() == fb.getBlockData().getMaterial();
            if (empieza) {
                if (!registro.hay(mundo)) return;
                Anotado an = registro.olvidar(mundo, b.getX(), b.getY(), b.getZ());
                if (an == null) return;
                sucio = true;
                fb.getPersistentDataContainer().set(Marcas.CORRUPCION, PersistentDataType.LONG, an.cuando());
            } else {
                Long cuando = fb.getPersistentDataContainer().get(Marcas.CORRUPCION, PersistentDataType.LONG);
                if (cuando == null || !hc.esHardcore(b.getWorld())) return;
                registro.anotar(mundo, b.getX(), b.getY(), b.getZ(), new Anotado(e.getTo().name(), cuando, false));
                sucio = true;
            }
            return;
        }
        cambiado(b, e.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEmpujar(BlockPistonExtendEvent e) {
        mover(e.getBlock().getWorld(), e.getBlocks(), e.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTirar(BlockPistonRetractEvent e) {
        mover(e.getBlock().getWorld(), e.getBlocks(), e.getDirection());
    }

    /**
     * Lo que mueve un piston se lleva su anotacion y su marca: primero se quitan todas y luego se ponen.
     *
     * La lista del evento trae tambien lo que el piston rompe en vez de mover (PistonMoveReaction.BREAK: las hojas,
     * las plantas). Eso no se mueve: lo anotado se olvida (suelta lo suyo, como al romperlo) y lo corrompido (las
     * hojas de azalea de la paleta verde) se quita ya sin soltar nada; el piston lee el bloque despues del evento y,
     * con aire, no suelta botin.
     */
    private void mover(World w, List<Block> bloques, BlockFace dir) {
        if (bloques.isEmpty() || !hc.esHardcore(w)) return;
        String mundo = w.getName();
        Ajustes a = ajustes();
        List<Map.Entry<Block, Anotado>> anotados = new ArrayList<>();
        List<Block> marcas = new ArrayList<>();
        for (Block b : bloques) {
            boolean rompe = b.getPistonMoveReaction() == PistonMoveReaction.BREAK;
            Anotado an = registro.olvidar(mundo, b.getX(), b.getY(), b.getZ());
            if (an != null) {
                sucio = true;
                if (!rompe) anotados.add(Map.entry(b.getRelative(dir), an));
            }
            if (marcado(b)) {
                desmarcar(b);
                if (!rompe) marcas.add(b.getRelative(dir));
                else if (a.corruptos().contains(b.getType())) b.setType(Material.AIR, false);
            }
        }
        for (Map.Entry<Block, Anotado> en : anotados) {
            Block d = en.getKey();
            registro.anotar(mundo, d.getX(), d.getY(), d.getZ(), en.getValue());
        }
        for (Block d : marcas) marcar(d);
    }

    // ------------------------------------------------------------------ eventos: chunks y jugadores

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCargar(ChunkLoadEvent e) {
        String mundo = e.getWorld().getName();
        Set<Long> s = atrasados.get(mundo);
        if (s == null) return;
        long ck = chunk(e.getChunk().getX(), e.getChunk().getZ());
        if (!s.remove(ck)) return;
        if (s.isEmpty()) atrasados.remove(mundo);
        Ajustes a = ajustes();
        List<Long> venc = Registro.vencidos(registro.enChunk(mundo, ck), System.currentTimeMillis(), a.edadMs());
        for (long p : venc) cola.add(new Tarea(mundo, p));
        alCargar += venc.size();
        arrancarTrabajo();
    }

    @EventHandler
    public void onCambiarMundo(PlayerChangedWorldEvent e) {
        avisados.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onSalir(PlayerQuitEvent e) {
        avisados.remove(e.getPlayer().getUniqueId());
    }

    // ================================================================== el barrido

    /** Una vez por segundo, desde Hardcore.tick: el barrido cuando toca y el guardado del minuto. */
    void tick() {
        Ajustes a = ajustes();
        long ahora = System.currentTimeMillis();
        if (a.activa() && ahora >= proximo && trabajo == null) barrer(a, ahora, "barrido");
        if (++segundosSinGuardar >= 60) {
            segundosSinGuardar = 0;
            if (sucio) guardar(false);
        }
    }

    /** Encola lo vencido de los chunks cargados; lo de los descargados queda esperando su carga. Devuelve cuantos. */
    private int barrer(Ajustes a, long ahora, String motivo) {
        proximo = ahora + a.cadaMs();
        actual = new Barrido(ahora, motivo);
        int n = 0;
        for (String mundo : new ArrayList<>(registro.mundos.keySet())) {
            World w = Bukkit.getWorld(mundo);
            if (w == null || !hc.esHardcore(w)) continue;
            comprobarMundo(w);
            Map<Long, Map<Long, Anotado>> chunks = registro.mundos.get(mundo);
            if (chunks == null) continue;
            for (Map.Entry<Long, Map<Long, Anotado>> c : chunks.entrySet()) {
                List<Long> venc = Registro.vencidos(c.getValue(), ahora, a.edadMs());
                if (venc.isEmpty()) continue;
                long ck = c.getKey();
                if (!w.isChunkLoaded(cx(ck), cz(ck))) {
                    atrasados.computeIfAbsent(mundo, k -> new HashSet<>()).add(ck);
                    continue;
                }
                for (long p : venc) cola.add(new Tarea(mundo, p));
                n += venc.size();
            }
        }
        actual.encolados = n;
        for (Set<Long> s : atrasados.values()) actual.esperan += s.size();
        arrancarTrabajo();
        if (trabajo == null) terminar();
        return n;
    }

    private void arrancarTrabajo() {
        if (parado || trabajo != null || cola.isEmpty()) return;
        trabajo = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(), this::trabajar, 1L, 1L);
    }

    /** Un tick de trabajo: como mucho por-tick bloques. */
    private void trabajar() {
        Ajustes a = ajustes();
        long ahora = System.currentTimeMillis();
        int hechos = 0;
        while (hechos < a.porTick() && !cola.isEmpty()) {
            Tarea t = cola.poll();
            hechos++;
            hc.seguro("corrupcion", () -> procesar(t, a, ahora));
        }
        if (cola.isEmpty()) terminar();
    }

    /** Se acabo la cola: se para el trabajo y, si era un barrido, se apunta en la telemetria. */
    private void terminar() {
        if (trabajo != null) trabajo.cancel();
        trabajo = null;
        sonados.clear();
        Barrido b = actual;
        actual = null;
        if (b == null) return;
        b.fin = System.currentTimeMillis();
        ultimo = b;
        int carga = alCargar;
        alCargar = 0;
        int hechos = b.corrompidos + b.podridos + b.olvidados;
        if (hechos == 0 && carga == 0 && registro.total() == 0) return;
        Telemetria tel = hc.telemetria();
        if (tel == null) return;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("motivo", b.motivo);
        c.put("corrompidos", b.corrompidos);
        c.put("podridos", b.podridos);
        c.put("olvidados", b.olvidados);
        c.put("esperan", b.esperan);
        c.put("al_cargar", carga);
        c.put("anotados", registro.total());
        c.put("ms", b.fin - b.inicio);
        hc.seguro("telemetria", () -> tel.suceso("corrupcion", null, c));
    }

    private void procesar(Tarea t, Ajustes a, long ahora) {
        Anotado an = registro.de(t.mundo(), t.pos());
        // Ya hecho (otra tarea de la misma posicion) o puesto otra vez entre medias.
        if (an == null || ahora - an.cuando() < a.edadMs()) return;
        World w = Bukkit.getWorld(t.mundo());
        if (w == null || !hc.esHardcore(w)) return;
        int x = px(t.pos()), z = pz(t.pos());
        if (!w.isChunkLoaded(x >> 4, z >> 4)) {
            atrasados.computeIfAbsent(t.mundo(), k -> new HashSet<>()).add(chunk(x >> 4, z >> 4));
            return;
        }
        Resultado r = resolver(w.getBlockAt(x, py(t.pos()), z), an, a);
        Barrido b = actual;
        if (b == null) return;
        switch (r) {
            case CORROMPIDO -> b.corrompidos++;
            case PODRIDO -> b.podridos++;
            case OLVIDADO -> b.olvidados++;
        }
    }

    /** Lo que le pasa a un bloque anotado y vencido, con su chunk cargado. Siempre sale de la lista. */
    private Resultado resolver(Block b, Anotado an, Ajustes a) {
        registro.olvidar(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        sucio = true;
        if (hc.enSpawn(b.getLocation().add(0.5, 0.5, 0.5))) return Resultado.OLVIDADO;
        if (an.fluido()) return secar(b, an, a);
        Material actual = b.getType();
        if (!actual.name().equals(an.material())) {
            // Si cambia, se olvida; salvo de funcional a funcional (el tallo que se engancha a su calabaza).
            Material antes = Material.matchMaterial(an.material());
            if (antes == null || !a.funcionales().contains(antes) || !a.funcionales().contains(actual)) {
                return Resultado.OLVIDADO;
            }
            // Nunca un bloque con bloque-entidad que no es el que se anoto: lo pone otro (el cofre de una ruina
            // o la boveda de una Boveda Caida donde el jugador habia puesto una enredadera o una flor). Los
            // cambios de un contenedor del propio jugador (el cobre que se oxida, encerarlo) ya se siguen por
            // sus eventos, asi que aqui solo puede ser de otro.
            if (b.getState(false) instanceof TileState) return Resultado.OLVIDADO;
        }
        BlockData viejo = b.getBlockData();
        Destino d = destino(actual, viejo, a);
        if (d == Destino.EXENTO) return Resultado.OLVIDADO;
        if (d == Destino.PUDRIR) {
            pudrir(b, viejo, a);
            return Resultado.PODRIDO;
        }
        BlockData nuevo = corrupta(viejo, d, a.gama(bioma(b)), semilla(pos(b.getX(), b.getY(), b.getZ()), an.cuando()));
        if (nuevo == null) {
            pudrir(b, viejo, a);
            return Resultado.PODRIDO;
        }
        b.setBlockData(nuevo, false);
        marcar(b);
        efectos(b, nuevo, a);
        return Resultado.CORROMPIDO;
    }

    /** El agua, la lava o la nieve polvo de un cubo: se secan si siguen ahi; el agua de un bloque anegado, se va. */
    private Resultado secar(Block b, Anotado an, Ajustes a) {
        Material m = b.getType();
        boolean agua = Material.WATER.name().equals(an.material());
        if ((agua && (m == Material.WATER || m == Material.BUBBLE_COLUMN)) || (!agua && m.name().equals(an.material()))) {
            b.setType(Material.AIR, true);
            efectos(b, null, a);
            return Resultado.PODRIDO;
        }
        if (agua && b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) {
            wl.setWaterlogged(false);
            b.setBlockData(wl, true);
            efectos(b, null, a);
            return Resultado.PODRIDO;
        }
        return Resultado.OLVIDADO;
    }

    /**
     * Se pudre: el inventario se vacia antes y el bloque desaparece sin soltar nada. Se va tambien su otra mitad
     * (puertas, camas, plantas altas) y la columna de lo que crecio de el (caña de azucar, cactus, bambu, algas, enredaderas).
     */
    private void pudrir(Block b, BlockData viejo, Ajustes a) {
        vaciar(b);
        Block otra = otraParte(b, viejo);
        efectos(b, viejo, a);
        Material m = viejo.getMaterial();
        boolean anegado = viejo instanceof Waterlogged wl && wl.isWaterlogged();
        Material queda = Columnas.DE_AGUA.contains(m) ? Material.WATER : Material.AIR;
        // Con agua alrededor se avisa a los vecinos para que el agua vuelva a su sitio.
        b.setType(queda, anegado);
        // La otra parte de una cama puede caer en el chunk de al lado: si no esta cargado, no se carga (la otra
        // mitad tiene su propia anotacion y se pudre cuando su chunk se cargue).
        if (otra != null && otra.getWorld().isChunkLoaded(otra.getX() >> 4, otra.getZ() >> 4) && otra.getType() == m) {
            registro.olvidar(otra.getWorld().getName(), otra.getX(), otra.getY(), otra.getZ());
            vaciar(otra);
            otra.setType(queda, false);
        }
        columna(b, m);
    }

    private static void vaciar(Block b) {
        BlockState st = b.getState(false);
        if (st instanceof Chest ch) {
            // Solo esta mitad: la otra de un cofre doble puede ser del mundo (una ruina).
            ch.getBlockInventory().clear();
        } else if (st instanceof InventoryHolder h) {
            h.getInventory().clear();
        }
    }

    private void columna(Block base, Material m) {
        BlockFace dir = BlockFace.UP;
        Set<Material> familia = Columnas.ARRIBA.get(m);
        if (familia == null) {
            familia = Columnas.ABAJO.get(m);
            dir = BlockFace.DOWN;
        }
        if (familia == null) return;
        Block b = base.getRelative(dir);
        for (int i = 0; i < 64 && familia.contains(b.getType()); i++) {
            registro.olvidar(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
            b.setType(Columnas.DE_AGUA.contains(b.getType()) ? Material.WATER : Material.AIR, false);
            b = b.getRelative(dir);
        }
    }

    private static String bioma(Block b) {
        try {
            return b.getWorld().getBiome(b.getX(), b.getY(), b.getZ()).getKey().toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Unas pocas particulas del bloque y, una vez por chunk y barrido, un crujido grave solo para quien esta cerca. */
    private void efectos(Block b, BlockData d, Ajustes a) {
        World w = b.getWorld();
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        if (a.particulas() && d != null && !d.getMaterial().isAir() && d.getMaterial() != Material.WATER
                && d.getMaterial() != Material.LAVA) {
            w.spawnParticle(Particle.BLOCK, c, 6, 0.3, 0.3, 0.3, 0, d);
        }
        if (a.sonido().isEmpty() || a.radioSonido() <= 0) return;
        if (!sonados.add(w.getName() + ";" + chunk(b.getX() >> 4, b.getZ() >> 4))) return;
        double r2 = a.radioSonido() * a.radioSonido();
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(c) <= r2) p.playSound(c, a.sonido(), SoundCategory.BLOCKS, 0.7f, 0.6f);
        }
    }

    // ================================================================== guardado

    private static String fichero(String mundo) {
        return mundo.replaceAll("[^A-Za-z0-9_.-]", "_") + ".txt";
    }

    private void cargar() {
        File[] fs = carpeta.listFiles((d, n) -> n.endsWith(".txt"));
        if (fs == null) return;
        int n = 0;
        for (File f : fs) {
            try {
                n += Registro.leer(registro, Files.readString(f.toPath(), StandardCharsets.UTF_8), uids);
            } catch (IOException | RuntimeException e) {
                hc.plugin().getLogger().log(Level.WARNING, "[Calamity] Corrupción: no se pudo leer " + f.getName(), e);
            }
        }
        if (n > 0) hc.plugin().getLogger().info("[Calamity] Corrupción: " + n + " bloques anotados de antes del reinicio.");
    }

    /** Copia lo anotado a disco: en otro hilo, o ya (al parar). Un mundo sin nada se queda sin fichero. */
    private void guardar(boolean ya) {
        sucio = false;
        Map<String, String> textos = new LinkedHashMap<>();
        for (String mundo : registro.mundos.keySet()) {
            if (registro.hay(mundo)) textos.put(fichero(mundo), registro.texto(mundo, uids.get(mundo)));
        }
        long sec = ++secuencia;
        Runnable escribir = () -> escribir(textos, sec);
        if (ya) escribir.run();
        else hc.plugin().getServer().getScheduler().runTaskAsynchronously(hc.plugin(), escribir);
    }

    private void escribir(Map<String, String> textos, long sec) {
        synchronized (cerrojo) {
            // Una copia mas vieja que la ultima escrita no pisa a la nueva.
            if (sec < escrita) return;
            escrita = sec;
            try {
                Path dir = carpeta.toPath();
                Files.createDirectories(dir);
                for (Map.Entry<String, String> e : textos.entrySet()) {
                    Path destino = dir.resolve(e.getKey());
                    Path tmp = dir.resolve(e.getKey() + ".tmp");
                    Files.writeString(tmp, e.getValue(), StandardCharsets.UTF_8);
                    try {
                        Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException sinAtomico) {
                        Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                File[] viejos = dir.toFile().listFiles((d, n) -> n.endsWith(".txt") && !textos.containsKey(n));
                if (viejos != null) for (File f : viejos) Files.deleteIfExists(f.toPath());
            } catch (IOException e) {
                hc.plugin().getLogger().warning("[Calamity] Corrupción: no se pudo guardar lo anotado: " + e.getMessage());
            }
        }
    }

    /** Al parar las reglas: lo que quedaba del barrido se hara en el siguiente arranque. */
    void parar() {
        parado = true;
        HandlerList.unregisterAll(this);
        if (trabajo != null) trabajo.cancel();
        trabajo = null;
        cola.clear();
        atrasados.clear();
        avisados.clear();
        sonados.clear();
        guardar(true);
    }

    // ================================================================== comando

    private void comando(CommandSender quien, String[] args) {
        // El registro de subcomandos es estatico y sobrevive a parar(): con las reglas apagadas, este modulo ya no
        // escucha ni guarda, y un barrido suyo corromperia bloques con una lista vieja.
        if (parado) {
            quien.sendMessage(ComandoCalamity.mensaje("La corrupción está parada: las reglas de Calamity están apagadas."));
            return;
        }
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "info";
        Ajustes a = ajustes();
        long ahora = System.currentTimeMillis();
        switch (sub) {
            case "now" -> {
                if (!a.activa()) {
                    quien.sendMessage(ComandoCalamity.mensaje("La corrupción está apagada en la config (hardcore.corrupcion.activa)."));
                    return;
                }
                if (trabajo != null) {
                    quien.sendMessage(ComandoCalamity.mensaje("Ya hay un barrido en marcha: quedan " + cola.size() + " bloques."));
                    return;
                }
                int n = barrer(a, ahora, "staff");
                Barrido b = actual != null ? actual : ultimo;
                int esperan = b == null ? 0 : b.esperan;
                hc.plugin().bitacora().anotar("corrupcion", "barrido a mano", quien.getName(), n + " bloques");
                quien.sendMessage(ComandoCalamity.mensaje("Barrido en marcha: " + n + " bloques vencidos (más de "
                        + Hogueras.reloj(a.edadMs()) + " anotados), " + a.porTick() + " por tick. Chunks que esperan a cargarse: "
                        + esperan + "."));
            }
            case "clear" -> {
                if (!(quien instanceof Player p)) {
                    quien.sendMessage(ComandoCalamity.mensaje("Solo desde el juego: el radio es alrededor de ti."));
                    return;
                }
                int radio;
                try {
                    radio = args.length > 2 ? Integer.parseInt(args[2]) : -1;
                } catch (NumberFormatException e) {
                    radio = -1;
                }
                if (radio < 1 || radio > 1024) {
                    quien.sendMessage(ComandoCalamity.mensaje("Uso: /calamity corruption clear <radius> (de 1 a 1024 bloques)."));
                    return;
                }
                Location l = p.getLocation();
                int n = registro.olvidarRadio(p.getWorld().getName(), l.getBlockX(), l.getBlockZ(), radio);
                if (n > 0) guardar(false);
                hc.plugin().bitacora().anotar("corrupcion", "olvidar", p.getName(), p.getWorld().getName() + " "
                        + l.getBlockX() + " " + l.getBlockZ() + " radio " + radio, n + " bloques");
                quien.sendMessage(ComandoCalamity.mensaje("Olvidados " + n + " bloques anotados a " + radio
                        + " bloques de ti. Lo ya corrompido sigue corrompido."));
            }
            default -> info(quien, a, ahora);
        }
    }

    private void info(CommandSender quien, Ajustes a, long ahora) {
        quien.sendMessage(ComandoCalamity.mensaje("Corrupción: " + (a.activa() ? "activa" : "apagada en la config")
                + ". Anotados " + registro.total() + ". Próximo barrido en " + Hogueras.reloj(Math.max(0, proximo - ahora))
                + " (cada " + Hogueras.reloj(a.cadaMs()) + "; se corrompe lo que lleva " + Hogueras.reloj(a.edadMs()) + ")."));
        for (String mundo : registro.mundos.keySet()) {
            Set<Long> at = atrasados.get(mundo);
            quien.sendMessage(Component.text("  " + mundo + ": " + registro.total(mundo) + " anotados en "
                    + registro.mundos.get(mundo).size() + " chunks" + (at == null || at.isEmpty() ? "" : ", "
                    + at.size() + " chunks con algo vencido esperan a cargarse"), Paleta.TENUE));
        }
        if (trabajo != null) quien.sendMessage(Component.text("  En marcha: quedan " + cola.size() + " bloques.", Paleta.TENUE));
        Barrido u = ultimo;
        if (u != null) {
            quien.sendMessage(Component.text("  Último barrido (" + u.motivo + ") hace " + Hogueras.reloj(ahora - u.fin)
                    + ": " + u.encolados + " vencidos; " + u.corrompidos + " corrompidos, " + u.podridos + " podridos, " + u.olvidados + " olvidados, "
                    + u.esperan + " chunks esperando.", Paleta.TENUE));
        }
        if (!a.avisos().isEmpty()) {
            quien.sendMessage(Component.text("  Avisos de la config: " + String.join("; ", a.avisos()), Paleta.AVISO));
        }
    }

    // ================================================================== autotest

    /**
     * Lo que no necesita servidor: posiciones, chunks, marcas, el reparto de la paleta, las alternativas "a|b",
     * los colores de bioma, lo vencido, el radio de clear y el fichero (ida y vuelta). Tambien lo corre una
     * prueba local con solo el jar de Paper en el classpath.
     */
    static void pruebasPuras(Autotest.Hoja h) {
        int[][] sitios = {{0, 0, 0}, {-1, -64, -1}, {29_999_999, 319, -29_999_999}, {123, -2048, -456}, {-17, 2047, 33}};
        for (int[] s : sitios) {
            long k = pos(s[0], s[1], s[2]);
            h.ok("posición " + s[0] + " " + s[1] + " " + s[2] + " ida y vuelta", px(k) == s[0] && py(k) == s[1] && pz(k) == s[2]);
        }
        long ck = chunk(-1, 5);
        h.ok("chunk -1 5 ida y vuelta", cx(ck) == -1 && cz(ck) == 5);
        h.ok("chunk de Paper: x en los 32 bits bajos", chunk(3, 0) == 3L && chunk(0, 1) == 1L << 32);
        h.ok("marca: misma posición del chunk, misma clave", local(-1, -64, -1) == local(15, -64, 15));
        h.ok("marca: otra altura, otra clave", local(3, 319, 4) != local(3, 318, 4) && local(3, -64, 4) != local(3, -63, 4));
        Set<Integer> claves = new HashSet<>();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = -64; y < 320; y += 7) claves.add(local(x, y, z));
        h.igual("marca: sin choques en un chunk", 16 * 16 * 55, claves.size());

        // 1.16.4: los faroles, exentos de serie (por nombre o por "*final", como los resuelve materiales), y nada mas que
        // acabe en lantern: ni la linterna de mar ni la calabaza. Con el servidor, autotest() los mira bloque a bloque.
        List<String> faltan = new ArrayList<>();
        for (String n : FAROLES) if (!cubre(EXENTOS, n)) faltan.add(n);
        h.igual("exentos de serie: los diez faroles", List.of(), faltan);
        h.ok("exentos de serie: ni la linterna de mar ni la calabaza", !cubre(EXENTOS, "sea_lantern")
                && !cubre(EXENTOS, "jack_o_lantern"));

        // El reparto: siempre el mismo para la misma semilla, y todos salen.
        List<String> cinco = List.of("a", "b", "c", "d", "e");
        Map<String, Integer> veces = new HashMap<>();
        for (int i = 0; i < 1000; i++) veces.merge(elegir(cinco, semilla(pos(i, 64, -i), 1_000_000L)), 1, Integer::sum);
        h.igual("paleta: salen los cinco en 1000 bloques", 5, veces.size());
        h.ok("paleta: ninguno por debajo de 120 de 1000", veces.values().stream().allMatch(v -> v >= 120));
        h.igual("paleta: la misma semilla elige lo mismo", elegir(cinco, 42), elegir(cinco, 42));
        h.igual("paleta vacía: nada", null, elegir(List.of(), 42));

        // Alternativas "a|b".
        Map<String, String> hay = Map.of("tuff_slab", "TUFF_SLAB", "red_nether_brick_slab", "RNB");
        h.igual("a|b: si a no existe, b", "RNB", primera("cinnabar_slab|red_nether_brick_slab", hay::get));
        h.igual("a|b: con espacios", "TUFF_SLAB", primera(" tuff_slab | red_nether_brick_slab ", hay::get));
        h.igual("a|b: ninguna existe", null, primera("cinnabar|polished_cinnabar", hay::get));

        // Colores de bioma.
        h.igual("crimson_organism es rojo", "rojo", colorDe(BIOMAS, "bracken:panacea/crimson_organism"));
        h.igual("honeybee_biome es amarillo", "amarillo", colorDe(BIOMAS, "panacea/honeybee_biome"));
        h.igual("quicksand_springs es amarillo", "amarillo", colorDe(BIOMAS, "bracken:panacea/quicksand_springs"));
        h.igual("creeper_dominion es verde", "verde", colorDe(BIOMAS, "bracken:panacea/creeper_dominion"));
        h.igual("ravenous_greenwood es verde", "verde", colorDe(BIOMAS, "bracken:panacea/ravenous_greenwood"));
        h.igual("condemned_taiga es negro", "negro", colorDe(BIOMAS, "bracken:panacea/condemned_taiga"));
        h.igual("cualquier otro es negro", "negro", colorDe(BIOMAS, "minecraft:plains"));
        h.igual("en mayúsculas también", "rojo", colorDe(BIOMAS, "BRACKEN:PANACEA/CRIMSON_ORGANISM"));
        Map<String, List<String>> otra = mapa("verde", List.of("panacea/*"), "negro", List.of("*"), "rojo",
                List.of("panacea/crimson_organism"));
        h.igual("el exacto gana a panacea/*", "rojo", colorDe(otra, "bracken:panacea/crimson_organism"));
        h.igual("panacea/* gana a *", "verde", colorDe(otra, "bracken:panacea/bamboo_valley"));
        h.igual("sin * nadie", null, colorDe(Map.of("rojo", List.of("panacea/crimson_organism")), "minecraft:plains"));
        int enLista = 0;
        for (List<String> l : BIOMAS.values()) enLista += l.size();
        h.igual("los 13 biomas de Panacea y el comodín", 14, enLista);

        // Lo vencido: a la edad justa, si; un milisegundo antes, no.
        final long t0 = 1_000_000_000L, edad = 5 * 60_000L;
        Map<Long, Anotado> enChunk = new HashMap<>();
        enChunk.put(pos(1, 64, 1), new Anotado("STONE", t0, false));
        enChunk.put(pos(2, 64, 1), new Anotado("STONE", t0 + 1000, false));
        h.igual("nada vencido a 4:59,999", 0, Registro.vencidos(enChunk, t0 + edad - 1, edad).size());
        h.igual("uno vencido a 5:00", 1, Registro.vencidos(enChunk, t0 + edad, edad).size());
        h.igual("los dos a 5:01", 2, Registro.vencidos(enChunk, t0 + edad + 1000, edad).size());
        h.igual("edad 0: todo vencido ya", 2, Registro.vencidos(enChunk, t0 + 1000, 0).size());

        // El registro: anotar, olvidar, radio.
        Registro r = new Registro();
        r.anotar("calamity", 0, 64, 0, new Anotado("STONE", t0, false));
        r.anotar("calamity", 10, 70, 0, new Anotado("OAK_PLANKS", t0, false));
        r.anotar("calamity", 40, 64, 0, new Anotado("STONE", t0, false));
        r.anotar("calamity", -17, 64, -17, new Anotado("WATER", t0, true));
        r.anotar("calamity", 0, 64, 0, new Anotado("STONE", t0 + 5, false));
        h.igual("anotar dos veces lo mismo cuenta uno", 4, r.total());
        h.igual("clear 16: dos dentro", 2, r.olvidarRadio("calamity", 0, 0, 16));
        h.ok("clear 16: se quedan el de 40 y el de -17", r.total() == 2 && r.de("calamity", 40, 64, 0) != null
                && r.de("calamity", -17, 64, -17) != null);
        h.ok("olvidar uno", r.olvidar("calamity", 40, 64, 0) != null);
        h.igual("olvidar lo que no hay", null, r.olvidar("calamity", 40, 64, 0));
        h.igual("queda uno", 1, r.total());

        // El fichero: ida y vuelta con dos mundos, negativos, fluidos y lineas rotas.
        Registro ida = new Registro();
        UUID uid = Autotest.sintetico(7);
        ida.anotar("calamity", -12, -60, 345, new Anotado("TUFF_SLAB", t0 + 1, false));
        ida.anotar("calamity", 7, 70, -9, new Anotado("WATER", t0 + 2, true));
        ida.anotar("calamity", 7, 71, -9, new Anotado("CHEST", t0 + 3, false));
        ida.anotar("otro mundo", 1, 2, 3, new Anotado("STONE", t0, false));
        String texto = ida.texto("calamity", uid) + "basura que no se entiende\n1 2 3 x STONE b\n" + ida.texto("otro mundo", null);
        Registro vuelta = new Registro();
        Map<String, UUID> leidos = new HashMap<>();
        int n = Registro.leer(vuelta, texto, leidos);
        h.igual("fichero: se leen los cuatro", 4, n);
        h.igual("fichero: losa", new Anotado("TUFF_SLAB", t0 + 1, false), vuelta.de("calamity", -12, -60, 345));
        h.igual("fichero: agua de cubo", new Anotado("WATER", t0 + 2, true), vuelta.de("calamity", 7, 70, -9));
        h.igual("fichero: cofre", new Anotado("CHEST", t0 + 3, false), vuelta.de("calamity", 7, 71, -9));
        h.igual("fichero: mundo con espacio en el nombre", new Anotado("STONE", t0, false), vuelta.de("otro mundo", 1, 2, 3));
        h.igual("fichero: el UID del mundo", uid, leidos.get("calamity"));
        h.ok("fichero: sin UID no se inventa", !leidos.containsKey("otro mundo"));
        h.igual("fichero: agrupado por chunk", true, ida.texto("calamity", uid).contains("\nc -1 21\n"));
        h.igual("olvidar un mundo regenerado", 3, vuelta.olvidarMundo("calamity"));
        h.igual("y el resto sigue", 1, vuelta.total());
    }

    /** Con servidor (sin tocar el mundo): ajustes de serie, paletas resueltas aqui, exentos, funcionales y formas. */
    List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        pruebasPuras(h);
        Ajustes a = Ajustes.defecto();
        h.ok("sin sección: activa", a.activa());
        h.igual("valores de serie: cada 5 min, edad 5 min, 50 por tick", "300000/300000/50",
                a.cadaMs() + "/" + a.edadMs() + "/" + a.porTick());
        h.igual("valores de serie: todo resuelve en este servidor", List.of(), a.avisos());
        h.igual("config del servidor: todo resuelve", List.of(), ajustes().avisos());
        for (String color : List.of("rojo", "verde", "amarillo", "negro")) {
            Gama g = a.gamas().get(color);
            h.ok(color + ": enteros, losa y escalera", g != null && !g.enteros().isEmpty() && !g.losas().isEmpty()
                    && !g.escaleras().isEmpty());
            if (g == null) continue;
            for (Material m : g.todos()) {
                h.ok(color + ": " + m.name().toLowerCase(Locale.ROOT) + " sin luz y no funcional",
                        m.createBlockData().getLightEmission() == 0 && !a.funcionales().contains(m));
            }
        }
        h.ok("amarillo no tiene muro: un muro pasa a entero", a.gamas().get("amarillo").muros().isEmpty());
        h.ok("lo corrompido se reconoce", a.corruptos().contains(Material.NETHERRACK) && a.corruptos().contains(Material.TUFF_SLAB));

        List<String> exentos = new ArrayList<>(List.of("torch", "wall_torch", "soul_torch", "soul_wall_torch", "copper_torch",
                "copper_wall_torch", "campfire", "soul_campfire"));
        exentos.addAll(FAROLES);
        for (String n : exentos) {
            Material m = Material.matchMaterial(n);
            h.ok("exento: " + n, m != null && destino(m, m.createBlockData(), a) == Destino.EXENTO);
        }
        // Con el config del servidor (corrupcion.exentos, si lo trae, sustituye a la lista de serie).
        Ajustes srv = ajustes();
        List<String> pudren = new ArrayList<>();
        for (String n : FAROLES) {
            Material m = Material.matchMaterial(n);
            if (m == null || destino(m, m.createBlockData(), srv) != Destino.EXENTO) pudren.add(n);
        }
        h.igual("config del servidor: ningún farol se pudre", List.of(), pudren);
        h.ok("la linterna de mar no es farol: se corrompe como un bloque", destino(Material.SEA_LANTERN,
                Material.SEA_LANTERN.createBlockData(), a) == Destino.ENTERO);
        for (String n : List.of("hopper", "chest", "trapped_chest", "barrel", "white_shulker_box", "furnace", "blast_furnace",
                "smoker", "dispenser", "dropper", "piston", "sticky_piston", "observer", "redstone_wire", "redstone_torch",
                "redstone_wall_torch", "repeater", "comparator", "redstone_block", "redstone_lamp", "rail", "powered_rail",
                "detector_rail", "activator_rail", "lever", "stone_button", "oak_button", "oak_pressure_plate",
                "light_weighted_pressure_plate", "tripwire_hook", "tripwire", "oak_trapdoor", "iron_trapdoor", "oak_door",
                "iron_door", "oak_fence", "nether_brick_fence", "oak_fence_gate", "red_bed", "crafting_table",
                "smithing_table", "anvil", "slime_block", "honey_block", "oak_sign", "oak_wall_sign", "oak_hanging_sign",
                "white_carpet", "moss_carpet", "poppy", "sunflower", "wheat", "carrots", "oak_sapling", "sugar_cane",
                "spawner", "copper_bulb", "waxed_oxidized_copper_bulb")) {
            Material m = Material.matchMaterial(n);
            h.ok("se pudre: " + n, m != null && destino(m, m.createBlockData(), a) == Destino.PUDRIR);
        }
        h.igual("piedra: entero", Destino.ENTERO, destino(Material.STONE, Material.STONE.createBlockData(), a));
        h.igual("cristal: entero", Destino.ENTERO, destino(Material.GLASS, Material.GLASS.createBlockData(), a));
        h.igual("losa: losa", Destino.LOSA, destino(Material.OAK_SLAB, Material.OAK_SLAB.createBlockData(), a));
        h.igual("escalera: escalera", Destino.ESCALERA, destino(Material.OAK_STAIRS, Material.OAK_STAIRS.createBlockData(), a));
        h.igual("muro: muro", Destino.MURO, destino(Material.COBBLESTONE_WALL, Material.COBBLESTONE_WALL.createBlockData(), a));
        h.igual("escalera de mano: se pudre", Destino.PUDRIR, destino(Material.LADDER, Material.LADDER.createBlockData(), a));
        h.igual("telaraña (no sólida): se pudre", Destino.PUDRIR, destino(Material.COBWEB, Material.COBWEB.createBlockData(), a));
        h.igual("agua: se pudre", Destino.PUDRIR, destino(Material.WATER, Material.WATER.createBlockData(), a));
        h.igual("cubo de agua: agua", Material.WATER, fluidoDe(Material.WATER_BUCKET));
        h.igual("cubo de bacalao: agua", Material.WATER, fluidoDe(Material.COD_BUCKET));
        h.igual("cubo de lava: lava", Material.LAVA, fluidoDe(Material.LAVA_BUCKET));
        h.igual("cubo de leche: nada", null, fluidoDe(Material.MILK_BUCKET));

        Gama rojo = a.gamas().get("rojo"), verde = a.gamas().get("verde"), amarillo = a.gamas().get("amarillo"),
                negro = a.gamas().get("negro");
        BlockData losa = Bukkit.createBlockData("minecraft:stone_brick_slab[type=top,waterlogged=true]");
        BlockData r1 = corrupta(losa, Destino.LOSA, rojo, 7);
        h.ok("losa de arriba con agua -> losa roja de arriba con agua", r1 instanceof Slab s && s.getType() == Slab.Type.TOP
                && s.isWaterlogged() && rojo.losas().contains(r1.getMaterial()));
        BlockData doble = corrupta(Bukkit.createBlockData("minecraft:oak_slab[type=double]"), Destino.LOSA, negro, 3);
        h.ok("losa doble -> losa doble negra", doble instanceof Slab s && s.getType() == Slab.Type.DOUBLE
                && doble.getMaterial() == Material.TUFF_SLAB);
        BlockData esc = Bukkit.createBlockData("minecraft:oak_stairs[facing=east,half=top,shape=outer_left,waterlogged=true]");
        BlockData r2 = corrupta(esc, Destino.ESCALERA, verde, 11);
        h.ok("escalera: orientación, mitad, forma y agua", r2 instanceof Stairs s && s.getFacing() == BlockFace.EAST
                && s.getHalf() == Bisected.Half.TOP && s.getShape() == Stairs.Shape.OUTER_LEFT && s.isWaterlogged()
                && verde.escaleras().contains(r2.getMaterial()));
        BlockData muro = Bukkit.createBlockData("minecraft:cobblestone_wall[up=true,north=low,east=tall,south=none,west=low]");
        BlockData r3 = corrupta(muro, Destino.MURO, negro, 5);
        h.ok("muro: alturas de cada lado", r3 instanceof Wall w && w.isUp() && w.getHeight(BlockFace.NORTH) == Wall.Height.LOW
                && w.getHeight(BlockFace.EAST) == Wall.Height.TALL && w.getHeight(BlockFace.SOUTH) == Wall.Height.NONE
                && negro.muros().contains(r3.getMaterial()));
        BlockData r4 = corrupta(muro, Destino.MURO, amarillo, 5);
        h.ok("muro en amarillo (sin muro) -> entero amarillo", r4 != null && amarillo.enteros().contains(r4.getMaterial()));
        BlockData piedra = Material.STONE.createBlockData();
        boolean hojas = false, hojasBien = true, enVerde = true;
        for (int i = 0; i < 300; i++) {
            BlockData x = corrupta(piedra, Destino.ENTERO, verde, i);
            enVerde &= x != null && verde.enteros().contains(x.getMaterial());
            if (x instanceof Leaves l) {
                hojas = true;
                hojasBien &= l.isPersistent();
            }
        }
        h.ok("entero en verde: siempre de la paleta verde", enVerde);
        h.ok("las hojas de la paleta salen y no se caen solas", hojas && hojasBien);
        BlockData tronco = Bukkit.createBlockData("minecraft:oak_log[axis=x]");
        boolean ejes = true, orientable = false;
        for (int i = 0; i < 300; i++) {
            BlockData x = corrupta(tronco, Destino.ENTERO, negro, i);
            if (x instanceof Orientable o) {
                orientable = true;
                ejes &= o.getAxis() == org.bukkit.Axis.X;
            }
        }
        h.ok("un tronco tumbado corrompe a pizarra tumbada", orientable && ejes);
        h.igual("sin paleta: se pudre", null, corrupta(piedra, Destino.ENTERO, new Gama(List.of(), List.of(), List.of(), List.of()), 1));
        h.igual("bioma rojo -> paleta roja", rojo, a.gama("bracken:panacea/crimson_organism"));
        h.igual("bioma de Minecraft -> paleta negra", negro, a.gama("minecraft:plains"));

        YamlConfiguration c = new YamlConfiguration();
        c.set("activa", false);
        c.set("edad-minutos", 0.5);
        c.set("paletas.amarillo.muro", List.of("sandstone_wall"));
        c.set("paletas.negro.enteros", List.of("glowstone", "tuff"));
        c.set("funcionales", List.of("chest", "no_existe_de_verdad"));
        Ajustes otra = Ajustes.de(c);
        h.ok("config: activa false y edad 30 s", !otra.activa() && otra.edadMs() == 30_000);
        h.ok("config: lo que falta queda de serie", otra.cadaMs() == 300_000 && otra.gamas().get("rojo").enteros().size() == 3);
        h.igual("config: muro amarillo propio", List.of(Material.SANDSTONE_WALL), otra.gamas().get("amarillo").muros());
        h.igual("config: lo que da luz no entra en la paleta", List.of(Material.TUFF), otra.gamas().get("negro").enteros());
        h.ok("config: lo que no existe se avisa", otra.avisos().stream().anyMatch(s -> s.contains("no_existe_de_verdad"))
                && otra.avisos().stream().anyMatch(s -> s.contains("glowstone")));
        h.ok("config: funcionales propios sustituyen a los de serie", otra.funcionales().contains(Material.CHEST)
                && !otra.funcionales().contains(Material.HOPPER));
        h.ok("marca propia en lethal_world", Marcas.CORRUPCION.getNamespace().equals(Marcas.NAMESPACE));
        return h.lineas();
    }
}
