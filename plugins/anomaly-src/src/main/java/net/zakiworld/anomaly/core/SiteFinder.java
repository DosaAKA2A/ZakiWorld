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
 * El terreno que acepta depende del elemento de la anomalia: una de tierra exige suelo
 * firme y seco, una de agua exige justo lo contrario.
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
    public void find(AnomalyType type, Consumer<Location> callback) {
        World world = pickWorld();
        if (world == null) {
            callback.accept(null);
            return;
        }
        attempt(world, pickAnchor(world), type, 0, callback);
    }

    private World pickWorld() {
        List<String> allowed = settings.allowedWorlds();
        List<World> pool = new ArrayList<>();
        for (World w : plugin.getServer().getWorlds()) {
            if (allowed.isEmpty() || allowed.contains(w.getName())) pool.add(w);
        }
        if (pool.isEmpty()) return null;
        List<World> withPlayers = new ArrayList<>();
        for (World w : pool) {
            if (!anyPlayer(w).isEmpty()) withPlayers.add(w);
        }
        List<World> from = withPlayers.isEmpty() ? pool : withPlayers;
        return from.get(random.nextInt(from.size()));
    }

    /**
     * Desde donde se mide la distancia.
     *
     * Se prefiere un jugador en modo jugable; si no hay ninguno vale cualquiera, aunque
     * este en creativo. Caer en el spawn del mundo es el ultimo recurso: anclarse ahi
     * hace que TODAS las anomalias salgan alrededor del centro del mapa, que es justo
     * lo contrario de lo que se busca.
     */
    private Location pickAnchor(World world) {
        List<Player> fighters = new ArrayList<>();
        for (Player p : world.getPlayers()) {
            if (Fx.isFightable(p)) fighters.add(p);
        }
        List<Player> pool = fighters.isEmpty() ? anyPlayer(world) : fighters;
        if (pool.isEmpty()) return world.getSpawnLocation();
        return pool.get(random.nextInt(pool.size())).getLocation();
    }

    private List<Player> anyPlayer(World world) {
        List<Player> out = new ArrayList<>();
        for (Player p : world.getPlayers()) {
            if (p.isOnline()) out.add(p);
        }
        return out;
    }

    private void attempt(World world, Location anchor, AnomalyType type, int tries, Consumer<Location> callback) {
        if (tries >= settings.searchAttempts()) {
            callback.accept(null);
            return;
        }
        double min = settings.minDistance();
        double max = Math.max(min + 64, settings.maxDistance());
        double angle = random.nextDouble() * Math.PI * 2;
        // Raiz cuadrada para que los puntos se repartan por igual en el area del anillo.
        // Sin esto se amontonan cerca del borde interior, o sea siempre a la distancia minima.
        double dist = Math.sqrt(min * min + random.nextDouble() * (max * max - min * min));
        int x = (int) (anchor.getX() + Math.cos(angle) * dist);
        int z = (int) (anchor.getZ() + Math.sin(angle) * dist);

        double border = world.getWorldBorder().getSize() / 2.0 - 32;
        Location center = world.getWorldBorder().getCenter();
        if (Math.abs(x - center.getX()) > border || Math.abs(z - center.getZ()) > border) {
            attempt(world, anchor, type, tries + 1, callback);
            return;
        }

        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            Location candidate = evaluate(world, x, z, type);
            if (candidate != null) {
                callback.accept(candidate);
            } else {
                attempt(world, anchor, type, tries + 1, callback);
            }
        })).exceptionally(t -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> attempt(world, anchor, type, tries + 1, callback));
            return null;
        });
    }

    /** Devuelve la ubicacion si pasa todos los filtros, o null si hay que probar otra. */
    private Location evaluate(World world, int x, int z, AnomalyType type) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y <= world.getMinHeight() + 1 || y >= world.getMaxHeight() - 8) return null;

        Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);

        if (!terrainSuits(world, x, y, z, type.element())) return null;
        if (!hasHeadroom(world, x, y + 1, z, type.element() == Element.VIENTO ? 6 : 4)) return null;
        if (!isFlatEnough(world, x, y, z, settings.arenaRadius())) return null;

        if (protection.tooCloseToSpawn(loc, settings.minSpawnDistance())) return null;
        if (protection.nearRegion(loc, settings.protectionMargin())) return null;
        if (settings.avoidBases() && protection.looksInhabited(loc, settings.baseScanRadius(), 8)) return null;

        return loc;
    }

    /**
     * El filtro de elemento. Es lo que impide que un jefe de tierra, con caballo y
     * armadura, aparezca en mitad del oceano.
     */
    private boolean terrainSuits(World world, int x, int y, int z, Element element) {
        Block floor = world.getBlockAt(x, y, z);
        double wet = liquidFraction(world, x, y, z, settings.arenaRadius());

        return switch (element) {
            case TIERRA -> {
                if (floor.isLiquid() || !floor.getType().isSolid()) yield false;
                if (floor.getType() == Material.LAVA || floor.getType() == Material.WATER) yield false;
                // Un charco no estorba; media arena bajo el agua si.
                yield wet <= 0.10;
            }
            case AGUA -> {
                // Aqui el agua es el requisito: se quiere superficie de agua o la orilla.
                yield wet >= 0.35;
            }
            case VIENTO -> {
                if (!floor.getType().isSolid()) yield false;
                // En alto y despejado; el nivel del mar no vale.
                yield y >= world.getSeaLevel() + 20 && wet <= 0.20;
            }
        };
    }

    /** Que parte de la arena esta cubierta de liquido, muestreando de 3 en 3 bloques. */
    private double liquidFraction(World world, int x, int y, int z, int radius) {
        int checks = 0;
        int wet = 0;
        for (int dx = -radius; dx <= radius; dx += 3) {
            for (int dz = -radius; dz <= radius; dz += 3) {
                if (dx * dx + dz * dz > radius * radius) continue;
                checks++;
                int h = world.getHighestBlockYAt(x + dx, z + dz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (world.getBlockAt(x + dx, h, z + dz).isLiquid()) wet++;
            }
        }
        return checks == 0 ? 1 : wet / (double) checks;
    }

    private boolean hasHeadroom(World world, int x, int y, int z, int needed) {
        for (int i = 0; i < needed; i++) {
            Material m = world.getBlockAt(x, y + i, z).getType();
            if (m.isSolid()) return false;
            if (m == Material.LAVA) return false;
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
