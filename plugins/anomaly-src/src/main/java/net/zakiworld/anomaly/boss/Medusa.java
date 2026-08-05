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
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.CaveSpider;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
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
 * MEDUSA, la novena anomalia.
 *
 * La gorgona. Dos ideas la sostienen, y las dos son de mirar:
 *
 *  - LOS CINCO PILARES. Mientras quede uno en pie, Medusa NO se puede matar. Son
 *    construcciones de bloques de verdad, no un adorno estirado: base de escaleras,
 *    columna de ladrillo de piedra y, a la altura de la cara, el ladrillo CINCELADO,
 *    que es lo unico que se puede picar. Al partirlo se derrumba el pilar entero.
 *    Y ahi esta la gracia: esos mismos pilares son la unica cobertura contra su
 *    mirada, asi que cada uno que tiran es un escondite menos.
 *
 *  - LA MIRADA. Cuando ella mira, tu no miras. Petrificar no mata: te clava en el
 *    sitio delante de todo lo demas. Hay tres formas de librarse, las tres jugables:
 *    apartar la vista, levantar el ESCUDO (el reflejo de Perseo) o ponerse detras
 *    de un pilar.
 *
 * Lo que toca del mundo lo DEVUELVE: cada bloque de cada pilar se guarda con su
 * estado original y se restaura al derrumbarlo o al cerrar el evento. La unica
 * anomalia que deja marca permanente es Herbola, y es a proposito.
 */
public final class Medusa extends BossFight {

    public static final String ID = "medusa";
    public static final TextColor ACCENT = TextColor.color(0xA7C957);

    private static final int VENOM = 0x6DBF3F;
    private static final int EYES = 0xD8F05A;

    /** Cuantos pilares se levantan, y por tanto cuantos hay que tirar. */
    private static final int PILLARS = 5;

    private final List<Pillar> pillars = new ArrayList<>();
    /** Las serpientes de la cabellera, que se mueven solas. */
    private final List<ItemDisplay> hair = new ArrayList<>();

    private boolean mortal;
    private double damageBonus = 1.0;

    public Medusa(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        super(plugin, event, where);
        abilities.addAll(plugin.registry().medusaAbilities());
    }

    @Override
    public String bossName() {
        return "Medusa";
    }

    // ------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        Location spot = arena.clone();

        boss = world().spawn(spot, Husk.class, h -> {
            h.setAdult();
            h.setPersistent(true);
            h.setRemoveWhenFarAway(false);
            h.setCanPickupItems(false);
            h.customName(Component.text("✦ ", ACCENT)
                    .append(Component.text("Medusa", ACCENT, TextDecoration.BOLD)));
            h.setCustomNameVisible(true);
            EntityEquipment eq = h.getEquipment();
            if (eq != null) {
                // Arco en la mano: la gorgona de las estatuas tambien caza de lejos.
                eq.setItemInMainHand(new ItemStack(Material.BOW));
                eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE, 0x3F5D2A));
                eq.setLeggings(dyed(Material.LEATHER_LEGGINGS, 0x33491F));
                eq.setBoots(dyed(Material.LEATHER_BOOTS, 0x6E6E63));
                eq.setItemInMainHandDropChance(0);
                eq.setChestplateDropChance(0);
                eq.setLeggingsDropChance(0);
                eq.setBootsDropChance(0);
            }
        });

        Compat.setAttribute(boss, "attack_damage", 12);
        Compat.setAttribute(boss, "armor", 14);
        Compat.setAttribute(boss, "knockback_resistance", 0.8);
        Compat.setAttribute(boss, "follow_range", 64);
        Compat.setAttribute(boss, "movement_speed", 0.30);
        Compat.setAttribute(boss, "scale", 1.7);
        applyHealth(plugin.registry().scaledHealth(plugin.registry().medusa(), targets(96).size()));
        boss.setMaximumNoDamageTicks(6);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        Glow.apply(boss, event.type().glowColor());

        growHair();
        raisePillars();
        arrivalAnimation(spot);
    }

    /** Armadura de cuero tenida de verde gorgona, sin posibilidad de que caiga. */
    private static ItemStack dyed(Material piece, int rgb) {
        ItemStack item = new ItemStack(piece);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(Color.fromRGB(rgb));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void arrivalAnimation(Location spot) {
        boss.setInvulnerable(true);
        busyFor(80);
        soundAt(spot, "block.deepslate.break", 1.6f, 0.5f);
        soundAt(spot, "entity.cave_spider.ambient", 1.5f, 0.6f);

        animate(80, tick -> {
            double t = tick / 80.0;
            // El suelo se cuartea a su alrededor: polvo de piedra de verdad.
            Fx.ring(spot, t * 8, (int) (t * 8 * 5) + 6, l -> {
                Location g = Fx.ground(l, 4);
                Compat.spawn(world(), Compat.BLOCK, g.clone().add(0, 0.2, 0), 1, 0.1, 0.05, 0.1, 0.01,
                        Material.STONE.createBlockData());
            });
            Fx.helix(spot, 1.4, 3.2, 14, 2.5, l ->
                    Compat.spawn(world(), Compat.ENTITY_EFFECT, l, 1, 0, 0, 0, 0, Color.fromRGB(VENOM)));
            if (tick % 12 == 0) {
                Compat.spawn(world(), Compat.DUST_PILLAR, spot.clone().add(0, 0.2, 0), 6, 1.2, 0.1, 1.2, 0,
                        Material.STONE.createBlockData());
                soundAt(spot, "block.stone.break", 1.2f, 0.6f);
            }
        }, () -> {
            if (!alive()) return;
            // Sigue intocable: de eso se encargan los pilares, no la animacion.
            soundAt(spot, "entity.husk.ambient", 1.6f, 0.5f);
            for (Player p : Fx.viewersNear(spot, 90)) {
                p.showTitle(Title.title(
                        Component.text("✦ ANOMALIA ✦", ACCENT, TextDecoration.BOLD),
                        Component.text("Medusa  ·  rompe los cinco pilares", NamedTextColor.GRAY),
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
        plugin.getLogger().info("Medusa: levantados " + pillars.size() + " pilares.");
    }

    /**
     * Levanta un pilar de bloques de verdad.
     *
     * Nada de texturas estiradas: escaleras alrededor de la base, dos ladrillos de
     * columna, el CINCELADO a la altura de la cara —el unico que se puede romper— y
     * una losa de remate. Cada bloque que se pisa se guarda antes para devolverlo.
     */
    private void buildPillar(Location base) {
        Pillar pillar = new Pillar(base.clone().add(0, 3, 0));

        set(pillar, base.clone().add(0, 0, 0), Material.STONE_BRICKS.createBlockData());
        set(pillar, base.clone().add(0, 1, 0), Material.STONE_BRICKS.createBlockData());
        set(pillar, base.clone().add(0, 2, 0), Material.CHISELED_STONE_BRICKS.createBlockData());
        set(pillar, base.clone().add(0, 3, 0), Material.CHISELED_STONE_BRICKS.createBlockData());
        BlockData cap = Material.STONE_BRICK_SLAB.createBlockData();
        if (cap instanceof Slab slab) slab.setType(Slab.Type.BOTTOM);
        set(pillar, base.clone().add(0, 4, 0), cap);

        // El pedestal: cuatro escaleras mirando hacia fuera, como en la referencia.
        BlockFace[] around = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace face : around) {
            BlockData stair = Material.STONE_BRICK_STAIRS.createBlockData();
            if (stair instanceof Stairs s) {
                s.setFacing(face.getOppositeFace());
                s.setHalf(Bisected.Half.BOTTOM);
            }
            set(pillar, base.clone().add(face.getModX(), 0, face.getModZ()), stair);
        }

        // El cincelado de arriba es el corazon; el de abajo es solo decoracion.
        pillar.core.setY(base.getY() + 3);
        pillars.add(pillar);

        Location fx = base.clone().add(0.5, 2, 0.5);
        Compat.spawn(world(), Compat.BLOCK, fx, 30, 0.6, 1.4, 0.6, 0.05,
                Material.STONE_BRICKS.createBlockData());
        soundAt(base, "block.stone.place", 1.5f, 0.5f);
    }

    /** Coloca un bloque guardando antes lo que hubiera. */
    private void set(Pillar pillar, Location at, BlockData data) {
        Block b = at.getBlock();
        Location key = b.getLocation();
        if (!pillar.before.containsKey(key)) pillar.before.put(key, b.getBlockData());
        b.setBlockData(data, false);
    }

    /** Devuelve el terreno tal y como estaba. */
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
        if (who != null) {
            soundAt(who.getLocation(), "block.amethyst_block.break", 1.4f, 0.7f);
        }
        for (Player p : Fx.viewersNear(loc(), 90)) {
            p.sendActionBar(Component.text("Pilar caido  ", NamedTextColor.GRAY)
                    .append(Component.text((PILLARS - left) + "/" + PILLARS, ACCENT, TextDecoration.BOLD))
                    .append(Component.text(left > 0 ? "   sigue siendo intocable" : "   ya se le puede matar",
                            left > 0 ? NamedTextColor.RED : NamedTextColor.GREEN)));
        }
        if (left > 0) return;

        becomeMortal();
    }

    /** Caido el ultimo pilar, la gorgona deja de estar protegida. */
    private void becomeMortal() {
        mortal = true;
        if (!alive()) return;
        boss.setInvulnerable(false);
        Location l = boss.getLocation();
        Compat.spawn(world(), Compat.FLASH, l.clone().add(0, 1.5, 0), 1);
        Compat.spawn(world(), Compat.SCULK_CHARGE_POP, l.clone().add(0, 1.5, 0), 40, 0.8, 1.0, 0.8, 0.1);
        soundAt(l, "entity.warden.sonic_boom", 1.4f, 1.2f);
        titleNear(Component.text("CAEN LOS PILARES", ACCENT, TextDecoration.BOLD),
                Component.text("Medusa ya puede morir", NamedTextColor.GRAY));
        broadcastNear(Component.text("Se le acabo la piedra que la sostenia.", ACCENT));
    }

    // ------------------------------------------------------------- LA CABELLERA

    /**
     * Las serpientes de la cabeza. Seis, cada una con su propio compas, que es lo que
     * hace que parezcan bichos y no un adorno girando.
     */
    private void growHair() {
        for (int i = 0; i < 6; i++) {
            ItemDisplay snake = Fx.itemDisplay(world(), boss.getEyeLocation(),
                    new ItemStack(i % 2 == 0 ? Material.TWISTING_VINES : Material.WEEPING_VINES), 0.75f);
            markMinion(snake);
            hair.add(snake);
        }
    }

    private void slitherHair() {
        if (!alive()) return;
        Location head = boss.getEyeLocation().add(0, 0.35, 0);
        for (int i = 0; i < hair.size(); i++) {
            ItemDisplay snake = hair.get(i);
            if (!snake.isValid()) continue;
            double phase = ticks() * 0.12 + i * (Math.PI * 2 / hair.size());
            double radius = 0.55 + Math.sin(ticks() * 0.09 + i) * 0.16;
            Location at = head.clone().add(
                    Math.cos(phase) * radius,
                    Math.sin(ticks() * 0.15 + i * 1.3) * 0.14,
                    Math.sin(phase) * radius);
            at.setYaw((float) Math.toDegrees(-phase));
            snake.teleport(at);
        }
        if (ticks() % 6 == 0) {
            Compat.spawn(world(), Compat.ENTITY_EFFECT, head, 2, 0.5, 0.25, 0.5, 0, Color.fromRGB(VENOM));
        }
        if (ticks() % 70 == 0) soundAt(loc(), "entity.cave_spider.ambient", 0.7f, 0.7f);
    }

    // ----------------------------------------------------------------- LA MIRADA

    private boolean lookingAtHer(Player p) {
        if (!alive()) return false;
        Vector to = boss.getEyeLocation().toVector().subtract(p.getEyeLocation().toVector());
        if (to.lengthSquared() < 1.0E-4) return true;
        double dot = p.getEyeLocation().getDirection().normalize().dot(to.normalize());
        return dot > 0.55 && p.hasLineOfSight(boss);
    }

    /**
     * Estar a cubierto detras de un pilar que siga en pie.
     *
     * Los pilares hacen dos cosas a la vez, y esa es toda la tension del combate: son
     * lo que hay que tirar para poder matarla y son el unico escondite de su mirada.
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
        return lookingAtHer(p);
    }

    /** Petrifica: no mata, CLAVA. Y te deja la cascara de piedra encima. */
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
        } else if (lookingAtHer(p)) {
            state = "¡APARTA LA VISTA!";
            color = NamedTextColor.RED;
        } else {
            state = "mirando a otro lado";
            color = NamedTextColor.GREEN;
        }
        p.sendActionBar(Component.text("Medusa te busca los ojos  ", NamedTextColor.GRAY)
                .append(Component.text(state, color, TextDecoration.BOLD)));
    }

    // -------------------------------------------------------------------- ambiente

    @Override
    protected void ambient() {
        if (!alive()) return;
        keepHostile();
        slitherHair();

        // Mientras quede un pilar, es intocable. Se reafirma cada tick por si algo
        // (una animacion, un plugin de terceros) se lo quita por el camino.
        if (!mortal) {
            if (!boss.isInvulnerable()) boss.setInvulnerable(true);
            if (ticks() % 60 == 0) {
                int left = standingPillars();
                for (Player p : Fx.viewersNear(loc(), 60)) {
                    p.sendActionBar(Component.text("Medusa es intocable  ", NamedTextColor.GRAY)
                            .append(Component.text(left + " pilar" + (left == 1 ? "" : "es") + " en pie",
                                    ACCENT, TextDecoration.BOLD)));
                }
            }
            // Un hilo de luz de ella a cada pilar: se ve de lejos que estan unidos.
            if (ticks() % 10 == 0) {
                for (Pillar pillar : pillars) {
                    if (!pillar.standing) continue;
                    Fx.beam(boss.getEyeLocation(), pillar.core.clone().add(0.5, 0.5, 0.5), 1.4, q ->
                            Compat.spawn(world(), Compat.END_ROD, q, 1, 0.02, 0.02, 0.02, 0));
                }
            }
        }
    }

    /** Siempre agresiva, y con el arco puesto tambien de lejos. */
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
        hair.clear();
        super.cleanup();
    }

    /**
     * Sus flechas tambien petrifican, un poquito. Llega aqui porque el manager avisa
     * de todo golpe dado por el jefe o por algo suyo, y las flechas van marcadas.
     */
    @Override
    public void onDealtDamage(Player victim, Entity dealer) {
        if (!(dealer instanceof Arrow)) return;
        Compat.apply(victim, "slowness", 60, 2);
        Compat.spawn(world(), Compat.FALLING_DUST_BLOCK, victim.getLocation().add(0, 1.2, 0), 12,
                0.3, 0.5, 0.3, 0, Material.STONE.createBlockData());
        victim.sendActionBar(Component.text("La flecha te deja la pierna de piedra.",
                NamedTextColor.RED, TextDecoration.BOLD));
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
        if (to == 2) shedSkin();
        if (to == 3) eyesAblaze();
    }

    /** FASE I -> II. Muda de piel: la cascara vieja cae y sale mas rapida. */
    private void shedSkin() {
        if (!alive()) return;
        busyFor(70);
        Location spot = boss.getLocation();
        soundAt(spot, "entity.cave_spider.hurt", 1.6f, 0.5f);
        broadcastNear(Component.text("Muda la piel.", ACCENT));

        animate(70, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            Compat.spawn(world(), Compat.FALLING_DUST_BLOCK, l.clone().add(0, 1.2, 0), 3, 0.5, 0.8, 0.5, 0,
                    Material.STONE.createBlockData());
            if (tick % 10 == 0) {
                Compat.spawn(world(), Compat.ITEM, l.clone().add(0, 1.5, 0), 10, 0.5, 0.7, 0.5, 0.1,
                        new ItemStack(Material.TWISTING_VINES));
                soundAt(l, "entity.spider.step", 1.1f, 0.7f);
            }
            Fx.helix(l, 1.2, 2.8, 12, 2.0, p ->
                    Compat.spawn(world(), Compat.ENTITY_EFFECT, p, 1, 0, 0, 0, 0, Color.fromRGB(VENOM)));
        }, () -> {
            if (!alive()) return;
            damageBonus = 1.2;
            Compat.setAttribute(boss, "attack_damage", 14);
            Compat.setAttribute(boss, "movement_speed", 0.34);
            titleNear(Component.text("FASE II", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("La piel nueva es mas rapida", NamedTextColor.GRAY));
        });
    }

    /** FASE III. Los ojos arden: la mirada deja de ser una habilidad y pasa a ser ELLA. */
    private void eyesAblaze() {
        if (!alive()) return;
        busyFor(70);
        Location spot = boss.getLocation();
        soundAt(spot, "entity.warden.sonic_charge", 1.4f, 1.6f);
        titleNear(Component.text("FASE III", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Los ojos arden; que no te pillen mirando", NamedTextColor.GRAY));

        animate(70, tick -> {
            if (!alive()) return;
            Location eye = boss.getEyeLocation();
            Compat.spawn(world(), Compat.END_ROD, eye, 3, 0.25, 0.15, 0.25, 0.01);
            if (tick % 8 == 0) {
                Compat.spawn(world(), Compat.SCULK_CHARGE_POP, eye, 6, 0.3, 0.2, 0.3, 0.02);
                soundAt(spot, "block.amethyst_block.chime", 1.2f, 0.5f);
            }
        }, () -> {
            if (!alive()) return;
            damageBonus = 1.45;
            Compat.setAttribute(boss, "attack_damage", 16);
            Compat.setAttribute(boss, "movement_speed", 0.36);
        });
    }

    // ---------------------------------------------------------------------- muerte

    /** Se resquebraja de dentro afuera y se deshace en piedra, como sus victimas. */
    @Override
    public void onDeath() {
        Location l = loc();
        BlockData stone = Material.STONE.createBlockData();
        soundAt(l, "entity.husk.death", 1.6f, 0.5f);

        for (ItemDisplay snake : hair) {
            if (!snake.isValid()) continue;
            Compat.spawn(world(), Compat.ITEM, snake.getLocation(), 8, 0.1, 0.1, 0.1, 0.05,
                    new ItemStack(Material.TWISTING_VINES));
            spawned.remove(snake);
            Fx.safeRemove(snake);
        }
        hair.clear();

        animate(90, tick -> {
            double t = tick / 90.0;
            Compat.spawn(world(), Compat.FALLING_DUST_BLOCK, l.clone().add(0, 1.2, 0), 5, 0.4, 0.7, 0.4, 0, stone);
            if (tick % 12 == 0) {
                Compat.spawn(world(), Compat.BLOCK_CRUMBLE, l.clone().add(0, 1, 0), 18, 0.4, 0.8, 0.4, 0.05, stone);
                soundAt(l, "block.deepslate.break", 1.2f, 0.5f + (float) t * 0.6f);
            }
        }, () -> {
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l.clone().add(0, 1, 0), 2);
            Compat.spawn(world(), Compat.BLOCK, l.clone().add(0, 1, 0), 60, 1.0, 1.2, 1.0, 0.1, stone);
            Compat.spawn(world(), Compat.DUST_PILLAR, l.clone().add(0, 0.2, 0), 30, 1.0, 0.2, 1.0, 0, stone);
            soundAt(l, "block.stone.break", 1.8f, 0.4f);
            broadcastNear(Component.text("La piedra, por fin, la reclama a ella.", ACCENT));
        });
    }

    // ============================================================== HABILIDADES ==

    /** 1. Mirada Petrea: la basica. Aviso largo, y al final castiga al que siga mirando. */
    public void stoneGaze() {
        if (!alive()) return;
        soundAt(loc(), "block.amethyst_block.resonate", 1.6f, 0.5f);
        broadcastNear(Component.text("Busca tus ojos.", ACCENT));

        animate(50, tick -> {
            if (!alive()) throw Stop.now();
            Location eye = boss.getEyeLocation();
            Compat.spawn(world(), Compat.END_ROD, eye, 2, 0.2, 0.1, 0.2, 0.01);
            if (tick % 10 == 0) {
                for (Player p : targets(24)) gazeWarning(p);
                soundAt(loc(), "block.sculk_shrieker.shriek", 0.8f, 1.8f);
            }
            if (tick != 46) return;
            Compat.spawn(world(), Compat.FLASH, eye, 1);
            soundAt(loc(), "entity.warden.sonic_boom", 1.2f, 1.6f);
            for (Player p : targets(24)) {
                if (exposed(p)) {
                    petrify(p, 50, 12);
                } else if (p.isBlocking() && lookingAtHer(p)) {
                    // El reflejo de Perseo: la mirada rebota y le pica a ELLA.
                    push(p, p.getLocation().getDirection().multiply(-0.4).setY(0.1));
                    p.sendActionBar(Component.text("El escudo devuelve la mirada.",
                            NamedTextColor.GREEN, TextDecoration.BOLD));
                    Compat.spawn(world(), Compat.ENCHANTED_HIT, boss.getEyeLocation(), 12, 0.3, 0.3, 0.3, 0.1);
                }
            }
        }, null);
    }

    /** 2. Latigo de Serpientes: tres zarpazos en arco delante de ella. */
    public void serpentLash() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        Location origin = boss.getLocation().add(0, 1.0, 0);
        Vector dir = target.getLocation().toVector().subtract(origin.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        final Vector face = dir.normalize();
        soundAt(origin, "entity.cave_spider.ambient", 1.4f, 0.6f);

        for (int i = 0; i < 3; i++) {
            final double radius = 2.5 + i * 1.5;
            later(8 + i * 10, () -> {
                if (!alive()) return;
                Location c = boss.getLocation().add(0, 1.0, 0);
                Fx.arc(c, face, radius, Math.PI * 0.7, (int) (radius * 6), p -> {
                    Location g = Fx.ground(p, 3).add(0, 0.3, 0);
                    Compat.spawn(world(), Compat.ENTITY_EFFECT, g, 1, 0.1, 0.15, 0.1, 0, Color.fromRGB(VENOM));
                });
                soundAt(c, "entity.player.attack.sweep", 1.2f, 0.7f);
                for (Player p : targets(radius + 1.2)) {
                    double d = p.getLocation().distance(c);
                    if (Math.abs(d - radius) > 1.2) continue;
                    Vector to = p.getLocation().toVector().subtract(c.toVector()).setY(0);
                    if (to.lengthSquared() < 0.01 || to.normalize().dot(face) < 0.3) continue;
                    hit(p, 10 * damageBonus);
                    Compat.apply(p, "poison", 60, 0);
                }
            });
        }
    }

    /** 3. Andanada Venenosa: escupe veneno sobre marcas que caen donde estabas. */
    public void venomVolley() {
        List<Player> victims = targets(22);
        if (victims.isEmpty() || !alive()) return;
        soundAt(loc(), "entity.llama.spit", 1.4f, 0.5f);
        broadcastNear(Component.text("Escupe veneno.", ACCENT));

        for (Player victim : victims) {
            Location mark = Fx.ground(victim.getLocation(), 4);
            animate(50, tick -> {
                if (tick < 24) {
                    Fx.telegraph(world(), mark, 2.2, VENOM);
                    return;
                }
                if (tick != 24) return;
                if (alive()) {
                    Fx.beam(boss.getEyeLocation(), mark.clone().add(0, 0.5, 0), 1.0, p ->
                            Compat.spawn(world(), Compat.SPIT, p, 1, 0.05, 0.05, 0.05, 0));
                }
                Compat.spawn(world(), Compat.ITEM_SLIME, mark.clone().add(0, 0.4, 0), 26, 1.0, 0.3, 1.0, 0);
                soundAt(mark, "entity.slime.squish", 1.3f, 0.6f);
                for (Player p : Fx.playersNear(mark, 2.4)) {
                    hit(p, 9 * damageBonus);
                    Compat.apply(p, "poison", 100, 1);
                }
            }, null);
        }
    }

    /** 4. Nido de Viboras: de la cabellera caen viboras que muerden con veneno. */
    public void viperNest() {
        if (!alive()) return;
        int count = 3 + random.nextInt(3);
        Location c = boss.getLocation();
        soundAt(c, "entity.cave_spider.ambient", 1.6f, 0.5f);
        broadcastNear(Component.text("Se le caen del pelo.", ACCENT));

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
                Compat.spawn(world(), Compat.ENTITY_EFFECT, sl.clone().add(0, 0.4, 0), 14, 0.4, 0.3, 0.4, 0,
                        Color.fromRGB(VENOM));
                soundAt(sl, "entity.cave_spider.step", 1.2f, 0.8f);
            });
        }
    }

    /** 5. Flecha Petrea: tres saetas que dejan la pierna de piedra a quien tocan. */
    public void stoneArrow() {
        if (!alive()) return;
        List<Player> victims = targets(30);
        if (victims.isEmpty()) return;
        soundAt(loc(), "item.crossbow.quick_charge_1", 1.4f, 0.6f);
        broadcastNear(Component.text("Tensa el arco.", ACCENT));

        for (int i = 0; i < 3; i++) {
            Player victim = victims.get(random.nextInt(victims.size()));
            later(14 + i * 8, () -> {
                if (!alive() || !Fx.isFightable(victim)) return;
                try {
                    Arrow arrow = boss.launchProjectile(Arrow.class,
                            victim.getEyeLocation().toVector().subtract(boss.getEyeLocation().toVector())
                                    .normalize().multiply(2.4));
                    arrow.setDamage(6 * damageBonus);
                    arrow.setPersistent(false);
                    arrow.setPickupStatus(Arrow.PickupStatus.DISALLOWED);
                    arrow.setColor(Color.fromRGB(0x9A9A92));
                    Tags.markMinion(arrow, ID);
                    Tags.markEvent(arrow, event.id());
                    soundAt(loc(), "entity.arrow.shoot", 1.3f, 0.7f);
                } catch (Throwable ignored) {
                }
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
                Compat.spawn(world(), Compat.END_ROD, boss.getEyeLocation(), 3, 0.2, 0.1, 0.2, 0.01);
                if (tick % 10 == 0) for (Player p : targets(24)) gazeWarning(p);
                return;
            }
            double angle = (tick - 20) * (Math.PI * 2 / 80.0);
            Vector dir = new Vector(Math.cos(angle), 0, Math.sin(angle));
            Location eye = boss.getEyeLocation();
            for (double d = 1.5; d < 18; d += 1.0) {
                Compat.spawn(world(), Compat.END_ROD, eye.clone().add(dir.clone().multiply(d)), 1,
                        0.04, 0.04, 0.04, 0);
            }
            if (tick % 20 == 0) soundAt(eye, "block.sculk_shrieker.shriek", 0.7f, 1.6f);
            for (Player p : targets(18)) {
                Vector to = p.getLocation().toVector().subtract(eye.toVector()).setY(0);
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

    /** 7. Colmillo Certero: se lanza al mas cercano y muerde. */
    public void preciseFang() {
        Player target = Fx.nearest(loc(), 14);
        if (target == null || !alive()) return;
        soundAt(loc(), "entity.cave_spider.hurt", 1.4f, 0.7f);

        Vector run = target.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (run.lengthSquared() > 0.01) {
            boss.setVelocity(run.normalize().multiply(1.0).setY(0.2));
        }
        later(10, () -> {
            if (!alive() || !Fx.isFightable(target)) return;
            if (boss.getLocation().distanceSquared(target.getLocation()) > 9) return;
            hit(target, 12 * damageBonus);
            Compat.apply(target, "poison", 120, 1);
            Compat.spawn(world(), Compat.CRIT, target.getLocation().add(0, 1, 0), 16, 0.3, 0.4, 0.3, 0.2);
            soundAt(target.getLocation(), "entity.player.attack.strong", 1.2f, 0.8f);
        });
    }

    /** 8. Abrazo Petreo: manos de piedra que arrastran al que mas se aleja. */
    public void stoneEmbrace() {
        Player target = Fx.farthest(loc(), plugin.settings().participationRadius());
        if (target == null || !alive()) return;
        soundAt(target.getLocation(), "block.deepslate.break", 1.5f, 0.4f);
        target.sendActionBar(Component.text("La piedra te agarra.", NamedTextColor.RED, TextDecoration.BOLD));
        BlockData stone = Material.STONE.createBlockData();

        animate(55, tick -> {
            if (!alive() || !Fx.isFightable(target)) throw Stop.now();
            Location tl = target.getLocation();
            Fx.ring(tl, 1.0, 8, tick * 0.3, p ->
                    Compat.spawn(world(), Compat.BLOCK, p, 1, 0.05, 0.3, 0.05, 0.02, stone));
            if (tick < 15) return;
            if (tick % 5 == 0) {
                Vector pull = boss.getLocation().toVector().subtract(tl.toVector());
                double dist = pull.length();
                if (dist < 4) throw Stop.now();
                push(target, pull.normalize().multiply(0.55).setY(0.15));
                Compat.spawn(world(), Compat.DUST_PILLAR, tl, 6, 0.3, 0.2, 0.3, 0, stone);
                soundAt(tl, "block.gravel.break", 1.0f, 0.6f);
            }
            if (tick % 20 == 0) hit(target, 5 * damageBonus);
        }, null);
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

    /** 11. Mirada de la Gorgona: la grande. Cinco segundos avisando, y luego la arena entera. */
    public void gorgonGaze() {
        if (!alive()) return;
        soundAt(loc(), "entity.warden.sonic_charge", 1.6f, 0.8f);
        titleNear(Component.text("LA MIRADA", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Escudo, pilar o darle la espalda", NamedTextColor.GRAY));

        animate(100, tick -> {
            if (!alive()) throw Stop.now();
            Location eye = boss.getEyeLocation();
            double t = tick / 100.0;
            Compat.spawn(world(), Compat.END_ROD, eye, (int) (2 + t * 5), 0.3, 0.2, 0.3, 0.01);
            Fx.ring(eye, 1.2 + t * 2.0, 14, tick * 0.2, p ->
                    Compat.spawn(world(), Compat.SCULK_CHARGE_POP, p, 1, 0, 0, 0, 0));
            if (tick % 20 == 0) {
                soundAt(eye, "block.amethyst_block.chime", 1.3f, 0.4f + (float) t);
                for (Player p : targets(26)) gazeWarning(p);
            }
            if (tick != 96) return;
            Compat.spawn(world(), Compat.FLASH, eye, 1);
            soundAt(eye, "entity.warden.sonic_boom", 1.6f, 1.2f);
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

    /** 12. Furia Serpentina: la cabellera entera barre alrededor en tres ondas. */
    public void serpentineFury() {
        if (!alive()) return;
        Location c = boss.getLocation().add(0, 1.0, 0);
        soundAt(c, "entity.cave_spider.death", 1.5f, 0.5f);
        broadcastNear(Component.text("La cabellera se desata.", ACCENT));

        for (int wave = 0; wave < 3; wave++) {
            final int w = wave;
            later(wave * 22, () -> {
                if (!alive()) return;
                java.util.Set<UUID> lashed = new java.util.HashSet<>();
                animate(16, tick -> {
                    double radius = 1.5 + tick * 0.45 + w * 0.5;
                    Location cc = boss.getLocation().add(0, 1.0, 0);
                    Fx.ring(cc, radius, (int) (radius * 6), tick * 0.3, p ->
                            Compat.spawn(world(), Compat.ENTITY_EFFECT, p, 1, 0.1, 0.25, 0.1, 0,
                                    Color.fromRGB(VENOM)));
                    for (Player p : targets(radius + 1.0)) {
                        double d = p.getLocation().distance(cc);
                        if (Math.abs(d - radius) > 1.1) continue;
                        if (!lashed.add(p.getUniqueId())) continue;
                        hit(p, 9 * damageBonus);
                        Compat.apply(p, "poison", 60, 1);
                        push(p, p.getLocation().toVector().subtract(cc.toVector())
                                .normalize().multiply(0.7).setY(0.3));
                    }
                }, null);
                soundAt(c, "entity.player.attack.sweep", 1.3f, 0.6f);
            });
        }
    }

    /** 13. Siseo: un cono de miedo que marea y quita las ganas de estar delante. */
    public void hiss() {
        if (!alive()) return;
        Location origin = boss.getEyeLocation();
        Vector face = origin.getDirection().setY(0);
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
                .append(Component.text("Medusa  ", ACCENT, TextDecoration.BOLD))
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
