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
import org.bukkit.entity.Bat;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Frog;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Witch;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LA BRUJA, la decima anomalia.
 *
 * Una bruja con el caldero puesto en la cabeza a modo de sombrero y un SAPO BLANCO
 * sentado en el hombro. El sapo es la mitad del jefe: en la fase 1 le canta y la
 * protege, y en la fase 2 SE BAJA, crece y pelea como un esbirro fuerte con vida
 * propia. Se le puede matar, pero matarselo tiene precio: ella se enfurece.
 *
 * El caldero no es decorado del todo: hierve, rebosa y de el sale la mitad de lo
 * que ella tira. Como la bruja de vanilla, ademas, bebe y lanza sus pocimas por su
 * cuenta; eso viene gratis con la entidad y aqui es sabor, no el plato.
 */
public final class Bruja extends BossFight {

    public static final String ID = "bruja";
    public static final TextColor ACCENT = TextColor.color(0xB07BD6);

    private static final int BREW = 0x7E4FA3;
    private static final int TOAD = 0xEFEFE6;
    private static final int ACID = 0x86B84E;

    private Frog toad;
    private BlockDisplay cauldron;
    private boolean toadFreed;
    private double damageBonus = 1.0;

    public Bruja(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        super(plugin, event, where);
        abilities.addAll(plugin.registry().brujaAbilities());
    }

    @Override
    public String bossName() {
        return "Bruja";
    }

    // ------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        Location spot = arena.clone();

        boss = world().spawn(spot, Witch.class, w -> {
            w.setPersistent(true);
            w.setRemoveWhenFarAway(false);
            w.setCanPickupItems(false);
            w.customName(Component.text("✦ ", ACCENT)
                    .append(Component.text("Bruja", ACCENT, TextDecoration.BOLD)));
            w.setCustomNameVisible(true);
        });

        Compat.setAttribute(boss, "attack_damage", 8);
        Compat.setAttribute(boss, "armor", 10);
        Compat.setAttribute(boss, "knockback_resistance", 0.7);
        Compat.setAttribute(boss, "follow_range", 64);
        Compat.setAttribute(boss, "movement_speed", 0.30);
        Compat.setAttribute(boss, "scale", 1.5);
        applyHealth(plugin.registry().scaledHealth(plugin.registry().bruja(), targets(96).size()));
        boss.setMaximumNoDamageTicks(6);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        Glow.apply(boss, event.type().glowColor());

        spawnToad();
        arrivalAnimation(spot);
    }

    /**
     * El caldero, plantado en el SUELO a su lado.
     *
     * Antes lo llevaba puesto en la cabeza como sombrero y quedaba fatal: un bloque
     * enorme flotando que ademas tapaba el nombre. Ahora es lo que siempre tuvo que
     * ser, un caldero puesto donde cocina, y solo aparece cuando le hace falta.
     */
    private void placeCauldron(Location where, int ticksToLive) {
        dropCauldron();
        Location spot = Fx.ground(where, 4);
        cauldron = Fx.blockDisplay(world(), spot.clone().add(0, 0.5, 0), Material.CAULDRON, 1.0f);
        markMinion(cauldron);
        Compat.spawn(world(), Compat.BLOCK, spot.clone().add(0, 0.5, 0), 14, 0.3, 0.2, 0.3, 0.03,
                Material.CAULDRON.createBlockData());
        soundAt(spot, "block.anvil.place", 1.1f, 1.3f);
        final BlockDisplay mine = cauldron;
        later(ticksToLive, () -> {
            if (cauldron == mine) dropCauldron();
        });
    }

    private void dropCauldron() {
        if (cauldron == null) return;
        spawned.remove(cauldron);
        Fx.safeRemove(cauldron);
        cauldron = null;
    }

    /**
     * El Sapo Blanco. La acompana ANDANDO desde el principio, no montado encima:
     * va a su lado como quien saca al perro, y en la fase 2 ya se pone serio.
     */
    private void spawnToad() {
        toad = world().spawn(boss.getLocation().add(1.2, 0, 0), Frog.class, f -> {
            f.setPersistent(true);
            f.setRemoveWhenFarAway(false);
            f.setInvulnerable(true);
            try {
                f.setVariant(Frog.Variant.WARM);
            } catch (Throwable ignored) {
            }
            f.customName(Component.text("Sapo Blanco", TextColor.color(TOAD)));
            f.setCustomNameVisible(true);
        });
        markMinion(toad);
    }

    private void arrivalAnimation(Location spot) {
        boss.setInvulnerable(true);
        busyFor(80);
        soundAt(spot, "entity.witch.celebrate", 1.6f, 0.7f);
        soundAt(spot, "block.brewing_stand.brew", 1.5f, 0.6f);

        animate(80, tick -> {
            double t = tick / 80.0;
            Fx.ring(spot, t * 8, (int) (t * 8 * 6) + 8, l -> {
                Location g = Fx.ground(l, 4);
                Compat.spawn(world(), Compat.EFFECT, g.clone().add(0, 0.2, 0), 1, 0, 0, 0, 0,
                        Compat.dust(BREW, 1.4f));
            });
            Fx.helix(spot, 1.2, 3.5, 20, 3.0, l ->
                    Compat.spawn(world(), Compat.WITCH, l, 1, 0, 0, 0, 0));
            if (tick % 14 == 0) {
                soundAt(spot, "entity.witch.ambient", 1.2f, 0.8f);
                Compat.spawn(world(), Compat.INSTANT_EFFECT, spot.clone().add(0, 2.5, 0), 10, 0.4, 0.4, 0.4, 0,
                        Compat.dust(BREW, 1.6f));
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            soundAt(spot, "entity.witch.celebrate", 1.6f, 1.0f);
            for (Player p : Fx.viewersNear(spot, 90)) {
                p.showTitle(Title.title(
                        Component.text("✦ ANOMALIA ✦", ACCENT, TextDecoration.BOLD),
                        Component.text("Bruja  ·  el caldero ya esta hirviendo", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1800), Duration.ofMillis(600))));
            }
        });
    }

    // -------------------------------------------------------------------- ambiente

    @Override
    protected void ambient() {
        if (!alive()) return;
        keepHostile();

        // El sapo la sigue andando: si se descuelga, pega un brinco para alcanzarla.
        if (toad != null && toad.isValid() && !toadFreed && ticks() % 20 == 0) {
            double d2 = toad.getLocation().distanceSquared(boss.getLocation());
            if (d2 > 400) {
                toad.teleport(boss.getLocation().add(1.2, 0, 0));
            } else if (d2 > 9) {
                Vector to = boss.getLocation().toVector().subtract(toad.getLocation().toVector());
                toad.setVelocity(to.normalize().multiply(0.42).setY(0.32));
                soundAt(toad.getLocation(), "entity.frog.step", 0.7f, 1.1f);
            }
        }

        // Le humean las manos: siempre esta a medio hechizo.
        if (ticks() % 5 == 0) {
            Compat.spawn(world(), Compat.EFFECT, boss.getEyeLocation().add(0, 0.2, 0), 2,
                    0.3, 0.2, 0.3, 0);
            if (Math.random() < 0.15) {
                Compat.spawn(world(), Compat.ENTITY_EFFECT, boss.getLocation().add(0, 1.2, 0), 1,
                        0.3, 0.4, 0.3, 0, org.bukkit.Color.fromRGB(BREW));
            }
        }

        // El sapo suelto persigue el solo: un brinco hacia el mas cercano cada tanto.
        if (toadFreed && toad != null && toad.isValid() && ticks() % 50 == 0) {
            toadHop(null);
        }
    }

    private void keepHostile() {
        if (ticks() % 20 != 0) return;
        LivingEntity current = boss instanceof org.bukkit.entity.Mob m ? m.getTarget() : null;
        if (current != null && current.isValid() && !current.isDead()) return;
        Player t = Fx.nearest(boss.getLocation(), plugin.settings().participationRadius());
        if (t != null && boss instanceof org.bukkit.entity.Mob m) m.setTarget(t);
    }

    /**
     * Un brinco del Sapo de Guerra hacia un objetivo. Si aterriza encima, pisa.
     * Lo usan el ambiente (brincos sueltos) y la habilidad Salto del Sapo (dirigido).
     */
    private void toadHop(Player chosen) {
        if (toad == null || !toad.isValid()) return;
        Player target = chosen != null ? chosen : Fx.nearest(toad.getLocation(), 18);
        if (target == null) return;

        Vector jump = target.getLocation().toVector().subtract(toad.getLocation().toVector());
        double dist = jump.length();
        if (dist < 1.5 || dist > 20) return;
        toad.setVelocity(jump.normalize().multiply(Math.min(1.1, 0.45 + dist * 0.06)).setY(0.55));
        soundAt(toad.getLocation(), "entity.frog.long_jump", 1.3f, 0.7f);

        animate(30, tick -> {
            if (toad == null || !toad.isValid()) throw Stop.now();
            Compat.spawn(world(), Compat.ENTITY_EFFECT, toad.getLocation(), 2, 0.2, 0.2, 0.2, 0,
                    Compat.dust(TOAD, 1.2f));
            for (Player p : Fx.playersNear(toad.getLocation(), 2.2)) {
                hit(p, 9 * damageBonus);
                Compat.apply(p, "slowness", 50, 1);
                push(p, p.getLocation().toVector().subtract(toad.getLocation().toVector())
                        .normalize().multiply(0.6).setY(0.35));
                Compat.spawn(world(), Compat.EXPLOSION, toad.getLocation(), 1, 0.2, 0.2, 0.2, 0);
                soundAt(toad.getLocation(), "entity.frog.step", 1.4f, 0.5f);
                throw Stop.now();
            }
        }, null);
    }

    // --------------------------------------------------------------- cambio de fase

    @Override
    protected void onPhaseChange(int from, int to) {
        if (event.bars() != null) event.bars().flash(from);
        if (to == 2) freeToad();
        if (to == 3) grandRitual();
    }

    /** FASE I -> II. El sapo deja de ser mascota: crece de golpe y entra a pelear. */
    private void freeToad() {
        if (toadFreed || !alive()) return;
        toadFreed = true;
        boss.setInvulnerable(true);
        busyFor(70);

        Location spot = boss.getLocation();
        soundAt(spot, "entity.witch.hurt", 1.6f, 0.6f);
        broadcastNear(Component.text("Le suelta la correa al sapo.", ACCENT));

        animate(70, tick -> {
            if (!alive()) return;
            if (tick == 25 && toad != null && toad.isValid()) {
                toad.setVelocity(new Vector(0.3, 0.5, 0));
                soundAt(toad.getLocation(), "entity.frog.long_jump", 1.5f, 0.5f);
            }
            if (tick == 45 && toad != null && toad.isValid()) {
                // Crece de golpe: de mascota a esbirro fuerte. Y desde aqui, se le puede matar.
                Compat.setAttribute(toad, "scale", 2.6);
                Compat.setAttribute(toad, "max_health", 140);
                toad.setHealth(140);
                Compat.setAttribute(toad, "movement_speed", 0.32);
                toad.setInvulnerable(false);
                Compat.spawn(world(), Compat.EGG_CRACK, toad.getLocation().add(0, 1, 0), 30,
                        0.8, 0.8, 0.8, 0);
                Compat.spawn(world(), Compat.ITEM_SLIME, toad.getLocation().add(0, 0.6, 0), 24,
                        0.7, 0.4, 0.7, 0);
                soundAt(toad.getLocation(), "entity.frog.ambient", 1.7f, 0.4f);
            }
            Fx.ring(Fx.ground(boss.getLocation(), 3).add(0, 0.2, 0), 2 + tick * 0.06, 20, tick * 0.2, p ->
                    Compat.spawn(world(), Compat.EFFECT, p, 1, 0, 0, 0, 0));
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            damageBonus = 1.2;
            Compat.setAttribute(boss, "movement_speed", 0.33);
            titleNear(Component.text("FASE II", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("El Sapo Blanco pelea con ella", NamedTextColor.GRAY));
        });
    }

    /** FASE III. El caldero rebosa: empieza el gran hechizo. */
    private void grandRitual() {
        if (!alive()) return;
        boss.setInvulnerable(true);
        busyFor(80);

        Location spot = boss.getLocation();
        soundAt(spot, "entity.witch.celebrate", 1.7f, 0.5f);
        titleNear(Component.text("FASE III", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("El caldero rebosa", NamedTextColor.GRAY));

        // A partir de aqui el caldero se queda puesto a su lado hasta el final.
        placeCauldron(spot, 20 * 60 * 15);

        animate(80, tick -> {
            if (!alive()) return;
            if (cauldron != null && cauldron.isValid()) {
                Compat.spawn(world(), Compat.LANDING_HONEY, cauldron.getLocation().add(0, 0.9, 0), 3,
                        0.25, 0.1, 0.25, 0);
                Compat.spawn(world(), Compat.INSTANT_EFFECT, cauldron.getLocation().add(0, 1.1, 0), 2,
                        0.3, 0.2, 0.3, 0);
            }
            Fx.helix(boss.getLocation(), 2.0, 4.5, 24, 3.0, p ->
                    Compat.spawn(world(), Compat.WITCH, p, 1, 0, 0, 0, 0));
            if (tick % 12 == 0) {
                soundAt(spot, "block.brewing_stand.brew", 1.4f, 0.5f);
                // El caldero salpica: gotas que queman donde caen.
                for (int i = 0; i < 3; i++) {
                    Location drop = Fx.ground(spot.clone().add(
                            (Math.random() - 0.5) * 10, 1, (Math.random() - 0.5) * 10), 5);
                    Compat.spawn(world(), Compat.ITEM_SLIME, drop.clone().add(0, 0.3, 0), 6,
                            0.4, 0.2, 0.4, 0);
                    Compat.spawn(world(), Compat.DRIPPING_HONEY, drop.clone().add(0, 0.6, 0), 3,
                            0.3, 0.2, 0.3, 0);
                    for (Player p : Fx.playersNear(drop, 1.6)) hit(p, 4 * damageBonus);
                }
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            damageBonus = 1.45;
            Compat.setAttribute(boss, "movement_speed", 0.36);
        });
    }

    // ------------------------------------------------------------ el sapo se muere

    /** Matar al Sapo Blanco tiene premio (un peligro menos) y castigo (ella se desata). */
    @Override
    public void onMinionDeath(LivingEntity minion) {
        if (toad == null || !minion.getUniqueId().equals(toad.getUniqueId())) return;
        toad = null;
        if (!alive()) return;

        damageBonus += 0.2;
        Compat.setAttribute(boss, "movement_speed",
                Compat.getAttribute(boss, "movement_speed", 0.33) + 0.03);
        soundAt(loc(), "entity.witch.hurt", 1.8f, 0.4f);
        titleNear(Component.text("HAN MATADO A SU SAPO", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("La Bruja se desata", NamedTextColor.GRAY));
        Compat.spawn(world(), Compat.ANGRY_VILLAGER, boss.getEyeLocation(), 10, 0.5, 0.5, 0.5, 0);
    }

    // ---------------------------------------------------------------------- muerte

    /** El caldero se le cae de la cabeza y se derrama; si el sapo vive, se va llorando. */
    @Override
    public void onDeath() {
        Location l = loc();
        soundAt(l, "entity.witch.death", 1.7f, 0.8f);

        animate(70, tick -> {
            // El caldero se vuelca y el brebaje se derrama por el suelo.
            if (tick == 20 && cauldron != null && cauldron.isValid()) {
                cauldron.teleport(Fx.ground(l, 4).add(0, 0.2, 0));
                soundAt(l, "block.anvil.land", 1.2f, 1.4f);
                Compat.spawn(world(), Compat.ITEM_SLIME, l.clone().add(0, 0.4, 0), 30, 1.0, 0.2, 1.0, 0);
                Compat.spawn(world(), Compat.FALLING_HONEY, l.clone().add(0, 0.6, 0), 20, 0.9, 0.3, 0.9, 0);
            }
            Compat.spawn(world(), Compat.EFFECT, l.clone().add(0, 1.2, 0), 3, 0.5, 0.6, 0.5, 0);
            if (tick % 12 == 0) {
                soundAt(l, "block.brewing_stand.brew", 1.1f, 0.4f);
                Fx.ring(Fx.ground(l, 4).add(0, 0.25, 0), tick * 0.12, 14, p ->
                        Compat.spawn(world(), Compat.COMPOSTER, p, 1, 0, 0, 0, 0));
            }
        }, () -> {
            Compat.spawn(world(), Compat.EXPLOSION, l, 2, 0.4, 0.4, 0.4, 0);
            soundAt(l, "block.glass.break", 1.5f, 0.6f);
            if (toad != null && toad.isValid()) {
                soundAt(toad.getLocation(), "entity.frog.death", 1.5f, 0.6f);
                Compat.spawn(world(), Compat.POOF, toad.getLocation(), 20, 0.4, 0.4, 0.4, 0.05);
                spawned.remove(toad);
                Fx.safeRemove(toad);
                toad = null;
                broadcastNear(Component.text("El sapo se marcha solo, dando brincos.", ACCENT));
            }
        });
    }

    // ============================================================== HABILIDADES ==

    /** 1. Pocima Virulenta: tres frascos por los aires, cada uno a una marca. */
    public void virulentBrew() {
        List<Player> victims = targets(22);
        if (victims.isEmpty() || !alive()) return;
        soundAt(loc(), "entity.witch.throw", 1.5f, 0.7f);
        broadcastNear(Component.text("Lanza sus pocimas.", ACCENT));

        int thrown = 0;
        for (Player victim : victims) {
            if (thrown++ >= 3) break;
            Location mark = Fx.ground(victim.getLocation(), 4);
            animate(46, tick -> {
                if (tick < 22) {
                    Fx.telegraph(world(), mark, 2.4, BREW);
                    if (tick % 4 == 0 && alive()) {
                        // El frasco en vuelo: un arco de gotas entre ella y la marca.
                        double t = tick / 22.0;
                        Vector from = boss.getEyeLocation().toVector().multiply(1 - t);
                        Vector to = mark.clone().add(0, 0.5, 0).toVector().multiply(t);
                        Location air = from.add(to).toLocation(world())
                                .add(0, Math.sin(t * Math.PI) * 4, 0);
                        Compat.spawn(world(), Compat.ENTITY_EFFECT, air, 3, 0.1, 0.1, 0.1, 0,
                                Compat.dust(BREW, 1.3f));
                    }
                    return;
                }
                if (tick != 22) return;
                Compat.spawn(world(), Compat.WITCH, mark.clone().add(0, 0.5, 0), 30, 1.0, 0.4, 1.0, 0);
                soundAt(mark, "block.glass.break", 1.3f, 0.9f);
                for (Player p : Fx.playersNear(mark, 2.8)) {
                    hit(p, 9 * damageBonus);
                    Compat.apply(p, "poison", 100, 1);
                    Compat.apply(p, "slowness", 80, 1);
                }
            }, null);
        }
    }

    /** 2. Caldero Hirviente: rebosa y deja charcos de brebaje alrededor. */
    public void boilingCauldron() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 5);
        soundAt(c, "block.brewing_stand.brew", 1.6f, 0.4f);
        broadcastNear(Component.text("Planta el caldero y lo pone a hervir.", ACCENT));
        placeCauldron(c.clone().add(1.5, 0, 0), 160);

        List<Location> puddles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2 * i / 6 + Math.random() * 0.5;
            double d = 3 + Math.random() * 6;
            puddles.add(Fx.ground(c.clone().add(Math.cos(a) * d, 1, Math.sin(a) * d), 5));
        }

        animate(140, tick -> {
            for (Location s : puddles) {
                Fx.ring(s.clone().add(0, 0.2, 0), 2.2, 12, tick * 0.1, p ->
                        Compat.spawn(world(), Compat.ITEM_SLIME, Fx.ground(p, 3).add(0, 0.2, 0), 1,
                                0, 0, 0, 0));
                if (Math.random() < 0.3) {
                    Compat.spawn(world(), Compat.BUBBLE_POP, s.clone().add(0, 0.4, 0), 2, 0.5, 0.2, 0.5, 0);
                    Compat.spawn(world(), Compat.CAMPFIRE_COSY_SMOKE, s.clone().add(0, 0.5, 0), 1,
                            0.3, 0.1, 0.3, 0.01);
                }
            }
            if (tick % 20 != 0) return;
            for (Location s : puddles) {
                for (Player p : Fx.playersNear(s, 2.4)) {
                    hit(p, 7 * damageBonus);
                    Compat.apply(p, "poison", 60, 0);
                    Compat.apply(p, "slowness", 50, 1);
                }
            }
        }, null);
    }

    /** 3. Maleficio: marca a uno y lo va royendo mientras dura. */
    public void hex() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        soundAt(loc(), "entity.witch.ambient", 1.6f, 0.5f);
        target.sendActionBar(Component.text("La Bruja te ha echado el mal de ojo.",
                NamedTextColor.RED, TextDecoration.BOLD));
        Compat.apply(target, "weakness", 160, 0);

        animate(160, tick -> {
            if (!alive() || !Fx.isFightable(target)) throw Stop.now();
            Compat.spawn(world(), Compat.SPORE_BLOSSOM_AIR, target.getLocation().add(0, 2.2, 0), 2,
                    0.3, 0.1, 0.3, 0);
            if (tick % 40 == 0) {
                hit(target, 5 * damageBonus);
                soundAt(target.getLocation(), "entity.witch.ambient", 0.8f, 1.3f);
            }
        }, () -> {
            if (Fx.isFightable(target)) {
                target.sendActionBar(Component.text("El maleficio se agota.", NamedTextColor.GREEN));
            }
        });
    }

    /** 4. Canto del Sapo: croa desde el hombro y ella aguanta el doble. Solo fase 1. */
    public void toadSong() {
        if (!alive() || toadFreed || toad == null || !toad.isValid()) return;
        soundAt(toad.getLocation(), "entity.frog.ambient", 1.5f, 0.5f);
        broadcastNear(Component.text("El sapo le croa.", ACCENT));

        animate(120, tick -> {
            if (!alive() || toad == null || !toad.isValid()) throw Stop.now();
            Compat.spawn(world(), Compat.NOTE, toad.getLocation().add(0, 0.5, 0), 1, 0.15, 0.15, 0.15, 1);
            Fx.ring(boss.getLocation().add(0, 1.0, 0), 1.5, 12, tick * 0.2, p ->
                    Compat.spawn(world(), Compat.ITEM_SLIME, p, 1, 0, 0, 0, 0, Compat.dust(TOAD, 1.2f)));
            Compat.apply(boss, "regeneration", 40, 2);
            Compat.apply(boss, "resistance", 40, 1);
            if (tick % 30 == 0) soundAt(toad.getLocation(), "entity.frog.ambient", 1.1f, 0.6f);
        }, null);
    }

    /** 5. Risa de Bruja: una carcajada que marea y empuja en cono. */
    public void witchCackle() {
        if (!alive()) return;
        Location origin = boss.getEyeLocation();
        Vector face = origin.getDirection().setY(0);
        if (face.lengthSquared() < 0.01) face = new Vector(1, 0, 0);
        final Vector dir = face.normalize();
        soundAt(origin, "entity.witch.celebrate", 1.8f, 0.6f);
        broadcastNear(Component.text("Se rie de ustedes.", ACCENT));

        animate(30, tick -> {
            if (!alive()) throw Stop.now();
            double d = 1 + tick * 0.35;
            if (d > 10) return;
            Fx.arc(boss.getLocation().add(0, 1.4, 0), dir, d, Math.PI * 0.6, (int) (d * 4), p ->
                    Compat.spawn(world(), Compat.SONIC_BOOM, p, 1, 0, 0, 0, 0));
            if (tick % 10 != 0) return;
            for (Player p : targets(10)) {
                Vector to = p.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
                if (to.lengthSquared() < 0.01 || to.normalize().dot(dir) < 0.4) continue;
                hit(p, 5 * damageBonus);
                Compat.apply(p, "nausea", 100, 0);
                push(p, to.normalize().multiply(0.5).setY(0.2));
            }
        }, null);
    }

    /** 6. Hervor Subito: una ola de brebaje hirviendo que solo pega en el borde. */
    public void suddenBoil() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 4);
        java.util.Set<java.util.UUID> scalded = new java.util.HashSet<>();
        soundAt(c, "block.lava.extinguish", 1.4f, 0.7f);
        broadcastNear(Component.text("El brebaje hierve de golpe.", ACCENT));

        animate(60, tick -> {
            if (tick < 15) {
                Fx.telegraph(world(), c, 10.0, BREW);
                return;
            }
            double radius = (tick - 15) * 0.35;
            if (radius > 10) return;
            Fx.ring(c, radius, (int) (radius * 6) + 8, p -> {
                Location g = Fx.ground(p, 4);
                Compat.spawn(world(), Compat.DRIPPING_HONEY, g.clone().add(0, 0.3, 0), 1, 0.1, 0.2, 0.1, 0,
                        Compat.dust(BREW, 1.5f));
                if (Math.random() < 0.2) {
                    Compat.spawn(world(), Compat.BUBBLE_POP, g.clone().add(0, 0.4, 0), 1, 0.1, 0.1, 0.1, 0);
                }
            });
            if (tick % 10 == 0) soundAt(c, "block.bubble_column.bubble_pop", 1.1f, 0.6f);
            for (Player p : targets(radius + 1.2)) {
                if (p.getLocation().distance(c) < radius - 1.5) continue;
                if (!scalded.add(p.getUniqueId())) continue;
                hit(p, 12 * damageBonus);
                Compat.apply(p, "slowness", 70, 1);
            }
        }, null);
    }

    /** 7. Salto del Sapo: el Sapo de Guerra se lanza sobre una marca. Fase 2. */
    public void toadSlam() {
        if (!alive() || !toadFreed || toad == null || !toad.isValid()) return;
        Player target = randomTarget();
        if (target == null) return;
        Location mark = Fx.ground(target.getLocation(), 4);
        soundAt(toad.getLocation(), "entity.frog.ambient", 1.6f, 0.4f);
        broadcastNear(Component.text("El sapo toma carrerilla.", ACCENT));

        animate(60, tick -> {
            if (toad == null || !toad.isValid()) throw Stop.now();
            if (tick < 20) {
                Fx.telegraph(world(), mark, 3.0, TOAD);
                return;
            }
            if (tick == 20) {
                Vector jump = mark.clone().add(0, 0.5, 0).toVector().subtract(toad.getLocation().toVector());
                toad.setVelocity(jump.normalize().multiply(1.2).setY(0.75));
                soundAt(toad.getLocation(), "entity.frog.long_jump", 1.5f, 0.5f);
                return;
            }
            Compat.spawn(world(), Compat.COMPOSTER, toad.getLocation(), 2, 0.3, 0.3, 0.3, 0,
                    Compat.dust(TOAD, 1.3f));
            boolean landed = toad.isOnGround() || toad.getLocation().distanceSquared(mark) < 4;
            if (tick < 30 || !landed) return;
            Location bl = toad.getLocation();
            Compat.spawn(world(), Compat.EXPLOSION, bl, 2, 0.4, 0.2, 0.4, 0);
            soundAt(bl, "entity.generic.explode", 1.2f, 0.9f);
            for (Player p : Fx.playersNear(bl, 4.0)) {
                hit(p, 14 * damageBonus);
                Compat.apply(p, "slowness", 60, 2);
                push(p, p.getLocation().toVector().subtract(bl.toVector())
                        .normalize().multiply(1.0).setY(0.5));
            }
            throw Stop.now();
        }, null);
    }

    /** 8. Lengua Latigo: el sapo engancha al que mas se aleja y lo trae. Fase 2. */
    public void tongueWhip() {
        if (!alive() || !toadFreed || toad == null || !toad.isValid()) return;
        Player target = Fx.farthest(toad.getLocation(), plugin.settings().participationRadius());
        if (target == null) return;
        soundAt(toad.getLocation(), "entity.frog.tongue", 1.6f, 0.7f);
        target.sendActionBar(Component.text("La lengua del sapo te engancha.",
                NamedTextColor.RED, TextDecoration.BOLD));

        animate(45, tick -> {
            if (toad == null || !toad.isValid() || !Fx.isFightable(target)) throw Stop.now();
            Fx.beam(toad.getLocation().add(0, 0.6, 0), target.getLocation().add(0, 1, 0), 0.8, p ->
                    Compat.spawn(world(), Compat.SNEEZE, p, 1, 0.05, 0.05, 0.05, 0,
                            Compat.dust(0xE8A0B4, 1.3f)));
            if (tick < 10 || tick % 5 != 0) return;
            Vector pull = toad.getLocation().toVector().subtract(target.getLocation().toVector());
            if (pull.length() < 3) {
                hit(target, 8 * damageBonus);
                Compat.apply(target, "slowness", 60, 2);
                soundAt(target.getLocation(), "entity.frog.eat", 1.3f, 0.8f);
                throw Stop.now();
            }
            push(target, pull.normalize().multiply(0.7).setY(0.2));
        }, null);
    }

    /** 9. Lluvia de Sapos: sapos pequenos que caen del cielo y revientan en veneno. */
    public void toadRain() {
        if (!alive()) return;
        List<Player> victims = targets();
        if (victims.isEmpty()) return;
        soundAt(loc(), "entity.witch.throw", 1.5f, 0.5f);
        broadcastNear(Component.text("Llueven sapos.", ACCENT));

        int count = 5 + random.nextInt(3);
        for (int i = 0; i < count; i++) {
            Player victim = victims.get(random.nextInt(victims.size()));
            Location mark = Fx.ground(victim.getLocation().clone().add(
                    (Math.random() - 0.5) * 6, 0, (Math.random() - 0.5) * 6), 5);
            later(i * 8, () -> {
                if (!alive()) return;
                Fx.telegraph(world(), mark, 1.8, ACID);
                Frog drop = world().spawn(mark.clone().add(0, 10, 0), Frog.class, f -> {
                    f.setPersistent(false);
                    f.setInvulnerable(true);
                    f.setSilent(true);
                    Compat.setAttribute(f, "scale", 0.7);
                });
                markMinion(drop);
                animate(40, tick -> {
                    if (!drop.isValid()) throw Stop.now();
                    Compat.spawn(world(), Compat.EFFECT, drop.getLocation(), 1, 0.1, 0.1, 0.1, 0,
                            Compat.dust(ACID, 1.1f));
                    if (!drop.isOnGround()) return;
                    Location bl = drop.getLocation();
                    Compat.spawn(world(), Compat.WITCH, bl, 16, 0.6, 0.3, 0.6, 0);
                    soundAt(bl, "entity.frog.death", 1.1f, 1.2f);
                    for (Player p : Fx.playersNear(bl, 2.2)) {
                        hit(p, 8 * damageBonus);
                        Compat.apply(p, "poison", 80, 1);
                    }
                    spawned.remove(drop);
                    Fx.safeRemove(drop);
                    throw Stop.now();
                }, () -> {
                    if (drop.isValid()) {
                        spawned.remove(drop);
                        Fx.safeRemove(drop);
                    }
                });
            });
        }
    }

    /** 10. Brebaje Oscuro: una nube que ciega y envenena a quien se quede dentro. */
    public void darkBrew() {
        if (!alive()) return;
        Location c = loc();
        soundAt(c, "entity.witch.throw", 1.5f, 0.4f);
        broadcastNear(Component.text("Suelta el brebaje oscuro.", ACCENT));

        animate(140, tick -> {
            double r = Math.min(9, 2 + tick * 0.1);
            Fx.sphere(c, r, 34, p -> {
                Compat.spawn(world(), Compat.INSTANT_EFFECT, p, 1, 0.3, 0.3, 0.3, 0, Compat.dust(0x3A2A4A, 1.5f));
                if (Math.random() < 0.1) Compat.spawn(world(), Compat.INSTANT_EFFECT, p, 1, 0, 0, 0, 0);
            });
            if (tick % 15 != 0) return;
            for (Player p : Fx.playersNear(c, r)) {
                Compat.apply(p, "blindness", 70, 0);
                Compat.apply(p, "poison", 60, 0);
                if (tick % 30 == 0) hit(p, 5 * damageBonus);
            }
        }, null);
    }

    /** 11. El Gran Hechizo: un circulo enorme, diez segundos de cuenta y adentro nadie. */
    public void grandSpell() {
        if (!alive()) return;
        final Location center = Fx.ground(boss.getLocation(), 5);
        final double radius = 14;
        soundAt(center, "entity.evoker.prepare_summon", 1.6f, 0.5f);
        titleNear(Component.text("EL GRAN HECHIZO", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Cinco segundos para salir", NamedTextColor.GRAY));

        // Cinco segundos, no diez: con diez daba tiempo a salir andando sin mirar.
        animate(100, tick -> {
            if (!alive()) throw Stop.now();
            double t = tick / 100.0;
            Fx.ring(center, radius, (int) (30 + t * 80), tick * 0.05, p ->
                    Compat.spawn(world(), Compat.WITCH, Fx.ground(p, 5).add(0, 0.3, 0), 1, 0, 0, 0, 0,
                            Compat.dust(BREW, (float) (1.3 + t))));
            Fx.helix(center, 1.5, 3.0 + t * 3, 20, 2.5, p ->
                    Compat.spawn(world(), Compat.ENTITY_EFFECT, p, 1, 0, 0, 0, 0));
            if (tick % 20 == 0) {
                int left = (100 - tick) / 20;
                soundAt(center, "block.note_block.bell", 1.3f, 0.4f + (float) t);
                for (Player p : Fx.viewersNear(center, 60)) {
                    boolean safe = p.getLocation().distance(center) > radius;
                    p.sendActionBar(Component.text("El Gran Hechizo  ", NamedTextColor.GRAY)
                            .append(Component.text(left + "s", ACCENT, TextDecoration.BOLD))
                            .append(Component.text(safe ? "   estas fuera" : "   ESTAS DENTRO",
                                    safe ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD)));
                }
            }
        }, () -> {
            if (!alive()) return;
            Compat.spawn(world(), Compat.FLASH, center.clone().add(0, 1, 0), 1);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, center, 3);
            soundAt(center, "entity.generic.explode", 1.8f, 0.5f);
            soundAt(center, "entity.witch.celebrate", 1.6f, 0.4f);
            for (Player p : Fx.playersNear(center, radius)) {
                double d = p.getLocation().distance(center);
                hit(p, Math.max(12, 34 - d * 1.2) * damageBonus);
                Compat.apply(p, "poison", 100, 1);
                push(p, p.getLocation().toVector().subtract(center.toVector())
                        .normalize().setY(0.7).multiply(1.4));
            }
        });
    }

    /** 12. Nube de Murcielagos: una bandada que persigue y no deja ver. */
    public void batCloud() {
        if (!alive()) return;
        Location c = boss.getLocation().add(0, 2, 0);
        soundAt(c, "entity.bat.takeoff", 1.6f, 0.6f);
        broadcastNear(Component.text("Suelta a sus murcielagos.", ACCENT));

        List<Bat> bats = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            Bat bat = world().spawn(c.clone().add(Math.cos(a) * 1.5, 0, Math.sin(a) * 1.5), Bat.class, b -> {
                b.setPersistent(false);
                b.setInvulnerable(true);
                b.setSilent(true);
                b.setAwake(true);
            });
            markMinion(bat);
            bats.add(bat);
        }

        animate(160, tick -> {
            List<Player> pool = targets(24);
            if (pool.isEmpty()) throw Stop.now();
            for (int i = 0; i < bats.size(); i++) {
                Bat bat = bats.get(i);
                if (!bat.isValid()) continue;
                Player chase = pool.get(i % pool.size());
                if (tick % 5 == 0) {
                    Vector to = chase.getEyeLocation().toVector().subtract(bat.getLocation().toVector());
                    if (to.lengthSquared() > 0.5) bat.setVelocity(to.normalize().multiply(0.45));
                }
                if (tick % 3 == 0) {
                    Compat.spawn(world(), Compat.SMOKE, bat.getLocation(), 1, 0.1, 0.1, 0.1, 0);
                }
                if (tick % 10 == 0 && bat.getLocation().distanceSquared(chase.getEyeLocation()) < 2.2) {
                    hit(chase, 4 * damageBonus);
                    Compat.apply(chase, "darkness", 60, 0);
                    soundAt(chase.getLocation(), "entity.bat.hurt", 1.0f, 0.8f);
                }
            }
        }, () -> {
            for (Bat bat : bats) {
                if (!bat.isValid()) continue;
                Compat.spawn(world(), Compat.POOF, bat.getLocation(), 6, 0.2, 0.2, 0.2, 0.02);
                spawned.remove(bat);
                Fx.safeRemove(bat);
            }
        });
    }

    /** 13. Pocima Final: marcas bajo TODOS, tres tandas seguidas. */
    public void finalBrew() {
        if (!alive()) return;
        soundAt(loc(), "entity.witch.throw", 1.7f, 0.4f);
        broadcastNear(Component.text("Vacia el caldero entero.", ACCENT));

        for (int wave = 0; wave < 3; wave++) {
            later(wave * 30, () -> {
                if (!alive()) return;
                for (Player victim : targets()) {
                    Location mark = Fx.ground(victim.getLocation(), 4);
                    animate(30, tick -> {
                        if (tick < 20) {
                            Fx.telegraph(world(), mark, 2.2, BREW);
                            return;
                        }
                        if (tick != 20) return;
                        Compat.spawn(world(), Compat.WITCH, mark.clone().add(0, 0.5, 0), 26,
                                0.9, 0.4, 0.9, 0);
                        soundAt(mark, "block.glass.break", 1.2f, 0.8f);
                        for (Player p : Fx.playersNear(mark, 2.5)) {
                            hit(p, 10 * damageBonus);
                            switch (random.nextInt(3)) {
                                case 0 -> Compat.apply(p, "poison", 80, 1);
                                case 1 -> Compat.apply(p, "slowness", 80, 2);
                                default -> Compat.apply(p, "weakness", 100, 0);
                            }
                        }
                    }, null);
                }
            });
        }
    }

    /** 14. Trago Amargo: bebe de su propio caldero y suelta el eructo. */
    public void bitterSip() {
        if (!alive()) return;
        soundAt(loc(), "entity.generic.drink", 1.5f, 0.7f);
        broadcastNear(Component.text("Bebe del caldero.", ACCENT));

        animate(40, tick -> {
            if (!alive()) throw Stop.now();
            Compat.spawn(world(), Compat.ENTITY_EFFECT, boss.getEyeLocation(), 2, 0.2, 0.2, 0.2, 0,
                    Compat.dust(BREW, 1.2f));
            if (tick != 30) return;
            Compat.apply(boss, "resistance", 100, 1);
            Compat.apply(boss, "speed", 100, 0);
            // El eructo: una nubecita acida alrededor. Poco dano, mucho teatro.
            soundAt(loc(), "entity.witch.celebrate", 1.4f, 0.5f);
            Fx.ring(boss.getLocation().add(0, 1.2, 0), 2.5, 18, p ->
                    Compat.spawn(world(), Compat.ITEM_SLIME, p, 2, 0.2, 0.3, 0.2, 0, Compat.dust(ACID, 1.5f)));
            for (Player p : targets(4)) {
                hit(p, 6 * damageBonus);
                Compat.apply(p, "nausea", 80, 0);
            }
        }, null);
    }

    /**
     * 15. Rayo Arcano: un haz que salta de uno a otro.
     *
     * La Bruja no es solo la que tira frascos: esto es magia a secas, sin objeto de por
     * medio, y encadena a todo el grupo si estan juntos.
     */
    public void arcaneBolt() {
        if (!alive()) return;
        List<Player> pool = targets(28);
        if (pool.isEmpty()) return;
        soundAt(loc(), "entity.illusioner.cast_spell", 1.6f, 0.7f);
        broadcastNear(Component.text("Traza un rayo.", ACCENT));

        Location from = boss.getEyeLocation();
        java.util.Set<java.util.UUID> chained = new java.util.HashSet<>();
        Location cursor = from.clone();
        int jumps = 0;
        for (Player p : pool) {
            if (jumps++ >= 5) break;
            if (!chained.add(p.getUniqueId())) continue;
            final Location a = cursor.clone();
            final Location b = p.getEyeLocation().clone();
            final int delay = jumps * 6;
            later(delay, () -> {
                if (!alive() || !Fx.isFightable(p)) return;
                Fx.beam(a, b, 0.5, q -> {
                    Compat.spawn(world(), Compat.ELECTRIC_SPARK, q, 1, 0.04, 0.04, 0.04, 0.01);
                    Compat.spawn(world(), Compat.ENTITY_EFFECT, q, 1, 0, 0, 0, 0,
                            org.bukkit.Color.fromRGB(BREW));
                });
                hit(p, 13 * damageBonus);
                Compat.apply(p, "slowness", 60, 1);
                Compat.spawn(world(), Compat.INSTANT_EFFECT, b, 12, 0.3, 0.4, 0.3, 0);
                soundAt(b, "entity.illusioner.mirror_move", 1.1f, 1.4f);
            });
            cursor = p.getEyeLocation().clone();
        }
    }

    /**
     * 16. Circulo de Runas: dibuja runas en el suelo y lo que quede dentro se marchita.
     *
     * Es lo contrario del Gran Hechizo: aqui no hay cuenta atras ni aviso enorme, hay
     * un sitio del suelo que deja de ser tuyo mientras dure.
     */
    public void runeCircle() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 5);
        soundAt(c, "block.enchantment_table.use", 1.6f, 0.6f);
        broadcastNear(Component.text("Dibuja runas.", ACCENT));

        animate(160, tick -> {
            if (tick % 2 == 0) {
                // Dos anillos girando en sentidos opuestos: se lee como un sello.
                Fx.ring(c.clone().add(0, 0.15, 0), 6.0, 26, tick * 0.05, p ->
                        Compat.spawn(world(), Compat.ENCHANT, Fx.ground(p, 3).add(0, 0.2, 0), 1,
                                0, 0, 0, 0));
                Fx.ring(c.clone().add(0, 0.15, 0), 3.6, 16, -tick * 0.08, p ->
                        Compat.spawn(world(), Compat.INSTANT_EFFECT, Fx.ground(p, 3).add(0, 0.2, 0), 1,
                                0, 0, 0, 0));
            }
            if (tick % 20 != 0) return;
            soundAt(c, "block.enchantment_table.use", 0.8f, 1.2f);
            for (Player p : Fx.playersNear(c, 6.2)) {
                hit(p, 9 * damageBonus);
                Compat.apply(p, "weakness", 80, 0);
                Compat.apply(p, "mining_fatigue", 80, 1);
                Compat.spawn(world(), Compat.ENTITY_EFFECT, p.getLocation().add(0, 1.2, 0), 4,
                        0.3, 0.5, 0.3, 0, org.bukkit.Color.fromRGB(0x3A2A4A));
            }
        }, null);
    }

    /**
     * 17. Mano de Bruja: agarra a alguien con magia, lo levanta y lo suelta.
     *
     * Sin frasco, sin sapo y sin caldero: solo ella y el gesto. Levantar a un jugador
     * pasa por lift(), que es lo que concede el permiso de vuelo temporal.
     */
    public void witchHand() {
        if (!alive()) return;
        List<Player> pool = targets(18);
        if (pool.isEmpty()) return;
        soundAt(loc(), "entity.evoker.prepare_attack", 1.5f, 0.8f);
        broadcastNear(Component.text("Cierra la mano.", ACCENT));

        for (Player victim : pool) {
            animate(70, tick -> {
                if (!alive() || !Fx.isFightable(victim)) throw Stop.now();
                if (tick < 40) {
                    lift(victim, new Vector(0, 0.16, 0));
                    Compat.apply(victim, "slow_falling", 40, 0);
                    Fx.ring(victim.getLocation().add(0, 1.0, 0), 0.9, 8, tick * 0.3, p ->
                            Compat.spawn(world(), Compat.ENTITY_EFFECT, p, 1, 0, 0, 0, 0,
                                    org.bukkit.Color.fromRGB(BREW)));
                    Fx.beam(boss.getEyeLocation(), victim.getLocation().add(0, 1, 0), 1.0, q ->
                            Compat.spawn(world(), Compat.EFFECT, q, 1, 0, 0, 0, 0));
                    return;
                }
                if (tick != 40) return;
                victim.setVelocity(new Vector(0, -1.9, 0));
                hit(victim, 15 * damageBonus);
                Compat.spawn(world(), Compat.INSTANT_EFFECT, victim.getLocation().add(0, 1, 0), 20,
                        0.4, 0.5, 0.4, 0);
                soundAt(victim.getLocation(), "entity.illusioner.prepare_blindness", 1.3f, 0.7f);
                victim.sendActionBar(Component.text("Te ha soltado.", NamedTextColor.RED, TextDecoration.BOLD));
            }, null);
        }
    }

    // ------------------------------------------------------------------ mensajeria

    private void broadcastNear(Component message) {
        Component line = Component.text("✦ ", ACCENT)
                .append(Component.text("Bruja  ", ACCENT, TextDecoration.BOLD))
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
