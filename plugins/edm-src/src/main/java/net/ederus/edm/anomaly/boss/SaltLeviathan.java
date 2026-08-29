package net.ederus.edm.anomaly.boss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.core.ActiveAnomaly;
import net.ederus.edm.anomaly.core.Compat;
import net.ederus.edm.anomaly.core.Fx;
import net.ederus.edm.anomaly.core.Glow;
import net.ederus.edm.anomaly.core.Stop;
import net.ederus.edm.anomaly.core.Tags;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ElderGuardian;
import org.bukkit.entity.Guardian;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * EL LEVIATAN DE SAL, la quinta anomalia.
 *
 * Un guardian anciano descomunal en el fondo del oceano. La pelea es entera bajo el
 * agua, que es lo que la hace distinta de todo lo demas: aqui el jugador es el que
 * esta fuera de su elemento.
 *
 * El problema evidente de una pelea sumergida es que sin pociones de respiracion es
 * una tortura, no un combate. Se resuelve con la propia ficcion: mientras estas DENTRO
 * de su arena, el abismo te deja respirar (poder de conducto). En cuanto sales, se
 * acaba el favor. Asi la arena tiene borde sin necesidad de paredes invisibles, y
 * huir del jefe pasa a ser peor idea que quedarse.
 */
public final class SaltLeviathan extends BossFight {

    public static final String ID = "leviatan_de_sal";
    public static final TextColor ACCENT = TextColor.color(0x4FD1C5);

    private static final int BRINE = 0x2E8B84;
    private static final int PRISM = 0x8FE3D9;
    private static final int ABYSS = 0x123B4A;

    /** Radio dentro del cual el abismo te deja respirar. */
    private static final double ARENA = 22;

    private boolean sank;
    private boolean awakened;
    private double damageBonus = 1.0;

    public SaltLeviathan(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        super(plugin, event, where);
        abilities.addAll(plugin.registry().leviathanAbilities());
    }

    @Override
    public String bossName() {
        return "Leviatan de Sal";
    }

    // ------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        Location spot = arena.clone();

        boss = world().spawn(spot, ElderGuardian.class, g -> {
            g.setPersistent(true);
            g.setRemoveWhenFarAway(false);
            g.customName(Component.text("✦ ", ACCENT)
                    .append(Component.text("Leviatan de Sal", ACCENT, TextDecoration.BOLD)));
            g.setCustomNameVisible(true);
        });

        Compat.setAttribute(boss, "attack_damage", 12);
        Compat.setAttribute(boss, "armor", 14);
        Compat.setAttribute(boss, "knockback_resistance", 1.0);
        Compat.setAttribute(boss, "follow_range", 64);
        Compat.setAttribute(boss, "movement_speed", 0.32);
        Compat.setAttribute(boss, "scale", 2.6);
        applyHealth(plugin.registry().scaledHealth(plugin.registry().leviathan(), targets(96).size()));
        boss.setMaximumNoDamageTicks(6);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        Glow.apply(boss, event.type().glowColor());

        arrivalAnimation(spot);
    }

    /** La llegada: el agua se aclara, se oye un canto grave y el fondo se levanta. */
    private void arrivalAnimation(Location spot) {
        boss.setInvulnerable(true);
        busyFor(80);
        soundAt(spot, "entity.elder_guardian.curse", 1.6f, 0.5f);
        soundAt(spot, "block.conduit.activate", 1.4f, 0.6f);

        animate(80, tick -> {
            double t = tick / 80.0;
            Fx.sphere(spot, 12 - t * 8, 40, p ->
                    Compat.spawn(world(), Compat.BUBBLE, p, 1, 0, 0, 0, 0, Compat.dust(PRISM, 1.5f)));
            if (tick % 4 == 0) {
                Compat.spawn(world(), Compat.NAUTILUS, spot, 30, 3.0, 2.0, 3.0, 0.05);
            }
            if (tick % 16 == 0) {
                soundAt(spot, "block.conduit.ambient", 1.2f, 0.5f);
                Compat.spawn(world(), Compat.ITEM, spot.clone().add(0, 1, 0), 40, 2.0, 1.5, 2.0, 0.15,
                        new org.bukkit.inventory.ItemStack(Material.PRISMARINE_SHARD));
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            Compat.spawn(world(), Compat.FLASH, spot.clone().add(0, 1.5, 0), 1);
            soundAt(spot, "entity.elder_guardian.curse", 1.8f, 0.7f);
            for (Player p : Fx.viewersNear(spot, 90)) {
                p.showTitle(Title.title(
                        Component.text("✦ ANOMALIA ✦", ACCENT, TextDecoration.BOLD),
                        Component.text("El abismo te deja respirar. Por ahora.", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2000), Duration.ofMillis(700))));
            }
        });
    }

    // -------------------------------------------------------------------- ambiente

    @Override
    protected void ambient() {
        if (!alive()) return;

        // El favor del abismo: dentro de la arena se respira y se ve; fuera, no.
        // Es lo que hace jugable una pelea sumergida sin regalar pociones a nadie.
        if (ticks() % 20 == 0) {
            for (Player p : Fx.viewersNear(loc(), ARENA)) {
                Compat.apply(p, "conduit_power", 90, 0);
            }
            drawBoundary();
        }

        if (ticks() % 4 == 0) {
            Location l = boss.getLocation().add(0, 1.4, 0);
            Compat.spawn(world(), Compat.BUBBLE, l, 3, 1.0, 0.8, 1.0, 0.02);
            Compat.spawn(world(), Compat.GLOW_SQUID_INK, l, 2, 0.9, 0.7, 0.9, 0,
                    Compat.dust(awakened ? PRISM : BRINE, 1.2f));
        }
    }

    /** El borde de la arena, dibujado para que se sepa donde acaba el aire. */
    private void drawBoundary() {
        Location c = loc();
        Fx.ring(c, ARENA, 60, ticks() * 0.02, p ->
                Compat.spawnForced(world(), Compat.NAUTILUS, p, 1, 0, 1.2, 0, 0, Compat.dust(PRISM, 2.0f)));
        for (Player p : Fx.viewersNear(c, 90)) {
            if (p.getLocation().distanceSquared(c) <= ARENA * ARENA) continue;
            p.sendActionBar(Component.text("Fuera del abismo no hay aire.", NamedTextColor.RED, TextDecoration.BOLD));
        }
    }

    @Override
    public double incomingDamageMultiplier() {
        return awakened ? 1.25 : 1.0;
    }

    // --------------------------------------------------------------- cambio de fase

    @Override
    protected void onPhaseChange(int from, int to) {
        if (event.bars() != null) event.bars().flash(from);
        if (to == 2) descend();
        if (to == 3) awaken();
    }

    /** FASE I -> II. Se hunde hasta el fondo y arrastra la corriente con el. */
    private void descend() {
        if (sank || !alive()) return;
        sank = true;
        boss.setInvulnerable(true);
        busyFor(80);

        Location spot = boss.getLocation();
        soundAt(spot, "entity.elder_guardian.hurt", 1.6f, 0.5f);
        broadcastNear(Component.text("Se hunde y la corriente cambia.", ACCENT));

        animate(80, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            boss.setVelocity(new Vector(0, -0.12, 0));
            Fx.helix(l, 3.0, 5.0, 26, 3.0, p ->
                    Compat.spawn(world(), Compat.BUBBLE_POP, p, 1, 0.05, 0.05, 0.05, 0.02));
            if (tick % 10 == 0) soundAt(l, "block.conduit.ambient", 1.2f, 0.4f);
            if (tick != 60) return;
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l, 1);
            soundAt(l, "entity.elder_guardian.curse", 1.6f, 0.6f);
            for (Player p : targets(12)) {
                hit(p, 10 * damageBonus);
                push(p, l.toVector().subtract(p.getLocation().toVector()).normalize().multiply(1.1));
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            Compat.setAttribute(boss, "attack_damage", 15);
            Compat.setAttribute(boss, "movement_speed", 0.38);
            titleNear(Component.text("FASE II", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("La corriente esta de su parte", NamedTextColor.GRAY));
        });
    }

    /** FASE II -> III. Despierta del todo: mas rapido y mucho mas fragil. */
    private void awaken() {
        if (awakened || !alive()) return;
        awakened = true;
        boss.setInvulnerable(true);
        busyFor(90);

        Location spot = boss.getLocation();
        soundAt(spot, "entity.elder_guardian.curse", 1.8f, 0.4f);
        titleNear(Component.text("DESPIERTA", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Su coraza se abre: pega mas y aguanta menos", NamedTextColor.GRAY));

        animate(90, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1.4, 0);
            Fx.sphere(l, 2.4 + Math.sin(tick * 0.18) * 0.4, 30, p ->
                    Compat.spawn(world(), Compat.DOLPHIN, p, 1, 0, 0, 0, 0, Compat.dust(PRISM, 1.8f)));
            if (tick % 12 == 0) {
                Compat.spawn(world(), Compat.ITEM, l, 30, 1.2, 1.0, 1.2, 0.2,
                        new org.bukkit.inventory.ItemStack(Material.PRISMARINE_CRYSTALS));
                soundAt(l, "block.conduit.attack_target", 1.3f, 0.6f);
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            damageBonus = 1.35;
            Compat.setAttribute(boss, "attack_damage", 19);
            Compat.setAttribute(boss, "movement_speed", 0.46);
            Compat.apply(boss, "dolphins_grace", 20 * 600, 0);
        });
    }

    // ---------------------------------------------------------------------- muerte

    @Override
    public void onDeath() {
        Location l = loc();
        soundAt(l, "entity.elder_guardian.death", 1.8f, 0.5f);

        animate(90, tick -> {
            double t = tick / 90.0;
            Fx.sphere(l, 1.0 + t * 6, 34, p ->
                    Compat.spawn(world(), Compat.SQUID_INK, p, 1, 0, 0, 0, 0, Compat.dust(PRISM, 1.6f)));
            if (tick % 8 == 0) {
                Compat.spawn(world(), Compat.BUBBLE, l.clone().add(0, 1, 0), 40, 1.5, 1.2, 1.5, 0.08);
                Compat.spawn(world(), Compat.ITEM, l.clone().add(0, 1, 0), 20, 1.0, 1.0, 1.0, 0.12,
                        new org.bukkit.inventory.ItemStack(Material.PRISMARINE_SHARD));
                soundAt(l, "block.conduit.deactivate", 1.0f, 0.5f + (float) t);
            }
        }, () -> {
            Compat.spawn(world(), Compat.FLASH, l.clone().add(0, 1, 0), 1);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l, 1);
            soundAt(l, "block.conduit.deactivate", 1.6f, 0.4f);
            soundAt(l, "entity.elder_guardian.curse", 1.0f, 1.4f);
            // Se acaba el favor: quien siga ahi abajo tiene que subir por su cuenta.
            for (Player p : Fx.viewersNear(l, ARENA)) {
                p.sendActionBar(Component.text("El abismo se cierra. Sube.", ACCENT, TextDecoration.BOLD));
            }
        });
    }

    // ============================================================== HABILIDADES ==

    // ---------------------------------------------------------- FASE I: el guardian

    /** 1. Haz Abisal: el rayo de guardian, pero cargado y con aviso. */
    public void abyssalBeam() {
        if (!alive()) return;
        // Tres haces a la vez, cada uno tensandose sobre su jugador: el abismo
        // no elige a uno, elige a los que le caben en la mirada.
        List<Player> marks = pickTargets(3);
        if (marks.isEmpty()) return;
        soundAt(loc(), "entity.guardian.attack", 1.6f, 0.5f);
        for (Player target : marks) {
            beamOn(target);
        }
    }

    /** Un haz: se tensa cuarenta ticks sobre el objetivo y descarga. */
    private void beamOn(Player target) {
        animate(60, tick -> {
            if (!alive() || !Fx.isFightable(target)) throw Stop.now();
            Location from = boss.getEyeLocation();
            Location to = target.getLocation().add(0, 1, 0);
            if (tick < 40) {
                // el haz se va tensando: primero fino y palido, luego grueso
                double thickness = tick / 40.0;
                Fx.beam(from, to, 0.5, p -> Compat.spawn(world(), Compat.SPLASH, p, 1,
                        0.05 * thickness, 0.05 * thickness, 0.05 * thickness, 0,
                        Compat.dust(tick < 30 ? PRISM : 0xFF5555, (float) (0.8 + thickness))));
                if (tick % 10 == 0) soundAt(from, "block.conduit.attack_target", 1.1f, 0.7f + tick / 50f);
                return;
            }
            if (tick != 40) return;
            soundAt(from, "entity.guardian.attack", 1.8f, 0.9f);
            Fx.beam(from, to, 0.25, p -> {
                Compat.spawn(world(), Compat.GLOW_SQUID_INK, p, 3, 0.1, 0.1, 0.1, 0, Compat.dust(0xFF5555, 1.6f));
                Compat.spawn(world(), Compat.END_ROD, p, 1, 0.02, 0.02, 0.02, 0.01);
            });
            hit(target, 18 * damageBonus);
            Compat.spawn(world(), Compat.FLASH, to, 1);
        }, null);
    }

    /** 2. Coro de Espinas: el fondo se eriza de espinas de prismarina. */
    public void thornChoir() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 6);
        Set<UUID> pricked = new HashSet<>();
        soundAt(c, "block.conduit.activate", 1.4f, 0.8f);
        broadcastNear(Component.text("El fondo se eriza.", ACCENT));

        animate(90, tick -> {
            if (tick < 30) {
                Fx.telegraph(world(), c, 9.0, BRINE);
                return;
            }
            if (tick > 60) return;
            double radius = (tick - 30) * 0.3;
            Fx.ring(c, radius, (int) (radius * 6) + 8, p -> {
                Location g = Fx.ground(p, 4);
                for (double h = 0; h < 2.4; h += 0.3) {
                    Compat.spawn(world(), Compat.GLOW_SQUID_INK, g.clone().add(0, h, 0), 1, 0, 0, 0, 0,
                            Compat.dust(PRISM, 1.3f));
                }
            });
            if (tick % 6 == 0) soundAt(c, "block.amethyst_block.chime", 1.2f, 0.6f);
            for (Player p : targets(radius + 1.2)) {
                if (p.getLocation().distance(c) < radius - 1.5) continue;
                if (!pricked.add(p.getUniqueId())) continue;
                hit(p, 12 * damageBonus);
                push(p, new Vector(0, 0.5, 0));
                soundAt(p.getLocation(), "entity.guardian.hurt", 1.1f, 1.2f);
            }
        }, null);
    }

    /** 3. Remolino: un embudo que arrastra a todos hacia el fondo y hacia el. */
    public void whirlpool() {
        if (!alive()) return;
        Location eye = boss.getLocation();
        soundAt(eye, "block.conduit.ambient", 1.5f, 0.4f);
        broadcastNear(Component.text("Abre un remolino.", ACCENT));

        animate(140, tick -> {
            if (!alive()) return;
            Location c = boss.getLocation();
            for (int layer = 0; layer < 5; layer++) {
                double r = 10 - layer * 1.6;
                double y = layer * 1.2;
                Fx.ring(c.clone().add(0, y, 0), r, (int) (r * 3) + 6, tick * 0.3 + layer, p ->
                        Compat.spawn(world(), Compat.BUBBLE, p, 1, 0.02, 0.02, 0.02, 0.01));
            }
            if (tick % 4 != 0) return;
            for (Player p : Fx.playersNear(c, 14)) {
                Vector pull = c.toVector().subtract(p.getLocation().toVector());
                if (pull.lengthSquared() < 4) continue;
                push(p, pull.normalize().multiply(0.32).setY(-0.18));
            }
            if (tick % 20 == 0) {
                soundAt(c, "entity.player.swim", 1.3f, 0.5f);
                for (Player p : targets(4.0)) hit(p, 7 * damageBonus);
            }
        }, null);
    }

    /**
     * 4. Columna Ascendente: chorros que te disparan hacia arriba.
     *
     * Subir es lo peor que te puede pasar aqui: te saca del alcance del jefe y te
     * acerca al borde donde se acaba el aire.
     */
    public void risingColumn() {
        List<Player> victims = targets();
        if (victims.isEmpty() || !alive()) return;
        soundAt(loc(), "block.bubble_column.upwards_ambient", 1.5f, 0.7f);

        for (Player victim : victims) {
            Location mark = Fx.ground(victim.getLocation(), 6);
            animate(70, tick -> {
                if (tick < 24) {
                    Fx.telegraph(world(), mark, 2.2, PRISM);
                    for (double h = 0; h < 8; h += 0.8) {
                        Compat.spawn(world(), Compat.DOLPHIN, mark.clone().add(0, h, 0), 1, 0.2, 0.1, 0.2, 0.01);
                    }
                    return;
                }
                if (tick > 55) return;
                for (double h = 0; h < 12; h += 0.5) {
                    Compat.spawn(world(), Compat.BUBBLE, mark.clone().add(0, h, 0), 2, 0.35, 0.1, 0.35, 0.06);
                }
                if (tick % 5 == 0) soundAt(mark, "block.bubble_column.upwards_inside", 1.1f, 1.0f);
                for (Player p : Fx.playersNear(mark, 2.4)) {
                    push(p, new Vector(0, 0.55, 0));
                    if (tick % 10 == 0) hit(p, 5 * damageBonus);
                }
            }, null);
        }
    }

    /** 5. Banco de Guardianes: llama guardianes menores que hostigan desde los lados. */
    public void guardianShoal() {
        if (!alive()) return;
        int count = 3 + random.nextInt(3);
        Location c = boss.getLocation();
        soundAt(c, "entity.guardian.ambient", 1.5f, 0.8f);
        broadcastNear(Component.text("Llama al banco.", ACCENT));

        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count;
            Location sl = c.clone().add(Math.cos(a) * 7, 1 + random.nextDouble() * 3, Math.sin(a) * 7);
            later(i * 8, () -> {
                if (!alive()) return;
                Compat.spawn(world(), Compat.NAUTILUS, sl, 24, 0.6, 0.6, 0.6, 0.05);
                Guardian g = world().spawn(sl, Guardian.class, e -> {
                    e.setPersistent(false);
                    Compat.setAttribute(e, "max_health", 30);
                    Compat.setAttribute(e, "attack_damage", 6);
                    e.setHealth(30);
                });
                g.customName(Component.text("Guardian del Banco", TextColor.color(0x9FD8D2)));
                markMinion(g);
                Glow.apply(g, event.type().glowColor());
                soundAt(sl, "entity.guardian.ambient", 1.1f, 1.2f);
            });
        }
    }

    // -------------------------------------------------------- FASE II: la corriente

    /** 6. Haces Encadenados: el rayo salta de uno a otro. */
    public void chainedBeams() {
        List<Player> victims = targets();
        if (victims.isEmpty() || !alive()) return;
        soundAt(loc(), "entity.guardian.attack", 1.5f, 0.7f);
        broadcastNear(Component.text("El haz va a saltar entre ustedes.", ACCENT));

        animate(30 + victims.size() * 14, tick -> {
            if (!alive()) return;
            if (tick < 30) {
                Location from = boss.getEyeLocation();
                for (Player p : victims) {
                    if (!Fx.isFightable(p)) continue;
                    Fx.beam(from, p.getLocation().add(0, 1, 0), 0.8, q ->
                            Compat.spawn(world(), Compat.NAUTILUS, q, 1, 0, 0, 0, 0, Compat.dust(PRISM, 0.9f)));
                }
                return;
            }
            int index = (tick - 30) / 14;
            if ((tick - 30) % 14 != 0 || index >= victims.size()) return;
            Player p = victims.get(index);
            if (!Fx.isFightable(p)) return;
            Location from = index == 0
                    ? boss.getEyeLocation()
                    : victims.get(index - 1).getLocation().add(0, 1, 0);
            Fx.beam(from, p.getLocation().add(0, 1, 0), 0.25, q -> {
                Compat.spawn(world(), Compat.DOLPHIN, q, 2, 0.08, 0.08, 0.08, 0, Compat.dust(0xFF5555, 1.5f));
                Compat.spawn(world(), Compat.END_ROD, q, 1, 0.02, 0.02, 0.02, 0.01);
            });
            hit(p, 13 * damageBonus);
            Compat.spawn(world(), Compat.FLASH, p.getLocation().add(0, 1, 0), 1);
            soundAt(p.getLocation(), "entity.guardian.attack", 1.4f, 1.1f);
        }, null);
    }

    /** 7. Tinta del Abismo: una nube negra que ciega a quien se queda dentro. */
    public void abyssalInk() {
        if (!alive()) return;
        Location c = boss.getLocation();
        soundAt(c, "entity.squid.squirt", 1.6f, 0.5f);
        broadcastNear(Component.text("Suelta tinta.", ACCENT));

        animate(120, tick -> {
            double radius = Math.min(9, 2 + tick * 0.12);
            Fx.sphere(c, radius, 40, p ->
                    Compat.spawn(world(), Compat.SQUID_INK, p, 1, 0.3, 0.3, 0.3, 0, Compat.dust(ABYSS, 2.0f)));
            if (tick % 10 != 0) return;
            for (Player p : Fx.playersNear(c, radius)) {
                Compat.apply(p, "blindness", 60, 0);
                Compat.apply(p, "slowness", 60, 0);
                if (tick % 30 == 0) hit(p, 5 * damageBonus);
            }
        }, null);
    }

    /**
     * 8. Presion: castiga a quien intenta huir hacia la superficie.
     * Cuanto mas arriba estas, mas duele.
     */
    public void pressure() {
        if (!alive()) return;
        double floorY = Fx.ground(boss.getLocation(), 8).getY();
        soundAt(loc(), "block.conduit.deactivate", 1.4f, 0.5f);
        broadcastNear(Component.text("La presion sube. No huyan hacia arriba.", ACCENT));

        animate(140, tick -> {
            if (tick % 20 != 0) return;
            for (Player p : Fx.playersNear(loc(), ARENA)) {
                double height = p.getLocation().getY() - floorY;
                if (height < 6) continue;
                hit(p, Math.min(14, 4 + height * 0.6) * damageBonus);
                Compat.apply(p, "slowness", 40, 1);
                Compat.spawn(world(), Compat.BUBBLE, p.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.06);
                p.sendActionBar(Component.text("La presion te aplasta aqui arriba.",
                        NamedTextColor.RED, TextDecoration.BOLD));
                soundAt(p.getLocation(), "entity.player.hurt_drown", 1.0f, 0.8f);
            }
        }, null);
    }

    /** 9. Latigo de Marea: un latigazo de agua que barre en linea. */
    public void tideWhip() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        Location origin = boss.getLocation().add(0, 1.2, 0);
        Vector dir = target.getLocation().toVector().subtract(origin.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        final Vector run = dir.normalize();
        Set<UUID> lashed = new HashSet<>();

        soundAt(origin, "entity.player.splash.high_speed", 1.4f, 0.8f);
        animate(60, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1.2, 0);
            if (tick < 22) {
                for (double d = 2; d < 16; d += 1.0) {
                    Compat.spawn(world(), Compat.SPLASH, l.clone().add(run.clone().multiply(d)), 1,
                            0.5, 0.3, 0.5, 0, Compat.dust(BRINE, 1.3f));
                }
                return;
            }
            double reach = (tick - 22) * 1.2;
            if (reach > 16) return;
            Location p = l.clone().add(run.clone().multiply(reach));
            Compat.spawn(world(), Compat.SPLASH, p, 12, 0.6, 0.6, 0.6, 0.06);
            Compat.spawn(world(), Compat.BUBBLE_POP, p, 6, 0.5, 0.5, 0.5, 0, Compat.dust(PRISM, 1.5f));
            if (tick == 23) soundAt(l, "item.trident.riptide_2", 1.5f, 0.7f);
            for (Player v : Fx.playersNear(p, 2.6)) {
                if (!lashed.add(v.getUniqueId())) continue;
                hit(v, 15 * damageBonus);
                push(v, run.clone().multiply(1.2).setY(0.3));
            }
        }, null);
    }

    /** 10. Torbellino de Espinas: gira soltando espinas a su alrededor. */
    public void thornSpin() {
        if (!alive()) return;
        soundAt(loc(), "entity.guardian.flop", 1.3f, 0.7f);

        animate(70, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1.2, 0);
            double a = tick * 0.5;
            for (int arm = 0; arm < 4; arm++) {
                double angle = a + Math.PI * 2 * arm / 4;
                for (double d = 1; d <= 6; d += 0.6) {
                    Compat.spawn(world(), Compat.GLOW_SQUID_INK, l.clone().add(Math.cos(angle) * d, 0, Math.sin(angle) * d),
                            1, 0, 0, 0, 0, Compat.dust(PRISM, 1.3f));
                }
            }
            if (tick % 12 != 0) return;
            soundAt(l, "block.amethyst_block.break", 1.2f, 0.9f);
            for (Player p : targets(6.4)) {
                hit(p, 9 * damageBonus);
                push(p, p.getLocation().toVector().subtract(l.toVector()).normalize().setY(0.2).multiply(0.5));
            }
        }, null);
    }

    // --------------------------------------------------------- FASE III: el leviatan

    /** 11. Rayo del Abismo: un haz gigantesco que barre la arena girando. */
    public void abyssRay() {
        if (!alive()) return;
        titleNear(Component.text("RAYO DEL ABISMO", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Ponte detras de el", NamedTextColor.GRAY));
        soundAt(loc(), "entity.guardian.attack", 1.8f, 0.4f);

        animate(140, tick -> {
            if (!alive()) return;
            Location l = boss.getEyeLocation();
            if (tick < 40) {
                Fx.sphere(l, 2.0 - tick * 0.03, 24, p ->
                        Compat.spawn(world(), Compat.NAUTILUS, p, 1, 0, 0, 0, 0, Compat.dust(0xFF5555, 1.4f)));
                if (tick % 10 == 0) soundAt(l, "block.conduit.attack_target", 1.3f, 0.5f + tick / 45f);
                return;
            }
            double angle = (tick - 40) * 0.07;
            Vector dir = new Vector(Math.cos(angle), 0, Math.sin(angle));
            for (double d = 1; d <= 20; d += 0.5) {
                Location p = l.clone().add(dir.clone().multiply(d));
                Compat.spawn(world(), Compat.DOLPHIN, p, 2, 0.15, 0.15, 0.15, 0, Compat.dust(0xFF5555, 1.7f));
                Compat.spawn(world(), Compat.END_ROD, p, 1, 0.03, 0.03, 0.03, 0.01);
            }
            if (tick % 6 == 0) soundAt(l, "entity.guardian.attack", 1.2f, 0.8f);
            for (Player p : targets(21)) {
                Vector to = p.getLocation().toVector().subtract(l.toVector()).setY(0);
                if (to.lengthSquared() < 0.01 || to.normalize().dot(dir) < 0.985) continue;
                hit(p, 11 * damageBonus);
            }
        }, null);
    }

    /** 12. Implosion: succiona a todo el mundo al centro y revienta. */
    public void implosion() {
        if (!alive()) return;
        Location c = boss.getLocation();
        soundAt(c, "block.conduit.deactivate", 1.6f, 0.5f);
        titleNear(Component.text("IMPLOSION", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Sal del centro antes de que cierre", NamedTextColor.GRAY));

        animate(100, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            if (tick < 70) {
                double r = 14 - tick * 0.16;
                Fx.sphere(l, Math.max(1, r), 40, p ->
                        Compat.spawn(world(), Compat.SQUID_INK, p, 1, 0, 0, 0, 0, Compat.dust(ABYSS, 1.6f)));
                for (Player p : Fx.playersNear(l, 16)) {
                    Vector pull = l.toVector().subtract(p.getLocation().toVector());
                    if (pull.lengthSquared() < 1) continue;
                    push(p, pull.normalize().multiply(0.42));
                }
                if (tick % 8 == 0) soundAt(l, "block.bubble_column.whirlpool_ambient", 1.3f, 0.5f);
                return;
            }
            if (tick != 70) return;
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l, 3);
            Compat.spawn(world(), Compat.FLASH, l, 1);
            Compat.spawn(world(), Compat.BUBBLE, l, 120, 3.0, 3.0, 3.0, 0.4);
            soundAt(l, "entity.generic.explode", 1.8f, 0.4f);
            soundAt(l, "block.conduit.deactivate", 1.6f, 0.8f);
            for (Player p : targets(9)) {
                double d = p.getLocation().distance(l);
                hit(p, Math.max(9, 26 - d * 2) * damageBonus);
                push(p, p.getLocation().toVector().subtract(l.toVector()).normalize().setY(0.5).multiply(1.4));
            }
        }, null);
    }

    /** 13. Maelstrom: la arena entera gira y arrastra. */
    public void maelstrom() {
        if (!alive()) return;
        Location c = loc();
        soundAt(c, "block.bubble_column.whirlpool_ambient", 1.6f, 0.4f);
        broadcastNear(Component.text("El abismo entero gira.", ACCENT));

        animate(160, tick -> {
            if (!alive()) return;
            for (int layer = 0; layer < 6; layer++) {
                double r = 4 + layer * 3;
                Fx.ring(c.clone().add(0, layer * 0.8, 0), r, (int) (r * 2) + 8, tick * 0.22 + layer * 0.5, p ->
                        Compat.spawn(world(), Compat.GLOW_SQUID_INK, p, 1, 0.05, 0.05, 0.05, 0.02));
            }
            if (tick % 3 != 0) return;
            for (Player p : Fx.playersNear(c, 20)) {
                Vector radial = p.getLocation().toVector().subtract(c.toVector()).setY(0);
                if (radial.lengthSquared() < 1) continue;
                // Empuje tangencial: no te tira al centro, te hace girar sin control.
                Vector spin = new Vector(-radial.getZ(), 0, radial.getX()).normalize().multiply(0.34);
                push(p, spin.setY(-0.05));
            }
            if (tick % 25 == 0) {
                for (Player p : Fx.playersNear(c, 20)) {
                    hit(p, 6 * damageBonus);
                    Compat.apply(p, "nausea", 80, 0);
                }
            }
        }, null);
    }

    /** 14. Mordida Abisal: se lanza y muerde con todo. */
    public void abyssalBite() {
        Player target = randomTarget();
        if (target == null || !alive()) return;

        soundAt(loc(), "entity.elder_guardian.ambient", 1.6f, 0.5f);
        animate(70, tick -> {
            if (!alive() || !Fx.isFightable(target)) throw Stop.now();
            Location l = boss.getLocation();
            Location tl = target.getLocation();
            if (tick < 24) {
                Fx.beam(l.clone().add(0, 1.4, 0), tl.clone().add(0, 1, 0), 0.6, p ->
                        Compat.spawn(world(), Compat.SPLASH, p, 1, 0, 0, 0, 0, Compat.dust(0xFF5555, 1.2f)));
                return;
            }
            if (tick < 50) {
                Vector dir = tl.toVector().subtract(l.toVector());
                if (dir.lengthSquared() > 0.5) boss.setVelocity(dir.normalize().multiply(0.95));
                Compat.spawn(world(), Compat.BUBBLE, l, 8, 0.8, 0.8, 0.8, 0.06);
                for (Player p : targets(3.6)) {
                    hit(p, 20 * damageBonus);
                    push(p, dir.clone().normalize().multiply(1.1).setY(0.3));
                    soundAt(p.getLocation(), "entity.guardian.attack", 1.5f, 0.6f);
                }
                return;
            }
            if (tick == 50) soundAt(l, "entity.elder_guardian.flop", 1.4f, 0.7f);
        }, null);
    }

    // -------------------------------------------------------------- cualquier fase

    /** 15. Canto de Sal: la maldicion del guardian anciano, con aviso y sin sorpresa. */
    public void saltSong() {
        if (!alive()) return;
        Location c = loc();
        soundAt(c, "entity.elder_guardian.curse", 1.7f, 0.6f);
        broadcastNear(Component.text("Entona el canto.", ACCENT));

        animate(80, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1.6, 0);
            Fx.ring(l, 2 + tick * 0.1, 20, tick * 0.15, p ->
                    Compat.spawn(world(), Compat.DOLPHIN, p, 1, 0, 0, 0, 0, Compat.dust(PRISM, 1.4f)));
            if (tick % 16 == 0) soundAt(l, "block.conduit.ambient", 1.1f, 0.5f);
            if (tick != 60) return;
            for (Player p : Fx.playersNear(c, ARENA)) {
                Compat.apply(p, "mining_fatigue", 20 * 25, 2);
                Compat.apply(p, "slowness", 60, 0);
                hit(p, 8 * damageBonus);
                Compat.spawn(world(), Compat.GLOW_SQUID_INK, p.getLocation().add(0, 2.2, 0), 20, 0.3, 0.3, 0.3, 0,
                        Compat.dust(PRISM, 1.6f));
                soundAt(p.getLocation(), "entity.elder_guardian.curse", 1.0f, 1.2f);
            }
        }, null);
    }

    // ------------------------------------------------------------------ mensajeria

    private void broadcastNear(Component message) {
        Component line = Component.text("✦ ", ACCENT)
                .append(Component.text("Leviatan de Sal  ", ACCENT, TextDecoration.BOLD))
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
