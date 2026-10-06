package net.ederus.calamity;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import net.ederus.calamity.hardcore.Hardcore;
import net.ederus.calamity.hardcore.PlaceholdersLethal;
import net.ederus.calamity.hardcore.Subcomandos;
import net.ederus.edm.comun.Bitacora;
import net.ederus.lethalworld.LethalWorldPlugin;

/**
 * Calamity: lo que se juega en los mundos de Lethal World. La PARCA, el Eco, la Aduana, la
 * cordura, las Reliquias, los Contratos y el resto de reglas hardcore, que solo existen en
 * los mundos de la lista hardcore.mundos, y los mobs con nivel de Lethal World (MobsLethal).
 *
 * Hasta LethalWorld 1.1.1 todo esto vivia dentro de LethalWorld, en net.ederus.lethalworld
 * .hardcore. Salio a plugin propio por lo mismo que LethalWorld salio de EDM: el jar de los
 * mundos lleva dentro el datapack de Bracken (33 MB) y casi nunca cambia, y Calamity cambia
 * casi a diario. Juntos, cada arreglo de una regla obligaba a recompilar y subir los mundos.
 * Ahora cada uno se mueve solo.
 *
 * De LethalWorld (depend en el plugin.yml) solo se usan dos cosas: saber si un mundo es suyo
 * (LethalWorldPlugin.esMundo) y su Bitacora lethal-world, que se comparte para que muertes,
 * Ecos, PARCAs y pagos sigan saliendo en el mismo log de siempre. Lo demas va en la carpeta
 * propia de este plugin, plugins/Calamity: config.yml, hardcore-datos.yml, la telemetria...
 * La primera vez que arranca se trae alli lo que LethalWorld 1.1.1 tenia de Calamity en
 * plugins/LethalWorld (ver migrarDeLethalWorld).
 *
 * Lo que NO cambia al separarlos, y es a proposito: las marcas del PersistentDataContainer
 * siguen siendo lethal_world: (Marcas.NAMESPACE), los placeholders siguen siendo
 * %lethalworld_...%. Son cosas que ya existen en el servidor (objetos, mobs, scoreboards) y
 * tienen que seguir reconociendose igual que antes. El permiso de los jugadores,
 * lethalworld.calamity, ya no abre nada desde la 1.12: /calamity es solo de staff (calamity.admin)
 * y los jugadores lo hacen todo desde los NPCs.
 */
public final class CalamityPlugin extends JavaPlugin {

    /** La version, en el mismo sitio que en LethalWorld. Se sube a la vez que pom.xml y plugin.yml. */
    public static final String VERSION = "1.17.0";

    private LethalWorldPlugin lethalWorld;
    private MobsLethal mobs;
    private Hardcore hardcore;

    /** El plugin de los mundos. No es null mientras Calamity este encendido. */
    public LethalWorldPlugin lethalWorld() {
        return lethalWorld;
    }

    /** El ciclo de mobs; lo usan tambien las reglas hardcore para invocar por su cuenta. */
    public MobsLethal mobs() {
        return mobs;
    }

    /** Las reglas de los mundos hardcore. Existe siempre; mira activo() antes de usarlo. */
    public Hardcore hardcore() {
        return hardcore;
    }

    /**
     * La Bitacora lethal-world, la de LethalWorld: Calamity no abre una suya. Todo lo que
     * antes anotaba (muertes, Ecos, PARCAs, pagos) sigue en plugins/LethalWorld/logs.
     */
    public Bitacora bitacora() {
        return lethalWorld.bitacora();
    }

    @Override
    public void onEnable() {
        // LethalWorld es depend: Paper no deberia arrancar esto sin el. Aun asi, mejor un
        // aviso claro que un NullPointerException a mitad de arranque.
        if (!(getServer().getPluginManager().getPlugin("LethalWorld") instanceof LethalWorldPlugin lw)) {
            getLogger().severe("[Calamity] No encuentro LethalWorld. Sin sus mundos Calamity no arranca.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.lethalWorld = lw;

        migrarDeLethalWorld();
        saveDefaultConfig();
        reloadConfig();

        mobs = new MobsLethal(this);
        mobs.arrancar();
        hardcore = new Hardcore(this);
        hardcore.arrancar();

        // 1.12: /calamity es el unico comando y es de staff (calamity.admin). Sin el permiso no sale
        // en el tab y contesta como un comando que no existe; los jugadores van por los NPCs.
        var cal = getCommand("calamity");
        if (cal != null) {
            var raiz = new ComandoRaiz(this);
            cal.setExecutor(raiz);
            cal.setTabCompleter(raiz);
            cal.setPermission(Subcomandos.PERMISO);
            cal.permissionMessage(ComandoRaiz.desconocido());
        } else {
            getLogger().warning("El comando /calamity no está en el plugin.yml.");
        }

        // Despues de arrancar: los modulos registran sus placeholders al nacer.
        PlaceholdersLethal.activar(this);

        // TEMPORAL: /lw hardcore, /lw level y la forma en espanol de la 1.11 se traducen a /calamity
        // (por consola, NPCs y premios guardados) y avisan en la consola para que se cambien.
        getServer().getPluginManager().registerEvents(new RedireccionComandos(this), this);
        RedireccionComandos.engancharLw(this);
        RedireccionComandos.registrarOculto(this);

        getLogger().info("[Calamity] " + VERSION + " listo, sobre LethalWorld "
                + lethalWorld.getPluginMeta().getVersion() + ".");
    }

    @Override
    public void onDisable() {
        RedireccionComandos.soltar();
        if (mobs != null) mobs.parar();
        if (hardcore != null) hardcore.parar();
        PlaceholdersLethal.desactivar();
    }

    // -------------------------------------------------------------------- migracion

    /** Los datos de Calamity que LethalWorld 1.1.1 dejaba en su carpeta. Se copian tal cual. */
    private static final List<String> DATOS_VIEJOS =
            List.of("hardcore-datos.yml", "reliquias.log", "telemetria", "encuestas");

    /** Lo que en plugins/LethalWorld sigue siendo de LethalWorld: ni se copia ni se avisa. */
    private static final Set<String> DE_LETHALWORLD = Set.of("config.yml", "logs", "pregen.yml");

    /**
     * La primera vez: se trae lo de Calamity que LethalWorld 1.1.1 dejo en plugins/LethalWorld.
     * Hasta entonces las reglas hardcore y los mobs vivian dentro de LethalWorld, y con ellos
     * su config (hardcore: y mobs:) y sus datos (horas y cordura guardada, Reliquias,
     * telemetria, encuestas). Sin esto, un servidor que ya tenia Calamity montado arrancaria
     * con la config de fabrica: sin sus mundos hardcore, sin sus puertas, sin las horas ni las
     * Reliquias de nadie.
     *
     * Solo corre si plugins/Calamity/config.yml aun no existe y el config de LethalWorld trae
     * hardcore: o mobs: con algo dentro. Si no, no toca nada ni dice nada, y el arranque sigue
     * con la config del jar. Manda lo viejo: esas secciones pisan enteras a las de fabrica, con
     * todo lo que el staff hubiera tocado a mano o desde el panel. La carpeta de LethalWorld NO
     * se toca: queda como copia por si hay que volver atras (LethalWorld 2.0.0 ya no lee esas
     * secciones, y logs/ y pregen.yml siguen siendo suyos).
     */
    private void migrarDeLethalWorld() {
        File destino = getDataFolder();
        if (new File(destino, "config.yml").isFile()) return;
        File vieja = new File(getServer().getPluginsFolder(), "LethalWorld");
        File configVieja = new File(vieja, "config.yml");
        if (!configVieja.isFile()) return;
        YamlConfiguration viejo = YamlConfiguration.loadConfiguration(configVieja);
        List<String> secciones = new ArrayList<>();
        for (String s : List.of("hardcore", "mobs")) {
            if (!viejo.isConfigurationSection(s)) continue;
            if (!viejo.getConfigurationSection(s).getKeys(false).isEmpty()) secciones.add(s);
        }
        if (secciones.isEmpty()) return;

        // Primero los datos y al final el config.yml, que es lo que da la migracion por hecha:
        // si el servidor se cae a medias, el siguiente arranque lo vuelve a intentar (y lo que
        // ya se hubiera copiado no se pisa).
        destino.mkdirs();
        List<String> traidos = new ArrayList<>();
        for (String nombre : DATOS_VIEJOS) {
            Path origen = new File(vieja, nombre).toPath();
            if (!Files.exists(origen)) continue;
            try {
                int n = copiar(origen, new File(destino, nombre).toPath());
                if (Files.isDirectory(origen)) traidos.add(nombre + "/ (" + n + (n == 1 ? " fichero)" : " ficheros)"));
                else traidos.add(n > 0 ? nombre : nombre + " (ya estaba, no se pisa)");
            } catch (IOException | UncheckedIOException e) {
                getLogger().warning("[Calamity] No se pudo traer " + nombre
                        + " de plugins/LethalWorld: " + e.getMessage());
            }
        }

        // La config de fabrica en disco, con sus comentarios, y encima las secciones viejas
        // enteras: createSection con getValues(true) copia todas las subclaves tal cual
        // (mundos, puertas, llegada, salida, biomas, guarniciones...) sin ir una a una.
        saveDefaultConfig();
        reloadConfig();
        for (String s : secciones) {
            ConfigurationSection seccionVieja = viejo.getConfigurationSection(s);
            getConfig().createSection(s, seccionVieja.getValues(true));
        }
        saveConfig();
        // Las subsecciones copiadas siguen colgando en memoria del fichero viejo (su raiz es
        // el config de LethalWorld). Releido de disco, el config ya es solo de Calamity.
        reloadConfig();

        getLogger().info("[Calamity] Importada la configuración de plugins/LethalWorld/config.yml ("
                + String.join(", ", secciones) + ")"
                + (traidos.isEmpty() ? "" : " y sus datos: " + String.join(", ", traidos))
                + ". La carpeta de LethalWorld queda como estaba.");

        List<String> sinTraer = new ArrayList<>();
        File[] todo = vieja.listFiles();
        if (todo != null) {
            for (File f : todo) {
                String n = f.getName();
                if (DE_LETHALWORLD.contains(n) || DATOS_VIEJOS.contains(n)) continue;
                sinTraer.add(f.isDirectory() ? n + "/" : n);
            }
        }
        if (!sinTraer.isEmpty()) {
            sinTraer.sort(null);
            getLogger().warning("[Calamity] En plugins/LethalWorld hay cosas que no se reconocen"
                    + " y no se han traído: " + String.join(", ", sinTraer) + ". Si son de Calamity,"
                    + " hay que copiarlas a mano a plugins/Calamity.");
        }
    }

    /**
     * Copia un fichero, o una carpeta con todo lo que tenga dentro. Lo que ya este en destino
     * no se pisa. Devuelve cuantos ficheros ha copiado.
     */
    private static int copiar(Path origen, Path destino) throws IOException {
        List<Path> todo;
        try (Stream<Path> recorrido = Files.walk(origen)) {
            todo = recorrido.toList();
        }
        int copiados = 0;
        for (Path p : todo) {
            Path q = destino.resolve(origen.relativize(p));
            if (Files.isDirectory(p)) {
                Files.createDirectories(q);
            } else if (!Files.exists(q)) {
                Files.copy(p, q, StandardCopyOption.COPY_ATTRIBUTES);
                copiados++;
            }
        }
        return copiados;
    }
}
