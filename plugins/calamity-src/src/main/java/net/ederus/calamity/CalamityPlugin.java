package net.ederus.calamity;

import org.bukkit.plugin.java.JavaPlugin;

import net.ederus.calamity.hardcore.ComandoCalamity;
import net.ederus.calamity.hardcore.Hardcore;
import net.ederus.calamity.hardcore.PlaceholdersLethal;
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
 *
 * Lo que NO cambia al separarlos, y es a proposito: las marcas del PersistentDataContainer
 * siguen siendo lethal_world: (Marcas.NAMESPACE), los placeholders siguen siendo
 * %lethalworld_...% y el permiso de /calamity sigue siendo lethalworld.calamity. Son cosas que
 * ya existen en el servidor (objetos, mobs, scoreboards, grupos de LuckPerms) y tienen que
 * seguir reconociendose igual que antes.
 */
public final class CalamityPlugin extends JavaPlugin {

    /** La version, en el mismo sitio que en LethalWorld. Se sube a la vez que pom.xml y plugin.yml. */
    public static final String VERSION = "1.0.0";

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

        // TODO fase 2: migrarDeLethalWorld();
        saveDefaultConfig();
        reloadConfig();

        mobs = new MobsLethal(this);
        mobs.arrancar();
        hardcore = new Hardcore(this);
        hardcore.arrancar();

        // /calamity es de los jugadores: va aparte de /calamidad, que es de staff.
        var cal = getCommand("calamity");
        if (cal != null) {
            var calamity = new ComandoCalamity(this);
            cal.setExecutor(calamity);
            cal.setTabCompleter(calamity);
        } else {
            getLogger().warning("El comando /calamity no esta en el plugin.yml.");
        }
        // /calamidad: lo que antes era /lw hardcore y /lw level.
        var cld = getCommand("calamidad");
        if (cld != null) {
            var calamidad = new ComandoCalamidad(this);
            cld.setExecutor(calamidad);
            cld.setTabCompleter(calamidad);
        } else {
            getLogger().warning("El comando /calamidad no esta en el plugin.yml.");
        }

        // Despues de arrancar: los modulos registran sus placeholders al nacer.
        PlaceholdersLethal.activar(this);

        // /lw hardcore y /lw level siguen funcionando: se reescriben a /calamidad.
        getServer().getPluginManager().registerEvents(new RedireccionComandos(), this);
        RedireccionComandos.engancharLw(this);

        getLogger().info("[Calamity] " + VERSION + " listo, sobre LethalWorld "
                + lethalWorld.getPluginMeta().getVersion() + ".");
    }

    @Override
    public void onDisable() {
        RedireccionComandos.soltarLw();
        if (mobs != null) mobs.parar();
        if (hardcore != null) hardcore.parar();
        PlaceholdersLethal.desactivar();
    }
}
