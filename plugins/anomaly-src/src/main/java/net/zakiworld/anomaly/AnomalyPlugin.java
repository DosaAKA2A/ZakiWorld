package net.zakiworld.anomaly;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.zakiworld.anomaly.core.Anchors;
import net.zakiworld.anomaly.core.Anim;
import net.zakiworld.anomaly.core.AnomalyManager;
import net.zakiworld.anomaly.core.AnomalyRegistry;
import net.zakiworld.anomaly.core.AnomalyType;
import net.zakiworld.anomaly.core.Announcer;
import net.zakiworld.anomaly.core.Compat;
import net.zakiworld.anomaly.core.Fx;
import net.zakiworld.anomaly.core.Protection;
import net.zakiworld.anomaly.core.Settings;
import net.zakiworld.anomaly.core.SiteFinder;
import net.zakiworld.anomaly.core.Tags;
import net.zakiworld.anomaly.drops.DropStore;
import net.zakiworld.anomaly.menu.Menus;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ANOMALY  ·  Iris Studio
 *
 * Eventos anomalos para Ederus: cada cierto tiempo (o cuando lo pide un operador)
 * se abre una grieta en un punto libre del mapa, sale un jefe por fases y quien lo
 * mate se lleva un botin que se configura entero desde el menu.
 *
 * La idea de fondo es empujar a la gente a salir del spawn y a pelear en grupo: el
 * jefe escala con el numero de jugadores, varias habilidades castigan dispersarse y
 * el botin se reparte entre todos los que participaron, no solo entre quien da el
 * ultimo golpe.
 */
public final class AnomalyPlugin extends JavaPlugin {

    /** La lee el banner de /anomaly info; hay que subirla junto al pom y al plugin.yml. */
    public static final String VERSION = "1.4.0";

    private static final TextColor BRAND = TextColor.color(0x9BD7E4);

    private Settings settings;
    private AnomalyRegistry registry;
    private DropStore drops;
    private Protection protection;
    private SiteFinder sites;
    private AnomalyManager manager;
    private Announcer announcer;
    private Menus menus;
    private Anchors anchors;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Tags.init(this);

        this.settings = new Settings(this);
        this.anchors = new Anchors();
        this.registry = new AnomalyRegistry(this);
        this.drops = new DropStore(this);
        this.drops.load();
        this.protection = new Protection(this);
        this.sites = new SiteFinder(this, protection, settings);
        this.announcer = new Announcer(this);
        this.manager = new AnomalyManager(this);
        this.menus = new Menus(this);

        getServer().getPluginManager().registerEvents(manager, this);
        getServer().getPluginManager().registerEvents(menus, this);

        PluginCommand command = getCommand("anomaly");
        if (command != null) {
            AnomalyCommand handler = new AnomalyCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        sweepLeftovers();
        manager.restartScheduler();

        getLogger().info("Anomaly " + VERSION + " listo. " + registry.all().size()
                + " anomalia(s) en el catalogo, " + Compat.missingParticles() + " particula(s) ausente(s).");
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
        if (drops != null) drops.save();
        Anim.cancelAll();
    }

    /**
     * Barrido de arranque. Si el servidor se cayo a mitad de un combate, en el mundo
     * quedan el jefe, sus esbirros y la decoracion; aqui se borran antes de que nadie
     * se encuentre un caballero suelto sin barra de vida.
     */
    private void sweepLeftovers() {
        int removed = 0;
        for (World world : getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (!Tags.isOurs(e)) continue;
                Fx.safeRemove(e);
                removed++;
            }
        }
        if (removed > 0) {
            getLogger().info("Barridas " + removed + " entidad(es) de una anomalia anterior.");
        }
    }

    public void reloadEverything() {
        manager.stop(true);
        Anim.cancelAll();
        reloadConfig();
        drops.load();
        manager.restartScheduler();
    }

    // -------------------------------------------------------------------- servicios

    public Settings settings() {
        return settings;
    }

    public AnomalyRegistry registry() {
        return registry;
    }

    public DropStore drops() {
        return drops;
    }

    public Protection protection() {
        return protection;
    }

    public SiteFinder sites() {
        return sites;
    }

    public AnomalyManager manager() {
        return manager;
    }

    public Announcer announcer() {
        return announcer;
    }

    public Menus menus() {
        return menus;
    }

    public Anchors anchors() {
        return anchors;
    }

    // ---------------------------------------------------------------------- estado

    /** La anomalia elegida en el menu; es la que abre el boton Iniciar. */
    public String selectedId() {
        String id = getConfig().getString("menu.seleccion", "");
        if (id.isBlank() || registry.get(id) == null) {
            AnomalyType first = registry.all().isEmpty() ? null : registry.all().get(0);
            return first == null ? "" : first.id();
        }
        return id;
    }

    public void selectedId(String id) {
        settings.set("menu.seleccion", id);
    }

    public AnomalyType selected() {
        return registry.get(selectedId());
    }

    /** Solo operadores o quien tenga anomaly.gui, tal y como se pidio. */
    public boolean mayUseGui(CommandSender sender) {
        if (sender.isOp()) return true;
        if (sender instanceof Player player) return player.hasPermission("anomaly.gui");
        return true; // la consola siempre puede
    }

    public Component prefix() {
        return Component.text("✦ ", BRAND)
                .append(Component.text("Anomaly", BRAND, TextDecoration.BOLD))
                .append(Component.text("  ", NamedTextColor.GRAY));
    }
}
