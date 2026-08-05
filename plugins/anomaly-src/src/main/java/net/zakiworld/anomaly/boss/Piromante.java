package net.zakiworld.anomaly.boss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.zakiworld.anomaly.AnomalyPlugin;
import net.zakiworld.anomaly.core.ActiveAnomaly;
import net.zakiworld.anomaly.core.Compat;
import net.zakiworld.anomaly.core.Fx;
import net.zakiworld.anomaly.core.Glow;
import net.zakiworld.anomaly.core.Stop;
import net.zakiworld.anomaly.core.Tags;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Blaze;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * EL PIROMANTE, la decimoquinta anomalia.
 *
 * Un aldeano del desierto —el de la tunica mas naranja que hay— con armadura de cuero
 * teñida de rojo y una vara de blaze. Es el unico jefe puramente MAGICO y puramente a
 * DISTANCIA: no tiene un solo golpe cuerpo a cuerpo, todo lo que hace sale ardiendo de
 * las manos, y encima persigue, asi que no se le gana quedandose lejos.
 *
 * QUEMA EL SUELO DE VERDAD, y ese es el motivo de la unica regla dura que tiene: cada
 * fuego que prende pasa antes por WorldGuard. Dentro de una region protegida, sea de
 * la administracion o el terreno de un jugador, NO se enciende nada. Ademas, todo lo
 * que prende se apunta y se apaga al cerrar el evento: el jardin permanente es cosa de
 * Herbola, aqui no queda ni una brasa.
 */
public final class Piromante extends BossFight {

    public static final String ID = "piromante";
    public static final TextColor ACCENT = TextColor.color(0xE2703A);

    /** Los fuegos que ha prendido, con lo que habia debajo para devolverlo. */
    private final Map<Location, Material> burned = new LinkedHashMap<>();
    /** Tope de fuegos vivos: pasado de aqui deja de prender. */
    private static final int MAX_FIRES = 260;

    private double damageBonus = 1.0;

    public Piromante(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        super(plugin, event, where);
        abilities.addAll(plugin.registry().piromanteAbilities());
    }

    @Override
    public String bossName() {
        return "El Piromante";
    }

    // ------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        Location spot = arena.clone();

        boss = world().spawn(spot, Villager.class, v -> {
            v.setAdult();
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
            v.setCanPickupItems(false);
            // El del desierto es el de tunica mas naranja, y de profesion armero, que
            // le pone el delantal rojo. Es lo mas cerca del fuego que da un aldeano.
            try {
                v.setVillagerType(Villager.Type.DESERT);
                v.setProfession(Villager.Profession.WEAPONSMITH);
                v.setVillagerLevel(5);
            } catch (Throwable ignored) {
            }
            v.customName(Component.text("✦ ", ACCENT)
                    .append(Component.text("El Piromante", ACCENT, TextDecoration.BOLD)));
            v.setCustomNameVisible(true);
        });

        EntityEquipment eq = boss.getEquipment();
        if (eq != null) {
            eq.setHelmet(dyed(Material.LEATHER_HELMET, 0xB3301C));
            eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE, 0xE2703A));
            eq.setLeggings(dyed(Material.LEATHER_LEGGINGS, 0x8C2A12));
            eq.setBoots(dyed(Material.LEATHER_BOOTS, 0xE2A03A));
            eq.setItemInMainHand(new ItemStack(Material.BLAZE_ROD));
            eq.setHelmetDropChance(0);
            eq.setChestplateDropChance(0);
            eq.setLeggingsDropChance(0);
            eq.setBootsDropChance(0);
            eq.setItemInMainHandDropChance(0);
        }

        Compat.setAttribute(boss, "attack_damage", 2);
        Compat.setAttribute(boss, "armor", 12);
        Compat.setAttribute(boss, "knockback_resistance", 0.6);
        Compat.setAttribute(boss, "follow_range", 80);
        Compat.setAttribute(boss, "movement_speed", 0.38);
        Compat.setAttribute(boss, "scale", 1.4);
        applyHealth(plugin.registry().scaledHealth(plugin.registry().piromante(), targets(96).size()));
        boss.setMaximumNoDamageTicks(6);
        boss.setFireTicks(0);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        Glow.apply(boss, event.type().glowColor());

        arrivalAnimation(spot);
    }

    private static ItemStack dyed(Material piece, int rgb) {
        ItemStack item = new ItemStack(piece);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(org.bukkit.Color.fromRGB(rgb));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void arrivalAnimation(Location spot) {
        boss.setInvulnerable(true);
        busyFor(80);
        soundAt(spot, "item.firecharge.use", 1.8f, 0.5f);
        soundAt(spot, "entity.blaze.ambient", 1.4f, 0.7f);

        animate(80, tick -> {
            double t = tick / 80.0;
            Fx.ring(spot, t * 7, (int) (t * 7 * 6) + 8, l -> {
                Location g = Fx.ground(l, 4);
                Compat.spawn(world(), Compat.FLAME, g.clone().add(0, 0.2, 0), 1, 0.05, 0.02, 0.05, 0.01);
            });
            Fx.helix(spot, 1.3, 3.6, 18, 3.0, l -> {
                Compat.spawn(world(), Compat.SMALL_FLAME, l, 1, 0, 0, 0, 0);
                if (Math.random() < 0.2) Compat.spawn(world(), Compat.LAVA, l, 1, 0, 0, 0, 0);
            });
            if (tick % 14 == 0) {
                soundAt(spot, "block.fire.ambient", 1.4f, 0.8f);
                Compat.spawn(world(), Compat.CAMPFIRE_SIGNAL_SMOKE, spot.clone().add(0, 1, 0), 4,
                        0.4, 0.4, 0.4, 0.02);
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            Compat.spawn(world(), Compat.FLASH, spot.clone().add(0, 1, 0), 1);
            soundAt(spot, "item.firecharge.use", 2.0f, 0.7f);
            for (Player p : Fx.viewersNear(spot, 90)) {
                p.showTitle(Title.title(
                        Component.text("✦ ANOMALIA ✦", ACCENT, TextDecoration.BOLD),
                        Component.text("El Piromante  ·  no le des la espalda al fuego", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1800), Duration.ofMillis(600))));
            }
        });
    }

    // ------------------------------------------------------------------- EL FUEGO

    /**
     * Prende un bloque. AQUI ESTA LA REGLA IMPORTANTE del jefe: antes de encender nada
     * se le pregunta a WorldGuard. Dentro de una region —de la administracion o el
     * terreno de un jugador— no se enciende, y punto. Todo lo que se prende queda
     * anotado para apagarlo al final: aqui no se queda ninguna brasa.
     */
    private void ignite(Location where) {
        if (burned.size() >= MAX_FIRES) return;
        Location ground = Fx.ground(where, 4);
        Block spot = ground.getBlock();
        if (spot.getType() != Material.AIR && spot.getType() != Material.CAVE_AIR) return;
        Block below = spot.getRelative(0, -1, 0);
        if (!below.getType().isSolid()) return;
        // Ni dentro ni al borde: el fuego se propaga y no queremos que salte la valla.
        if (plugin.protection().nearRegion(ground, 2)) return;

        Location key = spot.getLocation();
        if (burned.containsKey(key)) return;
        burned.put(key, spot.getType());
        spot.setType(Material.FIRE, false);
    }

    /** Un circulo de fuego, respetando las protecciones bloque a bloque. */
    private void igniteArea(Location center, double radius, double density) {
        int r = (int) Math.ceil(radius);
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z > radius * radius) continue;
                if (Math.random() > density) continue;
                ignite(center.clone().add(x, 1, z));
            }
        }
    }

    private void extinguishAll() {
        for (Map.Entry<Location, Material> e : burned.entrySet()) {
            try {
                Block b = e.getKey().getBlock();
                if (b.getType() == Material.FIRE) b.setType(e.getValue(), false);
            } catch (Throwable ignored) {
            }
        }
        burned.clear();
    }

    /** Quemadura: dano de fuego con toda la parafernalia sonora y visual. */
    private void scorch(Player p, double damage, int fireTicks) {
        hit(p, damage * damageBonus);
        if (fireTicks > 0) p.setFireTicks(Math.max(p.getFireTicks(), fireTicks));
        Compat.spawn(world(), Compat.FLAME, p.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.03);
        Compat.spawn(world(), Compat.LAVA, p.getLocation().add(0, 1, 0), 2, 0.2, 0.2, 0.2, 0);
        soundAt(p.getLocation(), "entity.player.hurt_on_fire", 1.1f, 0.9f);
    }

    // -------------------------------------------------------------------- ambiente

    @Override
    protected void ambient() {
        if (!alive()) return;
        boss.setFireTicks(0);
        keepHostile();

        // Le arde la vara y le humea la tunica.
        if (ticks() % 3 == 0) {
            Location hand = boss.getLocation().add(0, 1.3, 0);
            Compat.spawn(world(), Compat.SMALL_FLAME, hand, 2, 0.35, 0.25, 0.35, 0.01);
            if (random.nextInt(4) == 0) {
                Compat.spawn(world(), Compat.LAVA, hand, 1, 0.2, 0.1, 0.2, 0);
            }
        }
        if (ticks() % 10 == 0) {
            Compat.spawn(world(), Compat.CAMPFIRE_COSY_SMOKE, boss.getEyeLocation().add(0, 0.5, 0), 1,
                    0.2, 0.1, 0.2, 0.01);
        }
        if (ticks() % 90 == 0) soundAt(loc(), "block.fire.ambient", 0.9f, 0.7f);
    }

    /** Persigue de verdad: no es un mago que se queda quieto lanzando. */
    private void keepHostile() {
        if (ticks() % 10 != 0) return;
        Player t = Fx.nearest(boss.getLocation(), plugin.settings().participationRadius());
        if (t == null) return;
        if (boss instanceof org.bukkit.entity.Mob m) {
            LivingEntity current = m.getTarget();
            if (current == null || !current.isValid() || current.isDead()) m.setTarget(t);
        }
        face(t.getEyeLocation());

        double d2 = boss.getLocation().distanceSquared(t.getLocation());
        if (d2 > 64 && ticks() % 20 == 0) {
            Vector to = t.getLocation().toVector().subtract(boss.getLocation().toVector());
            if (to.lengthSquared() > 0.01) {
                boss.setVelocity(to.normalize().multiply(0.5).setY(Math.max(0.05, boss.getVelocity().getY())));
                Compat.spawn(world(), Compat.FLAME, boss.getLocation(), 4, 0.2, 0.1, 0.2, 0.02);
            }
        }
        // Un fogonazo de aviso cada tanto, para que se note que sigue ahi.
        if (ticks() % 60 == 0 && boss.hasLineOfSight(t)) {
            Fx.beam(boss.getEyeLocation(), t.getEyeLocation(), 2.0, p ->
                    Compat.spawn(world(), Compat.SMALL_FLAME, p, 1, 0, 0, 0, 0));
        }
    }

    @Override
    public void cleanup() {
        extinguishAll();
        super.cleanup();
    }

    /** Todo lo que lanza quema, tambien lo que le salga de los esbirros. */
    @Override
    public void onDealtDamage(Player victim, org.bukkit.entity.Entity dealer) {
        victim.setFireTicks(Math.max(victim.getFireTicks(), 60));
        Compat.spawn(world(), Compat.FLAME, victim.getLocation().add(0, 1, 0), 8, 0.3, 0.4, 0.3, 0.03);
    }

    // --------------------------------------------------------------- cambio de fase

    @Override
    protected void onPhaseChange(int from, int to) {
        if (event.bars() != null) event.bars().flash(from);
        if (to == 2) emberPhase();
        if (to == 3) infernoPhase();
    }

    /** FASE I -> II. Se envuelve en brasas: mas rapido y mas caliente. */
    private void emberPhase() {
        if (!alive()) return;
        busyFor(60);
        Location spot = boss.getLocation();
        soundAt(spot, "entity.blaze.shoot", 1.7f, 0.6f);
        broadcastNear(Component.text("Se envuelve en brasas.", ACCENT));

        animate(60, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            Fx.helix(l, 1.1, 2.8, 14, 2.5, p -> Compat.spawn(world(), Compat.FLAME, p, 1, 0, 0, 0, 0.01));
            if (tick % 10 == 0) {
                Compat.spawn(world(), Compat.LAVA, l.clone().add(0, 1, 0), 6, 0.4, 0.4, 0.4, 0);
                soundAt(l, "block.lava.pop", 1.2f, 0.8f);
            }
        }, () -> {
            if (!alive()) return;
            damageBonus = 1.25;
            Compat.setAttribute(boss, "movement_speed", 0.42);
            titleNear(Component.text("FASE II", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("El suelo empieza a arder", NamedTextColor.GRAY));
            igniteArea(Fx.ground(boss.getLocation(), 5), 5, 0.4);
        });
    }

    /** FASE III. Infierno: el aire arde a su alrededor y todo pega mas. */
    private void infernoPhase() {
        if (!alive()) return;
        busyFor(70);
        Location spot = boss.getLocation();
        soundAt(spot, "entity.ghast.shoot", 1.8f, 0.6f);
        soundAt(spot, "item.firecharge.use", 1.8f, 0.4f);
        titleNear(Component.text("FASE III", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Ya no apaga nada", NamedTextColor.GRAY));

        animate(70, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1, 0);
            Fx.sphere(l, 1.5 + tick * 0.03, 20, p -> {
                Compat.spawn(world(), Compat.FLAME, p, 1, 0, 0, 0, 0.01);
                if (Math.random() < 0.15) Compat.spawn(world(), Compat.SOUL_FIRE_FLAME, p, 1, 0, 0, 0, 0);
            });
            if (tick % 12 == 0) {
                Compat.spawn(world(), Compat.EXPLOSION, l, 1, 0.5, 0.5, 0.5, 0);
                soundAt(l, "block.fire.extinguish", 1.2f, 0.5f);
            }
        }, () -> {
            if (!alive()) return;
            damageBonus = 1.55;
            Compat.setAttribute(boss, "movement_speed", 0.46);
            igniteArea(Fx.ground(boss.getLocation(), 5), 8, 0.5);
        });
    }

    // ---------------------------------------------------------------------- muerte

    /** Se apaga como una hoguera a la que le echan arena, y se lleva sus fuegos. */
    @Override
    public void onDeath() {
        Location l = loc();
        soundAt(l, "entity.blaze.death", 1.7f, 0.6f);

        animate(80, tick -> {
            double t = tick / 80.0;
            Compat.spawn(world(), Compat.FLAME, l.clone().add(0, 1, 0), (int) (8 * (1 - t)) + 1,
                    0.4, 0.6, 0.4, 0.02);
            Compat.spawn(world(), Compat.CAMPFIRE_COSY_SMOKE, l.clone().add(0, 1.2, 0), 2, 0.3, 0.3, 0.3, 0.02);
            if (tick % 14 == 0) soundAt(l, "block.fire.extinguish", 1.3f, 0.6f + (float) t);
        }, () -> {
            Compat.spawn(world(), Compat.CAMPFIRE_SIGNAL_SMOKE, l.clone().add(0, 1, 0), 30, 0.6, 0.8, 0.6, 0.05);
            soundAt(l, "block.fire.extinguish", 1.8f, 0.4f);
            extinguishAll();
            broadcastNear(Component.text("Se apaga, y el fuego con el.", ACCENT));
        });
    }

    // ============================================================== HABILIDADES ==

    /** 1. Bola de Fuego: la clasica, cargada y con aviso. */
    public void fireball() {
        if (!alive()) return;
        Player target = randomTarget();
        if (target == null) return;
        soundAt(loc(), "entity.blaze.shoot", 1.6f, 0.7f);
        broadcastNear(Component.text("Carga una bola.", ACCENT));

        animate(34, tick -> {
            if (!alive()) throw Stop.now();
            Location hand = boss.getEyeLocation();
            if (tick < 24) {
                Fx.sphere(hand, 0.4 + tick * 0.02, 8, p ->
                        Compat.spawn(world(), Compat.FLAME, p, 1, 0, 0, 0, 0));
                return;
            }
            if (tick != 24) return;
            if (!Fx.isFightable(target)) throw Stop.now();
            try {
                LargeFireball ball = boss.launchProjectile(LargeFireball.class,
                        target.getEyeLocation().toVector().subtract(hand.toVector()).normalize());
                ball.setYield(0);
                ball.setIsIncendiary(false);
                Tags.markMinion(ball, ID);
                Tags.markEvent(ball, event.id());
            } catch (Throwable ignored) {
            }
            soundAt(hand, "item.firecharge.use", 1.7f, 0.8f);
        }, null);
    }

    /** 2. Andanada de Brasas: seis bolas pequenas en abanico. */
    public void emberVolley() {
        if (!alive()) return;
        Player target = randomTarget();
        if (target == null) return;
        soundAt(loc(), "entity.blaze.shoot", 1.4f, 1.2f);

        for (int i = 0; i < 6; i++) {
            final int idx = i;
            later(i * 5, () -> {
                if (!alive() || !Fx.isFightable(target)) return;
                Location hand = boss.getEyeLocation();
                Vector dir = target.getEyeLocation().toVector().subtract(hand.toVector()).normalize();
                double spread = (idx - 2.5) * 0.09;
                dir.add(new Vector(-dir.getZ() * spread, 0, dir.getX() * spread)).normalize();
                try {
                    SmallFireball ball = boss.launchProjectile(SmallFireball.class, dir);
                    ball.setIsIncendiary(false);
                    Tags.markMinion(ball, ID);
                    Tags.markEvent(ball, event.id());
                } catch (Throwable ignored) {
                }
                Compat.spawn(world(), Compat.SMALL_FLAME, hand, 6, 0.2, 0.2, 0.2, 0.02);
                soundAt(hand, "entity.blaze.shoot", 1.0f, 1.4f);
            });
        }
    }

    /** 3. Mar de Llamas: un circulo enorme de suelo ardiendo. */
    public void seaOfFlames() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 5);
        soundAt(c, "item.firecharge.use", 1.8f, 0.4f);
        broadcastNear(Component.text("Prende el suelo.", ACCENT));

        animate(70, tick -> {
            if (tick < 20) {
                Fx.telegraph(world(), c, 11.0, 0xE2703A);
                return;
            }
            double radius = (tick - 20) * 0.24;
            if (radius > 11) return;
            Fx.ring(c, radius, (int) (radius * 6) + 8, p -> {
                Location g = Fx.ground(p, 4);
                Compat.spawn(world(), Compat.FLAME, g.clone().add(0, 0.25, 0), 2, 0.1, 0.05, 0.1, 0.01);
            });
            if (tick % 6 == 0) {
                igniteArea(c, radius, 0.28);
                soundAt(c, "block.fire.ambient", 1.2f, 0.6f);
            }
            for (Player p : targets(radius + 1.0)) {
                if (p.getLocation().distance(c) < radius - 1.6) continue;
                scorch(p, 9, 80);
            }
        }, null);
    }

    /** 4. Meteoros: bolas que caen del cielo sobre marcas. */
    public void meteorShower() {
        if (!alive()) return;
        List<Player> victims = targets(30);
        if (victims.isEmpty()) return;
        soundAt(loc(), "entity.ghast.warn", 1.6f, 0.7f);
        broadcastNear(Component.text("Llama al cielo.", ACCENT));

        int count = 6 + random.nextInt(3);
        for (int i = 0; i < count; i++) {
            Player victim = victims.get(random.nextInt(victims.size()));
            Location mark = Fx.ground(victim.getLocation().clone().add(
                    (Math.random() - 0.5) * 7, 0, (Math.random() - 0.5) * 7), 5);
            later(i * 9, () -> {
                if (!alive()) return;
                animate(52, tick -> {
                    if (tick < 26) {
                        Fx.telegraph(world(), mark, 3.0, 0xFF7043);
                        Compat.spawn(world(), Compat.SMALL_FLAME, mark.clone().add(0, 0.3, 0), 2,
                                0.5, 0.1, 0.5, 0.01);
                        return;
                    }
                    if (tick < 44) {
                        // El meteoro bajando: una bola de fuego dibujada, sin entidad.
                        double h = 18 - (tick - 26) * 1.0;
                        Location at = mark.clone().add(0, h, 0);
                        Compat.spawn(world(), Compat.FLAME, at, 8, 0.3, 0.3, 0.3, 0.02);
                        Compat.spawn(world(), Compat.LAVA, at, 2, 0.2, 0.2, 0.2, 0);
                        Compat.spawn(world(), Compat.CAMPFIRE_COSY_SMOKE, at, 3, 0.3, 0.3, 0.3, 0.01);
                        return;
                    }
                    if (tick != 44) return;
                    Compat.spawn(world(), Compat.EXPLOSION_EMITTER, mark, 1);
                    Compat.spawn(world(), Compat.LAVA, mark, 20, 0.8, 0.3, 0.8, 0);
                    soundAt(mark, "entity.generic.explode", 1.6f, 0.7f);
                    soundAt(mark, "item.firecharge.use", 1.4f, 0.5f);
                    igniteArea(mark, 3, 0.5);
                    for (Player p : Fx.playersNear(mark, 4.0)) {
                        scorch(p, 16, 100);
                        push(p, p.getLocation().toVector().subtract(mark.toVector())
                                .normalize().multiply(0.9).setY(0.5));
                    }
                }, null);
            });
        }
    }

    /** 5. Muro de Fuego: una pared de llamas que avanza. */
    public void fireWall() {
        if (!alive()) return;
        Player target = randomTarget();
        if (target == null) return;
        Vector dir = target.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        final Vector run = dir.normalize();
        final Vector side = new Vector(-run.getZ(), 0, run.getX());
        java.util.Set<UUID> burnedSet = new java.util.HashSet<>();

        soundAt(loc(), "block.fire.ambient", 1.7f, 0.5f);
        broadcastNear(Component.text("Levanta un muro.", ACCENT));

        animate(60, tick -> {
            if (!alive()) throw Stop.now();
            double reach = tick * 0.35;
            if (reach > 18) throw Stop.now();
            Location center = boss.getLocation().add(0, 0.3, 0).add(run.clone().multiply(reach));
            for (double s = -5; s <= 5; s += 0.7) {
                Location at = center.clone().add(side.clone().multiply(s));
                Location g = Fx.ground(at, 4);
                for (double h = 0; h < 2.5; h += 0.5) {
                    Compat.spawn(world(), Compat.FLAME, g.clone().add(0, h, 0), 1, 0.05, 0.05, 0.05, 0.01);
                }
                if (tick % 6 == 0) ignite(at);
                for (Player p : Fx.playersNear(g, 1.6)) {
                    if (!burnedSet.add(p.getUniqueId())) continue;
                    scorch(p, 14, 120);
                    push(p, run.clone().multiply(0.6).setY(0.35));
                }
            }
            if (tick % 8 == 0) soundAt(center, "block.fire.ambient", 1.1f, 0.7f);
        }, null);
    }

    /** 6. Aliento de Ghast: un cono de fuego largo delante de el. */
    public void flameBreath() {
        if (!alive()) return;
        Location origin = boss.getEyeLocation();
        Vector face = origin.getDirection().setY(0);
        if (face.lengthSquared() < 0.01) face = new Vector(1, 0, 0);
        final Vector dir = face.normalize();
        soundAt(origin, "entity.ghast.shoot", 1.7f, 0.8f);
        broadcastNear(Component.text("Escupe fuego.", ACCENT));

        animate(50, tick -> {
            if (!alive()) throw Stop.now();
            double reach = 2 + tick * 0.3;
            if (reach > 14) return;
            Fx.arc(boss.getEyeLocation(), dir, reach, Math.PI * 0.35, (int) (reach * 4), p -> {
                Compat.spawn(world(), Compat.FLAME, p, 2, 0.15, 0.15, 0.15, 0.02);
                if (Math.random() < 0.1) Compat.spawn(world(), Compat.LAVA, p, 1, 0, 0, 0, 0);
            });
            if (tick % 10 != 0) return;
            soundAt(boss.getLocation(), "block.fire.extinguish", 1.0f, 1.3f);
            for (Player p : targets(14)) {
                Vector to = p.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
                if (to.lengthSquared() < 0.01 || to.normalize().dot(dir) < 0.6) continue;
                scorch(p, 8, 100);
            }
        }, null);
    }

    /** 7. Guardia de Blazes: llama a dos blazes que hostigan por su cuenta. */
    public void blazeGuard() {
        if (!alive()) return;
        soundAt(loc(), "entity.blaze.ambient", 1.6f, 0.8f);
        broadcastNear(Component.text("Llama a las brasas.", ACCENT));

        for (int i = 0; i < 2; i++) {
            double a = Math.PI * i + random.nextDouble();
            Location sl = boss.getLocation().clone().add(Math.cos(a) * 3, 1.5, Math.sin(a) * 3);
            later(i * 10, () -> {
                if (!alive()) return;
                try {
                    Blaze blaze = world().spawn(sl, Blaze.class, b -> {
                        b.setPersistent(false);
                        Compat.setAttribute(b, "max_health", 30);
                        b.setHealth(30);
                    });
                    blaze.customName(Component.text("Brasa", ACCENT));
                    markMinion(blaze);
                } catch (Throwable ignored) {
                }
                Compat.spawn(world(), Compat.FLAME, sl, 20, 0.4, 0.4, 0.4, 0.05);
                soundAt(sl, "entity.blaze.burn", 1.2f, 1.0f);
            });
        }
    }

    /** 8. Marca Ardiente: te pone una marca que estalla donde estes. */
    public void burningMark() {
        if (!alive()) return;
        List<Player> victims = targets(28);
        if (victims.isEmpty()) return;
        soundAt(loc(), "block.fire.ambient", 1.4f, 1.2f);
        broadcastNear(Component.text("Te marca.", ACCENT));

        for (Player victim : victims) {
            victim.sendActionBar(Component.text("Te arde una marca encima.",
                    NamedTextColor.RED, TextDecoration.BOLD));
            animate(90, tick -> {
                if (!Fx.isFightable(victim)) throw Stop.now();
                if (tick < 80) {
                    Fx.ring(victim.getLocation().add(0, 2.3, 0), 0.5, 6, tick * 0.4, p ->
                            Compat.spawn(world(), Compat.SMALL_FLAME, p, 1, 0, 0, 0, 0));
                    if (tick % 20 == 0) soundAt(victim.getLocation(), "block.fire.ambient", 0.8f, 1.6f);
                    return;
                }
                if (tick != 80) return;
                Location l = victim.getLocation();
                Compat.spawn(world(), Compat.EXPLOSION, l.clone().add(0, 1, 0), 2, 0.4, 0.4, 0.4, 0);
                Compat.spawn(world(), Compat.LAVA, l, 14, 0.6, 0.3, 0.6, 0);
                soundAt(l, "entity.generic.explode", 1.4f, 0.9f);
                igniteArea(Fx.ground(l, 4), 2.5, 0.5);
                for (Player p : Fx.playersNear(l, 4.0)) scorch(p, 15, 120);
            }, null);
        }
    }

    /** 9. Anillo de Cenizas: dos anillos que se cruzan, uno sale y otro entra. */
    public void ashRings() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 5);
        java.util.Set<UUID> outHit = new java.util.HashSet<>();
        java.util.Set<UUID> inHit = new java.util.HashSet<>();
        soundAt(c, "block.campfire.crackle", 1.6f, 0.5f);
        broadcastNear(Component.text("Cenizas.", ACCENT));

        animate(80, tick -> {
            if (tick < 18) {
                Fx.telegraph(world(), c, 12.0, 0xC1440E);
                return;
            }
            double out = (tick - 18) * 0.22;
            double in = 12 - (tick - 18) * 0.22;
            for (double radius : new double[]{out, in}) {
                if (radius < 0.5 || radius > 12) continue;
                boolean outward = radius == out;
                Fx.ring(c, radius, (int) (radius * 5) + 6, p -> {
                    Location g = Fx.ground(p, 4);
                    Compat.spawn(world(), Compat.ASH, g.clone().add(0, 0.3, 0), 2, 0.1, 0.1, 0.1, 0.01);
                    Compat.spawn(world(), Compat.FLAME, g.clone().add(0, 0.2, 0), 1, 0.05, 0.02, 0.05, 0);
                });
                for (Player p : targets(radius + 1.0)) {
                    double d = p.getLocation().distance(c);
                    if (Math.abs(d - radius) > 1.2) continue;
                    if (!(outward ? outHit : inHit).add(p.getUniqueId())) continue;
                    scorch(p, 11, 80);
                }
            }
            if (tick % 10 == 0) soundAt(c, "block.campfire.crackle", 1.0f, 0.8f);
        }, null);
    }

    /** 10. Columna de Lava: una columna que sube y revienta bajo cada uno. */
    public void lavaPillar() {
        if (!alive()) return;
        List<Player> victims = targets();
        if (victims.isEmpty()) return;
        soundAt(loc(), "block.lava.pop", 1.6f, 0.5f);

        for (Player victim : victims) {
            Location mark = Fx.ground(victim.getLocation(), 4);
            animate(56, tick -> {
                if (tick < 24) {
                    Fx.telegraph(world(), mark, 2.4, 0xFF5722);
                    Compat.spawn(world(), Compat.LAVA, mark.clone().add(0, 0.2, 0), 1, 0.3, 0.05, 0.3, 0);
                    return;
                }
                if (tick > 44) return;
                double h = (tick - 24) * 0.35;
                Fx.ring(mark.clone().add(0, h, 0), 1.2, 10, tick * 0.3, p -> {
                    Compat.spawn(world(), Compat.FLAME, p, 1, 0, 0, 0, 0.01);
                    if (Math.random() < 0.2) Compat.spawn(world(), Compat.LAVA, p, 1, 0, 0, 0, 0);
                });
                if (tick != 44) return;
                Compat.spawn(world(), Compat.EXPLOSION, mark.clone().add(0, 1, 0), 1, 0.3, 0.5, 0.3, 0);
                soundAt(mark, "block.lava.extinguish", 1.5f, 0.6f);
                igniteArea(mark, 2, 0.6);
                for (Player p : Fx.playersNear(mark, 2.6)) {
                    scorch(p, 14, 140);
                    push(p, new Vector(0, 0.7, 0));
                }
            }, null);
        }
    }

    /** 11. Nova Ignea: se enciende entero y revienta en catorce bloques. */
    public void igniteNova() {
        if (!alive()) return;
        final Location center = boss.getLocation().clone();
        soundAt(center, "entity.blaze.burn", 1.8f, 0.5f);
        titleNear(Component.text("NOVA IGNEA", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Alejense de el", NamedTextColor.GRAY));

        animate(110, tick -> {
            if (!alive()) throw Stop.now();
            Location l = boss.getLocation().add(0, 1, 0);
            double t = tick / 110.0;
            Fx.sphere(l, 1.0 + t * 2.5, (int) (14 + t * 30), p -> {
                Compat.spawn(world(), Compat.FLAME, p, 1, 0, 0, 0, 0);
                if (Math.random() < 0.08) Compat.spawn(world(), Compat.SOUL_FIRE_FLAME, p, 1, 0, 0, 0, 0);
            });
            if (tick % 20 == 0) {
                soundAt(l, "block.fire.ambient", 1.3f, 0.4f + (float) t);
                for (Player p : Fx.viewersNear(l, 40)) {
                    boolean safe = p.getLocation().distance(boss.getLocation()) > 14;
                    p.sendActionBar(Component.text("Nova  ", NamedTextColor.GRAY)
                            .append(Component.text(((110 - tick) / 20) + "s", ACCENT, TextDecoration.BOLD))
                            .append(Component.text(safe ? "   estas fuera" : "   ESTAS DENTRO",
                                    safe ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD)));
                }
            }
        }, () -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            Compat.spawn(world(), Compat.FLASH, l.clone().add(0, 1, 0), 1);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l.clone().add(0, 1, 0), 4);
            Compat.spawn(world(), Compat.LAVA, l.clone().add(0, 1, 0), 60, 2.0, 0.8, 2.0, 0);
            soundAt(l, "entity.generic.explode", 2.0f, 0.4f);
            soundAt(l, "item.firecharge.use", 1.8f, 0.3f);
            igniteArea(Fx.ground(l, 5), 10, 0.45);
            for (Player p : Fx.playersNear(l, 14)) {
                double d = p.getLocation().distance(l);
                scorch(p, Math.max(12, 40 - d * 2.0), 160);
                push(p, p.getLocation().toVector().subtract(l.toVector())
                        .normalize().multiply(1.5).setY(0.8));
            }
        });
    }

    /** 12. Rastro de Brasas: por donde pisa deja fuego un rato. */
    public void emberTrail() {
        if (!alive()) return;
        soundAt(loc(), "block.fire.ambient", 1.3f, 1.1f);
        broadcastNear(Component.text("Deja brasas por donde pisa.", ACCENT));

        animate(160, tick -> {
            if (!alive()) throw Stop.now();
            if (tick % 4 != 0) return;
            Location l = Fx.ground(boss.getLocation(), 4);
            ignite(l.clone().add(0, 1, 0));
            Compat.spawn(world(), Compat.FLAME, l.clone().add(0, 0.2, 0), 4, 0.3, 0.05, 0.3, 0.01);
            for (Player p : Fx.playersNear(l, 2.0)) scorch(p, 5, 60);
        }, null);
    }

    // ------------------------------------------------------------------ mensajeria

    private void broadcastNear(Component message) {
        Component line = Component.text("✦ ", ACCENT)
                .append(Component.text("El Piromante  ", ACCENT, TextDecoration.BOLD))
                .append(message.colorIfAbsent(NamedTextColor.GRAY));
        for (Player p : Fx.viewersNear(loc(), 90)) p.sendActionBar(line);
    }

    private void titleNear(Component title, Component subtitle) {
        for (Player p : Fx.viewersNear(loc(), 90)) {
            p.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1400), Duration.ofMillis(500))));
        }
    }
}
