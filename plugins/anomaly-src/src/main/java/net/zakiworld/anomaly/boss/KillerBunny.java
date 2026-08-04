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
import net.zakiworld.anomaly.core.Stop;
import net.zakiworld.anomaly.core.Tags;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * EL CONEJO ASESINO, la tercera anomalia.
 *
 * El killer bunny del Aether. Su gracia es la de siempre: cada vez que muerde a
 * alguien SE MULTIPLICA. La copia tambien muerde, y tambien se multiplica, asi que la
 * pelea se le va de las manos al grupo en cuestion de segundos si no las limpian.
 *
 * El tope son 20 copias vivas. No es un numero decorativo: sin el, veinte jugadores
 * mordidos a la vez tiran el servidor.
 *
 * NO BRILLA ni levanta pilar de luz, a peticion expresa. Solo se sabe donde esta por
 * las coordenadas del anuncio, y para cuando llegas ya lo tienes encima.
 */
public final class KillerBunny extends BossFight {

    public static final String ID = "conejo_asesino";
    public static final TextColor ACCENT = TextColor.color(0xE8DCD2);

    /** Tope duro de copias vivas. */
    public static final int MAX_COPIES = 20;

    /**
     * El nombre que llevan el jefe y TODAS sus copias, siempre oculto. Que sea el mismo
     * es deliberado: no debe haber ninguna forma de saber cual es el conejo de verdad.
     */
    private static final Component DISGUISE = Component.text("Conejo Asesino", ACCENT);

    private static final int FUR = 0xE8DCD2;
    private static final int BLOOD = 0xB01A1A;
    private static final int SOIL = 0x6B4A2F;

    private final List<Rabbit> copies = new ArrayList<>();
    private boolean redEyes;
    private boolean hordeCalled;
    private double damageBonus = 1.0;
    private long lastSplit;

    public KillerBunny(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        super(plugin, event, where);
        abilities.addAll(plugin.registry().bunnyAbilities());
    }

    @Override
    public String bossName() {
        return "Conejo Asesino";
    }

    // ------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        Location spot = arena.clone();

        boss = world().spawn(spot, Rabbit.class, r -> {
            r.setAdult();
            r.setPersistent(true);
            r.setRemoveWhenFarAway(false);
            r.setCanPickupItems(false);
            killerType(r);
            // Nombre puesto pero NUNCA visible, y exactamente el mismo que llevaran las
            // copias: es lo que impide distinguir al de verdad ni siquiera mirando NBT.
            r.customName(DISGUISE);
            r.setCustomNameVisible(false);
        });

        Compat.setAttribute(boss, "attack_damage", 9);
        Compat.setAttribute(boss, "armor", 6);
        Compat.setAttribute(boss, "knockback_resistance", 0.6);
        Compat.setAttribute(boss, "follow_range", 64);
        Compat.setAttribute(boss, "movement_speed", 0.42);
        // Sin escala: del tamano de un conejo normal, como cualquiera de sus copias.
        applyHealth(plugin.registry().scaledHealth(plugin.registry().bunny(), targets(96).size()));
        boss.setMaximumNoDamageTicks(6);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        // Sin Glow.apply a proposito: esta anomalia no se ve venir.

        arrivalAnimation(spot);
    }

    /** Deja al conejo en su variante asesina; si la version no la trae, se queda blanco. */
    private static void killerType(Rabbit r) {
        try {
            r.setRabbitType(Rabbit.Type.THE_KILLER_BUNNY);
        } catch (Throwable ignored) {
            try {
                r.setRabbitType(Rabbit.Type.WHITE);
            } catch (Throwable ignored2) {
            }
        }
    }

    /**
     * La llegada: nada de tormentas ni grietas. La hierba se mueve, se oyen saltos,
     * y de repente esta ahi. Es la unica anomalia que no se anuncia sola en el sitio.
     */
    private void arrivalAnimation(Location spot) {
        boss.setInvulnerable(true);
        busyFor(50);
        soundAt(spot, "entity.rabbit.jump", 1.0f, 0.6f);

        animate(50, tick -> {
            Location g = Fx.ground(spot, 4);
            if (tick % 4 == 0) {
                double a = Math.random() * Math.PI * 2;
                double d = Math.random() * 6;
                Location p = Fx.ground(g.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d), 3);
                Compat.spawn(world(), Compat.BLOCK, p.clone().add(0, 0.2, 0), 8, 0.25, 0.05, 0.25, 0.03,
                        groundBlock(p));
                Compat.sound(world(), p, "block.grass.step", 0.8f, 1.4f);
            }
            if (tick == 40) {
                soundAt(g, "entity.rabbit.attack", 1.4f, 0.5f);
                Compat.spawn(world(), Compat.DUST, g.clone().add(0, 0.8, 0), 40, 0.7, 0.5, 0.7, 0,
                        Compat.dust(BLOOD, 1.4f));
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            soundAt(boss.getLocation(), "entity.rabbit.attack", 1.6f, 0.4f);
            for (Player p : Fx.viewersNear(spot, 80)) {
                p.showTitle(Title.title(
                        Component.text("✦ ANOMALIA ✦", ACCENT, TextDecoration.BOLD),
                        Component.text("Algo se mueve entre la hierba", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1600), Duration.ofMillis(600))));
            }
        });
    }

    // -------------------------------------------------------------------- ambiente

    @Override
    protected void ambient() {
        pruneCopies();
        if (ticks() % 5 != 0 || !alive()) return;
        // Las particulas las sueltan el jefe Y las copias, con el mismo color. Antes solo
        // las tiraba el jefe y eso lo senalaba a distancia, que es justo lo contrario
        // de lo que se busca con esta anomalia.
        var tint = Compat.dust(redEyes ? BLOOD : FUR, 0.9f);
        Compat.spawn(world(), Compat.DUST, boss.getLocation().add(0, 0.4, 0), 1, 0.3, 0.2, 0.3, 0, tint);
        for (Rabbit r : copies) {
            if (r != null && r.isValid()) {
                Compat.spawn(world(), Compat.DUST, r.getLocation().add(0, 0.4, 0), 1, 0.3, 0.2, 0.3, 0, tint);
            }
        }
        if (ticks() % 60 == 0 && !copies.isEmpty()) {
            warn(Component.text("Copias vivas  ", NamedTextColor.GRAY)
                    .append(Component.text(copies.size() + " / " + MAX_COPIES,
                            copies.size() >= MAX_COPIES ? NamedTextColor.RED : NamedTextColor.GOLD,
                            TextDecoration.BOLD)));
        }
    }

    // ------------------------------------------------------------ LA MULTIPLICACION

    /**
     * Lo que define a esta anomalia: muerde y se multiplica.
     *
     * Vale tanto si muerde el jefe como si muerde una copia, que es lo que hace que
     * la cosa se dispare. El enfriamiento corto evita que un jugador rodeado genere
     * veinte copias en el mismo tick.
     */
    @Override
    public void onDealtDamage(Player victim, Entity dealer) {
        if (!alive() || copies.size() >= MAX_COPIES) return;
        if (ticks() - lastSplit < 6) return;
        lastSplit = ticks();
        Location from = dealer != null && dealer.isValid() ? dealer.getLocation() : boss.getLocation();
        split(from, 1);
    }

    /** Saca n copias alrededor de un punto, respetando el tope. */
    private void split(Location from, int n) {
        for (int i = 0; i < n; i++) {
            if (copies.size() >= MAX_COPIES) {
                return;
            }
            double a = Math.random() * Math.PI * 2;
            Location sl = Fx.ground(from.clone().add(Math.cos(a) * 1.8, 1, Math.sin(a) * 1.8), 4);

            Rabbit copy = world().spawn(sl, Rabbit.class, r -> {
                r.setAdult();
                r.setPersistent(false);
                r.setCanPickupItems(false);
                killerType(r);
                // Mismo nombre oculto y mismo tamano que el jefe. Lo unico que cambia
                // es la vida y el dano, y eso no se ve.
                r.customName(DISGUISE);
                r.setCustomNameVisible(false);
                Compat.setAttribute(r, "max_health", 20);
                Compat.setAttribute(r, "attack_damage", 5);
                Compat.setAttribute(r, "movement_speed", 0.44);
                r.setHealth(20);
            });
            markMinion(copy);
            copies.add(copy);

            Compat.spawn(world(), Compat.DUST, sl.clone().add(0, 0.5, 0), 20, 0.4, 0.4, 0.4, 0,
                    Compat.dust(FUR, 1.3f));
            Compat.spawn(world(), Compat.POOF, sl, 10, 0.3, 0.3, 0.3, 0.03);
            soundAt(sl, "entity.rabbit.jump", 1.1f, 1.4f);
        }
    }

    private void pruneCopies() {
        copies.removeIf(r -> r == null || !r.isValid() || r.isDead());
    }

    public int copyCount() {
        return copies.size();
    }

    /**
     * Cuantas mas copias vivas, mas cuesta hacerle dano al conejo grande.
     *
     * Es lo que convierte la multiplicacion en una mecanica de verdad en vez de en un
     * estorbo: si el grupo se dedica solo al jefe, el jefe casi no baja; hay que
     * repartirse y limpiar copias. Nunca pasa de la mitad del dano.
     */
    @Override
    public double incomingDamageMultiplier() {
        return Math.max(0.5, 1.0 - copies.size() * 0.025);
    }

    // --------------------------------------------------------------- cambio de fase

    @Override
    protected void onPhaseChange(int from, int to) {
        if (event.bars() != null) event.bars().flash(from);
        if (to == 2) redEyesPhase();
        if (to == 3) theHorde();
    }

    /** FASE I -> II. Se le ponen los ojos rojos y a partir de ahi muerde mas fuerte. */
    private void redEyesPhase() {
        if (redEyes || !alive()) return;
        redEyes = true;
        boss.setInvulnerable(true);
        busyFor(60);

        Location spot = boss.getLocation();
        soundAt(spot, "entity.rabbit.attack", 1.6f, 0.4f);
        broadcastNear(Component.text("Se le ponen los ojos rojos.", ACCENT));

        animate(60, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 0.7, 0);
            Fx.sphere(l, 1.1, 18, p -> Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0,
                    Compat.dust(BLOOD, 1.4f)));
            if (tick % 10 == 0) soundAt(l, "entity.rabbit.hurt", 1.2f, 0.6f);
            if (tick == 40) {
                Compat.spawn(world(), Compat.DUST, l, 60, 0.7, 0.6, 0.7, 0, Compat.dust(BLOOD, 1.8f));
                split(boss.getLocation(), 4);
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            damageBonus = 1.2;
            Compat.setAttribute(boss, "attack_damage", 12);
            Compat.setAttribute(boss, "movement_speed", 0.5);
            titleNear(Component.text("FASE II", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("Ya no es uno solo", NamedTextColor.GRAY));
        });
    }

    /** FASE II -> III. Llama a la horda: sale de golpe hasta el tope de copias. */
    private void theHorde() {
        if (hordeCalled || !alive()) return;
        hordeCalled = true;
        boss.setInvulnerable(true);
        busyFor(90);

        Location spot = boss.getLocation();
        soundAt(spot, "entity.rabbit.attack", 1.8f, 0.3f);
        titleNear(Component.text("LA HORDA", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Limpien copias o no bajara nunca", NamedTextColor.GRAY));

        animate(90, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            if (tick % 6 == 0) {
                split(l, 2);
                Compat.spawn(world(), Compat.BLOCK, Fx.ground(l, 3), 20, 1.2, 0.1, 1.2, 0.08, groundBlock(l));
                soundAt(l, "entity.rabbit.jump", 1.2f, 1.0f);
            }
            Fx.ring(Fx.ground(l, 3).add(0, 0.2, 0), 3.0, 18, tick * 0.2, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(BLOOD, 1.3f)));
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            damageBonus = 1.4;
            Compat.setAttribute(boss, "movement_speed", 0.56);
            soundAt(boss.getLocation(), "entity.rabbit.attack", 1.8f, 0.5f);
        });
    }

    // ---------------------------------------------------------------------- muerte

    @Override
    public void onDeath() {
        Location l = loc();
        soundAt(l, "entity.rabbit.death", 1.8f, 0.5f);

        // Al caer el jefe, las copias se deshacen una a una: la plaga se apaga sola.
        int delay = 0;
        for (Rabbit copy : new ArrayList<>(copies)) {
            later(delay += 3, () -> {
                if (copy == null || !copy.isValid()) return;
                Location cl = copy.getLocation();
                Compat.spawn(world(), Compat.POOF, cl.clone().add(0, 0.4, 0), 14, 0.3, 0.3, 0.3, 0.04);
                Compat.spawn(world(), Compat.DUST, cl.clone().add(0, 0.4, 0), 10, 0.3, 0.3, 0.3, 0,
                        Compat.dust(FUR, 1.2f));
                Compat.sound(world(), cl, "entity.rabbit.death", 0.7f, 1.3f);
                spawned.remove(copy);
                Fx.safeRemove(copy);
            });
        }
        copies.clear();

        animate(70, tick -> {
            double t = tick / 70.0;
            Fx.ring(l.clone().add(0, 0.3 + t * 1.4, 0), 1.8 * (1 - t) + 0.3, 16, tick * 0.3, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(BLOOD, 1.4f)));
            if (tick % 10 == 0) {
                Compat.spawn(world(), Compat.DUST, l.clone().add(0, 0.6, 0), 16, 0.5, 0.4, 0.5, 0,
                        Compat.dust(FUR, 1.3f));
            }
        }, () -> {
            Compat.spawn(world(), Compat.POOF, l, 60, 0.7, 0.5, 0.7, 0.08);
            Compat.spawn(world(), Compat.EXPLOSION, l.clone().add(0, 0.5, 0), 1);
            soundAt(l, "entity.rabbit.death", 1.2f, 0.9f);
            soundAt(l, "block.grass.break", 1.4f, 0.7f);
        });
    }

    // ============================================================== HABILIDADES ==

    // ------------------------------------------------------------ FASE I: la plaga

    /** 1. Camada: se parte en tres de golpe, sin necesidad de morder a nadie. */
    public void litter() {
        if (!alive()) return;
        Location l = boss.getLocation();
        soundAt(l, "entity.rabbit.jump", 1.4f, 0.7f);
        broadcastNear(Component.text("Se parte en varios.", ACCENT));

        animate(30, tick -> {
            if (!alive()) return;
            Location c = boss.getLocation().add(0, 0.5, 0);
            Fx.ring(c, 1.2, 10, tick * 0.4, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(FUR, 1.2f)));
            if (tick == 20) split(boss.getLocation(), 3);
        }, null);
    }

    /** 2. Salto Asesino: se lanza sobre alguien desde arriba. */
    public void killerLeap() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        Location mark = Fx.ground(target.getLocation(), 4);

        soundAt(loc(), "entity.rabbit.jump", 1.5f, 0.9f);
        animate(60, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            if (tick == 10) {
                boss.setVelocity(new Vector(0, 1.05, 0));
                Compat.spawn(world(), Compat.BLOCK, Fx.ground(l, 3), 20, 0.5, 0.05, 0.5, 0.06, groundBlock(l));
                return;
            }
            if (tick > 10 && tick < 32) {
                Fx.telegraph(world(), mark, 2.6, BLOOD);
                Compat.spawn(world(), Compat.DUST, l, 2, 0.2, 0.2, 0.2, 0, Compat.dust(FUR, 1.0f));
                return;
            }
            if (tick == 32) {
                boss.teleport(mark.clone().add(0, 6, 0));
                return;
            }
            if (tick < 42) {
                boss.setVelocity(new Vector(0, -1.4, 0));
                return;
            }
            if (tick != 42) return;
            boss.teleport(mark);
            Compat.spawn(world(), Compat.BLOCK, mark, 80, 1.2, 0.3, 1.2, 0.2, groundBlock(mark));
            Compat.spawn(world(), Compat.DUST, mark.clone().add(0, 0.6, 0), 40, 0.8, 0.4, 0.8, 0,
                    Compat.dust(BLOOD, 1.5f));
            soundAt(mark, "entity.rabbit.attack", 1.6f, 0.6f);
            for (Player p : Fx.playersNear(mark, 3.4)) {
                hit(p, 14 * damageBonus);
                push(p, p.getLocation().toVector().subtract(mark.toVector()).normalize().setY(0.5));
            }
        }, null);
    }

    /** 3. Madriguera: se hunde en el suelo y sale al lado del que menos se lo espera. */
    public void burrow() {
        Player target = Fx.farthest(loc(), plugin.settings().participationRadius());
        if (target == null || !alive()) return;

        soundAt(loc(), "block.rooted_dirt.break", 1.4f, 0.7f);
        broadcastNear(Component.text("Se mete bajo tierra.", ACCENT));

        animate(70, tick -> {
            if (!alive()) return;
            if (tick < 16) {
                Location l = boss.getLocation();
                Compat.spawn(world(), Compat.BLOCK, Fx.ground(l, 3), 26, 0.5, 0.1, 0.5, 0.1, groundBlock(l));
                if (tick % 5 == 0) soundAt(l, "block.gravel.break", 1.0f, 0.8f);
                return;
            }
            if (tick == 16) {
                boss.setInvisible(true);
                boss.setInvulnerable(true);
                return;
            }
            if (tick < 50) {
                // el bulto avanzando bajo tierra hacia la victima
                double t = (tick - 16) / 34.0;
                Location from = arena.clone();
                Location tl = Fx.ground(target.getLocation(), 4);
                Location bump = boss.getLocation().clone();
                Vector step = tl.toVector().subtract(bump.toVector()).setY(0);
                if (step.lengthSquared() > 0.04) {
                    boss.teleport(bump.add(step.normalize().multiply(0.45)));
                }
                Location g = Fx.ground(boss.getLocation(), 3);
                Compat.spawn(world(), Compat.BLOCK, g.clone().add(0, 0.15, 0), 10, 0.3, 0.05, 0.3, 0.05,
                        groundBlock(g));
                if (tick % 6 == 0) Compat.sound(world(), g, "block.rooted_dirt.step", 1.1f, 0.7f);
                return;
            }
            if (tick != 50) return;
            boss.setInvisible(false);
            boss.setInvulnerable(false);
            Location g = Fx.ground(boss.getLocation(), 3);
            boss.setVelocity(new Vector(0, 0.7, 0));
            Compat.spawn(world(), Compat.BLOCK, g, 90, 0.8, 0.4, 0.8, 0.25, groundBlock(g));
            soundAt(g, "entity.rabbit.attack", 1.6f, 0.7f);
            for (Player p : Fx.playersNear(g, 3.2)) {
                hit(p, 13 * damageBonus);
                push(p, new Vector(0, 0.6, 0));
            }
        }, () -> {
            if (alive()) {
                boss.setInvisible(false);
                boss.setInvulnerable(false);
            }
        });
    }

    /** 4. Carrera en Zigzag: cruza la arena a saltos cortos y sin linea recta. */
    public void zigzag() {
        if (!alive()) return;
        Set<UUID> clipped = new HashSet<>();
        soundAt(loc(), "entity.rabbit.jump", 1.3f, 1.2f);

        animate(80, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            if (tick % 10 == 0) {
                Player t = randomTarget();
                Vector dir = t != null
                        ? t.getLocation().toVector().subtract(l.toVector()).setY(0)
                        : new Vector(Math.random() - 0.5, 0, Math.random() - 0.5);
                if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0);
                // el zigzag: se desvia a un lado y a otro en vez de ir de frente
                Vector side = new Vector(-dir.getZ(), 0, dir.getX()).normalize()
                        .multiply((tick / 10) % 2 == 0 ? 0.6 : -0.6);
                boss.setVelocity(dir.normalize().add(side).normalize().multiply(0.95).setY(0.35));
                soundAt(l, "entity.rabbit.jump", 1.0f, 1.3f);
            }
            Compat.spawn(world(), Compat.BLOCK, Fx.ground(l, 3).add(0, 0.15, 0), 5, 0.25, 0.05, 0.25, 0.04,
                    groundBlock(l));
            for (Player p : targets(2.6)) {
                if (!clipped.add(p.getUniqueId())) continue;
                hit(p, 10 * damageBonus);
                push(p, p.getLocation().toVector().subtract(l.toVector()).normalize().setY(0.35));
                soundAt(p.getLocation(), "entity.rabbit.attack", 1.2f, 1.0f);
            }
        }, null);
    }

    /** 5. Patada Trasera: se gira y suelta una coz que manda lejos al mas cercano. */
    public void backKick() {
        Player target = Fx.nearest(loc(), 6);
        if (target == null || !alive()) return;

        soundAt(loc(), "entity.rabbit.hurt", 1.2f, 0.7f);
        animate(35, tick -> {
            if (!alive() || !Fx.isFightable(target)) throw Stop.now();
            Location l = boss.getLocation();
            if (tick < 18) {
                Compat.spawn(world(), Compat.BLOCK, Fx.ground(l, 3), 8, 0.4, 0.05, 0.4, 0.05, groundBlock(l));
                return;
            }
            if (tick != 18) return;
            Vector away = target.getLocation().toVector().subtract(l.toVector()).setY(0);
            if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
            hit(target, 12 * damageBonus);
            push(target, away.normalize().setY(0.55).multiply(1.9));
            Compat.spawn(world(), Compat.CLOUD, target.getLocation().add(0, 0.6, 0), 26, 0.4, 0.3, 0.4, 0.1);
            soundAt(target.getLocation(), "entity.rabbit.attack", 1.5f, 0.8f);
        }, null);
    }

    // ------------------------------------------------------------ FASE II: la marea

    /** 6. Enjambre: todas las copias se lanzan a la vez sobre el mismo jugador. */
    public void swarm() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        pruneCopies();
        if (copies.isEmpty()) {
            split(boss.getLocation(), 3);
        }
        broadcastNear(Component.text("Van todas a por uno.", ACCENT));
        target.sendActionBar(Component.text("Vienen todas a por ti.", NamedTextColor.RED, TextDecoration.BOLD));
        soundAt(target.getLocation(), "entity.rabbit.attack", 1.4f, 1.2f);

        animate(70, tick -> {
            if (!Fx.isFightable(target)) throw Stop.now();
            Location tl = target.getLocation();
            Fx.ring(tl.clone().add(0, 0.15, 0), 2.0, 14, tick * 0.3, p ->
                    Compat.spawn(world(), Compat.DUST, Fx.ground(p, 3).add(0, 0.15, 0), 1, 0, 0, 0, 0,
                            Compat.dust(BLOOD, 1.2f)));
            if (tick % 8 != 0) return;
            for (Rabbit r : copies) {
                if (r == null || !r.isValid()) continue;
                Vector dir = tl.toVector().subtract(r.getLocation().toVector());
                if (dir.lengthSquared() < 0.04) continue;
                r.setVelocity(dir.normalize().multiply(0.8).setY(0.3));
            }
        }, null);
    }

    /** 7. Frenesi: el conejo y todas sus copias se vuelven mucho mas rapidos. */
    public void frenzy() {
        if (!alive()) return;
        soundAt(boss.getLocation(), "entity.rabbit.jump", 1.5f, 1.5f);
        broadcastNear(Component.text("Se aceleran todas.", ACCENT));

        animate(120, tick -> {
            if (!alive()) throw Stop.now();
            Compat.apply(boss, "speed", 30, 2);
            for (Rabbit r : copies) {
                if (r != null && r.isValid()) Compat.apply(r, "speed", 30, 2);
            }
            if (tick % 6 != 0) return;
            Location l = boss.getLocation().add(0, 0.5, 0);
            Compat.spawn(world(), Compat.DUST, l, 3, 0.4, 0.3, 0.4, 0, Compat.dust(BLOOD, 1.1f));
            for (Rabbit r : copies) {
                if (r != null && r.isValid()) {
                    Compat.spawn(world(), Compat.DUST, r.getLocation().add(0, 0.4, 0), 2, 0.2, 0.2, 0.2, 0,
                            Compat.dust(FUR, 0.9f));
                }
            }
        }, null);
    }

    /** 8. Mordisco Profundo: un bocado que sigue sangrando un rato. */
    public void deepBite() {
        Player target = Fx.nearest(loc(), 7);
        if (target == null || !alive()) return;

        soundAt(loc(), "entity.rabbit.attack", 1.5f, 0.6f);
        animate(45, tick -> {
            if (!alive() || !Fx.isFightable(target)) throw Stop.now();
            if (tick < 20) {
                Fx.beam(boss.getLocation().add(0, 0.6, 0), target.getLocation().add(0, 1, 0), 0.4, p ->
                        Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(BLOOD, 1.1f)));
                return;
            }
            if (tick != 20) return;
            hit(target, 16 * damageBonus);
            Compat.apply(target, "wither", 120, 1);
            Compat.spawn(world(), Compat.DAMAGE_INDICATOR, target.getLocation().add(0, 1.1, 0), 24,
                    0.3, 0.4, 0.3, 0.15);
            Compat.spawn(world(), Compat.DUST, target.getLocation().add(0, 1.1, 0), 30, 0.4, 0.5, 0.4, 0,
                    Compat.dust(BLOOD, 1.6f));
            soundAt(target.getLocation(), "entity.rabbit.attack", 1.7f, 0.5f);
            target.sendActionBar(Component.text("Te ha mordido hondo.", NamedTextColor.RED, TextDecoration.BOLD));
        }, null);
    }

    /** 9. Campo de Madrigueras: agujeros por toda la arena; pisarlos duele. */
    public void burrowField() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 4);
        List<Location> holes = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            double a = Math.PI * 2 * i / 9.0;
            double d = 4 + Math.random() * 7;
            holes.add(Fx.ground(c.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d), 5));
        }
        soundAt(c, "block.rooted_dirt.break", 1.5f, 0.6f);
        broadcastNear(Component.text("Agujerea el suelo.", ACCENT));

        animate(160, tick -> {
            for (Location h : holes) {
                Compat.spawn(world(), Compat.BLOCK, h.clone().add(0, 0.15, 0), 2, 0.3, 0.05, 0.3, 0.03,
                        groundBlock(h));
                if (tick % 20 == 0) {
                    Fx.ring(h.clone().add(0, 0.15, 0), 1.4, 10, p ->
                            Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(SOIL, 1.3f)));
                }
            }
            if (tick % 20 != 0) return;
            for (Location h : holes) {
                for (Player p : Fx.playersNear(h, 1.6)) {
                    hit(p, 7 * damageBonus);
                    Compat.apply(p, "slowness", 60, 1);
                    Compat.spawn(world(), Compat.BLOCK, p.getLocation(), 20, 0.3, 0.2, 0.3, 0.1, groundBlock(h));
                    soundAt(p.getLocation(), "entity.rabbit.attack", 0.9f, 1.3f);
                }
            }
        }, null);
    }

    /** 10. Zarpazo Giratorio: gira sobre si mismo repartiendo zarpazos. */
    public void spinClaw() {
        if (!alive()) return;
        soundAt(loc(), "entity.rabbit.attack", 1.3f, 1.1f);

        animate(50, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 0.7, 0);
            double a = tick * 0.6;
            Fx.ring(l, 2.6, 10, a, p ->
                    Compat.spawn(world(), Compat.SWEEP_ATTACK, p, 1));
            if (tick % 10 != 0) return;
            soundAt(l, "entity.player.attack.sweep", 1.2f, 1.4f);
            for (Player p : targets(3.2)) {
                hit(p, 8 * damageBonus);
                push(p, p.getLocation().toVector().subtract(l.toVector()).normalize().setY(0.3).multiply(0.6));
            }
        }, null);
    }

    // ----------------------------------------------------------- FASE III: la horda

    /** 11. Estampida de Pelaje: la horda entera cruza la arena en linea. */
    public void furStampede() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        pruneCopies();
        Location start = boss.getLocation();
        Vector dir = target.getLocation().toVector().subtract(start.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        final Vector run = dir.normalize();

        broadcastNear(Component.text("La horda arranca.", ACCENT));
        soundAt(start, "entity.rabbit.jump", 1.6f, 0.7f);

        animate(70, tick -> {
            if (!alive()) return;
            if (tick < 20) {
                for (double d = 2; d < 20; d += 1.2) {
                    Location g = Fx.ground(start.clone().add(run.clone().multiply(d)), 4);
                    Compat.spawn(world(), Compat.DUST, g.clone().add(0, 0.15, 0), 1, 1.2, 0, 1.2, 0,
                            Compat.dust(BLOOD, 1.4f));
                }
                return;
            }
            boss.setVelocity(run.clone().multiply(1.0).setY(boss.getVelocity().getY()));
            for (Rabbit r : copies) {
                if (r != null && r.isValid()) r.setVelocity(run.clone().multiply(1.1).setY(0.15));
            }
            Location l = boss.getLocation();
            Compat.spawn(world(), Compat.BLOCK, Fx.ground(l, 3), 8, 0.6, 0.1, 0.6, 0.06, groundBlock(l));
            if (tick % 5 == 0) soundAt(l, "entity.rabbit.jump", 1.2f, 1.0f);
            for (Player p : targets(3.0)) {
                hit(p, 11 * damageBonus);
                push(p, run.clone().multiply(0.9).setY(0.4));
            }
        }, null);
    }

    /** 12. Salto Lunar: sube muchisimo y cae de golpe, con onda. */
    public void moonLeap() {
        if (!alive()) return;
        Set<UUID> struck = new HashSet<>();
        soundAt(loc(), "entity.rabbit.jump", 1.6f, 0.5f);
        broadcastNear(Component.text("Salta hasta perderse de vista.", ACCENT));

        animate(100, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            if (tick == 12) {
                boss.setVelocity(new Vector(0, 1.6, 0));
                Compat.spawn(world(), Compat.CLOUD, Fx.ground(l, 3), 40, 0.7, 0.1, 0.7, 0.1);
                return;
            }
            if (tick > 12 && tick < 55) {
                Compat.apply(boss, "slow_falling", 20, 0);
                Fx.telegraph(world(), Fx.ground(arena, 4), 5.5, BLOOD);
                return;
            }
            if (tick == 55) {
                Location mark = Fx.ground(arena, 4);
                boss.teleport(mark.clone().add(0, 12, 0));
                return;
            }
            if (tick < 66) {
                boss.setVelocity(new Vector(0, -1.8, 0));
                return;
            }
            if (tick > 90) return;
            if (tick == 66) {
                Location g = Fx.ground(boss.getLocation(), 4);
                Compat.spawn(world(), Compat.EXPLOSION_EMITTER, g, 1);
                Compat.spawn(world(), Compat.BLOCK, g, 160, 2.0, 0.4, 2.0, 0.3, groundBlock(g));
                soundAt(g, "entity.generic.explode", 1.5f, 0.6f);
                soundAt(g, "entity.rabbit.attack", 1.6f, 0.5f);
                return;
            }
            double radius = (tick - 66) * 0.5;
            if (radius > 9) return;
            Location g = Fx.ground(boss.getLocation(), 4);
            Fx.shockwave(world(), g, radius, Compat.CLOUD, 7);
            for (Player p : targets(radius + 1.0)) {
                if (p.getLocation().distance(g) < radius - 1.4) continue;
                if (!struck.add(p.getUniqueId())) continue;
                hit(p, 15 * damageBonus);
                push(p, p.getLocation().toVector().subtract(g.toVector()).normalize().setY(0.55).multiply(0.8));
            }
        }, null);
    }

    /** 13. Division Final: se parte hasta llenar el tope de copias de una vez. */
    public void finalDivision() {
        if (!alive()) return;
        pruneCopies();
        int room = MAX_COPIES - copies.size();
        if (room <= 0) {
            broadcastNear(Component.text("Ya no le caben mas.", ACCENT));
            return;
        }
        broadcastNear(Component.text("Se parte entera.", ACCENT));
        soundAt(loc(), "entity.rabbit.attack", 1.7f, 0.4f);

        animate(60, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            Fx.ring(l.clone().add(0, 0.5, 0), 1.0 + tick * 0.04, 12, tick * 0.5, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(FUR, 1.3f)));
            if (tick % 8 == 0) {
                split(l, 2);
                soundAt(l, "entity.rabbit.jump", 1.1f, 1.3f);
            }
        }, null);
    }

    /** 14. Mordida Final: se agarra a uno y no lo suelta. */
    public void finalBite() {
        Player target = randomTarget();
        if (target == null || !alive()) return;

        soundAt(loc(), "entity.rabbit.attack", 1.8f, 0.4f);
        target.sendActionBar(Component.text("Se te ha agarrado.", NamedTextColor.RED, TextDecoration.BOLD));

        animate(80, tick -> {
            if (!alive() || !Fx.isFightable(target)) throw Stop.now();
            Location tl = target.getLocation();
            if (tick < 24) {
                Vector dir = tl.toVector().subtract(boss.getLocation().toVector());
                if (dir.lengthSquared() > 0.04) boss.setVelocity(dir.normalize().multiply(0.75).setY(0.25));
                Fx.beam(boss.getLocation().add(0, 0.6, 0), tl.clone().add(0, 1, 0), 0.4, p ->
                        Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(BLOOD, 1.3f)));
                return;
            }
            if (tick % 12 != 0) return;
            if (boss.getLocation().distanceSquared(tl) > 25) return;
            hit(target, 9 * damageBonus);
            Compat.spawn(world(), Compat.DAMAGE_INDICATOR, tl.clone().add(0, 1.1, 0), 16, 0.3, 0.3, 0.3, 0.12);
            soundAt(tl, "entity.rabbit.attack", 1.3f, 0.7f);
        }, null);
    }

    // -------------------------------------------------------------- cualquier fase

    /** 15. Devorar: se come una copia y se cura con ella. */
    public void devour() {
        if (!alive()) return;
        pruneCopies();
        if (copies.isEmpty()) return;
        Rabbit prey = copies.get(random.nextInt(copies.size()));
        if (prey == null || !prey.isValid()) return;

        soundAt(loc(), "entity.rabbit.hurt", 1.3f, 0.6f);
        broadcastNear(Component.text("Se esta comiendo a una de las suyas.", ACCENT));

        animate(50, tick -> {
            if (!alive() || !prey.isValid()) throw Stop.now();
            Location pl = prey.getLocation().add(0, 0.4, 0);
            Vector dir = boss.getLocation().toVector().subtract(pl.toVector());
            if (dir.lengthSquared() > 0.04) prey.setVelocity(dir.normalize().multiply(0.5));
            Fx.beam(pl, boss.getLocation().add(0, 0.6, 0), 0.35, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(BLOOD, 1.2f)));
            if (tick < 40) return;
            double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
            boss.setHealth(Math.min(max, boss.getHealth() + max * 0.05));
            Compat.spawn(world(), Compat.HEART == null ? Compat.DUST : Compat.HEART,
                    boss.getLocation().add(0, 1, 0), 10, 0.4, 0.4, 0.4, 0.05);
            Compat.spawn(world(), Compat.POOF, pl, 20, 0.3, 0.3, 0.3, 0.05);
            soundAt(pl, "entity.rabbit.death", 1.2f, 1.2f);
            copies.remove(prey);
            spawned.remove(prey);
            Fx.safeRemove(prey);
            throw Stop.now();
        }, null);
    }

    /**
     * 16. Cambiazo: se intercambia el sitio con sus copias varias veces seguidas.
     *
     * Es la habilidad que remata la idea de esta anomalia: aunque hayas seguido al
     * conejo bueno con la vista, despues de esto ya no sabes cual era. El humo sale a
     * la vez en los dos puntos para que no se pueda deducir quien salio de donde.
     */
    public void swapPlaces() {
        pruneCopies();
        if (!alive() || copies.isEmpty()) return;
        soundAt(loc(), "entity.rabbit.jump", 1.2f, 1.5f);
        broadcastNear(Component.text("Se cambian de sitio.", ACCENT));

        animate(60, tick -> {
            if (!alive()) throw Stop.now();
            if (tick % 18 != 0) return;
            pruneCopies();
            if (copies.isEmpty()) throw Stop.now();

            Rabbit twin = copies.get(random.nextInt(copies.size()));
            if (twin == null || !twin.isValid()) return;
            Location a = boss.getLocation().clone();
            Location b = twin.getLocation().clone();

            puff(a);
            puff(b);
            boss.teleport(b);
            twin.teleport(a);

            // Y de paso se barajan dos copias entre ellas, para meter mas ruido.
            if (copies.size() >= 3) {
                Rabbit x = copies.get(random.nextInt(copies.size()));
                Rabbit y = copies.get(random.nextInt(copies.size()));
                if (x != null && y != null && x.isValid() && y.isValid() && !x.equals(y)) {
                    Location xl = x.getLocation().clone();
                    Location yl = y.getLocation().clone();
                    puff(xl);
                    puff(yl);
                    x.teleport(yl);
                    y.teleport(xl);
                }
            }
        }, null);
    }

    /** La nube que tapa un cambio de sitio. Identica siempre, venga de quien venga. */
    private void puff(Location l) {
        Compat.spawn(world(), Compat.POOF, l.clone().add(0, 0.4, 0), 24, 0.35, 0.35, 0.35, 0.05);
        Compat.spawn(world(), Compat.DUST, l.clone().add(0, 0.4, 0), 16, 0.35, 0.35, 0.35, 0,
                Compat.dust(FUR, 1.3f));
        Compat.sound(world(), l, "entity.rabbit.jump", 0.9f, 1.4f);
    }

    // ------------------------------------------------------------------ utilidades

    private BlockData groundBlock(Location l) {
        Material m = Fx.ground(l, 3).getBlock().getRelative(0, -1, 0).getType();
        if (!m.isSolid() || m.isAir()) m = Material.DIRT;
        return m.createBlockData();
    }

    private void broadcastNear(Component message) {
        Component line = Component.text("✦ ", ACCENT)
                .append(Component.text("Conejo Asesino  ", ACCENT, TextDecoration.BOLD))
                .append(message.colorIfAbsent(NamedTextColor.GRAY));
        for (Player p : Fx.viewersNear(loc(), 80)) p.sendActionBar(line);
    }

    private void titleNear(Component title, Component subtitle) {
        for (Player p : Fx.viewersNear(loc(), 80)) {
            p.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1400), Duration.ofMillis(500))));
        }
    }
}
