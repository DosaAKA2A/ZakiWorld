package net.ederus.edm.anomaly.core;

import net.ederus.edm.anomaly.AnomalyPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
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

/**
 * El arbol de logros de las anomalias.
 *
 * Minecraft solo lee logros desde un datapack, asi que el plugin se escribe el suyo
 * dentro del mundo la primera vez y lo va actualizando cuando cambia. Luego, al morir
 * un jefe, se le concede el logro a cada participante.
 *
 * Todos los iconos y el fondo son texturas VANILLA a proposito: un datapack que apunte
 * a una textura que no existe se ve como un cuadro morado y negro, y eso canta muchisimo.
 */
public final class Advancements {

    /** Sube esto para que el datapack se reescriba en el proximo arranque. */
    private static final int PACK_VERSION = 11;

    private static final String NS = "anomaly";
    private static final String ROOT = "raiz";
    private static final String ALL = "coleccionista";

    private final AnomalyPlugin plugin;

    public Advancements(AnomalyPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------- el datapack

    /**
     * Escribe el datapack dentro del mundo principal si falta o si cambio de version.
     *
     * @return true si lo ha (re)escrito, o sea que hace falta recargar para verlo
     */
    public boolean install() {
        World world = plugin.getServer().getWorlds().isEmpty() ? null : plugin.getServer().getWorlds().get(0);
        if (world == null) return false;

        // OJO: World#getWorldFolder devuelve la carpeta de la DIMENSION
        // (world/dimensions/minecraft/overworld), y los datapacks van en la raiz del
        // mundo. Hay que componerla desde el contenedor y el nombre del nivel.
        File pack = new File(plugin.getServer().getWorldContainer(),
                world.getName() + "/datapacks/anomaly_logros");
        File marker = new File(pack, ".version");
        try {
            if (marker.isFile()
                    && Files.readString(marker.toPath(), StandardCharsets.UTF_8).trim().equals(String.valueOf(PACK_VERSION))) {
                return false;
            }
            // Este mcmeta lleva LOS DOS esquemas de metadatos a la vez. Paper 26.1.x
            // todavia lee el viejo (pack_format + supported_formats) y sin el campo
            // supported_formats escupia "Error reading pack metadata" en cada arranque
            // — el propio error pedia ese campo. min_format/max_format quedan para
            // cuando el juego salte al esquema nuevo.
            write(new File(pack, "pack.mcmeta"), """
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

            write(adv.resolve(ROOT + ".json").toFile(), root());
            for (AnomalyType type : plugin.registry().all()) {
                write(adv.resolve(type.id() + ".json").toFile(), forAnomaly(type));
            }
            write(adv.resolve(ALL + ".json").toFile(), collector());

            write(marker, String.valueOf(PACK_VERSION));
            plugin.getLogger().info("Arbol de logros escrito en " + pack.getPath()
                    + " (" + (plugin.registry().all().size() + 2) + " logros).");
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "No se pudo escribir el arbol de logros", e);
            return false;
        }
    }

    private static void write(File file, String content) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
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
     */
    private String collector() {
        List<AnomalyType> all = plugin.registry().all();
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
        for (AnomalyType type : plugin.registry().all()) {
            Advancement adv = advancement(type.id());
            if (adv != null && player.getAdvancementProgress(adv).isDone()) n++;
        }
        return n;
    }

    /** Los nombres de las anomalias que ese jugador todavia no ha derrotado. */
    public List<String> missing(Player player) {
        List<String> out = new ArrayList<>();
        for (AnomalyType type : plugin.registry().all()) {
            Advancement adv = advancement(type.id());
            if (adv == null || !player.getAdvancementProgress(adv).isDone()) out.add(type.display());
        }
        return out;
    }
}
