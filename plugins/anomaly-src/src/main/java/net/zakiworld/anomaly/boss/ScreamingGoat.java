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
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Goat;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * LA CABRA GRITONA, la segunda anomalia.
 *
 * La cabra chillona del Aether, pero del tamano de una casa. Todo lo suyo sale del
 * grito: el berrido empuja, parte el cielo en rayos y la hace arder en blanco. No
 * tiene un solo truco de mago; es un animal enorme que embiste, salta y grita.
 *
 * Los rayos son solo el efecto visual (strikeLightningEffect): el dano lo pone el
 * plugin a mano. Un rayo de verdad prende fuego al terreno, y esto es un survival.
 */
public final class ScreamingGoat extends BossFight {

    public static final String ID = "cabra_gritona";
    public static final TextColor ACCENT = TextColor.color(0xEDF3F7);

    private static final int WHITE_HOT = 0xFFFFFF;
    private static final int STORM = 0xAFC9DE;
    private static final int HORN = 0xD9C08A;

    private boolean hornBroken;
    private boolean skyAnswered;
    private double damageBonus = 1.0;
    private long lastGore;

    public ScreamingGoat(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        super(plugin, event, where);
        abilities.addAll(plugin.registry().goatAbilities());
    }

    @Override
    public String bossName() {
        return "Cabra Gritona";
    }

    // ------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        Location spot = arena.clone();

        boss = world().spawn(spot, Goat.class, g -> {
            g.setAdult();
            g.setPersistent(true);
            g.setRemoveWhenFarAway(false);
            g.setCanPickupItems(false);
            try {
                g.setScreaming(true);
            } catch (Throwable ignored) {
                // en versiones sin la variante chillona se queda como cabra normal
            }
            g.customName(Component.text("✦ ", ACCENT)
                    .append(Component.text("Cabra Gritona", ACCENT, TextDecoration.BOLD)));
            g.setCustomNameVisible(true);
        });

        Compat.setAttribute(boss, "attack_damage", 12);
        Compat.setAttribute(boss, "armor", 12);
        Compat.setAttribute(boss, "knockback_resistance", 1.0);
        Compat.setAttribute(boss, "follow_range", 60);
        Compat.setAttribute(boss, "movement_speed", 0.34);
        // Una cabra del tamano normal no impone; el atributo de escala existe desde
        // 1.20.5 y si la version no lo trae simplemente se queda pequena.
        Compat.setAttribute(boss, "scale", 2.4);
        applyHealth(plugin.registry().scaledHealth(plugin.registry().goat(), targets(96).size()));
        boss.setMaximumNoDamageTicks(6);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        Glow.apply(boss, event.type().glowColor());

        arrivalAnimation(spot);
    }

    /** La llegada: una tormenta que se cierra sobre el cerro y un berrido. */
    private void arrivalAnimation(Location spot) {
        boss.setInvulnerable(true);
        busyFor(70);
        soundAt(spot, "entity.lightning_bolt.thunder", 1.4f, 0.7f);
        soundAt(spot, "item.goat_horn.play", 1.4f, 0.8f);

        animate(70, tick -> {
            double t = tick / 70.0;
            double radius = 9.0 - t * 7.0;
            Fx.ring(spot, radius, (int) (16 + radius * 4), tick * -0.14, l ->
                    Compat.spawn(world(), Compat.DUST, Fx.ground(l, 3).add(0, 0.15, 0), 1, 0, 0, 0, 0,
                            Compat.dust(STORM, 1.5f)));
            if (tick % 5 == 0) {
                Compat.spawn(world(), Compat.CLOUD, spot.clone().add(0, 3, 0), 12, 2.0, 0.6, 2.0, 0.02);
            }
            if (tick % 18 == 0) {
                double a = Math.random() * Math.PI * 2;
                Location bolt = Fx.ground(spot.clone().add(Math.cos(a) * 6, 0, Math.sin(a) * 6), 5);
                world().strikeLightningEffect(bolt);
                soundAt(bolt, "entity.lightning_bolt.impact", 1.0f, 1.2f);
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            world().strikeLightningEffect(spot);
            Compat.spawn(world(), Compat.FLASH, spot.clone().add(0, 1, 0), 1);
            soundAt(spot, "entity.goat.screaming_ambient", 1.6f, 0.7f);
            soundAt(spot, "entity.lightning_bolt.thunder", 1.6f, 1.0f);
            for (Player p : Fx.viewersNear(spot, 80)) {
                p.showTitle(Title.title(
                        Component.text("✦ ANOMALIA ✦", ACCENT, TextDecoration.BOLD),
                        Component.text("La Cabra Gritona baja del cerro", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1600), Duration.ofMillis(600))));
            }
        });
    }

    // ------------------------------------------------------------------- ambiente

    @Override
    protected void ambient() {
        if (!alive()) return;
        pursue();
        if (ticks() % 3 != 0) return;
        Location l = boss.getLocation().add(0, 1.4, 0);
        Compat.spawn(world(), Compat.DUST, l, 2, 0.6, 0.5, 0.6, 0, Compat.dust(WHITE_HOT, 1.0f));
        if (ticks() % 40 == 0) {
            Compat.spawn(world(), Compat.WHITE_SMOKE, l, 5, 0.5, 0.4, 0.5, 0.01);
        }
        if (skyAnswered && ticks() % 12 == 0) {
            Compat.spawn(world(), Compat.ELECTRIC_SPARK, l, 4, 0.7, 0.6, 0.7, 0.03);
        }
    }

    /**
     * La cabra es un animal PASIVO en Minecraft: no tiene IA que persiga ni que pegue,
     * por eso se quedaba parada mirando. Aqui se le pone la agresividad a mano.
     */
    private void pursue() {
        if (busy() || ticks() % 4 != 0) return;
        Player target = Fx.nearest(boss.getLocation(), plugin.settings().participationRadius());
        if (target == null) return;

        Location l = boss.getLocation();
        Location tl = target.getLocation();
        if (l.getWorld() == null || !l.getWorld().equals(tl.getWorld())) return;

        double distSq = l.distanceSquared(tl);
        Vector dir = tl.toVector().subtract(l.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        dir.normalize();

        // Siempre encarada al objetivo, que si no da la sensacion de estar despistada.
        Location face = l.clone();
        face.setDirection(dir);
        boss.setRotation(face.getYaw(), 0);

        if (distSq > 9) {
            double speed = phase() == 3 ? 0.30 : phase() == 2 ? 0.26 : 0.22;
            boss.setVelocity(dir.multiply(speed).setY(boss.getVelocity().getY()));
            if (ticks() % 24 == 0) soundAt(l, "entity.goat.step", 0.9f, 0.9f);
            return;
        }

        // A tiro: cabezazo con su enfriamiento propio, aparte de las habilidades.
        if (ticks() - lastGore < 20) return;
        lastGore = ticks();
        hit(target, 11 * damageBonus);
        push(target, dir.clone().multiply(0.9).setY(0.4));
        Compat.spawn(world(), Compat.CRIT, tl.clone().add(0, 1, 0), 16, 0.3, 0.4, 0.3, 0.2);
        soundAt(tl, "entity.goat.ram_impact", 1.2f, 0.9f);
    }

    // --------------------------------------------------------------- cambio de fase

    @Override
    protected void onPhaseChange(int from, int to) {
        if (event.bars() != null) event.bars().flash(from);
        if (to == 2) breakHorn();
        if (to == 3) skyAnswers();
    }

    /** FASE I -> II. Se le parte un cuerno de tanto embestir y se vuelve mas rapida. */
    private void breakHorn() {
        if (hornBroken || !alive()) return;
        hornBroken = true;
        boss.setInvulnerable(true);
        busyFor(80);

        Location spot = boss.getLocation();
        soundAt(spot, "entity.goat.horn_break", 1.6f, 0.7f);
        soundAt(spot, "entity.goat.screaming_ambient", 1.4f, 0.6f);
        broadcastNear(Component.text("Se le parte un cuerno.", ACCENT));

        animate(80, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1.8, 0);
            if (tick < 40) {
                Compat.spawn(world(), Compat.DUST, l, 6, 0.7, 0.4, 0.7, 0, Compat.dust(HORN, 1.4f));
                if (tick % 8 == 0) soundAt(l, "entity.goat.hurt", 1.2f, 0.7f);
                return;
            }
            if (tick != 40) return;
            try {
                ((Goat) boss).setLeftHorn(false);
            } catch (Throwable ignored) {
                // si la version no expone los cuernos, el efecto se queda en particulas
            }
            Compat.spawn(world(), Compat.ITEM, l, 60, 0.5, 0.5, 0.5, 0.2, new org.bukkit.inventory.ItemStack(Material.BONE));
            Compat.spawn(world(), Compat.EXPLOSION, l, 2, 0.4, 0.4, 0.4, 0);
            world().strikeLightningEffect(Fx.ground(boss.getLocation(), 4));
            soundAt(l, "entity.goat.horn_break", 1.6f, 1.0f);
            soundAt(l, "entity.lightning_bolt.impact", 1.2f, 1.4f);
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            Compat.setAttribute(boss, "movement_speed", 0.42);
            Compat.setAttribute(boss, "attack_damage", 15);
            titleNear(Component.text("FASE II", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("Un cuerno menos y el doble de rabia", NamedTextColor.GRAY));
        });
    }

    /** FASE II -> III. El cielo le contesta: arde en blanco y no para de tronar. */
    private void skyAnswers() {
        if (skyAnswered || !alive()) return;
        skyAnswered = true;
        boss.setInvulnerable(true);
        busyFor(100);

        Location spot = boss.getLocation();
        soundAt(spot, "entity.lightning_bolt.thunder", 1.8f, 0.6f);
        titleNear(Component.text("EL CIELO RESPONDE", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Cada grito trae tormenta", NamedTextColor.GRAY));

        animate(100, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            Fx.sphere(l.clone().add(0, 1.4, 0), 1.6 + Math.sin(tick * 0.2) * 0.3, 22, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(WHITE_HOT, 1.6f)));
            if (tick % 12 == 0) {
                double a = tick * 0.5;
                Location bolt = Fx.ground(l.clone().add(Math.cos(a) * 7, 0, Math.sin(a) * 7), 5);
                world().strikeLightningEffect(bolt);
                soundAt(bolt, "entity.lightning_bolt.impact", 1.1f, 1.1f);
                for (Player p : Fx.playersNear(bolt, 3.0)) hit(p, 6 * damageBonus);
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            damageBonus = 1.25;
            Compat.setAttribute(boss, "movement_speed", 0.46);
            Compat.spawn(world(), Compat.FLASH, boss.getLocation().add(0, 1.4, 0), 1);
            soundAt(boss.getLocation(), "entity.goat.screaming_ambient", 1.8f, 0.5f);
        });
    }

    // ---------------------------------------------------------------------- muerte

    @Override
    public void onDeath() {
        Location l = loc();
        soundAt(l, "entity.goat.screaming_death", 1.8f, 0.7f);
        soundAt(l, "entity.lightning_bolt.thunder", 1.4f, 0.8f);

        animate(70, tick -> {
            double t = tick / 70.0;
            Fx.ring(l.clone().add(0, 0.3 + t * 2.0, 0), 2.4 * (1 - t) + 0.4, 20, tick * -0.3, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(WHITE_HOT, 1.5f)));
            if (tick % 14 == 0) {
                double a = Math.random() * Math.PI * 2;
                Location bolt = Fx.ground(l.clone().add(Math.cos(a) * (5 - t * 4), 0, Math.sin(a) * (5 - t * 4)), 5);
                world().strikeLightningEffect(bolt);
                soundAt(bolt, "entity.lightning_bolt.impact", 0.9f, 1.3f);
            }
            if (tick % 8 == 0) {
                Compat.spawn(world(), Compat.WHITE_SMOKE, l.clone().add(0, 1, 0), 14, 0.6, 0.8, 0.6, 0.04);
            }
        }, () -> {
            Compat.spawn(world(), Compat.FLASH, l.clone().add(0, 1, 0), 1);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l, 1);
            world().strikeLightningEffect(l);
            soundAt(l, "item.goat_horn.play", 1.6f, 0.6f);
            soundAt(l, "entity.lightning_bolt.thunder", 1.2f, 1.4f);
        });
    }

    // ============================================================== HABILIDADES ==

    /**
     * El grito, del que sale todo lo demas: empuja, hace dano, parte el cielo en
     * rayos sobre los alcanzados y la deja ardiendo en blanco.
     *
     * @param spread amplitud del cono en grados; 360 la convierte en circular
     * @param bolts  cuantos rayos caen sobre los que se han comido el grito
     */
    private void scream(double spread, double reach, double damage, double pushStrength, int bolts, int windup) {
        if (!alive()) return;
        Location origin = boss.getLocation().add(0, 1.3, 0);
        Vector facing = boss.getLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 0.01) facing = new Vector(1, 0, 0);
        final Vector dir = facing.normalize();
        final boolean circular = spread >= 359;

        soundAt(origin, "entity.goat.prepare_ram", 1.2f, 0.8f);

        animate(windup + 40, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1.3, 0);

            if (tick < windup) {
                // Toma aire: se hincha y el blanco sube de intensidad.
                double r = 0.6 + (tick / (double) windup) * 1.4;
                Fx.sphere(l, r, 20, p -> Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0,
                        Compat.dust(WHITE_HOT, 1.2f)));
                if (tick % 6 == 0) soundAt(l, "entity.goat.long_jump", 0.8f, 0.6f + tick / (float) windup);
                return;
            }
            if (tick == windup) {
                Compat.spawn(world(), Compat.FLASH, l, 1);
                soundAt(l, "entity.goat.screaming_ambient", 1.8f, 0.6f);
                soundAt(l, "entity.warden.sonic_boom", 1.0f, 1.6f);

                List<Player> caught = new java.util.ArrayList<>();
                for (Player p : targets(reach)) {
                    Vector to = p.getLocation().toVector().subtract(l.toVector()).setY(0);
                    if (!circular && to.lengthSquared() > 0.01
                            && to.clone().normalize().dot(dir) < Math.cos(Math.toRadians(spread / 2))) {
                        continue;
                    }
                    caught.add(p);
                    hit(p, damage * damageBonus);
                    Vector away = p.getLocation().toVector().subtract(l.toVector());
                    if (away.lengthSquared() < 0.01) away = dir.clone();
                    push(p, away.normalize().setY(0.55).multiply(pushStrength));
                    Compat.apply(p, "nausea", 60, 0);
                    soundAt(p.getLocation(), "entity.goat.screaming_ram_impact", 1.2f, 0.9f);
                }

                // Los rayos caen sobre los que se han comido el grito; si no hay nadie,
                // reparte unos cuantos por el cono para que igual se vea la tormenta.
                for (int i = 0; i < bolts; i++) {
                    Location strike;
                    if (i < caught.size()) {
                        strike = Fx.ground(caught.get(i).getLocation(), 4);
                    } else {
                        double a = circular
                                ? Math.PI * 2 * i / bolts
                                : Math.atan2(dir.getZ(), dir.getX()) + (Math.random() - 0.5) * Math.toRadians(spread);
                        double d = 4 + Math.random() * (reach - 4);
                        strike = Fx.ground(l.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d), 5);
                    }
                    final Location bolt = strike;
                    later(i * 3, () -> {
                        world().strikeLightningEffect(bolt);
                        Compat.spawn(world(), Compat.ELECTRIC_SPARK, bolt.clone().add(0, 0.5, 0), 40,
                                0.8, 0.4, 0.8, 0.2);
                        soundAt(bolt, "entity.lightning_bolt.impact", 1.3f, 1.0f);
                        for (Player p : Fx.playersNear(bolt, 3.0)) hit(p, (damage * 0.45) * damageBonus);
                    });
                }
                return;
            }

            // La onda del grito, avanzando por el suelo.
            double radius = (tick - windup) * 0.9;
            if (radius > reach) return;
            if (circular) {
                Fx.ring(l, radius, (int) (radius * 6) + 8, p ->
                        Compat.spawn(world(), Compat.DUST, Fx.ground(p, 3).add(0, 0.3, 0), 1, 0, 0, 0, 0,
                                Compat.dust(WHITE_HOT, 1.6f)));
            } else {
                Fx.arc(l, dir, radius, Math.toRadians(spread), (int) (radius * 3) + 6, p ->
                        Compat.spawn(world(), Compat.DUST, Fx.ground(p, 3).add(0, 0.3, 0), 1, 0, 0, 0, 0,
                                Compat.dust(WHITE_HOT, 1.6f)));
            }
        }, null);
    }

    // ---------------------------------------------------------- FASE I: la embestida

    /** 1. Grito Atronador: el cono de siempre, con tres rayos sobre quien lo pille. */
    public void thunderScream() {
        broadcastNear(Component.text("Toma aire.", ACCENT));
        scream(120, 14, 11, 1.1, 3, 26);
    }

    /** 2. Embestida de Cuernos: retrocede, apunta y sale disparada. */
    public void hornCharge() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        Location start = boss.getLocation();
        Vector dir = target.getLocation().toVector().subtract(start.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        dir.normalize();
        Set<UUID> rammed = new HashSet<>();

        soundAt(start, "entity.goat.prepare_ram", 1.5f, 0.7f);
        broadcastNear(Component.text("Baja la cabeza.", ACCENT));

        animate(70, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            if (tick < 24) {
                // patea el suelo hacia atras antes de arrancar
                boss.setVelocity(dir.clone().multiply(-0.12).setY(boss.getVelocity().getY()));
                Compat.spawn(world(), Compat.BLOCK, Fx.ground(l, 3), 14, 0.5, 0.05, 0.5, 0.06, groundBlock(l));
                for (double d = 2; d < 20; d += 1.2) {
                    Location g = Fx.ground(l.clone().add(dir.clone().multiply(d)), 4);
                    Compat.spawn(world(), Compat.DUST, g.clone().add(0, 0.15, 0), 1, 0.9, 0, 0.9, 0,
                            Compat.dust(STORM, 1.3f));
                }
                if (tick % 6 == 0) soundAt(l, "entity.goat.step", 1.2f, 0.6f);
                return;
            }
            boss.setVelocity(dir.clone().multiply(1.25).setY(boss.getVelocity().getY()));
            Compat.spawn(world(), Compat.DUST, l.clone().add(0, 0.4, 0), 6, 0.4, 0.3, 0.4, 0,
                    Compat.dust(WHITE_HOT, 1.4f));
            if (tick % 4 == 0) soundAt(l, "entity.goat.step", 1.3f, 0.8f);
            for (Player p : targets(3.4)) {
                if (!rammed.add(p.getUniqueId())) continue;
                hit(p, 18 * damageBonus);
                push(p, dir.clone().multiply(1.5).setY(0.6));
                Compat.spawn(world(), Compat.CRIT, p.getLocation().add(0, 1, 0), 24, 0.4, 0.5, 0.4, 0.3);
                soundAt(p.getLocation(), "entity.goat.screaming_ram_impact", 1.6f, 0.8f);
            }
        }, null);
    }

    /** 3. Pisoton de Pezunas: se alza y descarga las cuatro patas. */
    public void hoofStomp() {
        if (!alive()) return;
        Set<UUID> struck = new HashSet<>();
        soundAt(boss.getLocation(), "entity.goat.long_jump", 1.3f, 0.6f);

        animate(60, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            if (tick < 20) {
                boss.setVelocity(new Vector(0, 0.1, 0));
                Fx.telegraph(world(), Fx.ground(l, 4), 6.5, STORM);
                return;
            }
            if (tick == 20) {
                Location g = Fx.ground(l, 4);
                Compat.spawn(world(), Compat.EXPLOSION_EMITTER, g, 1);
                Compat.spawn(world(), Compat.BLOCK, g, 130, 1.8, 0.3, 1.8, 0.22, groundBlock(g));
                soundAt(g, "entity.goat.screaming_ram_impact", 1.6f, 0.6f);
                soundAt(g, "entity.generic.explode", 1.2f, 0.6f);
                return;
            }
            double radius = (tick - 20) * 0.45;
            if (radius > 8) return;
            Location g = Fx.ground(boss.getLocation(), 4);
            Fx.shockwave(world(), g, radius, Compat.CLOUD, 7);
            for (Player p : targets(radius + 1.0)) {
                if (p.getLocation().distance(g) < radius - 1.4) continue;
                if (!struck.add(p.getUniqueId())) continue;
                hit(p, 12 * damageBonus);
                push(p, p.getLocation().toVector().subtract(g.toVector()).normalize().setY(0.5).multiply(0.75));
            }
        }, null);
    }

    /** 4. Berrido: un grito corto, sin rayos, para quitarse gente de encima. */
    public void bleat() {
        scream(360, 8, 7, 1.3, 0, 12);
    }

    /** 5. Salto Montanes: salta como en el cerro y cae donde estabas. */
    public void mountainLeap() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        Location mark = Fx.ground(target.getLocation(), 4);

        soundAt(loc(), "entity.goat.long_jump", 1.5f, 0.8f);
        broadcastNear(Component.text("Toma impulso.", ACCENT));

        animate(90, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            if (tick == 14) {
                boss.setVelocity(new Vector(0, 1.2, 0));
                Compat.spawn(world(), Compat.CLOUD, Fx.ground(l, 3), 36, 0.8, 0.1, 0.8, 0.1);
                return;
            }
            if (tick > 14 && tick < 42) {
                Compat.spawn(world(), Compat.WHITE_SMOKE, l, 3, 0.3, 0.3, 0.3, 0.01);
                Fx.telegraph(world(), mark, 4.0, STORM);
                return;
            }
            if (tick == 42) {
                boss.teleport(mark.clone().add(0, 8, 0));
                return;
            }
            if (tick > 42 && tick < 54) {
                boss.setVelocity(new Vector(0, -1.5, 0));
                return;
            }
            if (tick != 54) return;
            boss.teleport(mark);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, mark, 1);
            Compat.spawn(world(), Compat.BLOCK, mark, 160, 2.2, 0.4, 2.2, 0.3, groundBlock(mark));
            world().strikeLightningEffect(mark);
            soundAt(mark, "entity.goat.screaming_ram_impact", 1.8f, 0.5f);
            soundAt(mark, "entity.lightning_bolt.impact", 1.2f, 1.1f);
            for (Player p : Fx.playersNear(mark, 5.5)) {
                double d = p.getLocation().distance(mark);
                hit(p, Math.max(6, 20 - d * 2) * damageBonus);
                push(p, p.getLocation().toVector().subtract(mark.toVector()).normalize().setY(0.65));
            }
        }, null);
    }

    // ------------------------------------------------------------- FASE II: la rabia

    /** 6. Tormenta de Balidos: cuatro gritos girando, no hay donde esconderse. */
    public void bleatStorm() {
        if (!alive()) return;
        broadcastNear(Component.text("Empieza a gritar sin parar.", ACCENT));
        for (int i = 0; i < 4; i++) {
            final int index = i;
            later(i * 45, () -> {
                if (!alive()) return;
                Location l = boss.getLocation();
                l.setYaw(l.getYaw() + 90 * index);
                boss.teleport(l);
                scream(140, 12, 9, 1.0, 2, 16);
            });
        }
    }

    /** 7. Rebote: va rebotando de uno a otro, embistiendo a cada uno. */
    public void ricochet() {
        List<Player> victims = targets();
        if (victims.isEmpty() || !alive()) return;
        int hops = Math.min(4, victims.size());
        broadcastNear(Component.text("Empieza a rebotar.", ACCENT));

        for (int i = 0; i < hops; i++) {
            final Player victim = victims.get(i);
            later(i * 22, () -> {
                if (!alive() || !Fx.isFightable(victim)) return;
                Location from = boss.getLocation();
                Location to = Fx.ground(victim.getLocation(), 4);
                Fx.beam(from.clone().add(0, 1, 0), to.clone().add(0, 1, 0), 0.5, p ->
                        Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(WHITE_HOT, 1.4f)));
                boss.setVelocity(to.toVector().subtract(from.toVector()).normalize().multiply(1.4).setY(0.35));
                soundAt(from, "entity.goat.long_jump", 1.4f, 1.1f);
                later(8, () -> {
                    if (!alive()) return;
                    Location l = boss.getLocation();
                    Compat.spawn(world(), Compat.CRIT, l.clone().add(0, 1, 0), 20, 0.5, 0.5, 0.5, 0.25);
                    soundAt(l, "entity.goat.ram_impact", 1.4f, 0.9f);
                    for (Player p : targets(3.2)) {
                        hit(p, 13 * damageBonus);
                        push(p, p.getLocation().toVector().subtract(l.toVector()).normalize().setY(0.5));
                    }
                });
            });
        }
    }

    /** 8. Manada: baja el resto del rebano y embisten con ella. */
    public void herd() {
        if (!alive()) return;
        int count = 3 + random.nextInt(3);
        Location c = boss.getLocation();
        soundAt(c, "item.goat_horn.play", 1.6f, 1.0f);
        broadcastNear(Component.text("Llama al rebano.", ACCENT));

        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count;
            Location sl = Fx.ground(c.clone().add(Math.cos(a) * 7, 1, Math.sin(a) * 7), 5);
            later(i * 7, () -> {
                if (!alive()) return;
                Compat.spawn(world(), Compat.CLOUD, sl, 24, 0.5, 0.3, 0.5, 0.05);
                world().strikeLightningEffect(sl);
                soundAt(sl, "entity.goat.ambient", 1.2f, 1.0f);
                Goat g = world().spawn(sl, Goat.class, e -> {
                    e.setAdult();
                    e.setPersistent(false);
                    try {
                        e.setScreaming(true);
                    } catch (Throwable ignored) {
                    }
                    e.customName(Component.text("Cabra del Rebano", TextColor.color(0xC8D6E0)));
                    Compat.setAttribute(e, "max_health", 30);
                    Compat.setAttribute(e, "attack_damage", 6);
                    Compat.setAttribute(e, "movement_speed", 0.4);
                    Compat.setAttribute(e, "scale", 1.3);
                    e.setHealth(30);
                });
                markMinion(g);
                Glow.apply(g, event.type().glowColor());
            });
        }
    }

    /** 9. Cornada Ascendente: engancha al mas cercano y lo manda por los aires. */
    public void upwardGore() {
        Player target = Fx.nearest(loc(), 7);
        if (target == null || !alive()) return;

        soundAt(loc(), "entity.goat.prepare_ram", 1.4f, 1.2f);
        animate(40, tick -> {
            if (!alive() || !Fx.isFightable(target)) throw Stop.now();
            Location l = boss.getLocation().add(0, 1.2, 0);
            if (tick < 22) {
                Fx.beam(l, target.getLocation().add(0, 1, 0), 0.4, p ->
                        Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(HORN, 1.2f)));
                return;
            }
            if (tick != 22) return;
            hit(target, 16 * damageBonus);
            push(target, new Vector(0, 1.35, 0));
            Compat.spawn(world(), Compat.CRIT, target.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.4);
            Compat.spawn(world(), Compat.FLASH, target.getLocation().add(0, 1, 0), 1);
            soundAt(target.getLocation(), "entity.goat.screaming_ram_impact", 1.7f, 0.7f);
        }, null);
    }

    /** 10. Pelaje Blanco: se pone al rojo blanco y aguanta mucho mas mientras grita. */
    public void whiteCoat() {
        if (!alive()) return;
        soundAt(boss.getLocation(), "block.beacon.activate", 1.2f, 1.4f);
        broadcastNear(Component.text("Arde en blanco.", ACCENT));

        animate(140, tick -> {
            if (!alive()) throw Stop.now();
            Location l = boss.getLocation().add(0, 1.3, 0);
            Compat.apply(boss, "resistance", 25, 1);
            Fx.sphere(l, 1.7, 24, p -> Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0,
                    Compat.dust(WHITE_HOT, 1.7f)));
            if (tick % 35 == 0) {
                soundAt(l, "entity.goat.screaming_ambient", 1.2f, 1.0f);
                for (Player p : targets(7)) {
                    hit(p, 5 * damageBonus);
                    push(p, p.getLocation().toVector().subtract(l.toVector()).normalize().setY(0.4).multiply(0.7));
                }
            }
        }, null);
    }

    // ----------------------------------------------------------- FASE III: el trueno

    /** 11. Grito del Trueno: grito circular con un anillo de rayos alrededor. */
    public void thunderCry() {
        broadcastNear(Component.text("El cielo se pone del color de la cabra.", ACCENT));
        scream(360, 16, 15, 1.4, 8, 32);
    }

    /** 12. Estampida: tres embestidas seguidas por toda la arena. */
    public void stampede() {
        if (!alive()) return;
        broadcastNear(Component.text("No va a parar.", ACCENT));
        for (int i = 0; i < 3; i++) {
            later(i * 55, this::hornCharge);
        }
    }

    /** 13. Cielo Partido: la tormenta persigue a cada uno con su propio rayo. */
    public void splitSky() {
        if (!alive()) return;
        soundAt(loc(), "entity.lightning_bolt.thunder", 1.4f, 0.8f);
        broadcastNear(Component.text("Parte el cielo.", ACCENT));

        animate(140, tick -> {
            if (tick % 18 != 0) return;
            for (Player p : targets()) {
                Location mark = Fx.ground(p.getLocation(), 4);
                Fx.telegraph(world(), mark, 2.6, STORM);
                soundAt(mark, "block.beacon.power_select", 1.1f, 1.9f);
                later(16, () -> {
                    world().strikeLightningEffect(mark);
                    Compat.spawn(world(), Compat.ELECTRIC_SPARK, mark.clone().add(0, 0.5, 0), 60,
                            1.0, 0.5, 1.0, 0.25);
                    soundAt(mark, "entity.lightning_bolt.impact", 1.4f, 1.0f);
                    for (Player v : Fx.playersNear(mark, 3.2)) {
                        hit(v, 12 * damageBonus);
                        Compat.apply(v, "slowness", 50, 1);
                    }
                });
            }
        }, null);
    }

    /** 14. Aullido Final: el grito mas grande que tiene, con la tormenta entera. */
    public void finalHowl() {
        if (!alive()) return;
        titleNear(Component.text("AULLIDO FINAL", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Salgan del cerro", NamedTextColor.GRAY));
        scream(360, 22, 20, 1.8, 12, 45);
    }

    /** 15. Tozudez: se planta, no la mueve nadie y sale a por el mas cercano. */
    public void stubbornness() {
        if (!alive()) return;
        soundAt(boss.getLocation(), "entity.goat.horn_break", 1.2f, 1.3f);
        broadcastNear(Component.text("Se planta.", ACCENT));

        animate(70, tick -> {
            if (!alive()) throw Stop.now();
            Location l = boss.getLocation();
            Compat.apply(boss, "resistance", 20, 2);
            Fx.ring(Fx.ground(l, 3).add(0, 0.2, 0), 2.0, 16, tick * 0.25, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(HORN, 1.5f)));
            if (tick % 14 == 0) {
                Compat.spawn(world(), Compat.BLOCK, Fx.ground(l, 3), 20, 0.6, 0.05, 0.6, 0.08, groundBlock(l));
                soundAt(l, "entity.goat.step", 1.3f, 0.5f);
            }
        }, () -> {
            if (!alive()) return;
            soundAt(boss.getLocation(), "entity.goat.screaming_ambient", 1.5f, 0.8f);
            hornCharge();
        });
    }

    // ------------------------------------------------------------------- utilidades

    private BlockData groundBlock(Location l) {
        Material m = Fx.ground(l, 3).getBlock().getRelative(0, -1, 0).getType();
        if (!m.isSolid() || m.isAir()) m = Material.DIRT;
        return m.createBlockData();
    }

    private void broadcastNear(Component message) {
        Component line = Component.text("✦ ", ACCENT)
                .append(Component.text("Cabra Gritona  ", ACCENT, TextDecoration.BOLD))
                .append(message.colorIfAbsent(NamedTextColor.GRAY));
        for (Player p : Fx.viewersNear(loc(), 80)) p.sendActionBar(line);
    }

    private void titleNear(Component title, Component subtitle) {
        for (Player p : Fx.viewersNear(loc(), 80)) {
            p.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1400), Duration.ofMillis(500))));
        }
    }

    /** Deja a la vista el numero de esbirros vivos, para el /anomaly info. */
    public int minions() {
        int n = 0;
        for (Entity e : spawned) {
            if (e.isValid()) n++;
        }
        return n;
    }
}
