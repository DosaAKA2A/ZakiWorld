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
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * DARKNESS, la septima anomalia.
 *
 * Un enderman colosal. Todo lo suyo es morado, negro y ciego: no hay una sola
 * habilidad que no quite vision o no salga del vacio. No tiene esbirros; los siete
 * dobles que saca son parte de su curacion, no ayudantes que peguen.
 *
 * Es, con diferencia, el jefe mas duro de los siete, y esta pensado asi.
 */
public final class Darkness extends BossFight {

    public static final String ID = "darkness";
    public static final TextColor ACCENT = TextColor.color(0xB25CFF);

    private static final int VOID_PURPLE = 0x7B2FBE;
    private static final int MAGENTA = 0xD948E0;
    private static final int PITCH = 0x120A1C;

    /** Cuantos dobles saca durante el ritual de curacion. */
    private static final int DECOYS = 7;
    /** Dano acumulado que hay que meterle al original para romper el ritual. */
    private static final double RITUAL_BREAK = 260;

    private final List<Enderman> decoys = new ArrayList<>();

    private boolean healing;
    private double ritualDamage;
    private boolean colossus;
    private double damageBonus = 1.0;

    public Darkness(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        super(plugin, event, where);
        abilities.addAll(plugin.registry().darknessAbilities());
    }

    @Override
    public String bossName() {
        return "Darkness";
    }

    // ------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        Location spot = arena.clone();

        boss = world().spawn(spot, Enderman.class, e -> {
            e.setPersistent(true);
            e.setRemoveWhenFarAway(false);
            e.setCanPickupItems(false);
            // Un enderman va desmontando el mapa mientras camina. Aqui no.
            e.setCarriedBlock(null);
            e.customName(Component.text("✦ ", ACCENT)
                    .append(Component.text("Darkness", ACCENT, TextDecoration.BOLD)));
            e.setCustomNameVisible(true);
        });

        Compat.setAttribute(boss, "attack_damage", 18);
        Compat.setAttribute(boss, "armor", 16);
        Compat.setAttribute(boss, "armor_toughness", 8);
        Compat.setAttribute(boss, "knockback_resistance", 1.0);
        Compat.setAttribute(boss, "follow_range", 80);
        Compat.setAttribute(boss, "movement_speed", 0.38);
        // Poco mas grande que un enderman normal: lo gordo se guarda para la fase 3.
        Compat.setAttribute(boss, "scale", 1.5);
        applyHealth(plugin.registry().scaledHealth(plugin.registry().darkness(), targets(96).size()));
        boss.setMaximumNoDamageTicks(6);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        Glow.apply(boss, event.type().glowColor());

        arrivalAnimation(spot);
    }

    /** La llegada: la luz se apaga por partes y algo se levanta en el hueco. */
    private void arrivalAnimation(Location spot) {
        boss.setInvulnerable(true);
        busyFor(90);
        soundAt(spot, "entity.enderman.scream", 1.6f, 0.4f);
        soundAt(spot, "ambient.cave", 1.8f, 0.5f);

        animate(90, tick -> {
            double t = tick / 90.0;
            Fx.sphere(spot.clone().add(0, 2, 0), 12 - t * 9, 44, p ->
                    Compat.spawn(world(), Compat.SCULK_SOUL, p, 1, 0, 0, 0, 0, Compat.dust(PITCH, 2.0f)));
            Fx.helix(spot, 3.0, 7.0, 28, 3.0, p ->
                    Compat.spawn(world(), Compat.PORTAL, p, 2, 0.05, 0.05, 0.05, 0.02));
            if (tick % 12 == 0) {
                soundAt(spot, "entity.enderman.teleport", 1.2f, 0.4f);
                for (Player p : Fx.viewersNear(spot, 40)) Compat.apply(p, "darkness", 60, 0);
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            Compat.spawn(world(), Compat.FLASH, spot.clone().add(0, 2, 0), 1);
            soundAt(spot, "entity.enderman.scream", 1.8f, 0.5f);
            for (Player p : Fx.viewersNear(spot, 90)) {
                p.showTitle(Title.title(
                        Component.text("✦ ANOMALIA ✦", ACCENT, TextDecoration.BOLD),
                        Component.text("Darkness  ·  no vas a ver casi nada", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2000), Duration.ofMillis(700))));
            }
        });
    }

    // -------------------------------------------------------------------- ambiente

    @Override
    protected void ambient() {
        if (!alive()) return;
        if (ticks() % 3 == 0) {
            Location l = boss.getLocation().add(0, colossus ? 3.0 : 1.8, 0);
            Compat.spawn(world(), Compat.SMOKE, l, 4, 0.8, 1.0, 0.8, 0.03);
            Compat.spawn(world(), Compat.SQUID_INK, l, 2, 0.7, 0.9, 0.7, 0,
                    Compat.dust(colossus ? 0xFFFFFF : VOID_PURPLE, 1.4f));
        }
        // Una penumbra constante alrededor: la temática por encima de todo.
        if (ticks() % 40 == 0) {
            for (Player p : targets(14)) Compat.apply(p, "darkness", 70, 0);
        }
    }

    @Override
    public double incomingDamageMultiplier() {
        return colossus ? 1.2 : 1.0;
    }

    /** Durante el ritual, cada golpe al original acerca el momento de romperlo. */
    @Override
    public void onDamaged(Player attacker, double amount) {
        if (!healing) return;
        ritualDamage += amount;
        Compat.spawn(world(), Compat.REVERSE_PORTAL, boss.getLocation().add(0, 2, 0), 12, 0.5, 0.6, 0.5, 0,
                Compat.dust(MAGENTA, 1.6f));
        warn(Component.text("Ritual  ", NamedTextColor.GRAY)
                .append(Component.text((int) Math.min(100, ritualDamage / RITUAL_BREAK * 100) + "%",
                        NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
                .append(Component.text("  roto", NamedTextColor.GRAY)));
        if (ritualDamage >= RITUAL_BREAK) breakRitual(true);
    }

    // --------------------------------------------------------------- cambio de fase

    @Override
    protected void onPhaseChange(int from, int to) {
        if (event.bars() != null) event.bars().flash(from);
        if (to == 2) {
            damageBonus = 1.25;
            Compat.setAttribute(boss, "scale", 1.9);
            titleNear(Component.text("FASE II", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("Aprende a curarse", NamedTextColor.GRAY));
            soundAt(loc(), "entity.enderman.scream", 1.6f, 0.5f);
        }
        if (to == 3) becomeColossus();
    }

    /** FASE III. Crece hasta el coloso y pasa a brillar en blanco. */
    private void becomeColossus() {
        if (colossus || !alive()) return;
        colossus = true;
        boss.setInvulnerable(true);
        busyFor(110);
        breakRitual(false);

        Location spot = boss.getLocation();
        soundAt(spot, "entity.enderman.scream", 2.0f, 0.3f);
        titleNear(Component.text("COLOSO", NamedTextColor.WHITE, TextDecoration.BOLD),
                Component.text("Ya no cabe en el sitio", NamedTextColor.GRAY));

        animate(110, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            double scale = 1.9 + (tick / 110.0) * 3.6;
            Compat.setAttribute(boss, "scale", scale);
            Fx.sphere(l.clone().add(0, scale, 0), scale * 1.2, 40, p ->
                    Compat.spawn(world(), Compat.PORTAL, p, 1, 0, 0, 0, 0, Compat.dust(PITCH, 2.0f)));
            Fx.helix(l, scale, scale * 3, 30, 3.0, p ->
                    Compat.spawn(world(), Compat.LARGE_SMOKE, p, 2, 0.05, 0.05, 0.05, 0.03));
            if (tick % 14 == 0) {
                soundAt(l, "entity.enderman.stare", 1.6f, 0.4f);
                world().strikeLightningEffect(Fx.ground(l, 6));
                for (Player p : Fx.viewersNear(l, 40)) Compat.apply(p, "darkness", 100, 0);
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            damageBonus = 1.6;
            Compat.setAttribute(boss, "attack_damage", 26);
            Compat.setAttribute(boss, "movement_speed", 0.44);
            // Ya no es morado: en la ultima fase el brillo pasa a blanco.
            Glow.clear(boss);
            Glow.apply(boss, NamedTextColor.WHITE);
            Compat.spawn(world(), Compat.FLASH, boss.getLocation().add(0, 3, 0), 1);
            soundAt(boss.getLocation(), "entity.wither.spawn", 1.8f, 0.4f);
        });
    }

    // ---------------------------------------------------------------------- muerte

    @Override
    public void onDeath() {
        Location l = loc();
        clearDecoys();
        Glow.clear(boss);
        soundAt(l, "entity.enderman.death", 1.8f, 0.4f);

        animate(90, tick -> {
            double t = tick / 90.0;
            Fx.sphere(l.clone().add(0, 2, 0), 6 * (1 - t) + 0.5, 40, p ->
                    Compat.spawn(world(), Compat.SCULK_CHARGE_POP, p, 1, 0, 0, 0, 0, Compat.dust(PITCH, 2.0f)));
            Fx.helix(l, 2.0 * (1 - t) + 0.4, 6.0, 26, 3.0, p ->
                    Compat.spawn(world(), Compat.PORTAL, p, 2, 0.03, 0.03, 0.03, 0.04));
            if (tick % 10 == 0) {
                Compat.spawn(world(), Compat.DRAGON_BREATH, l.clone().add(0, 2, 0), 40, 1.0, 1.5, 1.0, 0.2);
                soundAt(l, "entity.enderman.teleport", 1.0f, 0.4f + (float) t);
            }
        }, () -> {
            Compat.spawn(world(), Compat.FLASH, l.clone().add(0, 2, 0), 1);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l, 2);
            soundAt(l, "entity.enderman.scream", 1.4f, 1.4f);
            soundAt(l, "block.beacon.deactivate", 1.6f, 0.4f);
        });
    }

    // ============================================================== HABILIDADES ==

    // ------------------------------------------------------- EL RITUAL DE CURACION

    /**
     * 1. Septeto: se parte en siete dobles que vibran de rabia mientras el original
     * se cura.
     *
     * Los dobles NO atacan ni se mueven del sitio: son un escondite. Hay que encontrar
     * al de verdad y meterle dano suficiente para romperle el ritual. Si no lo logran,
     * recupera una barbaridad de vida.
     */
    public void septet() {
        if (!alive() || healing) return;
        healing = true;
        ritualDamage = 0;
        clearDecoys();

        Location c = boss.getLocation();
        soundAt(c, "entity.enderman.scream", 1.8f, 0.4f);
        titleNear(Component.text("SE ESTA CURANDO", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                Component.text("Encuentra al de verdad y rompele el ritual", NamedTextColor.GRAY));

        // Siete dobles repartidos en circulo, mas el original entre ellos.
        List<Location> spots = new ArrayList<>();
        for (int i = 0; i < DECOYS; i++) {
            double a = Math.PI * 2 * i / DECOYS;
            spots.add(Fx.ground(c.clone().add(Math.cos(a) * 7, 1, Math.sin(a) * 7), 6));
        }
        // El original se mete en uno de los sitios del corro; asi no canta por posicion.
        Location mine = spots.get(random.nextInt(spots.size()));
        boss.teleport(mine);

        for (Location sl : spots) {
            if (sl.equals(mine)) continue;
            Enderman d = world().spawn(sl, Enderman.class, e -> {
                e.setPersistent(false);
                e.setAI(false);
                e.setInvulnerable(true);
                e.setSilent(true);
                e.customName(Component.text("✦ ", ACCENT)
                        .append(Component.text("Darkness", ACCENT, TextDecoration.BOLD)));
                e.setCustomNameVisible(true);
                Compat.setAttribute(e, "scale", Compat.getAttribute(boss, "scale", 2.5));
            });
            markMinion(d);
            Glow.apply(d, event.type().glowColor());
            decoys.add(d);
        }

        animate(300, tick -> {
            if (!alive() || !healing) throw Stop.now();
            Location l = boss.getLocation();

            // Cada dos segundos el original y un doble se cambian el sitio, los dos con
            // el mismo humo. Aunque lo hayas seguido con la vista, lo pierdes: es lo que
            // convierte el ritual en una busqueda y no en pegarle al que ya tenias fichado.
            if (tick > 20 && tick % 40 == 0 && !decoys.isEmpty()) {
                Enderman twin = decoys.get(random.nextInt(decoys.size()));
                if (twin != null && twin.isValid()) {
                    Location here = boss.getLocation().clone();
                    Location there = twin.getLocation().clone();
                    blink(here);
                    blink(there);
                    boss.teleport(there);
                    twin.teleport(here);
                }
            }

            // Todos vibran igual, el original incluido: no hay pista visual.
            for (Enderman d : decoys) {
                if (d == null || !d.isValid()) continue;
                shiver(d.getLocation());
            }
            shiver(l);

            if (tick % 20 == 0) {
                double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
                boss.setHealth(Math.min(max, boss.getHealth() + max * 0.02));
                Compat.spawn(world(), Compat.HEART, l.clone().add(0, 2.6, 0), 3, 0.3, 0.3, 0.3, 0.02);
                soundAt(l, "block.beacon.ambient", 0.8f, 0.4f);
            }
            if (tick % 30 == 0) {
                for (Player p : targets(20)) Compat.apply(p, "darkness", 90, 0);
            }
        }, () -> breakRitual(false));
    }

    /** El humo que tapa un cambio de sitio. Identico siempre, venga de quien venga. */
    private void blink(Location at) {
        Compat.spawn(world(), Compat.REVERSE_PORTAL, at.clone().add(0, 1.5, 0), 40, 0.4, 0.9, 0.4, 0.12);
        Compat.spawn(world(), Compat.SMOKE, at.clone().add(0, 1.5, 0), 20, 0.4, 0.8, 0.4, 0,
                Compat.dust(VOID_PURPLE, 1.5f));
        Compat.sound(world(), at, "entity.enderman.teleport", 1.0f, 0.6f);
    }

    /** El temblor de rabia de un doble: se mueve un pelo pero no se va del sitio. */
    private void shiver(Location at) {
        Compat.spawn(world(), Compat.SCULK_SOUL, at.clone().add(0, 1.6, 0), 6, 0.5, 1.0, 0.5, 0.06);
        Compat.spawn(world(), Compat.SCULK_SOUL, at.clone().add(0, 1.6, 0), 2, 0.45, 0.9, 0.45, 0,
                Compat.dust(VOID_PURPLE, 1.4f));
    }

    /**
     * Cierra el ritual. Si lo rompieron a golpes, se lleva un castigo; si aguanto
     * hasta el final, ya se ha curado solo por el camino.
     */
    private void breakRitual(boolean byPlayers) {
        if (!healing) return;
        healing = false;
        clearDecoys();
        if (!alive()) return;

        Location l = boss.getLocation();
        Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l.clone().add(0, 1.5, 0), 1);
        if (byPlayers) {
            soundAt(l, "entity.enderman.hurt", 1.8f, 0.5f);
            Compat.apply(boss, "slowness", 120, 2);
            titleNear(Component.text("RITUAL ROTO", NamedTextColor.GREEN, TextDecoration.BOLD),
                    Component.text("Se queda aturdido unos segundos", NamedTextColor.GRAY));
        } else {
            soundAt(l, "entity.enderman.scream", 1.8f, 0.6f);
            broadcastNear(Component.text("Termino de curarse.", NamedTextColor.RED));
        }
    }

    private void clearDecoys() {
        for (Enderman d : decoys) {
            if (d == null) continue;
            Glow.clear(d);
            spawned.remove(d);
            Fx.safeRemove(d);
        }
        decoys.clear();
    }

    // ------------------------------------------------------- LAS CORONAS DE FAROS

    /**
     * 2. Prision de Vacio: una cupula de sombra que se cierra sobre la arena.
     *
     * Sustituye a las coronas de faro, que sobre el terreno real quedaban mal: las
     * columnas se clavaban en las paredes y se veian partidas. Esto no depende del
     * relieve, se lee de lejos y obliga a lo mismo pero mejor: pelear pegados a el.
     */
    public void voidPrison() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 6);
        soundAt(c, "entity.enderman.scream", 1.8f, 0.4f);
        titleNear(Component.text("PRISION DE VACIO", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                Component.text("La sombra se cierra: quedate dentro", NamedTextColor.GRAY));

        animate(220, tick -> {
            if (!alive()) throw Stop.now();
            Location center = Fx.ground(boss.getLocation(), 6);
            double t = Math.min(1.0, tick / 180.0);
            double radius = 20 - t * 13;

            // La pared: un cilindro de sombra que se cierra sobre el jefe.
            for (double h = 0; h <= 6; h += 1.5) {
                Fx.ring(center.clone().add(0, h, 0), radius, (int) (radius * 3) + 10, tick * 0.04, p ->
                        Compat.spawn(world(), Compat.SQUID_INK, p, 1, 0, 0.2, 0, 0, Compat.dust(PITCH, 2.2f)));
            }
            Fx.ring(center.clone().add(0, 0.3, 0), radius, (int) (radius * 4) + 12, p ->
                    Compat.spawn(world(), Compat.SCULK_CHARGE_POP, Fx.ground(p, 4).add(0, 0.25, 0), 1, 0, 0, 0, 0,
                            Compat.dust(MAGENTA, 1.7f)));

            if (tick % 20 == 0) soundAt(center, "ambient.cave", 1.2f, 0.4f);
            if (tick % 10 != 0) return;

            for (Player p : targets(60)) {
                if (p.getLocation().distance(center) <= radius) continue;
                hit(p, 15 * damageBonus);
                Compat.apply(p, "darkness", 120, 0);
                // Empujado hacia dentro: la pared no deja salir, mete de vuelta.
                push(p, center.toVector().subtract(p.getLocation().toVector())
                        .normalize().multiply(1.1).setY(0.25));
                p.sendActionBar(Component.text("La sombra no te deja salir.",
                        NamedTextColor.RED, TextDecoration.BOLD));
                soundAt(p.getLocation(), "entity.enderman.hurt", 1.1f, 0.5f);
            }
        }, null);
    }

    // ----------------------------------------------------------- EL CAMPO CINETICO

    /**
     * 3. Campo Cinetico: agarra a todos los de alrededor, los levanta despacio mientras
     * el se carga vibrando de rabia, y revienta mandandolos por los aires.
     */
    public void kineticField() {
        if (!alive()) return;
        Location c = boss.getLocation();
        List<Player> caught = new ArrayList<>(targets(14));
        if (caught.isEmpty()) return;

        soundAt(c, "block.beacon.power_select", 1.6f, 0.4f);
        titleNear(Component.text("CAMPO CINETICO", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                Component.text("Te tiene sujeto", NamedTextColor.GRAY));

        animate(150, tick -> {
            if (!alive()) throw Stop.now();
            Location l = boss.getLocation().add(0, 1.8, 0);

            if (tick < 110) {
                // Los sujeta y los sube despacio, sin dejarles caer.
                for (Player p : caught) {
                    if (!Fx.isFightable(p)) continue;
                    // Por lift() y no a pelo: es lo que concede el permiso de vuelo
                    // temporal. Sin el, el servidor expulsaba por volar a media subida.
                    lift(p, new Vector(0, 0.14, 0));
                    Compat.apply(p, "slow_falling", 40, 0);
                    Compat.spawn(world(), Compat.PORTAL, p.getLocation().add(0, 1, 0), 6, 0.4, 0.6, 0.4, 0.04);
                    Fx.beam(l, p.getLocation().add(0, 1, 0), 0.7, q ->
                            Compat.spawn(world(), Compat.SMOKE, q, 1, 0, 0, 0, 0, Compat.dust(VOID_PURPLE, 1.1f)));
                }
                // El vibra cada vez mas fuerte segun se carga.
                double shake = tick / 110.0;
                Fx.sphere(l, 1.4 + shake * 1.6, (int) (18 + shake * 26), p ->
                        Compat.spawn(world(), Compat.SCULK_CHARGE_POP, p, 1, 0, 0, 0, 0,
                                Compat.dust(MAGENTA, (float) (1.0 + shake))));
                if (tick % Math.max(3, (int) (12 - shake * 9)) == 0) {
                    soundAt(l, "entity.enderman.stare", 1.0f, 0.4f + (float) shake);
                }
                return;
            }
            if (tick != 110) return;

            Compat.spawn(world(), Compat.FLASH, l, 1);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l, 3);
            Compat.spawn(world(), Compat.SMOKE, l, 200, 4.0, 4.0, 4.0, 0, Compat.dust(MAGENTA, 2.0f));
            soundAt(l, "entity.generic.explode", 2.0f, 0.4f);
            soundAt(l, "entity.enderman.scream", 1.8f, 0.5f);

            for (Player p : caught) {
                if (!Fx.isFightable(p)) continue;
                Vector away = p.getLocation().toVector().subtract(l.toVector());
                if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 1);
                hit(p, 26 * damageBonus);
                // Los manda MUY lejos: es el sello de esta habilidad.
                push(p, away.normalize().multiply(3.2).setY(1.1));
                Compat.apply(p, "slow_falling", 100, 0);
                Compat.apply(p, "darkness", 120, 0);
            }
        }, null);
    }

    // ---------------------------------------------------------- resto de habilidades

    /** 4. Velo de Oscuridad: apaga la vista de todo el que este cerca. */
    public void veil() {
        if (!alive()) return;
        Location c = loc();
        soundAt(c, "ambient.cave", 1.8f, 0.4f);
        broadcastNear(Component.text("Apaga la luz.", ACCENT));

        animate(90, tick -> {
            double r = 3 + tick * 0.18;
            Fx.sphere(c, r, 36, p ->
                    Compat.spawn(world(), Compat.SCULK_SOUL, p, 1, 0.2, 0.2, 0.2, 0, Compat.dust(PITCH, 2.0f)));
            if (tick % 15 != 0) return;
            for (Player p : Fx.playersNear(c, r)) {
                Compat.apply(p, "darkness", 200, 0);
                Compat.apply(p, "blindness", 80, 0);
                if (tick % 30 == 0) hit(p, 9 * damageBonus);
            }
        }, null);
    }

    /** 5. Parpadeo: aparece detras de uno, pega y desaparece. Tres veces. */
    public void blink() {
        if (!alive()) return;
        soundAt(loc(), "entity.enderman.teleport", 1.5f, 0.6f);

        // Tres apariciones y cada una a la espalda de un jugador DISTINTO: antes
        // el azar podia cebarse tres veces con el mismo.
        List<Player> marks = pickTargets(3);
        if (marks.isEmpty()) return;
        for (int i = 0; i < 3; i++) {
            Player t = marks.get(i % marks.size());
            later(i * 26, () -> {
                if (!alive() || !Fx.isFightable(t)) return;
                Location from = boss.getLocation();
                Vector behind = t.getLocation().getDirection().setY(0).normalize().multiply(-2.5);
                Location to = Fx.ground(t.getLocation().add(behind), 4);

                Compat.spawn(world(), Compat.REVERSE_PORTAL, from.clone().add(0, 1.5, 0), 50, 0.5, 1.0, 0.5, 0.15);
                boss.teleport(to);
                Compat.spawn(world(), Compat.PORTAL, to.clone().add(0, 1.5, 0), 60, 0.5, 1.2, 0.5, 0.2);
                soundAt(to, "entity.enderman.teleport", 1.4f, 0.7f);

                later(8, () -> {
                    if (!alive() || !Fx.isFightable(t)) return;
                    hit(t, 17 * damageBonus);
                    Compat.apply(t, "darkness", 100, 0);
                    Compat.spawn(world(), Compat.CRIT, t.getLocation().add(0, 1, 0), 24, 0.3, 0.4, 0.3, 0.25);
                    soundAt(t.getLocation(), "entity.enderman.hurt", 1.3f, 0.6f);
                });
            });
        }
    }

    /** 6. La Mirada: fija a uno y lo castiga si le da la espalda. */
    public void stare() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        soundAt(loc(), "entity.enderman.stare", 1.8f, 0.5f);
        target.sendActionBar(Component.text("Te esta mirando. No le des la espalda.",
                NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));

        animate(160, tick -> {
            if (!alive() || !Fx.isFightable(target)) throw Stop.now();
            Location l = boss.getLocation().add(0, 2.0, 0);
            Location tl = target.getLocation().add(0, 1, 0);
            Fx.beam(l, tl, 0.7, p ->
                    Compat.spawn(world(), Compat.SQUID_INK, p, 1, 0, 0, 0, 0, Compat.dust(MAGENTA, 1.0f)));
            if (tick % 20 != 0) return;

            Vector look = target.getLocation().getDirection().setY(0);
            Vector toBoss = l.toVector().subtract(tl.toVector()).setY(0);
            boolean facing = look.lengthSquared() > 0.01 && toBoss.lengthSquared() > 0.01
                    && look.normalize().dot(toBoss.normalize()) > 0.2;
            if (facing) {
                target.sendActionBar(Component.text("Aguanta la mirada.", NamedTextColor.GREEN));
                return;
            }
            hit(target, 14 * damageBonus);
            Compat.apply(target, "darkness", 120, 0);
            Compat.spawn(world(), Compat.DRAGON_BREATH, tl, 30, 0.4, 0.5, 0.4, 0, Compat.dust(PITCH, 1.8f));
            soundAt(tl, "entity.enderman.scream", 1.2f, 0.7f);
        }, null);
    }

    /** 7. Pulso Negro: una onda de vacio que apaga la vista al pasar. */
    public void blackPulse() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 6);
        Set<UUID> struck = new HashSet<>();
        soundAt(c, "entity.enderman.scream", 1.6f, 0.5f);

        animate(70, tick -> {
            if (tick < 20) {
                Fx.telegraph(world(), c, 12.0, VOID_PURPLE);
                return;
            }
            double radius = (tick - 20) * 0.5;
            if (radius > 15) return;
            Fx.ring(c, radius, (int) (radius * 7) + 10, p -> {
                Location g = Fx.ground(p, 4);
                Compat.spawn(world(), Compat.LARGE_SMOKE, g.clone().add(0, 0.4, 0), 1, 0, 0.4, 0, 0,
                        Compat.dust(PITCH, 1.9f));
                Compat.spawn(world(), Compat.PORTAL, g.clone().add(0, 0.4, 0), 1, 0.1, 0.2, 0.1, 0.02);
            });
            for (Player p : targets(radius + 1.2)) {
                if (p.getLocation().distance(c) < radius - 1.5) continue;
                if (!struck.add(p.getUniqueId())) continue;
                hit(p, 16 * damageBonus);
                Compat.apply(p, "darkness", 140, 0);
                push(p, p.getLocation().toVector().subtract(c.toVector()).normalize().setY(0.4).multiply(0.9));
            }
        }, null);
    }

    /** 8. Agarre Sombrio: una mano de vacio que arrastra al mas lejano. */
    public void shadowGrasp() {
        if (!alive()) return;
        // Dos manos de vacio, una por cada jugador que intenta poner tierra de por medio.
        boolean any = false;
        for (Player target : farthestTargets(2)) {
            if (!any) soundAt(loc(), "entity.enderman.stare", 1.4f, 0.4f);
            any = true;
            graspOn(target);
        }
    }

    /** Una mano: el hilo de vacio, el arrastre y el apreton del final. */
    private void graspOn(Player target) {
        target.sendActionBar(Component.text("Algo te tiene sujeto.", NamedTextColor.RED, TextDecoration.BOLD));

        animate(70, tick -> {
            if (!alive() || !Fx.isFightable(target)) throw Stop.now();
            Location from = boss.getLocation().add(0, 1.8, 0);
            Location to = target.getLocation().add(0, 1, 0);
            Fx.beam(from, to, 0.4, p -> {
                Compat.spawn(world(), Compat.SCULK_CHARGE_POP, p, 1, 0, 0, 0, 0, Compat.dust(PITCH, 1.5f));
                Compat.spawn(world(), Compat.SCULK_SOUL, p, 1, 0.05, 0.05, 0.05, 0.02);
            });
            if (tick < 20) return;
            if (tick % 4 == 0) {
                push(target, from.toVector().subtract(to.toVector()).normalize().multiply(0.85).setY(0.2));
            }
            if (tick == 64) {
                hit(target, 15 * damageBonus);
                Compat.apply(target, "darkness", 120, 0);
                soundAt(to, "entity.enderman.hurt", 1.4f, 0.5f);
            }
        }, null);
    }

    /** 9. Lluvia del Vacio: motas negras que caen sobre marcas. */
    public void voidRain() {
        if (!alive()) return;
        soundAt(loc(), "ambient.cave", 1.6f, 0.5f);
        broadcastNear(Component.text("Cae el vacio.", ACCENT));

        animate(150, tick -> {
            if (tick % 18 != 0) return;
            for (Player p : targets()) {
                Location mark = Fx.ground(p.getLocation(), 5);
                Fx.telegraph(world(), mark, 2.6, VOID_PURPLE);
                later(20, () -> {
                    for (double h = 12; h > 0; h -= 1.0) {
                        Compat.spawn(world(), Compat.SMOKE, mark.clone().add(0, h, 0), 2, 0.2, 0.2, 0.2, 0,
                                Compat.dust(PITCH, 1.7f));
                    }
                    Compat.spawn(world(), Compat.EXPLOSION, mark.clone().add(0, 0.5, 0), 2, 0.4, 0.3, 0.4, 0);
                    soundAt(mark, "entity.enderman.teleport", 1.2f, 0.4f);
                    for (Player v : Fx.playersNear(mark, 3.0)) {
                        hit(v, 13 * damageBonus);
                        Compat.apply(v, "darkness", 100, 0);
                    }
                });
            }
        }, null);
    }

    /** 10. Eco del Vacio: sus posiciones pasadas estallan una detras de otra. */
    public void voidEcho() {
        if (!alive()) return;
        List<Location> trail = new ArrayList<>();
        soundAt(loc(), "entity.enderman.teleport", 1.2f, 0.4f);
        broadcastNear(Component.text("Deja rastro.", ACCENT));

        animate(140, tick -> {
            if (!alive()) return;
            if (tick % 10 == 0 && trail.size() < 8) {
                Location l = boss.getLocation().clone();
                trail.add(l);
                Compat.spawn(world(), Compat.REVERSE_PORTAL, l.clone().add(0, 1.5, 0), 20, 0.4, 0.8, 0.4, 0.05);
            }
            for (Location l : trail) {
                Compat.spawn(world(), Compat.SCULK_SOUL, l.clone().add(0, 1.5, 0), 1, 0.4, 0.8, 0.4, 0,
                        Compat.dust(VOID_PURPLE, 1.2f));
            }
            if (tick != 100) return;
            int i = 0;
            for (Location l : trail) {
                later(i++ * 6, () -> {
                    Compat.spawn(world(), Compat.EXPLOSION, l.clone().add(0, 1, 0), 2, 0.4, 0.4, 0.4, 0);
                    Compat.spawn(world(), Compat.SQUID_INK, l.clone().add(0, 1, 0), 30, 0.6, 0.8, 0.6, 0,
                            Compat.dust(MAGENTA, 1.7f));
                    soundAt(l, "entity.enderman.teleport", 1.1f, 0.7f);
                    for (Player p : Fx.playersNear(l, 3.2)) {
                        hit(p, 12 * damageBonus);
                        Compat.apply(p, "darkness", 80, 0);
                    }
                });
            }
        }, null);
    }

    /** 11. Ceguera Total: a todo el mundo, sin excepcion y sin sitio donde esconderse. */
    public void totalBlindness() {
        if (!alive()) return;
        Location c = loc();
        soundAt(c, "entity.enderman.scream", 2.0f, 0.3f);
        titleNear(Component.text("CEGUERA TOTAL", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD),
                Component.text("Pelea de oido", NamedTextColor.GRAY));

        animate(100, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 2, 0);
            Fx.sphere(l, 2 + tick * 0.14, 40, p ->
                    Compat.spawn(world(), Compat.SCULK_CHARGE_POP, p, 1, 0, 0, 0, 0, Compat.dust(PITCH, 2.0f)));
            if (tick % 20 != 0) return;
            for (Player p : Fx.viewersNear(c, plugin.settings().participationRadius())) {
                Compat.apply(p, "darkness", 220, 0);
                Compat.apply(p, "blindness", 120, 0);
            }
            for (Player p : targets()) hit(p, 6 * damageBonus);
        }, null);
    }

    /** 12. Desgarro: un zarpazo enorme en arco. */
    public void rend() {
        if (!alive()) return;
        Vector facing = boss.getLocation().getDirection().setY(0).normalize();
        soundAt(loc(), "entity.enderman.hurt", 1.5f, 0.5f);

        animate(45, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1.6, 0);
            double radius = 2 + tick * 0.2;
            Fx.arc(l, facing, radius, Math.toRadians(130), (int) (radius * 5), p -> {
                Compat.spawn(world(), Compat.SWEEP_ATTACK, p, 1);
                Compat.spawn(world(), Compat.PORTAL, p, 1, 0, 0, 0, 0, Compat.dust(MAGENTA, 1.5f));
            });
            if (tick != 24) return;
            soundAt(l, "entity.player.attack.sweep", 1.6f, 0.4f);
            for (Player p : targets(8.0)) {
                Vector to = p.getLocation().toVector().subtract(l.toVector()).setY(0);
                if (to.lengthSquared() > 0.01 && to.normalize().dot(facing) < 0.1) continue;
                hit(p, 21 * damageBonus);
                Compat.apply(p, "darkness", 90, 0);
                push(p, to.normalize().setY(0.4).multiply(1.0));
            }
        }, null);
    }

    /** 13. Colapso: un agujero negro que se lo traga todo y luego revienta. */
    public void collapse() {
        if (!alive()) return;
        Location c = boss.getLocation().add(0, 3, 0);
        titleNear(Component.text("COLAPSO", NamedTextColor.WHITE, TextDecoration.BOLD),
                Component.text("Un agujero negro. Corre.", NamedTextColor.GRAY));
        soundAt(c, "entity.wither.spawn", 1.8f, 0.3f);

        animate(200, tick -> {
            if (!alive()) return;
            Location hole = boss.getLocation().add(0, 3, 0);

            if (tick < 150) {
                // El disco negro y su corona de luz: la esfera se ve maciza a proposito.
                double r = 1.0 + (tick / 150.0) * 2.5;
                Fx.sphere(hole, r, (int) (40 + r * 18), p ->
                        Compat.spawn(world(), Compat.SCULK_CHARGE_POP, p, 1, 0, 0, 0, 0, Compat.dust(PITCH, 2.4f)));
                Fx.ring(hole, r + 1.2, 30, tick * 0.25, p ->
                        Compat.spawn(world(), Compat.SMOKE, p, 1, 0, 0, 0, 0, Compat.dust(MAGENTA, 1.8f)));
                for (Player p : Fx.playersNear(hole, 22)) {
                    Vector pull = hole.toVector().subtract(p.getLocation().toVector());
                    if (pull.lengthSquared() < 4) continue;
                    push(p, pull.normalize().multiply(0.38));
                    if (tick % 25 == 0) {
                        hit(p, 8 * damageBonus);
                        Compat.apply(p, "darkness", 100, 0);
                    }
                }
                if (tick % 10 == 0) soundAt(hole, "block.beacon.deactivate", 1.2f, 0.3f);
                return;
            }
            if (tick != 150) return;

            Compat.spawn(world(), Compat.FLASH, hole, 1);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, hole, 4);
            Compat.spawn(world(), Compat.SCULK_SOUL, hole, 260, 5.0, 5.0, 5.0, 0, Compat.dust(PITCH, 2.6f));
            soundAt(hole, "entity.generic.explode", 2.0f, 0.3f);
            soundAt(hole, "entity.wither.death", 1.6f, 0.4f);
            for (Player p : Fx.playersNear(hole, 16)) {
                double d = p.getLocation().distance(hole);
                hit(p, Math.max(12, 38 - d * 1.6) * damageBonus);
                push(p, p.getLocation().toVector().subtract(hole.toVector()).normalize().setY(0.7).multiply(2.0));
                Compat.apply(p, "darkness", 160, 0);
                Compat.apply(p, "slow_falling", 80, 0);
            }
        }, null);
    }

    /** 14. Fisura: el suelo se abre en grietas que escupen oscuridad. */
    public void fissure() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        Location origin = Fx.ground(boss.getLocation(), 6);
        Vector dir = target.getLocation().toVector().subtract(origin.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        final Vector run = dir.normalize();
        Set<UUID> split = new HashSet<>();

        soundAt(origin, "entity.enderman.stare", 1.5f, 0.4f);
        animate(70, tick -> {
            if (tick < 22) {
                for (double d = 2; d < 18; d += 1.0) {
                    Location g = Fx.ground(origin.clone().add(run.clone().multiply(d)), 4);
                    Compat.spawn(world(), Compat.SQUID_INK, g.clone().add(0, 0.15, 0), 1, 0.5, 0, 0.5, 0,
                            Compat.dust(VOID_PURPLE, 1.4f));
                }
                return;
            }
            double reach = (tick - 22) * 1.0;
            if (reach > 18) return;
            Location g = Fx.ground(origin.clone().add(run.clone().multiply(reach)), 4);
            for (double h = 0; h < 4; h += 0.5) {
                Compat.spawn(world(), Compat.REVERSE_PORTAL, g.clone().add(0, h, 0), 2, 0.4, 0.1, 0.4, 0,
                        Compat.dust(PITCH, 1.9f));
            }
            Compat.spawn(world(), Compat.SMOKE, g.clone().add(0, 1, 0), 8, 0.4, 0.6, 0.4, 0.05);
            if (tick == 23) soundAt(origin, "block.deepslate.break", 1.6f, 0.4f);
            for (Player p : Fx.playersNear(g, 2.4)) {
                if (!split.add(p.getUniqueId())) continue;
                hit(p, 18 * damageBonus);
                Compat.apply(p, "darkness", 120, 0);
                push(p, new Vector(0, 0.6, 0));
            }
        }, null);
    }

    /** 15. Singularidad: se encoge en un punto y vuelve a salir con todo. */
    public void singularity() {
        if (!alive()) return;
        Location c = boss.getLocation();
        soundAt(c, "entity.enderman.scream", 2.0f, 0.3f);
        titleNear(Component.text("SINGULARIDAD", NamedTextColor.WHITE, TextDecoration.BOLD),
                Component.text("Todo a la vez", NamedTextColor.GRAY));

        animate(120, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 2.5, 0);
            if (tick < 60) {
                double r = 8 - tick * 0.12;
                Fx.sphere(l, Math.max(0.5, r), 44, p ->
                        Compat.spawn(world(), Compat.PORTAL, p, 1, 0, 0, 0, 0, Compat.dust(MAGENTA, 1.8f)));
                for (Player p : Fx.playersNear(l, 18)) {
                    Vector pull = l.toVector().subtract(p.getLocation().toVector());
                    if (pull.lengthSquared() < 4) continue;
                    push(p, pull.normalize().multiply(0.3));
                }
                return;
            }
            if (tick != 60) return;
            Compat.spawn(world(), Compat.FLASH, l, 1);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l, 3);
            soundAt(l, "entity.generic.explode", 2.0f, 0.4f);
            for (int ring = 1; ring <= 4; ring++) {
                final int r = ring;
                later(ring * 5, () -> {
                    Fx.ring(Fx.ground(boss.getLocation(), 5), r * 4.0, r * 16, p ->
                            Compat.spawn(world(), Compat.SCULK_CHARGE_POP, Fx.ground(p, 3).add(0, 0.4, 0), 1, 0, 0, 0, 0,
                                    Compat.dust(PITCH, 2.0f)));
                    for (Player p : targets(r * 4.0 + 1.5)) {
                        if (p.getLocation().distance(loc()) < (r - 1) * 4.0) continue;
                        hit(p, (24 - r * 2) * damageBonus);
                        Compat.apply(p, "darkness", 140, 0);
                        push(p, p.getLocation().toVector().subtract(loc().toVector())
                                .normalize().setY(0.5).multiply(1.3));
                    }
                });
            }
        }, null);
    }

    // ------------------------------------------------------------------- limpieza

    @Override
    public void cleanup() {
        clearDecoys();
        super.cleanup();
    }

    // ------------------------------------------------------------------ mensajeria

    private void broadcastNear(Component message) {
        Component line = Component.text("✦ ", ACCENT)
                .append(Component.text("Darkness  ", ACCENT, TextDecoration.BOLD))
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
