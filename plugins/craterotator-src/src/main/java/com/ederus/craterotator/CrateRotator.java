package com.ederus.craterotator;

import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.data.DisplayHologramData;
import de.oliver.fancyholograms.api.data.HologramData;
import de.oliver.fancyholograms.api.data.ItemHologramData;
import de.oliver.fancyholograms.api.data.property.Visibility;
import de.oliver.fancyholograms.api.hologram.Hologram;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gira los hologramas tipo ITEM de FancyHolograms con una entidad nativa
 * (ItemDisplay) en su lugar, porque FancyHolograms mueve los suyos a base de
 * teletransportes y eso el cliente no lo interpola.
 * <p>
 * Lo que fallaba en la version anterior, y por que las cajas desaparecian o
 * se quedaban quietas:
 * <ul>
 *   <li>La entidad se creaba con setPersistent(false) y, en cuanto moria
 *       (ClearLag la barre, el chunk se descarga, un /kill @e, un reinicio a
 *       medias), el bucle la sacaba de la lista y NUNCA la volvia a crear.
 *       Como el holograma original ya estaba oculto, no quedaba nada.
 *       Ahora cada tick comprueba que la entidad siga viva y, si no, la
 *       vuelve a crear en el mismo sitio.</li>
 *   <li>Si FancyHolograms aun no habia cargado a los 5 s del arranque, o no
 *       encontraba el holograma, no se creaba nada y el bucle ni arrancaba:
 *       quietas para siempre hasta un /crot a mano. Ahora el bucle arranca
 *       siempre y reintenta cada pocos segundos los que falten.</li>
 *   <li>El giro se hacia con setRotation (el yaw de la entidad) y
 *       setTeleportDuration. Al pasar de 354 a 0 grados el cliente
 *       interpolaba 354 grados hacia atras, y con lag de paquetes el giro
 *       se veia a tirones o parado. Ahora se gira la TRANSFORMACION del
 *       display (leftRotation) con setInterpolationDuration, que es lo que
 *       Minecraft interpola de verdad y no tiene salto en la vuelta.</li>
 *   <li>Al apagar el plugin el holograma original se quedaba en visibilidad
 *       MANUAL (oculto) para siempre. Ahora se devuelve a ALL al parar, y
 *       tambien si no se pudo crear la entidad.</li>
 * </ul>
 */
public final class CrateRotator extends JavaPlugin {

    /** Un holograma que gira: de donde sale, con que item y la entidad viva (o null). */
    private static final class Spinner {
        final String name;
        final Location location;
        final ItemStack item;
        final Vector3f scale;
        final Vector3f translation;
        ItemDisplay display;
        float angle;            // grados, 0..360
        int failures;           // intentos seguidos de crear la entidad que fallaron

        Spinner(String name, Location location, ItemStack item, Vector3f scale, Vector3f translation) {
            this.name = name;
            this.location = location;
            this.item = item;
            this.scale = scale;
            this.translation = translation;
        }
    }

    private static final int RETRY_TICKS = 100;   // cada cuanto se reintentan los que faltan (5 s)

    private final NamespacedKey spinnerKey = new NamespacedKey(this, "crate-spinner");
    // Todo corre en el hilo principal (scheduler sincrono): un mapa normal basta.
    private final Map<String, Spinner> spinners = new LinkedHashMap<>();
    private List<String> pending = List.of();
    private BukkitTask task;
    private int step = 6;
    private int interval = 2;
    private int sinceRetry = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        start();
    }

    @Override
    public void onDisable() {
        stop();
    }

    private void start() {
        stop();
        reloadConfig();

        pending = getConfig().getStringList("holograms");
        step = getConfig().getInt("degrees-per-step", 6);
        interval = (int) Math.max(1L, getConfig().getLong("interval-ticks", 2L));
        final long startupDelay = Math.max(0L, getConfig().getLong("startup-delay-ticks", 100L));

        if (pending.isEmpty()) {
            getLogger().warning("No hay hologramas en config.yml (lista 'holograms'). No se rotara nada.");
            return;
        }

        // Entidades de una sesion anterior (una caida sin onDisable): fuera,
        // o habria dos cajas superpuestas.
        removeLeftoverSpinners();

        // El bucle arranca SIEMPRE. Lo que falte por crear se reintenta desde
        // dentro: asi un FancyHolograms lento no deja las cajas quietas.
        sinceRetry = RETRY_TICKS;   // el primer intento va nada mas cumplir el retraso inicial
        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, startupDelay, interval);
        getLogger().info("Rotador en marcha: " + pending.size() + " holograma(s), +" + step
                + " grados cada " + interval + " ticks.");
    }

    private void tick() {
        sinceRetry += interval;
        if (sinceRetry >= RETRY_TICKS) {
            sinceRetry = 0;
            setupMissing();
        }

        for (Spinner spinner : spinners.values()) {
            ItemDisplay display = spinner.display;
            if (display == null || display.isDead() || !display.isValid()) {
                // Se la llevo ClearLag, el chunk o alguien. Se vuelve a crear
                // en cuanto el chunk este cargado; mientras, no hay nada que girar.
                spinner.display = null;
                if (spinner.location.isChunkLoaded()) respawn(spinner);
                continue;
            }
            spinner.angle += step;
            if (spinner.angle >= 360f) spinner.angle -= 360f;
            applyRotation(spinner);
        }
    }

    /** Manda al cliente la rotacion nueva y le dice que la anime durante 'interval' ticks. */
    private void applyRotation(Spinner spinner) {
        ItemDisplay display = spinner.display;
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(interval);
        display.setTransformation(new Transformation(
                spinner.translation,
                new AxisAngle4f((float) Math.toRadians(spinner.angle), 0f, 1f, 0f),
                spinner.scale,
                new AxisAngle4f(0f, 0f, 0f, 1f)));
    }

    /** Los de la lista que aun no tienen Spinner: se leen de FancyHolograms y se crean. */
    private void setupMissing() {
        if (spinners.size() >= pending.size()) return;
        if (!FancyHologramsPlugin.isEnabled()) return;
        final HologramManager manager = FancyHologramsPlugin.get().getHologramManager();
        for (String name : pending) {
            if (spinners.containsKey(name)) continue;
            try {
                setupSpinner(manager, name);
            } catch (Throwable t) {
                getLogger().warning("No se pudo preparar el giro de '" + name + "': " + t);
            }
        }
    }

    /**
     * Lee posicion, item y apariencia del holograma de FancyHolograms, lo
     * oculta y crea el ItemDisplay que vamos a girar.
     */
    private void setupSpinner(HologramManager manager, String name) {
        Optional<Hologram> opt = manager.getHologram(name);
        if (opt.isEmpty()) {
            getLogger().warning("No existe ningun holograma llamado '" + name + "' en FancyHolograms (se reintenta).");
            return;
        }

        Hologram hologram = opt.get();
        HologramData data = hologram.getData();

        if (!(data instanceof ItemHologramData itemData)) {
            getLogger().warning("El holograma '" + name + "' no es de tipo ITEM (es " + data.getType()
                    + "); se deja como esta.");
            return;
        }

        Location location = data.getLocation().clone();
        if (location.getWorld() == null) {
            getLogger().warning("El holograma '" + name + "' esta en un mundo que no esta cargado (se reintenta).");
            return;
        }
        ItemStack item = itemData.getItemStack();
        if (item == null || item.getType() == Material.AIR) {
            item = new ItemStack(Material.CHEST);
        }

        Vector3f scale = new Vector3f(1, 1, 1);
        Vector3f translation = new Vector3f(0, 0, 0);
        if (data instanceof DisplayHologramData displayData) {
            scale = new Vector3f(displayData.getScale());
            translation = new Vector3f(displayData.getTranslation());
        }

        Spinner spinner = new Spinner(name, location, item.clone(), scale, translation);
        spinners.put(name, spinner);

        // Se oculta el original solo cuando la copia ya existe: asi nunca hay
        // un momento sin caja.
        if (spinner.location.isChunkLoaded()) respawn(spinner);
        data.setVisibility(Visibility.MANUAL);
        // Cambiar la visibilidad NO se la quita a quien ya la estaba viendo:
        // FancyHolograms solo la aplica a quien entra despues. Sin esto, los
        // que estaban conectados al arrancar veian la caja original quieta
        // debajo de la copia que gira (2.1.1).
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            hologram.forceHideHologram(player);
        }

        getLogger().info("Giro creado para '" + name + "' en " + location.getWorld().getName()
                + " (" + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + ").");
    }

    /** Crea (o vuelve a crear) la entidad de un Spinner en su sitio. */
    private void respawn(Spinner spinner) {
        try {
            ItemDisplay display = spinner.location.getWorld().spawn(spinner.location, ItemDisplay.class, entity -> {
                entity.setItemStack(spinner.item);
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setPersistent(false);
                entity.setInvulnerable(true);
                entity.setGravity(false);
                entity.setTransformation(new Transformation(
                        spinner.translation,
                        new AxisAngle4f((float) Math.toRadians(spinner.angle), 0f, 1f, 0f),
                        spinner.scale,
                        new AxisAngle4f(0f, 0f, 0f, 1f)));
                entity.getPersistentDataContainer().set(spinnerKey, PersistentDataType.STRING, spinner.name);
            });
            spinner.display = display;
            if (spinner.failures > 0) {
                getLogger().info("La caja '" + spinner.name + "' habia desaparecido; vuelta a crear.");
            }
            spinner.failures = 0;
        } catch (Throwable t) {
            spinner.display = null;
            if (spinner.failures++ == 0) {
                getLogger().warning("No se pudo crear la entidad de '" + spinner.name + "': " + t);
            }
        }
    }

    private void removeLeftoverSpinners() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(ItemDisplay.class)) {
                if (entity.getPersistentDataContainer().has(spinnerKey, PersistentDataType.STRING)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            getLogger().info("Se limpiaron " + removed + " entidad(es) de giro sobrantes de un arranque anterior.");
        }
    }

    private void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // Los originales vuelven a verse: sin esto, quitar el plugin (o un
        // fallo suyo) dejaba las cajas invisibles para siempre.
        if (!spinners.isEmpty() && FancyHologramsPlugin.isEnabled()) {
            HologramManager manager = FancyHologramsPlugin.get().getHologramManager();
            for (String name : spinners.keySet()) {
                manager.getHologram(name).ifPresent(h -> {
                    h.getData().setVisibility(Visibility.ALL);
                    for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                        h.forceUpdateShownStateFor(player);
                    }
                });
            }
        }
        for (Spinner spinner : spinners.values()) {
            if (spinner.display != null && !spinner.display.isDead()) {
                spinner.display.remove();
            }
        }
        spinners.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("craterotator.admin")) {
            sender.sendMessage("§cNo tienes permiso.");
            return true;
        }
        start();
        sender.sendMessage("§aCrateRotator recargado. Girando: §f" + getConfig().getStringList("holograms"));
        return true;
    }
}
