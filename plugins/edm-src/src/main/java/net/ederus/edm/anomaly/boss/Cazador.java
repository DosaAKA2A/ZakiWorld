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
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.PiglinBrute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * EL CAZADOR, la decimotercera anomalia.
 *
 * Un piglin brute vestido de cuero negro y con sombrero. No es fuerza bruta: es un
 * cazador de verdad, y lo que lo hace peligroso es que NO PELEA SIEMPRE IGUAL. Cambia
 * de arma delante de ti, y con el arma cambia todo lo demas.
 *
 *  - FASE I, el acecho: ballesta y arco. Se mantiene lejos, dispara y retrocede si te
 *    acercas. Pelearlo a la carrera es perder.
 *  - FASE II, el cepo: siembra el suelo de TRAMPAS. Se ven —una placa que parpadea—,
 *    pero cubren tanto terreno que acabas pisando una, y lo que sigue es una explosion
 *    con muy buen radio. Todas llevan MECHA: la que nadie pisa revienta sola a los doce
 *    segundos, asi que el campo se renueva en vez de acumularse. Mientras tanto dispara.
 *  - FASE III, la estocada: recoge de golpe todas las minas que quedaban y SE LAS TIRA
 *    ENCIMA a sus enemigos, tira las armas de distancia y saca la LANZA DE NETHERITA,
 *    el hacha y la espada, alternandolas segun la distancia a la que estes.
 *
 * El arma que lleva en la mano SIEMPRE dice lo que va a hacer, que es lo que lo hace
 * legible: si saca el hacha, es que viene.
 */
public final class Cazador extends BossFight {

    public static final String ID = "cazador";
    public static final TextColor ACCENT = TextColor.color(0x8C6239);

    private static final int LEATHER = 0x1A1A1A;

    /** Las trampas sembradas: donde estan, que habia debajo y su marca visible. */
    private final List<Trap> traps = new ArrayList<>();

    private double damageBonus = 1.0;
    private int weapon;

    public Cazador(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        super(plugin, event, where);
        abilities.addAll(plugin.registry().cazadorAbilities());
    }

    @Override
    public String bossName() {
        return "El Cazador";
    }

    // ------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        Location spot = arena.clone();

        boss = world().spawn(spot, PiglinBrute.class, p -> {
            p.setAdult();
            p.setPersistent(true);
            p.setRemoveWhenFarAway(false);
            p.setCanPickupItems(false);
            p.setImmuneToZombification(true);
            p.customName(Component.text("✦ ", ACCENT)
                    .append(Component.text("El Cazador", ACCENT, TextDecoration.BOLD)));
            p.setCustomNameVisible(true);
        });

        dressUp();
        Compat.setAttribute(boss, "attack_damage", 13);
        Compat.setAttribute(boss, "armor", 14);
        Compat.setAttribute(boss, "armor_toughness", 4);
        Compat.setAttribute(boss, "knockback_resistance", 0.9);
        Compat.setAttribute(boss, "follow_range", 80);
        Compat.setAttribute(boss, "movement_speed", 0.34);
        Compat.setAttribute(boss, "scale", 1.25);
        applyHealth(plugin.registry().scaledHealth(plugin.registry().cazador(), targets(96).size()));
        boss.setMaximumNoDamageTicks(6);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        Glow.apply(boss, event.type().glowColor());

        drawWeapon(Material.CROSSBOW);
        arrivalAnimation(spot);
    }

    /** Cuero negro de la cabeza a los pies y un sombrero encima. */
    private void dressUp() {
        EntityEquipment eq = boss.getEquipment();
        if (eq == null) return;
        eq.setHelmet(hat());
        eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE));
        eq.setLeggings(dyed(Material.LEATHER_LEGGINGS));
        eq.setBoots(dyed(Material.LEATHER_BOOTS));
        eq.setHelmetDropChance(0);
        eq.setChestplateDropChance(0);
        eq.setLeggingsDropChance(0);
        eq.setBootsDropChance(0);
        eq.setItemInMainHandDropChance(0);
        eq.setItemInOffHandDropChance(0);
    }

    /** El sombrero. Lo mas parecido a un ala ancha que hay sin resource pack. */
    private ItemStack hat() {
        for (String name : new String[]{"BLACK_CARPET", "BLACK_CONCRETE", "BLACK_WOOL"}) {
            Material m = Material.matchMaterial(name);
            if (m != null) return new ItemStack(m);
        }
        return dyed(Material.LEATHER_HELMET);
    }

    private static ItemStack dyed(Material piece) {
        ItemStack item = new ItemStack(piece);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(org.bukkit.Color.fromRGB(LEATHER));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Le pone un arma en la mano, y lo anuncia.
     *
     * Es la mitad del jefe: el arma que lleva es el aviso de lo que va a hacer. Se
     * cambia con sonido y con un mensaje corto para que se lea aunque estes de espaldas.
     */
    private void drawWeapon(Material weaponMaterial) {
        EntityEquipment eq = boss.getEquipment();
        if (eq == null || weaponMaterial == null) return;
        eq.setItemInMainHand(new ItemStack(weaponMaterial));
        eq.setItemInMainHandDropChance(0);
        soundAt(loc(), "item.armor.equip_leather", 1.2f, 0.8f);
        Compat.spawn(world(), Compat.ITEM, boss.getEyeLocation(), 8, 0.2, 0.2, 0.2, 0.05,
                new ItemStack(weaponMaterial));
    }

    /** El primer material de la lista que exista en esta version. */
    private static Material pick(String... names) {
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null) return m;
        }
        return Material.IRON_SWORD;
    }

    private void arrivalAnimation(Location spot) {
        boss.setInvulnerable(true);
        busyFor(70);
        soundAt(spot, "entity.piglin_brute.ambient", 1.6f, 0.6f);
        soundAt(spot, "item.crossbow.loading_start", 1.4f, 0.8f);

        animate(70, tick -> {
            Location l = boss.getLocation().add(0, 1, 0);
            if (tick % 6 == 0) {
                Compat.spawn(world(), Compat.CAMPFIRE_COSY_SMOKE, l, 2, 0.4, 0.3, 0.4, 0.01);
            }
            if (tick % 20 == 0) {
                Compat.spawn(world(), Compat.SMALL_GUST, l, 3, 0.5, 0.3, 0.5, 0);
                soundAt(l, "entity.piglin_brute.step", 1.1f, 0.7f);
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            soundAt(spot, "item.crossbow.loading_end", 1.5f, 0.9f);
            for (Player p : Fx.viewersNear(spot, 90)) {
                p.showTitle(Title.title(
                        Component.text("✦ ANOMALIA ✦", ACCENT, TextDecoration.BOLD),
                        Component.text("El Cazador  ·  mira lo que lleva en la mano", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1800), Duration.ofMillis(600))));
            }
        });
    }

    // ------------------------------------------------------------------ las trampas

    /** Una trampa sembrada: la marca que se ve y el punto donde revienta. */
    private static final class Trap {
        final Location at;
        final BlockDisplay mark;
        long armedAt;

        Trap(Location at, BlockDisplay mark, long armedAt) {
            this.at = at;
            this.mark = mark;
            this.armedAt = armedAt;
        }
    }

    /**
     * LA MECHA. Una mina que nadie pisa NO se queda ahi para siempre: cuenta atras y
     * revienta sola.
     *
     * Antes caducaban al minuto y medio y desaparecian sin mas, asi que el suelo acababa
     * sembrado de placas rojas que solo estorbaban a la vista. Con mecha, cada tanda que
     * siembra es una amenaza con fecha: o la esquivas o te pilla el reventon.
     *
     * Veinte segundos, no doce: con una mecha muy corta el campo se vacia antes de que le
     * de tiempo a sembrar la siguiente tanda, y en la ultima fase no le quedaria nada que
     * tirar. Asi se estabiliza en un puñado de minas en vez de en treinta.
     */
    private static final int FUSE = 400;

    /** Los ultimos tres segundos pitan de verdad, para que se pueda salir. */
    private static final int FUSE_WARNING = 60;

    /**
     * Siembra trampas. Se ven —una placa que asoma del suelo y parpadea—, pero cubren
     * tanto terreno que retroceder sin mirar es pisar una.
     */
    public void layTraps() {
        if (!alive()) return;
        int count = 4 + random.nextInt(3);
        Location c = Fx.ground(boss.getLocation(), 5);
        soundAt(c, "block.tripwire.attach", 1.5f, 0.8f);
        broadcastNear(Component.text("Siembra el suelo.", ACCENT));

        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count + random.nextDouble() * 0.7;
            double d = 4 + random.nextDouble() * 9;
            Location spot = Fx.ground(c.clone().add(Math.cos(a) * d, 1, Math.sin(a) * d), 6);
            later(i * 4, () -> {
                if (!alive()) return;
                traps.add(new Trap(spot.clone(), plantMine(spot), ticks()));
                soundAt(spot, "block.tripwire.click_on", 1.0f, 1.4f);
            });
        }
    }

    /**
     * Planta la marca de una mina y la deja BIEN VISIBLE.
     *
     * La placa a ras de suelo de la primera version no se veia: quedaba enterrada en la
     * hierba y la gente la pisaba sin enterarse de que existia el mecanismo. Ahora es un
     * bloque rojo levantado del suelo y con CONTORNO ROJO —los displays admiten color
     * libre, no solo los dieciseis del marcador—, asi que se ve incluso a traves del
     * terreno. Que se vea es parte del trato: la trampa castiga no mirar, no la mala
     * suerte.
     */
    private BlockDisplay plantMine(Location spot) {
        BlockDisplay mark = Fx.blockDisplay(world(), spot.clone().add(0, 0.45, 0),
                Material.REDSTONE_BLOCK, 0.55f);
        markMinion(mark);
        try {
            mark.setGlowColorOverride(org.bukkit.Color.fromRGB(0xFF2D2D));
            mark.setGlowing(true);
            mark.setViewRange(6.0f);
        } catch (Throwable ignored) {
        }
        Compat.spawn(world(), Compat.ELECTRIC_SPARK, spot.clone().add(0, 0.5, 0), 8, 0.25, 0.15, 0.25, 0.03);
        return mark;
    }

    /** Vigila las trampas: parpadean, caducan y revientan si alguien las pisa. */
    private void tickTraps() {
        if (traps.isEmpty()) return;
        Iterator<Trap> it = traps.iterator();
        while (it.hasNext()) {
            Trap trap = it.next();
            if (trap.mark == null || !trap.mark.isValid()) {
                it.remove();
                continue;
            }
            long age = ticks() - trap.armedAt;
            boolean burning = age > FUSE - FUSE_WARNING;

            // Un guiño rojo cada segundo: la trampa se ve, si se mira. Con la mecha ya
            // corta, el guiño se acelera y suena a lo que es.
            if (ticks() % (burning ? 4 : 16) == 0) {
                Compat.spawn(world(), Compat.ELECTRIC_SPARK, trap.at.clone().add(0, 0.55, 0), burning ? 8 : 4,
                        0.2, 0.1, 0.2, 0.02);
                Fx.ring(trap.at.clone().add(0, 0.12, 0), 1.5, 12, q ->
                        Compat.spawn(world(), Compat.TRIAL_SPAWNER_DETECTION, q, 1, 0, 0, 0, 0));
                soundAt(trap.at, burning ? "entity.creeper.primed" : "block.tripwire.click_off",
                        burning ? 0.8f : 0.5f, 1.8f);
            }
            // Se acabo la mecha: revienta sola, la pise alguien o no.
            if (age > FUSE) {
                it.remove();
                detonate(trap, null);
                continue;
            }
            Player victim = null;
            for (Player p : Fx.playersNear(trap.at, 1.6)) {
                victim = p;
                break;
            }
            if (victim == null) continue;
            it.remove();
            detonate(trap, victim);
        }
    }

    private void disarm(Trap trap) {
        if (trap.mark == null) return;
        spawned.remove(trap.mark);
        Fx.safeRemove(trap.mark);
    }

    /** El cepo salta: explosion con muy buen radio y a todo el que pille. */
    private void detonate(Trap trap, Player who) {
        Location l = trap.at.clone().add(0, 0.4, 0);
        disarm(trap);
        blast(l, who);
    }

    /** El reventon en si, en el punto que sea: en el suelo o en el aire al llegar. */
    private void blast(Location l, Player who) {
        Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l, 2);
        Compat.spawn(world(), Compat.GUST_EMITTER_LARGE, l, 1, 0, 0, 0, 0);
        Compat.spawn(world(), Compat.DUST_PILLAR, l, 30, 1.2, 0.2, 1.2, 0,
                Material.STONE.createBlockData());
        Compat.spawn(world(), Compat.LAVA, l, 12, 0.6, 0.3, 0.6, 0);
        soundAt(l, "entity.generic.explode", 1.9f, 0.6f);
        soundAt(l, "block.iron_trapdoor.open", 1.4f, 0.5f);
        if (who != null) {
            who.sendActionBar(Component.text("¡TRAMPA!", NamedTextColor.RED, TextDecoration.BOLD));
        }

        for (Player p : Fx.playersNear(l, 7.0)) {
            double d = p.getLocation().distance(l);
            hit(p, Math.max(9, 30 - d * 2.4) * damageBonus);
            push(p, p.getLocation().toVector().subtract(l.toVector())
                    .normalize().multiply(1.2).setY(0.7));
            Compat.apply(p, "slowness", 60, 1);
        }
    }

    // -------------------------------------------------------------------- ambiente

    @Override
    protected void ambient() {
        if (!alive()) return;
        tickTraps();
        keepHostile();

        if (ticks() % 5 == 0) {
            // Le humea el sombrero: es un piglin, arde por dentro.
            Compat.spawn(world(), Compat.CAMPFIRE_COSY_SMOKE, boss.getEyeLocation().add(0, 0.4, 0), 1,
                    0.15, 0.05, 0.15, 0.005);
        }
    }

    /**
     * En fase 1 y 2 el Cazador MANTIENE LA DISTANCIA: si te le acercas, retrocede
     * mientras dispara. Es lo que obliga a perseguirlo por el campo de trampas.
     */
    private void keepHostile() {
        if (ticks() % 5 != 0) return;
        Player t = Fx.nearest(boss.getLocation(), plugin.settings().participationRadius());
        if (t == null) return;
        if (boss instanceof org.bukkit.entity.Mob m) {
            LivingEntity current = m.getTarget();
            if (current == null || !current.isValid() || current.isDead()) m.setTarget(t);
        }
        face(t.getEyeLocation());

        double d2 = boss.getLocation().distanceSquared(t.getLocation());
        if (phase() < 3 && d2 < 25) {
            Vector away = boss.getLocation().toVector().subtract(t.getLocation().toVector()).setY(0);
            if (away.lengthSquared() > 0.01) {
                boss.setVelocity(away.normalize().multiply(0.5).setY(0.1));
                Compat.spawn(world(), Compat.SMALL_GUST, boss.getLocation(), 2, 0.2, 0.1, 0.2, 0);
            }
        }

        // El pulso del cazador: sin esperar a que le toque una habilidad, elige el arma
        // por la DISTANCIA y dispara. Antes se quedaba largos ratos mirando entre
        // habilidad y habilidad, que es justo lo contrario de un genio de las armas.
        if (ticks() % 20 != 0) return;
        if (d2 > 100) {
            // Lejos: ballesta o arco, y una rafaga a varios a la vez.
            if (!holding(Material.CROSSBOW) && !holding(Material.BOW)) {
                drawWeapon(random.nextBoolean() ? Material.CROSSBOW : Material.BOW);
            }
            List<Player> pool = targets(60);
            int fired = 0;
            for (Player p : pool) {
                if (fired++ >= 3) break;
                if (!boss.hasLineOfSight(p)) continue;
                shoot(p, 2.8, 6);
            }
            if (fired > 0) soundAt(loc(), "item.crossbow.shoot", 1.3f, 1.0f);
            return;
        }
        if (d2 > 25) {
            // Media distancia: la lanza, que tiene alcance de sobra.
            if (!holding(SPEAR)) drawWeapon(SPEAR);
            if (boss.getLocation().distanceSquared(t.getLocation()) < 64) jab(t);
            return;
        }
        // Encima: hacha o espada, y rapido.
        if (!holding(AXE) && !holding(BLADE)) drawWeapon(random.nextBoolean() ? AXE : BLADE);
        hit(t, 8 * damageBonus);
        Compat.spawn(world(), Compat.SWEEP_ATTACK, t.getLocation().add(0, 1, 0), 1);
        soundAt(t.getLocation(), "entity.player.attack.sweep", 1.1f, 1.0f);
    }

    private static final Material SPEAR = pick("NETHERITE_SPEAR", "TRIDENT", "IRON_SPEAR");
    private static final Material AXE = pick("NETHERITE_AXE", "DIAMOND_AXE", "IRON_AXE");
    private static final Material BLADE = pick("NETHERITE_SWORD", "DIAMOND_SWORD", "IRON_SWORD");

    private boolean holding(Material m) {
        EntityEquipment eq = boss.getEquipment();
        return eq != null && eq.getItemInMainHand().getType() == m;
    }

    /** Un lanzazo corto de mantenimiento: alcanza a todo lo que este en la linea. */
    private void jab(Player target) {
        Vector dir = target.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        final Vector run = dir.normalize();
        for (double d = 1.5; d < 6.5; d += 1.0) {
            Location tip = boss.getEyeLocation().add(run.clone().multiply(d));
            Compat.spawn(world(), Compat.ENCHANTED_HIT, tip, 2, 0.1, 0.1, 0.1, 0.02);
            for (Player p : Fx.playersNear(tip, 1.6)) {
                hit(p, 9 * damageBonus);
                push(p, run.clone().multiply(0.35).setY(0.12));
            }
        }
        soundAt(loc(), "item.trident.hit", 1.1f, 1.1f);
    }

    @Override
    public void cleanup() {
        for (Trap trap : traps) disarm(trap);
        traps.clear();
        super.cleanup();
    }

    // --------------------------------------------------------------- cambio de fase

    @Override
    protected void onPhaseChange(int from, int to) {
        if (event.bars() != null) event.bars().flash(from);
        if (to == 2) trapperPhase();
        if (to == 3) spearPhase();
    }

    /** FASE I -> II. Deja de disparar tanto y empieza a sembrar el suelo. */
    private void trapperPhase() {
        if (!alive()) return;
        busyFor(50);
        drawWeapon(Material.TRIPWIRE_HOOK);
        soundAt(loc(), "block.tripwire.attach", 1.6f, 0.6f);
        titleNear(Component.text("FASE II", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text("Mira donde pisas", NamedTextColor.GRAY));
        damageBonus = 1.2;
        Compat.setAttribute(boss, "movement_speed", 0.38);
        layTraps();
    }

    /** FASE III. Tira lo de lejos y saca la lanza: ahora quiere verte la cara. */
    private void spearPhase() {
        if (!alive()) return;
        busyFor(60);
        Location l = boss.getLocation();
        drawWeapon(pick("NETHERITE_SPEAR", "TRIDENT", "NETHERITE_SWORD"));
        soundAt(l, "entity.piglin_brute.angry", 1.8f, 0.7f);
        soundAt(l, "item.trident.return", 1.4f, 0.8f);
        titleNear(Component.text("FASE III", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Ha tirado el arco", NamedTextColor.GRAY));

        damageBonus = 1.45;
        Compat.setAttribute(boss, "attack_damage", 20);
        Compat.setAttribute(boss, "attack_speed", 2.6);
        Compat.setAttribute(boss, "movement_speed", 0.44);
        Compat.spawn(world(), Compat.CRIT, l.clone().add(0, 1.4, 0), 30, 0.5, 0.6, 0.5, 0.3);

        hurlTraps();
    }

    /**
     * Al entrar en la ultima fase RECOGE EL CAMPO DE MINAS Y TE LO TIRA ENCIMA.
     *
     * Todo lo que sembro y nadie llego a pisar sale volando hacia sus enemigos. Es la
     * traduccion literal de tirar las armas de distancia: ya no espera a que caigas en
     * la trampa, te la lleva el.
     *
     * Y sale DISPARADO, que es como se pidio: la tanda entera despega casi a la vez —un
     * tick entre mina y mina, lo justo para que no sea un unico fogonazo— y cada una
     * vuela a {@link #HURL_SPEED} bloques por tick. Con la version lenta parecian
     * flotar hacia ti y se perdia todo el susto.
     */
    private void hurlTraps() {
        if (traps.isEmpty()) return;
        List<Trap> flying = new ArrayList<>(traps);
        traps.clear();

        titleNear(Component.text("RECOGE LOS CEPOS", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Te los tira encima", NamedTextColor.GRAY));
        soundAt(loc(), "block.chain.break", 1.6f, 0.6f);
        soundAt(loc(), "entity.wind_charge.wind_burst", 1.8f, 0.7f);

        int delay = 0;
        for (Trap trap : flying) {
            final Trap mine = trap;
            later(delay, () -> hurl(mine));
            delay += 1;
        }
    }

    /** Bloques por tick de una mina lanzada. A 2,4 cruza diez bloques en cuatro ticks. */
    private static final double HURL_SPEED = 2.4;

    /** Una mina en vuelo: sube, va a por alguien y revienta al llegar o al agotarse. */
    private void hurl(Trap trap) {
        BlockDisplay mark = trap.mark;
        if (mark == null || !mark.isValid()) return;
        Player victim = randomTarget();
        if (victim == null) {
            // Sin nadie a quien tirarsela, revienta donde estaba: no se queda de adorno.
            detonate(trap, null);
            return;
        }
        Location from = trap.at.clone().add(0, 0.45, 0);
        soundAt(from, "entity.wind_charge.wind_burst", 1.2f, 1.4f);

        animate(50, tick -> {
            if (!mark.isValid()) throw Stop.now();
            Location here = mark.getLocation();
            Player aim = Fx.isFightable(victim) ? victim : Fx.nearest(here, 40);
            if (aim == null) {
                blast(here, null);
                spawned.remove(mark);
                Fx.safeRemove(mark);
                throw Stop.now();
            }
            Location to = aim.getLocation().add(0, 1.0, 0);
            Vector step = to.toVector().subtract(here.toVector());
            double dist = step.length();

            if (dist < HURL_SPEED || tick >= 48) {
                blast(here, aim);
                spawned.remove(mark);
                Fx.safeRemove(mark);
                throw Stop.now();
            }
            Location next = here.clone().add(step.normalize().multiply(Math.min(HURL_SPEED, dist)));
            // El primer tick sale hacia arriba, para que se vea despegar del suelo.
            if (tick < 2) next.add(0, 0.5, 0);
            mark.teleport(next);
            // Estela: a esta velocidad hay que pintar el camino entero, que si no se ve
            // la mina saltando de sitio en sitio en vez de volando.
            Fx.beam(here, next, 0.5, p -> {
                Compat.spawn(world(), Compat.ELECTRIC_SPARK, p, 2, 0.05, 0.05, 0.05, 0.02);
                Compat.spawn(world(), Compat.SMALL_FLAME, p, 1, 0.04, 0.04, 0.04, 0.01);
            });
            if (tick % 3 == 0) soundAt(here, "entity.creeper.primed", 0.7f, 1.9f);
        }, null);
    }

    // ---------------------------------------------------------------------- muerte

    @Override
    public void onDeath() {
        Location l = loc();
        soundAt(l, "entity.piglin_brute.death", 1.7f, 0.7f);

        for (Trap trap : new ArrayList<>(traps)) disarm(trap);
        traps.clear();

        animate(70, tick -> {
            if (tick % 12 == 0) {
                Compat.spawn(world(), Compat.CAMPFIRE_COSY_SMOKE, l.clone().add(0, 1, 0), 10, 0.4, 0.5, 0.4, 0.02);
                soundAt(l, "item.armor.equip_leather", 1.0f, 0.6f);
            }
        }, () -> {
            Compat.spawn(world(), Compat.ITEM, l.clone().add(0, 1, 0), 24, 0.5, 0.5, 0.5, 0.12,
                    new ItemStack(Material.CROSSBOW));
            Compat.spawn(world(), Compat.SMALL_GUST, l.clone().add(0, 1, 0), 12, 0.5, 0.5, 0.5, 0);
            soundAt(l, "item.crossbow.shoot", 1.2f, 0.6f);
            broadcastNear(Component.text("Se le cae el sombrero.", ACCENT));
        });
    }

    // ============================================================== HABILIDADES ==

    /** 1. Andanada de Ballesta: tres saetas seguidas al que tenga a tiro. */
    public void crossbowVolley() {
        if (!alive()) return;
        List<Player> pool = targets(60);
        if (pool.isEmpty()) return;
        drawWeapon(Material.CROSSBOW);
        soundAt(loc(), "item.crossbow.loading_start", 1.4f, 0.9f);
        broadcastNear(Component.text("Carga la ballesta.", ACCENT));

        // Seis saetas REPARTIDAS entre todos los que tenga a tiro. Vaciar el cargador
        // en una sola cabeza no sirve de nada cuando vienen en grupo.
        for (int i = 0; i < 6; i++) {
            final int idx = i;
            later(12 + i * 5, () -> {
                if (!alive()) return;
                List<Player> now = targets(60);
                if (now.isEmpty()) return;
                Player p = now.get(idx % now.size());
                if (!Fx.isFightable(p)) return;
                shoot(p, 2.8, 7);
                soundAt(loc(), "item.crossbow.shoot", 1.3f, 1.0f);
            });
        }
    }

    /** 2. Lluvia de Flechas: apunta arriba y caen sobre las marcas. */
    public void arrowRain() {
        if (!alive()) return;
        List<Player> victims = targets(34);
        if (victims.isEmpty()) return;
        drawWeapon(Material.BOW);
        soundAt(loc(), "item.crossbow.quick_charge_3", 1.4f, 0.7f);
        broadcastNear(Component.text("Dispara al cielo.", ACCENT));

        for (Player victim : victims) {
            Location mark = Fx.ground(victim.getLocation(), 4);
            animate(60, tick -> {
                if (tick < 26) {
                    Fx.telegraph(world(), mark, 2.6, 0xC8A165);
                    return;
                }
                if (tick != 26) return;
                for (int i = 0; i < 4; i++) {
                    Location from = mark.clone().add(
                            (Math.random() - 0.5) * 3, 16, (Math.random() - 0.5) * 3);
                    try {
                        Arrow arrow = world().spawnArrow(from, new Vector(0, -1, 0), 2.2f, 1.5f);
                        arrow.setShooter(boss);
                        arrow.setDamage(5 * damageBonus);
                        arrow.setPersistent(false);
                        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                        Tags.markMinion(arrow, ID);
                        Tags.markEvent(arrow, event.id());
                    } catch (Throwable ignored) {
                    }
                }
                soundAt(mark, "entity.arrow.shoot", 1.2f, 0.8f);
            }, null);
        }
    }

    /** 3. Saeta Perforante: un disparo cargado que atraviesa a quien pille. */
    public void piercingBolt() {
        if (!alive()) return;
        Player target = Fx.farthest(loc(), plugin.settings().participationRadius());
        if (target == null) target = randomTarget();
        if (target == null) return;
        final Player victim = target;
        drawWeapon(Material.CROSSBOW);
        soundAt(loc(), "item.crossbow.loading_middle", 1.5f, 0.6f);

        animate(40, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < 30) {
                if (!Fx.isFightable(victim)) throw Stop.now();
                Fx.beam(boss.getEyeLocation(), victim.getEyeLocation(), 1.2, p ->
                        Compat.spawn(world(), Compat.WAX_ON, p, 1, 0.02, 0.02, 0.02, 0));
                return;
            }
            if (tick != 30) return;
            Arrow arrow = shoot(victim, 3.4, 12);
            if (arrow != null) {
                try {
                    arrow.setPierceLevel(4);
                    arrow.setCritical(true);
                } catch (Throwable ignored) {
                }
            }
            soundAt(loc(), "item.crossbow.shoot", 1.6f, 0.7f);
        }, null);
    }

    private Arrow shoot(Player target, double speed, double damage) {
        try {
            Vector dir = target.getEyeLocation().toVector()
                    .subtract(boss.getEyeLocation().toVector()).normalize().multiply(speed);
            Arrow arrow = boss.launchProjectile(Arrow.class, dir);
            arrow.setDamage(damage * damageBonus);
            arrow.setPersistent(false);
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            Tags.markMinion(arrow, ID);
            Tags.markEvent(arrow, event.id());
            return arrow;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 4. Cepo: siembra otra tanda de trampas donde esta. */
    public void trapField() {
        drawWeapon(Material.TRIPWIRE_HOOK);
        layTraps();
    }

    /** 5. Cepo Dirigido: pone la trampa justo bajo los pies de alguien. */
    public void aimedTrap() {
        if (!alive()) return;
        List<Player> victims = targets(24);
        if (victims.isEmpty()) return;
        drawWeapon(Material.TRIPWIRE_HOOK);
        soundAt(loc(), "block.tripwire.attach", 1.4f, 1.0f);
        broadcastNear(Component.text("Te pone una debajo.", ACCENT));

        for (Player victim : victims) {
            Location spot = Fx.ground(victim.getLocation(), 4);
            later(10, () -> {
                if (!alive()) return;
                // Se arma con retardo: si no, revienta en el mismo tick que se pone.
                traps.add(new Trap(spot.clone(), plantMine(spot), ticks() + 30));
            });
        }
    }

    /** 6. Retirada Calculada: salta hacia atras y deja una trampa donde estaba. */
    public void calculatedRetreat() {
        if (!alive()) return;
        Location where = Fx.ground(boss.getLocation(), 4);
        Vector back = boss.getLocation().getDirection().setY(0);
        if (back.lengthSquared() < 0.01) back = new Vector(1, 0, 0);
        boss.setVelocity(back.normalize().multiply(-1.1).setY(0.45));
        soundAt(where, "entity.piglin_brute.step", 1.4f, 1.2f);
        Compat.spawn(world(), Compat.SMALL_GUST, where, 8, 0.4, 0.2, 0.4, 0);

        later(6, () -> {
            if (!alive()) return;
            traps.add(new Trap(where.clone(), plantMine(where), ticks()));
            soundAt(where, "block.tripwire.click_on", 1.1f, 1.3f);
        });
    }

    /** 7. Estocada de Lanza: la lanza por delante, en linea y con mucho alcance. */
    public void spearThrust() {
        if (!alive()) return;
        Player target = randomTarget();
        if (target == null) return;
        drawWeapon(pick("NETHERITE_SPEAR", "TRIDENT", "IRON_SWORD"));
        Vector dir = target.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        final Vector run = dir.normalize();
        java.util.Set<UUID> pierced = new java.util.HashSet<>();

        soundAt(loc(), "item.trident.riptide_1", 1.5f, 0.8f);
        broadcastNear(Component.text("Baja la lanza.", ACCENT));

        animate(44, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < 18) {
                for (double d = 1.5; d < 9; d += 0.9) {
                    Compat.spawn(world(), Compat.CRIT, boss.getEyeLocation().add(run.clone().multiply(d)), 1,
                            0.05, 0.05, 0.05, 0);
                }
                return;
            }
            boss.setVelocity(run.clone().multiply(0.95).setY(boss.getVelocity().getY()));
            Location tip = boss.getEyeLocation().add(run.clone().multiply(2.2));
            Compat.spawn(world(), Compat.ENCHANTED_HIT, tip, 3, 0.15, 0.15, 0.15, 0.05);
            for (Player p : Fx.playersNear(tip, 3.0)) {
                if (!pierced.add(p.getUniqueId())) continue;
                hit(p, 20 * damageBonus);
                push(p, run.clone().multiply(1.0).setY(0.35));
                soundAt(p.getLocation(), "item.trident.hit", 1.4f, 0.8f);
            }
        }, null);
    }

    /** 8. Hachazo Descendente: cambia al hacha y parte el suelo delante. */
    public void axeCleave() {
        if (!alive()) return;
        drawWeapon(pick("NETHERITE_AXE", "DIAMOND_AXE", "IRON_AXE"));
        Vector face = boss.getLocation().getDirection().setY(0);
        if (face.lengthSquared() < 0.01) face = new Vector(1, 0, 0);
        final Vector dir = face.normalize();
        Location c = boss.getLocation();
        soundAt(c, "entity.player.attack.strong", 1.6f, 0.7f);

        animate(40, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < 20) {
                for (double d = 1; d < 8; d += 1.0) {
                    Location g = Fx.ground(c.clone().add(dir.clone().multiply(d)), 4);
                    Compat.spawn(world(), Compat.DUST_PILLAR, g.clone().add(0, 0.15, 0), 1, 0.2, 0.05, 0.2, 0,
                            Material.STONE.createBlockData());
                }
                return;
            }
            if (tick != 20) return;
            soundAt(c, "item.axe.strip", 1.6f, 0.5f);
            for (double d = 1; d < 8; d += 0.8) {
                Location g = Fx.ground(c.clone().add(dir.clone().multiply(d)), 4);
                Compat.spawn(world(), Compat.BLOCK, g.clone().add(0, 0.3, 0), 10, 0.3, 0.2, 0.3, 0.05,
                        Material.STONE.createBlockData());
                for (Player p : Fx.playersNear(g, 2.0)) {
                    hit(p, 17 * damageBonus);
                    push(p, dir.clone().multiply(0.8).setY(0.4));
                }
            }
        }, null);
    }

    /** 9. Danza de Espada: cambia a la espada y suelta una tanda rapida. */
    public void bladeDance() {
        if (!alive()) return;
        drawWeapon(pick("NETHERITE_SWORD", "DIAMOND_SWORD", "IRON_SWORD"));
        soundAt(loc(), "entity.player.attack.sweep", 1.5f, 1.1f);
        broadcastNear(Component.text("Saca la espada.", ACCENT));

        for (int i = 0; i < 6; i++) {
            later(i * 6, () -> {
                if (!alive()) return;
                // Cada corte de la danza barre a TODOS los que esten al alcance.
                for (Player near : targets(4.0)) {
                    hit(near, 7 * damageBonus);
                    Compat.spawn(world(), Compat.SWEEP_ATTACK, near.getLocation().add(0, 1, 0), 1);
                    Compat.spawn(world(), Compat.CRIT, near.getLocation().add(0, 1, 0), 8, 0.25, 0.3, 0.25, 0.2);
                    soundAt(near.getLocation(), "entity.player.attack.sweep", 1.1f, 1.0f + random.nextFloat() * 0.3f);
                }
            });
        }
    }

    /** 10. Marcar la Presa: elige a uno y le pega mucho mas fuerte mientras dure. */
    public void markPrey() {
        if (!alive()) return;
        List<Player> pool = targets(40);
        if (pool.isEmpty()) return;
        for (int i = 1; i < Math.min(3, pool.size()); i++) markOne(pool.get(i));
        Player target = pool.get(0);
        soundAt(loc(), "entity.piglin_brute.angry", 1.5f, 1.1f);
        target.sendActionBar(Component.text("El Cazador te ha marcado.",
                NamedTextColor.RED, TextDecoration.BOLD));
        titleNear(Component.text("PRESA MARCADA", ACCENT, TextDecoration.BOLD),
                Component.text(target.getName(), NamedTextColor.GRAY));
        if (boss instanceof org.bukkit.entity.Mob m) m.setTarget(target);

        animate(200, tick -> {
            if (!alive() || !Fx.isFightable(target)) throw Stop.now();
            if (tick % 10 == 0) {
                Compat.spawn(world(), Compat.WAX_ON, target.getLocation().add(0, 2.4, 0), 3, 0.2, 0.1, 0.2, 0);
            }
            if (tick % 40 == 0 && boss.getLocation().distanceSquared(target.getLocation()) < 16) {
                hit(target, 9 * damageBonus);
                soundAt(target.getLocation(), "entity.player.attack.crit", 1.2f, 0.9f);
            }
        }, null);
    }

    /** 11. Red de Cepos: un cerco de trampas alrededor del grupo. */
    public void trapRing() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 5);
        soundAt(c, "block.chain.place", 1.5f, 0.7f);
        broadcastNear(Component.text("Cierra el cerco.", ACCENT));

        int count = 10;
        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count;
            Location spot = Fx.ground(c.clone().add(Math.cos(a) * 9, 1, Math.sin(a) * 9), 6);
            later(i * 3, () -> {
                if (!alive()) return;
                traps.add(new Trap(spot.clone(), plantMine(spot), ticks()));
            });
        }
    }

    /** 12. Cambio de Arma: se replantea la pelea y saca otra cosa. */
    public void switchWeapon() {
        if (!alive()) return;
        Material[] arsenal = {
                Material.CROSSBOW,
                Material.BOW,
                pick("NETHERITE_SPEAR", "TRIDENT"),
                pick("NETHERITE_AXE", "IRON_AXE"),
                pick("NETHERITE_SWORD", "IRON_SWORD")};
        weapon = (weapon + 1 + random.nextInt(arsenal.length - 1)) % arsenal.length;
        drawWeapon(arsenal[weapon]);
        broadcastNear(Component.text("Cambia de arma.", ACCENT));
        Compat.spawn(world(), Compat.ENCHANT, boss.getEyeLocation(), 20, 0.4, 0.4, 0.4, 0.6);
    }

    /** Marca a uno mas, sin el titulo ni el aviso grande. */
    private void markOne(Player victim) {
        victim.sendActionBar(Component.text("El Cazador te ha marcado.",
                NamedTextColor.RED, TextDecoration.BOLD));
        animate(200, tick -> {
            if (!alive() || !Fx.isFightable(victim)) throw Stop.now();
            if (tick % 10 == 0) {
                Compat.spawn(world(), Compat.WAX_ON, victim.getLocation().add(0, 2.4, 0), 3, 0.2, 0.1, 0.2, 0);
            }
            if (tick % 40 == 0 && boss.getLocation().distanceSquared(victim.getLocation()) < 16) {
                hit(victim, 9 * damageBonus);
            }
        }, null);
    }

    // ------------------------------------------------------------------ mensajeria

    private void broadcastNear(Component message) {
        Component line = Component.text("✦ ", ACCENT)
                .append(Component.text("El Cazador  ", ACCENT, TextDecoration.BOLD))
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
