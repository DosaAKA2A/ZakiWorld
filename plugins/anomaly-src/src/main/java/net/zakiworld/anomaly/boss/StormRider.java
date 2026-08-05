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
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * STORM RIDER, la cuarta anomalia.
 *
 * Un ahogado con tridente montado en un phantom gigante. El phantom se agranda de
 * verdad con Phantom#setSize, que escala modelo Y caja de golpe, y se le fija un ancla
 * sobre la arena para que no se vaya a dar una vuelta por el mapa.
 *
 * Tres fases muy distintas:
 *  I   vuela alto y el cuerpo a cuerpo casi no le hace nada: hay que sacar el arco
 *  II  el phantom se rompe y el jinete pelea a pie, con un repertorio corto
 *  III modo berserker con dos tridentes: rapidisimo y demoledor, pero fragil
 */
public final class StormRider extends BossFight {

    public static final String ID = "storm_rider";
    public static final TextColor ACCENT = TextColor.color(0x7FD4E8);

    private static final int STORM = 0xAFC9DE;
    private static final int DEEP = 0x2C6E8F;
    private static final int RAGE = 0xE85D3A;

    /** Altura a la que se mantiene el phantom sobre la arena durante la fase 1. */
    private static final double FLIGHT_HEIGHT = 13;

    private boolean grounded;
    private boolean berserk;
    private boolean diving;
    private double damageBonus = 1.0;

    public StormRider(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        super(plugin, event, where);
        abilities.addAll(plugin.registry().riderAbilities());
    }

    @Override
    public String bossName() {
        return "Storm Rider";
    }

    // ------------------------------------------------------------------- aparicion

    /**
     * Nace siendo EL phantom, no montado en uno.
     *
     * En la fase uno el jefe es la propia criatura voladora; al entrar en la segunda se
     * estrella contra el suelo y toma su forma terrestre. Que el phantom sea el jefe y
     * no una montura quita de en medio toda la friccion que daba la IA de vuelo.
     */
    @Override
    public void spawn() {
        Location air = arena.clone().add(0, FLIGHT_HEIGHT, 0);

        boss = world().spawn(air, Phantom.class, p -> {
            p.setSize(9);
            p.setPersistent(true);
            p.setRemoveWhenFarAway(false);
            p.setShouldBurnInDay(false);
            p.setAnchorLocation(air);
            p.customName(Component.text("✦ ", ACCENT)
                    .append(Component.text("Storm Rider", ACCENT, TextDecoration.BOLD)));
            p.setCustomNameVisible(true);
        });

        Compat.setAttribute(boss, "attack_damage", 12);
        Compat.setAttribute(boss, "armor", 10);
        Compat.setAttribute(boss, "knockback_resistance", 1.0);
        Compat.setAttribute(boss, "follow_range", 80);
        applyHealth(plugin.registry().scaledHealth(plugin.registry().rider(), targets(96).size()));
        boss.setMaximumNoDamageTicks(6);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        Glow.apply(boss, event.type().glowColor());
        boss.setGravity(false);

        arrivalAnimation(arena.clone());
    }

    /**
     * Cambia el cuerpo del jefe conservando su vida.
     *
     * La barra sigue igual porque trabaja con la fraccion vida/maxima, y aqui se copian
     * las dos. Es la unica forma limpia de que un jefe cambie de criatura a mitad de
     * combate sin que el evento se entere.
     */
    private void becomeDrowned(Location at) {
        double health = boss.getHealth();
        double max = Compat.getAttribute(boss, "max_health", health);

        Glow.clear(boss);
        Fx.safeRemove(boss);

        boss = world().spawn(at, Drowned.class, d -> {
            d.setAdult();
            d.setPersistent(true);
            d.setRemoveWhenFarAway(false);
            d.setCanPickupItems(false);
            d.setShouldBurnInDay(false);
            d.customName(Component.text("✦ ", ACCENT)
                    .append(Component.text("Storm Rider", ACCENT, TextDecoration.BOLD)));
            d.setCustomNameVisible(true);
        });
        dressUp(boss.getEquipment(), false);
        Compat.setAttribute(boss, "max_health", max);
        boss.setHealth(Math.min(max, health));
        Compat.setAttribute(boss, "attack_damage", 13);
        Compat.setAttribute(boss, "armor", 12);
        Compat.setAttribute(boss, "armor_toughness", 4);
        Compat.setAttribute(boss, "knockback_resistance", 1.0);
        Compat.setAttribute(boss, "follow_range", 72);
        Compat.setAttribute(boss, "movement_speed", 0.36);
        boss.setMaximumNoDamageTicks(6);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        Glow.apply(boss, event.type().glowColor());
    }

    private void dressUp(EntityEquipment eq, boolean dual) {
        if (eq == null) return;
        eq.setHelmet(named(Material.TURTLE_HELMET, "Yelmo de la Corriente"));
        eq.setChestplate(named(Material.IRON_CHESTPLATE, "Peto Anegado"));
        eq.setItemInMainHand(named(Material.TRIDENT, "Tridente de Tormenta"));
        eq.setItemInOffHand(dual ? named(Material.TRIDENT, "Tridente de Tormenta") : null);
        eq.setHelmetDropChance(0);
        eq.setChestplateDropChance(0);
        eq.setItemInMainHandDropChance(0);
        eq.setItemInOffHandDropChance(0);
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

    /** La llegada: se junta una tormenta y de ella baja la sombra del phantom. */
    private void arrivalAnimation(Location spot) {
        boss.setInvulnerable(true);
        busyFor(70);
        soundAt(spot, "entity.lightning_bolt.thunder", 1.3f, 0.7f);
        soundAt(spot, "entity.phantom.ambient", 1.6f, 0.5f);

        animate(70, tick -> {
            Location air = spot.clone().add(0, FLIGHT_HEIGHT, 0);
            double t = tick / 70.0;
            Fx.ring(air, 10 - t * 6, 26, tick * 0.12, l ->
                    Compat.spawn(world(), Compat.CLOUD, l, 2, 0.3, 0.2, 0.3, 0.02));
            Fx.ring(spot, 8 - t * 4, 22, tick * -0.1, l ->
                    Compat.spawn(world(), Compat.DUST, Fx.ground(l, 3).add(0, 0.15, 0), 1, 0, 0, 0, 0,
                            Compat.dust(STORM, 1.5f)));
            if (tick % 16 == 0) {
                double a = Math.random() * Math.PI * 2;
                Location bolt = Fx.ground(spot.clone().add(Math.cos(a) * 7, 0, Math.sin(a) * 7), 5);
                world().strikeLightningEffect(bolt);
                soundAt(bolt, "entity.lightning_bolt.impact", 0.9f, 1.1f);
            }
            if (tick % 10 == 0) soundAt(air, "entity.phantom.flap", 1.4f, 0.6f);
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            soundAt(spot, "entity.phantom.bite", 1.6f, 0.6f);
            for (Player p : Fx.viewersNear(spot, 90)) {
                p.showTitle(Title.title(
                        Component.text("✦ ANOMALIA ✦", ACCENT, TextDecoration.BOLD),
                        Component.text("Storm Rider  ·  saquen los arcos", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1800), Duration.ofMillis(600))));
            }
        });
    }

    // -------------------------------------------------------------------- ambiente

    @Override
    protected void ambient() {
        if (!alive()) return;

        if (!grounded && !diving) flyOrbit();

        if (ticks() % 4 == 0) {
            Location l = boss.getLocation().add(0, 1.2, 0);
            Compat.spawn(world(), Compat.DUST, l, 2, 0.4, 0.4, 0.4, 0,
                    Compat.dust(berserk ? RAGE : STORM, 1.0f));
            if (berserk) {
                Compat.spawn(world(), Compat.ELECTRIC_SPARK, l, 2, 0.5, 0.5, 0.5, 0.03);
            }
        }
        if (ticks() % 20 == 0) disarmLooseTridents();
        if (!grounded && ticks() % 60 == 0) {
            warn(Component.text("Vuela demasiado alto para la espada. ", NamedTextColor.GRAY)
                    .append(Component.text("Usa el arco.", NamedTextColor.AQUA, TextDecoration.BOLD)));
        }
    }

    /**
     * El vuelo, hecho a mano.
     *
     * Da vueltas sobre la arena a su altura, ladeandose hacia donde va. Al ser velocidad
     * impuesta cada tick y sin gravedad, no hay IA con la que pelear y la animacion deja
     * de romperse: el jefe esta exactamente donde el plugin dice que esta.
     */
    private void flyOrbit() {
        double targetY = Fx.ground(arena, 6).getY() + FLIGHT_HEIGHT;
        double angle = ticks() * 0.03;
        Location want = arena.clone().add(Math.cos(angle) * 10, targetY - arena.getY(), Math.sin(angle) * 10);

        Vector move = want.toVector().subtract(boss.getLocation().toVector());
        double len = move.length();
        boss.setVelocity(len < 0.2 ? new Vector(0, 0, 0) : move.multiply(Math.min(0.32, 0.85 / len)));

        Player look = Fx.nearest(arena, plugin.settings().participationRadius());
        if (look != null) {
            Location face = boss.getLocation();
            face.setDirection(look.getLocation().toVector().subtract(face.toVector()));
            boss.setRotation(face.getYaw(), face.getPitch());
        }

        if (ticks() % 6 == 0) {
            Compat.spawn(world(), Compat.CLOUD, boss.getLocation(), 4, 0.8, 0.4, 0.8, 0.01);
        }
        if (ticks() % 40 == 0) soundAt(boss.getLocation(), "entity.phantom.flap", 0.9f, 0.6f);
    }

    /**
     * Los tridentes que lanza el jinete (o su propia IA) se clavan en el suelo y se
     * pueden recoger. En un survival eso es una fabrica de tridentes gratis, asi que se
     * les quita la recogida y se marcan para que la limpieza final se los lleve.
     */
    private void disarmLooseTridents() {
        for (Entity e : boss.getNearbyEntities(48, 48, 48)) {
            if (!(e instanceof Trident t)) continue;
            if (Tags.temporarySince(t) != null) continue;
            if (!(t.getShooter() instanceof Entity shooter) || !shooter.equals(boss)) continue;
            try {
                t.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            } catch (Throwable ignored) {
            }
            Tags.markTemporary(t);
            track(t);
            expire(t, 200);
        }
    }

    /**
     * En el aire, el cuerpo a cuerpo casi no le hace nada: es lo que obliga a pelear
     * la primera fase con arco, que es como se pidio. Las flechas entran enteras.
     */
    @Override
    public double incomingDamageMultiplier(Entity damager) {
        double base = incomingDamageMultiplier();
        if (grounded) return base;
        return damager instanceof Projectile ? base : base * 0.2;
    }

    /** En berserker pega much0 mas pero tambien lo revientan mucho antes. */
    @Override
    public double incomingDamageMultiplier() {
        return berserk ? 1.6 : 1.0;
    }

    // --------------------------------------------------------------- cambio de fase

    @Override
    protected void onPhaseChange(int from, int to) {
        if (event.bars() != null) event.bars().flash(from);
        if (to == 2) crashDown();
        if (to == 3) berserker();
    }

    /**
     * FASE I -> II. El picado que lo cambia todo.
     *
     * El phantom se lanza en vertical contra el suelo, revienta al impactar y de la
     * explosion sale el ahogado con el tridente: la forma terrestre.
     */
    private void crashDown() {
        if (grounded || !alive()) return;
        grounded = true;
        boss.setInvulnerable(true);
        busyFor(120);

        final Location impact = Fx.ground(arena, 6);
        soundAt(boss.getLocation(), "entity.phantom.swoop", 2.0f, 0.4f);
        titleNear(Component.text("SE VIENE ENCIMA", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text("Apartense del punto de impacto", NamedTextColor.GRAY));

        animate(120, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();

            if (tick < 40) {
                // Sube y se coloca sobre el punto, avisando con la marca en el suelo.
                Location want = impact.clone().add(0, 18, 0);
                Vector move = want.toVector().subtract(l.toVector());
                if (move.lengthSquared() > 0.5) boss.setVelocity(move.normalize().multiply(0.75));
                Fx.telegraph(world(), impact, 7.0, STORM);
                if (tick % 8 == 0) soundAt(impact, "entity.phantom.flap", 1.4f, 0.5f);
                return;
            }
            if (tick < 62) {
                boss.setVelocity(new Vector(0, -1.5, 0));
                Fx.telegraph(world(), impact, 7.0, RAGE);
                Compat.spawn(world(), Compat.CLOUD, l, 10, 0.9, 0.6, 0.9, 0.06);
                Compat.spawn(world(), Compat.LARGE_SMOKE, l, 6, 0.7, 0.5, 0.7, 0.03);
                if (tick % 4 == 0) soundAt(l, "entity.phantom.swoop", 1.4f, 0.5f);
                return;
            }
            if (tick != 62) return;

            boss.teleport(impact);
            world().strikeLightningEffect(impact);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, impact, 3);
            Compat.spawn(world(), Compat.BLOCK, impact, 220, 2.6, 0.5, 2.6, 0.35, groundBlock(impact));
            Compat.spawn(world(), Compat.ITEM, impact.clone().add(0, 1, 0), 90, 1.2, 1.0, 1.2, 0.3,
                    new ItemStack(Material.PHANTOM_MEMBRANE));
            soundAt(impact, "entity.generic.explode", 2.0f, 0.4f);
            soundAt(impact, "entity.phantom.death", 1.8f, 0.5f);
            soundAt(impact, "entity.lightning_bolt.impact", 1.6f, 0.8f);

            for (Player p : Fx.playersNear(impact, 9)) {
                double d = p.getLocation().distance(impact);
                hit(p, Math.max(10, 28 - d * 1.8) * damageBonus);
                push(p, p.getLocation().toVector().subtract(impact.toVector())
                        .normalize().setY(0.7).multiply(1.5));
            }

            // De la explosion sale la forma terrestre.
            becomeDrowned(impact);
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            titleNear(Component.text("FASE II", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("Ya lo tienes al alcance", NamedTextColor.GRAY));
            soundAt(boss.getLocation(), "item.trident.return", 1.6f, 0.6f);
        });
    }

    /**
     * FASE II -> III. Modo berserker: saca el segundo tridente y se vuelve rapidisimo.
     *
     * "Menos vida" se hace subiendo el dano que recibe, NO bajandole la vida maxima:
     * bajarsela le subiria la fraccion de vida y lo devolveria a la fase 2, y el
     * combate entraria en bucle. El efecto para el jugador es el mismo: dura menos.
     */
    private void berserker() {
        if (berserk || !alive()) return;
        berserk = true;
        boss.setInvulnerable(true);
        busyFor(80);

        Location spot = boss.getLocation();
        soundAt(spot, "item.trident.riptide_3", 1.6f, 0.6f);
        titleNear(Component.text("BERSERKER", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Dos tridentes y ninguna paciencia", NamedTextColor.GRAY));

        animate(80, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1.1, 0);
            Fx.sphere(l, 1.3 + Math.sin(tick * 0.2) * 0.25, 22, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(RAGE, 1.5f)));
            if (tick % 12 == 0) {
                world().strikeLightningEffect(Fx.ground(
                        l.clone().add((Math.random() - 0.5) * 8, 0, (Math.random() - 0.5) * 8), 5));
                soundAt(l, "entity.lightning_bolt.impact", 0.9f, 1.4f);
            }
            if (tick == 50) {
                dressUp(boss.getEquipment(), true);
                Compat.spawn(world(), Compat.FLASH, l, 1);
                soundAt(l, "item.trident.return", 1.6f, 0.7f);
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            damageBonus = 1.5;
            Compat.setAttribute(boss, "attack_damage", 20);
            Compat.setAttribute(boss, "attack_speed", 4.0);
            Compat.setAttribute(boss, "movement_speed", 0.52);
            Compat.apply(boss, "speed", 20 * 600, 2);
            soundAt(boss.getLocation(), "entity.drowned.ambient", 1.6f, 0.5f);
        });
    }

    // ---------------------------------------------------------------------- muerte

    @Override
    public void onDeath() {
        Location l = loc();
        soundAt(l, "entity.drowned.death", 1.8f, 0.6f);
        soundAt(l, "item.trident.return", 1.4f, 0.7f);

        animate(70, tick -> {
            double t = tick / 70.0;
            Fx.helix(l, 1.6 * (1 - t) + 0.3, 3.0, 18, 2.0, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(DEEP, 1.4f)));
            if (tick % 12 == 0) {
                Compat.spawn(world(), Compat.SPLASH,
                        l.clone().add(0, 1, 0), 20, 0.6, 0.5, 0.6, 0.05);
                soundAt(l, "entity.drowned.hurt_water", 1.0f, 0.8f);
            }
            if (tick == 45) {
                world().strikeLightningEffect(l);
                soundAt(l, "entity.lightning_bolt.impact", 1.2f, 1.2f);
            }
        }, () -> {
            Compat.spawn(world(), Compat.FLASH, l.clone().add(0, 1, 0), 1);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l, 1);
            soundAt(l, "entity.lightning_bolt.thunder", 1.2f, 1.3f);
            soundAt(l, "item.trident.thunder", 1.4f, 0.8f);
        });
    }

    // ============================================================== HABILIDADES ==

    // -------------------------------------------------------- FASE I: desde el aire

    /** 1. Chillido del Temporal: un grito que empuja y deja sordo de vista. */
    public void stormShriek() {
        if (!alive()) return;
        Location c = boss.getLocation();
        soundAt(c, "entity.phantom.hurt", 1.8f, 0.4f);
        broadcastNear(Component.text("Chilla desde arriba.", ACCENT));

        animate(70, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            if (tick < 26) {
                Fx.sphere(l, 2.4 - tick * 0.05, 24, p ->
                        Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(STORM, 1.4f)));
                if (tick % 8 == 0) soundAt(l, "entity.phantom.ambient", 1.2f, 0.4f + tick / 40f);
                return;
            }
            if (tick != 26) return;
            soundAt(l, "entity.phantom.bite", 2.0f, 0.5f);
            for (int r = 1; r <= 5; r++) {
                final int ring = r;
                later(r * 3, () -> {
                    Location g = Fx.ground(boss.getLocation(), 8);
                    Fx.ring(g, ring * 3.2, ring * 12, p ->
                            Compat.spawn(world(), Compat.CLOUD, Fx.ground(p, 4).add(0, 0.4, 0), 2,
                                    0.15, 0.15, 0.15, 0.02));
                    for (Player p : Fx.playersNear(g, ring * 3.2 + 1.5)) {
                        if (p.getLocation().distance(g) < (ring - 1) * 3.2) continue;
                        hit(p, (14 - ring) * damageBonus);
                        Compat.apply(p, "blindness", 60, 0);
                        push(p, p.getLocation().toVector().subtract(g.toVector())
                                .normalize().setY(0.4).multiply(0.9));
                    }
                });
            }
        }, null);
    }

    /** 2. Picado: se lanza en vertical sobre alguien y vuelve a subir. */
    public void divebomb() {
        Player target = randomTarget();
        if (target == null || !alive() || grounded) return;
        Location mark = Fx.ground(target.getLocation(), 4);

        soundAt(loc(), "entity.phantom.swoop", 1.6f, 0.7f);
        broadcastNear(Component.text("Se lanza en picado.", ACCENT));

        // Mientras dura el picado el vuelo automatico se aparta: manda la habilidad.
        diving = true;
        animate(80, tick -> {
            if (!alive() || grounded) throw Stop.now();
            if (tick < 24) {
                Fx.telegraph(world(), mark, 3.6, STORM);
                if (tick % 8 == 0) soundAt(mark, "entity.phantom.flap", 1.2f, 1.0f);
                return;
            }
            if (tick < 46) {
                Vector to = mark.clone().add(0, 1.5, 0).toVector().subtract(boss.getLocation().toVector());
                if (to.lengthSquared() > 0.5) boss.setVelocity(to.normalize().multiply(1.15));
                Compat.spawn(world(), Compat.CLOUD, boss.getLocation(), 8, 0.5, 0.4, 0.5, 0.04);
                Compat.spawn(world(), Compat.DUST, boss.getLocation(), 4, 0.4, 0.4, 0.4, 0,
                        Compat.dust(STORM, 1.4f));
                for (Player p : Fx.playersNear(boss.getLocation(), 3.6)) {
                    hit(p, 13 * damageBonus);
                    push(p, p.getLocation().toVector().subtract(boss.getLocation().toVector())
                            .normalize().setY(0.5).multiply(1.0));
                }
                return;
            }
            boss.setVelocity(new Vector(0, 0.8, 0));
            if (tick % 8 == 0) soundAt(boss.getLocation(), "entity.phantom.flap", 1.2f, 0.7f);
        }, () -> diving = false);
    }

    /** 3. Ojo del Huracan: un remolino que arrastra a todos hacia el centro. */
    public void hurricaneEye() {
        if (!alive()) return;
        Location eye = Fx.ground(arena, 5);
        soundAt(eye, "entity.phantom.ambient", 1.4f, 0.6f);
        broadcastNear(Component.text("Se levanta el remolino.", ACCENT));

        animate(140, tick -> {
            double radius = 14 - (tick % 40) * 0.15;
            Fx.helix(eye, radius * 0.5, 6, 26, 3.0, p ->
                    Compat.spawn(world(), Compat.CLOUD, p, 1, 0.1, 0.1, 0.1, 0.01));
            Fx.ring(eye, radius, (int) (radius * 4), tick * 0.25, p ->
                    Compat.spawn(world(), Compat.DUST, Fx.ground(p, 3).add(0, 0.3, 0), 1, 0, 0, 0, 0,
                            Compat.dust(STORM, 1.3f)));
            if (tick % 5 != 0) return;
            for (Player p : Fx.playersNear(eye, 16)) {
                Vector pull = eye.toVector().subtract(p.getLocation().toVector()).setY(0);
                if (pull.lengthSquared() < 4) continue;
                push(p, pull.normalize().multiply(0.35).setY(0.12));
            }
            if (tick % 20 == 0) {
                soundAt(eye, "entity.phantom.flap", 1.0f, 0.5f);
                for (Player p : Fx.playersNear(eye, 3.5)) hit(p, 6 * damageBonus);
            }
        }, null);
    }

    /** 4. Descarga: rayos sobre marcas que persiguen a cada uno. */
    public void discharge() {
        if (!alive()) return;
        soundAt(loc(), "entity.lightning_bolt.thunder", 1.3f, 1.0f);

        animate(120, tick -> {
            if (tick % 24 != 0) return;
            for (Player p : targets()) {
                Location mark = Fx.ground(p.getLocation(), 4);
                Fx.telegraph(world(), mark, 2.6, STORM);
                soundAt(mark, "block.beacon.power_select", 1.0f, 1.9f);
                later(18, () -> {
                    world().strikeLightningEffect(mark);
                    Compat.spawn(world(), Compat.ELECTRIC_SPARK, mark.clone().add(0, 0.5, 0), 50,
                            0.9, 0.5, 0.9, 0.2);
                    soundAt(mark, "entity.lightning_bolt.impact", 1.4f, 1.0f);
                    for (Player v : Fx.playersNear(mark, 3.0)) {
                        hit(v, 11 * damageBonus);
                        Compat.apply(v, "slowness", 40, 1);
                    }
                });
            }
        }, null);
    }

    /** 6. Viento Cortante: cuchillas de aire que barren el suelo desde el cielo. */
    public void windBlades() {
        if (!alive()) return;
        Location c = Fx.ground(arena, 5);
        soundAt(loc(), "entity.player.attack.sweep", 1.4f, 0.6f);

        animate(90, tick -> {
            if (tick % 18 != 0) return;
            double base = Math.random() * Math.PI * 2;
            Set<UUID> cut = new HashSet<>();
            for (double d = 2; d <= 16; d += 0.8) {
                Location p = Fx.ground(c.clone().add(Math.cos(base) * d, 0, Math.sin(base) * d), 4);
                Compat.spawn(world(), Compat.SWEEP_ATTACK, p.clone().add(0, 0.4, 0), 1);
                Compat.spawn(world(), Compat.DUST, p.clone().add(0, 0.3, 0), 2, 0.3, 0.1, 0.3, 0,
                        Compat.dust(STORM, 1.4f));
                for (Player v : Fx.playersNear(p, 2.0)) {
                    if (!cut.add(v.getUniqueId())) continue;
                    hit(v, 10 * damageBonus);
                    push(v, new Vector(Math.cos(base), 0.35, Math.sin(base)).multiply(0.7));
                }
            }
            soundAt(c, "item.trident.riptide_1", 1.2f, 1.0f);
        }, null);
    }

    // ------------------------------------------------------------ FASE II: a pie

    /** 7. Barrido de Tridente: un arco amplio a ras de suelo. */
    public void tridentSweep() {
        if (!alive()) return;
        Location c = boss.getLocation().add(0, 1.0, 0);
        Vector facing = boss.getLocation().getDirection().setY(0).normalize();
        soundAt(c, "item.trident.riptide_2", 1.3f, 0.9f);

        animate(40, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1.0, 0);
            double radius = 1.5 + tick * 0.16;
            Fx.arc(l, facing, radius, Math.toRadians(150), (int) (radius * 5), p -> {
                Compat.spawn(world(), Compat.SWEEP_ATTACK, p, 1);
                Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(STORM, 1.2f));
            });
            if (tick != 22) return;
            soundAt(l, "entity.player.attack.sweep", 1.5f, 0.8f);
            for (Player p : targets(6.0)) {
                Vector to = p.getLocation().toVector().subtract(l.toVector()).setY(0);
                if (to.lengthSquared() > 0.01 && to.normalize().dot(facing) < 0.1) continue;
                hit(p, 15 * damageBonus);
                push(p, to.normalize().setY(0.35).multiply(0.8));
            }
        }, null);
    }

    /** 8. Maremoto: una ola que sale de el y barre todo lo que pilla. */
    public void tidalWave() {
        if (!alive()) return;
        Set<UUID> soaked = new HashSet<>();
        Location c = Fx.ground(boss.getLocation(), 4);
        soundAt(c, "entity.player.splash.high_speed", 1.6f, 0.6f);
        broadcastNear(Component.text("Levanta la marea.", ACCENT));

        animate(70, tick -> {
            if (tick < 20) {
                Fx.telegraph(world(), c, 9.0, DEEP);
                return;
            }
            double radius = (tick - 20) * 0.42;
            if (radius > 11) return;
            Location g = Fx.ground(boss.getLocation(), 4);
            Fx.ring(g, radius, (int) (radius * 7) + 8, p -> {
                Location gp = Fx.ground(p, 3);
                Compat.spawn(world(), Compat.SPLASH,
                        gp.clone().add(0, 0.4, 0), 2, 0.15, 0.25, 0.15, 0.02);
                Compat.spawn(world(), Compat.DUST, gp.clone().add(0, 0.3, 0), 1, 0, 0, 0, 0,
                        Compat.dust(DEEP, 1.5f));
            });
            if (tick % 6 == 0) soundAt(g, "entity.player.swim", 1.2f, 0.7f);
            for (Player p : targets(radius + 1.0)) {
                if (p.getLocation().distance(g) < radius - 1.4) continue;
                if (!soaked.add(p.getUniqueId())) continue;
                hit(p, 13 * damageBonus);
                push(p, p.getLocation().toVector().subtract(g.toVector()).normalize().setY(0.5).multiply(1.1));
                Compat.apply(p, "slowness", 60, 1);
            }
        }, null);
    }

    /** 9. Ancla de Tormenta: arponea al mas lejano y lo trae de vuelta. */
    public void stormAnchor() {
        Player target = Fx.farthest(loc(), plugin.settings().participationRadius());
        if (target == null || !alive()) return;
        if (target.getLocation().distanceSquared(loc()) < 36) return;

        soundAt(loc(), "item.trident.throw", 1.5f, 0.7f);
        target.sendActionBar(Component.text("Te ha arponeado.", NamedTextColor.RED, TextDecoration.BOLD));

        animate(50, tick -> {
            if (!alive() || !Fx.isFightable(target)) throw Stop.now();
            Location from = boss.getLocation().add(0, 1.2, 0);
            Location to = target.getLocation().add(0, 1.0, 0);
            Fx.beam(from, to, 0.4, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(DEEP, 1.2f)));
            if (tick < 16) {
                if (tick % 5 == 0) soundAt(to, "block.chain.hit", 1.0f, 0.9f);
                return;
            }
            if (tick % 5 == 0) {
                push(target, from.toVector().subtract(to.toVector()).normalize().multiply(0.85).setY(0.28));
                soundAt(to, "item.trident.riptide_1", 0.9f, 0.9f);
            }
            if (tick == 45) {
                hit(target, 12 * damageBonus);
                soundAt(to, "item.trident.hit", 1.4f, 0.8f);
            }
        }, null);
    }

    /** 10. Carga de Marea: embiste con el tridente por delante. */
    public void tideCharge() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        Location start = boss.getLocation();
        Vector dir = target.getLocation().toVector().subtract(start.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        final Vector run = dir.normalize();
        Set<UUID> rammed = new HashSet<>();

        soundAt(start, "item.trident.riptide_3", 1.4f, 0.7f);
        animate(60, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            if (tick < 20) {
                for (double d = 2; d < 18; d += 1.2) {
                    Location g = Fx.ground(l.clone().add(run.clone().multiply(d)), 4);
                    Compat.spawn(world(), Compat.DUST, g.clone().add(0, 0.15, 0), 1, 0.9, 0, 0.9, 0,
                            Compat.dust(DEEP, 1.4f));
                }
                return;
            }
            boss.setVelocity(run.clone().multiply(1.1).setY(boss.getVelocity().getY()));
            Compat.spawn(world(), Compat.SPLASH,
                    l.clone().add(0, 0.5, 0), 6, 0.4, 0.3, 0.4, 0.04);
            if (tick % 5 == 0) soundAt(l, "entity.player.swim", 1.2f, 0.8f);
            for (Player p : targets(3.0)) {
                if (!rammed.add(p.getUniqueId())) continue;
                hit(p, 17 * damageBonus);
                push(p, run.clone().multiply(1.2).setY(0.5));
                soundAt(p.getLocation(), "item.trident.hit", 1.5f, 0.7f);
            }
        }, null);
    }

    // -------------------------------------------------------- FASE III: berserker

    /** 11. Frenesi de Tridentes: una tanda de golpes rapidisimos a su alrededor. */
    public void tridentFrenzy() {
        if (!alive()) return;
        soundAt(loc(), "item.trident.riptide_3", 1.6f, 1.2f);
        broadcastNear(Component.text("Se le va la cabeza.", ACCENT));

        animate(80, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1.0, 0);
            double a = tick * 0.9;
            Fx.ring(l, 2.4, 8, a, p -> {
                Compat.spawn(world(), Compat.SWEEP_ATTACK, p, 1);
                Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(RAGE, 1.3f));
            });
            if (tick % 8 != 0) return;
            soundAt(l, "item.trident.hit", 1.3f, 1.3f);
            for (Player p : targets(3.4)) {
                hit(p, 9 * damageBonus);
                push(p, p.getLocation().toVector().subtract(l.toVector()).normalize().setY(0.2).multiply(0.4));
            }
        }, null);
    }

    /** 12. Doble Tajo: dos cortes cruzados, uno con cada tridente. */
    public void crossSlash() {
        if (!alive()) return;
        Vector facing = boss.getLocation().getDirection().setY(0).normalize();
        soundAt(loc(), "item.trident.riptide_2", 1.5f, 1.0f);

        for (int pass = 0; pass < 2; pass++) {
            final int side = pass == 0 ? 1 : -1;
            later(pass * 16, () -> {
                if (!alive()) return;
                Location l = boss.getLocation().add(0, 1.1, 0);
                soundAt(l, "entity.player.attack.sweep", 1.5f, side > 0 ? 0.9f : 1.2f);
                for (double d = 1; d <= 7; d += 0.5) {
                    double spread = Math.toRadians(35 * side);
                    double base = Math.atan2(facing.getZ(), facing.getX()) + spread;
                    Location p = l.clone().add(Math.cos(base) * d, 0.2 * side, Math.sin(base) * d);
                    Compat.spawn(world(), Compat.SWEEP_ATTACK, p, 1);
                    Compat.spawn(world(), Compat.DUST, p, 2, 0.1, 0.1, 0.1, 0, Compat.dust(RAGE, 1.4f));
                }
                for (Player p : targets(7.0)) {
                    Vector to = p.getLocation().toVector().subtract(l.toVector()).setY(0);
                    if (to.lengthSquared() > 0.01 && to.normalize().dot(facing) < 0.2) continue;
                    hit(p, 18 * damageBonus);
                    push(p, to.normalize().setY(0.4).multiply(0.9));
                }
            });
        }
    }

    /** 13. Tormenta Perfecta: rayos, viento y embestida, todo a la vez. */
    public void perfectStorm() {
        if (!alive()) return;
        titleNear(Component.text("TORMENTA PERFECTA", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Aparta o aguanta", NamedTextColor.GRAY));
        soundAt(loc(), "entity.lightning_bolt.thunder", 1.6f, 0.6f);

        animate(150, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            Fx.helix(l, 2.2, 4.0, 16, 2.0, p ->
                    Compat.spawn(world(), Compat.ELECTRIC_SPARK, p, 1, 0.05, 0.05, 0.05, 0.02));
            if (tick % 30 == 0) {
                for (Player p : targets()) {
                    Location mark = Fx.ground(p.getLocation(), 4);
                    Fx.telegraph(world(), mark, 2.4, RAGE);
                    later(14, () -> {
                        world().strikeLightningEffect(mark);
                        soundAt(mark, "entity.lightning_bolt.impact", 1.3f, 1.1f);
                        for (Player v : Fx.playersNear(mark, 2.8)) hit(v, 13 * damageBonus);
                    });
                }
            }
            if (tick % 45 == 0) {
                Player t = randomTarget();
                if (t == null) return;
                Vector dir = t.getLocation().toVector().subtract(l.toVector()).setY(0);
                if (dir.lengthSquared() > 0.04) boss.setVelocity(dir.normalize().multiply(1.2).setY(0.25));
                soundAt(l, "item.trident.riptide_3", 1.4f, 1.0f);
            }
            if (tick % 10 == 0) {
                for (Player p : targets(3.2)) {
                    hit(p, 8 * damageBonus);
                    push(p, p.getLocation().toVector().subtract(l.toVector()).normalize().setY(0.3).multiply(0.5));
                }
            }
        }, null);
    }

    /** 14. Salto del Trueno: salta y cae con un rayo encima. */
    public void thunderJump() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        Location mark = Fx.ground(target.getLocation(), 4);

        soundAt(loc(), "item.trident.riptide_1", 1.5f, 0.8f);
        animate(80, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            if (tick == 12) {
                boss.setVelocity(new Vector(0, 1.25, 0));
                return;
            }
            if (tick > 12 && tick < 42) {
                Fx.telegraph(world(), mark, 4.0, RAGE);
                Compat.spawn(world(), Compat.ELECTRIC_SPARK, l, 4, 0.3, 0.3, 0.3, 0.03);
                return;
            }
            if (tick == 42) {
                boss.teleport(mark.clone().add(0, 8, 0));
                return;
            }
            if (tick < 52) {
                boss.setVelocity(new Vector(0, -1.6, 0));
                return;
            }
            if (tick != 52) return;
            boss.teleport(mark);
            world().strikeLightningEffect(mark);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, mark, 1);
            Compat.spawn(world(), Compat.BLOCK, mark, 150, 2.0, 0.4, 2.0, 0.3, groundBlock(mark));
            soundAt(mark, "entity.lightning_bolt.impact", 1.7f, 0.9f);
            soundAt(mark, "item.trident.hit_ground", 1.6f, 0.6f);
            for (Player p : Fx.playersNear(mark, 6.0)) {
                double d = p.getLocation().distance(mark);
                hit(p, Math.max(8, 22 - d * 2) * damageBonus);
                push(p, p.getLocation().toVector().subtract(mark.toVector()).normalize().setY(0.6));
            }
        }, null);
    }

    // -------------------------------------------------------------- cualquier fase

    /** 15. Relampago Guia: marca a uno y le cae el rayo donde este. */
    public void guidingBolt() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        soundAt(target.getLocation(), "block.beacon.power_select", 1.2f, 1.7f);
        target.sendActionBar(Component.text("El cielo te ha elegido.", NamedTextColor.RED, TextDecoration.BOLD));

        animate(70, tick -> {
            if (!Fx.isFightable(target)) throw Stop.now();
            Location tl = target.getLocation();
            Fx.beam(tl.clone().add(0, 0.3, 0), tl.clone().add(0, 12, 0), 0.9, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(STORM, 1.1f)));
            Fx.ring(tl.clone().add(0, 0.15, 0), 1.8, 14, tick * 0.25, p ->
                    Compat.spawn(world(), Compat.DUST, Fx.ground(p, 3).add(0, 0.15, 0), 1, 0, 0, 0, 0,
                            Compat.dust(STORM, 1.2f)));
            if (tick % 14 == 0) soundAt(tl, "block.note_block.pling", 1.0f, 0.6f + tick / 70f);
            if (tick != 60) return;
            Location mark = Fx.ground(tl, 4);
            world().strikeLightningEffect(mark);
            Compat.spawn(world(), Compat.ELECTRIC_SPARK, mark.clone().add(0, 0.5, 0), 70, 1.0, 0.6, 1.0, 0.3);
            soundAt(mark, "entity.lightning_bolt.impact", 1.6f, 0.9f);
            for (Player p : Fx.playersNear(mark, 3.4)) {
                hit(p, 16 * damageBonus);
                Compat.apply(p, "slowness", 60, 1);
            }
        }, null);
    }

    // ------------------------------------------------------------------ utilidades

    private BlockData groundBlock(Location l) {
        Material m = Fx.ground(l, 3).getBlock().getRelative(0, -1, 0).getType();
        if (!m.isSolid() || m.isAir()) m = Material.DIRT;
        return m.createBlockData();
    }

    private void broadcastNear(Component message) {
        Component line = Component.text("✦ ", ACCENT)
                .append(Component.text("Storm Rider  ", ACCENT, TextDecoration.BOLD))
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
