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
import org.bukkit.Particle;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.SkeletonHorse;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * EL CABALLERO SEPULCRAL, la primera anomalia.
 *
 * Un esqueleto con armadura de netherita, lanza de netherita y montado en un caballo
 * esqueleto. Pelea en tres fases: montado, a pie y como heraldo, y cada fase cambia
 * su repertorio entero.
 *
 * La regla de las animaciones que ya regia en Rip vale aqui: el enfriamiento de cada
 * habilidad nunca es menor que lo que dura su animacion, para que no se solapen dos.
 */
public final class SepulchralKnight extends BossFight {

    public static final String ID = "caballero_sepulcral";
    public static final TextColor ACCENT = TextColor.color(0x9BD7E4);

    private static final int BONE = 0xE8E2D0;
    private static final int SPECTRAL = 0x7FE9E0;
    private static final int RUST = 0xB3543A;
    private static final int VOID_PURPLE = 0x5A3E7A;

    private SkeletonHorse mount;
    private boolean dismounted;
    private boolean lastStandDone;
    private double damageBonus = 1.0;
    private double vulnerability = 1.0;

    public SepulchralKnight(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        super(plugin, event, where);
        abilities.addAll(plugin.registry().knightAbilities());
    }

    @Override
    public String bossName() {
        return "Caballero Sepulcral";
    }

    /** Cuanto dano extra hace ahora mismo (sube si sobrevive a la resurreccion). */
    public double damageBonus() {
        return damageBonus;
    }

    /** Multiplicador de dano RECIBIDO. Sube si le rompen las anclas. */
    @Override
    public double incomingDamageMultiplier() {
        return vulnerability;
    }

    // ------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        Location spot = arena.clone();

        mount = world().spawn(spot, SkeletonHorse.class, h -> {
            h.setAdult();
            h.setTamed(true);
            h.setPersistent(true);
            h.setRemoveWhenFarAway(false);
            h.setInvulnerable(true);
            h.customName(Component.text("Montura del Caballero", ACCENT));
            h.setCustomNameVisible(false);
            try {
                h.getInventory().setSaddle(new ItemStack(Material.SADDLE));
            } catch (Throwable ignored) {
                // algunas versiones no dejan ensillar por API; es solo estetico
            }
            Compat.setAttribute(h, "max_health", 120);
            Compat.setAttribute(h, "movement_speed", 0.32);
            Compat.setAttribute(h, "jump_strength", 0.9);
            h.setHealth(120);
        });
        markMinion(mount);

        boss = world().spawn(spot, Skeleton.class, s -> {
            s.setPersistent(true);
            s.setRemoveWhenFarAway(false);
            s.setCanPickupItems(false);
            s.setGlowing(true);
            s.customName(Component.text("✦ ", ACCENT)
                    .append(Component.text("Caballero Sepulcral", ACCENT, TextDecoration.BOLD)));
            s.setCustomNameVisible(true);
            dressUp(s.getEquipment());
        });

        Compat.setAttribute(boss, "attack_damage", 11);
        Compat.setAttribute(boss, "armor", 20);
        Compat.setAttribute(boss, "armor_toughness", 8);
        Compat.setAttribute(boss, "knockback_resistance", 1.0);
        Compat.setAttribute(boss, "follow_range", 60);
        Compat.setAttribute(boss, "movement_speed", 0.26);
        applyHealth(plugin.registry().scaledHealth(plugin.registry().knight(), targets(96).size()));
        // Con la invulnerabilidad vanilla de 20 ticks solo pega uno; bajarla es lo que
        // hace que un grupo pueda repartirse el trabajo de verdad.
        boss.setMaximumNoDamageTicks(6);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        mount.addPassenger(boss);

        // El brillo rojo es la marca de esta anomalia y, en la practica, la forma de
        // encontrarla: el contorno se ve a traves del terreno y a mucha mas distancia
        // que cualquier particula.
        Glow.apply(boss, event.type().glowColor());
        Glow.apply(mount, event.type().glowColor());

        arrivalAnimation(spot);
    }

    private void dressUp(EntityEquipment eq) {
        if (eq == null) return;
        eq.setHelmet(named(Material.NETHERITE_HELMET, "Yelmo del Juramento"));
        eq.setChestplate(named(Material.NETHERITE_CHESTPLATE, "Peto del Juramento"));
        eq.setLeggings(named(Material.NETHERITE_LEGGINGS, "Grebas del Juramento"));
        eq.setBoots(named(Material.NETHERITE_BOOTS, "Espuelas del Juramento"));
        eq.setItemInMainHand(spear());
        eq.setHelmetDropChance(0);
        eq.setChestplateDropChance(0);
        eq.setLeggingsDropChance(0);
        eq.setBootsDropChance(0);
        eq.setItemInMainHandDropChance(0);
    }

    /** La lanza es el arma nueva de la version; si no existe, cae al tridente. */
    public static ItemStack spear() {
        Material m = Material.matchMaterial("NETHERITE_SPEAR");
        if (m == null) m = Material.matchMaterial("TRIDENT");
        if (m == null) m = Material.IRON_SWORD;
        return named(m, "Lanza del Paramo");
    }

    private static ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, ACCENT).decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** La llegada: una grieta que se abre y escupe al jinete. */
    private void arrivalAnimation(Location spot) {
        boss.setInvulnerable(true);
        busyFor(70);
        soundAt(spot, "entity.wither.spawn", 1.0f, 0.55f);
        soundAt(spot, "block.beacon.deactivate", 1.0f, 0.4f);

        animate(70, tick -> {
            double t = tick / 70.0;
            double radius = 1.2 + t * 7.0;
            Fx.ring(spot, radius, (int) (18 + radius * 4), tick * 0.18, l -> {
                Compat.spawn(world(), Compat.ASH, Fx.ground(l, 3).add(0, 0.15, 0), 1, 0, 0, 0, 0,
                        Compat.dust(SPECTRAL, 1.5f));
            });
            if (tick % 4 == 0) {
                Fx.helix(spot, 1.4, 4.5, 24, 2.0, l ->
                        Compat.spawn(world(), Compat.SOUL_FIRE_FLAME, l, 1, 0.02, 0.02, 0.02, 0.001));
            }
            if (tick % 12 == 0) {
                soundAt(spot, "block.soul_sand.break", 1.0f, 0.5f + (float) t);
                Compat.spawn(world(), Compat.SCULK_SOUL, spot.clone().add(0, 1, 0), 20, 1.5, 1.0, 1.5, 0.02);
            }
            if (tick == 55) {
                soundAt(spot, "entity.skeleton_horse.ambient", 1.4f, 0.6f);
                soundAt(spot, "entity.horse.angry", 1.2f, 0.5f);
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, spot, 1);
            Compat.spawn(world(), Compat.FLASH, spot.clone().add(0, 1, 0), 1);
            soundAt(spot, "entity.ender_dragon.growl", 1.4f, 0.7f);
            for (Player p : Fx.viewersNear(spot, 80)) {
                p.showTitle(Title.title(
                        Component.text("✦ ANOMALIA ✦", ACCENT, TextDecoration.BOLD),
                        Component.text("El Caballero Sepulcral ha cruzado", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1600), Duration.ofMillis(600))));
            }
        });
    }

    // ------------------------------------------------------------------- ambiente

    @Override
    protected void ambient() {
        if (ticks() % 3 != 0 || !alive()) return;
        Location l = boss.getLocation().add(0, 1.1, 0);
        Compat.spawn(world(), Compat.WHITE_ASH, l, 2, 0.35, 0.5, 0.35, 0, Compat.dust(SPECTRAL, 0.9f));
        if (ticks() % 30 == 0) {
            Compat.spawn(world(), Compat.SOUL, l, 4, 0.4, 0.6, 0.4, 0.005);
        }
        if (phase() == 3 && ticks() % 10 == 0) {
            Compat.spawn(world(), Compat.SOUL_FIRE_FLAME, l, 3, 0.4, 0.6, 0.4, 0.01);
        }
    }

    // --------------------------------------------------------------- cambio de fase

    @Override
    protected void onPhaseChange(int from, int to) {
        if (event.bars() != null) event.bars().flash(from);
        if (to == 2) dismount();
        if (to == 3) failedResurrection();
    }

    /**
     * FASE I -> II. El caballo se encabrita, se deshace en huesos y el caballero
     * cae al suelo de pie. Mientras dura no se le puede tocar.
     */
    private void dismount() {
        if (dismounted || !alive()) return;
        dismounted = true;
        boss.setInvulnerable(true);
        busyFor(90);

        Location spot = boss.getLocation();
        soundAt(spot, "entity.horse.death", 1.5f, 0.6f);
        soundAt(spot, "entity.ender_dragon.flap", 1.2f, 0.5f);
        broadcastNear(Component.text("La montura se deshace.", ACCENT));

        animate(90, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            if (tick < 30 && mount != null && mount.isValid()) {
                // encabritado: el caballo tiembla y suelta polvo de hueso
                mount.setVelocity(new Vector(0, 0.06, 0));
                Compat.spawn(world(), Compat.SOUL, l.clone().add(0, 0.6, 0), 6, 0.6, 0.4, 0.6, 0,
                        Compat.dust(BONE, 1.2f));
                if (tick % 6 == 0) soundAt(l, "entity.skeleton.hurt", 1.0f, 0.6f + tick / 60f);
            }
            if (tick == 30) {
                if (mount != null && mount.isValid()) {
                    mount.eject();
                    Location m = mount.getLocation();
                    Compat.spawn(world(), Compat.EXPLOSION, m, 3, 0.6, 0.5, 0.6, 0);
                    Compat.spawn(world(), Compat.ITEM, m.clone().add(0, 0.8, 0), 90, 0.7, 0.7, 0.7, 0.25,
                            new ItemStack(Material.BONE));
                    soundAt(m, "entity.skeleton_horse.death", 1.6f, 0.7f);
                    soundAt(m, "block.bone_block.break", 1.4f, 0.5f);
                    spawned.remove(mount);
                    Fx.safeRemove(mount);
                    mount = null;
                }
            }
            if (tick > 30 && tick < 70) {
                // el caballero baja girando la lanza
                double t = (tick - 30) / 40.0;
                Fx.ring(l.clone().add(0, 1.0, 0), 1.6 - t, 14, tick * 0.4, p ->
                        Compat.spawn(world(), Compat.ENCHANTED_HIT, p, 1, 0, 0, 0, 0));
                if (tick % 5 == 0) soundAt(l, "item.trident.riptide_1", 0.7f, 1.4f);
            }
            if (tick == 70) {
                Location g = Fx.ground(l, 4);
                Compat.spawn(world(), Compat.EXPLOSION_EMITTER, g, 1);
                Compat.spawn(world(), Compat.BLOCK, g, 120, 2.5, 0.2, 2.5, 0.1,
                        Material.DEEPSLATE.createBlockData());
                soundAt(g, "entity.generic.explode", 1.4f, 0.6f);
                soundAt(g, "block.anvil_land", 1.2f, 0.5f);
                for (Player p : targets(9)) {
                    hit(p, 8 * damageBonus);
                    push(p, p.getLocation().toVector().subtract(g.toVector()).normalize().setY(0.45).multiply(0.9));
                }
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            Compat.setAttribute(boss, "movement_speed", 0.33);
            Compat.setAttribute(boss, "attack_damage", 13);
            titleNear(Component.text("FASE II", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("El Caballero pelea a pie", NamedTextColor.GRAY));
            soundAt(boss.getLocation(), "entity.wither.spawn", 1.2f, 1.2f);
        });
    }

    /**
     * FASE II -> III. Intenta rehacerse: tres anclas de hueso lo sostienen mientras
     * es invulnerable. Si el grupo las rompe a tiempo, pierde la armadura y recibe
     * un 30% mas de dano; si no, se levanta pegando un 35% mas fuerte.
     *
     * A proposito NO se cura: curarse le devolveria a la fase 2 y el combate entraria
     * en bucle.
     */
    private void failedResurrection() {
        if (lastStandDone || !alive()) return;
        lastStandDone = true;
        boss.setInvulnerable(true);
        busyFor(260);

        Location spot = boss.getLocation();
        titleNear(Component.text("SE ESTA REHACIENDO", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Rompan las tres anclas de hueso", NamedTextColor.GRAY));
        soundAt(spot, "entity.wither.spawn", 1.6f, 0.5f);
        soundAt(spot, "block.beacon.activate", 1.4f, 0.4f);

        List<Entity> anchorList = new ArrayList<>();
        final int[] broken = {0};
        for (int i = 0; i < 3; i++) {
            double a = Math.PI * 2 * i / 3.0;
            Location al = Fx.ground(spot.clone().add(Math.cos(a) * 7, 1, Math.sin(a) * 7), 5);
            ArmorStand stand = world().spawn(al, ArmorStand.class, s -> {
                s.setInvisible(true);
                s.setGravity(false);
                s.setInvulnerable(false);
                s.setPersistent(false);
                s.setBasePlate(false);
                s.setArms(false);
                s.customName(Component.text("Ancla de Hueso", NamedTextColor.WHITE, TextDecoration.BOLD));
                s.setCustomNameVisible(true);
                EntityEquipment eq = s.getEquipment();
                if (eq != null) eq.setHelmet(new ItemStack(Material.BONE_BLOCK));
            });
            markMinion(stand);
            anchorList.add(stand);
            plugin.anchors().register(stand, 6,
                    () -> {
                        Compat.spawn(world(), Compat.ITEM, stand.getLocation().add(0, 1.6, 0), 20,
                                0.3, 0.3, 0.3, 0.1, new ItemStack(Material.BONE));
                        soundAt(stand.getLocation(), "block.bone_block.hit", 1.0f, 0.8f);
                    },
                    () -> {
                        broken[0]++;
                        Location bl = stand.getLocation();
                        Compat.spawn(world(), Compat.EXPLOSION, bl.clone().add(0, 1.4, 0), 2, 0.3, 0.3, 0.3, 0);
                        soundAt(bl, "block.bone_block.break", 1.4f, 0.6f);
                        soundAt(bl, "entity.wither.hurt", 1.0f, 1.4f);
                        broadcastNear(Component.text("Ancla rota  " + broken[0] + "/3", NamedTextColor.GREEN));
                    });
        }

        animate(260, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            Compat.spawn(world(), Compat.SOUL, l.clone().add(0, 1, 0), 4, 0.4, 0.8, 0.4, 0.02);
            for (Entity anchor : anchorList) {
                if (!anchor.isValid()) continue;
                Fx.beam(anchor.getLocation().add(0, 1.6, 0), l.clone().add(0, 1.2, 0), 0.55, p ->
                        Compat.spawn(world(), Compat.SCRAPE, p, 1, 0, 0, 0, 0, Compat.dust(SPECTRAL, 1.0f)));
            }
            if (tick % 20 == 0) {
                int left = 3 - broken[0];
                warn(Component.text("Anclas en pie  ", NamedTextColor.GRAY)
                        .append(Component.text(left + "/3", left == 0 ? NamedTextColor.GREEN : NamedTextColor.RED,
                                TextDecoration.BOLD))
                        .append(Component.text("   ·   " + Math.max(0, (260 - tick) / 20) + "s", NamedTextColor.DARK_GRAY)));
                soundAt(l, "block.beacon.ambient", 0.8f, 0.6f);
            }
            if (broken[0] >= 3) throw Stop.now();
        }, () -> finishResurrection(anchorList, broken[0] >= 3));
    }

    private void finishResurrection(List<Entity> anchorList, boolean playersWon) {
        for (Entity e : anchorList) {
            plugin.anchors().forget(e);
            spawned.remove(e);
            Fx.safeRemove(e);
        }
        if (!alive()) return;
        boss.setInvulnerable(false);
        Location l = boss.getLocation();

        if (playersWon) {
            vulnerability = 1.30;
            EntityEquipment eq = boss.getEquipment();
            if (eq != null) {
                eq.setChestplate(null);
                eq.setLeggings(null);
            }
            Compat.setAttribute(boss, "armor", 6);
            Compat.spawn(world(), Compat.ITEM, l.clone().add(0, 1.2, 0), 80, 0.6, 0.8, 0.6, 0.2,
                    new ItemStack(Material.NETHERITE_SCRAP));
            soundAt(l, "item.shield.break", 1.6f, 0.6f);
            soundAt(l, "block.anvil_destroy", 1.2f, 0.8f);
            titleNear(Component.text("ARMADURA ROTA", NamedTextColor.GREEN, TextDecoration.BOLD),
                    Component.text("Recibe un 30% mas de dano", NamedTextColor.GRAY));
        } else {
            damageBonus = 1.35;
            Compat.setAttribute(boss, "attack_damage", 17);
            Compat.setAttribute(boss, "movement_speed", 0.36);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l.clone().add(0, 1, 0), 2);
            soundAt(l, "entity.ender_dragon.growl", 1.6f, 0.5f);
            titleNear(Component.text("SE HA REHECHO", NamedTextColor.RED, TextDecoration.BOLD),
                    Component.text("Golpea un 35% mas fuerte", NamedTextColor.GRAY));
        }
        soundAt(l, "entity.wither.spawn", 1.4f, 0.9f);
    }

    // ---------------------------------------------------------------------- muerte

    @Override
    public void onDeath() {
        Location l = loc();
        soundAt(l, "entity.wither.death", 1.6f, 0.7f);
        soundAt(l, "entity.ender_dragon.death", 1.0f, 1.2f);

        animate(80, tick -> {
            double t = tick / 80.0;
            // la armadura se desprende pieza a pieza
            if (tick % 16 == 0) {
                Material[] pieces = {Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
                        Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS, Material.NETHERITE_SCRAP};
                Material piece = pieces[(tick / 16) % pieces.length];
                ItemDisplay d = Fx.itemDisplay(world(), l.clone().add(
                        (Math.random() - 0.5) * 1.4, 1.2, (Math.random() - 0.5) * 1.4), new ItemStack(piece), 0.7f);
                track(d);
                expire(d, 60);
                soundAt(l, "item.armor.equip_netherite", 1.0f, 0.7f);
            }
            Fx.ring(l.clone().add(0, 0.2 + t * 2.4, 0), 2.2 * (1 - t) + 0.4, 22, tick * 0.25, p ->
                    Compat.spawn(world(), Compat.SOUL_FIRE_FLAME, p, 1, 0, 0.02, 0, 0.002));
            if (tick % 8 == 0) {
                Compat.spawn(world(), Compat.SOUL, l.clone().add(0, 1, 0), 12, 0.5, 0.8, 0.5, 0.05);
                soundAt(l, "block.soul_sand.break", 0.9f, 0.5f + (float) t);
            }
        }, () -> {
            // la grieta se cierra
            Compat.spawn(world(), Compat.FLASH, l.clone().add(0, 1, 0), 1);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l, 1);
            Compat.spawn(world(), Compat.REVERSE_PORTAL, l.clone().add(0, 1, 0), 160, 0.8, 1.4, 0.8, 0.4);
            soundAt(l, "block.beacon.deactivate", 1.4f, 0.5f);
            soundAt(l, "entity.ender_dragon.growl", 0.8f, 1.6f);
        });
    }

    // ============================================================== HABILIDADES ==
    // Cada una se declara en AnomalyRegistry con su fase, enfriamiento y duracion,
    // y aqui vive solo la animacion. El orden es el mismo que el del menu.

    // ------------------------------------------------------------- FASE I: montado

    /** 1. Carga de Lanza: marca un pasillo, galopa por el y arrolla lo que pille. */
    public void lanceCharge() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        Location start = boss.getLocation();
        Vector dir = target.getLocation().toVector().subtract(start.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        dir.normalize();

        soundAt(start, "entity.horse.angry", 1.4f, 0.7f);
        soundAt(start, "item.trident.riptide_3", 1.2f, 0.6f);
        broadcastNear(Component.text("Baja la lanza.", ACCENT));

        // aviso: el pasillo se pinta 25 ticks antes de que arranque
        animate(25, tick -> {
            for (double d = 2; d < 26; d += 1.0) {
                Location l = Fx.ground(start.clone().add(dir.clone().multiply(d)), 4);
                Compat.spawn(world(), Compat.CRIT, l.clone().add(0, 0.15, 0), 1, 1.1, 0, 1.1, 0,
                        Compat.dust(RUST, 1.3f));
            }
            if (tick % 8 == 0) soundAt(start, "block.note_block.bass", 1.0f, 0.5f);
        }, () -> {
            if (!alive()) return;
            soundAt(boss.getLocation(), "entity.horse.gallop", 1.6f, 0.8f);
            animate(40, tick -> {
                if (!alive()) return;
                Entity mover = boss.getVehicle() != null ? boss.getVehicle() : boss;
                mover.setVelocity(dir.clone().multiply(1.15).setY(mover.getVelocity().getY()));
                Location l = boss.getLocation();
                Compat.spawn(world(), Compat.SOUL_FIRE_FLAME, l.clone().add(0, 0.3, 0), 6, 0.4, 0.3, 0.4, 0,
                        Compat.dust(BONE, 1.4f));
                Compat.spawn(world(), Compat.SWEEP_ATTACK, l.clone().add(dir.clone().multiply(1.6)).add(0, 1, 0), 1);
                if (tick % 5 == 0) soundAt(l, "entity.horse.gallop", 1.1f, 0.9f);
                for (Player p : targets(3.2)) {
                    hit(p, 12 * damageBonus);
                    push(p, dir.clone().multiply(1.1).setY(0.4));
                    Compat.spawn(world(), Compat.CRIT, p.getLocation().add(0, 1, 0), 14, 0.3, 0.4, 0.3, 0.2);
                    soundAt(p.getLocation(), "item.trident.hit", 1.2f, 0.9f);
                }
            }, () -> soundAt(loc(), "entity.horse.breathe", 1.0f, 0.8f));
        });
    }

    /** 2. Barrido de Guadana: tres barridos concentricos de la lanza. */
    public void scytheSweep() {
        if (!alive()) return;
        Location c = boss.getLocation();
        soundAt(c, "item.trident.riptide_2", 1.2f, 0.8f);

        animate(40, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1.0, 0);
            if (tick % 12 != 0) {
                double r = 1.0 + (tick % 12) * 0.5;
                Fx.ring(l, r, (int) (12 + r * 5), tick * 0.5, p ->
                        Compat.spawn(world(), Compat.SWEEP_ATTACK, p, 1));
                return;
            }
            int wave = tick / 12;
            double radius = 4.0 + wave * 1.6;
            soundAt(l, "entity.player.attack.sweep", 1.4f, 0.9f - wave * 0.1f);
            soundAt(l, "item.trident.return", 1.0f, 1.2f);
            Fx.ring(l, radius, (int) (radius * 9), p -> {
                Compat.spawn(world(), Compat.SWEEP_ATTACK, p, 1);
                Compat.spawn(world(), Compat.ASH, p, 1, 0, 0, 0, 0, Compat.dust(BONE, 1.1f));
            });
            for (Player p : targets(radius)) {
                double d = p.getLocation().distance(l);
                if (d < radius - 2.2) continue;
                hit(p, (9 - wave) * damageBonus);
                push(p, p.getLocation().toVector().subtract(l.toVector()).normalize().setY(0.28).multiply(0.55));
            }
        }, null);
    }

    /**
     * 3. Pisoton de la Montura: el caballo se encabrita y descarga los cascos.
     * La onda sale del suelo, no de ningun arma: aqui todo es peso y golpe.
     */
    public void hoofSlam() {
        if (!alive()) return;
        Set<UUID> struck = new HashSet<>();
        soundAt(boss.getLocation(), "entity.horse.angry", 1.4f, 0.6f);
        broadcastNear(Component.text("Encabrita la montura.", ACCENT));

        animate(70, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            if (tick < 25) {
                Entity mover = boss.getVehicle() != null ? boss.getVehicle() : boss;
                mover.setVelocity(new Vector(0, 0.08, 0));
                Compat.spawn(world(), Compat.WHITE_ASH, l.clone().add(0, 0.4, 0), 5, 0.5, 0.3, 0.5, 0,
                        Compat.dust(BONE, 1.2f));
                Fx.telegraph(world(), Fx.ground(l, 4), 7.0, RUST);
                if (tick % 6 == 0) soundAt(l, "entity.horse.breathe", 1.1f, 0.5f);
                return;
            }
            if (tick == 25) {
                Location g = Fx.ground(l, 4);
                Compat.spawn(world(), Compat.EXPLOSION_EMITTER, g, 1);
                Compat.spawn(world(), Compat.BLOCK, g, 140, 2.0, 0.3, 2.0, 0.2, groundBlock(g));
                soundAt(g, "entity.generic.explode", 1.5f, 0.55f);
                soundAt(g, "block.anvil_land", 1.4f, 0.5f);
                soundAt(g, "entity.ravager.stunned", 1.2f, 0.7f);
                return;
            }
            double radius = (tick - 25) * 0.42;
            if (radius > 9) return;
            Location g = Fx.ground(boss.getLocation(), 4);
            Fx.shockwave(world(), g, radius, Compat.CLOUD, 8);
            Fx.ring(g, radius, (int) (radius * 8) + 6, pt -> Compat.spawn(world(), Compat.BLOCK,
                    Fx.ground(pt, 3).add(0, 0.25, 0), 2, 0.15, 0.1, 0.15, 0.05, groundBlock(g)));
            for (Player p : targets(radius + 1.0)) {
                if (p.getLocation().distance(g) < radius - 1.4) continue;
                // Sin esto la onda golpearia al mismo jugador un tick tras otro mientras
                // el anillo lo atraviesa, y de un solo pisoton se moriria.
                if (!struck.add(p.getUniqueId())) continue;
                hit(p, 13 * damageBonus);
                push(p, p.getLocation().toVector().subtract(g.toVector()).normalize().setY(0.5).multiply(0.8));
                soundAt(p.getLocation(), "entity.player.attack.crit", 1.2f, 0.7f);
            }
        }, null);
    }

    /** 4. Estandarte de Guerra: planta un estandarte que lo protege mientras siga en pie. */
    public void warBanner() {
        if (!alive()) return;
        Location spot = Fx.ground(boss.getLocation().add((Math.random() - 0.5) * 5, 1, (Math.random() - 0.5) * 5), 5);

        ArmorStand banner = world().spawn(spot, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setGravity(false);
            s.setPersistent(false);
            s.setBasePlate(false);
            s.customName(Component.text("Estandarte de Guerra", ACCENT, TextDecoration.BOLD));
            s.setCustomNameVisible(true);
            EntityEquipment eq = s.getEquipment();
            if (eq != null) eq.setHelmet(new ItemStack(Material.BLACK_BANNER));
        });
        markMinion(banner);
        soundAt(spot, "block.wool.place", 1.4f, 0.6f);
        soundAt(spot, "event.raid.horn", 0.7f, 1.4f);
        broadcastNear(Component.text("Planta el estandarte. Derribenlo.", ACCENT));

        final boolean[] down = {false};
        plugin.anchors().register(banner, 8,
                () -> {
                    soundAt(banner.getLocation(), "block.wool.hit", 1.0f, 0.9f);
                    Compat.spawn(world(), Compat.ITEM, banner.getLocation().add(0, 1.7, 0), 12, 0.2, 0.2, 0.2, 0.05,
                            new ItemStack(Material.BLACK_WOOL));
                },
                () -> {
                    down[0] = true;
                    soundAt(banner.getLocation(), "entity.item.break", 1.4f, 0.7f);
                    broadcastNear(Component.text("Estandarte derribado.", NamedTextColor.GREEN));
                });

        animate(300, tick -> {
            if (!banner.isValid() || down[0]) throw Stop.now();
            Location bl = banner.getLocation().add(0, 1.9, 0);
            Fx.ring(bl, 0.6, 8, tick * 0.3, p ->
                    Compat.spawn(world(), Compat.SOUL, p, 1, 0, 0, 0, 0, Compat.dust(RUST, 1.0f)));
            if (!alive()) return;
            if (boss.getLocation().distanceSquared(banner.getLocation()) < 15 * 15) {
                Compat.apply(boss, "resistance", 25, 1);
                Compat.apply(boss, "speed", 25, 0);
                if (tick % 10 == 0) {
                    Fx.beam(bl, boss.getLocation().add(0, 1.2, 0), 0.6, p ->
                            Compat.spawn(world(), Compat.SCRAPE, p, 1, 0, 0, 0, 0, Compat.dust(RUST, 0.8f)));
                }
            }
            if (tick % 40 == 0) soundAt(bl, "block.beacon.ambient", 0.6f, 0.7f);
        }, () -> {
            plugin.anchors().forget(banner);
            spawned.remove(banner);
            Fx.safeRemove(banner);
        });
    }

    /** 5. Relincho Aterrador: un cono de miedo que ciega y frena. */
    public void terrifyingNeigh() {
        if (!alive()) return;
        Location c = boss.getLocation().add(0, 1.2, 0);
        Vector facing = boss.getLocation().getDirection().setY(0).normalize();

        soundAt(c, "entity.horse.death", 1.8f, 0.5f);
        soundAt(c, "entity.ender_dragon.growl", 1.2f, 1.4f);

        animate(50, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1.2, 0);
            double radius = 1.5 + tick * 0.36;
            Fx.arc(l, facing, radius, Math.toRadians(110), (int) (10 + radius * 3), p -> {
                Compat.spawn(world(), Compat.CLOUD, p, 1, 0.1, 0.1, 0.1, 0.01);
                Compat.spawn(world(), Compat.CRIT, p, 1, 0, 0, 0, 0, Compat.dust(VOID_PURPLE, 1.6f));
            });
            if (tick % 10 == 0) soundAt(l, "entity.warden.roar", 0.6f, 1.6f);
            if (tick != 20) return;
            for (Player p : targets(14)) {
                Vector to = p.getLocation().toVector().subtract(l.toVector()).setY(0);
                if (to.lengthSquared() < 0.01 || to.normalize().dot(facing) < 0.35) continue;
                hit(p, 5 * damageBonus);
                Compat.apply(p, "blindness", 80, 0);
                Compat.apply(p, "slowness", 120, 1);
                Compat.apply(p, "nausea", 100, 0);
                soundAt(p.getLocation(), "entity.horse.death", 1.0f, 0.6f);
            }
        }, null);
    }

    /** 6. Llamada de Jinetes: dos a cuatro jinetes menores salen del suelo. */
    public void ridersCall() {
        if (!alive()) return;
        int count = 2 + random.nextInt(3);
        Location c = boss.getLocation();
        soundAt(c, "event.raid.horn", 1.6f, 0.8f);
        broadcastNear(Component.text("Llama a sus jinetes.", ACCENT));

        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count;
            Location sl = Fx.ground(c.clone().add(Math.cos(a) * 6, 1, Math.sin(a) * 6), 5);
            later(i * 8, () -> {
                if (!alive()) return;
                Compat.spawn(world(), Compat.EXPLOSION, sl, 1);
                Compat.spawn(world(), Compat.BLOCK, sl, 50, 0.7, 0.2, 0.7, 0.1,
                        Material.DIRT.createBlockData());
                soundAt(sl, "entity.skeleton_horse.ambient", 1.2f, 1.0f);

                SkeletonHorse h = world().spawn(sl, SkeletonHorse.class, e -> {
                    e.setAdult();
                    e.setTamed(true);
                    e.setPersistent(false);
                    Compat.setAttribute(e, "max_health", 30);
                    e.setHealth(30);
                });
                Skeleton rider = world().spawn(sl, Skeleton.class, e -> {
                    e.setPersistent(false);
                    e.customName(Component.text("Jinete del Paramo", TextColor.color(0xA9C4CC)));
                    e.setCustomNameVisible(false);
                    EntityEquipment eq = e.getEquipment();
                    if (eq != null) {
                        eq.setHelmet(new ItemStack(Material.IRON_HELMET));
                        eq.setItemInMainHand(new ItemStack(Material.BOW));
                        eq.setHelmetDropChance(0);
                        eq.setItemInMainHandDropChance(0);
                    }
                    Compat.setAttribute(e, "max_health", 26);
                    e.setHealth(26);
                });
                h.addPassenger(rider);
                markMinion(h);
                markMinion(rider);
            });
        }
    }

    // ---------------------------------------------------------------- FASE II: a pie

    /** 7. Estocada Fantasma: se teletransporta a la espalda del mas lejano y estoca. */
    public void phantomThrust() {
        Player target = Fx.farthest(loc(), plugin.settings().participationRadius());
        if (target == null || !alive()) return;

        Location from = boss.getLocation();
        Vector behind = target.getLocation().getDirection().setY(0).normalize().multiply(-2.2);
        Location to = Fx.ground(target.getLocation().add(behind), 3);

        soundAt(from, "entity.ravager.step", 1.4f, 0.9f);
        Compat.spawn(world(), Compat.LARGE_SMOKE, from.clone().add(0, 1, 0), 40, 0.4, 0.9, 0.4, 0.06);
        Fx.beam(from.clone().add(0, 1, 0), to.clone().add(0, 1, 0), 0.4, p ->
                Compat.spawn(world(), Compat.SOUL_FIRE_FLAME, p, 2, 0.05, 0.05, 0.05, 0, Compat.dust(BONE, 1.2f)));

        boss.teleport(to);
        soundAt(to, "entity.player.attack.sweep", 1.4f, 0.7f);

        animate(45, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1.2, 0);
            if (tick < 18) {
                // carga la estocada: la lanza se echa atras
                Fx.ring(l, 1.2 - tick * 0.05, 10, tick * 0.6, p ->
                        Compat.spawn(world(), Compat.ENCHANTED_HIT, p, 1));
                if (tick % 6 == 0) soundAt(l, "block.note_block.hat", 1.0f, 1.6f);
                return;
            }
            if (tick != 18) return;
            Vector dir = boss.getLocation().getDirection().setY(0).normalize();
            soundAt(l, "item.trident.riptide_1", 1.4f, 1.2f);
            soundAt(l, "item.trident.hit", 1.4f, 0.9f);
            for (double d = 0.5; d <= 5.0; d += 0.4) {
                Location p = l.clone().add(dir.clone().multiply(d));
                Compat.spawn(world(), Compat.CRIT, p, 6, 0.12, 0.12, 0.12, 0.08);
                Compat.spawn(world(), Compat.ASH, p, 2, 0.1, 0.1, 0.1, 0, Compat.dust(SPECTRAL, 1.2f));
            }
            for (Player p : targets(5.5)) {
                Vector to2 = p.getLocation().toVector().subtract(l.toVector()).setY(0);
                if (to2.lengthSquared() > 0.01 && to2.normalize().dot(dir) < 0.55) continue;
                hit(p, 15 * damageBonus);
                Compat.spawn(world(), Compat.DAMAGE_INDICATOR, p.getLocation().add(0, 1.2, 0), 10, 0.3, 0.3, 0.3, 0.1);
            }
        }, null);
    }

    /**
     * 8. Tajo Descendente: levanta la lanza con las dos manos y parte el suelo en
     * linea recta. La grieta la abre el golpe; no hay nada flotando por su cuenta.
     */
    public void overheadCleave() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        Location origin = boss.getLocation();
        Vector dir = target.getLocation().toVector().subtract(origin.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        dir.normalize();
        Set<UUID> cleaved = new HashSet<>();

        soundAt(origin, "entity.player.attack.strong", 1.4f, 0.6f);
        broadcastNear(Component.text("Levanta la lanza.", ACCENT));

        animate(80, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            if (tick < 28) {
                Compat.spawn(world(), Compat.WHITE_ASH, l.clone().add(0, 2.6, 0), 4, 0.3, 0.2, 0.3, 0,
                        Compat.dust(RUST, 1.5f));
                for (double d = 1.5; d < 15; d += 1.2) {
                    Location g = Fx.ground(l.clone().add(dir.clone().multiply(d)), 4);
                    Compat.spawn(world(), Compat.SOUL, g.clone().add(0, 0.15, 0), 1, 0.35, 0, 0.35, 0,
                            Compat.dust(RUST, 1.4f));
                }
                if (tick % 7 == 0) soundAt(l, "block.note_block.bass", 1.1f, 0.45f + tick / 90f);
                return;
            }
            if (tick == 28) {
                soundAt(l, "item.trident.riptide_3", 1.3f, 0.7f);
                soundAt(l, "block.deepslate.break", 1.5f, 0.5f);
                soundAt(l, "entity.player.attack.crit", 1.4f, 0.5f);
                return;
            }
            double reach = (tick - 28) * 1.1;
            if (reach > 15) return;
            Location g = Fx.ground(l.clone().add(dir.clone().multiply(reach)), 4);
            Compat.spawn(world(), Compat.BLOCK, g.clone().add(0, 0.3, 0), 30, 0.5, 0.25, 0.5, 0.22, groundBlock(g));
            Compat.spawn(world(), Compat.LARGE_SMOKE, g.clone().add(0, 0.4, 0), 6, 0.4, 0.2, 0.4, 0.02);
            for (Player p : Fx.playersNear(g, 2.4)) {
                if (!cleaved.add(p.getUniqueId())) continue;
                hit(p, 17 * damageBonus);
                push(p, new Vector(0, 0.5, 0));
                soundAt(p.getLocation(), "entity.player.attack.crit", 1.3f, 0.6f);
            }
        }, null);
    }

    /** 9. Cadena de Hueso: engancha al mas lejano y lo arrastra de vuelta al centro. */
    public void boneChain() {
        Player target = Fx.farthest(loc(), plugin.settings().participationRadius());
        if (target == null || !alive()) return;
        if (target.getLocation().distanceSquared(loc()) < 36) return;

        soundAt(loc(), "entity.leashknot.place", 1.4f, 0.6f);
        soundAt(target.getLocation(), "block.chain.place", 1.2f, 0.7f);
        target.sendActionBar(Component.text("Te ha enganchado.", NamedTextColor.RED, TextDecoration.BOLD));

        animate(55, tick -> {
            if (!alive() || !Fx.isFightable(target)) throw Stop.now();
            Location from = boss.getLocation().add(0, 1.2, 0);
            Location to = target.getLocation().add(0, 1.0, 0);
            Fx.beam(from, to, 0.45, p -> {
                Compat.spawn(world(), Compat.SCRAPE, p, 1, 0, 0, 0, 0, Compat.dust(BONE, 1.0f));
                if (tick % 6 == 0) Compat.spawn(world(), Compat.ITEM, p, 1, 0.04, 0.04, 0.04, 0.01,
                        new ItemStack(Material.BONE));
            });
            if (tick < 20) {
                if (tick % 5 == 0) soundAt(to, "block.chain.hit", 1.0f, 0.8f);
                return;
            }
            if (tick % 5 == 0) {
                Vector pull = from.toVector().subtract(to.toVector()).normalize().multiply(0.75).setY(0.25);
                push(target, pull);
                soundAt(to, "item.trident.riptide_1", 0.9f, 0.8f);
            }
            if (tick == 50) {
                hit(target, 8 * damageBonus);
                Compat.spawn(world(), Compat.ITEM, to, 40, 0.4, 0.4, 0.4, 0.15, new ItemStack(Material.BONE));
                soundAt(to, "block.bone_block.break", 1.2f, 0.8f);
            }
        }, null);
    }

    /**
     * 10. Juramento Roto: marca a alguien y lo obliga a quedarse cerca del jefe.
     * Es la habilidad mas "de equipo": obliga a que el grupo no se disperse.
     */
    public void brokenOath() {
        Player target = randomTarget();
        if (target == null || !alive()) return;

        soundAt(target.getLocation(), "entity.evoker.prepare_summon", 1.4f, 0.6f);
        soundAt(target.getLocation(), "block.beacon.activate", 1.0f, 0.5f);
        target.showTitle(Title.title(
                Component.text("JURAMENTO", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("No te alejes del Caballero", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1200), Duration.ofMillis(400))));

        animate(140, tick -> {
            if (!Fx.isFightable(target) || !alive()) throw Stop.now();
            Location tl = target.getLocation().add(0, 2.4, 0);
            Fx.ring(tl, 0.7, 10, tick * 0.35, p ->
                    Compat.spawn(world(), Compat.CRIT, p, 1, 0, 0, 0, 0, Compat.dust(RUST, 1.2f)));

            double dist = target.getLocation().distance(boss.getLocation());
            boolean far = dist > 16;
            if (tick % 10 == 0) {
                target.sendActionBar(Component.text("Juramento  ", NamedTextColor.GRAY)
                        .append(Component.text((int) dist + "m", far ? NamedTextColor.RED : NamedTextColor.GREEN,
                                TextDecoration.BOLD))
                        .append(Component.text("  ·  limite 16m   " + ((140 - tick) / 20) + "s",
                                NamedTextColor.DARK_GRAY)));
                soundAt(target.getLocation(), far ? "block.note_block.didgeridoo" : "block.note_block.chime",
                        0.8f, far ? 0.6f : 1.5f);
            }
            if (tick == 140 - 1) {
                if (far) {
                    hit(target, 22 * damageBonus);
                    Compat.spawn(world(), Compat.EXPLOSION, target.getLocation().add(0, 1, 0), 3, 0.4, 0.6, 0.4, 0);
                    soundAt(target.getLocation(), "entity.wither.hurt", 1.6f, 0.5f);
                    target.sendActionBar(Component.text("Rompiste el juramento.", NamedTextColor.RED,
                            TextDecoration.BOLD));
                } else {
                    Compat.spawn(world(), Compat.TOTEM, target.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.3);
                    soundAt(target.getLocation(), "block.beacon.deactivate", 1.0f, 1.4f);
                    target.sendActionBar(Component.text("Aguantaste el juramento.", NamedTextColor.GREEN));
                }
            }
        }, null);
    }

    /** 11. Circulo de Osario: un cerco de hueso que se cierra; fuera se pierde vida. */
    public void ossuaryCircle() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 4);
        soundAt(c, "block.bone_block.place", 1.6f, 0.5f);
        broadcastNear(Component.text("Levanta el osario. Adentro.", ACCENT));

        animate(120, tick -> {
            double t = tick / 120.0;
            double radius = 18 - t * 11; // de 18 a 7 bloques
            int points = (int) (radius * 6);
            Fx.ring(c, radius, points, tick * 0.05, p -> {
                Location g = Fx.ground(p, 4);
                Compat.spawn(world(), Compat.SOUL_FIRE_FLAME, g.clone().add(0, 0.3, 0), 1, 0, 0.35, 0, 0,
                        Compat.dust(BONE, 1.5f));
                if (tick % 10 == 0) {
                    Compat.spawn(world(), Compat.ITEM, g.clone().add(0, 0.5, 0), 1, 0.05, 0.1, 0.05, 0.02,
                            new ItemStack(Material.BONE));
                }
            });
            if (tick % 20 != 0) return;
            soundAt(c, "block.bone_block.step", 1.2f, 0.6f);
            for (Player p : targets(80)) {
                if (p.getLocation().distance(c) <= radius) continue;
                hit(p, 6 * damageBonus);
                Compat.apply(p, "wither", 60, 0);
                p.sendActionBar(Component.text("Estas fuera del osario.", NamedTextColor.RED, TextDecoration.BOLD));
                soundAt(p.getLocation(), "entity.wither.hurt", 0.8f, 1.4f);
            }
        }, () -> {
            soundAt(c, "block.bone_block.break", 1.4f, 0.7f);
            Fx.ring(c, 7, 40, p -> Compat.spawn(world(), Compat.ITEM, Fx.ground(p, 3), 3, 0.2, 0.2, 0.2, 0.1,
                    new ItemStack(Material.BONE)));
        });
    }

    /** 12. Guardia de Netherita: se cubre con la armadura y aguanta a pie firme. */
    public void netheriteWard() {
        if (!alive()) return;
        Location c = boss.getLocation();
        double shield = 160;
        boss.setAbsorptionAmount(shield);
        soundAt(c, "item.shield.block", 1.6f, 0.5f);
        soundAt(c, "block.anvil_use", 1.2f, 0.7f);
        broadcastNear(Component.text("Alza la guardia. Rompanla a golpes.", ACCENT));

        animate(160, tick -> {
            if (!alive()) throw Stop.now();
            Location l = boss.getLocation().add(0, 1.1, 0);
            // Placas pegadas al cuerpo, no objetos girando alrededor: es armadura,
            // no un encantamiento.
            double r = 1.0 + Math.sin(tick * 0.15) * 0.12;
            Fx.sphere(l, r, 26, p -> Compat.spawn(world(), Compat.ASH, p, 1, 0, 0, 0, 0,
                    Compat.dust(VOID_PURPLE, 1.3f)));
            if (tick % 5 == 0) {
                Fx.ring(l, r + 0.25, 12, tick * 0.2, p ->
                        Compat.spawn(world(), Compat.ELECTRIC_SPARK, p, 1, 0, 0, 0, 0.01));
            }
            if (tick % 20 == 0) {
                soundAt(l, "block.anvil_land", 0.5f, 1.7f);
                warn(Component.text("Guardia  ", NamedTextColor.GRAY)
                        .append(Component.text((int) boss.getAbsorptionAmount() + " / " + (int) shield,
                                NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)));
            }
            if (boss.getAbsorptionAmount() <= 0) {
                Compat.spawn(world(), Compat.EXPLOSION, l, 2, 0.5, 0.5, 0.5, 0);
                soundAt(l, "item.shield.break", 1.6f, 0.7f);
                soundAt(l, "block.anvil_destroy", 1.2f, 1.0f);
                broadcastNear(Component.text("Guardia rota.", NamedTextColor.GREEN));
                throw Stop.now();
            }
        }, () -> {
            if (alive()) boss.setAbsorptionAmount(0);
        });
    }

    // ------------------------------------------------------------- FASE III: heraldo

    /**
     * 13. Sismo del Paramo: cuatro pisotones seguidos, cada uno con su onda.
     * Hay que moverse entre onda y onda; quedarse quieto no vale.
     */
    public void earthquake() {
        if (!alive()) return;
        broadcastNear(Component.text("El suelo empieza a partirse.", ACCENT));
        soundAt(loc(), "entity.ravager.roar", 1.3f, 0.6f);

        for (int wave = 0; wave < 4; wave++) {
            final int index = wave;
            later(wave * 45, () -> {
                if (!alive()) return;
                Location aim = Fx.ground(boss.getLocation(), 4);
                Fx.telegraph(world(), aim, 5.0, RUST);
                soundAt(aim, "block.note_block.bass", 1.2f, 0.4f);
                later(16, () -> {
                    if (!alive()) return;
                    Location impact = Fx.ground(boss.getLocation(), 4);
                    Set<UUID> struck = new HashSet<>();
                    Compat.spawn(world(), Compat.EXPLOSION_EMITTER, impact, 1);
                    Compat.spawn(world(), Compat.BLOCK, impact, 120, 1.8, 0.3, 1.8, 0.25, groundBlock(impact));
                    soundAt(impact, "entity.generic.explode", 1.4f, 0.5f);
                    soundAt(impact, "block.anvil_land", 1.3f, 0.45f);
                    animate(24, t -> {
                        double radius = t * 0.55;
                        Fx.shockwave(world(), impact, radius, Compat.CLOUD, 7);
                        Fx.ring(impact, radius, (int) (radius * 7) + 6, pt ->
                                Compat.spawn(world(), Compat.BLOCK, Fx.ground(pt, 3).add(0, 0.2, 0), 2,
                                        0.12, 0.1, 0.12, 0.05, groundBlock(impact)));
                        for (Player p : Fx.playersNear(impact, radius + 1.0)) {
                            if (p.getLocation().distance(impact) < radius - 1.4) continue;
                            if (!struck.add(p.getUniqueId())) continue;
                            hit(p, (12 + index) * damageBonus);
                            push(p, p.getLocation().toVector().subtract(impact.toVector())
                                    .normalize().setY(0.45).multiply(0.7));
                        }
                    }, null);
                });
            });
        }
    }

    /**
     * 14. Salto Demoledor: se agacha, salta muy alto y cae de lleno sobre la marca.
     * Es el golpe mas bruto que tiene, y por eso el que mas avisa.
     */
    public void crushingLeap() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        Location mark = Fx.ground(target.getLocation(), 4);

        soundAt(loc(), "entity.ravager.step", 1.4f, 0.5f);
        broadcastNear(Component.text("Se agacha para saltar.", ACCENT));

        animate(110, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            if (tick < 18) {
                Compat.spawn(world(), Compat.BLOCK, Fx.ground(l, 3), 12, 0.5, 0.05, 0.5, 0.05, groundBlock(l));
                return;
            }
            if (tick == 18) {
                boss.setVelocity(new Vector(0, 1.35, 0));
                soundAt(l, "entity.generic.explode", 1.1f, 1.6f);
                Compat.spawn(world(), Compat.CLOUD, Fx.ground(l, 3), 40, 0.8, 0.1, 0.8, 0.12);
                return;
            }
            if (tick < 46) {
                Compat.spawn(world(), Compat.LARGE_SMOKE, l, 3, 0.3, 0.3, 0.3, 0.01);
                Fx.telegraph(world(), mark, 4.5, RUST);
                if (tick % 8 == 0) soundAt(mark, "block.note_block.bass", 1.0f, 0.5f);
                return;
            }
            if (tick == 46) {
                boss.teleport(mark.clone().add(0, 9, 0));
                return;
            }
            if (tick < 60) {
                boss.setVelocity(new Vector(0, -1.6, 0));
                Compat.spawn(world(), Compat.WHITE_ASH, l, 6, 0.3, 0.5, 0.3, 0, Compat.dust(RUST, 1.6f));
                return;
            }
            if (tick != 60) return;
            boss.teleport(mark);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, mark, 2);
            Compat.spawn(world(), Compat.BLOCK, mark, 200, 2.5, 0.5, 2.5, 0.35, groundBlock(mark));
            soundAt(mark, "entity.generic.explode", 1.8f, 0.4f);
            soundAt(mark, "block.anvil_land", 1.6f, 0.4f);
            soundAt(mark, "entity.ravager.stunned", 1.4f, 0.6f);
            for (Player p : Fx.playersNear(mark, 6.5)) {
                double d = p.getLocation().distance(mark);
                hit(p, Math.max(7, 24 - d * 2) * damageBonus);
                push(p, p.getLocation().toVector().subtract(mark.toVector()).normalize().setY(0.7));
            }
        }, null);
    }

    /** 15. Ultima Carga: el fantasma del caballo vuelve para una carga final. */
    public void finalCharge() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        Location start = boss.getLocation();
        Vector dir = target.getLocation().toVector().subtract(start.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        dir.normalize();

        soundAt(start, "entity.skeleton_horse.death", 1.6f, 0.5f);
        soundAt(start, "entity.horse.angry", 1.4f, 0.6f);
        broadcastNear(Component.text("El fantasma de la montura vuelve.", ACCENT));
        titleNear(Component.text("ULTIMA CARGA", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Apartense del pasillo", NamedTextColor.GRAY));

        animate(120, tick -> {
            if (!alive()) return;
            if (tick < 40) {
                // el espectro del caballo se dibuja bajo el caballero
                Location l = boss.getLocation();
                Fx.helix(l, 1.3, 2.2, 20, 1.5, p ->
                        Compat.spawn(world(), Compat.SOUL, p, 1, 0, 0, 0, 0, Compat.dust(SPECTRAL, 1.5f)));
                for (double d = 2; d < 30; d += 1.0) {
                    Location g = Fx.ground(l.clone().add(dir.clone().multiply(d)), 4);
                    Compat.spawn(world(), Compat.SCRAPE, g.clone().add(0, 0.15, 0), 1, 1.3, 0, 1.3, 0,
                            Compat.dust(RUST, 1.5f));
                }
                if (tick % 8 == 0) soundAt(l, "entity.horse.breathe", 1.2f, 0.5f);
                return;
            }
            Entity mover = boss.getVehicle() != null ? boss.getVehicle() : boss;
            mover.setVelocity(dir.clone().multiply(1.5).setY(mover.getVelocity().getY()));
            Location l = boss.getLocation();
            Fx.helix(l, 1.1, 2.0, 14, 1.0, p ->
                    Compat.spawn(world(), Compat.SOUL_FIRE_FLAME, p, 1, 0.02, 0.02, 0.02, 0.005));
            Compat.spawn(world(), Compat.CRIT, l.clone().add(0, 0.3, 0), 8, 0.5, 0.4, 0.5, 0,
                    Compat.dust(SPECTRAL, 1.6f));
            if (tick % 4 == 0) soundAt(l, "entity.horse.gallop", 1.4f, 0.7f);
            for (Player p : targets(3.6)) {
                hit(p, 20 * damageBonus);
                push(p, dir.clone().multiply(1.4).setY(0.55));
                Compat.spawn(world(), Compat.EXPLOSION, p.getLocation().add(0, 1, 0), 1);
                soundAt(p.getLocation(), "item.trident.hit", 1.4f, 0.7f);
            }
        }, () -> soundAt(loc(), "entity.horse.breathe", 1.2f, 0.6f));
    }

    /** 16. Grito del Paramo: un haz sonico frontal que atraviesa todo. */
    public void wastelandScream() {
        if (!alive()) return;
        Location c = boss.getLocation().add(0, 1.4, 0);
        Vector dir = boss.getLocation().getDirection().setY(0).normalize();

        soundAt(c, "entity.warden.sonic_charge", 1.6f, 0.8f);
        animate(70, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1.4, 0);
            if (tick < 40) {
                double r = 2.2 - tick * 0.045;
                Fx.sphere(l, Math.max(0.4, r), 16, p ->
                        Compat.spawn(world(), Compat.SOUL_FIRE_FLAME, p, 1, 0, 0, 0, 0, Compat.dust(SPECTRAL, 1.3f)));
                for (double d = 1; d < 22; d += 1.5) {
                    Compat.spawn(world(), Compat.ASH, l.clone().add(dir.clone().multiply(d)), 1, 0.25, 0.25, 0.25, 0,
                            Compat.dust(VOID_PURPLE, 1.0f));
                }
                if (tick % 10 == 0) soundAt(l, "block.note_block.bass", 1.2f, 0.4f + tick / 40f);
                return;
            }
            if (tick != 40) return;
            soundAt(l, "entity.warden.sonic_boom", 1.8f, 1.0f);
            for (double d = 1; d <= 24; d += 0.8) {
                Location p = l.clone().add(dir.clone().multiply(d));
                Compat.spawn(world(), Compat.SONIC_BOOM != null ? Compat.SONIC_BOOM : Compat.CLOUD, p, 1);
                Compat.spawn(world(), Compat.WHITE_ASH, p, 4, 0.35, 0.35, 0.35, 0, Compat.dust(SPECTRAL, 1.6f));
            }
            for (Player p : targets(24)) {
                Vector to = p.getLocation().toVector().subtract(l.toVector()).setY(0);
                if (to.lengthSquared() > 0.01 && to.normalize().dot(dir) < 0.86) continue;
                hit(p, 18 * damageBonus);
                Compat.apply(p, "darkness", 100, 0);
                soundAt(p.getLocation(), "entity.warden.sonic_boom", 1.0f, 1.4f);
            }
        }, null);
    }

    // -------------------------------------------------------- CUALQUIER FASE

    /**
     * 17. Caceria: le echa el ojo al que mas se aleja y va a por el en persona.
     * Mismo castigo de antes a quien se despega del grupo, pero a la carrera.
     */
    public void hunt() {
        Player target = Fx.farthest(loc(), plugin.settings().participationRadius());
        if (target == null || !alive()) return;

        soundAt(loc(), "entity.ravager.roar", 1.4f, 0.7f);
        target.sendActionBar(Component.text("Te ha echado el ojo.", NamedTextColor.RED, TextDecoration.BOLD));
        Compat.setAttribute(boss, "movement_speed", 0.46);

        animate(120, tick -> {
            if (!alive() || !Fx.isFightable(target)) throw Stop.now();
            Location l = boss.getLocation();
            Location tl = target.getLocation();
            if (l.getWorld() == null || !l.getWorld().equals(tl.getWorld())) throw Stop.now();

            Compat.spawn(world(), Compat.SOUL, l.clone().add(0, 0.3, 0), 4, 0.3, 0.2, 0.3, 0,
                    Compat.dust(RUST, 1.3f));
            Fx.ring(tl.clone().add(0, 0.15, 0), 1.4, 12, tick * 0.3, p ->
                    Compat.spawn(world(), Compat.SCRAPE, Fx.ground(p, 3).add(0, 0.15, 0), 1, 0, 0, 0, 0,
                            Compat.dust(RUST, 1.1f)));

            Vector dir = tl.toVector().subtract(l.toVector()).setY(0);
            if (dir.lengthSquared() > 0.04) {
                Entity mover = boss.getVehicle() != null ? boss.getVehicle() : boss;
                mover.setVelocity(dir.normalize().multiply(0.42).setY(mover.getVelocity().getY()));
            }
            if (tick % 10 == 0) soundAt(l, "entity.ravager.step", 1.0f, 0.8f);

            if (l.distanceSquared(tl) < 9) {
                hit(target, 19 * damageBonus);
                push(target, tl.toVector().subtract(l.toVector()).normalize().setY(0.45).multiply(1.1));
                Compat.spawn(world(), Compat.CRIT, tl.clone().add(0, 1, 0), 30, 0.4, 0.5, 0.4, 0.3);
                soundAt(tl, "entity.player.attack.knockback", 1.4f, 0.6f);
                soundAt(tl, "item.shield.block", 1.2f, 0.5f);
                throw Stop.now();
            }
        }, () -> {
            if (alive()) Compat.setAttribute(boss, "movement_speed", phase() == 1 ? 0.26 : 0.33);
        });
    }

    /** 18. Leva de Huesos: recluta esqueletos que salen del suelo alrededor. */
    public void boneLevy() {
        if (!alive()) return;
        int count = 3 + random.nextInt(4);
        Location c = boss.getLocation();
        soundAt(c, "entity.wither.shoot", 1.4f, 0.6f);
        broadcastNear(Component.text("Recluta a los caidos.", ACCENT));

        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count + random.nextDouble() * 0.4;
            double r = 4 + random.nextDouble() * 4;
            Location sl = Fx.ground(c.clone().add(Math.cos(a) * r, 1, Math.sin(a) * r), 5);
            boolean wither = phase() == 3 || random.nextBoolean();

            later(i * 6, () -> {
                if (!alive()) return;
                animate(20, tick -> {
                    Compat.spawn(world(), Compat.BLOCK, sl, 8, 0.4, 0.1, 0.4, 0.05,
                            Material.COARSE_DIRT.createBlockData());
                    Compat.spawn(world(), Compat.SOUL, sl.clone().add(0, 0.3, 0), 3, 0.3, 0.2, 0.3, 0.01);
                    if (tick % 6 == 0) soundAt(sl, "block.rooted_dirt.break", 1.0f, 0.6f);
                }, () -> {
                    LivingEntity minion = wither
                            ? world().spawn(sl, WitherSkeleton.class, e -> {
                                e.setPersistent(false);
                                Compat.setAttribute(e, "max_health", 34);
                                e.setHealth(34);
                                EntityEquipment eq = e.getEquipment();
                                if (eq != null) {
                                    eq.setItemInMainHand(new ItemStack(Material.STONE_SWORD));
                                    eq.setItemInMainHandDropChance(0);
                                }
                            })
                            : world().spawn(sl, Skeleton.class, e -> {
                                e.setPersistent(false);
                                Compat.setAttribute(e, "max_health", 24);
                                e.setHealth(24);
                                EntityEquipment eq = e.getEquipment();
                                if (eq != null) {
                                    eq.setItemInMainHand(new ItemStack(Material.BOW));
                                    eq.setItemInMainHandDropChance(0);
                                }
                            });
                    minion.customName(Component.text("Caido del Paramo", TextColor.color(0xA9C4CC)));
                    markMinion(minion);
                    Compat.spawn(world(), Compat.POOF, sl, 20, 0.3, 0.4, 0.3, 0.05);
                    soundAt(sl, "entity.skeleton.ambient", 1.0f, 0.8f);
                });
            });
        }
    }

    // ------------------------------------------------------------------- mensajeria

    /** Los datos del bloque que pisa, para que los escombros sean del terreno real. */
    private BlockData groundBlock(Location l) {
        Material m = Fx.ground(l, 3).getBlock().getRelative(0, -1, 0).getType();
        if (!m.isSolid() || m.isAir()) m = Material.DIRT;
        return m.createBlockData();
    }

    private void broadcastNear(Component message) {
        Component line = Component.text("✦ ", ACCENT)
                .append(Component.text("Caballero Sepulcral  ", ACCENT, TextDecoration.BOLD))
                .append(message.colorIfAbsent(NamedTextColor.GRAY));
        for (Player p : Fx.viewersNear(loc(), 80)) p.sendActionBar(line);
    }

    private void titleNear(Component title, Component subtitle) {
        for (Player p : Fx.viewersNear(loc(), 80)) {
            p.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1400), Duration.ofMillis(500))));
        }
    }

    /** Deja a la vista el numero de esbirros, para el /anomaly info. */
    public int minions() {
        int n = 0;
        for (Entity e : spawned) {
            if (e.isValid() && e instanceof LivingEntity && !(e instanceof Display)) n++;
        }
        return n;
    }
}
