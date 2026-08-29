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
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.CaveSpider;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Spider;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ÁRAGON, la decimocuarta anomalia.
 *
 * Una arana DESCOMUNAL y lentisima. Ese es el diseño entero: Áragon casi no pelea. Se
 * arrastra, teje y pone huevos; quien te muerde son sus hijas, y son muchas.
 *
 *  - CRIAS DIMINUTAS Y A MONTONES. Salen a camadas de doce, del tamaño de un puño
 *    (atributo scale), corriendo mas rapido de lo que se puede retroceder. Sueltas no
 *    son nada; en enjambre te comen.
 *  - LOS HUEVOS. Bolas blancas plantadas por la arena. Si se rompen a tiempo, no pasa
 *    nada. Si no, ECLOSIONAN y sale otra camada. Son la unica forma de que el enjambre
 *    deje de crecer, y por eso hay que repartirse: unos al jefe, otros a los huevos.
 *  - LA TELA. Telaraña de verdad, la que frena. Va a la lista blanca de siempre y se
 *    devuelve entera al cerrar el evento.
 *
 * A proposito lleva MUY POCAS particulas sueltas: lo que tiene que llenar la pantalla
 * son las patas, no el confeti.
 */
public final class Aragon extends BossFight {

    public static final String ID = "aragon";
    public static final TextColor ACCENT = TextColor.color(0x6B5B7B);

    /** Tope de esbirros vivos a la vez. Sin esto, veinte jugadores tiran el servidor. */
    private static final int MAX_BROOD = 110;
    /** Tope de bloques de tela puestos a la vez. */
    private static final int MAX_WEB = 400;

    /** Los huevos puestos: donde estan, su marca y cuando eclosionan. */
    private final List<Egg> eggs = new ArrayList<>();
    /** La tela tejida, con lo que habia debajo para devolverlo. */
    private final Map<Location, BlockData> webs = new LinkedHashMap<>();
    private final Map<UUID, Integer> broodIds = new HashMap<>();

    private double damageBonus = 1.0;

    public Aragon(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        super(plugin, event, where);
        abilities.addAll(plugin.registry().aragonAbilities());
    }

    @Override
    public String bossName() {
        return "Áragon";
    }

    // ------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        Location spot = arena.clone();

        boss = world().spawn(spot, Spider.class, s -> {
            s.setPersistent(true);
            s.setRemoveWhenFarAway(false);
            s.setCanPickupItems(false);
            s.customName(Component.text("✦ ", ACCENT)
                    .append(Component.text("Áragon", ACCENT, TextDecoration.BOLD)));
            s.setCustomNameVisible(true);
        });

        Compat.setAttribute(boss, "attack_damage", 16);
        Compat.setAttribute(boss, "armor", 16);
        Compat.setAttribute(boss, "knockback_resistance", 1.0);
        Compat.setAttribute(boss, "follow_range", 72);
        // Lentisima a proposito: es una mole que se arrastra, no algo que te persigue.
        Compat.setAttribute(boss, "movement_speed", 0.11);
        // 4.5 la dejaba flotando: con una caja de golpe tan enorme el servidor la
        // expulsa hacia arriba en cuanto roza un bloque, y se queda a medio metro del
        // suelo. A 2.8 sigue siendo una mole y pisa la tierra.
        Compat.setAttribute(boss, "scale", 2.8);
        Compat.setAttribute(boss, "step_height", 1.5);
        applyHealth(plugin.registry().scaledHealth(plugin.registry().aragon(), targets(96).size()));
        boss.setMaximumNoDamageTicks(6);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        Glow.apply(boss, event.type().glowColor());

        arrivalAnimation(spot);
        later(60, () -> brood(24));
    }

    private void arrivalAnimation(Location spot) {
        boss.setInvulnerable(true);
        busyFor(80);
        soundAt(spot, "entity.spider.ambient", 1.8f, 0.4f);

        animate(80, tick -> {
            if (tick % 10 == 0) {
                soundAt(spot, "entity.spider.step", 1.4f, 0.4f);
                Compat.spawn(world(), Compat.ITEM_COBWEB, spot.clone().add(0, 1, 0), 6, 1.5, 0.5, 1.5, 0);
            }
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            soundAt(spot, "entity.spider.ambient", 2.0f, 0.3f);
            for (Player p : Fx.viewersNear(spot, 90)) {
                p.showTitle(Title.title(
                        Component.text("✦ ANOMALIA ✦", ACCENT, TextDecoration.BOLD),
                        Component.text("Áragon  ·  ella no corre, corren sus hijas", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1800), Duration.ofMillis(600))));
            }
        });
    }

    // ------------------------------------------------------------------ LA CAMADA

    private int broodAlive() {
        broodIds.keySet().removeIf(id -> {
            org.bukkit.entity.Entity e = plugin.getServer().getEntity(id);
            return e == null || !e.isValid();
        });
        return broodIds.size();
    }

    /**
     * Una camada de crias diminutas. El tamaño sale del atributo scale, asi que son
     * aranas normales encogidas: se mueven igual, muerden igual, pero caben a docenas.
     */
    private void brood(int count) {
        if (!alive()) return;
        int room = MAX_BROOD - broodAlive();
        if (room <= 0) return;
        int n = Math.min(count, room);
        Location c = boss.getLocation();

        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / Math.max(1, n) + random.nextDouble() * 0.5;
            double r = 1.5 + random.nextDouble() * 3;
            Location sl = Fx.ground(c.clone().add(Math.cos(a) * r, 1, Math.sin(a) * r), 5);
            try {
                if (random.nextInt(10) < 3) {
                    // Cria JOVEN: el escalon que faltaba entre la diminuta y la
                    // guardiana. Misma camada, otra silueta: el ataque se ve vivo.
                    Spider young = world().spawn(sl, Spider.class, s -> {
                        s.setPersistent(false);
                        s.setRemoveWhenFarAway(true);
                        Compat.setAttribute(s, "scale", 0.62 + random.nextDouble() * 0.18);
                        Compat.setAttribute(s, "max_health", 14);
                        Compat.setAttribute(s, "attack_damage", 5);
                        Compat.setAttribute(s, "movement_speed", 0.36);
                        s.setHealth(14);
                    });
                    young.customName(Component.text("Esbirro", ACCENT));
                    young.setCustomNameVisible(false);
                    markMinion(young);
                    broodIds.put(young.getUniqueId(), 1);
                } else {
                    CaveSpider baby = world().spawn(sl, CaveSpider.class, s -> {
                        s.setPersistent(false);
                        s.setRemoveWhenFarAway(true);
                        // Jitter de tamaño: ninguna cria es identica a la de al lado.
                        Compat.setAttribute(s, "scale", 0.28 + random.nextDouble() * 0.14);
                        Compat.setAttribute(s, "max_health", 8);
                        Compat.setAttribute(s, "attack_damage", 3);
                        Compat.setAttribute(s, "movement_speed", 0.42);
                        s.setHealth(8);
                    });
                    baby.customName(Component.text("Esbirro", ACCENT));
                    baby.setCustomNameVisible(false);
                    markMinion(baby);
                    broodIds.put(baby.getUniqueId(), 1);
                }
            } catch (Throwable ignored) {
            }
        }
        soundAt(c, "entity.spider.step", 1.2f, 1.8f);
    }

    /** Una guardiana: arana normal, grande y con mala leche. Menos, pero pegan. */
    private void guardian() {
        if (!alive() || broodAlive() >= MAX_BROOD) return;
        Location sl = Fx.ground(boss.getLocation().clone().add(
                (random.nextDouble() - 0.5) * 8, 1, (random.nextDouble() - 0.5) * 8), 5);
        try {
            Spider guard = world().spawn(sl, Spider.class, s -> {
                s.setPersistent(false);
                Compat.setAttribute(s, "scale", 1.3);
                Compat.setAttribute(s, "max_health", 34);
                Compat.setAttribute(s, "attack_damage", 8);
                s.setHealth(34);
            });
            guard.customName(Component.text("Esbirro Mayor", ACCENT));
            markMinion(guard);
            broodIds.put(guard.getUniqueId(), 2);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ LOS HUEVOS

    /** Un huevo: la bola blanca, el tick en que eclosiona y si ya reviento. */
    private static final class Egg {
        final Location at;
        final BlockDisplay shell;
        final long hatchAt;
        boolean broken;

        Egg(Location at, BlockDisplay shell, long hatchAt) {
            this.at = at;
            this.shell = shell;
            this.hatchAt = hatchAt;
        }
    }

    /**
     * Pone huevos. Son bolas blancas bien visibles: romperlos a tiempo es la unica
     * forma de que el enjambre no crezca sin parar, y por eso obligan a repartirse.
     */
    public void layEggs() {
        if (!alive()) return;
        int count = 3 + random.nextInt(3);
        Location c = Fx.ground(boss.getLocation(), 5);
        soundAt(c, "entity.spider.ambient", 1.6f, 1.4f);
        broadcastNear(Component.text("Pone huevos. Rompanlos.", ACCENT));

        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count + random.nextDouble() * 0.6;
            double d = 4 + random.nextDouble() * 8;
            Location spot = Fx.ground(c.clone().add(Math.cos(a) * d, 1, Math.sin(a) * d), 6);
            BlockDisplay shell = Fx.blockDisplay(world(), spot.clone().add(0, 0.55, 0),
                    Material.WHITE_CONCRETE, 0.85f);
            markMinion(shell);
            eggs.add(new Egg(spot.clone(), shell, ticks() + 400));
            Compat.spawn(world(), Compat.ITEM_COBWEB, spot.clone().add(0, 0.6, 0), 6, 0.3, 0.3, 0.3, 0);
            soundAt(spot, "block.slime_block.place", 1.1f, 1.4f);
        }
    }

    /**
     * Vigila los huevos. Laten segun se acercan a eclosionar —el unico efecto que
     * tienen, para que se distingan del resto de la escena sin llenarlo de particulas—
     * y al cumplir el plazo se abren y sale otra camada.
     */
    private void tickEggs() {
        if (eggs.isEmpty()) return;
        Iterator<Egg> it = eggs.iterator();
        while (it.hasNext()) {
            Egg egg = it.next();
            if (egg.broken || egg.shell == null || !egg.shell.isValid()) {
                it.remove();
                continue;
            }
            long left = egg.hatchAt - ticks();
            if (left > 0) {
                // El latido se acelera segun se acerca: se oye que va a pasar algo.
                int beat = (int) Math.max(4, left / 12);
                if (ticks() % beat == 0) {
                    soundAt(egg.at, "block.slime_block.hit", 0.8f, 0.7f + (400 - left) / 400.0f);
                }
                continue;
            }
            it.remove();
            hatch(egg);
        }
    }

    private void hatch(Egg egg) {
        Location l = egg.at.clone().add(0, 0.5, 0);
        if (egg.shell != null) {
            spawned.remove(egg.shell);
            Fx.safeRemove(egg.shell);
        }
        Compat.spawn(world(), Compat.EGG_CRACK, l, 24, 0.4, 0.3, 0.4, 0.05);
        Compat.spawn(world(), Compat.ITEM_COBWEB, l, 10, 0.4, 0.3, 0.4, 0);
        soundAt(l, "entity.slime.squish", 1.5f, 1.6f);
        soundAt(l, "entity.spider.ambient", 1.3f, 1.7f);

        int room = MAX_BROOD - broodAlive();
        int n = Math.min(16, Math.max(0, room));
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / Math.max(1, n);
            Location sl = Fx.ground(l.clone().add(Math.cos(a) * 1.2, 0, Math.sin(a) * 1.2), 4);
            try {
                CaveSpider baby = world().spawn(sl, CaveSpider.class, s -> {
                    s.setPersistent(false);
                    s.setRemoveWhenFarAway(true);
                    Compat.setAttribute(s, "scale", 0.26 + random.nextDouble() * 0.14);
                    Compat.setAttribute(s, "max_health", 6);
                    Compat.setAttribute(s, "attack_damage", 3);
                    Compat.setAttribute(s, "movement_speed", 0.45);
                    s.setHealth(6);
                });
                baby.setCustomNameVisible(false);
                markMinion(baby);
                broodIds.put(baby.getUniqueId(), 1);
            } catch (Throwable ignored) {
            }
        }
        broadcastNear(Component.text("Un huevo se ha abierto.", ACCENT));
    }

    /** Se puede reventar el huevo a golpes: el cascaron es una entidad marcada. */
    @Override
    public boolean onBlockBroken(Block block, Player who) {
        return false;
    }

    /**
     * Los huevos se rompen pegandoles. La marca es una entidad de dibujo y esas no
     * reciben golpes, asi que se comprueba la cercania cuando alguien pega al aire:
     * es lo que permite "romper el huevo" sin inventarse un bloque nuevo.
     */
    private void breakEggNear(Location where, Player who) {
        for (Egg egg : eggs) {
            if (egg.broken) continue;
            if (egg.at.distanceSquared(where) > 2.6 * 2.6) continue;
            egg.broken = true;
            if (egg.shell != null) {
                spawned.remove(egg.shell);
                Fx.safeRemove(egg.shell);
            }
            Location l = egg.at.clone().add(0, 0.5, 0);
            Compat.spawn(world(), Compat.EGG_CRACK, l, 16, 0.3, 0.3, 0.3, 0.04);
            soundAt(l, "block.glass.break", 1.3f, 1.2f);
            if (who != null) {
                who.sendActionBar(Component.text("Huevo reventado.", NamedTextColor.GREEN, TextDecoration.BOLD));
            }
            return;
        }
    }

    // -------------------------------------------------------------------- LA TELA

    /**
     * La mantiene pegada al suelo. Una arana trepa paredes y con este tamaño se queda
     * flotando en cuanto roza algo, que se ve fatal; si la pillamos en el aire sin
     * bloque debajo, se la empuja hacia abajo hasta que apoya las patas.
     */
    private void stayGrounded() {
        if (ticks() % 4 != 0 || !alive()) return;
        if (boss.isOnGround()) return;
        Location l = boss.getLocation();
        Location ground = Fx.ground(l, 6);
        if (l.getY() - ground.getY() < 0.35) return;
        boss.setVelocity(boss.getVelocity().setY(Math.min(-0.35, boss.getVelocity().getY())));
    }

    /** Solo teje sobre aire: nunca sustituye un bloque de nadie. */
    private void weaveAt(Location where) {
        if (webs.size() >= MAX_WEB) return;
        Block b = where.getBlock();
        if (b.getType() != Material.AIR && b.getType() != Material.CAVE_AIR) return;
        Location key = b.getLocation();
        if (webs.containsKey(key)) return;
        webs.put(key, b.getBlockData());
        b.setType(Material.COBWEB, false);
    }

    /** Devuelve el terreno: la tela es temporal, no como el jardin de Herbola. */
    private void clearWebs() {
        for (Map.Entry<Location, BlockData> e : webs.entrySet()) {
            try {
                Block b = e.getKey().getBlock();
                if (b.getType() == Material.COBWEB) b.setBlockData(e.getValue(), false);
            } catch (Throwable ignored) {
            }
        }
        webs.clear();
    }

    // -------------------------------------------------------------------- ambiente

    @Override
    protected void ambient() {
        if (!alive()) return;
        tickEggs();
        keepHostile();
        driveBrood();
        stayGrounded();

        // Muy poco efecto de ambiente a proposito: lo que llena la pantalla son las
        // crias, y si ademas se echa confeti no se ve nada.
        if (ticks() % 40 == 0) {
            Compat.spawn(world(), Compat.ITEM_COBWEB, boss.getLocation().add(0, 1.5, 0), 2, 1.2, 0.6, 1.2, 0);
        }
        if (ticks() % 120 == 0) soundAt(loc(), "entity.spider.ambient", 1.0f, 0.4f);

        // Reponer camada sin parar: la arena tiene que estar SIEMPRE llena de bichos.
        if (ticks() % 100 == 0 && broodAlive() < 45) brood(10);
    }

    private void keepHostile() {
        if (ticks() % 20 != 0) return;
        Player t = Fx.nearest(boss.getLocation(), plugin.settings().participationRadius());
        if (t == null) return;
        if (boss instanceof org.bukkit.entity.Mob m) {
            LivingEntity current = m.getTarget();
            if (current == null || !current.isValid() || current.isDead()) m.setTarget(t);
        }
        // Si alguien esta pegado a un huevo, cuenta como intento de romperlo.
        for (Player p : targets(30)) breakEggNear(p.getLocation(), null);
    }

    /**
     * Que la camada ATAQUE de verdad, y a montones.
     *
     * Las aranas de Minecraft son NEUTRALES de dia: sin esto, el enjambre se quedaba
     * paseando alrededor mientras el grupo pegaba tranquilamente a la madre. Aqui se
     * les renueva el objetivo a todas, se les quita el miedo a la luz y se empuja a las
     * que se quedan atras, que es lo que convierte "muchas aranas" en "te comen".
     */
    private void driveBrood() {
        if (ticks() % 20 != 0) return;
        List<Player> pool = targets(46);
        if (pool.isEmpty()) return;
        // Barajado: sin esto el reparto por indice empezaba siempre por el mismo
        // jugador y el enjambre acababa apilado sobre dos o tres cabezas.
        java.util.Collections.shuffle(pool, random);
        int i = 0;
        for (UUID id : new ArrayList<>(broodIds.keySet())) {
            org.bukkit.entity.Entity e = plugin.getServer().getEntity(id);
            if (!(e instanceof org.bukkit.entity.Mob m) || !m.isValid()) {
                broodIds.remove(id);
                continue;
            }
            Player target = pool.get(i++ % pool.size());
            LivingEntity current = m.getTarget();
            // La mitad de las veces se le renueva el objetivo aunque tenga uno: es lo
            // que impide que toda la camada muerda al que pego primero.
            if (current == null || !current.isValid() || current.isDead() || random.nextInt(2) == 0) {
                m.setTarget(target);
            }
            // Las que se descuelgan pegan un tiron para volver a la pelea.
            double d2 = m.getLocation().distanceSquared(target.getLocation());
            if (d2 > 144 && d2 < 90 * 90) {
                Vector to = target.getLocation().toVector().subtract(m.getLocation().toVector());
                if (to.lengthSquared() > 0.01) {
                    m.setVelocity(to.normalize().multiply(0.42).setY(0.24));
                }
            }
        }
    }

    @Override
    public void cleanup() {
        clearWebs();
        eggs.clear();
        broodIds.clear();
        super.cleanup();
    }

    /** Al morder, las crias envenenan un poco; el jefe agarra con tela. */
    @Override
    public void onDealtDamage(Player victim, org.bukkit.entity.Entity dealer) {
        if (dealer != null && dealer.equals(boss)) {
            weaveAt(victim.getLocation().getBlock().getLocation());
            Compat.apply(victim, "slowness", 60, 1);
            return;
        }
        Compat.apply(victim, "poison", 60, 0);
    }

    // --------------------------------------------------------------- cambio de fase

    @Override
    protected void onPhaseChange(int from, int to) {
        if (event.bars() != null) event.bars().flash(from);
        if (to == 2) {
            damageBonus = 1.2;
            titleNear(Component.text("FASE II", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("Pone mas huevos", NamedTextColor.GRAY));
            soundAt(loc(), "entity.spider.ambient", 1.6f, 0.5f);
            layEggs();
            brood(24);
        }
        if (to == 3) {
            damageBonus = 1.45;
            Compat.setAttribute(boss, "movement_speed", 0.15);
            titleNear(Component.text("FASE III", NamedTextColor.RED, TextDecoration.BOLD),
                    Component.text("Sale la camada entera", NamedTextColor.GRAY));
            layEggs();
            brood(34);
            guardian();
            guardian();
        }
    }

    // ---------------------------------------------------------------------- muerte

    /** Al caer la madre, la camada se deshace con ella. */
    @Override
    public void onDeath() {
        Location l = loc();
        soundAt(l, "entity.spider.death", 1.8f, 0.4f);

        animate(80, tick -> {
            if (tick % 14 == 0) {
                Compat.spawn(world(), Compat.ITEM_COBWEB, l.clone().add(0, 1, 0), 12, 1.2, 0.6, 1.2, 0);
                soundAt(l, "entity.spider.step", 1.1f, 0.4f);
            }
        }, () -> {
            Compat.spawn(world(), Compat.EGG_CRACK, l.clone().add(0, 1, 0), 40, 1.0, 0.6, 1.0, 0.05);
            soundAt(l, "entity.spider.death", 1.4f, 0.7f);
            clearWebs();
            broadcastNear(Component.text("La camada se queda sin madre.", ACCENT));
        });
    }

    // ============================================================== HABILIDADES ==

    /** 1. Camada: doce crias de golpe. */
    public void spawnBrood() {
        if (!alive()) return;
        broadcastNear(Component.text("Suelta la camada.", ACCENT));
        brood(22);
    }

    /** 2. Puesta: tres o cuatro huevos por la arena. */
    public void eggClutch() {
        layEggs();
    }

    /** 3. Telar: teje una maraña alrededor de cada jugador. */
    public void weave() {
        if (!alive()) return;
        List<Player> victims = targets(26);
        if (victims.isEmpty()) return;
        soundAt(loc(), "entity.spider.ambient", 1.4f, 1.2f);
        broadcastNear(Component.text("Teje.", ACCENT));

        for (Player victim : victims) {
            Location c = victim.getLocation();
            later(14, () -> {
                if (!alive()) return;
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        weaveAt(c.clone().add(x, 0, z).getBlock().getLocation());
                    }
                }
                weaveAt(c.clone().add(0, 1, 0).getBlock().getLocation());
                soundAt(c, "block.wool.place", 1.2f, 0.9f);
            });
        }
    }

    /** 4. Hilo: engancha a los DOS que mas se alejan y los arrastra hacia la madre. */
    public void webPull() {
        if (!alive()) return;
        for (Player target : farthestTargets(2)) {
            pullOn(target);
        }
    }

    /** Un hilo: el sedal de telarana y el tiron periodico hasta traerlo. */
    private void pullOn(Player target) {
        soundAt(target.getLocation(), "entity.spider.ambient", 1.3f, 1.3f);
        target.sendActionBar(Component.text("Un hilo te tira.", NamedTextColor.RED, TextDecoration.BOLD));

        animate(50, tick -> {
            if (!alive() || !Fx.isFightable(target)) throw Stop.now();
            Fx.beam(boss.getLocation().add(0, 1.5, 0), target.getLocation().add(0, 1, 0), 0.9, p ->
                    Compat.spawn(world(), Compat.ITEM_COBWEB, p, 1, 0, 0, 0, 0));
            if (tick < 12 || tick % 5 != 0) return;
            Vector pull = boss.getLocation().toVector().subtract(target.getLocation().toVector());
            if (pull.length() < 5) throw Stop.now();
            push(target, pull.normalize().multiply(0.7).setY(0.2));
        }, null);
    }

    /** 5. Guardianas: dos aranas grandes que si pegan de verdad. */
    public void summonGuardians() {
        if (!alive()) return;
        broadcastNear(Component.text("Llama a las guardianas.", ACCENT));
        guardian();
        guardian();
        soundAt(loc(), "entity.spider.ambient", 1.5f, 0.6f);
    }

    /** 6. Mordisco de la Madre: lento, avisado y brutal si te pilla. */
    public void motherBite() {
        if (!alive()) return;
        Player target = Fx.nearest(loc(), 7);
        if (target == null) return;
        Location mark = Fx.ground(target.getLocation(), 4);
        soundAt(loc(), "entity.spider.ambient", 1.7f, 0.35f);

        animate(50, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < 30) {
                Fx.telegraph(world(), mark, 3.2, 0x6B5B7B);
                return;
            }
            if (tick != 30) return;
            soundAt(mark, "entity.spider.hurt", 1.8f, 0.5f);
            Compat.spawn(world(), Compat.DAMAGE_INDICATOR, mark.clone().add(0, 1, 0), 20, 0.6, 0.4, 0.6, 0.1);
            for (Player p : Fx.playersNear(mark, 3.2)) {
                hit(p, 24 * damageBonus);
                Compat.apply(p, "poison", 140, 1);
                push(p, p.getLocation().toVector().subtract(mark.toVector())
                        .normalize().multiply(0.8).setY(0.4));
            }
        }, null);
    }

    /** 7. Cortina de Tela: un cerco de telaraña que encierra al grupo. */
    public void webWall() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 5);
        soundAt(c, "block.wool.place", 1.5f, 0.7f);
        broadcastNear(Component.text("Cierra la tela.", ACCENT));

        int points = 26;
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            Location spot = Fx.ground(c.clone().add(Math.cos(a) * 11, 1, Math.sin(a) * 11), 6);
            later(i, () -> {
                if (!alive()) return;
                weaveAt(spot.getBlock().getLocation());
                weaveAt(spot.clone().add(0, 1, 0).getBlock().getLocation());
            });
        }
    }

    /** 8. Marea de Crias: tres camadas seguidas por toda la arena. */
    public void broodTide() {
        if (!alive()) return;
        broadcastNear(Component.text("Vienen todas.", ACCENT));
        for (int i = 0; i < 3; i++) {
            later(i * 24, () -> {
                if (!alive()) return;
                brood(18);
                soundAt(loc(), "entity.spider.step", 1.3f, 1.7f);
            });
        }
    }

    /** 9. Veneno de Nido: una nube pegajosa que envenena y frena donde cae. */
    public void nestVenom() {
        if (!alive()) return;
        List<Player> victims = targets();
        if (victims.isEmpty()) return;
        soundAt(loc(), "entity.spider.hurt", 1.4f, 1.2f);

        for (Player victim : victims) {
            Location mark = Fx.ground(victim.getLocation(), 4);
            animate(120, tick -> {
                if (tick < 20) {
                    Fx.telegraph(world(), mark, 2.6, 0x4C7A34);
                    return;
                }
                if (tick % 6 == 0) {
                    Compat.spawn(world(), Compat.ITEM_SLIME, mark.clone().add(0, 0.3, 0), 3, 1.0, 0.1, 1.0, 0);
                }
                if (tick % 20 != 0) return;
                for (Player p : Fx.playersNear(mark, 2.8)) {
                    hit(p, 6 * damageBonus);
                    Compat.apply(p, "poison", 80, 1);
                    Compat.apply(p, "slowness", 60, 1);
                }
            }, null);
        }
    }

    /** 10. Sacudida: se alza sobre las patas y las baja de golpe. */
    public void legSlam() {
        if (!alive()) return;
        Location c = Fx.ground(boss.getLocation(), 4);
        java.util.Set<UUID> struck = new java.util.HashSet<>();
        soundAt(c, "entity.spider.step", 1.8f, 0.4f);
        broadcastNear(Component.text("Se alza sobre las patas.", ACCENT));

        animate(60, tick -> {
            if (tick < 24) {
                Fx.telegraph(world(), c, 9.0, 0x6B5B7B);
                return;
            }
            double radius = (tick - 24) * 0.5;
            if (radius > 9) return;
            Fx.ring(c, radius, (int) (radius * 5) + 6, p -> {
                Location g = Fx.ground(p, 4);
                Compat.spawn(world(), Compat.DUST_PILLAR, g.clone().add(0, 0.15, 0), 1, 0.1, 0.05, 0.1, 0,
                        Material.STONE.createBlockData());
            });
            if (tick % 8 == 0) soundAt(c, "entity.spider.step", 1.2f, 0.5f);
            for (Player p : targets(radius + 1.2)) {
                if (p.getLocation().distance(c) < radius - 1.5) continue;
                if (!struck.add(p.getUniqueId())) continue;
                hit(p, 14 * damageBonus);
                push(p, p.getLocation().toVector().subtract(c.toVector())
                        .normalize().multiply(1.0).setY(0.6));
            }
        }, null);
    }

    // ------------------------------------------------------------------ mensajeria

    private void broadcastNear(Component message) {
        Component line = Component.text("✦ ", ACCENT)
                .append(Component.text("Áragon  ", ACCENT, TextDecoration.BOLD))
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
