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
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.CaveSpider;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Goat;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Ravager;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LA QUIMERA, la novena anomalia.
 *
 * Tres animales mal cosidos en uno: cuerpo de fiera, una CABRA clavada en el lomo y
 * una COLA DE SERPIENTE arrastrandose detras. No es un dibujo en particulas: son
 * entidades de verdad montadas unas sobre otras, que es la unica forma honesta de
 * hacer una quimera cuando el servidor no puede repintar una criatura.
 *
 * Se queda con las dos ideas que ya funcionaban:
 *
 *  - LOS CINCO PILARES. Mientras quede uno en pie, la Quimera NO se puede matar. Son
 *    construcciones de bloques de verdad; solo cede el ladrillo CINCELADO del centro,
 *    y al partirlo se derrumba el pilar entero. Esos mismos pilares son la unica
 *    cobertura contra la mirada, asi que cada uno que tiran es un escondite menos.
 *
 *  - LA MIRADA DE LA COLA. Quien la mire de frente cuando la serpiente levanta la
 *    cabeza se queda de piedra: no mata, CLAVA en el sitio, que delante de una fiera
 *    de tres cabezas es peor. Se libra quien aparta la vista, quien levanta el ESCUDO
 *    y quien se pone detras de un pilar.
 *
 * Lo que toca del mundo lo DEVUELVE: cada bloque de cada pilar se guarda con su
 * estado original y se restaura al derrumbarlo o al cerrar el evento.
 */
public final class Quimera extends BossFight {

    public static final String ID = "quimera";
    public static final TextColor ACCENT = TextColor.color(0xC08A4A);

    private static final int VENOM = 0x6DBF3F;

    /** Cuantos pilares se levantan, y por tanto cuantos hay que tirar. */
    private static final int PILLARS = 5;

    private final List<Pillar> pillars = new ArrayList<>();
    /** Los eslabones de la cola, que se arrastran detras del cuerpo. */
    private final List<org.bukkit.entity.BlockDisplay> tail = new ArrayList<>();

    private Goat goatHead;
    private boolean mortal;
    private double damageBonus = 1.0;

    public Quimera(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        super(plugin, event, where);
        abilities.addAll(plugin.registry().quimeraAbilities());
    }

    @Override
    public String bossName() {
        return "Quimera";
    }

    // ------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        Location spot = arena.clone();

        // El cuerpo: un ravager, la fiera mas grande y con peor idea que hay a mano.
        boss = world().spawn(spot, Ravager.class, r -> {
            r.setPersistent(true);
            r.setRemoveWhenFarAway(false);
            r.setCanPickupItems(false);
            r.customName(Component.text("✦ ", ACCENT)
                    .append(Component.text("Quimera", ACCENT, TextDecoration.BOLD)));
            r.setCustomNameVisible(true);
        });

        Compat.setAttribute(boss, "attack_damage", 14);
        Compat.setAttribute(boss, "armor", 14);
        Compat.setAttribute(boss, "knockback_resistance", 1.0);
        Compat.setAttribute(boss, "follow_range", 64);
        Compat.setAttribute(boss, "movement_speed", 0.32);
        Compat.setAttribute(boss, "scale", 1.35);
        applyHealth(plugin.registry().scaledHealth(plugin.registry().quimera(), targets(96).size()));
        boss.setMaximumNoDamageTicks(6);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        Glow.apply(boss, event.type().glowColor());

        growGoatHead();
        growTail();
        raisePillars();
        arrivalAnimation(spot);
    }

    /**
     * La cabra del lomo. Va montada de verdad, asi que se mueve con el cuerpo y berrea
     * por su cuenta. Es intocable: matar una cabeza suelta no es la mecanica.
     */
    private void growGoatHead() {
        goatHead = world().spawn(boss.getLocation().add(0, 1.2, 0), Goat.class, g -> {
            g.setAdult();
            g.setScreaming(true);
            g.setPersistent(true);
            g.setRemoveWhenFarAway(false);
            g.setInvulnerable(true);
            g.setCustomNameVisible(false);
            Compat.setAttribute(g, "scale", 0.95);
        });
        markMinion(goatHead);
        boss.addPassenger(goatHead);
    }

    /** La cola: seis eslabones que van detras con retardo, como una serpiente. */
    private void growTail() {
        // Bloques, no objetos de musgo: los eslabones eran alfombra de musgo y azalea, y
        // arrastrandose por el suelo parecia que la Quimera iba dejando jardin detras
        // como Herbola. Un cubo escamoso se lee como cola y no se confunde con nada.
        for (int i = 0; i < 6; i++) {
            org.bukkit.entity.BlockDisplay link = Fx.blockDisplay(world(), boss.getLocation(),
                    i == 0 ? Material.GREEN_TERRACOTTA : Material.GREEN_GLAZED_TERRACOTTA,
                    i == 0 ? 0.62f : 0.52f - i * 0.05f);
            markMinion(link);
            tail.add(link);
        }
    }

    private void arrivalAnimation(Location spot) {
        boss.setInvulnerable(true);
        busyFor(80);
        soundAt(spot, "entity.ravager.roar", 1.6f, 0.6f);
        soundAt(spot, "entity.goat.screaming.ambient", 1.4f, 0.7f);

        animate(80, tick -> {
            double t = tick / 80.0;
            Fx.ring(spot, t * 8, (int) (t * 8 * 5) + 6, l -> {
                Location g = Fx.ground(l, 4);
                Compat.spawn(world(), Compat.BLOCK, g.clone().add(0, 0.2, 0), 1, 0.1, 0.05, 0.1, 0.01,
                        Material.STONE.createBlockData());
            });
            if (tick % 20 == 0) {
                Compat.spawn(world(), Compat.DUST_PILLAR, spot.clone().add(0, 0.2, 0), 6, 1.2, 0.1, 1.2, 0,
                        Material.STONE.createBlockData());
                soundAt(spot, "entity.ravager.step", 1.3f, 0.6f);
            }
            if (tick % 26 == 0) soundAt(spot, "entity.goat.screaming.ambient", 1.1f, 0.8f);
        }, () -> {
            if (!alive()) return;
            soundAt(spot, "entity.ravager.roar", 1.8f, 0.5f);
            for (Player p : Fx.viewersNear(spot, 90)) {
                p.showTitle(Title.title(
                        Component.text("✦ ANOMALIA ✦", ACCENT, TextDecoration.BOLD),
                        Component.text("Quimera  ·  rompe los cinco pilares", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1800), Duration.ofMillis(600))));
            }
        });
    }

    // =========================================================== LOS CINCO PILARES

    /** Un pilar: lo que se ve, lo que habia debajo y si sigue en pie. */
    private static final class Pillar {
        final Location core;
        final Map<Location, BlockData> before = new LinkedHashMap<>();
        boolean standing = true;

        Pillar(Location core) {
            this.core = core;
        }
    }

    private void raisePillars() {
        Location center = Fx.ground(arena.clone(), 6);
        for (int i = 0; i < PILLARS; i++) {
            double a = Math.PI * 2 * i / PILLARS + 0.35;
            double d = 10 + random.nextDouble() * 4;
            Location base = Fx.ground(center.clone().add(Math.cos(a) * d, 2, Math.sin(a) * d), 8);
            buildPillar(base);
        }
        plugin.getLogger().info("Quimera: levantados " + pillars.size() + " pilares.");
    }

    /**
     * Levanta un pilar de bloques de verdad: escaleras alrededor de la base, dos
     * ladrillos de columna, el CINCELADO a la altura de la cara —el unico que se puede
     * romper— y una losa de remate. Cada bloque se guarda antes para devolverlo.
     */
    private void buildPillar(Location base) {
        Pillar pillar = new Pillar(base.clone().add(0, 3, 0));

        set(pillar, base.clone(), Material.STONE_BRICKS.createBlockData());
        set(pillar, base.clone().add(0, 1, 0), Material.STONE_BRICKS.createBlockData());
        set(pillar, base.clone().add(0, 2, 0), Material.CHISELED_STONE_BRICKS.createBlockData());
        set(pillar, base.clone().add(0, 3, 0), Material.CHISELED_STONE_BRICKS.createBlockData());
        BlockData cap = Material.STONE_BRICK_SLAB.createBlockData();
        if (cap instanceof Slab slab) slab.setType(Slab.Type.BOTTOM);
        set(pillar, base.clone().add(0, 4, 0), cap);

        BlockFace[] around = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace face : around) {
            BlockData stair = Material.STONE_BRICK_STAIRS.createBlockData();
            if (stair instanceof Stairs s) {
                s.setFacing(face.getOppositeFace());
                s.setHalf(Bisected.Half.BOTTOM);
            }
            set(pillar, base.clone().add(face.getModX(), 0, face.getModZ()), stair);
        }

        pillar.core.setY(base.getY() + 3);
        pillars.add(pillar);

        Location fx = base.clone().add(0.5, 2, 0.5);
        Compat.spawn(world(), Compat.BLOCK, fx, 30, 0.6, 1.4, 0.6, 0.05,
                Material.STONE_BRICKS.createBlockData());
        soundAt(base, "block.stone.place", 1.5f, 0.5f);
    }

    private void set(Pillar pillar, Location at, BlockData data) {
        Block b = at.getBlock();
        Location key = b.getLocation();
        if (!pillar.before.containsKey(key)) pillar.before.put(key, b.getBlockData());
        b.setBlockData(data, false);
    }

    private void restore(Pillar pillar) {
        for (Map.Entry<Location, BlockData> e : pillar.before.entrySet()) {
            try {
                e.getKey().getBlock().setBlockData(e.getValue(), false);
            } catch (Throwable ignored) {
            }
        }
        pillar.before.clear();
    }

    private int standingPillars() {
        int n = 0;
        for (Pillar p : pillars) {
            if (p.standing) n++;
        }
        return n;
    }

    /**
     * Solo se puede picar el ladrillo cincelado del centro. Lo demas del pilar se
     * protege: si se pudiera desmontar por abajo, la mecanica se resolveria cavando.
     */
    @Override
    public boolean onBlockBroken(Block block, Player who) {
        Location key = block.getLocation();
        for (Pillar pillar : pillars) {
            if (!pillar.standing) continue;
            if (sameBlock(key, pillar.core)) {
                crumble(pillar, who);
                return true;
            }
            if (pillar.before.containsKey(key)) {
                who.sendActionBar(Component.text("Solo cede el ladrillo cincelado.",
                        NamedTextColor.RED, TextDecoration.BOLD));
                return true;
            }
        }
        return false;
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    /** Cae el cincelado y se lleva por delante el pilar entero. */
    private void crumble(Pillar pillar, Player who) {
        pillar.standing = false;
        Location c = pillar.core.clone().add(0.5, 0.5, 0.5);
        BlockData rubble = Material.STONE_BRICKS.createBlockData();

        Compat.spawn(world(), Compat.BLOCK_CRUMBLE, c, 60, 0.6, 1.6, 0.6, 0.1, rubble);
        Compat.spawn(world(), Compat.BLOCK, c, 50, 0.7, 1.8, 0.7, 0.12, rubble);
        Compat.spawn(world(), Compat.DUST_PILLAR, c.clone().subtract(0, 2.5, 0), 20, 0.8, 0.2, 0.8, 0, rubble);
        soundAt(c, "block.stone.break", 1.8f, 0.4f);
        soundAt(c, "entity.generic.explode", 1.0f, 0.5f);
        restore(pillar);

        int left = standingPillars();
        if (who != null) soundAt(who.getLocation(), "block.amethyst_block.break", 1.4f, 0.7f);
        for (Player p : Fx.viewersNear(loc(), 90)) {
            p.sendActionBar(Component.text("Pilar caido  ", NamedTextColor.GRAY)
                    .append(Component.text((PILLARS - left) + "/" + PILLARS, ACCENT, TextDecoration.BOLD))
                    .append(Component.text(left > 0 ? "   sigue siendo intocable" : "   ya se le puede matar",
                            left > 0 ? NamedTextColor.RED : NamedTextColor.GREEN)));
        }
        if (left > 0) return;
        becomeMortal();
    }

    /** Caido el ultimo pilar, la fiera deja de estar protegida. */
    private void becomeMortal() {
        mortal = true;
        if (!alive()) return;
        boss.setInvulnerable(false);
        Location l = boss.getLocation();
        Compat.spawn(world(), Compat.FLASH, l.clone().add(0, 1.5, 0), 1);
        Compat.spawn(world(), Compat.SCULK_CHARGE_POP, l.clone().add(0, 1.5, 0), 40, 0.8, 1.0, 0.8, 0.1);
        soundAt(l, "entity.ravager.roar", 1.8f, 0.7f);
        titleNear(Component.text("CAEN LOS PILARES", ACCENT, TextDecoration.BOLD),
                Component.text("La Quimera ya puede morir", NamedTextColor.GRAY));
        broadcastNear(Component.text("Se le acabo la piedra que la sostenia.", ACCENT));
    }

    // ---------------------------------------------------- LA COLA Y SU MIRADA

    /**
     * La cola se arrastra con retardo: cada eslabon persigue al anterior en vez de ir
     * clavado a una posicion, que es lo que la hace parecer un bicho y no una fila.
     */
    private void dragTail() {
        if (!alive()) return;
        Vector back = boss.getLocation().getDirection().setY(0);
        if (back.lengthSquared() < 0.01) back = new Vector(1, 0, 0);
        back = back.normalize().multiply(-1);

        Location anchor = boss.getLocation().add(0, 0.9, 0).add(back.clone().multiply(1.1));
        for (int i = 0; i < tail.size(); i++) {
            org.bukkit.entity.BlockDisplay link = tail.get(i);
            if (!link.isValid()) continue;
            double wave = Math.sin(ticks() * 0.16 - i * 0.7) * (0.35 + i * 0.06);
            Vector side = new Vector(-back.getZ(), 0, back.getX()).multiply(wave);
            Location at = anchor.clone()
                    .add(back.clone().multiply(i * 0.55))
                    .add(side)
                    .add(0, i == 0 ? 0.35 : -i * 0.06, 0);
            at.setYaw(boss.getLocation().getYaw());
            link.teleport(at);
        }
        if (ticks() % 8 == 0 && !tail.isEmpty() && tail.get(0).isValid()) {
            Compat.spawn(world(), Compat.ENTITY_EFFECT, tail.get(0).getLocation(), 1, 0.1, 0.1, 0.1, 0,
                    Color.fromRGB(VENOM));
        }
    }

    /** La cabeza de la serpiente, que es de donde sale la mirada. */
    private Location snakeHead() {
        if (!tail.isEmpty() && tail.get(0).isValid()) return tail.get(0).getLocation().add(0, 0.3, 0);
        return boss.getEyeLocation();
    }

    private boolean lookingAtIt(Player p) {
        if (!alive()) return false;
        Vector to = snakeHead().toVector().subtract(p.getEyeLocation().toVector());
        if (to.lengthSquared() < 1.0E-4) return true;
        double dot = p.getEyeLocation().getDirection().normalize().dot(to.normalize());
        return dot > 0.55 && p.hasLineOfSight(boss);
    }

    /**
     * Estar a cubierto detras de un pilar en pie. Los pilares hacen dos cosas a la vez,
     * y esa es la tension: son lo que hay que tirar y el unico escondite que hay.
     */
    private boolean behindPillar(Player p) {
        for (Pillar pillar : pillars) {
            if (!pillar.standing) continue;
            if (pillar.core.distanceSquared(p.getLocation()) < 3.2 * 3.2) return true;
        }
        return false;
    }

    private boolean exposed(Player p) {
        if (!Fx.isFightable(p)) return false;
        if (p.isBlocking()) return false;
        if (behindPillar(p)) return false;
        return lookingAtIt(p);
    }

    /** Petrifica: no mata, CLAVA. Y deja la cascara de piedra encima. */
    private void petrify(Player p, int ticksHeld, double damage) {
        hit(p, damage * damageBonus);
        root(p, ticksHeld);
        p.sendActionBar(Component.text("Te has quedado de piedra.", NamedTextColor.RED, TextDecoration.BOLD));
        soundAt(p.getLocation(), "block.deepslate.place", 1.4f, 0.5f);

        BlockData stone = Material.STONE.createBlockData();
        animate(ticksHeld, tick -> {
            if (!Fx.isFightable(p)) throw Stop.now();
            if (tick % 4 == 0) {
                Compat.spawn(world(), Compat.FALLING_DUST_BLOCK, p.getLocation().add(0, 1.2, 0), 4,
                        0.3, 0.6, 0.3, 0, stone);
            }
        }, () -> {
            if (!Fx.isFightable(p)) return;
            Compat.spawn(world(), Compat.BLOCK_CRUMBLE, p.getLocation().add(0, 1, 0), 20,
                    0.3, 0.6, 0.3, 0.06, stone);
            soundAt(p.getLocation(), "block.stone.break", 1.2f, 0.8f);
        });
    }

    private void gazeWarning(Player p) {
        String state;
        NamedTextColor color;
        if (p.isBlocking()) {
            state = "el escudo aguanta";
            color = NamedTextColor.GREEN;
        } else if (behindPillar(p)) {
            state = "a cubierto tras el pilar";
            color = NamedTextColor.GREEN;
        } else if (lookingAtIt(p)) {
            state = "¡APARTA LA VISTA!";
            color = NamedTextColor.RED;
        } else {
            state = "mirando a otro lado";
            color = NamedTextColor.GREEN;
        }
        p.sendActionBar(Component.text("La cola te busca los ojos  ", NamedTextColor.GRAY)
                .append(Component.text(state, color, TextDecoration.BOLD)));
    }

    // -------------------------------------------------------------------- ambiente

    @Override
    protected void ambient() {
        if (!alive()) return;
        keepHostile();
        dragTail();

        if (goatHead != null && goatHead.isValid() && ticks() % 90 == 0) {
            soundAt(loc(), "entity.goat.screaming.ambient", 0.9f, 0.8f);
        }

        if (!mortal) {
            if (!boss.isInvulnerable()) boss.setInvulnerable(true);
            if (ticks() % 60 == 0) {
                int left = standingPillars();
                for (Player p : Fx.viewersNear(loc(), 60)) {
                    p.sendActionBar(Component.text("La Quimera es intocable  ", NamedTextColor.GRAY)
                            .append(Component.text(left + " pilar" + (left == 1 ? "" : "es") + " en pie",
                                    ACCENT, TextDecoration.BOLD)));
                }
            }
            if (ticks() % 10 == 0) {
                for (Pillar pillar : pillars) {
                    if (!pillar.standing) continue;
                    Fx.beam(boss.getEyeLocation(), pillar.core.clone().add(0.5, 0.5, 0.5), 1.4, q ->
                            Compat.spawn(world(), Compat.END_ROD, q, 1, 0.02, 0.02, 0.02, 0));
                }
            }
        }
    }

    private void keepHostile() {
        if (ticks() % 10 != 0) return;
        Player t = Fx.nearest(boss.getLocation(), plugin.settings().participationRadius());
        if (t == null) return;
        if (boss instanceof org.bukkit.entity.Mob m) {
            LivingEntity current = m.getTarget();
            if (current == null || !current.isValid() || current.isDead()) m.setTarget(t);
        }
    }

    @Override
    public void cleanup() {
        for (Pillar pillar : pillars) {
            if (pillar.standing) restore(pillar);
        }
        pillars.clear();
        tail.clear();
        goatHead = null;
        super.cleanup();
    }

    /** Lo que escupe la cola tambien envenena; va marcado como suyo. */
    @Override
    public void onDealtDamage(Player victim, Entity dealer) {
        if (!(dealer instanceof org.bukkit.entity.Projectile)) return;
        Compat.apply(victim, "poison", 100, 1);
        Compat.spawn(world(), Compat.ITEM_SLIME, victim.getLocation().add(0, 1.2, 0), 10, 0.3, 0.4, 0.3, 0);
    }

    /**
     * Puede estar intocable todo el tiempo que haga falta: es su mecanica, no una
     * animacion colgada. Sin esto el vigilante de BossFight le quitaria la proteccion
     * a los veinte segundos y los pilares no servirian de nada.
     */
    @Override
    protected boolean allowLongInvulnerability() {
        return true;
    }

    // --------------------------------------------------------------- cambio de fase

    @Override
    protected void onPhaseChange(int from, int to) {
        if (event.bars() != null) event.bars().flash(from);
        if (to == 2) goatFury();
        if (to == 3) snakeAwakens();
    }

    /** FASE I -> II. La cabra se vuelve loca y el cuerpo entero acelera. */
    private void goatFury() {
        if (!alive()) return;
        busyFor(70);
        Location spot = boss.getLocation();
        soundAt(spot, "entity.goat.screaming.death", 1.8f, 0.7f);
        broadcastNear(Component.text("La cabra pierde la cabeza.", ACCENT));

        animate(70, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1.8, 0);
            Compat.spawn(world(), Compat.ANGRY_VILLAGER, l, 2, 0.4, 0.3, 0.4, 0);
            if (tick % 12 == 0) {
                soundAt(l, "entity.goat.screaming.ambient", 1.4f, 0.9f);
                Compat.spawn(world(), Compat.SMALL_GUST, l, 2, 0.4, 0.3, 0.4, 0);
            }
        }, () -> {
            if (!alive()) return;
            damageBonus = 1.2;
            Compat.setAttribute(boss, "attack_damage", 17);
            Compat.setAttribute(boss, "movement_speed", 0.37);
            titleNear(Component.text("FASE II", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("Embiste sin frenar", NamedTextColor.GRAY));
        });
    }

    /** FASE III. La serpiente levanta la cabeza: la mirada pasa a ser lo importante. */
    private void snakeAwakens() {
        if (!alive()) return;
        busyFor(70);
        Location spot = boss.getLocation();
        soundAt(spot, "entity.warden.sonic_charge", 1.4f, 1.6f);
        titleNear(Component.text("FASE III", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("La cola levanta la cabeza", NamedTextColor.GRAY));

        animate(70, tick -> {
            if (!alive()) return;
            Location head = snakeHead();
            Compat.spawn(world(), Compat.END_ROD, head, 3, 0.25, 0.15, 0.25, 0.01);
            if (tick % 8 == 0) {
                Compat.spawn(world(), Compat.SCULK_CHARGE_POP, head, 6, 0.3, 0.2, 0.3, 0.02);
                soundAt(spot, "entity.cave_spider.ambient", 1.0f, 0.5f);
            }
        }, () -> {
            if (!alive()) return;
            damageBonus = 1.45;
            Compat.setAttribute(boss, "attack_damage", 20);
            Compat.setAttribute(boss, "movement_speed", 0.40);
        });
    }

    // ---------------------------------------------------------------------- muerte

    /** Se le sueltan las costuras: cada animal se deshace por su lado. */
    @Override
    public void onDeath() {
        Location l = loc();
        BlockData stone = Material.STONE.createBlockData();
        soundAt(l, "entity.ravager.death", 1.6f, 0.5f);
        soundAt(l, "entity.goat.screaming.death", 1.4f, 0.6f);

        for (org.bukkit.entity.BlockDisplay link : tail) {
            if (!link.isValid()) continue;
            Compat.spawn(world(), Compat.BLOCK, link.getLocation(), 8, 0.2, 0.2, 0.2, 0.02,
                    Material.GREEN_TERRACOTTA.createBlockData());
            spawned.remove(link);
            Fx.safeRemove(link);
        }
        tail.clear();
        if (goatHead != null && goatHead.isValid()) {
            Compat.spawn(world(), Compat.POOF, goatHead.getLocation(), 20, 0.4, 0.4, 0.4, 0.05);
            spawned.remove(goatHead);
            Fx.safeRemove(goatHead);
            goatHead = null;
        }

        animate(90, tick -> {
            double t = tick / 90.0;
            Compat.spawn(world(), Compat.FALLING_DUST_BLOCK, l.clone().add(0, 1.2, 0), 5, 0.4, 0.7, 0.4, 0, stone);
            if (tick % 12 == 0) {
                Compat.spawn(world(), Compat.BLOCK_CRUMBLE, l.clone().add(0, 1, 0), 18, 0.4, 0.8, 0.4, 0.05, stone);
                soundAt(l, "block.deepslate.break", 1.2f, 0.5f + (float) t * 0.6f);
            }
        }, () -> {
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l.clone().add(0, 1, 0), 2);
            Compat.spawn(world(), Compat.DUST_PILLAR, l.clone().add(0, 0.2, 0), 30, 1.0, 0.2, 1.0, 0, stone);
            soundAt(l, "block.stone.break", 1.8f, 0.4f);
            broadcastNear(Component.text("Se le sueltan las costuras.", ACCENT));
        });
    }

    // ============================================================== HABILIDADES ==

    /** 1. Mirada de la Cola: la basica. Aviso largo, y castiga al que siga mirando. */
    public void stoneGaze() {
        if (!alive()) return;
        soundAt(loc(), "block.amethyst_block.resonate", 1.6f, 0.5f);
        broadcastNear(Component.text("La cola busca tus ojos.", ACCENT));

        animate(50, tick -> {
            if (!alive()) throw Stop.now();
            Location head = snakeHead();
            Compat.spawn(world(), Compat.END_ROD, head, 2, 0.2, 0.1, 0.2, 0.01);
            if (tick % 10 == 0) {
                for (Player p : targets(24)) gazeWarning(p);
                soundAt(loc(), "block.sculk_shrieker.shriek", 0.8f, 1.8f);
            }
            if (tick != 46) return;
            Compat.spawn(world(), Compat.FLASH, head, 1);
            soundAt(loc(), "entity.warden.sonic_boom", 1.2f, 1.6f);
            for (Player p : targets(24)) {
                if (exposed(p)) {
                    petrify(p, 50, 12);
                } else if (p.isBlocking() && lookingAtIt(p)) {
                    push(p, p.getLocation().getDirection().multiply(-0.4).setY(0.1));
                    p.sendActionBar(Component.text("El escudo devuelve la mirada.",
                            NamedTextColor.GREEN, TextDecoration.BOLD));
                    Compat.spawn(world(), Compat.ENCHANTED_HIT, head, 12, 0.3, 0.3, 0.3, 0.1);
                }
            }
        }, null);
    }

    /** 2. Embestida de la Fiera: baja la cabeza y arrolla en linea recta. */
    public void beastCharge() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        Location from = boss.getLocation();
        Vector dir = target.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        final Vector run = dir.normalize();
        java.util.Set<UUID> hitSet = new java.util.HashSet<>();

        soundAt(from, "entity.ravager.roar", 1.6f, 0.8f);
        broadcastNear(Component.text("Baja la cabeza.", ACCENT));

        animate(50, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < 16) {
                for (double d = 1; d < 14; d += 1.2) {
                    Location g = Fx.ground(boss.getLocation().add(run.clone().multiply(d)), 4);
                    Compat.spawn(world(), Compat.DUST_PILLAR, g.clone().add(0, 0.15, 0), 1, 0.15, 0.05, 0.15, 0,
                            Material.STONE.createBlockData());
                }
                return;
            }
            boss.setVelocity(run.clone().multiply(1.15).setY(boss.getVelocity().getY()));
            Compat.spawn(world(), Compat.SMALL_GUST, boss.getLocation(), 1, 0.2, 0.1, 0.2, 0);
            for (Player p : Fx.playersNear(boss.getLocation(), 2.6)) {
                if (!hitSet.add(p.getUniqueId())) continue;
                hit(p, 15 * damageBonus);
                push(p, run.clone().multiply(1.3).setY(0.55));
                soundAt(p.getLocation(), "entity.ravager.attack", 1.3f, 0.9f);
            }
        }, null);
    }

    /** 3. Berrido de la Cabra: un grito en cono que empuja y marea. */
    public void goatBleat() {
        if (!alive()) return;
        Location origin = boss.getEyeLocation();
        Vector face = origin.getDirection().setY(0);
        if (face.lengthSquared() < 0.01) face = new Vector(1, 0, 0);
        final Vector dir = face.normalize();
        soundAt(origin, "entity.goat.screaming.ambient", 2.0f, 0.5f);
        broadcastNear(Component.text("La cabra berrea.", ACCENT));

        animate(30, tick -> {
            if (!alive()) throw Stop.now();
            double d = 1 + tick * 0.4;
            if (d > 12) return;
            // Nada de notas musicales: esto es un berrido, no una cancion.
            Fx.arc(boss.getLocation().add(0, 1.6, 0), dir, d, Math.PI * 0.55, (int) (d * 4), p -> {
                Compat.spawn(world(), Compat.SMALL_GUST, p, 1, 0, 0, 0, 0);
                Compat.spawn(world(), Compat.WHITE_ASH, p, 1, 0.1, 0.1, 0.1, 0.01);
            });
            if (tick % 8 != 0) return;
            for (Player p : targets(12)) {
                Vector to = p.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
                if (to.lengthSquared() < 0.01 || to.normalize().dot(dir) < 0.45) continue;
                hit(p, 7 * damageBonus);
                Compat.apply(p, "nausea", 90, 0);
                push(p, to.normalize().multiply(0.8).setY(0.3));
            }
        }, null);
    }

    /** 4. Nido de Viboras: de la cola se descuelgan viboras. */
    public void viperNest() {
        if (!alive()) return;
        int count = 3 + random.nextInt(3);
        Location c = snakeHead();
        soundAt(c, "entity.cave_spider.ambient", 1.6f, 0.5f);
        broadcastNear(Component.text("De la cola se descuelgan viboras.", ACCENT));

        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count + random.nextDouble() * 0.4;
            double r = 2 + random.nextDouble() * 3;
            Location sl = Fx.ground(c.clone().add(Math.cos(a) * r, 1, Math.sin(a) * r), 5);
            later(i * 6, () -> {
                if (!alive()) return;
                CaveSpider viper = world().spawn(sl, CaveSpider.class, s -> {
                    s.setPersistent(false);
                    Compat.setAttribute(s, "max_health", 20);
                    s.setHealth(20);
                });
                viper.customName(Component.text("Vibora", TextColor.color(VENOM)));
                markMinion(viper);
                Compat.spawn(world(), Compat.ENTITY_EFFECT, sl.clone().add(0, 0.4, 0), 12, 0.4, 0.3, 0.4, 0,
                        Color.fromRGB(VENOM));
                soundAt(sl, "entity.cave_spider.step", 1.2f, 0.8f);
            });
        }
    }

    /** 5. Escupitajo Venenoso: la cola escupe veneno a distancia. */
    public void venomSpit() {
        if (!alive()) return;
        List<Player> victims = targets(30);
        if (victims.isEmpty()) return;
        soundAt(loc(), "entity.llama.spit", 1.5f, 0.5f);
        broadcastNear(Component.text("La cola escupe.", ACCENT));

        for (int i = 0; i < 3; i++) {
            Player victim = victims.get(random.nextInt(victims.size()));
            later(10 + i * 8, () -> {
                if (!alive() || !Fx.isFightable(victim)) return;
                Location head = snakeHead();
                try {
                    org.bukkit.entity.LlamaSpit spit =
                            world().spawn(head, org.bukkit.entity.LlamaSpit.class);
                    spit.setShooter(boss);
                    spit.setVelocity(victim.getEyeLocation().toVector().subtract(head.toVector())
                            .normalize().multiply(1.8));
                    Tags.markMinion(spit, ID);
                    Tags.markEvent(spit, event.id());
                } catch (Throwable ignored) {
                }
                Compat.spawn(world(), Compat.SPIT, head, 10, 0.2, 0.2, 0.2, 0.05);
                soundAt(head, "entity.llama.spit", 1.3f, 0.7f);
            });
        }
    }

    /** 6. Mirada en Barrido: el rayo de la mirada recorre la arena girando. */
    public void sweepingGaze() {
        if (!alive()) return;
        soundAt(loc(), "block.amethyst_block.resonate", 1.6f, 0.4f);
        broadcastNear(Component.text("Barre la arena con la mirada.", ACCENT));
        java.util.Set<UUID> caught = new java.util.HashSet<>();

        animate(100, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < 20) {
                Compat.spawn(world(), Compat.END_ROD, snakeHead(), 3, 0.2, 0.1, 0.2, 0.01);
                if (tick % 10 == 0) for (Player p : targets(24)) gazeWarning(p);
                return;
            }
            double angle = (tick - 20) * (Math.PI * 2 / 80.0);
            Vector dir = new Vector(Math.cos(angle), 0, Math.sin(angle));
            Location head = snakeHead();
            for (double d = 1.5; d < 18; d += 1.0) {
                Compat.spawn(world(), Compat.END_ROD, head.clone().add(dir.clone().multiply(d)), 1,
                        0.04, 0.04, 0.04, 0);
            }
            if (tick % 20 == 0) soundAt(head, "block.sculk_shrieker.shriek", 0.7f, 1.6f);
            for (Player p : targets(18)) {
                Vector to = p.getLocation().toVector().subtract(head.toVector()).setY(0);
                if (to.lengthSquared() < 0.01) continue;
                double diff = Math.abs(Math.atan2(to.getZ(), to.getX()) - Math.atan2(dir.getZ(), dir.getX()));
                diff = Math.min(diff, Math.PI * 2 - diff);
                if (diff > 0.25) continue;
                if (!exposed(p)) continue;
                if (!caught.add(p.getUniqueId())) continue;
                petrify(p, 30, 8);
            }
        }, null);
    }

    /** 7. Zarpazo Triple: las tres cabezas pegan seguidas a lo que tenga delante. */
    public void tripleMaul() {
        if (!alive()) return;
        soundAt(loc(), "entity.ravager.attack", 1.5f, 0.8f);
        for (int i = 0; i < 3; i++) {
            later(i * 8, () -> {
                if (!alive()) return;
                Location c = boss.getLocation().add(0, 1.2, 0);
                Vector face = boss.getLocation().getDirection().setY(0);
                if (face.lengthSquared() < 0.01) face = new Vector(1, 0, 0);
                final Vector dir = face.normalize();
                Fx.arc(c, dir, 3.5, Math.PI * 0.8, 22, p ->
                        Compat.spawn(world(), Compat.SWEEP_ATTACK, p, 1, 0, 0, 0, 0));
                for (Player p : targets(4.5)) {
                    Vector to = p.getLocation().toVector().subtract(c.toVector()).setY(0);
                    if (to.lengthSquared() < 0.01 || to.normalize().dot(dir) < 0.2) continue;
                    hit(p, 8 * damageBonus);
                    Compat.spawn(world(), Compat.CRIT, p.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.2);
                }
            });
        }
    }

    /** 8. Cornada Ascendente: engancha al mas cercano y lo manda por los aires. */
    public void upwardGore() {
        Player target = Fx.nearest(loc(), 5);
        if (target == null || !alive()) return;
        soundAt(loc(), "entity.goat.ram_impact", 1.6f, 0.7f);
        hit(target, 13 * damageBonus);
        push(target, new Vector(0, 1.15, 0));
        Compat.spawn(world(), Compat.SMALL_GUST, target.getLocation(), 6, 0.3, 0.2, 0.3, 0);
        Compat.spawn(world(), Compat.CRIT, target.getLocation().add(0, 1, 0), 20, 0.3, 0.4, 0.3, 0.3);
        target.sendActionBar(Component.text("Te ha enganchado con el cuerno.",
                NamedTextColor.RED, TextDecoration.BOLD));
    }

    /** 9. Lluvia de Colmillos: colmillos de piedra que brotan bajo cada uno. */
    public void fangRain() {
        List<Player> victims = targets();
        if (victims.isEmpty() || !alive()) return;
        soundAt(loc(), "entity.evoker_fangs.attack", 1.4f, 0.6f);
        broadcastNear(Component.text("El suelo saca colmillos.", ACCENT));
        BlockData deep = Material.DEEPSLATE.createBlockData();

        for (Player victim : victims) {
            Location mark = Fx.ground(victim.getLocation(), 4);
            animate(46, tick -> {
                if (tick < 22) {
                    Fx.telegraph(world(), mark, 2.0, 0x9A9A92);
                    return;
                }
                if (tick != 22) return;
                Compat.spawn(world(), Compat.DUST_PILLAR, mark.clone().add(0, 0.3, 0), 24, 0.6, 0.3, 0.6, 0, deep);
                Compat.spawn(world(), Compat.BLOCK, mark.clone().add(0, 0.5, 0), 24, 0.8, 0.5, 0.8, 0.06, deep);
                soundAt(mark, "entity.evoker_fangs.attack", 1.3f, 0.8f);
                for (Player p : Fx.playersNear(mark, 2.2)) {
                    hit(p, 11 * damageBonus);
                    Compat.apply(p, "poison", 80, 0);
                    push(p, new Vector(0, 0.45, 0));
                }
            }, null);
        }
    }

    /** 10. Veneno Ancestral: una onda de veneno que solo pega en el borde. */
    public void ancientVenom() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 4);
        java.util.Set<UUID> burned = new java.util.HashSet<>();
        soundAt(c, "entity.slime.squish", 1.6f, 0.4f);
        broadcastNear(Component.text("El veneno viejo despierta.", ACCENT));

        animate(70, tick -> {
            if (tick < 18) {
                Fx.telegraph(world(), c, 12.0, VENOM);
                return;
            }
            double radius = (tick - 18) * 0.35;
            if (radius > 12) return;
            Fx.ring(c, radius, (int) (radius * 5) + 6, p -> {
                Location g = Fx.ground(p, 4);
                Compat.spawn(world(), Compat.ITEM_SLIME, g.clone().add(0, 0.3, 0), 1, 0.1, 0.2, 0.1, 0);
            });
            if (tick % 10 == 0) soundAt(c, "block.slime_block.break", 1.1f, 0.6f);
            for (Player p : targets(radius + 1.2)) {
                if (p.getLocation().distance(c) < radius - 1.5) continue;
                if (!burned.add(p.getUniqueId())) continue;
                hit(p, 12 * damageBonus);
                Compat.apply(p, "poison", 120, 1);
            }
        }, null);
    }

    /** 11. La Mirada Entera: cinco segundos avisando, y luego la arena entera. */
    public void fullGaze() {
        if (!alive()) return;
        soundAt(loc(), "entity.warden.sonic_charge", 1.6f, 0.8f);
        titleNear(Component.text("LA MIRADA", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Escudo, pilar o darle la espalda", NamedTextColor.GRAY));

        animate(100, tick -> {
            if (!alive()) throw Stop.now();
            Location head = snakeHead();
            double t = tick / 100.0;
            Compat.spawn(world(), Compat.END_ROD, head, (int) (2 + t * 5), 0.3, 0.2, 0.3, 0.01);
            Fx.ring(head, 1.2 + t * 2.0, 14, tick * 0.2, p ->
                    Compat.spawn(world(), Compat.SCULK_CHARGE_POP, p, 1, 0, 0, 0, 0));
            if (tick % 20 == 0) {
                soundAt(head, "block.amethyst_block.chime", 1.3f, 0.4f + (float) t);
                for (Player p : targets(26)) gazeWarning(p);
            }
            if (tick != 96) return;
            Compat.spawn(world(), Compat.FLASH, head, 1);
            soundAt(head, "entity.warden.sonic_boom", 1.6f, 1.2f);
            for (Player p : targets(26)) {
                if (exposed(p)) {
                    petrify(p, 100, 20);
                } else {
                    p.sendActionBar(Component.text("Te has librado de la mirada.",
                            NamedTextColor.GREEN, TextDecoration.BOLD));
                }
            }
        }, null);
    }

    /** 12. Pisoton de la Fiera: se alza y descarga; la onda barre diez bloques. */
    public void beastStomp() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 4);
        java.util.Set<UUID> struck = new java.util.HashSet<>();
        soundAt(c, "entity.ravager.step", 1.8f, 0.5f);
        broadcastNear(Component.text("Se alza.", ACCENT));

        animate(60, tick -> {
            if (tick < 20) {
                Fx.telegraph(world(), c, 10.0, 0x9A9A92);
                return;
            }
            double radius = (tick - 20) * 0.45;
            if (radius > 10) return;
            Fx.ring(c, radius, (int) (radius * 6) + 6, p -> {
                Location g = Fx.ground(p, 4);
                Compat.spawn(world(), Compat.DUST_PILLAR, g.clone().add(0, 0.15, 0), 1, 0.1, 0.05, 0.1, 0,
                        Material.STONE.createBlockData());
            });
            if (tick % 8 == 0) soundAt(c, "entity.ravager.attack", 1.1f, 0.6f);
            for (Player p : targets(radius + 1.2)) {
                if (p.getLocation().distance(c) < radius - 1.5) continue;
                if (!struck.add(p.getUniqueId())) continue;
                hit(p, 13 * damageBonus);
                push(p, p.getLocation().toVector().subtract(c.toVector())
                        .normalize().multiply(1.1).setY(0.65));
            }
        }, null);
    }

    /** 13. Siseo: un cono de miedo que marea y quita las ganas de estar delante. */
    public void hiss() {
        if (!alive()) return;
        Location origin = snakeHead();
        Vector face = boss.getLocation().getDirection().setY(0);
        if (face.lengthSquared() < 0.01) face = new Vector(1, 0, 0);
        final Vector dir = face.normalize();
        soundAt(origin, "entity.cave_spider.ambient", 1.8f, 0.4f);
        broadcastNear(Component.text("Sisea.", ACCENT));

        animate(30, tick -> {
            if (!alive()) throw Stop.now();
            double d = 1 + tick * 0.35;
            if (d > 10) return;
            Fx.arc(boss.getLocation().add(0, 1.2, 0), dir, d, Math.PI * 0.6, (int) (d * 4), p ->
                    Compat.spawn(world(), Compat.SNEEZE, p, 1, 0.1, 0.1, 0.1, 0.02));
            if (tick % 10 != 0) return;
            for (Player p : targets(10)) {
                Vector to = p.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
                if (to.lengthSquared() < 0.01 || to.normalize().dot(dir) < 0.4) continue;
                hit(p, 5 * damageBonus);
                Compat.apply(p, "nausea", 80, 0);
                Compat.apply(p, "slowness", 60, 1);
                push(p, to.normalize().multiply(0.5).setY(0.15));
            }
        }, null);
    }

    // ------------------------------------------------------------------ mensajeria

    private void broadcastNear(Component message) {
        Component line = Component.text("✦ ", ACCENT)
                .append(Component.text("Quimera  ", ACCENT, TextDecoration.BOLD))
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
