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
import org.bukkit.block.Block;
import org.bukkit.entity.Bogged;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * HERBOLA, la octava anomalia.
 *
 * Un bogged con arco que convierte en jardin todo lo que pisa: musgo, azalea y flores,
 * dejando alfombra de musgo por donde pasa igual que un muneco de nieve deja nieve. La
 * acompana un loro rojo, el Cantor, que vuela con ella —nunca posado encima— y cuyo
 * canto es lo que la mantiene entera.
 *
 * OJO: esta anomalia MODIFICA EL MUNDO. Cada bloque que cambia queda anotado con su
 * estado original y se devuelve tal cual al terminar el evento. Solo convierte bloques
 * naturales de una lista blanca: nunca toca cofres, puertas ni nada construido.
 */
public final class Herbola extends BossFight {

    public static final String ID = "herbola";
    public static final TextColor ACCENT = TextColor.color(0x7BC96F);

    private static final int MOSS = 0x5E8C3F;
    private static final int BLOOM = 0xE86FA8;
    private static final int SAP = 0xC8E06A;

    /** Tope de bloques convertidos. Pasado de aqui deja de transformar. */
    private static final int MAX_CHANGED = 4000;

    /** Lo unico que se permite convertir: terreno natural y nada mas. */
    private static final Set<Material> GROUND = EnumSet.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL,
            Material.ROOTED_DIRT, Material.MUD, Material.CLAY, Material.SAND, Material.RED_SAND,
            Material.GRAVEL, Material.STONE, Material.ANDESITE, Material.DIORITE, Material.GRANITE,
            Material.DEEPSLATE, Material.TUFF, Material.SNOW_BLOCK, Material.DIRT_PATH);

    private static final Set<Material> OVERGROWABLE = EnumSet.of(
            Material.AIR, Material.CAVE_AIR, Material.SHORT_GRASS, Material.TALL_GRASS,
            Material.FERN, Material.LARGE_FERN, Material.DEAD_BUSH, Material.SNOW);

    /** Cuantos bloques lleva convertidos, solo para el tope y el log. */
    private int changed;

    private Parrot parrot;
    private boolean parrotFreed;
    /** Mientras el Cantor hace lo suyo (un picado, el llanto) nadie le toca la posicion. */
    private boolean parrotBusy;
    private boolean flockPhase;
    private double damageBonus = 1.0;

    public Herbola(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        super(plugin, event, where);
        abilities.addAll(plugin.registry().herbolaAbilities());
    }

    @Override
    public String bossName() {
        return "Herbola";
    }

    // ------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        Location spot = arena.clone();

        boss = world().spawn(spot, Bogged.class, b -> {
            b.setPersistent(true);
            b.setRemoveWhenFarAway(false);
            b.setCanPickupItems(false);
            b.setShouldBurnInDay(false);
            b.customName(Component.text("✦ ", ACCENT)
                    .append(Component.text("Herbola", ACCENT, TextDecoration.BOLD)));
            // Su nombre SIEMPRE a la vista. Ya no hay loro sentado en la cabeza que lo
            // tape, asi que no hace falta ningun holograma aparte.
            b.setCustomNameVisible(true);
            EntityEquipment eq = b.getEquipment();
            if (eq != null) {
                // EL ARCO, otra vez. La amapola la dejaba sin ataque a distancia: un
                // bogged sin arco pierde su rutina de disparo y solo sabe empujar, y con
                // el loro montado encima ni eso. Con arco vuelve a tener con que pelear,
                // y sus flechas envenenan de serie.
                eq.setItemInMainHand(new ItemStack(Material.BOW));
                eq.setHelmet(new ItemStack(Material.FLOWERING_AZALEA));
                eq.setItemInMainHandDropChance(0);
                eq.setHelmetDropChance(0);
            }
        });

        Compat.setAttribute(boss, "attack_damage", 14);
        Compat.setAttribute(boss, "armor", 12);
        Compat.setAttribute(boss, "knockback_resistance", 0.8);
        Compat.setAttribute(boss, "follow_range", 72);
        // Rapida a proposito: su gracia es cubrir terreno, y para eso hay que andarlo.
        Compat.setAttribute(boss, "movement_speed", 0.40);
        Compat.setAttribute(boss, "scale", 1.8);
        applyHealth(plugin.registry().scaledHealth(plugin.registry().herbola(), targets(96).size()));
        boss.setMaximumNoDamageTicks(6);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        Glow.apply(boss, event.type().glowColor());

        spawnParrot();
        arrivalAnimation(spot);
    }

    /**
     * El Cantor: el loro rojo que le canta. Nunca se le puede matar.
     *
     * NO SE POSA ENCIMA. Iba de pasajero en la cabeza y eso le costaba a Herbola la mitad
     * de su IA —un mob que lleva pasajero deja de disparar y casi de moverse— ademas de
     * taparle el nombre. Ahora vuela suelto y la sigue, orbitandola por encima.
     *
     * Su nombre lo lleva puesto pero NO fijo: aparece solo cuando le apuntas de cerca,
     * que es el comportamiento normal de un nombre no visible. El de Herbola, en cambio,
     * se ve siempre.
     */
    private void spawnParrot() {
        parrot = world().spawn(escortSpot(), Parrot.class, p -> {
            p.setAdult();
            p.setPersistent(true);
            p.setRemoveWhenFarAway(false);
            p.setInvulnerable(true);
            p.setSilent(false);
            p.setCollidable(false);
            try {
                p.setVariant(Parrot.Variant.RED);
            } catch (Throwable ignored) {
            }
            p.customName(Component.text("Cantor", TextColor.color(0xE05A4A)));
            p.setCustomNameVisible(false);
        });
        markMinion(parrot);
    }

    /**
     * Donde le toca volar al Cantor: orbitando por encima de Herbola.
     *
     * Suelto (fase 2 en adelante) vuela mas alto y mas abierto, para que se lea que ya
     * no le esta cantando al oido sino buscando a quien tirarse.
     */
    private Location escortSpot() {
        double angle = ticks() * 0.05;
        double radius = parrotFreed ? 2.4 : 1.1;
        Location spot = boss.getLocation().add(
                Math.cos(angle) * radius,
                boss.getHeight() + (parrotFreed ? 1.5 : 0.7),
                Math.sin(angle) * radius);
        Vector toBoss = boss.getEyeLocation().toVector().subtract(spot.toVector());
        if (toBoss.lengthSquared() > 0.01) spot.setDirection(toBoss);
        return spot;
    }

    /**
     * Lo pega a su sitio tick a tick. Se le lleva a mano en vez de fiarlo a la IA del
     * loro, que se distrae con cualquier cosa y acaba posandose en un arbol.
     */
    private void escortParrot() {
        if (parrotBusy || parrot == null || !parrot.isValid() || !alive()) return;
        try {
            parrot.setVelocity(new Vector(0, 0, 0));
            parrot.teleport(escortSpot());
        } catch (Throwable ignored) {
        }
    }

    private void arrivalAnimation(Location spot) {
        boss.setInvulnerable(true);
        busyFor(80);
        soundAt(spot, "block.moss.place", 1.6f, 0.6f);
        soundAt(spot, "entity.parrot.ambient", 1.4f, 1.0f);

        animate(80, tick -> {
            double t = tick / 80.0;
            double radius = t * 9;
            Fx.ring(spot, radius, (int) (radius * 6) + 8, l -> {
                Location g = Fx.ground(l, 4);
                Compat.spawn(world(), Compat.SPORE_BLOSSOM_AIR, g.clone().add(0, 0.2, 0), 1, 0, 0, 0, 0,
                        Compat.dust(MOSS, 1.4f));
            });
            if (tick % 8 == 0) {
                bloomAround(Fx.ground(spot, 4), (int) radius, 0.35);
                soundAt(spot, "block.azalea.place", 1.1f, 0.8f);
            }
            if (tick % 16 == 0) soundAt(spot, "entity.parrot.ambient", 1.0f, 1.2f);
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            soundAt(spot, "entity.parrot.imitate.creeper", 1.2f, 1.0f);
            for (Player p : Fx.viewersNear(spot, 90)) {
                p.showTitle(Title.title(
                        Component.text("✦ ANOMALIA ✦", ACCENT, TextDecoration.BOLD),
                        Component.text("Herbola  ·  el bosque se le adelanta", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1800), Duration.ofMillis(600))));
            }
        });
    }

    // --------------------------------------------------- LA CONVERSION DEL TERRENO

    /**
     * Convierte un bloque. Lo que Herbola cambia SE QUEDA: el jardin es permanente,
     * asi lo quiso el servidor. Por eso importa mas que nunca la lista blanca: solo
     * toca terreno natural, nunca un cofre, una puerta ni nada construido.
     */
    private void convert(Block b, Material to) {
        if (changed >= MAX_CHANGED) return;
        if (b.getType() == to) return;
        b.setType(to, false);
        changed++;
    }

    /** El rastro de musgo por donde pisa, como la nieve del muneco. */
    private void trail() {
        if (!alive() || changed >= MAX_CHANGED) return;
        Location g = Fx.ground(boss.getLocation(), 3);
        Block floor = g.getBlock().getRelative(0, -1, 0);
        if (GROUND.contains(floor.getType())) convert(floor, Material.MOSS_BLOCK);
        Block on = g.getBlock();
        if (OVERGROWABLE.contains(on.getType())) convert(on, Material.MOSS_CARPET);
    }

    /**
     * Convierte en jardin un area alrededor de un punto.
     *
     * @param density de 0 a 1; cuanta parte de los bloques se transforma
     */
    private void bloomAround(Location center, int radius, double density) {
        if (changed >= MAX_CHANGED) return;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z > radius * radius) continue;
                if (Math.random() > density) continue;
                Location probe = Fx.ground(center.clone().add(x, 1, z), 5);
                Block floor = probe.getBlock().getRelative(0, -1, 0);
                if (!GROUND.contains(floor.getType())) continue;
                convert(floor, Math.random() < 0.75 ? Material.MOSS_BLOCK : Material.ROOTED_DIRT);

                Block on = probe.getBlock();
                if (!OVERGROWABLE.contains(on.getType())) continue;
                double roll = Math.random();
                Material top;
                if (roll < 0.45) top = Material.MOSS_CARPET;
                else if (roll < 0.65) top = Material.SHORT_GRASS;
                else if (roll < 0.80) top = Material.AZALEA;
                else if (roll < 0.92) top = Material.FLOWERING_AZALEA;
                else top = Math.random() < 0.5 ? Material.OXEYE_DAISY : Material.PINK_PETALS;
                convert(on, top);
            }
        }
    }

    @Override
    public void cleanup() {
        if (changed > 0) {
            plugin.getLogger().info("Herbola: dejo " + changed + " bloque(s) convertidos. Es a proposito.");
        }
        if (parrot != null) {
            Glow.clear(parrot);
            spawned.remove(parrot);
            Fx.safeRemove(parrot);
            parrot = null;
        }
        super.cleanup();
    }

    // -------------------------------------------------------------------- ambiente

    @Override
    protected void ambient() {
        if (!alive()) return;

        // Va mas rapida que antes, asi que el rastro se pinta mas a menudo o se corta.
        if (ticks() % 3 == 0) trail();
        keepHostile();

        if (ticks() % 4 == 0) {
            Location l = boss.getLocation().add(0, 1.4, 0);
            Compat.spawn(world(), Compat.COMPOSTER, l, 2, 0.5, 0.6, 0.5, 0, Compat.dust(MOSS, 1.1f));
            if (Math.random() < 0.3) {
                Compat.spawn(world(), Compat.SPORE_BLOSSOM_AIR,
                        l, 1, 0.6, 0.6, 0.6, 0.01);
            }
        }

        escortParrot();

        // El loro suelto hostiga por su cuenta; el de fase 1 canta mientras vuela.
        if (parrot != null && parrot.isValid()) {
            if (!parrotFreed) {
                if (ticks() % 60 == 0) {
                    Compat.apply(boss, "regeneration", 80, 1);
                    Compat.apply(boss, "resistance", 80, 0);
                    Compat.spawn(world(), Compat.NOTE, parrot.getLocation().add(0, 0.6, 0), 1, 0.1, 0.1, 0.1, 1);
                    soundAt(parrot.getLocation(), "entity.parrot.ambient", 0.9f, 1.3f);
                }
            } else if (ticks() % 5 == 0) {
                Compat.spawn(world(), Compat.HAPPY_VILLAGER, parrot.getLocation(), 2, 0.2, 0.2, 0.2, 0,
                        Compat.dust(0xE05A4A, 1.0f));
            }
        }
    }

    /**
     * Herbola persigue, dispara y pega de cerca.
     *
     * Con el arco de vuelta la rutina de tiro del bogged hace el trabajo sola —eso es lo
     * que se habia perdido y por lo que parecia inofensiva—, asi que aqui solo se le
     * ayuda con lo que su IA hace mal: renovar objetivo, no quedarse plantada en un
     * desnivel y no dejar que nadie se le vaya del mapa. El empujon solo se aplica si
     * esta LEJOS: de cerca estorbaria al tiro y a su propio movimiento.
     */
    private void keepHostile() {
        if (ticks() % 10 != 0) return;
        Player t = Fx.nearest(boss.getLocation(), plugin.settings().participationRadius());
        if (t == null) return;
        if (boss instanceof org.bukkit.entity.Mob m) {
            org.bukkit.entity.LivingEntity current = m.getTarget();
            if (current == null || !current.isValid() || current.isDead()) m.setTarget(t);
        }

        double d2 = boss.getLocation().distanceSquared(t.getLocation());
        // Encima: culatazo. El arco no sirve a bocajarro y no se la va a dejar inofensiva
        // a quien se le pegue.
        if (d2 < 9) {
            if (ticks() % 16 == 0) {
                face(t.getEyeLocation());
                hit(t, 10 * damageBonus);
                Compat.spawn(world(), Compat.SWEEP_ATTACK, t.getLocation().add(0, 1, 0), 1);
                Compat.spawn(world(), Compat.ITEM, t.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.1,
                        new ItemStack(Material.POPPY));
                soundAt(t.getLocation(), "entity.player.attack.sweep", 1.2f, 0.8f);
            }
            return;
        }

        // NO SE LE ESCAPA NADIE. La pathfinding de un esqueleto se pierde con cualquier
        // desnivel y se queda plantada, asi que si el objetivo esta fuera del alcance del
        // arco se le empuja hacia el. Perseguir es su mecanica: si no corre, no convierte
        // terreno y el jefe entero deja de tener sentido.
        if (d2 > 18 * 18 && ticks() % 8 == 0) {
            Vector to = t.getLocation().toVector().subtract(boss.getLocation().toVector());
            double dist = to.length();
            if (dist > 0.5) {
                double push = dist > 30 ? 0.62 : 0.5;
                Vector step = to.normalize().multiply(push);
                // Un saltito si tiene algo delante, para no atascarse en un bordillo.
                double lift = boss.isOnGround() && blocked(step) ? 0.42 : Math.max(0.0, boss.getVelocity().getY());
                boss.setVelocity(step.setY(lift));
                Compat.spawn(world(), Compat.SPORE_BLOSSOM_AIR, boss.getLocation().add(0, 0.4, 0), 2,
                        0.3, 0.2, 0.3, 0.01);
            }
        }
        // Si se le escapa del todo, aparece de golpe entre la maleza.
        if (d2 > 55 * 55 && ticks() % 60 == 0) {
            Location behind = Fx.ground(t.getLocation().clone().add(
                    (Math.random() - 0.5) * 6, 1, (Math.random() - 0.5) * 6), 6);
            boss.teleport(behind);
            Compat.spawn(world(), Compat.COMPOSTER, behind.clone().add(0, 1, 0), 24, 0.5, 0.7, 0.5, 0);
            soundAt(behind, "block.moss.place", 1.3f, 0.7f);
            broadcastNear(Component.text("El bosque te alcanza.", ACCENT));
        }
    }

    /** Si tiene un bloque justo delante a la altura de los pies. */
    private boolean blocked(Vector step) {
        Location ahead = boss.getLocation().add(step.clone().normalize().multiply(0.9));
        return ahead.getBlock().getType().isSolid();
    }

    // --------------------------------------------------------------- cambio de fase

    @Override
    protected void onPhaseChange(int from, int to) {
        if (event.bars() != null) event.bars().flash(from);
        if (to == 2) freeParrot();
        if (to == 3) callFlock();
    }

    /** FASE I -> II. El loro deja de cantar y empieza a hostigar por su cuenta. */
    private void freeParrot() {
        if (parrotFreed || !alive()) return;
        parrotFreed = true;
        boss.setInvulnerable(true);
        busyFor(70);

        Location spot = boss.getLocation();
        soundAt(spot, "entity.parrot.imitate.ender_dragon", 1.6f, 1.0f);
        broadcastNear(Component.text("El loro deja de cantar.", ACCENT));

        animate(70, tick -> {
            if (!alive()) return;
            if (tick == 30 && parrot != null && parrot.isValid()) {
                Compat.spawn(world(), Compat.CHERRY_LEAVES, parrot.getLocation(), 40, 0.5, 0.5, 0.5, 0,
                        Compat.dust(0xE05A4A, 1.5f));
                soundAt(parrot.getLocation(), "entity.parrot.fly", 1.4f, 1.0f);
            }
            Location l = boss.getLocation();
            Fx.ring(Fx.ground(l, 3).add(0, 0.2, 0), 2 + tick * 0.06, 20, tick * 0.2, p ->
                    Compat.spawn(world(), Compat.MYCELIUM, p, 1, 0, 0, 0, 0, Compat.dust(MOSS, 1.3f)));
            if (tick % 10 == 0) bloomAround(Fx.ground(l, 4), 5, 0.3);
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            damageBonus = 1.2;
            Compat.setAttribute(boss, "attack_damage", 14);
            Compat.setAttribute(boss, "movement_speed", 0.34);
            titleNear(Component.text("FASE II", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("El loro ataca en picado y amarra", NamedTextColor.GRAY));
        });
    }

    /** FASE III. Empieza a llamar bandadas de loros que revientan al caer. */
    private void callFlock() {
        if (flockPhase || !alive()) return;
        flockPhase = true;
        boss.setInvulnerable(true);
        busyFor(80);

        Location spot = boss.getLocation();
        soundAt(spot, "entity.parrot.imitate.witch", 1.6f, 0.9f);
        titleNear(Component.text("FASE III", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Vienen mas, y estos revientan", NamedTextColor.GRAY));

        animate(80, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            Fx.helix(l, 2.5, 5.0, 24, 3.0, p ->
                    Compat.spawn(world(), Compat.FALLING_SPORE_BLOSSOM, p, 1, 0, 0, 0, 0, Compat.dust(BLOOM, 1.5f)));
            if (tick % 12 == 0) {
                bloomAround(Fx.ground(l, 4), 8, 0.4);
                soundAt(l, "block.azalea_leaves.place", 1.3f, 0.7f);
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            damageBonus = 1.45;
            Compat.setAttribute(boss, "movement_speed", 0.38);
        });
    }

    // ---------------------------------------------------------------------- muerte

    /**
     * La muerte de Herbola no termina la pelea: empieza el llanto del Cantor.
     *
     * El loro se eleva iluminado de rojo mientras carga un area enorme, muy despacio y
     * muy avisada. Quien no salga a tiempo se come la explosion, y todo lo que alcance
     * queda convertido en jardin para siempre.
     */
    @Override
    public void onDeath() {
        Location l = loc();
        soundAt(l, "entity.bogged.death", 1.6f, 0.7f);

        animate(60, tick -> {
            double t = tick / 60.0;
            Fx.helix(l, 2.4 * (1 - t) + 0.4, 4.0, 22, 2.5, p ->
                    Compat.spawn(world(), Compat.SPORE_BLOSSOM_AIR, p, 1, 0, 0, 0, 0, Compat.dust(MOSS, 1.5f)));
            if (tick % 10 == 0) {
                Compat.spawn(world(), Compat.ITEM, l.clone().add(0, 1, 0), 20, 0.7, 0.7, 0.7, 0.12,
                        new ItemStack(Material.FLOWERING_AZALEA_LEAVES));
                soundAt(l, "block.moss.break", 1.1f, 0.6f + (float) t);
            }
        }, () -> cantorWeeps(l));
    }

    /** El llanto del Cantor dura mucho mas que una muerte normal; hay que esperarlo. */
    @Override
    public int deathAnimationTicks() {
        return 60 + 100 + 60;
    }

    /** El llanto: se eleva, carga, y revienta en un jardin permanente. */
    private void cantorWeeps(Location l) {
        if (parrot == null || !parrot.isValid()) {
            plugin.getLogger().warning("El Cantor ya no estaba al morir Herbola; no hubo llanto.");
            return;
        }
        plugin.getLogger().info("Herbola: empieza el llanto del Cantor.");
        // Ya no sigue a nadie: se eleva el solo mientras carga.
        parrotBusy = true;
        final Location center = Fx.ground(l, 6);
        final double radius = 18;

        for (Player p : Fx.viewersNear(center, 90)) {
            p.sendMessage(plugin.prefix()
                    .append(Component.text("El Cantor llora.", TextColor.color(0xE05A4A), TextDecoration.BOLD)));
            p.showTitle(net.kyori.adventure.title.Title.title(
                    Component.text("EL CANTOR LLORA", TextColor.color(0xE05A4A), TextDecoration.BOLD),
                    Component.text("Salgan del circulo", NamedTextColor.GRAY),
                    net.kyori.adventure.title.Title.Times.times(
                            Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(600))));
        }
        Glow.clear(parrot);
        Glow.apply(parrot, NamedTextColor.RED);
        soundAt(center, "entity.parrot.imitate.ghast", 1.8f, 0.6f);

        // CINCO segundos. Antes eran doce y daba tiempo a salir andando; ahora hay que
        // decidir ya, y quedarse dentro se paga con la vida.
        animate(100, tick -> {
            if (parrot == null || !parrot.isValid()) throw Stop.now();
            parrot.teleport(parrot.getLocation().add(0, 0.045, 0));
            double t = tick / 100.0;

            Fx.ring(center, radius, (int) (40 + t * 90), tick * 0.12, p ->
                    Compat.spawnForced(world(), Compat.COMPOSTER, Fx.ground(p, 5).add(0, 0.3, 0), 1, 0, 0, 0, 0,
                            Compat.dust(0xE05A4A, (float) (1.4 + t))));
            Fx.beam(center, parrot.getLocation(), 1.2, p ->
                    Compat.spawn(world(), Compat.HAPPY_VILLAGER, p, 1, 0, 0, 0, 0, Compat.dust(0xE05A4A, 1.3f)));
            Compat.spawn(world(), Compat.CHERRY_LEAVES, parrot.getLocation(), 6, 0.4, 0.4, 0.4, 0,
                    Compat.dust(0xE05A4A, (float) (1.2 + t)));

            if (tick % 20 == 0) {
                int left = (100 - tick) / 20;
                soundAt(center, "block.note_block.pling", 1.4f, 0.4f + (float) t);
                for (Player p : Fx.viewersNear(center, 60)) {
                    boolean safe = p.getLocation().distance(center) > radius;
                    p.sendActionBar(Component.text("El Cantor llora  ", NamedTextColor.GRAY)
                            .append(Component.text(left + "s", TextColor.color(0xE05A4A), TextDecoration.BOLD))
                            .append(Component.text(safe ? "   estas fuera" : "   ESTAS DENTRO",
                                    safe ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD)));
                }
            }
        }, () -> {
            Location pl = parrot != null && parrot.isValid() ? parrot.getLocation() : center;
            Compat.spawn(world(), Compat.FLASH, pl, 1);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, center, 4);
            soundAt(center, "entity.generic.explode", 2.0f, 0.4f);
            soundAt(center, "entity.parrot.death", 1.8f, 0.7f);
            soundAt(center, "block.moss.place", 1.8f, 0.5f);

            // Todo el circulo queda convertido, y se queda asi.
            bloomAround(center, (int) radius, 1.0);
            for (int r = 1; r <= 6; r++) {
                final int ring = r;
                later(r * 3, () -> Fx.ring(center, ring * 3.0, ring * 14, p ->
                        Compat.spawn(world(), Compat.MYCELIUM, Fx.ground(p, 4).add(0, 0.4, 0), 2, 0, 0, 0, 0,
                                Compat.dust(BLOOM, 1.8f))));
            }
            // CASI DESTRUCTIVO: a bocajarro se lleva por delante a cualquiera, y
            // hasta en el borde del circulo hace falta ir muy bien de vida.
            for (Player p : Fx.playersNear(center, radius)) {
                double d = p.getLocation().distance(center);
                hit(p, Math.max(65, 240 - d * 7));
                push(p, p.getLocation().toVector().subtract(center.toVector())
                        .normalize().setY(0.8).multiply(1.6));
            }
            if (parrot != null) {
                Glow.clear(parrot);
                spawned.remove(parrot);
                Fx.safeRemove(parrot);
                parrot = null;
            }
            plugin.getLogger().info("Herbola: el Cantor estallo; el jardin queda.");
            broadcastNear(Component.text("Donde lloro, crecio un jardin.", ACCENT));
        });
    }

    // ============================================================== HABILIDADES ==

    /** 1. Manto de Musgo: convierte un circulo entero y castiga a quien lo pise. */
    public void mossMantle() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 5);
        soundAt(c, "block.moss.place", 1.6f, 0.5f);
        broadcastNear(Component.text("Extiende el manto.", ACCENT));

        animate(80, tick -> {
            if (tick % 8 != 0) return;
            int r = 2 + tick / 8;
            if (r > 10) return;
            bloomAround(c, r, 0.55);
            Fx.ring(c, r, r * 6, p ->
                    Compat.spawn(world(), Compat.FALLING_SPORE_BLOSSOM, Fx.ground(p, 3).add(0, 0.25, 0), 1, 0, 0, 0, 0,
                            Compat.dust(MOSS, 1.4f)));
            soundAt(c, "block.moss.step", 1.2f, 0.7f);
            for (Player p : Fx.playersNear(c, r)) {
                hit(p, 6 * damageBonus);
                Compat.apply(p, "slowness", 60, 1);
            }
        }, null);
    }

    /** 2. Raices: al que pisa el musgo lo agarran del suelo. */
    public void roots() {
        List<Player> victims = targets(14);
        if (victims.isEmpty() || !alive()) return;
        soundAt(loc(), "block.roots.place", 1.5f, 0.6f);
        broadcastNear(Component.text("El suelo agarra.", ACCENT));

        for (Player victim : victims) {
            Location mark = Fx.ground(victim.getLocation(), 4);
            animate(90, tick -> {
                if (tick < 20) {
                    Fx.telegraph(world(), mark, 2.0, MOSS);
                    return;
                }
                if (tick == 20) {
                    bloomAround(mark, 2, 0.8);
                    soundAt(mark, "block.roots.break", 1.3f, 0.5f);
                }
                if (tick > 70) return;
                for (double h = 0; h < 2.0; h += 0.35) {
                    Fx.ring(mark.clone().add(0, h, 0), 1.0, 8, tick * 0.2 + h, p ->
                            Compat.spawn(world(), Compat.SPORE_BLOSSOM_AIR, p, 1, 0, 0, 0, 0, Compat.dust(MOSS, 1.2f)));
                }
                for (Player p : Fx.playersNear(mark, 1.9)) {
                    rootWithRoots(p, 20);
                    if (tick % 20 == 0) hit(p, 7 * damageBonus);
                }
            }, null);
        }
    }

    /**
     * Amarra a un jugador: no es solo lentitud, es que no se mueve del sitio.
     * Es lo que pedia el picado del loro, y se reutiliza en las raices.
     */
    private void rootWithRoots(Player p, int ticksHeld) {
        // El amarre en si vive en BossFight: el de aqui usaba jump_boost con amplificador
        // 128, que en las versiones actuales son ciento veintiocho niveles de salto y te
        // mandaba al cielo, con expulsion por "moverse muy rapido" de regalo.
        root(p, ticksHeld);
        Compat.spawn(world(), Compat.ITEM_COBWEB, p.getLocation().add(0, 0.4, 0), 4, 0.3, 0.3, 0.3, 0);
        Compat.spawn(world(), Compat.BLOCK, p.getLocation().add(0, 0.4, 0), 12, 0.3, 0.25, 0.3, 0.02,
                Material.MOSS_BLOCK.createBlockData());
    }

    /** 3. Esporas: una nube que envenena y ciega un poco. */
    public void spores() {
        if (!alive()) return;
        Location c = loc();
        soundAt(c, "block.big_dripleaf.tilt_down", 1.5f, 0.6f);
        broadcastNear(Component.text("Suelta esporas.", ACCENT));

        animate(140, tick -> {
            double r = Math.min(9, 2 + tick * 0.1);
            Fx.sphere(c, r, 34, p -> {
                Compat.spawn(world(), Compat.COMPOSTER, p, 1, 0.3, 0.3, 0.3, 0, Compat.dust(SAP, 1.4f));
                if (Math.random() < 0.15) {
                    Compat.spawn(world(), Compat.SPORE_BLOSSOM_AIR, p, 1, 0.2, 0.2, 0.2, 0.01);
                }
            });
            if (tick % 15 != 0) return;
            for (Player p : Fx.playersNear(c, r)) {
                Compat.apply(p, "poison", 100, 1);
                Compat.apply(p, "nausea", 80, 0);
                if (tick % 30 == 0) hit(p, 5 * damageBonus);
            }
        }, null);
    }

    /** 4. Floracion: la azalea revienta desde abajo en anillos. */
    public void bloom() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 5);
        java.util.Set<java.util.UUID> hit = new java.util.HashSet<>();
        soundAt(c, "block.azalea.break", 1.6f, 0.5f);

        animate(80, tick -> {
            if (tick < 22) {
                Fx.telegraph(world(), c, 11.0, BLOOM);
                return;
            }
            double radius = (tick - 22) * 0.4;
            if (radius > 11) return;
            Fx.ring(c, radius, (int) (radius * 6) + 8, p -> {
                Location g = Fx.ground(p, 4);
                for (double h = 0; h < 2.2; h += 0.4) {
                    Compat.spawn(world(), Compat.HAPPY_VILLAGER, g.clone().add(0, h, 0), 1, 0.1, 0.1, 0.1, 0,
                            Compat.dust(Math.random() < 0.5 ? BLOOM : MOSS, 1.4f));
                }
            });
            if (tick % 6 == 0) {
                bloomAround(c, (int) radius, 0.25);
                soundAt(c, "block.azalea_leaves.break", 1.2f, 0.8f);
            }
            for (Player p : targets(radius + 1.2)) {
                if (p.getLocation().distance(c) < radius - 1.5) continue;
                if (!hit.add(p.getUniqueId())) continue;
                hit(p, 14 * damageBonus);
                push(p, new Vector(0, 0.6, 0));
            }
        }, null);
    }

    /** 5. Canto del Loro: el loro le canta y ella aguanta el doble. Solo fase 1. */
    public void parrotSong() {
        if (!alive() || parrotFreed || parrot == null || !parrot.isValid()) return;
        soundAt(parrot.getLocation(), "entity.parrot.imitate.evoker", 1.4f, 1.2f);
        broadcastNear(Component.text("El loro le canta.", ACCENT));

        animate(120, tick -> {
            if (!alive() || parrot == null || !parrot.isValid()) throw Stop.now();
            Location pl = parrot.getLocation().add(0, 0.5, 0);
            if (ticks() % 20 == 0) Compat.spawn(world(), Compat.NOTE, pl, 1, 0.1, 0.1, 0.1, 1);
            Fx.ring(boss.getLocation().add(0, 1.0, 0), 1.6, 12, tick * 0.2, p ->
                    Compat.spawn(world(), Compat.CHERRY_LEAVES, p, 1, 0, 0, 0, 0, Compat.dust(SAP, 1.3f)));
            Compat.apply(boss, "regeneration", 40, 2);
            Compat.apply(boss, "resistance", 40, 1);
            Compat.apply(boss, "speed", 40, 1);
            if (tick % 25 == 0) soundAt(pl, "entity.parrot.ambient", 1.1f, 1.4f);
        }, null);
    }

    /** 6. Latigo de Liana: una liana que barre en linea y arrastra. */
    public void vineWhip() {
        Player target = randomTarget();
        if (target == null || !alive()) return;
        Location origin = boss.getLocation().add(0, 1.2, 0);
        Vector dir = target.getLocation().toVector().subtract(origin.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        final Vector run = dir.normalize();
        java.util.Set<java.util.UUID> lashed = new java.util.HashSet<>();

        soundAt(origin, "block.vine.break", 1.4f, 0.6f);
        animate(55, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation().add(0, 1.2, 0);
            if (tick < 20) {
                for (double d = 2; d < 14; d += 1.0) {
                    Compat.spawn(world(), Compat.MYCELIUM, l.clone().add(run.clone().multiply(d)), 1,
                            0.4, 0.3, 0.4, 0, Compat.dust(MOSS, 1.3f));
                }
                return;
            }
            double reach = (tick - 20) * 1.1;
            if (reach > 14) return;
            Location p = l.clone().add(run.clone().multiply(reach));
            Compat.spawn(world(), Compat.FALLING_SPORE_BLOSSOM, p, 8, 0.4, 0.4, 0.4, 0, Compat.dust(MOSS, 1.5f));
            if (tick == 21) soundAt(l, "item.whip.crack", 1.4f, 0.8f);
            for (Player v : Fx.playersNear(p, 2.4)) {
                if (!lashed.add(v.getUniqueId())) continue;
                hit(v, 14 * damageBonus);
                push(v, l.toVector().subtract(v.getLocation().toVector()).normalize().multiply(0.9).setY(0.25));
                rootWithRoots(v, 30);
            }
        }, null);
    }

    /** 7. Picado del Loro: el loro se tira encima y te deja amarrado. Fase 2. */
    public void parrotDive() {
        if (!alive() || !parrotFreed || parrot == null || !parrot.isValid()) return;
        Player target = randomTarget();
        if (target == null) return;

        soundAt(parrot.getLocation(), "entity.parrot.fly", 1.5f, 0.9f);
        broadcastNear(Component.text("El loro se lanza.", ACCENT));

        // Mientras dure el picado vuela el solo: la escolta no le toca la posicion.
        parrotBusy = true;
        animate(80, tick -> {
            if (!alive() || parrot == null || !parrot.isValid()) throw Stop.now();
            if (!Fx.isFightable(target)) throw Stop.now();
            Location pl = parrot.getLocation();
            Location tl = target.getLocation().add(0, 1, 0);

            if (tick < 22) {
                // sube para coger altura antes de tirarse
                parrot.setVelocity(new Vector(0, 0.35, 0));
                Fx.telegraph(world(), Fx.ground(target.getLocation(), 4), 2.2, 0xE05A4A);
                return;
            }
            if (tick < 55) {
                Vector to = tl.toVector().subtract(pl.toVector());
                if (to.lengthSquared() > 0.4) parrot.setVelocity(to.normalize().multiply(1.15));
                Compat.spawn(world(), Compat.SPORE_BLOSSOM_AIR, pl, 3, 0.2, 0.2, 0.2, 0, Compat.dust(0xE05A4A, 1.2f));
                if (pl.distanceSquared(tl) < 4) {
                    hit(target, 11 * damageBonus);
                    rootWithRoots(target, 70);
                    Compat.spawn(world(), Compat.CRIT, tl, 24, 0.3, 0.4, 0.3, 0.25);
                    Compat.spawn(world(), Compat.COMPOSTER, tl, 30, 0.4, 0.5, 0.4, 0, Compat.dust(MOSS, 1.5f));
                    soundAt(tl, "entity.parrot.hurt", 1.5f, 0.8f);
                    target.sendActionBar(Component.text("Te ha amarrado al suelo.",
                            NamedTextColor.RED, TextDecoration.BOLD));
                    throw Stop.now();
                }
                return;
            }
            parrot.setVelocity(new Vector(0, 0.4, 0));
        }, () -> parrotBusy = false);
    }

    /** 8. Zarzal: un cerco de espinos que se cierra. */
    public void bramble() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 5);
        soundAt(c, "block.sweet_berry_bush.place", 1.5f, 0.6f);
        broadcastNear(Component.text("Levanta el zarzal.", ACCENT));

        animate(140, tick -> {
            double t = tick / 140.0;
            double radius = 15 - t * 9;
            Fx.ring(c, radius, (int) (radius * 5) + 8, tick * 0.06, p -> {
                Location g = Fx.ground(p, 4);
                for (double h = 0; h < 1.6; h += 0.4) {
                    Compat.spawn(world(), Compat.HAPPY_VILLAGER, g.clone().add(0, h, 0), 1, 0, 0, 0, 0,
                            Compat.dust(MOSS, 1.4f));
                }
            });
            if (tick % 20 != 0) return;
            soundAt(c, "block.sweet_berry_bush.break", 1.1f, 0.7f);
            for (Player p : targets(60)) {
                if (p.getLocation().distance(c) <= radius) continue;
                hit(p, 9 * damageBonus);
                Compat.apply(p, "poison", 60, 0);
                p.sendActionBar(Component.text("Estas fuera del claro.", NamedTextColor.RED, TextDecoration.BOLD));
            }
        }, null);
    }

    /** 9. Polen Cegador: una nube dorada que no deja ver. */
    public void pollen() {
        if (!alive()) return;
        Location c = loc();
        soundAt(c, "block.pink_petals.place", 1.5f, 0.8f);

        animate(90, tick -> {
            Fx.sphere(c, 3 + tick * 0.1, 30, p ->
                    Compat.spawn(world(), Compat.CHERRY_LEAVES, p, 1, 0.3, 0.3, 0.3, 0, Compat.dust(BLOOM, 1.5f)));
            if (tick % 18 != 0) return;
            for (Player p : Fx.playersNear(c, 3 + tick * 0.1)) {
                Compat.apply(p, "blindness", 70, 0);
                Compat.apply(p, "slowness", 70, 0);
                hit(p, 5 * damageBonus);
            }
        }, null);
    }

    /** 10. Bosque Subito: brotan arboles de azalea alrededor. */
    public void suddenForest() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 5);
        soundAt(c, "block.azalea_leaves.place", 1.6f, 0.5f);
        broadcastNear(Component.text("Crece el bosque.", ACCENT));

        for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2 * i / 6;
            double d = 5 + Math.random() * 5;
            Location sl = Fx.ground(c.clone().add(Math.cos(a) * d, 1, Math.sin(a) * d), 5);
            later(i * 8, () -> {
                if (!alive()) return;
                Fx.telegraph(world(), sl, 2.0, MOSS);
                later(16, () -> {
                    bloomAround(sl, 2, 0.9);
                    for (double h = 0; h < 4; h += 0.4) {
                        Fx.ring(sl.clone().add(0, h, 0), 1.2, 10, h, p ->
                                Compat.spawn(world(), Compat.MYCELIUM, p, 1, 0, 0, 0, 0, Compat.dust(MOSS, 1.5f)));
                    }
                    Compat.spawn(world(), Compat.ITEM, sl.clone().add(0, 2, 0), 40, 0.8, 1.0, 0.8, 0.1,
                            new ItemStack(Material.FLOWERING_AZALEA_LEAVES));
                    soundAt(sl, "block.azalea.break", 1.3f, 0.6f);
                    for (Player p : Fx.playersNear(sl, 3.0)) {
                        hit(p, 13 * damageBonus);
                        push(p, new Vector(0, 0.7, 0));
                        rootWithRoots(p, 40);
                    }
                });
            });
        }
    }

    /** 11. Bandada Explosiva: loros que suben, se tiran y revientan. Fase 3. */
    public void explosiveFlock() {
        if (!alive()) return;
        int count = 4 + random.nextInt(3);
        Location c = boss.getLocation();
        soundAt(c, "entity.parrot.imitate.ghast", 1.6f, 1.0f);
        broadcastNear(Component.text("Llama a la bandada.", ACCENT));

        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count;
            Location sl = c.clone().add(Math.cos(a) * 5, 1.5, Math.sin(a) * 5);
            later(i * 10, () -> {
                if (!alive()) return;
                Parrot bird = world().spawn(sl, Parrot.class, p -> {
                    p.setAdult();
                    p.setPersistent(false);
                    p.setInvulnerable(true);
                    p.setSilent(true);
                    try {
                        p.setVariant(Math.random() < 0.5 ? Parrot.Variant.RED : Parrot.Variant.GREEN);
                    } catch (Throwable ignored) {
                    }
                });
                markMinion(bird);
                soundAt(sl, "entity.parrot.fly", 1.2f, 1.3f);

                Player victim = randomTarget();
                animate(120, tick -> {
                    if (!bird.isValid()) throw Stop.now();
                    Location bl = bird.getLocation();

                    // Primero busca altura, como se pidio.
                    if (tick < 34) {
                        bird.setVelocity(new Vector(0, 0.42, 0));
                        Compat.spawn(world(), Compat.FALLING_SPORE_BLOSSOM, bl, 2, 0.15, 0.15, 0.15, 0,
                                Compat.dust(0xE05A4A, 1.0f));
                        return;
                    }
                    Location aim = victim != null && Fx.isFightable(victim)
                            ? victim.getLocation() : Fx.ground(boss.getLocation(), 4);
                    if (tick == 34) Fx.telegraph(world(), Fx.ground(aim, 4), 2.6, BLOOM);

                    Vector to = aim.clone().add(0, 0.5, 0).toVector().subtract(bl.toVector());
                    if (to.lengthSquared() > 0.6) bird.setVelocity(to.normalize().multiply(1.3));
                    Compat.spawn(world(), Compat.SPORE_BLOSSOM_AIR, bl, 3, 0.2, 0.2, 0.2, 0, Compat.dust(BLOOM, 1.2f));

                    boolean landed = bl.distanceSquared(aim) < 4
                            || Fx.ground(bl, 2).getBlock().getRelative(0, -1, 0).getType().isSolid()
                            && bl.getY() <= Fx.ground(aim, 5).getY() + 1.2;
                    if (!landed && tick < 110) return;

                    Compat.spawn(world(), Compat.EXPLOSION, bl, 2, 0.4, 0.4, 0.4, 0);
                    Compat.spawn(world(), Compat.COMPOSTER, bl, 50, 0.8, 0.8, 0.8, 0, Compat.dust(BLOOM, 1.7f));
                    Compat.spawn(world(), Compat.ITEM, bl, 30, 0.6, 0.6, 0.6, 0.15,
                            new ItemStack(Material.FLOWERING_AZALEA_LEAVES));
                    soundAt(bl, "entity.generic.explode", 1.3f, 1.3f);
                    soundAt(bl, "entity.parrot.death", 1.2f, 1.0f);
                    bloomAround(Fx.ground(bl, 4), 3, 0.6);
                    for (Player p : Fx.playersNear(bl, 4.0)) {
                        hit(p, 12 * damageBonus);
                        push(p, p.getLocation().toVector().subtract(bl.toVector())
                                .normalize().setY(0.7).multiply(1.5));
                    }
                    spawned.remove(bird);
                    Fx.safeRemove(bird);
                    throw Stop.now();
                }, null);
            });
        }
    }

    /** 12. Savia Corrosiva: charcos de savia que queman al pisarlos. */
    public void corrosiveSap() {
        List<Player> victims = targets();
        if (victims.isEmpty() || !alive()) return;
        soundAt(loc(), "block.honey_block.slide", 1.4f, 0.6f);

        for (Player victim : victims) {
            Location mark = Fx.ground(victim.getLocation(), 4);
            animate(140, tick -> {
                if (tick < 20) {
                    Fx.telegraph(world(), mark, 2.8, SAP);
                    return;
                }
                Fx.ring(mark.clone().add(0, 0.2, 0), 2.6, 16, tick * 0.1, p ->
                        Compat.spawn(world(), Compat.HAPPY_VILLAGER, Fx.ground(p, 3).add(0, 0.2, 0), 1, 0, 0, 0, 0,
                                Compat.dust(SAP, 1.4f)));
                if (tick % 20 != 0) return;
                for (Player p : Fx.playersNear(mark, 2.8)) {
                    hit(p, 8 * damageBonus);
                    Compat.apply(p, "poison", 80, 1);
                    Compat.apply(p, "slowness", 60, 1);
                }
            }, null);
        }
    }

    /** 13. Raiz Madre: una raiz enorme que sale del suelo y lo revienta todo. */
    public void motherRoot() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 5);
        titleNear(Component.text("RAIZ MADRE", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Sal del circulo", NamedTextColor.GRAY));
        soundAt(c, "block.roots.break", 1.7f, 0.4f);

        animate(110, tick -> {
            if (tick < 60) {
                Fx.telegraph(world(), c, 8.0, MOSS);
                if (tick % 10 == 0) {
                    soundAt(c, "block.rooted_dirt.break", 1.2f, 0.5f);
                    Compat.spawn(world(), Compat.BLOCK, c, 20, 3.0, 0.1, 3.0, 0.05,
                            Material.MOSS_BLOCK.createBlockData());
                }
                return;
            }
            if (tick != 60) return;
            bloomAround(c, 8, 0.85);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, c, 2);
            for (double h = 0; h < 7; h += 0.4) {
                Fx.ring(c.clone().add(0, h, 0), 2.5 - h * 0.2, 14, h * 2, p ->
                        Compat.spawn(world(), Compat.CHERRY_LEAVES, p, 1, 0, 0, 0, 0, Compat.dust(MOSS, 1.7f)));
            }
            soundAt(c, "block.roots.break", 1.8f, 0.4f);
            soundAt(c, "entity.generic.explode", 1.4f, 0.5f);
            for (Player p : Fx.playersNear(c, 8.5)) {
                double d = p.getLocation().distance(c);
                hit(p, Math.max(9, 26 - d * 2) * damageBonus);
                push(p, p.getLocation().toVector().subtract(c.toVector()).normalize().setY(0.8).multiply(1.2));
                rootWithRoots(p, 50);
            }
        }, null);
    }

    /** 14. Siembra: deja semillas que brotan y agarran a quien pasa. */
    public void sowing() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 5);
        soundAt(c, "item.bone_meal.use", 1.5f, 0.8f);
        broadcastNear(Component.text("Siembra el terreno.", ACCENT));

        List<Location> seeds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8 + Math.random() * 0.4;
            double d = 3 + Math.random() * 8;
            seeds.add(Fx.ground(c.clone().add(Math.cos(a) * d, 1, Math.sin(a) * d), 5));
        }
        for (Location s : seeds) bloomAround(s, 1, 1.0);

        animate(170, tick -> {
            for (Location s : seeds) {
                Compat.spawn(world(), Compat.MYCELIUM, s.clone().add(0, 0.3, 0), 1, 0.3, 0.15, 0.3, 0,
                        Compat.dust(SAP, 1.2f));
            }
            if (tick % 20 != 0) return;
            for (Location s : seeds) {
                for (Player p : Fx.playersNear(s, 1.8)) {
                    hit(p, 7 * damageBonus);
                    rootWithRoots(p, 30);
                    Compat.spawn(world(), Compat.COMPOSTER, p.getLocation().add(0, 0.5, 0), 14, 0.3, 0.4, 0.3, 0);
                    soundAt(p.getLocation(), "block.roots.place", 1.0f, 0.9f);
                }
            }
        }, null);
    }

    // ------------------------------------------------------------------ mensajeria

    private void broadcastNear(Component message) {
        Component line = Component.text("✦ ", ACCENT)
                .append(Component.text("Herbola  ", ACCENT, TextDecoration.BOLD))
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
