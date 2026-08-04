/*
 * Decompiled with CFR 0.152.
 */
package net.zakiworld.rip;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.zakiworld.rip.Clones;
import net.zakiworld.rip.Compat;
import net.zakiworld.rip.RipEffect;
import net.zakiworld.rip.RipPlugin;
import org.bukkit.Bukkit;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.PufferFish;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class EffectRunner {
    private static final int MAX_TICKS = 400;
    private static final long FX_TTL_MS = 30000L;
    private final RipPlugin plugin;
    private final NamespacedKey fxKey;
    private final Set<BukkitRunnable> active = Collections.synchronizedSet(new HashSet());
    private final Set<Entity> fxEntities = Collections.synchronizedSet(new HashSet());
    private final Map<Entity, Long> fxBorn = new ConcurrentHashMap<Entity, Long>();

    public EffectRunner(RipPlugin plugin) {
        this.plugin = plugin;
        this.fxKey = new NamespacedKey((Plugin)plugin, "fx");
        Bukkit.getScheduler().runTaskTimer((Plugin)plugin, this::sweep, 100L, 100L);
    }

    /*
     * Red de seguridad: ninguna entidad de efecto sobrevive a FX_TTL_MS, pase lo
     * que pase con la animacion que la creo.
     */
    private void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Entity, Long> entry : this.fxBorn.entrySet()) {
            if (now - entry.getValue() < FX_TTL_MS) continue;
            Entity e = entry.getKey();
            this.fxBorn.remove(e);
            this.fxEntities.remove(e);
            try {
                e.remove();
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void cancelAll() {
        HashSet<Entity> ents;
        HashSet<BukkitRunnable> tasks;
        Set<BukkitRunnable> set = this.active;
        Set<BukkitRunnable> set2 = set;
        synchronized (set2) {
            tasks = new HashSet<BukkitRunnable>(this.active);
            this.active.clear();
        }
        for (BukkitRunnable t : tasks) {
            try {
                t.cancel();
            }
            catch (Throwable throwable) {}
        }
        Set<Entity> set22 = this.fxEntities;
        Set<Entity> set3 = set22;
        synchronized (set3) {
            ents = new HashSet<Entity>(this.fxEntities);
            this.fxEntities.clear();
        }
        this.fxBorn.clear();
        for (Entity e : ents) {
            try {
                e.remove();
            }
            catch (Throwable throwable) {}
        }
    }

    public int activeCount() {
        return this.active.size();
    }

    public boolean isFxEntity(Entity e) {
        return e != null && e.getPersistentDataContainer().has(this.fxKey, PersistentDataType.BYTE);
    }

    private void tag(Entity e) {
        e.getPersistentDataContainer().set(this.fxKey, PersistentDataType.BYTE, Byte.valueOf((byte)1));
        e.setPersistent(false);
        this.fxEntities.add(e);
        this.fxBorn.put(e, System.currentTimeMillis());
    }

    private void discard(Entity e) {
        if (e == null) {
            return;
        }
        this.fxEntities.remove(e);
        this.fxBorn.remove(e);
        try {
            e.remove();
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private void animate(int ticks, TickAction action) {
        this.animate(ticks, 1L, action, null);
    }

    private void animate(int ticks, long period, final TickAction action, final Runnable onEnd) {
        final int limit = Math.min(ticks, MAX_TICKS);
        BukkitRunnable task = new BukkitRunnable(){
            int t = 0;
            int errors = 0;

            private void finish() {
                EffectRunner.this.stop(this);
                if (onEnd != null) {
                    try {
                        onEnd.run();
                    }
                    catch (Throwable ex) {
                        EffectRunner.this.plugin.getLogger().log(Level.WARNING, "Fallo en el cierre de una animacion", ex);
                    }
                }
            }

            public void run() {
                if (this.t >= limit) {
                    this.finish();
                    return;
                }
                int current = this.t++;
                try {
                    action.accept(current);
                }
                catch (Throwable ex) {
                    if (++this.errors == 1) {
                        EffectRunner.this.plugin.getLogger().log(Level.WARNING, "Error en el tick " + current + " de una animacion; la animacion continua", ex);
                    }
                    if (this.errors >= 5) {
                        EffectRunner.this.plugin.getLogger().warning("Animacion cancelada tras 5 errores; se limpian sus entidades");
                        this.finish();
                    }
                }
            }
        };
        this.active.add(task);
        task.runTaskTimer((Plugin)this.plugin, 0L, period);
    }

    private void later(long delay, final Runnable action) {
        BukkitRunnable task = new BukkitRunnable(){

            public void run() {
                EffectRunner.this.stop(this);
                try {
                    action.run();
                }
                catch (Throwable ex) {
                    EffectRunner.this.plugin.getLogger().log(Level.WARNING, "Fallo en una tarea diferida de efecto", ex);
                }
            }
        };
        this.active.add(task);
        task.runTaskLater((Plugin)this.plugin, Math.max(0L, delay));
    }

    private void stop(BukkitRunnable task) {
        this.active.remove(task);
        try {
            task.cancel();
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private void circle(Location center, double radius, int points, Consumer<Location> spawner) {
        int n = Math.max(1, Math.min(points, 64));
        for (int i = 0; i < n; ++i) {
            double angle = Math.PI * 2 / (double)n * (double)i;
            spawner.accept(center.clone().add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius));
        }
    }

    private void line(Location from, Location to, double step, Consumer<Location> spawner) {
        Vector dir = to.toVector().subtract(from.toVector());
        double len = dir.length();
        if (len < 0.01) {
            return;
        }
        dir.normalize();
        for (double d = 0.0; d <= len; d += step) {
            spawner.accept(from.clone().add(dir.clone().multiply(d)));
        }
    }

    private void firework(World w, Location loc, FireworkEffect.Type type, org.bukkit.Color ... colors) {
        try {
            Firework fw = (Firework)w.spawn(loc, Firework.class, f -> {
                FireworkMeta meta = f.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder().with(type).withColor(colors).withFade(org.bukkit.Color.WHITE).withTrail().withFlicker().build());
                meta.setPower(0);
                f.setFireworkMeta(meta);
            });
            this.tag((Entity)fw);
            this.later(1L, () -> {
                try {
                    if (fw.isValid()) {
                        fw.detonate();
                    }
                }
                catch (Throwable throwable) {
                    // empty catch block
                }
                this.fxEntities.remove(fw);
            });
        }
        catch (Throwable ex) {
            this.plugin.getLogger().log(Level.WARNING, "No se pudo generar el cohete", ex);
        }
    }

    private static org.bukkit.Color randomBright() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        float hue = r.nextFloat();
        Color c = Color.getHSBColor(hue, 0.85f, 1.0f);
        return org.bukkit.Color.fromRGB((int)c.getRed(), (int)c.getGreen(), (int)c.getBlue());
    }

    private static double rnd(double min, double max) {
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    private ItemDisplay fallingItem(World w, Location ground, ItemStack item, float scale, float yawRad, float tiltRad, double fromY, double restY, int fallTicks) {
        try {
            ItemDisplay display = (ItemDisplay)w.spawn(ground, ItemDisplay.class, d -> {
                d.setItemStack(item);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setBillboard(Display.Billboard.FIXED);
                d.setShadowRadius(0.0f);
                d.setBrightness(new Display.Brightness(15, 15));
                Quaternionf rot = new Quaternionf().rotateY(yawRad).rotateZ((float)Math.toRadians(-135.0) + tiltRad);
                d.setTransformation(new Transformation(new Vector3f(0.0f, (float)fromY, 0.0f), rot, new Vector3f(scale, scale, scale), new Quaternionf()));
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(0);
            });
            this.tag((Entity)display);
            this.later(2L, () -> {
                if (!display.isValid()) {
                    return;
                }
                Transformation t = display.getTransformation();
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(fallTicks);
                display.setTransformation(new Transformation(new Vector3f(0.0f, (float)restY, 0.0f), t.getLeftRotation(), t.getScale(), t.getRightRotation()));
            });
            return display;
        }
        catch (Throwable ex) {
            return null;
        }
    }

    private static Location ringPoint(Location base, int index, int count, double radius) {
        double a = Math.PI * 2 / (double)Math.max(1, count) * (double)index;
        return base.clone().add(Math.cos(a) * radius, 0.0, Math.sin(a) * radius);
    }

    private void flashAway(World w, ItemDisplay display) {
        if (display == null) {
            return;
        }
        Location at = null;
        float scale = 1.0f;
        try {
            if (display.isValid()) {
                Transformation tr = display.getTransformation();
                at = display.getLocation().clone().add(0.0, (double)tr.getTranslation().y, 0.0);
                scale = tr.getScale().x;
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        if (at != null) {
            boolean big = scale > 3.0f;
            Compat.spawn(w, Compat.FLASH, at, 1);
            Compat.spawn(w, Compat.END_ROD, at, big ? 40 : 14, big ? 0.5 : 0.18, big ? 0.7 : 0.3, big ? 0.5 : 0.18, big ? 0.12 : 0.05);
            Compat.spawn(w, Compat.ELECTRIC_SPARK, at, big ? 26 : 9, big ? 0.4 : 0.2, big ? 0.6 : 0.3, big ? 0.4 : 0.2, big ? 0.15 : 0.08);
            Compat.sound(w, at, "block.amethyst_block.chime", big ? 1.2f : 0.6f, big ? 0.8f : (float)EffectRunner.rnd(1.3, 1.8));
            if (big) {
                Compat.sound(w, at, "entity.illusioner.mirror_move", 1.0f, 0.7f);
            }
        }
        this.discard((Entity)display);
    }

    private void shrinkAndDiscard(ItemDisplay display, int ticks) {
        if (display == null || !display.isValid()) {
            this.discard((Entity)display);
            return;
        }
        try {
            Transformation t = display.getTransformation();
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(ticks);
            display.setTransformation(new Transformation(t.getTranslation(), t.getLeftRotation(), new Vector3f(0.0f, 0.0f, 0.0f), t.getRightRotation()));
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        this.later((long)ticks + 1L, () -> this.discard((Entity)display));
    }

    public void play(RipEffect effect, Location loc, Player killer, Player victim) {
        if (effect == null || loc == null || loc.getWorld() == null) {
            return;
        }
        World w = loc.getWorld();
        Location base = loc.clone();
        Location c = base.clone().add(0.0, 1.0, 0.0);
        switch (effect) {
            case K_BLOOD: {
                this.kBlood(w, c);
                break;
            }
            case K_HEARTS: {
                this.kHearts(w, c);
                break;
            }
            case K_SPARK: {
                this.kSpark(w, c);
                break;
            }
            case K_COINS: {
                this.kCoins(w, c);
                break;
            }
            case K_POOF: {
                this.kPoof(w, c);
                break;
            }
            case K_FIREWORK: {
                this.kFirework(w, c);
                break;
            }
            case K_NOTES: {
                this.kNotes(w, c);
                break;
            }
            case K_SLASH: {
                this.kSlash(w, c);
                break;
            }
            case K_CONFETTI: {
                this.kConfetti(w, c);
                break;
            }
            case K_PIERCE: {
                this.kPierce(w, c, killer);
                break;
            }
            case K_EXPLOSION: {
                this.kExplosion(w, c);
                break;
            }
            case K_FROST: {
                this.kFrost(w, c);
                break;
            }
            case K_INFERNO: {
                this.kInferno(w, c);
                break;
            }
            case K_SHOCKWAVE: {
                this.kShockwave(w, base);
                break;
            }
            case K_METEOR: {
                this.kMeteor(w, base, c);
                break;
            }
            case K_GEYSER: {
                this.kGeyser(w, base, c);
                break;
            }
            case K_SOULS: {
                this.kSouls(w, c, killer);
                break;
            }
            case K_DRAGON: {
                this.kDragon(w, c);
                break;
            }
            case K_TOTEM: {
                this.kTotem(w, c);
                break;
            }
            case K_CHAINS: {
                this.kChains(w, base, c);
                break;
            }
            case K_ECLIPSE: {
                this.kEclipse(w, base, c);
                break;
            }
            case K_LIGHTNING: {
                this.kLightning(w, base, c);
                break;
            }
            case K_BLACKHOLE: {
                this.kBlackhole(w, c);
                break;
            }
            case K_LASER: {
                this.kLaser(w, base, c, killer);
                break;
            }
            case K_SWORDFALL: {
                this.kSwordfall(w, base, c, victim);
                break;
            }
            case K_BLASTOFF: {
                this.kBlastoff(w, base, c, victim);
                break;
            }
            case K_ORBITAL: {
                this.kOrbital(w, base, c);
                break;
            }
            case D_SMOKE: {
                this.dSmoke(w, c);
                break;
            }
            case D_BONES: {
                this.dBones(w, c);
                break;
            }
            case D_BLOODPOOL: {
                this.dBloodpool(w, base);
                break;
            }
            case D_SPLAT: {
                this.dSplat(w, c);
                break;
            }
            case D_DUST: {
                this.dDust(w, base, c);
                break;
            }
            case D_FIREWORK: {
                this.dFirework(w, c);
                break;
            }
            case D_CHERRY: {
                this.dCherry(w, c);
                break;
            }
            case D_RAIN: {
                this.dRain(w, c);
                break;
            }
            case D_BALLOONS: {
                this.dBalloons(w, c);
                break;
            }
            case D_SPORES: {
                this.dSpores(w, c);
                break;
            }
            case D_GRAVE: {
                this.dGrave(w, base, c);
                break;
            }
            case D_SHATTER: {
                this.dShatter(w, c);
                break;
            }
            case D_VOID: {
                this.dVoid(w, c);
                break;
            }
            case D_ENDER: {
                this.dEnder(w, c);
                break;
            }
            case D_STATUE: {
                this.dStatue(w, c);
                break;
            }
            case D_WHIRLPOOL: {
                this.dWhirlpool(w, base, c);
                break;
            }
            case D_ANGEL: {
                this.dAngel(w, base, c);
                break;
            }
            case D_SOULS: {
                this.dSouls(w, base, c);
                break;
            }
            case D_GLITCH: {
                this.dGlitch(w, c);
                break;
            }
            case D_PHOENIX: {
                this.dPhoenix(w, base, c);
                break;
            }
            case D_SPECTERS: {
                this.dSpecters(w, c);
                break;
            }
            case D_IMPLOSION: {
                this.dImplosion(w, c);
                break;
            }
            case D_SUPERNOVA: {
                this.dSupernova(w, base, c);
                break;
            }
            case D_LIGHTNING: {
                this.dLightning(w, base, c);
                break;
            }
            case D_PUFFERFISH: {
                this.dPufferfish(w, c);
                break;
            }
            case D_SIXTYSEVEN: {
                this.dSixtySeven(w, base, c);
                break;
            }
            case D_REQUIEM: {
                this.dRequiem(w, base, c);
                break;
            }
            case D_AGONY: {
                this.dAgony(w, base, c, victim);
            }
        }
    }

    private void kBlood(World w, Location c) {
        Compat.spawn(w, Compat.BLOCK, c, 60, 0.4, 0.6, 0.4, 0.1, Material.REDSTONE_BLOCK.createBlockData());
        Compat.spawn(w, Compat.DUST, c, 45, 0.5, 0.7, 0.5, 0.0, new Particle.DustOptions(org.bukkit.Color.fromRGB((int)140, (int)0, (int)0), 1.6f));
        Compat.sound(w, c, "entity.player.attack.crit", 1.0f, 0.7f);
    }

    private void kHearts(World w, Location c) {
        this.animate(12, t -> {
            Compat.spawn(w, Compat.HEART, c.clone().add(0.0, (double)t * 0.12, 0.0), 3, 0.5, 0.2, 0.5, 0.0);
            if (t == 0) {
                Compat.sound(w, c, "entity.villager.yes", 1.0f, 1.4f);
            }
        });
    }

    private void kSpark(World w, Location c) {
        this.animate(12, t -> {
            this.circle(c, 1.2 + Math.sin((double)t * 0.4) * 0.3, 12, p -> Compat.spawn(w, Compat.ELECTRIC_SPARK, p, 2, 0.05, 0.05, 0.05, 0.02));
            if (t == 0) {
                Compat.sound(w, c, "entity.lightning_bolt.impact", 0.5f, 1.9f);
            }
        });
    }

    private void kCoins(World w, Location c) {
        Compat.spawn(w, Compat.ITEM, c, 40, 0.4, 0.6, 0.4, 0.25, new ItemStack(Material.GOLD_NUGGET));
        Compat.sound(w, c, "entity.experience_orb.pickup", 1.0f, 0.7f);
    }

    private void kPoof(World w, Location c) {
        Compat.spawn(w, Compat.POOF, c, 20, 0.35, 0.5, 0.35, 0.05);
        Compat.spawn(w, Compat.CLOUD, c, 12, 0.3, 0.4, 0.3, 0.02);
        Compat.sound(w, c, "block.snow.break", 1.0f, 0.7f);
    }

    private void kFirework(World w, Location c) {
        this.firework(w, c, FireworkEffect.Type.BALL_LARGE, org.bukkit.Color.RED, org.bukkit.Color.ORANGE);
        Compat.sound(w, c, "entity.firework_rocket.launch", 1.0f, 1.2f);
    }

    private void kNotes(World w, Location c) {
        this.animate(18, t -> {
            Compat.spawn(w, Compat.NOTE, c.clone().add(0.0, 0.5 + (double)t * 0.08, 0.0), 2, 0.6, 0.2, 0.6, 1.0);
            if (t % 4 == 0) {
                Compat.sound(w, c, "block.note_block.bit", 1.0f, 0.6f + (float)(t % 12) * 0.1f);
            }
        });
    }

    private void kSlash(World w, Location c) {
        this.animate(16, t -> {
            Location p = c.clone().add(Math.cos((double)t * 0.8) * 0.8, (double)t * 0.08, Math.sin((double)t * 0.8) * 0.8);
            Compat.spawn(w, Compat.SWEEP_ATTACK, p, 1);
            Compat.spawn(w, Compat.CRIT, p, 5, 0.2, 0.2, 0.2, 0.15);
            if (t % 4 == 0) {
                Compat.sound(w, c, "entity.player.attack.sweep", 1.0f, 1.0f + (float)t * 0.03f);
            }
        });
    }

    private void kConfetti(World w, Location c) {
        this.animate(20, t -> {
            for (int i = 0; i < 6; ++i) {
                Compat.spawn(w, Compat.DUST, c.clone().add(EffectRunner.rnd(-0.7, 0.7), EffectRunner.rnd(0.0, 1.4), EffectRunner.rnd(-0.7, 0.7)), 1, 0.0, 0.0, 0.0, 0.0, new Particle.DustOptions(EffectRunner.randomBright(), 1.1f));
            }
            Compat.spawn(w, Compat.FIREWORK, c, 2, 0.5, 0.6, 0.5, 0.05);
            if (t == 0) {
                Compat.sound(w, c, "entity.firework_rocket.twinkle", 1.0f, 1.4f);
            }
            if (t == 10) {
                Compat.sound(w, c, "block.note_block.chime", 0.9f, 1.8f);
            }
        });
    }

    private void kPierce(World w, Location c, Player killer) {
        Location from = killer != null && killer.isOnline() && killer.getWorld().equals((Object)w) ? killer.getEyeLocation() : c.clone().add(2.5, 0.5, 0.0);
        this.animate(10, t -> {
            if (t == 0) {
                this.line(from, c, 0.35, p -> {
                    Compat.spawn(w, Compat.CRIT, p, 2, 0.03, 0.03, 0.03, 0.01);
                    Compat.spawn(w, Compat.END_ROD, p, 1, 0.0, 0.0, 0.0, 0.005);
                });
                Compat.sound(w, c, "item.trident.hit", 1.0f, 1.2f);
            }
            if (t == 4) {
                Compat.spawn(w, Compat.SWEEP_ATTACK, c, 2, 0.2, 0.2, 0.2, 0.0);
                Compat.sound(w, c, "entity.arrow.hit_player", 1.0f, 0.8f);
            }
        });
    }

    private void kExplosion(World w, Location c) {
        this.animate(28, t -> {
            if (t < 10) {
                Compat.spawn(w, Compat.SMOKE, c.clone().add(0.0, 0.8, 0.0), 3, 0.1, 0.2, 0.1, 0.01);
                Compat.spawn(w, Compat.FLAME, c.clone().add(0.0, 1.0, 0.0), 1, 0.05, 0.05, 0.05, 0.01);
                if (t == 0) {
                    Compat.sound(w, c, "entity.tnt.primed", 1.0f, 1.0f);
                }
            } else if (t == 10) {
                Compat.spawn(w, Compat.EXPLOSION_EMITTER, c, 1);
                Compat.spawn(w, Compat.CLOUD, c, 30, 0.6, 0.4, 0.6, 0.15);
                Compat.sound(w, c, "entity.generic.explode", 1.0f, 0.9f);
            } else if (t == 20) {
                Compat.spawn(w, Compat.LARGE_SMOKE, c, 18, 0.7, 0.5, 0.7, 0.02);
                Compat.sound(w, c, "block.fire.extinguish", 0.7f, 0.6f);
            }
        });
    }

    private void kFrost(World w, Location c) {
        this.animate(30, t -> {
            if (t < 18) {
                Compat.spawn(w, Compat.SNOWFLAKE, c, 6, 0.4, 0.7, 0.4, 0.02);
                Compat.spawn(w, Compat.BLOCK, c, 5, 0.4, 0.7, 0.4, 0.0, Material.BLUE_ICE.createBlockData());
                if (t == 0) {
                    Compat.sound(w, c, "entity.player.hurt_freeze", 1.0f, 0.8f);
                }
                if (t == 9) {
                    Compat.sound(w, c, "block.powder_snow.step", 1.0f, 0.6f);
                }
            } else if (t == 18) {
                Compat.spawn(w, Compat.BLOCK, c, 50, 0.4, 0.8, 0.4, 0.15, Material.ICE.createBlockData());
                Compat.sound(w, c, "block.glass.break", 1.0f, 0.7f);
                Compat.sound(w, c, "block.amethyst_block.break", 0.8f, 1.6f);
            }
        });
    }

    private void kInferno(World w, Location c) {
        this.animate(32, t -> {
            if (t < 20) {
                Compat.spawn(w, Compat.FLAME, c.clone().add(0.0, (double)t * 0.12, 0.0), 8, 0.3, 0.1, 0.3, 0.02);
                Compat.spawn(w, Compat.LAVA, c, 1, 0.4, 0.3, 0.4, 0.0);
                if (t == 0) {
                    Compat.sound(w, c, "item.firecharge.use", 1.0f, 0.8f);
                }
                if (t == 10) {
                    Compat.sound(w, c, "block.fire.ambient", 1.0f, 0.7f);
                }
            } else if (t == 20) {
                this.circle(c.clone().add(0.0, 0.1, 0.0), 1.6, 20, p -> Compat.spawn(w, Compat.FLAME, p, 3, 0.05, 0.15, 0.05, 0.02));
                Compat.sound(w, c, "entity.blaze.shoot", 1.0f, 0.7f);
            }
        });
    }

    private void kShockwave(World w, Location base) {
        this.animate(24, t -> {
            int wave = t < 12 ? t : t - 12;
            double r = 0.3 + (double)wave * 0.35;
            this.circle(base.clone().add(0.0, 0.1, 0.0), r, (int)(8.0 + r * 5.0), p -> Compat.spawn(w, t < 12 ? Compat.CLOUD : Compat.CRIT, p, 1));
            if (t == 0) {
                Compat.sound(w, base, "block.anvil.land", 1.0f, 0.6f);
            }
            if (t == 12) {
                Compat.sound(w, base, "entity.generic.explode", 0.5f, 1.6f);
            }
        });
    }

    private void kMeteor(World w, Location base, Location c) {
        Vector fall = new Vector(-0.55, -1.0, 0.35).normalize();
        Location start = c.clone().subtract(fall.clone().multiply(14.0));
        this.animate(38, t -> {
            if (t < 18) {
                Location p2 = start.clone().add(fall.clone().multiply((double)t * 0.7777777777777778));
                Compat.spawn(w, Compat.FLAME, p2, 8, 0.25, 0.25, 0.25, 0.02);
                Compat.spawn(w, Compat.LAVA, p2, 2, 0.2, 0.2, 0.2, 0.0);
                Compat.spawn(w, Compat.LARGE_SMOKE, p2, 2, 0.15, 0.15, 0.15, 0.01);
                if (t == 0) {
                    Compat.sound(w, c, "entity.blaze.shoot", 1.0f, 0.5f);
                }
                if (t == 10) {
                    Compat.sound(w, c, "entity.ghast.shoot", 0.8f, 0.6f);
                }
            } else if (t == 18) {
                Compat.spawn(w, Compat.EXPLOSION_EMITTER, c, 1);
                Compat.spawn(w, Compat.LAVA, c, 25, 0.6, 0.3, 0.6, 0.0);
                this.circle(base.clone().add(0.0, 0.1, 0.0), 1.8, 22, p -> Compat.spawn(w, Compat.FLAME, p, 2, 0.05, 0.1, 0.05, 0.02));
                Compat.sound(w, c, "entity.generic.explode", 1.0f, 0.7f);
                Compat.sound(w, c, "block.fire.ambient", 1.0f, 0.5f);
            }
        });
    }

    private void kGeyser(World w, Location base, Location c) {
        this.animate(34, t -> {
            if (t == 0) {
                Compat.sound(w, base, "block.pointed_dripstone.drip_water_into_cauldron", 1.0f, 0.6f);
            }
            if (t < 26) {
                double h = Math.min(3.0, (double)t * 0.25);
                Compat.spawn(w, Compat.SPLASH, base.clone().add(0.0, h, 0.0), 10, 0.25, 0.3, 0.25, 0.1);
                Compat.spawn(w, Compat.BUBBLE_POP, base.clone().add(0.0, h * 0.5, 0.0), 4, 0.2, 0.4, 0.2, 0.02);
                Compat.spawn(w, Compat.CLOUD, base.clone().add(0.0, h, 0.0), 2, 0.15, 0.2, 0.15, 0.03);
                if (t == 4) {
                    Compat.sound(w, base, "entity.dolphin.splash", 1.0f, 0.8f);
                }
                if (t == 14) {
                    Compat.sound(w, base, "entity.generic.splash", 1.0f, 0.7f);
                }
            } else if (t == 26) {
                Compat.spawn(w, Compat.SPLASH, c.clone().add(0.0, 1.5, 0.0), 30, 0.6, 0.4, 0.6, 0.2);
                Compat.sound(w, base, "entity.player.splash.high_speed", 1.0f, 0.9f);
            }
        });
    }

    private void kSouls(World w, Location c, Player killer) {
        this.animate(48, t -> {
            if (t < 30) {
                double y = (double)t * 0.12;
                this.circle(c.clone().add(0.0, y, 0.0), Math.max(0.15, 0.9 - y * 0.18), 8, p -> Compat.spawn(w, Compat.SOUL, p, 1, 0.0, 0.0, 0.0, 0.01));
                Compat.spawn(w, Compat.SOUL_FIRE_FLAME, c, 2, 0.3, 0.5, 0.3, 0.02);
                if (t == 0) {
                    Compat.sound(w, c, "particle.soul_escape", 1.5f, 0.8f);
                }
                if (t == 12) {
                    Compat.sound(w, c, "block.soul_soil.place", 1.0f, 0.5f);
                }
            } else if (t == 30) {
                Compat.sound(w, c, "particle.soul_escape", 1.6f, 0.5f);
                if (killer != null && killer.isOnline() && killer.getWorld().equals((Object)w)) {
                    this.line(c.clone().add(0.0, 3.2, 0.0), killer.getEyeLocation(), 0.4, p -> Compat.spawn(w, Compat.SOUL, p, 1, 0.05, 0.05, 0.05, 0.02));
                }
            } else if (t == 40) {
                Compat.sound(w, c, "block.amethyst_block.chime", 0.8f, 0.6f);
            }
        });
    }

    private void kDragon(World w, Location c) {
        this.animate(50, t -> {
            if (t < 34) {
                Compat.spawn(w, Compat.DRAGON_BREATH, c, 10, 0.6, 0.4, 0.6, 0.02);
                double angle = (double)t * 0.5;
                Compat.spawn(w, Compat.PORTAL, c.clone().add(Math.cos(angle) * 1.1, 0.3 + (double)t * 0.05, Math.sin(angle) * 1.1), 3, 0.05, 0.05, 0.05, 0.2);
                if (t == 0) {
                    Compat.sound(w, c, "entity.ender_dragon.growl", 0.7f, 1.4f);
                }
                if (t == 14) {
                    Compat.sound(w, c, "entity.ender_dragon.flap", 1.0f, 1.2f);
                }
            } else if (t == 34) {
                Compat.spawn(w, Compat.DRAGON_BREATH, c, 45, 0.8, 0.6, 0.8, 0.1);
                Compat.spawn(w, Compat.SWEEP_ATTACK, c, 3, 0.4, 0.4, 0.4, 0.0);
                Compat.sound(w, c, "entity.ender_dragon.hurt", 0.7f, 0.8f);
                Compat.sound(w, c, "entity.generic.eat", 1.0f, 0.6f);
            }
        });
    }

    private void kTotem(World w, Location c) {
        this.animate(44, t -> {
            if (t == 0) {
                Compat.spawn(w, Compat.TOTEM, c, 50, 0.4, 0.6, 0.4, 0.4);
                Compat.sound(w, c, "item.totem.use", 0.8f, 1.2f);
            } else if (t < 30) {
                double angle = (double)t * 0.55;
                double y = (double)t * 0.07;
                Compat.spawn(w, Compat.TOTEM, c.clone().add(Math.cos(angle) * 0.8, y, Math.sin(angle) * 0.8), 2, 0.03, 0.03, 0.03, 0.05);
                if (t == 15) {
                    Compat.sound(w, c, "block.note_block.chime", 0.8f, 0.9f);
                }
            } else if (t == 30) {
                Compat.spawn(w, Compat.TOTEM, c.clone().add(0.0, 2.2, 0.0), 40, 0.8, 0.2, 0.8, 0.15);
                Compat.sound(w, c, "entity.item.break", 1.0f, 0.6f);
                Compat.sound(w, c, "entity.villager.no", 1.0f, 0.7f);
            }
        });
    }

    private void kChains(World w, Location base, Location c) {
        Location[] anchors = new Location[4];
        for (int i = 0; i < 4; ++i) {
            double a = 1.5707963267948966 * (double)i + 0.7853981633974483;
            anchors[i] = base.clone().add(Math.cos(a) * 2.4, 0.0, Math.sin(a) * 2.4);
        }
        this.animate(56, t -> {
            if (t == 0) {
                Compat.sound(w, base, "block.chain.place", 1.2f, 0.5f);
                Compat.sound(w, base, "entity.warden.heartbeat", 1.0f, 0.7f);
            }
            if (t < 24) {
                double progress = (double)t / 24.0;
                for (Location anchor : anchors) {
                    Vector dir = c.clone().add(0.0, 0.4, 0.0).toVector().subtract(anchor.toVector()).multiply(progress);
                    Location p2 = anchor.clone().add(dir);
                    p2.add(0.0, Math.sin(progress * Math.PI) * 1.2, 0.0);
                    Compat.spawn(w, Compat.SOUL_FIRE_FLAME, p2, 2, 0.04, 0.04, 0.04, 0.005);
                }
                if (t % 8 == 0) {
                    Compat.sound(w, base, "block.chain.step", 1.0f, 0.6f + (float)t * 0.02f);
                }
            } else if (t < 42) {
                this.circle(c, 0.7 + Math.sin((double)t * 0.5) * 0.15, 10, p -> Compat.spawn(w, Compat.SOUL_FIRE_FLAME, p, 1, 0.02, 0.02, 0.02, 0.003));
                if (t == 30) {
                    Compat.sound(w, base, "entity.warden.heartbeat", 1.2f, 0.6f);
                }
            } else if (t == 42) {
                Compat.spawn(w, Compat.SOUL, c, 25, 0.4, 0.6, 0.4, 0.06);
                Compat.sound(w, base, "block.chain.break", 1.2f, 0.5f);
                Compat.sound(w, base, "particle.soul_escape", 1.4f, 0.6f);
            }
        });
    }

    private void kEclipse(World w, Location base, Location c) {
        this.animate(60, t -> {
            Location sky = c.clone().add(0.0, 3.5, 0.0);
            if (t < 36) {
                double r = Math.max(0.3, 3.0 - (double)t * 0.075);
                this.circle(sky, r, 24, p -> Compat.spawn(w, Compat.SQUID_INK, p, 1, 0.03, 0.03, 0.03, 0.0));
                Compat.spawn(w, Compat.SMOKE, c, 3, 0.6, 0.8, 0.6, 0.01);
                if (t == 0) {
                    Compat.sound(w, c, "block.beacon.deactivate", 1.0f, 0.5f);
                }
                if (t == 18) {
                    Compat.sound(w, c, "entity.wither.ambient", 0.4f, 0.5f);
                }
            } else if (t == 36) {
                Compat.spawn(w, Compat.FLASH, sky, 1);
                this.circle(sky, 1.6, 28, p -> Compat.spawn(w, Compat.END_ROD, p, 1, 0.02, 0.02, 0.02, 0.01));
                Compat.sound(w, c, "block.beacon.activate", 1.0f, 1.6f);
                Compat.sound(w, c, "block.amethyst_block.chime", 1.0f, 0.5f);
            } else if (t == 48) {
                Compat.spawn(w, Compat.END_ROD, c, 20, 0.5, 1.0, 0.5, 0.04);
            }
        });
    }

    private void kLightning(World w, Location base, Location c) {
        Location[] preStrikes = new Location[]{base.clone().add(2.2, 0.0, 1.4), base.clone().add(-1.8, 0.0, 2.0), base.clone().add(-1.2, 0.0, -2.3)};
        this.animate(96, t -> {
            Location sky = c.clone().add(0.0, 5.0, 0.0);
            if (t < 30) {
                this.circle(sky, 2.5 + Math.sin((double)t * 0.3), 20, p -> Compat.spawn(w, Compat.CLOUD, p, 2, 0.2, 0.1, 0.2, 0.0));
                Compat.spawn(w, Compat.ELECTRIC_SPARK, sky, 3, 1.5, 0.3, 1.5, 0.05);
                if (t == 0) {
                    Compat.sound(w, c, "entity.lightning_bolt.thunder", 0.5f, 0.5f);
                }
                if (t == 18) {
                    Compat.sound(w, c, "item.trident.thunder", 0.6f, 0.6f);
                }
            } else if (t >= 30 && t < 66 && (t - 30) % 12 == 0) {
                int i = (t - 30) / 12;
                if (i < preStrikes.length) {
                    w.strikeLightningEffect(preStrikes[i]);
                    Compat.spawn(w, Compat.ELECTRIC_SPARK, preStrikes[i].clone().add(0.0, 0.5, 0.0), 15, 0.3, 0.5, 0.3, 0.1);
                }
            } else if (t == 70) {
                w.strikeLightningEffect(base);
                Compat.spawn(w, Compat.FLASH, c, 1);
                Compat.sound(w, c, "entity.lightning_bolt.impact", 1.0f, 0.8f);
                Compat.sound(w, c, "entity.lightning_bolt.thunder", 1.0f, 1.0f);
            } else if (t == 74) {
                this.circle(base.clone().add(0.0, 0.1, 0.0), 1.4, 18, p -> Compat.spawn(w, Compat.LARGE_SMOKE, p, 2, 0.05, 0.1, 0.05, 0.01));
                Compat.spawn(w, Compat.LAVA, base, 10, 0.6, 0.1, 0.6, 0.0);
                Compat.sound(w, c, "block.fire.extinguish", 1.0f, 0.5f);
            }
        });
    }

    private void kBlackhole(World w, Location c) {
        this.animate(92, t -> {
            if (t < 50) {
                double r = Math.min(2.4, 0.5 + (double)t * 0.05);
                double spin = (double)t * 0.35;
                for (int i = 0; i < 16; ++i) {
                    double a = 0.39269908169872414 * (double)i + spin;
                    Location p2 = c.clone().add(Math.cos(a) * r, Math.sin(a) * r * 0.35, Math.sin(a) * r);
                    Compat.spawn(w, Compat.PORTAL, p2, 1);
                    if (i % 4 != 0) continue;
                    Compat.spawn(w, Compat.SQUID_INK, p2, 1, 0.02, 0.02, 0.02, 0.0);
                }
                Compat.spawn(w, Compat.REVERSE_PORTAL, c, 3, 0.15, 0.2, 0.15, 0.03);
                if (t == 0) {
                    Compat.sound(w, c, "block.portal.trigger", 0.8f, 0.5f);
                }
                if (t == 25) {
                    Compat.sound(w, c, "block.portal.ambient", 0.9f, 0.4f);
                }
            } else if (t < 72) {
                double r = Math.max(0.1, 2.4 - (double)(t - 50) * 0.11);
                for (int i = 0; i < 12; ++i) {
                    double a = 0.5235987755982988 * (double)i + (double)t * 0.4;
                    Location p3 = c.clone().add(Math.cos(a) * r, Math.sin(a * 2.0) * 0.5, Math.sin(a) * r);
                    Vector v = c.toVector().subtract(p3.toVector());
                    if (!(v.lengthSquared() > 1.0E-6)) continue;
                    v.normalize().multiply(0.3);
                    Compat.spawn(w, Compat.REVERSE_PORTAL, p3, 0, v.getX(), v.getY(), v.getZ(), 1.0);
                }
                if (t == 60) {
                    Compat.sound(w, c, "block.beacon.deactivate", 1.0f, 0.4f);
                }
            } else if (t == 72) {
                Compat.spawn(w, Compat.FLASH, c, 1);
                this.circle(c, 1.8, 26, p -> Compat.spawn(w, Compat.END_ROD, p, 1, 0.02, 0.02, 0.02, 0.04));
                Compat.sound(w, c, "entity.enderman.teleport", 1.0f, 0.4f);
                Compat.sound(w, c, "entity.warden.sonic_boom", 0.6f, 0.7f);
            } else if (t == 80) {
                Compat.spawn(w, Compat.PORTAL, c, 30, 0.3, 0.3, 0.3, 0.8);
                Compat.sound(w, c, "entity.enderman.scream", 0.6f, 0.4f);
            }
        });
    }

    private void kLaser(World w, Location base, Location c, Player killer) {
        boolean hasKiller = killer != null && killer.isOnline() && killer.getWorld().equals((Object)w);
        Location origin = hasKiller ? killer.getEyeLocation().clone() : c.clone().add(0.0, 8.0, 0.0);
        this.animate(84, t -> {
            Location from = hasKiller && killer.isOnline() ? killer.getEyeLocation() : origin;
            Location location = from;
            if (t < 30) {
                double r = Math.max(0.15, 1.2 - (double)t * 0.035);
                this.circle(from.clone().add(0.0, 0.2, 0.0), r, 12, p -> Compat.spawn(w, Compat.END_ROD, p, 1, 0.0, 0.0, 0.0, 0.005));
                if (t % 8 == 0) {
                    Compat.sound(w, from, "block.beacon.ambient", 0.9f, 0.8f + (float)t * 0.03f);
                }
                if (t == 24) {
                    Compat.sound(w, from, "block.respawn_anchor.charge", 1.0f, 1.5f);
                }
            } else if (t < 60) {
                this.line(from, c, 0.35, p -> {
                    Compat.spawn(w, Compat.END_ROD, p, 1, 0.0, 0.0, 0.0, 0.002);
                    if (ThreadLocalRandom.current().nextInt(3) == 0) {
                        Compat.spawn(w, Compat.ELECTRIC_SPARK, p, 1, 0.08, 0.08, 0.08, 0.01);
                    }
                });
                Compat.spawn(w, Compat.LAVA, c, 2, 0.3, 0.2, 0.3, 0.0);
                if (t == 30) {
                    Compat.sound(w, c, "entity.guardian.attack", 1.0f, 1.2f);
                }
                if (t % 10 == 0) {
                    Compat.sound(w, c, "block.beacon.deactivate", 0.5f, 1.9f);
                }
            } else if (t == 60) {
                Compat.spawn(w, Compat.EXPLOSION_EMITTER, c, 1);
                this.circle(base.clone().add(0.0, 0.1, 0.0), 1.5, 20, p -> {
                    Compat.spawn(w, Compat.LAVA, p, 2, 0.05, 0.05, 0.05, 0.0);
                    Compat.spawn(w, Compat.FLAME, p, 2, 0.05, 0.1, 0.05, 0.01);
                });
                Compat.sound(w, c, "entity.generic.explode", 1.0f, 0.8f);
                Compat.sound(w, c, "block.lava.extinguish", 1.0f, 0.6f);
            }
        });
    }

    private void kOrbital(World w, Location base, Location c) {
        this.animate(112, t -> {
            if (t < 40) {
                double pulse = 1.6 + Math.sin((double)t * 0.45) * 0.25;
                this.circle(base.clone().add(0.0, 0.1, 0.0), pulse, 24, p -> Compat.spawn(w, Compat.DUST, p, 1, 0.0, 0.0, 0.0, 0.0, new Particle.DustOptions(org.bukkit.Color.fromRGB((int)255, (int)60, (int)60), 1.3f)));
                this.circle(base.clone().add(0.0, 0.1, 0.0), 0.4, 8, p -> Compat.spawn(w, Compat.DUST, p, 1, 0.0, 0.0, 0.0, 0.0, new Particle.DustOptions(org.bukkit.Color.fromRGB((int)255, (int)200, (int)60), 1.0f)));
                if (t % 10 == 0) {
                    Compat.sound(w, base, "block.note_block.bell", 0.8f, 1.7f + (float)t * 0.005f);
                }
            } else if (t == 40) {
                Compat.spawn(w, Compat.FLASH, c.clone().add(0.0, 20.0, 0.0), 1);
                Compat.sound(w, c, "item.trident.thunder", 1.0f, 1.4f);
            } else if (t > 44 && t < 84) {
                double top;
                for (double y = top = Math.max(0.0, 26.0 - (double)(t - 44) * 1.1); y < 26.0; y += 1.3) {
                    Compat.spawn(w, Compat.END_ROD, c.clone().add(0.0, y, 0.0), 2, 0.25, 0.4, 0.25, 0.01);
                }
                this.circle(base.clone().add(0.0, 0.2, 0.0), 1.2, 14, p -> Compat.spawn(w, Compat.FLAME, p, 1, 0.03, 0.1, 0.03, 0.01));
                if (t % 8 == 0) {
                    Compat.sound(w, c, "block.beacon.ambient", 1.0f, 0.5f);
                }
                if (t == 64) {
                    Compat.sound(w, c, "entity.guardian.attack", 1.0f, 0.6f);
                }
            } else if (t == 84) {
                Compat.spawn(w, Compat.EXPLOSION_EMITTER, c, 2);
                Compat.spawn(w, Compat.FLASH, c, 1);
                Compat.spawn(w, Compat.END_ROD, c, 60, 0.4, 0.4, 0.4, 0.45);
                Compat.sound(w, c, "entity.generic.explode", 1.0f, 0.5f);
                Compat.sound(w, c, "entity.warden.sonic_boom", 0.8f, 0.8f);
                Compat.sound(w, c, "entity.wither.death", 0.25f, 0.5f);
            } else if (t == 96) {
                Compat.spawn(w, Compat.LARGE_SMOKE, c, 25, 0.9, 0.6, 0.9, 0.02);
            }
        });
    }

    private void kSwordfall(World w, Location base, Location c, Player victim) {
        ItemStack[] armor = null;
        ItemStack hand = null;
        LivingEntity clone = null;
        if (victim != null) {
            try {
                armor = victim.getInventory().getArmorContents();
                hand = victim.getInventory().getItemInMainHand();
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            clone = Clones.spawnFrozenClone(base, victim, armor, hand);
            if (clone != null) {
                this.tag((Entity)clone);
            }
        }
        LivingEntity frozen = clone;
        Set<ItemDisplay> swords = Collections.synchronizedSet(new LinkedHashSet<ItemDisplay>());
        if (frozen != null) {
            this.circle(base.clone().add(0.0, 0.2, 0.0), 1.0, 14, p -> Compat.spawn(w, Compat.ENCHANT, p, 2, 0.05, 0.3, 0.05, 0.4));
            Compat.sound(w, base, "block.respawn_anchor.charge", 1.0f, 0.6f);
            Compat.sound(w, base, "entity.elder_guardian.curse", 0.5f, 1.4f);
        }
        this.animate(140, 1L, t -> {
            if (t >= 4 && t <= 26 && (t - 4) % 2 == 0) {
                int i = (t - 4) / 2;
                double a = Math.PI * 2 / 12.0 * (double)i;
                Location ground = EffectRunner.ringPoint(base, i, 12, 2.2);
                int fallTicks = 4;
                ItemDisplay sword = this.fallingItem(w, ground, new ItemStack(Material.GOLDEN_SWORD), 1.4f, (float)(-a), 0.0f, 13.0, 0.85, fallTicks);
                if (sword != null) {
                    swords.add(sword);
                    this.later((long)fallTicks + 2L, () -> {
                        Compat.spawn(w, Compat.BLOCK, ground.clone().add(0.0, 0.2, 0.0), 12, 0.15, 0.1, 0.15, 0.05, Material.STONE.createBlockData());
                        Compat.spawn(w, Compat.CRIT, ground.clone().add(0.0, 0.5, 0.0), 6, 0.1, 0.2, 0.1, 0.1);
                        Compat.sound(w, ground, "entity.player.attack.sweep", 1.0f, (float)EffectRunner.rnd(0.7, 1.3));
                        Compat.sound(w, ground, "item.trident.hit_ground", 0.8f, (float)EffectRunner.rnd(0.8, 1.2));
                        Compat.sound(w, ground, "block.bell.use", 0.45f, (float)EffectRunner.rnd(1.4, 1.9));
                    });
                }
            }
            if (t < 40 && t % 4 == 0 && frozen != null && frozen.isValid()) {
                Compat.spawn(w, Compat.ENCHANT, frozen.getLocation().add(0.0, 1.4, 0.0), 4, 0.35, 0.6, 0.35, 0.3);
            }
            if (t == 36) {
                Compat.sound(w, c, "entity.lightning_bolt.thunder", 0.6f, 0.5f);
                Compat.spawn(w, Compat.FLASH, c.clone().add(0.0, 12.0, 0.0), 1);
            }
            if (t == 40) {
                int fallTicks = 8;
                ItemDisplay giant = this.fallingItem(w, base.clone(), new ItemStack(Material.GOLDEN_SWORD), 6.0f, 0.6f, 0.0f, 24.0, 2.6, fallTicks);
                if (giant != null) {
                    swords.add(giant);
                }
                this.animate(fallTicks, tick -> {
                    double y = 24.0 - (double)tick * (21.4 / (double)fallTicks);
                    Compat.spawn(w, Compat.CLOUD, base.clone().add(0.0, y, 0.0), 3, 0.3, 0.5, 0.3, 0.01);
                    Compat.spawn(w, Compat.CRIT, base.clone().add(0.0, y, 0.0), 4, 0.2, 0.4, 0.2, 0.05);
                });
            }
            if (t == 50) {
                Compat.spawn(w, Compat.EXPLOSION_EMITTER, base, 1);
                Compat.spawn(w, Compat.FLASH, c, 1);
                Compat.spawn(w, Compat.BLOCK, base.clone().add(0.0, 0.3, 0.0), 60, 1.2, 0.3, 1.2, 0.1, Material.DEEPSLATE.createBlockData());
                Compat.sound(w, base, "block.anvil.land", 1.0f, 0.5f);
                Compat.sound(w, base, "block.anvil.use", 1.0f, 0.4f);
                Compat.sound(w, base, "entity.lightning_bolt.impact", 1.0f, 0.7f);
                Compat.sound(w, base, "item.mace.smash_ground_heavy", 1.0f, 0.6f);
                Compat.sound(w, base, "block.bell.use", 1.5f, 0.55f);
                this.later(6L, () -> Compat.sound(w, base, "block.bell.resonate", 1.2f, 0.75f));
                this.later(16L, () -> Compat.sound(w, base, "block.bell.resonate", 0.8f, 0.6f));
                if (frozen != null && frozen.isValid()) {
                    Compat.spawn(w, Compat.POOF, frozen.getLocation().add(0.0, 1.0, 0.0), 20, 0.3, 0.6, 0.3, 0.03);
                    Compat.spawn(w, Compat.SOUL, frozen.getLocation().add(0.0, 1.0, 0.0), 12, 0.25, 0.5, 0.25, 0.05);
                    Compat.sound(w, base, "entity.enderman.teleport", 0.8f, 0.6f);
                }
                this.discard((Entity)frozen);
            }
            if (t > 50 && t <= 64) {
                double r = (double)(t - 50) * 0.45;
                this.circle(base.clone().add(0.0, 0.15, 0.0), r, (int)(10.0 + r * 6.0), p -> {
                    Compat.spawn(w, Compat.CLOUD, p, 1);
                    if (t % 2 == 0) {
                        Compat.spawn(w, Compat.CRIT, p, 1, 0.03, 0.05, 0.03, 0.02);
                    }
                });
            }
            if (t == 96) {
                List<ItemDisplay> pending;
                Set set;
                Set set2 = set = swords;
                synchronized (set2) {
                    pending = new ArrayList<ItemDisplay>(swords);
                    swords.clear();
                }
                for (int i = 0; i < pending.size(); ++i) {
                    ItemDisplay sword = pending.get(i);
                    this.later((long)i * 2L, () -> this.flashAway(w, sword));
                }
            }
        }, () -> {
            Set set;
            Set set2 = set = swords;
            synchronized (set2) {
                for (ItemDisplay sword : swords) {
                    this.discard((Entity)sword);
                }
                swords.clear();
            }
            this.discard((Entity)frozen);
        });
    }

    /*
     * Fija la hora del cielo en el cliente de todos los jugadores del mundo.
     * Es puramente visual: no toca la hora real del mundo, asi que no afecta
     * a mobs, granjas ni al dormir. Siempre hay que cerrarlo con skyReset.
     */
    private void sky(World w, long absolute) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().equals(w)) continue;
            try {
                p.setPlayerTime(absolute, false);
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
    }

    private void skyReset() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                p.resetPlayerTime();
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
    }

    /*
     * Enciende (o apaga) un haz de faro DE VERDAD, el mismo que renderiza el
     * cliente para un beacon con su piramide. No se toca el mundo: se manda un
     * cambio de bloque falso solo a los clientes cercanos y, al apagarlo, se
     * les reenvia el bloque real. El faro va en el bloque de debajo de los pies
     * con un 3x3 de hierro bajo el, que es lo minimo para que el haz salga. Si
     * hay techo encima no se vera, igual que en vanilla.
     */
    private void beaconBeam(World w, Location feet, boolean on) {
        Location bc = feet.clone().subtract(0.0, 1.0, 0.0).getBlock().getLocation();
        org.bukkit.block.data.BlockData beacon = Material.BEACON.createBlockData();
        org.bukkit.block.data.BlockData iron = Material.IRON_BLOCK.createBlockData();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().equals(w)) continue;
            try {
                if (p.getLocation().distanceSquared(bc) > 9216.0) continue;
                p.sendBlockChange(bc, on ? beacon : bc.getBlock().getBlockData());
                for (int dx = -1; dx <= 1; ++dx) {
                    for (int dz = -1; dz <= 1; ++dz) {
                        Location b = bc.clone().add((double)dx, -1.0, (double)dz);
                        p.sendBlockChange(b, on ? iron : b.getBlock().getBlockData());
                    }
                }
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
    }

    private void dAgony(World w, Location base, Location c, Player victim) {
        ItemStack[] armor = null;
        ItemStack hand = null;
        LivingEntity clone = null;
        if (victim != null) {
            try {
                armor = victim.getInventory().getArmorContents();
                hand = victim.getInventory().getItemInMainHand();
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            clone = Clones.spawnFrozenClone(base, victim, armor, hand);
        }
        final LivingEntity god = clone;
        if (god != null) {
            this.tag((Entity)god);
        }
        long startTime = w.getTime();
        double rise = 2.4;
        Compat.sound(w, base, "entity.ender_dragon.growl", 1.2f, 0.4f);
        Compat.sound(w, base, "ambient.cave", 1.0f, 0.5f);
        this.animate(170, 1L, t -> {
            if (t < 12) {
                this.circle(base.clone().add(0.0, 0.15, 0.0), 1.6, 18, p -> Compat.spawn(w, Compat.SOUL, p, 1, 0.02, 0.12, 0.02, 0.02));
                Compat.spawn(w, Compat.LARGE_SMOKE, base.clone().add(0.0, 0.4, 0.0), 3, 0.5, 0.15, 0.5, 0.01);
            }
            if (t == 6 && god != null && god.isValid()) {
                try {
                    god.setGlowing(true);
                }
                catch (Throwable throwable) {
                    // empty catch block
                }
                Compat.spawn(w, Compat.FLASH, c, 1);
                Compat.sound(w, base, "block.portal.trigger", 0.5f, 0.6f);
                Compat.sound(w, base, "block.respawn_anchor.charge", 1.0f, 0.5f);
            }
            if (t >= 8 && t <= 70 && god != null && god.isValid()) {
                double p = (double)(t - 8) / 62.0;
                double eased = p * p * (3.0 - 2.0 * p);
                Location at = base.clone().add(0.0, rise * eased, 0.0);
                at.setPitch(0.0f);
                try {
                    god.teleport(at);
                }
                catch (Throwable throwable) {
                    // empty catch block
                }
            }
            /*
             * Un solo cambio de hora: del momento en el que murio a la fase
             * contraria, en 60 ticks (3 s). Como setPlayerTime deja la hora
             * congelada, luego se queda ahi hasta que el destello final lo
             * resetea y tapa el salto de vuelta.
             */
            if (t >= 20 && t <= 80) {
                double p = (double)(t - 20) / 60.0;
                this.sky(w, startTime + (long)(12000.0 * p));
            }
            if (t == 20) {
                Compat.sound(w, base, "entity.illusioner.prepare_mirror", 1.0f, 0.6f);
            }
            if (t > 70 && t < 146 && god != null && god.isValid()) {
                double bob = Math.sin((double)t * 0.09) * 0.14;
                Location at = base.clone().add(0.0, rise + bob, 0.0);
                at.setPitch(0.0f);
                try {
                    god.teleport(at);
                }
                catch (Throwable throwable) {
                    // empty catch block
                }
            }
            if (t == 90) {
                Compat.sound(w, base, "entity.elder_guardian.curse", 0.6f, 0.6f);
                Compat.sound(w, base, "block.enchantment_table.use", 1.0f, 0.5f);
            }
            if (t == 112) {
                this.beaconBeam(w, base, true);
                Compat.sound(w, base, "block.beacon.activate", 1.0f, 0.7f);
                Compat.sound(w, base, "block.conduit.activate", 0.9f, 0.8f);
            }
            if (t == 138) {
                Compat.sound(w, base, "block.beacon.power_select", 1.0f, 0.6f);
            }
            if (t == 146) {
                Compat.spawn(w, Compat.FLASH, base.clone().add(0.0, rise + 1.0, 0.0), 1);
                Compat.spawn(w, Compat.POOF, base.clone().add(0.0, rise + 1.0, 0.0), 40, 0.4, 0.7, 0.4, 0.06);
                Compat.spawn(w, Compat.END_ROD, base.clone().add(0.0, rise + 1.0, 0.0), 50, 0.4, 0.8, 0.4, 0.25);
                Compat.spawn(w, Compat.SOUL, base.clone().add(0.0, rise + 1.0, 0.0), 24, 0.3, 0.6, 0.3, 0.06);
                Compat.sound(w, base, "entity.enderman.teleport", 0.9f, 0.5f);
                Compat.sound(w, base, "block.amethyst_block.chime", 1.0f, 0.6f);
                Compat.sound(w, base, "block.beacon.deactivate", 1.0f, 0.7f);
                this.discard((Entity)god);
                this.beaconBeam(w, base, false);
                this.skyReset();
            }
            if (t == 158) {
                Compat.sound(w, base, "block.bell.resonate", 0.9f, 0.7f);
            }
        }, () -> {
            this.skyReset();
            this.beaconBeam(w, base, false);
            this.discard((Entity)god);
        });
        this.later(200L, this::skyReset);
    }

    private void dSmoke(World w, Location c) {
        this.animate(14, t -> {
            Compat.spawn(w, Compat.CAMPFIRE_SIGNAL, c.clone().add(0.0, (double)t * 0.1, 0.0), 3, 0.3, 0.2, 0.3, 0.01);
            Compat.spawn(w, Compat.LARGE_SMOKE, c, 2, 0.3, 0.4, 0.3, 0.02);
            if (t == 0) {
                Compat.sound(w, c, "block.fire.extinguish", 1.0f, 0.7f);
            }
        });
    }

    private void dBones(World w, Location c) {
        Compat.spawn(w, Compat.ITEM, c, 45, 0.4, 0.7, 0.4, 0.2, new ItemStack(Material.BONE));
        Compat.sound(w, c, "entity.skeleton.death", 1.0f, 0.8f);
    }

    private void dBloodpool(World w, Location base) {
        this.animate(14, t -> {
            double r = 0.3 + (double)t * 0.15;
            this.circle(base.clone().add(0.0, 0.05, 0.0), r, (int)(6.0 + r * 7.0), p -> Compat.spawn(w, Compat.DUST, p, 1, new Particle.DustOptions(org.bukkit.Color.fromRGB((int)120, (int)0, (int)0), 1.8f)));
            if (t == 0) {
                Compat.sound(w, base, "block.honey_block.fall", 1.0f, 0.5f);
            }
        });
    }

    private void dSplat(World w, Location c) {
        Compat.spawn(w, Compat.ITEM, c, 40, 0.4, 0.5, 0.4, 0.2, new ItemStack(Material.SLIME_BALL));
        Compat.spawn(w, Compat.SPLASH, c, 15, 0.4, 0.3, 0.4, 0.1);
        Compat.sound(w, c, "entity.slime.death", 1.0f, 0.7f);
    }

    private void dDust(World w, Location base, Location c) {
        this.animate(14, t -> {
            Compat.spawn(w, Compat.WHITE_ASH, c, 8, 0.4, 0.6, 0.4, 0.01);
            Compat.spawn(w, Compat.ASH, base.clone().add(0.0, 0.2, 0.0), 5, 0.4, 0.1, 0.4, 0.005);
            if (t == 0) {
                Compat.sound(w, c, "block.sand.break", 1.0f, 0.6f);
            }
        });
    }

    private void dFirework(World w, Location c) {
        this.firework(w, c, FireworkEffect.Type.STAR, org.bukkit.Color.AQUA, org.bukkit.Color.FUCHSIA, org.bukkit.Color.YELLOW);
        Compat.sound(w, c, "entity.firework_rocket.launch", 1.0f, 1.0f);
    }

    private void dCherry(World w, Location c) {
        this.animate(22, t -> {
            Compat.spawn(w, Compat.CHERRY_LEAVES, c.clone().add(0.0, 1.5, 0.0), 3, 0.8, 0.3, 0.8, 0.0);
            if (t == 0) {
                Compat.sound(w, c, "block.cherry_leaves.place", 1.5f, 0.8f);
            }
            if (t == 11) {
                Compat.sound(w, c, "block.cherry_wood.place", 1.0f, 1.4f);
            }
        });
    }

    private void dRain(World w, Location c) {
        this.animate(24, t -> {
            Location cloud = c.clone().add(0.0, 2.2, 0.0);
            Compat.spawn(w, Compat.CLOUD, cloud, 2, 0.5, 0.1, 0.5, 0.0);
            Compat.spawn(w, Compat.FALLING_WATER, cloud, 4, 0.6, 0.1, 0.6, 0.0);
            if (t % 12 == 0) {
                Compat.sound(w, c, "weather.rain", 0.6f, 1.0f);
            }
        });
    }

    private void dBalloons(World w, Location c) {
        org.bukkit.Color[] palette = new org.bukkit.Color[6];
        double[] phase = new double[6];
        for (int i = 0; i < 6; ++i) {
            palette[i] = EffectRunner.randomBright();
            phase[i] = EffectRunner.rnd(0.0, Math.PI * 2);
        }
        this.animate(26, t -> {
            for (int i = 0; i < 6; ++i) {
                Location p;
                double a;
                int popAt = 12 + i * 2;
                if (t < popAt) {
                    a = phase[i] + (double)t * 0.15;
                    p = c.clone().add(Math.cos(a) * 0.8, (double)t * 0.14 + (double)i * 0.1, Math.sin(a) * 0.8);
                    Compat.spawn(w, Compat.DUST, p, 3, 0.06, 0.08, 0.06, 0.0, new Particle.DustOptions(palette[i], 1.5f));
                    continue;
                }
                if (t != popAt) continue;
                a = phase[i] + (double)t * 0.15;
                p = c.clone().add(Math.cos(a) * 0.8, (double)t * 0.14 + (double)i * 0.1, Math.sin(a) * 0.8);
                Compat.spawn(w, Compat.DUST, p, 10, 0.15, 0.15, 0.15, 0.0, new Particle.DustOptions(palette[i], 1.0f));
                Compat.sound(w, p, "entity.chicken.egg", 1.0f, (float)EffectRunner.rnd(0.9, 1.5));
            }
            if (t == 0) {
                Compat.sound(w, c, "block.wool.break", 1.0f, 1.3f);
            }
        });
    }

    private void dSpores(World w, Location c) {
        this.animate(24, t -> {
            Compat.spawn(w, Compat.SPORE_BLOSSOM_AIR, c, 6, 0.6, 0.6, 0.6, 0.0);
            Compat.spawn(w, Compat.CRIMSON_SPORE, c, 3, 0.5, 0.5, 0.5, 0.0);
            Compat.spawn(w, Compat.WARPED_SPORE, c, 3, 0.5, 0.5, 0.5, 0.0);
            if (t == 0) {
                Compat.sound(w, c, "block.fungus.break", 1.0f, 0.7f);
            }
            if (t == 12) {
                Compat.sound(w, c, "block.moss.step", 1.0f, 0.6f);
            }
        });
    }

    private void dGrave(World w, Location base, Location c) {
        this.animate(34, t -> {
            if (t < 24) {
                Compat.spawn(w, Compat.ASH, base, 10, 0.6, 0.1, 0.6, 0.01);
                Compat.spawn(w, Compat.SOUL, base.clone().add(0.0, 0.2, 0.0), 2, 0.3, 0.1, 0.3, 0.02);
                if (t == 0) {
                    Compat.sound(w, base, "block.soul_sand.break", 1.0f, 0.6f);
                }
                if (t == 10) {
                    Compat.sound(w, base, "entity.vex.ambient", 0.7f, 0.5f);
                }
            } else if (t == 24) {
                Compat.spawn(w, Compat.SOUL, c.clone().add(0.0, 1.0, 0.0), 12, 0.3, 0.4, 0.3, 0.03);
                Compat.sound(w, base, "block.bell.use", 1.0f, 0.5f);
            }
        });
    }

    private void dShatter(World w, Location c) {
        this.animate(26, t -> {
            if (t < 12) {
                Compat.spawn(w, Compat.BLOCK, c, 6, 0.35, 0.6, 0.35, 0.0, Material.AMETHYST_BLOCK.createBlockData());
                if (t == 0) {
                    Compat.sound(w, c, "block.amethyst_block.place", 1.0f, 0.7f);
                }
            } else if (t == 12) {
                Compat.spawn(w, Compat.ITEM, c, 55, 0.4, 0.7, 0.4, 0.15, new ItemStack(Material.AMETHYST_SHARD));
                Compat.sound(w, c, "block.amethyst_cluster.break", 1.0f, 0.8f);
                Compat.sound(w, c, "block.glass.break", 1.0f, 0.9f);
            }
        });
    }

    private void dVoid(World w, Location c) {
        this.animate(28, t -> {
            Compat.spawn(w, Compat.SQUID_INK, c, 6, 0.4, 0.6, 0.4, 0.03);
            Compat.spawn(w, Compat.PORTAL, c, 8, 0.4, 0.6, 0.4, 0.4);
            if (t == 0) {
                Compat.sound(w, c, "entity.squid.squirt", 1.0f, 0.5f);
            }
            if (t == 10) {
                Compat.sound(w, c, "ambient.cave", 0.8f, 0.6f);
            }
            if (t == 20) {
                this.circle(c, 1.2, 14, p -> Compat.spawn(w, Compat.SQUID_INK, p, 1, 0.02, 0.02, 0.02, 0.01));
            }
        });
    }

    private void dEnder(World w, Location c) {
        this.animate(26, t -> {
            Compat.spawn(w, Compat.PORTAL, c, 15, 0.4, 0.7, 0.4, 0.6);
            if (t == 0) {
                Compat.sound(w, c, "entity.enderman.teleport", 1.0f, 0.8f);
            }
            if (t == 8) {
                Compat.sound(w, c, "entity.enderman.scream", 0.6f, 0.6f);
            }
            if (t == 18) {
                Compat.spawn(w, Compat.REVERSE_PORTAL, c, 20, 0.3, 0.5, 0.3, 0.05);
                Compat.sound(w, c, "entity.enderman.teleport", 0.7f, 0.5f);
            }
        });
    }

    private void dStatue(World w, Location c) {
        this.animate(40, t -> {
            if (t < 16) {
                double h = (double)t * 0.12;
                Compat.spawn(w, Compat.BLOCK, c.clone().add(0.0, h - 0.6, 0.0), 8, 0.3, 0.15, 0.3, 0.0, Material.STONE.createBlockData());
                if (t == 0) {
                    Compat.sound(w, c, "block.grindstone.use", 0.8f, 0.5f);
                }
                if (t == 10) {
                    Compat.sound(w, c, "block.stone.place", 1.0f, 0.5f);
                }
            } else if (t < 28) {
                if (t % 4 == 0) {
                    Compat.spawn(w, Compat.SMOKE, c, 2, 0.25, 0.5, 0.25, 0.0);
                }
            } else if (t == 28) {
                Compat.spawn(w, Compat.BLOCK, c, 60, 0.4, 0.7, 0.4, 0.12, Material.COBBLESTONE.createBlockData());
                Compat.spawn(w, Compat.SMOKE, c, 15, 0.4, 0.5, 0.4, 0.02);
                Compat.sound(w, c, "block.stone.break", 1.0f, 0.5f);
                Compat.sound(w, c, "block.gravel.break", 1.0f, 0.6f);
            }
        });
    }

    private void dWhirlpool(World w, Location base, Location c) {
        this.animate(36, t -> {
            if (t < 28) {
                double r = Math.max(0.2, 1.6 - (double)t * 0.05);
                double y = Math.max(0.05, 1.2 - (double)t * 0.04);
                for (int i = 0; i < 3; ++i) {
                    double a = (double)t * 0.55 + 2.0943951023931953 * (double)i;
                    Location p = base.clone().add(Math.cos(a) * r, y, Math.sin(a) * r);
                    Compat.spawn(w, Compat.SPLASH, p, 3, 0.08, 0.05, 0.08, 0.0);
                    Compat.spawn(w, Compat.BUBBLE_POP, p, 2, 0.05, 0.05, 0.05, 0.0);
                }
                if (t == 0) {
                    Compat.sound(w, base, "ambient.underwater.enter", 1.0f, 0.7f);
                }
                if (t == 12) {
                    Compat.sound(w, base, "entity.drowned.ambient_water", 0.8f, 0.6f);
                }
            } else if (t == 28) {
                Compat.spawn(w, Compat.BUBBLE_POP, base.clone().add(0.0, 0.3, 0.0), 20, 0.3, 0.2, 0.3, 0.05);
                Compat.sound(w, base, "item.bucket.fill", 1.0f, 0.6f);
            }
        });
    }

    private void dAngel(World w, Location base, Location c) {
        this.animate(52, t -> {
            if (t < 30) {
                double angle = (double)t * 0.5;
                double y = (double)t * 0.15;
                Compat.spawn(w, Compat.END_ROD, base.clone().add(Math.cos(angle) * 0.7, y, Math.sin(angle) * 0.7), 1);
                Compat.spawn(w, Compat.END_ROD, base.clone().add(Math.cos(angle + Math.PI) * 0.7, y, Math.sin(angle + Math.PI) * 0.7), 1);
                if (t == 0) {
                    Compat.sound(w, c, "block.beacon.activate", 1.0f, 1.6f);
                }
                if (t == 14) {
                    Compat.sound(w, c, "block.amethyst_block.chime", 1.0f, 1.8f);
                }
            } else if (t == 30) {
                this.circle(base.clone().add(0.0, 4.6, 0.0), 0.9, 18, p -> Compat.spawn(w, Compat.END_ROD, p, 1, 0.01, 0.01, 0.01, 0.005));
                Compat.spawn(w, Compat.FLASH, base.clone().add(0.0, 4.6, 0.0), 1);
                Compat.sound(w, c, "block.note_block.chime", 1.0f, 2.0f);
                Compat.sound(w, c, "block.beacon.power_select", 0.7f, 1.8f);
            } else if (t > 34 && t % 3 == 0) {
                Compat.spawn(w, Compat.ITEM, c.clone().add(EffectRunner.rnd(-0.8, 0.8), 2.5, EffectRunner.rnd(-0.8, 0.8)), 1, 0.05, 0.1, 0.05, 0.02, new ItemStack(Material.FEATHER));
            }
        });
    }

    private void dSouls(World w, Location base, Location c) {
        this.animate(46, t -> {
            if (t < 34) {
                double angle = (double)t * 0.6;
                double y = 2.5 - (double)t * 0.07;
                double r = Math.max(0.1, 1.4 - (double)t * 0.035);
                Location p = base.clone().add(Math.cos(angle) * r, y, Math.sin(angle) * r);
                Compat.spawn(w, Compat.SOUL, p, 2, 0.05, 0.05, 0.05, 0.005);
                Compat.spawn(w, Compat.SCULK_SOUL, p, 1, 0.0, 0.0, 0.0, 0.01);
                if (t == 0) {
                    Compat.sound(w, c, "particle.soul_escape", 1.6f, 0.6f);
                }
                if (t == 14) {
                    Compat.sound(w, c, "block.sculk_shrieker.shriek", 0.5f, 1.4f);
                }
            } else if (t == 34) {
                Compat.spawn(w, Compat.SCULK_CHARGE_POP, base.clone().add(0.0, 0.3, 0.0), 25, 0.4, 0.3, 0.4, 0.05);
                Compat.sound(w, base, "block.sculk.spread", 1.0f, 0.5f);
            }
        });
    }

    private void dGlitch(World w, Location c) {
        this.animate(42, t -> {
            if (t < 32) {
                Compat.spawn(w, Compat.ENCHANT, c, 20, 0.6, 0.8, 0.6, 1.0);
                if (t % 6 == 0) {
                    Location p = c.clone().add(EffectRunner.rnd(-1.2, 1.2), EffectRunner.rnd(-0.5, 1.5), EffectRunner.rnd(-1.2, 1.2));
                    org.bukkit.Color mono = ThreadLocalRandom.current().nextBoolean() ? org.bukkit.Color.fromRGB((int)90, (int)255, (int)140) : org.bukkit.Color.fromRGB((int)220, (int)70, (int)255);
                    Compat.spawn(w, Compat.DUST, p, 5, 0.12, 0.12, 0.12, 0.0, new Particle.DustOptions(mono, 1.4f));
                    Compat.sound(w, c, "block.enchantment_table.use", 0.8f, (float)EffectRunner.rnd(0.4, 1.9));
                }
            } else if (t == 32) {
                Compat.spawn(w, Compat.FLASH, c, 1);
                Compat.spawn(w, Compat.ENCHANT, c, 40, 0.8, 1.0, 0.8, 2.0);
                Compat.sound(w, c, "block.beacon.deactivate", 1.0f, 1.9f);
            }
        });
    }

    private void dPhoenix(World w, Location base, Location c) {
        this.animate(64, t -> {
            if (t < 26) {
                double angle = (double)t * 0.6;
                double y = (double)t * 0.09;
                Compat.spawn(w, Compat.FLAME, base.clone().add(Math.cos(angle) * 0.8, y, Math.sin(angle) * 0.8), 3, 0.05, 0.05, 0.05, 0.01);
                Compat.spawn(w, Compat.LAVA, c, 1, 0.3, 0.3, 0.3, 0.0);
                if (t == 0) {
                    Compat.sound(w, c, "item.firecharge.use", 1.0f, 0.6f);
                }
                if (t == 14) {
                    Compat.sound(w, c, "entity.blaze.ambient", 1.0f, 0.7f);
                }
            } else if (t < 46) {
                if (t % 6 == 0) {
                    for (int side = -1; side <= 1; side += 2) {
                        for (int i = 0; i < 5; ++i) {
                            double reach = 0.4 + (double)i * 0.28;
                            Location p = c.clone().add((double)side * reach, 1.0 + (double)i * 0.12, 0.0);
                            Compat.spawn(w, Compat.FLAME, p, 3, 0.06, 0.06, 0.06, 0.015);
                            Compat.spawn(w, Compat.SMALL_FLAME, p, 2, 0.05, 0.05, 0.05, 0.01);
                        }
                    }
                    Compat.sound(w, c, "entity.ender_dragon.flap", 0.6f, 1.5f);
                }
            } else if (t == 46) {
                Compat.spawn(w, Compat.FLAME, c, 45, 0.5, 0.7, 0.5, 0.12);
                Compat.spawn(w, Compat.LAVA, c, 15, 0.5, 0.5, 0.5, 0.0);
                Compat.sound(w, c, "entity.blaze.death", 0.6f, 0.5f);
                Compat.sound(w, c, "entity.generic.explode", 0.5f, 1.4f);
            } else if (t > 48 && t % 3 == 0) {
                Compat.spawn(w, Compat.ASH, c.clone().add(0.0, 1.0, 0.0), 6, 0.7, 0.5, 0.7, 0.01);
            }
        });
    }

    private void dSpecters(World w, Location c) {
        double[] headings = new double[5];
        for (int i = 0; i < 5; ++i) {
            headings[i] = 1.2566370614359172 * (double)i + EffectRunner.rnd(-0.3, 0.3);
        }
        this.animate(56, t -> {
            if (t == 0) {
                Compat.sound(w, c, "particle.soul_escape", 1.4f, 0.5f);
                Compat.sound(w, c, "entity.vex.charge", 0.9f, 0.6f);
            }
            if (t < 40) {
                double progress = (double)t / 40.0;
                for (int i = 0; i < 5; ++i) {
                    double a = headings[i] + progress * 1.2;
                    double r = progress * 3.2;
                    double y = Math.sin(progress * Math.PI) * 1.8 + 0.6;
                    Location p = c.clone().add(Math.cos(a) * r, y, Math.sin(a) * r);
                    Compat.spawn(w, Compat.SOUL, p, 2, 0.06, 0.06, 0.06, 0.005);
                    Compat.spawn(w, Compat.GLOW_SQUID_INK, p, 1, 0.04, 0.04, 0.04, 0.0);
                }
                if (t == 16) {
                    Compat.sound(w, c, "entity.vex.ambient", 0.8f, 0.5f);
                }
                if (t == 30) {
                    Compat.sound(w, c, "entity.phantom.ambient", 0.7f, 0.6f);
                }
            } else if (t == 40) {
                Compat.sound(w, c, "entity.ghast.scream", 0.3f, 0.5f);
                Compat.spawn(w, Compat.SOUL, c, 10, 1.5, 0.8, 1.5, 0.02);
            }
        });
    }

    private void dImplosion(World w, Location c) {
        this.animate(92, t -> {
            if (t < 46) {
                double r = 4.0 - (double)t * 0.055;
                for (int i = 0; i < 12; ++i) {
                    double a = 0.5235987755982988 * (double)i + (double)t * 0.25;
                    double y = Math.sin(a * 2.0 + (double)t * 0.2) * 1.1;
                    Location p2 = c.clone().add(Math.cos(a) * r, y, Math.sin(a) * r);
                    Vector v = c.toVector().subtract(p2.toVector());
                    if (!(v.lengthSquared() > 1.0E-6)) continue;
                    v.normalize().multiply(0.3);
                    Compat.spawn(w, Compat.REVERSE_PORTAL, p2, 0, v.getX(), v.getY(), v.getZ(), 1.0);
                }
                this.circle(c, Math.max(0.3, r * 0.4), 10, p -> Compat.spawn(w, Compat.SQUID_INK, p, 1, 0.02, 0.02, 0.02, 0.0));
                if (t == 0) {
                    Compat.sound(w, c, "block.beacon.deactivate", 0.9f, 0.4f);
                }
                if (t == 24) {
                    Compat.sound(w, c, "block.portal.ambient", 0.8f, 0.3f);
                }
            } else if (t == 46) {
                Compat.spawn(w, Compat.FLASH, c, 1);
                Compat.sound(w, c, "block.respawn_anchor.deplete", 1.0f, 0.5f);
            } else if (t == 62) {
                Compat.spawn(w, Compat.FLASH, c, 1);
                Compat.spawn(w, Compat.EXPLOSION_EMITTER, c, 1);
                Compat.sound(w, c, "entity.warden.sonic_boom", 1.0f, 0.8f);
                Compat.sound(w, c, "entity.generic.explode", 0.8f, 0.4f);
            } else if (t > 62 && t <= 80) {
                double r = (double)(t - 62) * 0.4;
                this.circle(c, r, (int)(10.0 + r * 5.0), p -> Compat.spawn(w, Compat.END_ROD, p, 1, 0.02, 0.02, 0.02, 0.01));
            }
        });
    }

    private void dSupernova(World w, Location base, Location c) {
        Location star = c.clone().add(0.0, 3.0, 0.0);
        this.animate(104, t -> {
            if (t < 44) {
                double size = 0.2 + (double)t * 0.02;
                Compat.spawn(w, Compat.END_ROD, star, 4, size, size, size, 0.01);
                Compat.spawn(w, Compat.FLAME, star, 2, size * 0.6, size * 0.6, size * 0.6, 0.005);
                if (t % 10 == 0) {
                    Compat.sound(w, c, "block.beacon.ambient", 0.9f, 0.6f + (float)t * 0.02f);
                }
            } else if (t < 60) {
                double size = Math.max(0.05, 1.1 - (double)(t - 44) * 0.07);
                Compat.spawn(w, Compat.END_ROD, star, 3, size, size, size, 0.0);
                if (t == 50) {
                    Compat.sound(w, c, "block.beacon.deactivate", 1.0f, 1.8f);
                }
            } else if (t == 60) {
                Compat.spawn(w, Compat.FLASH, star, 2);
                Compat.spawn(w, Compat.EXPLOSION_EMITTER, star, 2);
                Compat.spawn(w, Compat.LAVA, star, 20, 0.4, 0.4, 0.4, 0.0);
                Compat.sound(w, c, "entity.generic.explode", 1.0f, 0.5f);
                Compat.sound(w, c, "entity.ender_dragon.death", 0.25f, 0.6f);
                Compat.sound(w, c, "block.beacon.power_select", 1.0f, 0.5f);
            } else if (t > 60 && t <= 88 && (t - 60) % 4 == 0) {
                double r = (double)(t - 60) * 0.28;
                for (int ring = 0; ring < 3; ++ring) {
                    double y = 3.0 - (double)ring * 1.2;
                    this.circle(base.clone().add(0.0, y, 0.0), r * (1.0 - (double)ring * 0.15), 20, p -> Compat.spawn(w, Compat.END_ROD, p, 1, 0.03, 0.03, 0.03, 0.01));
                }
            }
        });
    }

    private void dLightning(World w, Location base, Location c) {
        Location[] preStrikes = new Location[]{base.clone().add(1.9, 0.0, -1.5), base.clone().add(-2.1, 0.0, 1.2)};
        this.animate(90, t -> {
            Location sky = c.clone().add(0.0, 4.5, 0.0);
            if (t < 34) {
                this.circle(sky, 2.8, 18, p -> Compat.spawn(w, Compat.CLOUD, p, 1, 0.25, 0.1, 0.25, 0.0));
                Compat.spawn(w, Compat.FALLING_WATER, sky, 6, 1.8, 0.1, 1.8, 0.0);
                if (t == 0) {
                    Compat.sound(w, c, "entity.lightning_bolt.thunder", 0.4f, 0.4f);
                }
                if (t == 16) {
                    Compat.sound(w, c, "weather.rain.above", 0.8f, 0.7f);
                }
            } else if (t == 38 || t == 52) {
                w.strikeLightningEffect(preStrikes[t == 38 ? 0 : 1]);
            } else if (t == 66) {
                w.strikeLightningEffect(base);
                Compat.spawn(w, Compat.FLASH, c, 1);
                Compat.sound(w, c, "entity.lightning_bolt.impact", 1.0f, 0.7f);
            } else if (t == 70) {
                w.strikeLightningEffect(base);
                this.circle(base.clone().add(0.0, 0.15, 0.0), 1.5, 16, p -> Compat.spawn(w, Compat.ELECTRIC_SPARK, p, 2, 0.05, 0.15, 0.05, 0.03));
                Compat.spawn(w, Compat.LARGE_SMOKE, c, 12, 0.5, 0.5, 0.5, 0.02);
                Compat.sound(w, c, "entity.lightning_bolt.thunder", 1.0f, 0.9f);
                Compat.sound(w, c, "block.fire.extinguish", 1.0f, 0.5f);
            }
        });
    }

    private void dPufferfish(World w, Location c) {
        Compat.spawn(w, Compat.SPLASH, c, 30, 0.4, 0.5, 0.4, 0.2);
        Compat.spawn(w, Compat.BUBBLE_POP, c, 20, 0.4, 0.5, 0.4, 0.1);
        Compat.sound(w, c, "entity.puffer_fish.blow_up", 1.2f, 0.7f);
        Compat.sound(w, c, "entity.slime.squish", 1.0f, 0.5f);
        Set<PufferFish> school = Collections.synchronizedSet(new HashSet());
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < 12; ++i) {
            try {
                double a = 0.5235987755982988 * (double)i + rng.nextDouble(-0.2, 0.2);
                PufferFish fish = (PufferFish)w.spawn(c, PufferFish.class, f -> {
                    f.setPuffState(2);
                    f.setInvulnerable(true);
                    f.setSilent(false);
                    f.setRemoveWhenFarAway(true);
                });
                this.tag((Entity)fish);
                fish.setVelocity(new Vector(Math.cos(a) * EffectRunner.rnd(0.25, 0.5), EffectRunner.rnd(0.35, 0.6), Math.sin(a) * EffectRunner.rnd(0.25, 0.5)));
                school.add(fish);
                continue;
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
        this.animate(84, 1L, t -> {
            if (t == 20 || t == 44) {
                Compat.sound(w, c, "entity.puffer_fish.blow_up", 0.8f, (float)EffectRunner.rnd(0.8, 1.3));
            }
            if (t >= 70 && (t - 70) % 1 == 0) {
                Set set;
                Set set2 = set = school;
                synchronized (set2) {
                    Iterator it = school.iterator();
                    if (it.hasNext()) {
                        PufferFish fish = (PufferFish)it.next();
                        it.remove();
                        if (fish.isValid()) {
                            Compat.spawn(w, Compat.POOF, fish.getLocation().add(0.0, 0.2, 0.0), 8, 0.15, 0.15, 0.15, 0.02);
                            Compat.sound(w, fish.getLocation(), "entity.puffer_fish.blow_out", 0.7f, (float)EffectRunner.rnd(0.9, 1.4));
                        }
                        this.discard((Entity)fish);
                    }
                }
            }
        }, () -> {
            Set set;
            Set set2 = set = school;
            synchronized (set2) {
                for (PufferFish fish : school) {
                    this.discard((Entity)fish);
                }
                school.clear();
            }
        });
    }

    private void dRequiem(World w, Location base, Location c) {
        this.animate(112, t -> {
            if (t < 20) {
                Compat.spawn(w, Compat.END_ROD, c.clone().add(0.0, 8.0 - (double)t * 0.3, 0.0), 3, 0.3, 0.3, 0.3, 0.005);
                if (t == 0) {
                    Compat.sound(w, c, "block.beacon.activate", 1.0f, 0.6f);
                }
            } else if (t < 80) {
                for (double y = 0.0; y < 7.0; y += 0.9) {
                    Compat.spawn(w, Compat.END_ROD, base.clone().add(0.0, y, 0.0), 1, 0.15, 0.3, 0.15, 0.002);
                }
                double ringY = 6.5 - (double)((t - 20) % 24) * 0.28;
                this.circle(base.clone().add(0.0, ringY, 0.0), 1.1, 16, p -> Compat.spawn(w, Compat.WAX_OFF, p, 1, 0.02, 0.02, 0.02, 0.0));
                if ((t - 20) % 12 == 0) {
                    int step = (t - 20) / 12;
                    Compat.sound(w, c, "block.note_block.chime", 1.0f, 0.6f + (float)step * 0.2f);
                }
                if (t == 50) {
                    Compat.sound(w, c, "block.amethyst_block.resonate", 1.0f, 0.7f);
                }
                if (t > 40) {
                    Compat.spawn(w, Compat.SOUL, base.clone().add(0.0, (double)(t - 40) * 0.16, 0.0), 2, 0.1, 0.1, 0.1, 0.01);
                }
            } else if (t == 80) {
                Compat.spawn(w, Compat.FLASH, c.clone().add(0.0, 2.0, 0.0), 1);
                for (double y = 0.5; y < 6.5; y += 1.5) {
                    double fy = y;
                    this.circle(base.clone().add(0.0, fy, 0.0), 1.8, 20, p -> Compat.spawn(w, Compat.END_ROD, p, 1, 0.03, 0.03, 0.03, 0.03));
                }
                Compat.sound(w, c, "block.amethyst_block.chime", 1.0f, 0.5f);
                Compat.sound(w, c, "block.amethyst_block.chime", 1.0f, 0.75f);
                Compat.sound(w, c, "block.amethyst_block.chime", 1.0f, 1.0f);
                Compat.sound(w, c, "block.beacon.power_select", 0.8f, 1.6f);
            } else if (t > 84 && t % 4 == 0) {
                Compat.spawn(w, Compat.WAX_OFF, c.clone().add(0.0, 1.5, 0.0), 6, 0.8, 1.2, 0.8, 0.01);
            }
        });
    }

    private void kBlastoff(World w, Location base, Location c, Player victim) {
        ItemStack[] armor = null;
        ItemStack hand = null;
        LivingEntity clone = null;
        if (victim != null) {
            try {
                armor = victim.getInventory().getArmorContents();
                hand = victim.getInventory().getItemInMainHand();
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            clone = Clones.spawnFrozenClone(base, victim, armor, hand);
            if (clone != null) {
                this.tag((Entity)clone);
            }
        }
        LivingEntity flyer = clone;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double ha = rng.nextDouble(Math.PI * 2);
        Vector drift = new Vector(Math.cos(ha), 0.0, Math.sin(ha));
        Location start = base.clone();
        double[] height = new double[]{0.0};
        Compat.sound(w, base, "entity.creeper.primed", 0.8f, 0.7f);
        this.animate(96, 1L, t -> {
            if (t < 14) {
                Location feet = start.clone().add(EffectRunner.rnd(-0.1, 0.1), 0.05, EffectRunner.rnd(-0.1, 0.1));
                if (flyer != null && flyer.isValid() && t % 2 == 0) {
                    flyer.teleport(feet.clone().add(0.0, 0.0, 0.0));
                }
                Compat.spawn(w, Compat.SMOKE, feet, 4, 0.2, 0.05, 0.2, 0.01);
                Compat.spawn(w, Compat.LAVA, feet, 1);
                if (t == 10) {
                    Compat.sound(w, start, "entity.firework_rocket.launch", 1.6f, 0.8f);
                    Compat.sound(w, start, "entity.ghast.shoot", 0.8f, 1.6f);
                    Compat.spawn(w, Compat.EXPLOSION, start, 1);
                    Compat.spawn(w, Compat.CLOUD, start, 18, 0.6, 0.1, 0.6, 0.08);
                }
                return;
            }
            if (t < 78) {
                int ft = t - 14;
                double vy = 0.28 + (double)ft * 0.026;
                height[0] = height[0] + vy;
                double side = (double)ft * (double)ft * 0.0035;
                Location pos = start.clone().add(drift.getX() * side, height[0], drift.getZ() * side);
                if (pos.getY() > (double)(w.getMaxHeight() - 4)) {
                    pos.setY((double)(w.getMaxHeight() - 4));
                }
                if (flyer != null && flyer.isValid()) {
                    Location tp = pos.clone();
                    tp.setYaw((float)(ft * 24));
                    flyer.teleport(tp);
                }
                Compat.spawn(w, Compat.FLAME, pos.clone().add(0.0, -0.4, 0.0), 5, 0.12, 0.12, 0.12, 0.02);
                Compat.spawn(w, Compat.CLOUD, pos.clone().add(0.0, -0.7, 0.0), 3, 0.15, 0.15, 0.15, 0.01);
                Compat.spawn(w, Compat.FIREWORK, pos, 2, 0.1, 0.1, 0.1, 0.05);
                if (ft % 8 == 0) {
                    Compat.sound(w, pos, "block.note_block.pling", 0.9f, (float)(0.7 + (double)ft * 0.016));
                    Compat.sound(w, pos, "entity.firework_rocket.launch", 0.5f, (float)(1.0 + (double)ft * 0.01));
                }
                return;
            }
            if (t == 78) {
                Location peak = start.clone().add(drift.getX() * 14.0, height[0], drift.getZ() * 14.0);
                if (flyer != null && flyer.isValid()) {
                    Compat.spawn(w, Compat.POOF, flyer.getLocation().add(0.0, 1.0, 0.0), 14, 0.25, 0.4, 0.25, 0.02);
                }
                this.discard((Entity)flyer);
                Compat.spawn(w, Compat.FLASH, peak, 2);
                this.firework(w, peak, FireworkEffect.Type.STAR, org.bukkit.Color.WHITE, org.bukkit.Color.fromRGB((int)255, (int)230, (int)120));
                Compat.sound(w, peak, "entity.firework_rocket.twinkle", 1.6f, 1.2f);
                Compat.sound(w, peak, "block.note_block.chime", 1.8f, 1.7f);
                this.later(4L, () -> Compat.sound(w, peak, "block.note_block.chime", 1.4f, 2.0f));
                Particle.DustOptions gold = new Particle.DustOptions(org.bukkit.Color.fromRGB((int)255, (int)235, (int)130), 1.5f);
                for (int i = 0; i < 5; ++i) {
                    double a1 = 1.5707963267948966 + (double)i * 2.5132741228718345;
                    double a2 = 1.5707963267948966 + (double)(i + 1) * 2.5132741228718345;
                    for (double f = 0.0; f <= 1.0; f += 0.12) {
                        double x = Math.cos(a1) * (1.0 - f) * 1.6 + Math.cos(a2) * f * 1.6;
                        double y = Math.sin(a1) * (1.0 - f) * 1.6 + Math.sin(a2) * f * 1.6;
                        Compat.spawn(w, Compat.DUST, peak.clone().add(x, y, 0.0), 1, 0.0, 0.0, 0.0, 0.0, gold);
                    }
                }
                return;
            }
            if (t > 82 && t % 5 == 0) {
                Location peak = start.clone().add(drift.getX() * 14.0, height[0], drift.getZ() * 14.0);
                Compat.spawn(w, Compat.FIREWORK, peak, 6, 0.5, 0.5, 0.5, 0.03);
            }
        }, () -> this.discard((Entity)flyer));
    }

    private void dSixtySeven(World w, Location base, Location c) {
        TextDisplay display = null;
        try {
            display = (TextDisplay)w.spawn(c.clone().add(0.0, 2.2, 0.0), TextDisplay.class, d -> {
                d.text((Component)Component.text((String)"67", (TextColor)TextColor.color((int)16765514), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}));
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setSeeThrough(true);
                d.setBackgroundColor(org.bukkit.Color.fromARGB((int)0, (int)0, (int)0, (int)0));
                d.setBrightness(new Display.Brightness(15, 15));
                Transformation tr = d.getTransformation();
                d.setTransformation(new Transformation(tr.getTranslation(), tr.getLeftRotation(), new Vector3f(0.5f, 0.5f, 0.5f), tr.getRightRotation()));
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(3);
            });
            this.tag((Entity)display);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        TextDisplay big = display;
        boolean custom = this.plugin.getConfig().getBoolean("sixtyseven.custom-sound", true);
        String customKey = this.plugin.getConfig().getString("sixtyseven.custom-sound-key", "zakiworld:doot67");
        if (custom && customKey != null && !customKey.isBlank()) {
            Compat.sound(w, c, customKey, 2.0f, 1.0f);
        } else {
            Compat.sound(w, c, "item.goat_horn.sound.1", 1.6f, 0.9f);
            Compat.sound(w, c, "entity.wither.spawn", 0.5f, 1.8f);
        }
        Compat.sound(w, c, "entity.ender_dragon.growl", 0.6f, 1.4f);
        Compat.spawn(w, Compat.FLASH, c, 1);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        this.animate(130, 1L, t -> {
            Transformation tr;
            if (big != null && big.isValid()) {
                float scale = t < 8 ? 0.5f + (float)t * 1.35f : 9.0f + (float)Math.sin((double)t * 0.35) * 1.4f;
                tr = big.getTransformation();
                big.setTransformation(new Transformation(tr.getTranslation(), tr.getLeftRotation(), new Vector3f(scale, scale, scale), tr.getRightRotation()));
                if (t % 2 == 0) {
                    int rgb = Color.HSBtoRGB((float)((double)t * 0.02 % 1.0), 0.85f, 1.0f) & 0xFFFFFF;
                    big.text((Component)Component.text((String)"67", (TextColor)TextColor.color((int)rgb), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}));
                }
            }
            if (t >= 6 && t < 110) {
                double a = (double)t * 0.42;
                double y = (double)t * 0.055;
                for (int arm = 0; arm < 2; ++arm) {
                    double aa = a + (double)arm * Math.PI;
                    Location sp = c.clone().add(Math.cos(aa) * 2.2, y, Math.sin(aa) * 2.2);
                    Compat.spawn(w, Compat.TOTEM, sp, 2, 0.05, 0.05, 0.05, 0.06);
                    if (t % 3 != 0) continue;
                    Compat.spawn(w, Compat.DUST, sp, 1, 0.0, 0.0, 0.0, 0.0, new Particle.DustOptions(org.bukkit.Color.fromRGB((int)255, (int)70, (int)150), 1.6f));
                }
            }
            if (t >= 16 && t % 22 == 0 && t <= 104) {
                Location fw = c.clone().add(EffectRunner.rnd(-2.5, 2.5), EffectRunner.rnd(2.0, 5.0), EffectRunner.rnd(-2.5, 2.5));
                this.firework(w, fw, rng.nextBoolean() ? FireworkEffect.Type.BALL_LARGE : FireworkEffect.Type.STAR, org.bukkit.Color.fromRGB((int)255, (int)210, (int)74), org.bukkit.Color.fromRGB((int)255, (int)45, (int)149));
            }
            if (t % 26 == 6) {
                Compat.sound(w, c, "block.note_block.bit", 1.4f, 0.9f);
            }
            if (t % 26 == 12) {
                Compat.sound(w, c, "block.note_block.bit", 1.4f, 1.1f);
            }
            if (t % 14 < 7 && t > 10 && t < 100) {
                double r = 1.5 + (double)(t % 14) * 0.35;
                this.circle(base.clone().add(0.0, 0.15, 0.0), r, (int)(8.0 + r * 5.0), pnt -> Compat.spawn(w, Compat.FLAME, pnt, 1, 0.02, 0.05, 0.02, 0.005));
            }
            if (t == 112) {
                Compat.spawn(w, Compat.EXPLOSION_EMITTER, c.clone().add(0.0, 1.5, 0.0), 1);
                Compat.spawn(w, Compat.FLASH, c.clone().add(0.0, 3.0, 0.0), 2);
                this.firework(w, c.clone().add(0.0, 4.0, 0.0), FireworkEffect.Type.BALL_LARGE, org.bukkit.Color.fromRGB((int)255, (int)210, (int)74), org.bukkit.Color.WHITE);
                Compat.sound(w, c, "entity.generic.explode", 1.2f, 0.7f);
                Compat.sound(w, c, "entity.player.levitate", 1.0f, 0.5f);
            }
            if (t > 112 && big != null && big.isValid()) {
                float shrink = Math.max(0.1f, 9.0f - (float)(t - 112) * 0.6f);
                tr = big.getTransformation();
                big.setTransformation(new Transformation(tr.getTranslation(), tr.getLeftRotation(), new Vector3f(shrink, shrink, shrink), tr.getRightRotation()));
            }
        }, () -> this.discard((Entity)big));
    }

    @FunctionalInterface
    private static interface TickAction {
        public void accept(int var1);
    }
}

