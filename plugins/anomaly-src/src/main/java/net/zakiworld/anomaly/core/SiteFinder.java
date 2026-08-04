package net.zakiworld.anomaly.core;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Busca donde plantar una anomalia. El objetivo es que salga LEJOS de las bases pero
 * cerca de donde hay gente, para que valga la pena salir a explorar sin obligar a
 * cruzar el mapa entero.
 *
 * Los trozos se cargan de forma asincrona: buscar un sitio no puede congelar el servidor.
 */
public final class SiteFinder {

    private final Plugin plugin;
    private final Protection protection;
    private final Settings settings;
    private final Random random = new Random();

    public SiteFinder(Plugin plugin, Protection protection, Settings settings) {
        this.plugin = plugin;
        this.protection = protection;
        this.settings = settings;
    }

    /** Entrega una ubicacion valida, o null si tras todos los intentos no hay ninguna. */
    public void find(Consumer<Location> callback) {
        World world = pickWorld();
        if (world == null) {
            callback.accept(null);
            return;
        }
        Location anchor = pickAnchor(world);
        attempt(world, anchor, 0, callback);
    }

    private World pickWorld() {
        List<String> allowed = settings.allowedWorlds();
        List<World> pool = new ArrayList<>();
        for (World w : plugin.getServer().getWorlds()) {
            if (allowed.isEmpty() || allowed.contains(w.getName())) pool.add(w);
        }
        if (pool.isEmpty()) return null;
        // Se prefiere un mundo que tenga gente dentro; si no, el primero permitido.
        List<World> withPlayers = new ArrayList<>();
        for (World w : pool) {
            for (Player p : w.getPlayers()) {
                if (Fx.isFightable(p)) {
                    withPlayers.add(w);
                    break;
                }
            }
        }
        List<World> from = withPlayers.isEmpty() ? pool : withPlayers;
        return from.get(random.nextInt(from.size()));
    }

    private Location pickAnchor(World world) {
        List<Player> players = new ArrayList<>();
        for (Player p : world.getPlayers()) {
            if (Fx.isFightable(p)) players.add(p);
        }
        if (players.isEmpty()) return world.getSpawnLocation();
        return players.get(random.nextInt(players.size())).getLocation();
    }

    private void attempt(World world, Location anchor, int tries, Consumer<Location> callback) {
        if (tries >= settings.searchAttempts()) {
            callback.accept(null);
            return;
        }
        double min = settings.minDistance();
        double max = Math.max(min + 16, settings.maxDistance());
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = min + random.nextDouble() * (max - min);
        int x = (int) (anchor.getX() + Math.cos(angle) * dist);
        int z = (int) (anchor.getZ() + Math.sin(angle) * dist);

        // Fuera del borde del mundo no hay nada que buscar.
        double border = world.getWorldBorder().getSize() / 2.0 - 32;
        Location center = world.getWorldBorder().getCenter();
        if (Math.abs(x - center.getX()) > border || Math.abs(z - center.getZ()) > border) {
            attempt(world, anchor, tries + 1, callback);
            return;
        }

        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            Location candidate = evaluate(world, x, z);
            if (candidate != null) {
                callback.accept(candidate);
            } else {
                attempt(world, anchor, tries + 1, callback);
            }
        })).exceptionally(t -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> attempt(world, anchor, tries + 1, callback));
            return null;
        });
    }

    /** Devuelve la ubicacion si pasa todos los filtros, o null si hay que probar otra. */
    private Location evaluate(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y <= world.getMinHeight() + 1 || y >= world.getMaxHeight() - 8) return null;

        Block floor = world.getBlockAt(x, y, z);
        if (!floor.getType().isSolid()) return null;
        if (floor.isLiquid()) return null;

        Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);

        // Hace falta un claro: el jefe mide mas de dos bloques y va montado.
        if (!hasHeadroom(world, x, y + 1, z, 4)) return null;
        if (!isFlatEnough(world, x, y, z, settings.arenaRadius())) return null;

        if (protection.tooCloseToSpawn(loc, settings.minSpawnDistance())) return null;
        if (protection.nearRegion(loc, settings.protectionMargin())) return null;
        if (settings.avoidBases() && protection.looksInhabited(loc, settings.baseScanRadius(), 8)) return null;

        return loc;
    }

    private boolean hasHeadroom(World world, int x, int y, int z, int needed) {
        for (int i = 0; i < needed; i++) {
            Material m = world.getBlockAt(x, y + i, z).getType();
            if (m.isSolid()) return false;
            if (m == Material.LAVA || m == Material.WATER) return false;
        }
        return true;
    }

    /**
     * Mide cuanto sube y baja el terreno alrededor. Un jefe montado en un caballo
     * dentro de un barranco es un jefe que no se puede pelear.
     */
    private boolean isFlatEnough(World world, int x, int y, int z, int radius) {
        int checks = 0;
        int ok = 0;
        for (int dx = -radius; dx <= radius; dx += 3) {
            for (int dz = -radius; dz <= radius; dz += 3) {
                if (dx * dx + dz * dz > radius * radius) continue;
                checks++;
                int h = world.getHighestBlockYAt(x + dx, z + dz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (Math.abs(h - y) <= settings.maxSlope()) ok++;
            }
        }
        if (checks == 0) return false;
        return (ok / (double) checks) >= 0.72;
    }
}
