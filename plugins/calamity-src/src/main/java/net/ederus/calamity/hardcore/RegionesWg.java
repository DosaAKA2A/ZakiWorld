package net.ederus.calamity.hardcore;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Calamity 1.11 · Si un punto cae dentro de una region de WorldGuard con nombre (calamity_exterior),
 * por reflexion como ZonaSpawn. La region se busca como mucho cada 10 s por mundo y nombre; la
 * pregunta en si (ProtectedRegion.contains) es barata. Sin WorldGuard, o sin esa region, nada esta
 * dentro.
 */
final class RegionesWg {

    private record Cache(Object region, long hasta) {
    }

    private static final long VIDA_MS = 10_000;
    private static final Map<String, Cache> CACHE = new HashMap<>();
    private static Method contains;
    private static boolean fallo;

    private RegionesWg() {
    }

    /** Si (x, y, z) esta en la region nombre de ese mundo. Solo hilo principal. */
    static boolean dentro(World w, String nombre, int x, int y, int z) {
        if (w == null || nombre == null || nombre.isBlank() || fallo) return false;
        Object r = region(w, nombre.trim());
        if (r == null || contains == null) return false;
        try {
            return (boolean) contains.invoke(r, x, y, z);
        } catch (Throwable t) {
            return false;
        }
    }

    static void olvidar() {
        CACHE.clear();
    }

    private static Object region(World w, String nombre) {
        String k = w.getName() + "|" + nombre;
        long ahora = System.currentTimeMillis();
        Cache c = CACHE.get(k);
        if (c != null && c.hasta() > ahora) return c.region();
        Object r = buscar(w, nombre);
        CACHE.put(k, new Cache(r, ahora + VIDA_MS));
        return r;
    }

    private static Object buscar(World w, String nombre) {
        Plugin wg = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (wg == null || !wg.isEnabled()) return null;
        try {
            ClassLoader cl = wg.getClass().getClassLoader();
            Class<?> cWg = Class.forName("com.sk89q.worldguard.WorldGuard", true, cl);
            Object instancia = cWg.getMethod("getInstance").invoke(null);
            Object plataforma = cWg.getMethod("getPlatform").invoke(instancia);
            Object contenedor = Class.forName("com.sk89q.worldguard.internal.platform.WorldGuardPlatform", true, cl)
                    .getMethod("getRegionContainer").invoke(plataforma);
            Class<?> cMundoWe = Class.forName("com.sk89q.worldedit.world.World", true, cl);
            Object mundoWe = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, cl)
                    .getMethod("adapt", World.class).invoke(null, w);
            Object gestor = Class.forName("com.sk89q.worldguard.protection.regions.RegionContainer", true, cl)
                    .getMethod("get", cMundoWe).invoke(contenedor, mundoWe);
            if (gestor == null) return null;
            Object r = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager", true, cl)
                    .getMethod("getRegion", String.class).invoke(gestor, nombre);
            if (r == null) return null;
            if (contains == null) {
                contains = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion", true, cl)
                        .getMethod("contains", int.class, int.class, int.class);
            }
            return r;
        } catch (Throwable t) {
            fallo = true;
            Bukkit.getLogger().warning("[Calamity] No se pudo leer la región «" + nombre + "» de WorldGuard: " + t
                    + ". Las ruinas y la Bóveda Caída no la tendrán en cuenta.");
            return null;
        }
    }
}
