package net.ederus.edm.anomaly.core;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Level;

/**
 * Decide si un punto del mapa es "de alguien". Ederus protege con WorldGuard y
 * ProtectionStones; ProtectionStones crea sus claims COMO regiones de WorldGuard, asi que
 * un solo hook cubre tanto las regiones de admin como los terrenos de los jugadores.
 *
 * Todo el hook es por reflexion: si manana quitan WorldGuard el plugin sigue arrancando,
 * solo pierde esa comprobacion (y lo dice en el log, no en silencio).
 */
public final class Protection {

    /** Bloques que delatan una base aunque nadie la haya reclamado con un claim. */
    private static final Set<Material> BASE_MARKERS = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL, Material.ENDER_CHEST,
            Material.SHULKER_BOX, Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
            Material.CRAFTING_TABLE, Material.ENCHANTING_TABLE, Material.ANVIL, Material.BREWING_STAND,
            Material.BEACON, Material.RESPAWN_ANCHOR, Material.LODESTONE, Material.HOPPER,
            Material.SPAWNER, Material.END_PORTAL_FRAME);

    private final Plugin plugin;

    private boolean worldGuardReady;
    private Object regionQuery;
    private Method adaptLocation;
    private Method getApplicableRegions;
    private Method regionSetSize;

    public Protection(Plugin plugin) {
        this.plugin = plugin;
        setupWorldGuard();
    }

    public boolean hasWorldGuard() {
        return worldGuardReady;
    }

    private void setupWorldGuard() {
        if (plugin.getServer().getPluginManager().getPlugin("WorldGuard") == null) {
            plugin.getLogger().info("WorldGuard no esta instalado: las anomalias solo evitaran bases por heuristica.");
            return;
        }
        try {
            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object wg = wgClass.getMethod("getInstance").invoke(null);
            Object platform = wgClass.getMethod("getPlatform").invoke(wg);
            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            this.regionQuery = container.getClass().getMethod("createQuery").invoke(container);

            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            for (Method m : adapter.getMethods()) {
                if (m.getName().equals("adapt") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == Location.class) {
                    this.adaptLocation = m;
                    break;
                }
            }
            if (adaptLocation == null) throw new NoSuchMethodException("BukkitAdapter.adapt(org.bukkit.Location)");

            Class<?> weLocation = adaptLocation.getReturnType();
            this.getApplicableRegions = regionQuery.getClass().getMethod("getApplicableRegions", weLocation);
            this.regionSetSize = this.getApplicableRegions.getReturnType().getMethod("size");

            this.worldGuardReady = true;
            plugin.getLogger().info("WorldGuard enganchado: las anomalias no apareceran dentro de ninguna region.");
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING,
                    "WorldGuard esta instalado pero no se pudo enganchar (" + t + "). Se seguira solo con la heuristica de bases.");
        }
    }

    /** true si en ese punto exacto hay al menos una region de WorldGuard. */
    public boolean insideRegion(Location loc) {
        if (!worldGuardReady || loc == null) return false;
        try {
            Object weLoc = adaptLocation.invoke(null, loc);
            Object set = getApplicableRegions.invoke(regionQuery, weLoc);
            int size = (int) regionSetSize.invoke(set);
            return size > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Comprueba el punto y un anillo a su alrededor, para que el jefe no aparezca
     * pegado al borde de un claim y acabe peleando dentro de el.
     */
    public boolean nearRegion(Location loc, double margin) {
        if (!worldGuardReady || loc == null) return false;
        if (insideRegion(loc)) return true;
        if (margin <= 0) return false;
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8.0;
            Location probe = loc.clone().add(Math.cos(a) * margin, 0, Math.sin(a) * margin);
            if (insideRegion(probe)) return true;
            if (insideRegion(probe.clone().add(0, 8, 0))) return true;
        }
        return false;
    }

    /**
     * Rastro de base sin claim: cofres, hornos, yunques... Muestrea de 2 en 2 bloques,
     * que a 24 de radio son ~3.500 lecturas y solo se hace sobre la ubicacion finalista.
     */
    public boolean looksInhabited(Location center, int radius, int height) {
        World w = center.getWorld();
        if (w == null) return false;
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        for (int x = -radius; x <= radius; x += 2) {
            for (int z = -radius; z <= radius; z += 2) {
                for (int y = -height; y <= height; y += 2) {
                    Block b = w.getBlockAt(cx + x, cy + y, cz + z);
                    Material m = b.getType();
                    if (BASE_MARKERS.contains(m)) return true;
                    if (m.name().endsWith("_BED") || m.name().endsWith("_SHULKER_BOX")) return true;
                }
            }
        }
        return false;
    }

    /** Distancia al spawn del mundo, para respetar el radio protegido del servidor. */
    public boolean tooCloseToSpawn(Location loc, double minDistance) {
        World w = loc.getWorld();
        if (w == null) return false;
        Location spawn = w.getSpawnLocation();
        if (!spawn.getWorld().equals(w)) return false;
        double dx = spawn.getX() - loc.getX();
        double dz = spawn.getZ() - loc.getZ();
        return (dx * dx + dz * dz) < (minDistance * minDistance);
    }
}
