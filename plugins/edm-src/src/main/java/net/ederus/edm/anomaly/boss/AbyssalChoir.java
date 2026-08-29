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
import org.bukkit.entity.Guardian;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * EL CORO ABISAL, la sexta anomalia.
 *
 * Tres cantores orbitan un nucleo intocable, unidos a el por haces de luz. El nucleo
 * NO se puede tocar mientras el coro siga entero: hay que apagar a los cantores en el
 * ORDEN que marcan sus luces, y solo entonces se abre una ventana para castigar al
 * nucleo. Matar uno fuera de orden lo revive todo y cuesta caro.
 *
 * Es el jefe mas distinto de los seis: no se gana pegando mas fuerte, se gana
 * coordinandose y mirando de que color esta cada cantor. Premia hablar por voz.
 */
public final class AbyssalChoir extends BossFight {

    public static final String ID = "coro_abisal";
    public static final TextColor ACCENT = TextColor.color(0xB69BE3);

    private static final int VIOLET = 0x9A73D6;
    private static final int LIGHT = 0xE6DCFF;
    private static final int DEEPV = 0x3A2A5C;

    /** Radio dentro del cual el coro te deja respirar. */
    private static final double ARENA = 20;
    private static final int SINGERS = 3;

    /** Colores del orden: el primero de la secuencia es blanco, luego amarillo, luego rojo. */
    private static final NamedTextColor[] ORDER_COLORS = {
            NamedTextColor.WHITE, NamedTextColor.YELLOW, NamedTextColor.RED};

    /** Como se NOMBRA cada turno: por su color, nunca por un numero. */
    private static final String[] ORDER_NAMES = {"BLANCO", "AMARILLO", "ROJO"};

    private final List<Guardian> singers = new ArrayList<>();
    /** Los cantores en el orden en que hay que apagarlos. */
    private final List<UUID> order = new ArrayList<>();
    private int nextInOrder;
    private long openUntil;
    private int roundsWon;
    private double damageBonus = 1.0;

    public AbyssalChoir(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        super(plugin, event, where);
        abilities.addAll(plugin.registry().choirAbilities());
    }

    @Override
    public String bossName() {
        return "Coro Abisal";
    }

    // ------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        Location spot = arena.clone();

        boss = world().spawn(spot, Guardian.class, g -> {
            g.setPersistent(true);
            g.setRemoveWhenFarAway(false);
            g.setAI(false); // el nucleo no persigue: flota y canta
            g.setGravity(false);
            g.customName(Component.text("✦ ", ACCENT)
                    .append(Component.text("Nucleo del Coro", ACCENT, TextDecoration.BOLD)));
            g.setCustomNameVisible(true);
        });

        Compat.setAttribute(boss, "armor", 10);
        Compat.setAttribute(boss, "knockback_resistance", 1.0);
        Compat.setAttribute(boss, "follow_range", 64);
        Compat.setAttribute(boss, "scale", 3.0);
        applyHealth(plugin.registry().scaledHealth(plugin.registry().choir(), targets(96).size()));
        boss.setMaximumNoDamageTicks(4);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        Glow.apply(boss, event.type().glowColor());

        arrivalAnimation(spot);
    }

    private void arrivalAnimation(Location spot) {
        boss.setInvulnerable(true);
        busyFor(90);
        soundAt(spot, "block.conduit.activate", 1.6f, 0.4f);
        soundAt(spot, "block.amethyst_block.chime", 1.4f, 0.5f);

        animate(90, tick -> {
            double t = tick / 90.0;
            Fx.sphere(spot, 10 - t * 7, 44, p ->
                    Compat.spawn(world(), Compat.NAUTILUS, p, 1, 0, 0, 0, 0, Compat.dust(VIOLET, 1.6f)));
            Fx.helix(spot.clone().subtract(0, 3, 0), 4.0, 8.0, 30, 3.0, p ->
                    Compat.spawn(world(), Compat.GLOW, p, 1, 0, 0, 0, 0, Compat.dust(LIGHT, 1.2f)));
            if (tick % 18 == 0) soundAt(spot, "block.amethyst_block.chime", 1.3f, 0.5f + (float) t);
        }, () -> {
            if (!alive()) return;
            summonChoir();
            for (Player p : Fx.viewersNear(spot, 90)) {
                p.showTitle(Title.title(
                        Component.text("✦ ANOMALIA ✦", ACCENT, TextDecoration.BOLD),
                        Component.text("Apaga el coro en el orden de sus luces", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2200), Duration.ofMillis(700))));
            }
        });
    }

    // ---------------------------------------------------------------------- el coro

    /** Levanta los tres cantores y sortea el orden en que hay que apagarlos. */
    private void summonChoir() {
        if (!alive()) return;
        clearSingers();
        Location c = boss.getLocation();

        for (int i = 0; i < SINGERS; i++) {
            double a = Math.PI * 2 * i / SINGERS;
            Location sl = c.clone().add(Math.cos(a) * 6, 0.5, Math.sin(a) * 6);
            Guardian g = world().spawn(sl, Guardian.class, e -> {
                e.setPersistent(false);
                e.setGravity(false);
                Compat.setAttribute(e, "max_health", 60);
                Compat.setAttribute(e, "attack_damage", 5);
                Compat.setAttribute(e, "scale", 1.5);
                e.setHealth(60);
            });
            markMinion(g);
            singers.add(g);
        }

        order.clear();
        for (Guardian g : singers) order.add(g.getUniqueId());
        Collections.shuffle(order);
        nextInOrder = 0;
        boss.setInvulnerable(true);
        openUntil = 0;
        paintOrder();

        soundAt(c, "block.amethyst_block.chime", 1.6f, 0.7f);
        broadcastNear(Component.text("El coro vuelve a cantar. Miren las luces.", ACCENT));
    }

    /**
     * Pinta cada cantor del color de su turno: blanco el primero, amarillo el segundo,
     * rojo el tercero. Es toda la interfaz que tiene el puzzle, asi que tiene que
     * leerse de un vistazo desde el otro lado de la arena.
     */
    private void paintOrder() {
        for (int i = 0; i < order.size(); i++) {
            Guardian g = singerOf(order.get(i));
            if (g == null) continue;
            Glow.clear(g);
            Glow.apply(g, ORDER_COLORS[Math.min(i, ORDER_COLORS.length - 1)]);
            g.customName(Component.text("Cantor", ORDER_COLORS[Math.min(i, ORDER_COLORS.length - 1)],
                    TextDecoration.BOLD));
            g.setCustomNameVisible(true);
        }
    }

    private Guardian singerOf(UUID id) {
        for (Guardian g : singers) {
            if (g != null && g.isValid() && g.getUniqueId().equals(id)) return g;
        }
        return null;
    }

    private void clearSingers() {
        for (Guardian g : singers) {
            if (g == null) continue;
            Glow.clear(g);
            spawned.remove(g);
            Fx.safeRemove(g);
        }
        singers.clear();
    }

    /**
     * El corazon del puzzle. Si cae el cantor que tocaba, se avanza; si cae otro, el
     * coro se rehace entero y el nucleo cobra la equivocacion.
     */
    @Override
    public void onMinionDeath(LivingEntity minion) {
        if (!alive() || order.isEmpty()) return;
        UUID id = minion.getUniqueId();
        if (!order.contains(id)) return;

        boolean correct = nextInOrder < order.size() && order.get(nextInOrder).equals(id);
        Location l = minion.getLocation();

        if (!correct) {
            wrongNote(l);
            return;
        }

        nextInOrder++;
        Compat.spawn(world(), Compat.BUBBLE_POP, l.clone().add(0, 1, 0), 40, 0.6, 0.6, 0.6, 0,
                Compat.dust(LIGHT, 1.6f));
        soundAt(l, "block.amethyst_block.chime", 1.5f, 0.8f + nextInOrder * 0.3f);
        broadcastNear(Component.text("Cantor " + ORDER_NAMES[Math.min(nextInOrder - 1, 2)]
                + " apagado.", NamedTextColor.GREEN));

        if (nextInOrder >= order.size()) openCore();
    }

    /** Se equivocaron de cantor: vuelve el coro entero y duele. */
    private void wrongNote(Location where) {
        Location c = loc();
        soundAt(c, "block.conduit.deactivate", 1.8f, 0.4f);
        soundAt(c, "entity.elder_guardian.curse", 1.4f, 0.5f);
        titleNear(Component.text("NOTA FALSA", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Ese no tocaba. El coro se rehace.", NamedTextColor.GRAY));

        for (Player p : Fx.playersNear(c, ARENA)) {
            hit(p, 12 * damageBonus);
            Compat.apply(p, "slowness", 80, 1);
            Compat.spawn(world(), Compat.END_ROD, p.getLocation().add(0, 1.2, 0), 20, 0.4, 0.5, 0.4, 0,
                    Compat.dust(DEEPV, 1.6f));
        }
        Compat.spawn(world(), Compat.EXPLOSION_EMITTER, where, 1);

        // Se rehace tras un respiro, para que se vea el castigo antes de volver a empezar.
        later(30, this::summonChoir);
    }

    /** Cayo el coro en orden: el nucleo queda expuesto unos segundos. */
    private void openCore() {
        if (!alive()) return;
        roundsWon++;
        int seconds = Math.max(6, 12 - roundsWon);
        openUntil = ticks() + seconds * 20L;
        boss.setInvulnerable(false);

        Location c = boss.getLocation();
        Compat.spawn(world(), Compat.FLASH, c.clone().add(0, 1.5, 0), 1);
        Compat.spawn(world(), Compat.EXPLOSION_EMITTER, c, 1);
        soundAt(c, "block.conduit.activate", 1.8f, 1.2f);
        titleNear(Component.text("EL CORO CALLA", NamedTextColor.GREEN, TextDecoration.BOLD),
                Component.text("El nucleo esta abierto: " + seconds + " segundos", NamedTextColor.GRAY));
    }

    // -------------------------------------------------------------------- ambiente

    @Override
    protected void ambient() {
        if (!alive()) return;

        // El coro te deja respirar mientras estes dentro, igual que el Leviatan.
        if (ticks() % 20 == 0) {
            for (Player p : Fx.viewersNear(loc(), ARENA)) {
                Compat.apply(p, "conduit_power", 90, 0);
            }
        }

        singers.removeIf(g -> g == null || !g.isValid() || g.isDead());

        // Se cierra la ventana: vuelve el coro.
        if (openUntil > 0 && ticks() >= openUntil) {
            openUntil = 0;
            soundAt(loc(), "block.conduit.deactivate", 1.4f, 0.7f);
            broadcastNear(Component.text("El nucleo se cierra.", ACCENT));
            summonChoir();
        }

        Location c = boss.getLocation();
        boolean open = openUntil > 0;

        // Los haces que unen cada cantor con el nucleo. Son la pista visual de que el
        // nucleo esta protegido: mientras se vean, no se le puede hacer nada.
        if (ticks() % 2 == 0) {
            for (Guardian g : singers) {
                if (g == null || !g.isValid()) continue;
                Fx.beam(g.getLocation().add(0, 0.8, 0), c.clone().add(0, 1.4, 0), 0.5, p ->
                        Compat.spawn(world(), Compat.DOLPHIN, p, 1, 0, 0, 0, 0, Compat.dust(LIGHT, 1.1f)));
            }
        }

        // Los cantores orbitan de verdad, no se quedan quietos.
        if (!singers.isEmpty()) {
            double a = ticks() * 0.02;
            for (int i = 0; i < singers.size(); i++) {
                Guardian g = singers.get(i);
                if (g == null || !g.isValid()) continue;
                double angle = a + Math.PI * 2 * i / SINGERS;
                Location want = c.clone().add(Math.cos(angle) * 6, 0.5 + Math.sin(a * 2 + i) * 0.8,
                        Math.sin(angle) * 6);
                Vector move = want.toVector().subtract(g.getLocation().toVector());
                if (move.lengthSquared() > 0.04) g.setVelocity(move.multiply(0.25));
            }
        }

        if (ticks() % 4 == 0) {
            Fx.sphere(c.clone().add(0, 1.2, 0), open ? 2.2 : 1.6, 20, p ->
                    Compat.spawn(world(), Compat.ENCHANT, p, 1, 0, 0, 0, 0,
                            Compat.dust(open ? LIGHT : VIOLET, open ? 1.8f : 1.2f)));
        }
        if (ticks() % 20 == 0) {
            warn(open
                    ? Component.text("NUCLEO ABIERTO  ", NamedTextColor.GREEN, TextDecoration.BOLD)
                            .append(Component.text(((openUntil - ticks()) / 20) + "s", NamedTextColor.WHITE))
                    : Component.text("Nucleo protegido  ", NamedTextColor.GRAY)
                            .append(Component.text("apaga el Cantor " + ORDER_NAMES[Math.min(nextInOrder, 2)],
                                    ORDER_COLORS[Math.min(nextInOrder, 2)], TextDecoration.BOLD)));
        }
    }

    /** Mientras el coro cante, el nucleo es intocable a proposito. */
    @Override
    protected boolean allowLongInvulnerability() {
        return true;
    }

    @Override
    public double incomingDamageMultiplier() {
        return openUntil > 0 ? 1.0 : 0.0;
    }

    // --------------------------------------------------------------- cambio de fase

    @Override
    protected void onPhaseChange(int from, int to) {
        if (event.bars() != null) event.bars().flash(from);
        Location c = loc();
        soundAt(c, "block.conduit.activate", 1.6f, 0.5f);
        Compat.spawn(world(), Compat.EXPLOSION_EMITTER, c, 1);

        if (to == 2) {
            damageBonus = 1.2;
            titleNear(Component.text("FASE II", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("Los cantores tambien atacan", NamedTextColor.GRAY));
        }
        if (to == 3) {
            damageBonus = 1.45;
            titleNear(Component.text("FASE III", NamedTextColor.RED, TextDecoration.BOLD),
                    Component.text("El coro entero canta a la vez", NamedTextColor.GRAY));
        }
        // Cada cambio de fase rehace el coro: se acabo la ventana que hubiera.
        openUntil = 0;
        later(20, this::summonChoir);
    }

    // ---------------------------------------------------------------------- muerte

    @Override
    public void onDeath() {
        Location l = loc();
        soundAt(l, "block.conduit.deactivate", 1.8f, 0.4f);
        soundAt(l, "block.amethyst_block.break", 1.6f, 0.5f);

        int delay = 0;
        for (Guardian g : new ArrayList<>(singers)) {
            later(delay += 6, () -> {
                if (g == null || !g.isValid()) return;
                Location gl = g.getLocation();
                Compat.spawn(world(), Compat.FLASH, gl, 1);
                Compat.spawn(world(), Compat.NAUTILUS, gl, 30, 0.5, 0.5, 0.5, 0, Compat.dust(LIGHT, 1.6f));
                Compat.sound(world(), gl, "block.amethyst_block.chime", 1.2f, 1.4f);
                Glow.clear(g);
                spawned.remove(g);
                Fx.safeRemove(g);
            });
        }
        singers.clear();

        animate(90, tick -> {
            double t = tick / 90.0;
            Fx.helix(l.clone().subtract(0, 2, 0), 3.0 * (1 - t) + 0.5, 6.0, 24, 3.0, p ->
                    Compat.spawn(world(), Compat.GLOW, p, 1, 0, 0, 0, 0, Compat.dust(LIGHT, 1.5f)));
            if (tick % 10 == 0) {
                Compat.spawn(world(), Compat.ITEM, l.clone().add(0, 1, 0), 20, 1.0, 1.0, 1.0, 0.15,
                        new org.bukkit.inventory.ItemStack(Material.AMETHYST_SHARD));
                soundAt(l, "block.amethyst_block.chime", 1.0f, 0.4f + (float) t);
            }
        }, () -> {
            Compat.spawn(world(), Compat.FLASH, l.clone().add(0, 1, 0), 1);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l, 2);
            soundAt(l, "block.conduit.deactivate", 1.8f, 0.6f);
            for (Player p : Fx.viewersNear(l, ARENA)) {
                p.sendActionBar(Component.text("El canto se apaga. Sube.", ACCENT, TextDecoration.BOLD));
            }
        });
    }

    // ============================================================== HABILIDADES ==

    /** 1. Orden del Coro: rehace la secuencia sin rehacer los cantores. */
    public void reorder() {
        if (!alive() || singers.isEmpty() || openUntil > 0) return;
        List<UUID> alive = new ArrayList<>();
        for (Guardian g : singers) {
            if (g != null && g.isValid()) alive.add(g.getUniqueId());
        }
        if (alive.size() < 2) return;
        Collections.shuffle(alive);
        order.clear();
        order.addAll(alive);
        nextInOrder = 0;
        paintOrder();
        soundAt(loc(), "block.amethyst_block.chime", 1.6f, 1.4f);
        broadcastNear(Component.text("Cambia el orden. Vuelvan a mirar.", ACCENT));
    }

    /** 2. Haz del Nucleo: un rayo largo desde el centro. */
    public void coreBeam() {
        if (!alive()) return;
        // El haz del nucleo se parte en dos horquillas, cada una a un jugador.
        List<Player> marks = pickTargets(2);
        if (marks.isEmpty()) return;
        soundAt(loc(), "block.conduit.attack_target", 1.5f, 0.6f);
        for (Player target : marks) {
            beamOn(target);
        }
    }

    /** Una horquilla del haz: la carga y la descarga sobre su objetivo. */
    private void beamOn(Player target) {
        animate(55, tick -> {
            if (!alive() || !Fx.isFightable(target)) throw Stop.now();
            Location from = boss.getLocation().add(0, 1.4, 0);
            Location to = target.getLocation().add(0, 1, 0);
            if (tick < 36) {
                Fx.beam(from, to, 0.6, p -> Compat.spawn(world(), Compat.BUBBLE_POP, p, 1, 0, 0, 0, 0,
                        Compat.dust(tick < 26 ? VIOLET : LIGHT, 1.0f)));
                return;
            }
            if (tick != 36) return;
            Fx.beam(from, to, 0.25, p -> {
                Compat.spawn(world(), Compat.END_ROD, p, 2, 0.1, 0.1, 0.1, 0, Compat.dust(LIGHT, 1.7f));
                Compat.spawn(world(), Compat.END_ROD, p, 1, 0.02, 0.02, 0.02, 0.01);
            });
            hit(target, 16 * damageBonus);
            soundAt(to, "block.conduit.attack_target", 1.6f, 1.0f);
        }, null);
    }

    /** 3. Pulso Armonico: un anillo de luz que se expande desde el nucleo. */
    public void harmonicPulse() {
        if (!alive()) return;
        Location c = boss.getLocation();
        java.util.Set<UUID> hitOnce = new java.util.HashSet<>();
        soundAt(c, "block.amethyst_block.resonate", 1.6f, 0.6f);

        animate(60, tick -> {
            double radius = tick * 0.35;
            if (radius > 16) return;
            Fx.sphere(c, radius, (int) (radius * 8) + 12, p ->
                    Compat.spawn(world(), Compat.DOLPHIN, p, 1, 0, 0, 0, 0, Compat.dust(LIGHT, 1.3f)));
            if (tick % 8 == 0) soundAt(c, "block.amethyst_block.chime", 1.0f, 0.6f + tick / 60f);
            for (Player p : Fx.playersNear(c, radius + 1.2)) {
                if (p.getLocation().distance(c) < radius - 1.6) continue;
                if (!hitOnce.add(p.getUniqueId())) continue;
                hit(p, 11 * damageBonus);
                push(p, p.getLocation().toVector().subtract(c.toVector()).normalize().setY(0.3).multiply(0.7));
            }
        }, null);
    }

    /** 4. Marea Baja: arrastra a todos hacia el coro. */
    public void undertow() {
        if (!alive()) return;
        Location c = loc();
        soundAt(c, "block.bubble_column.whirlpool_ambient", 1.4f, 0.6f);
        broadcastNear(Component.text("Los arrastra hacia el centro.", ACCENT));

        animate(100, tick -> {
            Fx.ring(c, 12 - (tick % 40) * 0.2, 30, tick * 0.2, p ->
                    Compat.spawn(world(), Compat.BUBBLE, p, 1, 0.05, 0.05, 0.05, 0.01));
            if (tick % 4 != 0) return;
            for (Player p : Fx.playersNear(c, 16)) {
                Vector pull = c.toVector().subtract(p.getLocation().toVector());
                if (pull.lengthSquared() < 4) continue;
                push(p, pull.normalize().multiply(0.3));
            }
            if (tick % 25 == 0) {
                for (Player p : Fx.playersNear(c, 4)) hit(p, 7 * damageBonus);
            }
        }, null);
    }

    /** 5. Contracanto: cada cantor dispara su propio haz. */
    public void counterSong() {
        if (!alive() || singers.isEmpty()) return;
        soundAt(loc(), "block.amethyst_block.chime", 1.5f, 1.1f);
        broadcastNear(Component.text("Los cantores responden.", ACCENT));

        for (int i = 0; i < singers.size(); i++) {
            Guardian g = singers.get(i);
            if (g == null || !g.isValid()) continue;
            Player victim = randomTarget();
            if (victim == null) continue;
            later(i * 12, () -> {
                if (!g.isValid() || !Fx.isFightable(victim)) return;
                animate(40, tick -> {
                    if (!g.isValid() || !Fx.isFightable(victim)) throw Stop.now();
                    Location from = g.getLocation().add(0, 0.8, 0);
                    Location to = victim.getLocation().add(0, 1, 0);
                    if (tick < 26) {
                        Fx.beam(from, to, 0.7, p -> Compat.spawn(world(), Compat.ENCHANT, p, 1, 0, 0, 0, 0,
                                Compat.dust(VIOLET, 0.9f)));
                        return;
                    }
                    if (tick != 26) return;
                    Fx.beam(from, to, 0.3, p ->
                            Compat.spawn(world(), Compat.NAUTILUS, p, 2, 0.08, 0.08, 0.08, 0,
                                    Compat.dust(LIGHT, 1.5f)));
                    hit(victim, 9 * damageBonus);
                    soundAt(to, "entity.guardian.attack", 1.2f, 1.2f);
                }, null);
            });
        }
    }

    /** 6. Disonancia: una nota que ciega y entumece. */
    public void dissonance() {
        if (!alive()) return;
        Location c = loc();
        soundAt(c, "block.amethyst_block.resonate", 1.7f, 0.4f);
        broadcastNear(Component.text("Desafina a proposito.", ACCENT));

        animate(70, tick -> {
            Fx.sphere(c, 3 + tick * 0.12, 30, p ->
                    Compat.spawn(world(), Compat.GLOW, p, 1, 0.2, 0.2, 0.2, 0, Compat.dust(DEEPV, 1.7f)));
            if (tick % 14 == 0) soundAt(c, "block.amethyst_block.hit", 1.2f, 0.4f);
            if (tick != 50) return;
            for (Player p : Fx.playersNear(c, 14)) {
                hit(p, 8 * damageBonus);
                Compat.apply(p, "blindness", 70, 0);
                Compat.apply(p, "mining_fatigue", 200, 1);
                Compat.apply(p, "nausea", 100, 0);
                soundAt(p.getLocation(), "block.amethyst_block.break", 1.0f, 0.5f);
            }
        }, null);
    }

    /** 7. Enjambre Abisal: peces de luz que hostigan sin formar parte del coro. */
    public void abyssalSwarm() {
        if (!alive()) return;
        int count = 3 + random.nextInt(3);
        Location c = boss.getLocation();
        soundAt(c, "entity.guardian.ambient", 1.4f, 1.3f);

        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count;
            Location sl = c.clone().add(Math.cos(a) * 9, 1 + random.nextDouble() * 2, Math.sin(a) * 9);
            later(i * 7, () -> {
                if (!alive()) return;
                Compat.spawn(world(), Compat.BUBBLE_POP, sl, 20, 0.4, 0.4, 0.4, 0, Compat.dust(VIOLET, 1.3f));
                Guardian g = world().spawn(sl, Guardian.class, e -> {
                    e.setPersistent(false);
                    Compat.setAttribute(e, "max_health", 22);
                    Compat.setAttribute(e, "attack_damage", 5);
                    Compat.setAttribute(e, "scale", 0.8);
                    e.setHealth(22);
                });
                g.customName(Component.text("Eco", TextColor.color(0xC7B4E8)));
                // A proposito NO entra en la lista del coro: matarlo no cuenta ni penaliza.
                markMinion(g);
                soundAt(sl, "entity.guardian.ambient", 1.0f, 1.5f);
            });
        }
    }

    /** 8. Crescendo: la nota sube y revienta en un anillo enorme. */
    public void crescendo() {
        if (!alive()) return;
        Location c = loc();
        titleNear(Component.text("CRESCENDO", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Salgan del alcance", NamedTextColor.GRAY));

        animate(110, tick -> {
            if (tick < 70) {
                double r = 2 + tick * 0.05;
                Fx.sphere(c, r, 26, p -> Compat.spawn(world(), Compat.END_ROD, p, 1, 0, 0, 0, 0,
                        Compat.dust(LIGHT, 1.0f + tick / 70f)));
                if (tick % 8 == 0) soundAt(c, "block.amethyst_block.chime", 1.2f, 0.4f + tick / 60f);
                return;
            }
            if (tick != 70) return;
            Compat.spawn(world(), Compat.FLASH, c.clone().add(0, 1, 0), 1);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, c, 2);
            soundAt(c, "block.amethyst_block.break", 1.8f, 0.5f);
            soundAt(c, "entity.generic.explode", 1.4f, 0.7f);
            for (Player p : Fx.playersNear(c, 14)) {
                double d = p.getLocation().distance(c);
                hit(p, Math.max(8, 24 - d * 1.2) * damageBonus);
                push(p, p.getLocation().toVector().subtract(c.toVector()).normalize().setY(0.5).multiply(1.2));
            }
        }, null);
    }

    /** 9. Nota Final: un haz que barre girando por toda la arena. */
    public void finalNote() {
        if (!alive()) return;
        soundAt(loc(), "block.conduit.attack_target", 1.7f, 0.4f);

        animate(120, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1.4, 0);
            if (tick < 30) {
                Fx.sphere(l, 2.2 - tick * 0.05, 22, p ->
                        Compat.spawn(world(), Compat.DOLPHIN, p, 1, 0, 0, 0, 0, Compat.dust(LIGHT, 1.5f)));
                return;
            }
            double angle = (tick - 30) * 0.08;
            Vector dir = new Vector(Math.cos(angle), 0, Math.sin(angle));
            for (double d = 1; d <= 18; d += 0.5) {
                Location p = l.clone().add(dir.clone().multiply(d));
                Compat.spawn(world(), Compat.ENCHANT, p, 2, 0.12, 0.12, 0.12, 0, Compat.dust(LIGHT, 1.6f));
                Compat.spawn(world(), Compat.END_ROD, p, 1, 0.02, 0.02, 0.02, 0.01);
            }
            if (tick % 6 == 0) soundAt(l, "block.amethyst_block.chime", 1.1f, 1.4f);
            for (Player p : targets(19)) {
                Vector to = p.getLocation().toVector().subtract(l.toVector()).setY(0);
                if (to.lengthSquared() < 0.01 || to.normalize().dot(dir) < 0.985) continue;
                hit(p, 12 * damageBonus);
            }
        }, null);
    }

    /** 10. Coro Completo: nucleo y cantores disparan a la vez. */
    public void fullChoir() {
        if (!alive()) return;
        titleNear(Component.text("CORO COMPLETO", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Todos a la vez", NamedTextColor.GRAY));
        soundAt(loc(), "block.amethyst_block.resonate", 1.8f, 0.5f);

        animate(80, tick -> {
            if (!alive()) return;
            Location c = boss.getLocation().add(0, 1.4, 0);
            List<Location> sources = new ArrayList<>();
            sources.add(c);
            for (Guardian g : singers) {
                if (g != null && g.isValid()) sources.add(g.getLocation().add(0, 0.8, 0));
            }
            List<Player> victims = targets();
            if (victims.isEmpty()) return;

            for (int i = 0; i < sources.size(); i++) {
                Player v = victims.get(i % victims.size());
                Location from = sources.get(i);
                Location to = v.getLocation().add(0, 1, 0);
                if (tick < 50) {
                    Fx.beam(from, to, 0.8, p -> Compat.spawn(world(), Compat.NAUTILUS, p, 1, 0, 0, 0, 0,
                            Compat.dust(VIOLET, 1.0f)));
                    continue;
                }
                if (tick != 50) continue;
                Fx.beam(from, to, 0.3, p ->
                        Compat.spawn(world(), Compat.GLOW, p, 2, 0.1, 0.1, 0.1, 0, Compat.dust(LIGHT, 1.7f)));
                hit(v, 10 * damageBonus);
                Compat.spawn(world(), Compat.FLASH, to, 1);
            }
            if (tick == 50) soundAt(c, "entity.guardian.attack", 1.7f, 0.7f);
        }, null);
    }

    /** 11. Eco: los cantores intercambian sus sitios sin cambiar su turno. */
    public void echo() {
        if (!alive() || singers.size() < 2) return;
        soundAt(loc(), "entity.enderman.teleport", 1.3f, 1.4f);
        broadcastNear(Component.text("Los cantores se cambian de sitio.", ACCENT));

        List<Location> spots = new ArrayList<>();
        for (Guardian g : singers) {
            if (g != null && g.isValid()) spots.add(g.getLocation().clone());
        }
        Collections.shuffle(spots);
        int i = 0;
        for (Guardian g : singers) {
            if (g == null || !g.isValid() || i >= spots.size()) continue;
            Location to = spots.get(i++);
            Compat.spawn(world(), Compat.BUBBLE_POP, g.getLocation(), 20, 0.4, 0.4, 0.4, 0, Compat.dust(VIOLET, 1.4f));
            Compat.spawn(world(), Compat.END_ROD, to, 20, 0.4, 0.4, 0.4, 0, Compat.dust(VIOLET, 1.4f));
            g.teleport(to);
        }
        // El color no se toca: el turno sigue siendo el mismo, solo cambia donde esta.
        paintOrder();
    }

    /** 12. Silencio: un instante de calma que termina en golpe. */
    public void silence() {
        if (!alive()) return;
        Location c = loc();
        soundAt(c, "block.conduit.deactivate", 1.5f, 1.2f);
        broadcastNear(Component.text("Se hace el silencio.", ACCENT));

        animate(80, tick -> {
            if (tick < 55) {
                if (tick % 10 == 0) {
                    Fx.ring(c, 10, 26, tick * 0.1, p ->
                            Compat.spawn(world(), Compat.DOLPHIN, p, 1, 0, 0, 0, 0, Compat.dust(DEEPV, 1.2f)));
                }
                return;
            }
            if (tick != 55) return;
            soundAt(c, "block.amethyst_block.resonate", 1.9f, 0.4f);
            Compat.spawn(world(), Compat.FLASH, c.clone().add(0, 1, 0), 1);
            for (Player p : Fx.playersNear(c, 12)) {
                hit(p, 14 * damageBonus);
                Compat.apply(p, "slowness", 100, 2);
                push(p, p.getLocation().toVector().subtract(c.toVector()).normalize().setY(0.4).multiply(0.8));
            }
        }, null);
    }

    // ------------------------------------------------------------------ mensajeria

    private void broadcastNear(Component message) {
        Component line = Component.text("✦ ", ACCENT)
                .append(Component.text("Coro Abisal  ", ACCENT, TextDecoration.BOLD))
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
