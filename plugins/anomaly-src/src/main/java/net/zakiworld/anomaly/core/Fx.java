package net.zakiworld.anomaly.core;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Primitivas de animacion compartidas por todas las habilidades: anillos, espirales,
 * haces, ondas y entidades de visualizacion. Todo lo que dibuja pasa por aqui para que
 * una habilidad nueva no tenga que reinventar la trigonometria.
 */
public final class Fx {

    private Fx() {
    }

    // ------------------------------------------------------------------ geometria

    /** Recorre los puntos de una circunferencia horizontal y entrega cada uno. */
    public static void ring(Location center, double radius, int points, Consumer<Location> at) {
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2 * i) / points;
            at.accept(center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius));
        }
    }

    /** Igual que ring pero con un desfase de giro, para anillos que rotan entre ticks. */
    public static void ring(Location center, double radius, int points, double offset, Consumer<Location> at) {
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2 * i) / points + offset;
            at.accept(center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius));
        }
    }

    /** Un arco horizontal centrado en la direccion dir, de amplitud spread radianes. */
    public static void arc(Location center, Vector dir, double radius, double spread, int points, Consumer<Location> at) {
        double base = Math.atan2(dir.getZ(), dir.getX());
        for (int i = 0; i < points; i++) {
            double a = base - spread / 2 + (spread * i) / Math.max(1, points - 1);
            at.accept(center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius));
        }
    }

    /** Espiral vertical ascendente. */
    public static void helix(Location base, double radius, double height, int points, double turns, Consumer<Location> at) {
        for (int i = 0; i < points; i++) {
            double t = (double) i / points;
            double a = Math.PI * 2 * turns * t;
            at.accept(base.clone().add(Math.cos(a) * radius, height * t, Math.sin(a) * radius));
        }
    }

    /** Puntos repartidos sobre la superficie de una esfera (espiral de Fibonacci). */
    public static void sphere(Location center, double radius, int points, Consumer<Location> at) {
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < points; i++) {
            double y = 1 - (i / (double) Math.max(1, points - 1)) * 2;
            double r = Math.sqrt(Math.max(0, 1 - y * y));
            double a = golden * i;
            at.accept(center.clone().add(Math.cos(a) * r * radius, y * radius, Math.sin(a) * r * radius));
        }
    }

    /** Recorre el segmento from-to en pasos de step bloques. */
    public static void beam(Location from, Location to, double step, Consumer<Location> at) {
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) return;
        Vector d = to.toVector().subtract(from.toVector());
        double len = d.length();
        if (len < 1.0E-4) return;
        d.multiply(1 / len);
        for (double t = 0; t <= len; t += step) {
            at.accept(from.clone().add(d.clone().multiply(t)));
        }
    }

    // ---------------------------------------------------------------- telegrafias

    /**
     * Marca redonda en el suelo que avisa de un impacto. Sigue el relieve, asi que
     * se ve igual de bien en una llanura que en una ladera.
     */
    public static void telegraph(World w, Location center, double radius, int rgb) {
        Particle.DustOptions dust = Compat.dust(rgb, 1.4f);
        int points = Math.max(12, (int) (radius * 10));
        ring(center, radius, points, l -> {
            Location g = ground(l, 4);
            Compat.spawn(w, Compat.DUST, g.clone().add(0, 0.12, 0), 1, 0, 0, 0, 0, dust);
        });
        ring(center, radius * 0.55, Math.max(8, points / 2), l -> {
            Location g = ground(l, 4);
            Compat.spawn(w, Compat.DUST, g.clone().add(0, 0.12, 0), 1, 0, 0, 0, 0, dust);
        });
    }

    /** Onda expansiva: un anillo que crece un poco cada tick. */
    public static void shockwave(World w, Location center, double radius, Particle p, int density) {
        int points = Math.max(10, (int) (radius * density));
        ring(center, radius, points, l -> {
            Location g = ground(l, 3);
            Compat.spawn(w, p, g.clone().add(0, 0.2, 0), 1, 0.05, 0.05, 0.05, 0.01);
        });
    }

    /** Baja desde loc buscando el primer bloque solido; si no lo encuentra devuelve loc. */
    public static Location ground(Location loc, int maxDown) {
        World w = loc.getWorld();
        if (w == null) return loc;
        Location probe = loc.clone();
        for (int i = 0; i <= maxDown; i++) {
            Block b = probe.clone().subtract(0, i, 0).getBlock();
            if (b.getType().isSolid()) {
                return probe.clone().subtract(0, i - 1, 0);
            }
        }
        return loc;
    }

    // ------------------------------------------------------- entidades de dibujo

    /**
     * Item flotante puramente visual. Se marca como entidad del plugin para que el
     * barredor pueda matarla si una habilidad se cae a mitad.
     */
    public static ItemDisplay itemDisplay(World w, Location loc, ItemStack stack, float scale) {
        ItemDisplay d = w.spawn(loc, ItemDisplay.class, e -> {
            e.setItemStack(stack);
            e.setBillboard(Display.Billboard.FIXED);
            e.setViewRange(2.5f);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setPersistent(false);
            e.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new AxisAngle4f(0, 0, 0, 1),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f(0, 0, 0, 1)));
        });
        Tags.markTemporary(d);
        return d;
    }

    public static BlockDisplay blockDisplay(World w, Location loc, Material mat, float scale) {
        BlockDisplay d = w.spawn(loc, BlockDisplay.class, e -> {
            e.setBlock(mat.createBlockData());
            e.setViewRange(2.5f);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setPersistent(false);
            e.setTransformation(new Transformation(
                    new Vector3f(-scale / 2, -scale / 2, -scale / 2),
                    new AxisAngle4f(0, 0, 0, 1),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f(0, 0, 0, 1)));
        });
        Tags.markTemporary(d);
        return d;
    }

    public static TextDisplay textDisplay(World w, Location loc, Component text) {
        TextDisplay d = w.spawn(loc, TextDisplay.class, e -> {
            e.text(text);
            e.setBillboard(Display.Billboard.CENTER);
            e.setViewRange(3.0f);
            e.setSeeThrough(true);
            e.setPersistent(false);
            e.setBrightness(new Display.Brightness(15, 15));
        });
        Tags.markTemporary(d);
        return d;
    }

    /** Gira una entidad de dibujo sobre su eje Y con interpolacion suave. */
    public static void spin(Display d, float yaw, float scale, int interpolation) {
        Transformation t = new Transformation(
                d.getTransformation().getTranslation(),
                new AxisAngle4f(yaw, 0, 1, 0),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f(0, 0, 0, 1));
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(interpolation);
        d.setTransformation(t);
    }

    /** Inclina un item para que se vea como una lanza apuntando en una direccion. */
    public static void aim(Display d, Vector dir, float scale, int interpolation) {
        Vector n = dir.clone();
        if (n.lengthSquared() < 1.0E-6) n = new Vector(0, 1, 0);
        n.normalize();
        Vector3f up = new Vector3f(0, 1, 0);
        Vector3f target = new Vector3f((float) n.getX(), (float) n.getY(), (float) n.getZ());
        Vector3f axis = new Vector3f(up).cross(target);
        float dot = up.dot(target);
        float angle = (float) Math.acos(Math.max(-1, Math.min(1, dot)));
        if (axis.lengthSquared() < 1.0E-6) {
            axis.set(1, 0, 0);
            angle = dot > 0 ? 0 : (float) Math.PI;
        } else {
            axis.normalize();
        }
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(interpolation);
        d.setTransformation(new Transformation(
                d.getTransformation().getTranslation(),
                new AxisAngle4f(angle, axis.x, axis.y, axis.z),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f(0, 0, 0, 1)));
    }

    // ------------------------------------------------------------------ objetivos

    /** Jugadores vivos y en modo jugable dentro del radio. */
    public static List<Player> playersNear(Location center, double radius) {
        List<Player> out = new ArrayList<>();
        World w = center.getWorld();
        if (w == null) return out;
        double sq = radius * radius;
        for (Player p : w.getPlayers()) {
            if (!isFightable(p)) continue;
            if (p.getLocation().distanceSquared(center) <= sq) out.add(p);
        }
        return out;
    }

    public static boolean isFightable(Player p) {
        if (p == null || !p.isOnline() || p.isDead()) return false;
        return switch (p.getGameMode()) {
            case SURVIVAL, ADVENTURE -> true;
            default -> false;
        };
    }

    public static Player nearest(Location center, double radius) {
        Player best = null;
        double bestSq = radius * radius;
        World w = center.getWorld();
        if (w == null) return null;
        for (Player p : w.getPlayers()) {
            if (!isFightable(p)) continue;
            double d = p.getLocation().distanceSquared(center);
            if (d <= bestSq) {
                bestSq = d;
                best = p;
            }
        }
        return best;
    }

    public static Player farthest(Location center, double radius) {
        Player best = null;
        double bestSq = -1;
        for (Player p : playersNear(center, radius)) {
            double d = p.getLocation().distanceSquared(center);
            if (d > bestSq) {
                bestSq = d;
                best = p;
            }
        }
        return best;
    }

    // ----------------------------------------------------------------- utilidades

    public static Location eye(LivingEntity e) {
        return e.getEyeLocation();
    }

    public static void safeRemove(Entity e) {
        if (e != null && e.isValid()) {
            try {
                e.remove();
            } catch (Throwable ignored) {
            }
        }
    }

    public static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }
}
