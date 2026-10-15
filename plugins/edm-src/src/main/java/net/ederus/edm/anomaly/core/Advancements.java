package net.ederus.edm.anomaly.core;

import net.ederus.edm.anomaly.AnomalyPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import net.ederus.edm.comun.Compat;

/**
 * El arbol de logros de las anomalias.
 *
 * Minecraft solo lee logros desde un datapack, asi que el plugin se escribe el suyo
 * dentro del mundo la primera vez y lo va actualizando cuando cambia. Luego, al morir
 * un jefe, se le concede el logro a cada participante.
 *
 * Todos los iconos y el fondo son texturas VANILLA a proposito: un datapack que apunte
 * a una textura que no existe se ve como un cuadro morado y negro, y eso canta muchisimo.
 *
 * POR QUE SE ESCRIBE EN onLoad (EDMPlugin#onLoad -> installBeforeWorlds) Y NO EN onEnable.
 * El servidor lee los datapacks al cargar los mundos, y eso pasa DESPUES de onLoad y
 * ANTES de onEnable. Escrito en onEnable, cada arbol nuevo llegaba tarde: ese arranque
 * servia el viejo (o ninguno) y hacia falta un /minecraft:reload o un segundo reinicio.
 * Peor: mientras el servidor no conoce un logro, al guardar el progreso de un jugador
 * el juego TIRA lo que tuviera de ese logro. Por eso "se reiniciaban para todos" cada
 * vez que se tocaba el arbol. En onLoad el arbol nuevo ya esta en disco cuando el mundo
 * lo lee, en el mismo arranque. No lo devuelvas a onEnable.
 *
 * Las otras dos reglas que protegen el progreso:
 *   - un fichero solo se escribe si su contenido cambia (se comparan los bytes);
 *   - los criterios tienen nombres fijos ("caida" en cada anomalia y el id de cada
 *     anomalia en la coleccion). El progreso se guarda por nombre de criterio: si el
 *     nombre cambiara, lo ganado se perderia.
 *
 * El arbol cubre solo las anomalias PROPIAS de EDM. Las de Calamity se registran mas
 * tarde (en su onEnable): meterlas haria que el arbol dependiera de si Calamity esta
 * cargado, y cada arranque con o sin el cambiaria la coleccion.
 */
public final class Advancements {

    /** Marcador de version del datapack. Es informativo: lo que manda es el contenido. */
    private static final int PACK_VERSION = 16;

    private static final String NS = "anomaly";
    /* La raiz del arbol NO puede llamarse "raiz": es el id de ROTTEN (boss/Raiz).
     * Con ese nombre el logro de ROTTEN pisaba el fichero de la raiz y quedaba como
     * padre de si mismo, y el juego descartaba el arbol entero. */
    private static final String ROOT = "inicio";
    private static final String ALL = "coleccionista";

    private final AnomalyPlugin plugin;
    /* En el arranque temprano (onLoad) el modulo aun no tiene registro propio: se le
     * pasa uno hecho a mano. Despues, null, y vale el del modulo. */
    private final AnomalyRegistry fijo;

    public Advancements(AnomalyPlugin plugin) {
        this(plugin, null);
    }

    private Advancements(AnomalyPlugin plugin, AnomalyRegistry fijo) {
        this.plugin = plugin;
        this.fijo = fijo;
    }

    private AnomalyRegistry registry() {
        return fijo != null ? fijo : plugin.registry();
    }

    /**
     * logros.activo. Si no esta en config.yml, depende de la build: encendido en EDM
     * y apagado en EDO (OneBlock no quiere logros de anomalias). Se lee en cada uso,
     * asi que /anomaly reload lo recoge sin mas.
     *
     * Se lee del config del modulo directamente y no por Settings: en onLoad todavia
     * no hay Settings.
     */
    public boolean enabled() {
        return plugin.getConfig().getBoolean("logros.activo",
                !(plugin.core() instanceof net.ederus.edm.EDMOneBlock));
    }

    /** Las anomalias que tienen logro: las propias de EDM, en orden fijo. */
    private List<AnomalyType> tipos() {
        return registry().own();
    }

    // ------------------------------------------------------------- el datapack

    /**
     * El arranque temprano: escribe el arbol ANTES de que el servidor cargue los
     * mundos, para que lo lea en este mismo arranque. Lo llama EDMPlugin#onLoad.
     * Ver el comentario de la clase.
     *
     * Monta un modulo de Anomaly de usar y tirar solo para leer su config y sus fichas
     * (los nombres de las anomalias salen de ahi); no registra nada en el servidor.
     */
    public static void installBeforeWorlds(net.ederus.edm.EDMPlugin core) {
        if (!core.getConfig().getBoolean("modulos.anomaly", true)) return;
        AnomalyPlugin temprano = new AnomalyPlugin(core);
        AnomalyRegistry registro = new AnomalyRegistry(temprano);
        Advancements a = new Advancements(temprano, registro);
        if (!a.enabled()) return;
        registro.loadFichas();
        a.install();
    }

    /**
     * La carpeta raiz del mundo principal.
     *
     * OJO: World#getWorldFolder devuelve la carpeta de la DIMENSION
     * (world/dimensions/minecraft/overworld), y los datapacks van en la raiz del
     * mundo. Hay que componerla desde el contenedor y el nombre del nivel. En onLoad
     * no hay mundos todavia: el nombre se lee de server.properties (level-name).
     */
    private File mundoPrincipal() {
        String nombre = null;
        if (!plugin.getServer().getWorlds().isEmpty()) {
            nombre = plugin.getServer().getWorlds().get(0).getName();
        } else {
            File props = new File("server.properties");
            if (props.isFile()) {
                java.util.Properties p = new java.util.Properties();
                try (java.io.Reader r = Files.newBufferedReader(props.toPath(), StandardCharsets.UTF_8)) {
                    p.load(r);
                    nombre = p.getProperty("level-name");
                } catch (IOException | IllegalArgumentException ignored) {
                }
            }
        }
        if (nombre == null || nombre.isBlank()) nombre = "world";
        return new File(plugin.getServer().getWorldContainer(), nombre.trim());
    }

    /**
     * Pone el datapack al dia dentro del mundo principal. Solo escribe los ficheros
     * cuyo contenido cambia: si todo esta igual, no toca nada.
     *
     * @return true si ha escrito o borrado algo
     */
    public boolean install() {
        if (!enabled()) return false;
        File pack = new File(mundoPrincipal(), "datapacks/anomaly_logros");
        File marker = new File(pack, ".version");
        try {
            int cambios = 0;
            // Este mcmeta lleva LOS DOS esquemas de metadatos a la vez. Paper 26.1.x
            // todavia lee el viejo (pack_format + supported_formats) y sin el campo
            // supported_formats escupia "Error reading pack metadata" en cada arranque
            // — el propio error pedia ese campo. min_format/max_format quedan para
            // cuando el juego salte al esquema nuevo.
            cambios += write(new File(pack, "pack.mcmeta"), """
                    {
                      "pack": {
                        "description": "Anomaly - arbol de logros de las anomalias",
                        "pack_format": 81,
                        "supported_formats": [4, 81],
                        "min_format": [4, 0],
                        "max_format": [101, 1]
                      }
                    }""");

            Path adv = new File(pack, "data/" + NS + "/advancement").toPath();
            Files.createDirectories(adv);

            cambios += write(adv.resolve(ROOT + ".json").toFile(), root());
            for (AnomalyType type : tipos()) {
                cambios += write(adv.resolve(type.id() + ".json").toFile(), forAnomaly(type));
            }
            cambios += write(adv.resolve(ALL + ".json").toFile(), collector());
            int borrados = pruneOrphans(adv);

            cambios += write(marker, String.valueOf(PACK_VERSION));
            if (cambios == 0 && borrados == 0) return false;
            plugin.getLogger().info("Arbol de logros puesto al dia en " + pack.getPath()
                    + " (" + (tipos().size() + 2) + " logros, " + cambios + " fichero(s) cambiado(s)"
                    + (borrados > 0 ? ", " + borrados + " de anomalias que ya no existen borrados" : "")
                    + ").");
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "No se pudo escribir el arbol de logros", e);
            return false;
        }
    }

    /**
     * Borra los logros que ya no tienen anomalia detras.
     *
     * Reescribir solo pisa los ficheros que tocan; los de una anomalia retirada se
     * quedaban para siempre (ya paso con uno) y el arbol del
     * juego seguia ensenando un desafio imposible de conseguir. Aqui se quita todo
     * .json que no sea la raiz, la coleccion o una anomalia del registro.
     */
    private int pruneOrphans(Path adv) throws IOException {
        java.util.Set<String> validos = new java.util.HashSet<>();
        validos.add(ROOT + ".json");
        validos.add(ALL + ".json");
        for (AnomalyType type : tipos()) validos.add(type.id() + ".json");
        int borrados = 0;
        try (java.util.stream.Stream<Path> files = Files.list(adv)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".json") || validos.contains(name)) continue;
                Files.deleteIfExists(f);
                borrados++;
            }
        }
        return borrados;
    }

    /**
     * Escribe el fichero solo si su contenido cambia: un logro que no cambia no se
     * toca nunca, ni siquiera se reescribe igual.
     *
     * @return 1 si lo ha escrito, 0 si ya estaba igual
     */
    private static int write(File file, String content) throws IOException {
        byte[] nuevo = content.getBytes(StandardCharsets.UTF_8);
        if (file.isFile() && java.util.Arrays.equals(Files.readAllBytes(file.toPath()), nuevo)) return 0;
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), nuevo);
        return 1;
    }

    /**
     * La raiz del arbol: la que pone el fondo y el titulo de la pestana.
     *
     * OJO CON EL FONDO. Ya no es una ruta de textura sino el ID DE UN SPRITE, y solo
     * valen los cinco que trae el juego (stone, nether, end, adventure, husbandry).
     * Poner "minecraft:textures/block/sculk.png" —que era lo correcto en su dia— pinta
     * el cuadriculado morado y negro de textura ausente y deja el titulo ilegible.
     * Comprobado contra los propios logros de vanilla en 26.1.2.
     */
    private String root() {
        return """
                {
                  "display": {
                    "icon": {"id": "minecraft:end_crystal"},
                    "title": {"text": "Anomalias", "color": "#9BD7E4", "bold": true},
                    "description": {"text": "Grietas que se abren solas en el mapa, y lo que sale por ellas.", "color": "gray"},
                    "background": "minecraft:gui/advancements/backgrounds/end",
                    "frame": "task",
                    "show_toast": false,
                    "announce_to_chat": false
                  },
                  "criteria": {"siempre": {"trigger": "minecraft:tick"}}
                }""";
    }

    /** Un logro por anomalia, todos colgando de la raiz y todos de tipo desafio. */
    private String forAnomaly(AnomalyType type) {
        String icon = type.icon().getKey().toString();
        // Con componentes cuando la anomalia los pida: es lo que hace que la cabeza de
        // Rabby salga con SU cara y no con la de serie.
        String components = type.iconComponentsJson();
        String iconJson = components == null
                ? "{\"id\": \"" + icon + "\"}"
                : "{\"id\": \"" + icon + "\", \"components\": " + components + "}";
        String color = String.format(Locale.ROOT, "#%06X", type.color().value());
        String title = escape(type.display());
        String desc = escape("Derrota a " + type.display() + ". " + type.tagline() + ".");
        return """
                {
                  "parent": "%s:%s",
                  "display": {
                    "icon": %s,
                    "title": {"text": "%s", "color": "%s", "bold": true},
                    "description": {"text": "%s", "color": "gray"},
                    "frame": "challenge",
                    "show_toast": true,
                    "announce_to_chat": true,
                    "hidden": false
                  },
                  "criteria": {"caida": {"trigger": "minecraft:impossible"}}
                }""".formatted(NS, ROOT, iconJson, title, color, desc);
    }

    /**
     * El logro legendario: exige tener el catalogo entero.
     *
     * requirements con todos los criterios en listas separadas significa "y", que es
     * justo lo que se quiere: no vale con uno, hay que traerlos todos.
     *
     * Cada criterio se llama como el id de su anomalia y va en su propia lista. Una
     * anomalia nueva solo AÑADE un criterio: lo ya ganado se conserva (el progreso se
     * guarda por nombre de criterio) y al jugador le falta solo la nueva. Ordenados por
     * id para que el fichero no cambie si se cambia la clase de una anomalia en el menu.
     */
    private String collector() {
        List<AnomalyType> all = new ArrayList<>(tipos());
        all.sort(java.util.Comparator.comparing(AnomalyType::id));
        StringBuilder criteria = new StringBuilder();
        StringBuilder reqs = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            String id = all.get(i).id();
            if (i > 0) {
                criteria.append(",\n    ");
                reqs.append(", ");
            }
            criteria.append('"').append(id).append("\": {\"trigger\": \"minecraft:impossible\"}");
            reqs.append("[\"").append(id).append("\"]");
        }
        return """
                {
                  "parent": "%s:%s",
                  "display": {
                    "icon": {"id": "minecraft:nether_star"},
                    "title": {"text": "El que las vio todas", "color": "#FFC64D", "bold": true},
                    "description": {"text": "Derrota a las %d anomalias. Ninguna se repite.", "color": "gray"},
                    "frame": "challenge",
                    "show_toast": true,
                    "announce_to_chat": true,
                    "hidden": false
                  },
                  "criteria": {
                    %s
                  },
                  "requirements": [%s]
                }""".formatted(NS, ROOT, all.size(), criteria, reqs);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ------------------------------------------------------------- la concesion

    /**
     * Concede el logro de esa anomalia y, si con eso completa la coleccion, el legendario.
     *
     * Si el datapack todavia no esta cargado (por ejemplo, se acaba de instalar y falta
     * reiniciar) simplemente no hay nada que conceder y no pasa nada.
     */
    public void award(Player player, String anomalyId) {
        if (!enabled()) return;
        if (grant(player, anomalyId) && plugin.settings().rawBool("logros.sonido", true)) {
            Compat.sound(player.getWorld(), player.getLocation(), "ui.toast.challenge_complete", 1.0f, 1.0f);
            Compat.sound(player.getWorld(), player.getLocation(), "entity.player.levelup", 0.7f, 1.4f);
        }
        grantCollector(player, anomalyId);
    }

    private boolean grant(Player player, String key) {
        Advancement adv = advancement(key);
        if (adv == null) return false;
        AdvancementProgress progress = player.getAdvancementProgress(adv);
        if (progress.isDone()) return false;
        for (String criterion : progress.getRemainingCriteria()) {
            progress.awardCriteria(criterion);
        }
        return true;
    }

    /** Marca el criterio de esa anomalia dentro del logro de coleccion. */
    private void grantCollector(Player player, String anomalyId) {
        Advancement adv = advancement(ALL);
        if (adv == null) return;
        AdvancementProgress progress = player.getAdvancementProgress(adv);
        if (progress.isDone()) return;
        if (progress.getRemainingCriteria().contains(anomalyId)) {
            progress.awardCriteria(anomalyId);
        }
    }

    private Advancement advancement(String key) {
        try {
            return plugin.getServer().getAdvancement(new NamespacedKey(NS, key));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Cuantos de los logros de anomalia lleva ya ese jugador. */
    public int owned(Player player) {
        int n = 0;
        for (AnomalyType type : tipos()) {
            Advancement adv = advancement(type.id());
            if (adv != null && player.getAdvancementProgress(adv).isDone()) n++;
        }
        return n;
    }

    /** Los nombres de las anomalias que ese jugador todavia no ha derrotado. */
    public List<String> missing(Player player) {
        List<String> out = new ArrayList<>();
        for (AnomalyType type : tipos()) {
            Advancement adv = advancement(type.id());
            if (adv == null || !player.getAdvancementProgress(adv).isDone()) out.add(type.display());
        }
        return out;
    }
}
