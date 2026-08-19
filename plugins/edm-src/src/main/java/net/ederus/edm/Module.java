package net.ederus.edm;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;

import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;

/*
 * Un modulo de EDM se comporta como un plugin de Bukkit: puede registrar eventos,
 * programar tareas y crear NamespacedKey igual que antes de la fusion.
 *
 * Lo que NO delega, y es la razon de que esta clase exista:
 *   - getName()       devuelve el nombre historico del plugin (Rip, Anomaly, EderusMain)
 *                     para que los NamespacedKey sigan siendo rip:algo y no edm:algo.
 *                     Si esto cambiara, las entidades y items ya marcados en el mundo
 *                     dejarian de reconocerse.
 *   - getDataFolder() da plugins/EDM/<id>, no plugins/EDM.
 *   - getConfig()     lee el config de ese subdirectorio, no el del nucleo.
 */
public abstract class Module implements Plugin {

    protected final EDMPlugin core;
    private final String id;
    private final String legacyName;
    private final File dataFolder;
    private FileConfiguration config;

    protected Module(EDMPlugin core, String id, String legacyName) {
        this.core = core;
        this.id = id;
        this.legacyName = legacyName;
        this.dataFolder = new File(core.getDataFolder(), id);
    }

    public final String getId() {
        return this.id;
    }

    public final EDMPlugin core() {
        return this.core;
    }

    /* El nombre historico: lo usan NamespacedKey y los mensajes del propio modulo. */
    @Override
    public final String getName() {
        return this.legacyName;
    }

    @Override
    public final File getDataFolder() {
        return this.dataFolder;
    }

    /* --- Configuracion propia del modulo --- */

    @Override
    public final FileConfiguration getConfig() {
        if (this.config == null) {
            this.reloadConfig();
        }
        return this.config;
    }

    @Override
    public final void reloadConfig() {
        File file = new File(this.dataFolder, "config.yml");
        this.config = YamlConfiguration.loadConfiguration(file);
        InputStream defaults = getResource("config.yml");
        if (defaults != null) {
            this.config.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
    }

    @Override
    public final void saveConfig() {
        try {
            getConfig().save(new File(this.dataFolder, "config.yml"));
        } catch (IOException e) {
            getLogger().warning("No se pudo guardar config.yml de " + this.id + ": " + e.getMessage());
        }
    }

    @Override
    public final void saveDefaultConfig() {
        saveResource("config.yml", false);
    }

    /* Los recursos del modulo viven dentro del jar en <id>/loquesea. */
    @Override
    public final InputStream getResource(String name) {
        return this.core.getResource(this.id + "/" + name);
    }

    @Override
    public final void saveResource(String name, boolean replace) {
        File out = new File(this.dataFolder, name);
        if (out.exists() && !replace) {
            return;
        }
        try (InputStream in = getResource(name)) {
            if (in == null) {
                getLogger().warning("El jar no trae el recurso " + this.id + "/" + name);
                return;
            }
            File parent = out.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (OutputStream os = Files.newOutputStream(out.toPath())) {
                in.transferTo(os);
            }
        } catch (IOException e) {
            getLogger().warning("No se pudo escribir " + name + " de " + this.id + ": " + e.getMessage());
        }
    }

    /* --- Todo lo demas es el nucleo --- */

    @Override
    public final Logger getLogger() {
        return this.core.getLogger();
    }

    @Override
    public final Server getServer() {
        return this.core.getServer();
    }

    @Override
    public final PluginDescriptionFile getDescription() {
        return this.core.getDescription();
    }

    @Override
    public final PluginMeta getPluginMeta() {
        return this.core.getPluginMeta();
    }

    @Override
    @SuppressWarnings("deprecation")
    public final PluginLoader getPluginLoader() {
        return this.core.getPluginLoader();
    }

    @Override
    public final LifecycleEventManager<Plugin> getLifecycleManager() {
        return this.core.getLifecycleManager();
    }

    @Override
    public final boolean isEnabled() {
        return this.core.isEnabled();
    }

    @Override
    public final boolean isNaggable() {
        return this.core.isNaggable();
    }

    @Override
    public final void setNaggable(boolean naggable) {
        this.core.setNaggable(naggable);
    }

    @Override
    public final ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return null;
    }

    @Override
    public final BiomeProvider getDefaultBiomeProvider(String worldName, String id) {
        return null;
    }

    @Override
    public void onLoad() {
    }

    /* Los comandos los declara el plugin.yml del nucleo. */
    public final org.bukkit.command.PluginCommand getCommand(String name) {
        return this.core.getCommand(name);
    }

    /*
     * Plugin extiende TabExecutor y Namespaced. Los modulos que de verdad
     * responden comandos sobreescriben estos metodos (EderusMain lo hace).
     */
    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
            String label, String[] args) {
        return false;
    }

    @Override
    public java.util.List<String> onTabComplete(org.bukkit.command.CommandSender sender,
            org.bukkit.command.Command command, String label, String[] args) {
        return null;
    }

    @Override
    public final String namespace() {
        return this.legacyName.toLowerCase(java.util.Locale.ROOT);
    }

    /*
     * onEnable/onDisable los implementa cada modulo (son los de siempre).
     * El nucleo los llama a mano; Bukkit nunca ve estos objetos como plugins cargados.
     */
}
