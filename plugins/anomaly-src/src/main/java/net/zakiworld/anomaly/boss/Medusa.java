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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.CaveSpider;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * MEDUSA, la novena anomalia.
 *
 * La gorgona: una mirada que convierte en piedra y una cabellera de serpientes.
 * Todo el combate gira alrededor de una regla que el jugador aprende en el primer
 * golpe: CUANDO ELLA MIRA, TU NO MIRAS. Hay tres maneras de salvarse de la mirada,
 * y las tres son jugables, no de suerte:
 *
 *  - apartar la vista (dejar de apuntarle con la camara)
 *  - levantar el ESCUDO: el reflejo, como Perseo
 *  - esconderse detras de una de sus ESTATUAS, que para eso las planta ella
 *
 * La petrificacion no mata de golpe: te clava en el sitio, con lo que el peligro
 * real es quedarse quieto delante de todo lo demas que tira.
 */
public final class Medusa extends BossFight {

    public static final String ID = "medusa";
    public static final TextColor ACCENT = TextColor.color(0xA7C957);

    private static final int STONE = 0x9A9A92;
    private static final int VENOM = 0x6DBF3F;
    private static final int EYES = 0xD8F05A;

    /** Los menhires que planta el Jardin de Estatuas; sirven de escondite. */
    private final List<BlockDisplay> statues = new ArrayList<>();

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
                eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE, 0x3F5D2A));
                eq.setLeggings(dyed(Material.LEATHER_LEGGINGS, 0x33491F));
                eq.setChestplateDropChance(0);
                eq.setLeggingsDropChance(0);
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

        arrivalAnimation(spot);
    }

    /** Armadura de cuero tenida de verde gorgona, sin posibilidad de que caiga. */
    private static ItemStack dyed(Material piece, int rgb) {
        ItemStack item = new ItemStack(piece);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(org.bukkit.Color.fromRGB(rgb));
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
            // La piedra se agrieta a su alrededor y las serpientes despiertan.
            Fx.ring(spot, t * 8, (int) (t * 8 * 6) + 8, l -> {
                Location g = Fx.ground(l, 4);
                Compat.spawn(world(), Compat.DUST, g.clone().add(0, 0.2, 0), 1, 0, 0, 0, 0,
                        Compat.dust(STONE, 1.4f));
            });
            Fx.helix(spot, 1.4, 3.2, 18, 2.5, l ->
                    Compat.spawn(world(), Compat.DUST, l, 1, 0, 0, 0, 0, Compat.dust(VENOM, 1.2f)));
            if (tick % 12 == 0) {
                Compat.spawn(world(), Compat.BLOCK, spot, 14, 1.5, 0.2, 1.5, 0.05,
                        Material.STONE.createBlockData());
                soundAt(spot, "block.stone.break", 1.2f, 0.6f);
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            soundAt(spot, "entity.husk.ambient", 1.6f, 0.5f);
            for (Player p : Fx.viewersNear(spot, 90)) {
                p.showTitle(Title.title(
                        Component.text("✦ ANOMALIA ✦", ACCENT, TextDecoration.BOLD),
                        Component.text("Medusa  ·  no la mires a los ojos", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1800), Duration.ofMillis(600))));
            }
        });
    }

    // ----------------------------------------------------------------- LA MIRADA

    /**
     * Si el jugador esta mirando hacia ella. Es la mitad de la regla del combate:
     * la otra mitad son las dos salvaciones (escudo y estatua).
     */
    private boolean lookingAtHer(Player p) {
        if (!alive()) return false;
        Vector to = boss.getEyeLocation().toVector().subtract(p.getEyeLocation().toVector());
        if (to.lengthSquared() < 1.0E-4) return true;
        double dot = p.getEyeLocation().getDirection().normalize().dot(to.normalize());
        return dot > 0.55 && p.hasLineOfSight(boss);
    }

    /** Pegado a un menhir cuenta como escondido, aunque tecnicamente asome la nariz. */
    private boolean behindStatue(Player p) {
        for (BlockDisplay s : statues) {
            if (!s.isValid()) continue;
            if (s.getLocation().distanceSquared(p.getLocation()) < 2.4 * 2.4) return true;
        }
        return false;
    }

    /**
     * Expuesto a la mirada: mirando hacia ella, sin escudo levantado y sin estatua
     * cerca. Cada mirada pasa por aqui, asi las tres salvaciones valen siempre igual.
     */
    private boolean exposed(Player p) {
        if (!Fx.isFightable(p)) return false;
        if (p.isBlocking()) return false;
        if (behindStatue(p)) return false;
        return lookingAtHer(p);
    }

    /**
     * Petrifica: no mata, CLAVA. Quieto en el sitio, sin saltar y picando lento,
     * con la cascara gris encima para que se vea desde fuera quien cayo.
     */
    private void petrify(Player p, int ticksHeld, double damage) {
        hit(p, damage * damageBonus);
        Compat.apply(p, "slowness", ticksHeld, 250);
        Compat.apply(p, "jump_boost", ticksHeld, 128);
        Compat.apply(p, "mining_fatigue", ticksHeld, 2);
        p.setVelocity(new Vector(0, -0.05, 0));
        p.sendActionBar(Component.text("Te has quedado de piedra.", NamedTextColor.RED, TextDecoration.BOLD));
        soundAt(p.getLocation(), "block.deepslate.place", 1.4f, 0.5f);

        animate(ticksHeld, tick -> {
            if (!Fx.isFightable(p)) throw Stop.now();
            if (tick % 4 == 0) {
                Compat.spawn(world(), Compat.DUST, p.getLocation().add(0, 1.0, 0), 6, 0.3, 0.6, 0.3, 0,
                        Compat.dust(STONE, 1.5f));
            }
        }, () -> {
            if (!Fx.isFightable(p)) return;
            Compat.spawn(world(), Compat.BLOCK, p.getLocation().add(0, 1, 0), 16, 0.3, 0.6, 0.3, 0.05,
                    Material.STONE.createBlockData());
            soundAt(p.getLocation(), "block.stone.break", 1.2f, 0.8f);
        });
    }

    /** El aviso comun de las tres miradas: a cada uno le dice si se esta salvando y por que. */
    private void gazeWarning(Player p) {
        String state;
        NamedTextColor color;
        if (p.isBlocking()) {
            state = "el escudo aguanta";
            color = NamedTextColor.GREEN;
        } else if (behindStatue(p)) {
            state = "escondido tras la estatua";
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

        // La cabellera: un anillo de serpientes girando a la altura de los ojos.
        if (ticks() % 3 == 0) {
            Location eye = boss.getEyeLocation();
            Fx.ring(eye, 0.6, 5, ticks() * 0.25, l ->
                    Compat.spawn(world(), Compat.DUST, l, 1, 0.05, 0.05, 0.05, 0,
                            Compat.dust(VENOM, 1.1f)));
        }
        if (ticks() % 90 == 0) {
            soundAt(loc(), "entity.cave_spider.ambient", 0.8f, 0.7f);
        }

        // Los menhires rotos se caen solos de la lista.
        if (ticks() % 40 == 0) {
            statues.removeIf(s -> !s.isValid());
        }
    }

    private void keepHostile() {
        if (ticks() % 20 != 0) return;
        LivingEntity current = boss instanceof org.bukkit.entity.Mob m ? m.getTarget() : null;
        if (current != null && current.isValid() && !current.isDead()) return;
        Player t = Fx.nearest(boss.getLocation(), plugin.settings().participationRadius());
        if (t != null && boss instanceof org.bukkit.entity.Mob m) m.setTarget(t);
    }

    @Override
    public void cleanup() {
        statues.clear();
        super.cleanup();
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
        boss.setInvulnerable(true);
        busyFor(70);
        Location spot = boss.getLocation();
        soundAt(spot, "entity.cave_spider.hurt", 1.6f, 0.5f);
        broadcastNear(Component.text("Muda la piel.", ACCENT));

        animate(70, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            Compat.spawn(world(), Compat.DUST, l.clone().add(0, 1.2, 0), 4, 0.5, 0.8, 0.5,
                    0, Compat.dust(STONE, 1.4f));
            if (tick % 10 == 0) {
                Compat.spawn(world(), Compat.ITEM, l.clone().add(0, 1.5, 0), 12, 0.5, 0.7, 0.5, 0.1,
                        new ItemStack(Material.GRAY_DYE));
                soundAt(l, "block.stone.break", 1.1f, 0.7f);
            }
            Fx.helix(l, 1.2, 2.8, 16, 2.0, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(VENOM, 1.3f)));
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
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
        boss.setInvulnerable(true);
        busyFor(70);
        Location spot = boss.getLocation();
        soundAt(spot, "entity.warden.sonic_charge", 1.4f, 1.6f);
        titleNear(Component.text("FASE III", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Los ojos arden; que no te pillen mirando", NamedTextColor.GRAY));

        animate(70, tick -> {
            if (!alive()) return;
            Location eye = boss.getEyeLocation();
            Compat.spawn(world(), Compat.DUST, eye, 4, 0.25, 0.15, 0.25, 0, Compat.dust(EYES, 1.6f));
            if (tick % 8 == 0) {
                Compat.spawn(world(), Compat.END_ROD, eye, 2, 0.2, 0.1, 0.2, 0.02);
                soundAt(spot, "block.amethyst_block.chime", 1.2f, 0.5f);
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
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
        soundAt(l, "entity.husk.death", 1.6f, 0.5f);

        animate(90, tick -> {
            double t = tick / 90.0;
            Compat.spawn(world(), Compat.DUST, l.clone().add(0, 1.2, 0), 4, 0.4, 0.7, 0.4, 0,
                    Compat.dust(STONE, (float) (1.2 + t)));
            Fx.helix(l, 1.3 * (1 - t) + 0.2, 3.0, 18, 2.0, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(VENOM, 1.2f)));
            if (tick % 12 == 0) {
                Compat.spawn(world(), Compat.BLOCK, l.clone().add(0, 1, 0), 18, 0.4, 0.8, 0.4, 0.05,
                        Material.STONE.createBlockData());
                soundAt(l, "block.deepslate.break", 1.2f, 0.5f + (float) t * 0.6f);
            }
        }, () -> {
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l.clone().add(0, 1, 0), 2);
            Compat.spawn(world(), Compat.BLOCK, l.clone().add(0, 1, 0), 60, 1.0, 1.2, 1.0, 0.1,
                    Material.STONE.createBlockData());
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
            Compat.spawn(world(), Compat.DUST, eye, 3, 0.2, 0.1, 0.2, 0,
                    Compat.dust(EYES, (float) (1.2 + tick * 0.02)));
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
                    Compat.spawn(world(), Compat.DUST, g, 2, 0.1, 0.15, 0.1, 0, Compat.dust(VENOM, 1.4f));
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
                if (tick == 24) {
                    if (alive()) {
                        Fx.beam(boss.getEyeLocation(), mark.clone().add(0, 0.5, 0), 1.0, p ->
                                Compat.spawn(world(), Compat.DUST, p, 1, 0.1, 0.1, 0.1, 0,
                                        Compat.dust(VENOM, 1.2f)));
                    }
                    Compat.spawn(world(), Compat.DUST, mark.clone().add(0, 0.4, 0), 30, 1.0, 0.4, 1.0, 0,
                            Compat.dust(VENOM, 1.6f));
                    soundAt(mark, "entity.slime.squish", 1.3f, 0.6f);
                    for (Player p : Fx.playersNear(mark, 2.4)) {
                        hit(p, 9 * damageBonus);
                        Compat.apply(p, "poison", 100, 1);
                    }
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
        broadcastNear(Component.text("Suelta el nido.", ACCENT));

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
                Compat.spawn(world(), Compat.DUST, sl.clone().add(0, 0.4, 0), 16, 0.4, 0.3, 0.4, 0,
                        Compat.dust(VENOM, 1.4f));
                soundAt(sl, "entity.cave_spider.step", 1.2f, 0.8f);
            });
        }
    }

    /** 5. Jardin de Estatuas: planta menhires. Son SU decorado, y TU escondite. */
    public void statueGarden() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 5);
        soundAt(c, "block.deepslate.place", 1.6f, 0.4f);
        broadcastNear(Component.text("Levanta su jardin.", ACCENT));

        int count = 4;
        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count + random.nextDouble() * 0.5;
            double d = 6 + random.nextDouble() * 6;
            Location sl = Fx.ground(c.clone().add(Math.cos(a) * d, 1, Math.sin(a) * d), 5);
            later(i * 8, () -> {
                if (!alive()) return;
                raiseStatue(sl);
            });
        }
    }

    /** Un menhir de piedra de dos bloques y medio. Se cae solo pasados 45 segundos. */
    private void raiseStatue(Location base) {
        Compat.spawn(world(), Compat.BLOCK, base, 20, 0.5, 0.2, 0.5, 0.05,
                Material.STONE.createBlockData());
        soundAt(base, "block.stone.place", 1.4f, 0.5f);
        BlockDisplay statue = Fx.lightColumn(world(), base,
                random.nextBoolean() ? Material.STONE : Material.COBBLESTONE, 0.9f, 2.5f);
        statues.add(statue);
        track(statue);
        expire(statue, 900);
    }

    /** 6. Mirada en Barrido: el rayo de la mirada recorre la arena girando. */
    public void sweepingGaze() {
        if (!alive()) return;
        soundAt(loc(), "block.amethyst_block.resonate", 1.6f, 0.4f);
        broadcastNear(Component.text("Barre la arena con la mirada.", ACCENT));
        java.util.Set<java.util.UUID> caught = new java.util.HashSet<>();

        animate(100, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < 20) {
                Compat.spawn(world(), Compat.DUST, boss.getEyeLocation(), 3, 0.2, 0.1, 0.2, 0,
                        Compat.dust(EYES, 1.5f));
                if (tick % 10 == 0) for (Player p : targets(24)) gazeWarning(p);
                return;
            }
            double angle = (tick - 20) * (Math.PI * 2 / 80.0);
            Vector dir = new Vector(Math.cos(angle), 0, Math.sin(angle));
            Location eye = boss.getEyeLocation();
            for (double d = 1.5; d < 18; d += 1.0) {
                Compat.spawn(world(), Compat.DUST, eye.clone().add(dir.clone().multiply(d)), 1,
                        0.15, 0.15, 0.15, 0, Compat.dust(EYES, 1.3f));
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

        animate(55, tick -> {
            if (!alive() || !Fx.isFightable(target)) throw Stop.now();
            Location tl = target.getLocation();
            Fx.ring(tl, 1.0, 8, tick * 0.3, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0.05, 0.3, 0.05, 0, Compat.dust(STONE, 1.4f)));
            if (tick < 15) return;
            if (tick % 5 == 0) {
                Vector pull = boss.getLocation().toVector().subtract(tl.toVector());
                double dist = pull.length();
                if (dist < 4) throw Stop.now();
                push(target, pull.normalize().multiply(0.55).setY(0.15));
                Compat.spawn(world(), Compat.BLOCK, tl, 6, 0.3, 0.2, 0.3, 0.03,
                        Material.STONE.createBlockData());
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

        for (Player victim : victims) {
            Location mark = Fx.ground(victim.getLocation(), 4);
            animate(46, tick -> {
                if (tick < 22) {
                    Fx.telegraph(world(), mark, 2.0, STONE);
                    return;
                }
                if (tick != 22) return;
                for (double h = 0; h < 1.6; h += 0.3) {
                    Fx.ring(mark.clone().add(0, h, 0), 1.4 - h * 0.5, 10, h * 3, p ->
                            Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(STONE, 1.5f)));
                }
                Compat.spawn(world(), Compat.BLOCK, mark.clone().add(0, 0.5, 0), 24, 0.8, 0.5, 0.8, 0.06,
                        Material.DEEPSLATE.createBlockData());
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
        java.util.Set<java.util.UUID> burned = new java.util.HashSet<>();
        soundAt(c, "entity.slime.squish", 1.6f, 0.4f);
        broadcastNear(Component.text("El veneno viejo despierta.", ACCENT));

        animate(70, tick -> {
            if (tick < 18) {
                Fx.telegraph(world(), c, 12.0, VENOM);
                return;
            }
            double radius = (tick - 18) * 0.35;
            if (radius > 12) return;
            Fx.ring(c, radius, (int) (radius * 6) + 8, p -> {
                Location g = Fx.ground(p, 4);
                Compat.spawn(world(), Compat.DUST, g.clone().add(0, 0.3, 0), 1, 0.1, 0.2, 0.1, 0,
                        Compat.dust(VENOM, 1.5f));
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
                Component.text("Escudo, estatua o darle la espalda", NamedTextColor.GRAY));

        animate(100, tick -> {
            if (!alive()) throw Stop.now();
            Location eye = boss.getEyeLocation();
            double t = tick / 100.0;
            Compat.spawn(world(), Compat.DUST, eye, (int) (2 + t * 6), 0.3, 0.2, 0.3, 0,
                    Compat.dust(EYES, (float) (1.2 + t)));
            Fx.ring(eye, 1.2 + t * 2.0, 16, tick * 0.2, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(EYES, 1.2f)));
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

    /** 12. Las Estatuas Despiertan: sus menhires se rompen y sale lo que habia dentro. */
    public void wakeStatues() {
        if (!alive()) return;
        // Si el jardin esta vacio, primero lo planta: despertar sin estatuas no es nada.
        if (statues.stream().noneMatch(BlockDisplay::isValid)) {
            statueGarden();
            later(50, this::breakStatuesIntoSoldiers);
            return;
        }
        breakStatuesIntoSoldiers();
    }

    private void breakStatuesIntoSoldiers() {
        if (!alive()) return;
        broadcastNear(Component.text("Las estatuas despiertan.", ACCENT));
        soundAt(loc(), "block.deepslate.break", 1.7f, 0.4f);

        int woken = 0;
        Iterator<BlockDisplay> it = statues.iterator();
        while (it.hasNext() && woken < 3) {
            BlockDisplay s = it.next();
            if (!s.isValid()) {
                it.remove();
                continue;
            }
            it.remove();
            woken++;
            Location base = s.getLocation();
            spawned.remove(s);
            Fx.safeRemove(s);

            Compat.spawn(world(), Compat.BLOCK, base.clone().add(0, 1, 0), 40, 0.5, 1.0, 0.5, 0.08,
                    Material.STONE.createBlockData());
            soundAt(base, "block.stone.break", 1.5f, 0.5f);
            Husk soldier = world().spawn(base, Husk.class, e -> {
                e.setAdult();
                e.setPersistent(false);
                Compat.setAttribute(e, "max_health", 30);
                e.setHealth(30);
                EntityEquipment eq = e.getEquipment();
                if (eq != null) {
                    eq.setItemInMainHand(new ItemStack(Material.STONE_SWORD));
                    eq.setItemInMainHandDropChance(0);
                }
            });
            soldier.customName(Component.text("Estatua Despierta", TextColor.color(STONE)));
            markMinion(soldier);
        }
    }

    /** 13. Furia Serpentina: gira y la cabellera entera barre alrededor en tres ondas. */
    public void serpentineFury() {
        if (!alive()) return;
        Location c = boss.getLocation().add(0, 1.0, 0);
        soundAt(c, "entity.cave_spider.death", 1.5f, 0.5f);
        broadcastNear(Component.text("La cabellera se desata.", ACCENT));

        for (int wave = 0; wave < 3; wave++) {
            final int w = wave;
            later(wave * 22, () -> {
                if (!alive()) return;
                java.util.Set<java.util.UUID> lashed = new java.util.HashSet<>();
                animate(16, tick -> {
                    double radius = 1.5 + tick * 0.45 + w * 0.5;
                    Location cc = boss.getLocation().add(0, 1.0, 0);
                    Fx.ring(cc, radius, (int) (radius * 7), tick * 0.3, p ->
                            Compat.spawn(world(), Compat.DUST, p, 1, 0.1, 0.25, 0.1, 0,
                                    Compat.dust(VENOM, 1.5f)));
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

    /** 14. Siseo: un cono de miedo que marea y quita las ganas de estar delante. */
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
                    Compat.spawn(world(), Compat.DUST, p, 1, 0.1, 0.1, 0.1, 0, Compat.dust(VENOM, 1.2f)));
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
