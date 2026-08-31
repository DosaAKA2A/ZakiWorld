package net.ederus.edm.anomaly.boss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.core.ActiveAnomaly;
import net.ederus.edm.anomaly.core.Compat;
import net.ederus.edm.anomaly.core.Disguises;
import net.ederus.edm.anomaly.core.Fx;
import net.ederus.edm.anomaly.core.Glow;
import net.ederus.edm.anomaly.core.Stop;
import net.ederus.edm.anomaly.core.Tags;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * EL MIMIC, la undecima anomalia.
 *
 * El unico jefe que no quiere que lo encuentres. Cada fase es un engano distinto:
 *
 *  - FASE I, el rebano: al llegar solo hay un grupo de animales del bioma. Uno es el.
 *    Sin nombre, sin brillo, pastando como los demas. La unica pista es la de siempre
 *    en un mimic: PEGARLE. Al recibir dano crece de golpe (cuanto, depende del animal)
 *    y suelta sus habilidades... hasta que se camufla otra vez: destello, y vuelta a
 *    empezar con un rebano nuevo y otro cuerpo.
 *  - FASE II, los cofres: se esconde dentro de uno de cinco cofres. Los falsos MUERDEN
 *    al que los toca; el verdadero es el propio jefe, y la barra baja al acertar.
 *    Mientras el grupo duda, LA CODICIA los roe a todos, y cuanto mas tardan mas duele.
 *  - FASE III, el robo de rostro: se copia a un jugador de verdad (su cara, su armadura,
 *    sus armas, su nombre a secas encima) y pega muchisimo mas fuerte. A la mitad de la
 *    fase deja de fingir: SE DESATA, y lo que queda es un berserker.
 *
 * El cuerpo cambia constantemente con el truco del Storm Rider: entidad nueva, vida y
 * maximo copiados, y la barra ni se entera. Que le han pegado se detecta vigilando la
 * vida en cada tick, no por evento: asi cuentan las flechas, la espada y hasta el
 * /anomaly hurt de las pruebas.
 */
public final class Mimic extends BossFight {

    public static final String ID = "mimic";
    public static final TextColor ACCENT = TextColor.color(0xD08A3E);

    private static final int GOLD = 0xE8C258;
    private static final int WOOD = 0x8A5A2B;

    /** Cuerpo actual del jefe durante las fases de animal. */
    private EntityType species = EntityType.COW;

    private boolean revealed;
    private long revealTick;
    private boolean hidden;
    private boolean chestsStarted;
    private boolean faceStolen;
    private boolean unleashed;
    private long chestsSince = -1;
    private int round;
    private double lastHealth = -1;
    private double damageBonus = 1.0;

    private UUID mimickedId;
    private String mimickedName;
    /** Si la copia lleva puesto el disfraz de jugador de LibsDisguises. */
    private boolean disguised;

    private final List<LivingEntity> decoys = new ArrayList<>();
    private final List<LivingEntity> chestDecoys = new ArrayList<>();
    private final List<BlockDisplay> chestProps = new ArrayList<>();
    private final Map<UUID, BlockDisplay> chestOf = new HashMap<>();

    public Mimic(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        super(plugin, event, where);
        abilities.addAll(plugin.registry().mimicAbilities());
    }

    @Override
    public String bossName() {
        return "Mimic";
    }

    // ------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        species = pickSpecies();
        become(species, arena.clone(), 1.0, null);
        spawnFlock();

        // Aparicion deliberadamente discreta: un destello y ya. Nada de anillos ni de
        // pilares que griten "el jefe esta aqui"; el anuncio da las coordenadas y el
        // resto es problema de quien venga.
        Location spot = arena.clone();
        busyFor(40);
        Compat.spawn(world(), Compat.FLASH, spot.clone().add(0, 1, 0), 1);
        soundAt(spot, "entity.illusioner.mirror_move", 1.4f, 0.7f);
        for (Player p : Fx.viewersNear(spot, 90)) {
            p.showTitle(Title.title(
                    Component.text("✦ ANOMALIA ✦", ACCENT, TextDecoration.BOLD),
                    Component.text("Mimic  ·  uno de ellos no es lo que parece", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1800), Duration.ofMillis(600))));
        }
    }

    // -------------------------------------------------- el cuerpo y sus disfraces

    /**
     * Cambia el cuerpo del jefe conservando su vida, como hace el Storm Rider.
     * Sin nombre y sin brillo a proposito: el disfraz ES el jefe.
     *
     * @param name nombre visible, o null para no llevar ninguno (camuflaje)
     */
    private LivingEntity become(EntityType type, Location at, double scale, Component name) {
        double health = -1;
        double max = -1;
        if (boss != null && boss.isValid()) {
            health = boss.getHealth();
            max = Compat.getAttribute(boss, "max_health", health);
            Glow.clear(boss);
            Fx.safeRemove(boss);
        }

        boss = (LivingEntity) world().spawnEntity(at, type);
        if (boss instanceof Ageable a) a.setAdult();
        if (boss instanceof Mob m) m.setRemoveWhenFarAway(false);
        boss.setPersistent(true);
        boss.setCanPickupItems(false);
        if (name != null) {
            boss.customName(name);
            boss.setCustomNameVisible(true);
        } else {
            boss.setCustomNameVisible(false);
        }

        if (max > 0) {
            Compat.setAttribute(boss, "max_health", max);
            boss.setHealth(Math.min(max, health));
        } else {
            applyHealth(plugin.registry().scaledHealth(plugin.registry().mimic(), targets(96).size()));
        }
        Compat.setAttribute(boss, "scale", scale);
        Compat.setAttribute(boss, "knockback_resistance", 0.7);
        Compat.setAttribute(boss, "follow_range", 64);
        boss.setMaximumNoDamageTicks(6);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        lastHealth = boss.getHealth();
        return boss;
    }

    /** Que animales tocan en este bioma. El primero de la lista es el mas probable. */
    private List<EntityType> biomePool() {
        String biome = "plains";
        try {
            biome = world().getBiome(arena).getKey().getKey();
        } catch (Throwable ignored) {
        }
        if (biome.contains("desert") || biome.contains("badlands")) {
            return List.of(EntityType.RABBIT, EntityType.DONKEY, EntityType.GOAT);
        }
        if (biome.contains("snow") || biome.contains("frozen") || biome.contains("ice") || biome.contains("grove")) {
            return List.of(EntityType.FOX, EntityType.RABBIT, EntityType.GOAT);
        }
        if (biome.contains("jungle")) {
            return List.of(EntityType.OCELOT, EntityType.PARROT, EntityType.CHICKEN);
        }
        if (biome.contains("swamp") || biome.contains("mangrove")) {
            return List.of(EntityType.FROG, EntityType.CHICKEN, EntityType.SHEEP);
        }
        if (biome.contains("savanna")) {
            return List.of(EntityType.DONKEY, EntityType.COW, EntityType.CHICKEN);
        }
        if (biome.contains("taiga")) {
            return List.of(EntityType.FOX, EntityType.RABBIT, EntityType.SHEEP);
        }
        return List.of(EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN);
    }

    private EntityType pickSpecies() {
        List<EntityType> pool = biomePool();
        return pool.get(random.nextInt(pool.size()));
    }

    /** Cuanto crece cada animal al destaparse. Un pollo del tamano de una vaca asusta mas. */
    private static double grownScale(EntityType type) {
        return switch (type) {
            case CHICKEN, PARROT -> 3.4;
            case RABBIT -> 3.2;
            case FROG -> 3.0;
            case FOX, OCELOT, PIG -> 2.7;
            case COW, SHEEP -> 2.5;
            case GOAT -> 2.4;
            case DONKEY -> 2.2;
            default -> 2.5;
        };
    }

    /** "entity.cow.ambient", "entity.chicken.ambient"... el sonido del disfraz de turno. */
    private String voice(String what) {
        return "entity." + species.getKey().getKey() + "." + what;
    }

    /** El rebano de senuelos: animales de verdad, tan anonimos como el jefe. */
    private void spawnFlock() {
        List<EntityType> pool = biomePool();
        int count = 5 + random.nextInt(3);
        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count + random.nextDouble() * 0.6;
            double d = 3 + random.nextDouble() * 5;
            Location sl = Fx.ground(arena.clone().add(Math.cos(a) * d, 1, Math.sin(a) * d), 5);
            EntityType type = pool.get(random.nextInt(pool.size()));
            try {
                LivingEntity animal = (LivingEntity) world().spawnEntity(sl, type);
                if (animal instanceof Ageable ag) ag.setAdult();
                if (animal instanceof Mob m) m.setRemoveWhenFarAway(false);
                animal.setPersistent(true);
                markMinion(animal);
                decoys.add(animal);
            } catch (Throwable ignored) {
            }
        }
    }

    private void clearFlock() {
        for (LivingEntity d : decoys) {
            if (!d.isValid()) continue;
            Compat.spawn(world(), Compat.POOF, d.getLocation().add(0, 0.5, 0), 8, 0.3, 0.3, 0.3, 0.02);
            spawned.remove(d);
            Fx.safeRemove(d);
        }
        decoys.clear();
    }

    // -------------------------------------------------------------------- ambiente

    @Override
    protected void ambient() {
        if (!alive()) return;

        watchHealth();

        switch (phase()) {
            case 1 -> {
                if (revealed) {
                    huntRevealed();
                    // Descubierto demasiado rato: destello y vuelta al anonimato.
                    if (ticks() - revealTick > 280) camouflage();
                }
            }
            case 2 -> {
                greed();
                if (hidden && ticks() % 8 == 0) shimmerChests();
            }
            case 3 -> {
                keepHostile();
                if (!unleashed && healthFraction() <= 1.0 / 6.0) unleash();
            }
            default -> {
            }
        }

        if (ticks() % 100 == 0) decoys.removeIf(d -> !d.isValid());
    }

    /**
     * Descubierto y a la caza: persigue al mas cercano y le entra a mordiscos.
     *
     * Sin esto, la fase 1 era un animal grande dando vueltas: crecia, soltaba una
     * habilidad de tarde en tarde y se dejaba pegar. Ahora, mientras esta destapado,
     * no para.
     */
    private void huntRevealed() {
        if (ticks() % 10 != 0) return;
        Player t = Fx.nearest(boss.getLocation(), plugin.settings().participationRadius());
        if (t == null) return;
        if (boss instanceof Mob m) {
            LivingEntity current = m.getTarget();
            if (current == null || !current.isValid() || current.isDead()) m.setTarget(t);
        }
        double d2 = boss.getLocation().distanceSquared(t.getLocation());
        if (d2 < 9) {
            if (ticks() % 20 == 0) {
                hit(t, 12 * damageBonus);
                Compat.spawn(world(), Compat.CRIT, t.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.2);
                soundAt(t.getLocation(), voice("hurt"), 1.2f, 0.7f);
            }
            return;
        }
        Vector to = t.getLocation().toVector().subtract(boss.getLocation().toVector());
        if (to.lengthSquared() > 0.25) {
            /* Sin esto embestia mirando a cualquier parte (el maniqui copia la
             * guiñada del zombi al teleportarse encima, asi que basta girarlo). */
            face(t.getLocation());
            boss.setVelocity(to.normalize().multiply(0.55).setY(Math.max(0.05, boss.getVelocity().getY())));
        }
    }

    /**
     * El detector de golpes: si la vida bajo desde el ultimo tick, alguien le pego.
     * Va por vida y no por evento a proposito: cuenta cualquier fuente de dano.
     */
    private void watchHealth() {
        double h = boss.getHealth();
        if (lastHealth < 0) lastHealth = h;
        if (h < lastHealth - 0.01) {
            if (phase() == 1 && !revealed) reveal();
            else if (phase() == 2 && hidden) burstOut(true);
        }
        lastHealth = h;
    }

    private void keepHostile() {
        if (ticks() % 20 != 0) return;
        LivingEntity current = boss instanceof Mob m ? m.getTarget() : null;
        if (current != null && current.isValid() && !current.isDead()) return;
        Player t = Fx.nearest(boss.getLocation(), plugin.settings().participationRadius());
        if (t != null && boss instanceof Mob m) m.setTarget(t);
    }

    // ------------------------------------------------------- FASE I: el rebano

    /** Lo han pillado: crece de golpe segun el animal que sea y entra a pelear. */
    private void reveal() {
        if (revealed || !alive()) return;
        revealed = true;
        revealTick = ticks();

        Location l = boss.getLocation();
        Compat.setAttribute(boss, "scale", grownScale(species));
        boss.customName(Component.text("✦ ", ACCENT)
                .append(Component.text("Mimic", ACCENT, TextDecoration.BOLD)));
        boss.setCustomNameVisible(true);

        Compat.spawn(world(), Compat.FLASH, l.clone().add(0, 1, 0), 1);
        Compat.spawn(world(), Compat.INFESTED, l.clone().add(0, 1.2, 0), 30, 0.8, 1.0, 0.8, 0,
                Compat.dust(GOLD, 1.6f));
        soundAt(l, voice("ambient"), 1.8f, 0.4f);
        soundAt(l, "entity.ravager.roar", 1.2f, 1.3f);
        for (Player p : Fx.playersNear(l, 5)) {
            push(p, p.getLocation().toVector().subtract(l.toVector()).normalize().multiply(0.7).setY(0.3));
        }
        broadcastNear(Component.text("¡Ese era!", ACCENT));
    }

    /** El destello: cuerpo nuevo, rebano nuevo y vuelta a no ser nadie. */
    public void camouflage() {
        if (!alive() || phase() != 1 || !revealed) return;
        revealed = false;
        busyFor(30);

        Location old = boss.getLocation();
        Compat.spawn(world(), Compat.FLASH, old.clone().add(0, 1, 0), 1);
        Compat.spawn(world(), Compat.POOF, old.clone().add(0, 1, 0), 30, 0.6, 0.8, 0.6, 0.05);
        soundAt(old, "entity.illusioner.mirror_move", 1.6f, 0.8f);
        broadcastNear(Component.text("Se pierde entre el rebano.", ACCENT));

        clearFlock();
        double a = random.nextDouble() * Math.PI * 2;
        double d = 4 + random.nextDouble() * 6;
        Location fresh = Fx.ground(arena.clone().add(Math.cos(a) * d, 1, Math.sin(a) * d), 5);

        species = pickSpecies();
        become(species, fresh, 1.0, null);
        spawnFlock();
        Compat.spawn(world(), Compat.FLASH, fresh.clone().add(0, 1, 0), 1);
    }

    // ------------------------------------------------------ FASE II: los cofres

    private void startChests() {
        if (!alive()) return;
        chestsStarted = true;
        chestsSince = ticks();
        round = 0;
        revealed = false;
        clearFlock();
        boss.setInvulnerable(true);
        busyFor(60);

        Location spot = boss.getLocation();
        soundAt(spot, "block.chest.open", 1.6f, 0.5f);
        soundAt(spot, "entity.illusioner.prepare_mirror", 1.5f, 0.7f);
        titleNear(Component.text("LOS COFRES", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text("Uno guarda algo vivo; los demas muerden", NamedTextColor.GRAY));

        animate(50, tick -> {
            if (!alive()) return;
            Fx.helix(boss.getLocation(), 1.5, 3.0, 18, 2.5, p ->
                    Compat.spawn(world(), Compat.VAULT_CONNECTION, p, 1, 0, 0, 0, 0, Compat.dust(GOLD, 1.4f)));
        }, () -> {
            if (!alive()) return;
            boss.setInvulnerable(false);
            hideRound();
        });
    }

    /** Una ronda: cinco cofres en circulo y el dentro de uno, sin nombre y sin cuerpo. */
    private void hideRound() {
        if (!alive() || phase() != 2 || hidden) return;
        clearRound();
        hidden = true;
        round++;

        Location center = Fx.ground(arena.clone(), 6);
        int bossSlot = random.nextInt(5);
        for (int i = 0; i < 5; i++) {
            double a = Math.PI * 2 * i / 5 + 0.2;
            Location spot = Fx.ground(center.clone().add(Math.cos(a) * 7, 1, Math.sin(a) * 7), 5);

            BlockDisplay chest = Fx.blockDisplay(world(), spot.clone().add(0, 0.5, 0), Material.CHEST, 1.0f);
            markMinion(chest);
            chestProps.add(chest);
            Compat.spawn(world(), Compat.POOF, spot.clone().add(0, 0.5, 0), 10, 0.3, 0.3, 0.3, 0.02);

            if (i == bossSlot) {
                boss.teleport(spot);
                boss.setInvisible(true);
                boss.setAI(false);
                boss.setSilent(true);
                Compat.setAttribute(boss, "scale", 0.9);
                boss.setCustomNameVisible(false);
            } else {
                Shulker bait = world().spawn(spot, Shulker.class, s -> {
                    s.setAI(false);
                    s.setInvisible(true);
                    s.setSilent(true);
                    s.setPersistent(false);
                    Compat.setAttribute(s, "max_health", 1);
                    s.setHealth(1);
                });
                markMinion(bait);
                chestDecoys.add(bait);
                chestOf.put(bait.getUniqueId(), chest);
            }
        }
        soundAt(center, "block.chest.close", 1.5f, 0.6f);
        broadcastNear(Component.text("Elige bien: la codicia no espera.", ACCENT));
    }

    /**
     * El reclamo de los cinco cofres, IGUAL en todos para no dar pistas.
     *
     * Son las hebras doradas de la boveda de las mazmorras de prueba: es exactamente
     * la lectura que se busca —"aqui dentro hay algo bueno"— y ademas no se parece a
     * ninguna otra cosa que suelte el plugin.
     */
    private void shimmerChests() {
        for (BlockDisplay c : chestProps) {
            if (!c.isValid()) continue;
            Compat.spawn(world(), Compat.VAULT_CONNECTION, c.getLocation().add(0, 0.6, 0), 1,
                    0.35, 0.35, 0.35, 0);
            if (random.nextInt(6) == 0) {
                Compat.spawn(world(), Compat.TRIAL_SPAWNER_DETECTION, c.getLocation().add(0, 0.9, 0),
                        1, 0.2, 0.2, 0.2, 0);
            }
        }
    }

    /**
     * La Codicia: dano por tiempo a todos los presentes mientras dure el juego de los
     * cofres. Cuanto mas tardan, mas duele; encontrarlo la corta hasta la ronda siguiente.
     */
    private void greed() {
        // Roe durante TODA la fase de los cofres, tambien mientras da la cara: antes
        // solo mordia estando escondido, asi que en cuanto lo encontraban se acababa el
        // reloj y la fase se volvia un paseo. Y sube mucho mas rapido: la idea es que
        // tardar duela, no que pique.
        if (!chestsStarted || faceStolen || ticks() % 30 != 0 || chestsSince < 0) return;
        double bite = Math.min(26, 4 + (ticks() - chestsSince) / 60.0);
        for (Player p : targets(40)) {
            hit(p, bite);
            Compat.spawn(world(), Compat.OMINOUS_SPAWNING, p.getLocation().add(0, 1.0, 0), 4,
                    0.3, 0.5, 0.3, 0);
            p.sendActionBar(Component.text("La Codicia te roe  ", NamedTextColor.GRAY)
                    .append(Component.text("-" + String.format(java.util.Locale.ROOT, "%.1f", bite),
                            ACCENT, TextDecoration.BOLD)));
        }
        if (ticks() % 80 == 0) soundAt(arena, "block.chest.locked", 1.2f, 0.5f);
    }

    /** Lo han encontrado (o le toca salir): revienta el cofre y da la cara un rato. */
    private void burstOut(boolean found) {
        if (!hidden || !alive()) return;
        hidden = false;
        clearRound();

        Location l = boss.getLocation();
        boss.setInvisible(false);
        boss.setAI(true);
        boss.setSilent(false);
        Compat.setAttribute(boss, "scale", grownScale(species));
        boss.customName(Component.text("✦ ", ACCENT)
                .append(Component.text("Mimic", ACCENT, TextDecoration.BOLD)));
        boss.setCustomNameVisible(true);

        Compat.spawn(world(), Compat.ITEM, l.clone().add(0, 0.8, 0), 30, 0.5, 0.5, 0.5, 0.12,
                new ItemStack(Material.CHEST));
        Compat.spawn(world(), Compat.FLASH, l.clone().add(0, 1, 0), 1);
        soundAt(l, "block.chest.open", 1.7f, 0.4f);
        soundAt(l, voice("hurt"), 1.6f, 0.5f);
        if (found) broadcastNear(Component.text("¡Ese cofre estaba VIVO!", ACCENT));
        for (Player p : Fx.playersNear(l, 4)) {
            hit(p, 8 * damageBonus);
            push(p, p.getLocation().toVector().subtract(l.toVector()).normalize().multiply(0.8).setY(0.4));
        }

        // Da la cara unos segundos y vuelve a esconderse, si la fase no ha acabado.
        later(260, () -> {
            if (alive() && phase() == 2 && !hidden) hideRound();
        });
    }

    private void clearRound() {
        for (BlockDisplay c : chestProps) {
            spawned.remove(c);
            Fx.safeRemove(c);
        }
        chestProps.clear();
        for (LivingEntity s : chestDecoys) {
            spawned.remove(s);
            Fx.safeRemove(s);
        }
        chestDecoys.clear();
        chestOf.clear();
    }

    /** Un cofre falso ha caido: muerde a quien lo abrio. */
    @Override
    public void onMinionDeath(LivingEntity minion) {
        BlockDisplay chest = chestOf.remove(minion.getUniqueId());
        if (chest == null) return;
        chestDecoys.removeIf(s -> s.getUniqueId().equals(minion.getUniqueId()));

        Location l = minion.getLocation();
        spawned.remove(chest);
        Fx.safeRemove(chest);
        Compat.spawn(world(), Compat.ITEM, l.clone().add(0, 0.6, 0), 24, 0.4, 0.4, 0.4, 0.1,
                new ItemStack(Material.CHEST));
        // Las mismas motas que sueltan los bloques infestados: el cofre estaba vivo.
        Compat.spawn(world(), Compat.INFESTED, l.clone().add(0, 0.7, 0), 20, 0.5, 0.4, 0.5, 0.05);
        Compat.spawn(world(), Compat.CRIT, l.clone().add(0, 0.8, 0), 16, 0.4, 0.4, 0.4, 0.2);
        soundAt(l, "entity.generic.eat", 1.6f, 0.5f);
        soundAt(l, "block.chest.close", 1.4f, 1.4f);

        Player victim = minion.getKiller();
        if (victim != null && Fx.isFightable(victim)) {
            hit(victim, 9 * damageBonus);
            push(victim, victim.getLocation().toVector().subtract(l.toVector())
                    .normalize().multiply(0.7).setY(0.35));
            victim.sendActionBar(Component.text("¡Ese cofre MUERDE!", NamedTextColor.RED, TextDecoration.BOLD));
        }
    }

    // ------------------------------------------------- FASE III: robo de rostro

    /** Se copia a un jugador de verdad: cara, armadura, armas y nombre a secas. */
    private void stealFace() {
        if (!alive()) return;
        faceStolen = true;
        if (hidden) {
            hidden = false;
            clearRound();
        }
        boss.setInvulnerable(true);
        busyFor(70);

        Player victim = randomTarget();
        Location spot = boss.getLocation();
        soundAt(spot, "entity.illusioner.prepare_mirror", 1.7f, 0.5f);

        animate(60, tick -> {
            if (!alive()) return;
            Location l = boss.getLocation();
            Fx.helix(l, 1.2, 2.8, 16, 3.0, p ->
                    Compat.spawn(world(), Compat.OMINOUS_SPAWNING, p, 1, 0, 0, 0, 0, Compat.dust(0x9BE3F0, 1.3f)));
            if (tick % 12 == 0) soundAt(l, "entity.illusioner.mirror_move", 1.2f, 0.6f + tick * 0.01f);
        }, () -> {
            if (!alive()) return;
            Location at = boss.getLocation();
            mimickedId = victim != null ? victim.getUniqueId() : null;
            mimickedName = victim != null ? victim.getName() : "Mimic";

            Zombie copy = (Zombie) become(EntityType.ZOMBIE, at, 1.0,
                    Component.text(mimickedName, NamedTextColor.WHITE));
            copy.setShouldBurnInDay(false);
            // La copia tiene que PARECER esa persona, no un zombi con su cabeza puesta.
            // El maniqui lleva el perfil del jugador, asi que sale su cara de verdad.
            var profile = victim != null ? Disguises.profileOf(plugin, victim) : null;
            disguised = profile != null;
            if (disguised) {
                wearShell(profile, Component.text(mimickedName, NamedTextColor.WHITE));
            }
            // Si el jugador copiado no estuviera conectado, se le pregunta a Mojang por
            // su nombre, igual que con la cuenta de Rabby.
            if (victim != null && !victim.isOnline()) {
                Disguises.resolveAccount(plugin, victim.getName(), this::reskinShell);
            }
            dressAs(copy, victim);
            Compat.setAttribute(boss, "attack_damage", 16);
            Compat.setAttribute(boss, "armor", 12);
            Compat.setAttribute(boss, "movement_speed", 0.32);
            boss.setInvulnerable(false);

            Compat.spawn(world(), Compat.FLASH, at.clone().add(0, 1, 0), 1);
            soundAt(at, "entity.player.levelup", 1.2f, 0.5f);
            titleNear(Component.text("ROBO DE ROSTRO", NamedTextColor.RED, TextDecoration.BOLD),
                    Component.text("Ahora es " + mimickedName, NamedTextColor.GRAY));
            if (victim != null) {
                victim.sendActionBar(Component.text("Te ha robado la cara A TI.",
                        NamedTextColor.RED, TextDecoration.BOLD));
            }
        });
    }

    /**
     * La armadura y las armas del jugador, copiadas pieza a pieza SOLO en el cuerpo
     * visible. Con maniqui, el zombi de debajo se queda vacio: es invisible pero sus
     * items no, y vestirlo tambien dejaba cada objeto flotando junto al del maniqui,
     * todo por duplicado.
     *
     * El casco es el unico caso raro: si el disfraz de jugador ha funcionado, la cara
     * ya es la suya y ponerle ademas su cabeza le deja un segundo craneo flotando; solo
     * se recurre a la cabeza cuando NO hay disfraz.
     */
    private void dressAs(Zombie copy, Player victim) {
        LivingEntity visible = body();
        boolean shelled = visible != copy;
        EntityEquipment eq = visible.getEquipment();
        if (eq == null) return;
        if (victim != null) {
            ItemStack[] armor = victim.getInventory().getArmorContents();
            eq.setBoots(cloneOf(armor[0]));
            eq.setLeggings(cloneOf(armor[1]));
            eq.setChestplate(cloneOf(armor[2]));
            eq.setItemInMainHand(cloneOf(victim.getInventory().getItemInMainHand()));
            eq.setItemInOffHand(cloneOf(victim.getInventory().getItemInOffHand()));

            ItemStack ownHelmet = cloneOf(armor[3]);
            if (ownHelmet != null) {
                eq.setHelmet(ownHelmet);
            } else if (!shelled) {
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                if (head.getItemMeta() instanceof SkullMeta meta) {
                    meta.setOwningPlayer(victim);
                    head.setItemMeta(meta);
                }
                eq.setHelmet(head);
            }
        } else {
            eq.setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
            if (!shelled) eq.setHelmet(new ItemStack(Material.NETHERITE_HELMET));
        }
        // El maniqui no suelta equipo al retirarse, pero por si el cuerpo visible es
        // el zombi (sin disfraz) o la entidad decide lo contrario algun dia.
        try {
            eq.setHelmetDropChance(0);
            eq.setChestplateDropChance(0);
            eq.setLeggingsDropChance(0);
            eq.setBootsDropChance(0);
            eq.setItemInMainHandDropChance(0);
            eq.setItemInOffHandDropChance(0);
        } catch (Throwable ignored) {
        }
    }

    private static ItemStack cloneOf(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    /** Mitad de la fase: deja de fingir. Brillo rojo, velocidad de ataque 4.0 y furia. */
    private void unleash() {
        if (unleashed || !alive()) return;
        unleashed = true;
        damageBonus = 1.5;

        Compat.setAttribute(boss, "attack_damage", 22);
        Compat.setAttribute(boss, "attack_speed", 4.0);
        Compat.setAttribute(boss, "movement_speed", 0.42);
        renameBody(Component.text("✦ ", NamedTextColor.DARK_RED)
                .append(Component.text("Mimic", NamedTextColor.DARK_RED, TextDecoration.BOLD)));
        glowBody(NamedTextColor.RED);

        Location l = boss.getLocation();
        Compat.spawn(world(), Compat.FLASH, l.clone().add(0, 1, 0), 1);
        Compat.spawn(world(), Compat.ANGRY_VILLAGER, l.clone().add(0, 1.8, 0), 12, 0.5, 0.4, 0.5, 0);
        soundAt(l, "entity.ravager.roar", 1.8f, 0.7f);
        soundAt(l, "entity.warden.roar", 1.2f, 1.4f);
        titleNear(Component.text("SE DESATA", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                Component.text("Ya no le importa fingir", NamedTextColor.GRAY));
    }

    // --------------------------------------------------------------- cambio de fase

    @Override
    protected void onPhaseChange(int from, int to) {
        if (event.bars() != null) event.bars().flash(from);
        if (to == 2) startChests();
        if (to == 3) stealFace();
    }

    /**
     * El Mimic no puede morirse sin haber enganado en las tres fases.
     *
     * Un jugador con buen equipo revienta el cofre de la fase 2 de un golpe y se llevaba
     * por delante el 40% de la barra: el jefe moria sin llegar a robarle la cara a nadie,
     * que es justo el final que hay que ver. Con esto el golpe se recorta hasta dejarlo
     * en el umbral, la fase salta en el tick siguiente y el suelo se levanta solo.
     */
    @Override
    public double survivalFloor() {
        if (!chestsStarted) return 2.0 / 3.0;
        if (!faceStolen) return 1.0 / 3.0;
        return 0;
    }

    // ---------------------------------------------------------------------- muerte

    /** Al morir se le caen todos los disfraces: destellos, y debajo no habia nada. */
    @Override
    public void onDeath() {
        Location l = loc();
        soundAt(l, "entity.illusioner.death", 1.7f, 0.6f);

        animate(80, tick -> {
            if (tick % 16 == 0) {
                Compat.spawn(world(), Compat.FLASH, l.clone().add(0, 1, 0), 1);
                soundAt(l, "entity.illusioner.mirror_move", 1.3f, 0.5f + tick * 0.01f);
            }
            Compat.spawn(world(), Compat.POOF, l.clone().add(0, 1, 0), 4, 0.5, 0.6, 0.5, 0.03);
            Fx.ring(l.clone().add(0, 0.3, 0), 1 + tick * 0.08, 12, tick * 0.2, p ->
                    Compat.spawn(world(), Compat.WHITE_SMOKE, p, 1, 0, 0, 0, 0, Compat.dust(GOLD, 1.4f)));
        }, () -> {
            Compat.spawn(world(), Compat.ITEM, l.clone().add(0, 0.8, 0), 40, 0.8, 0.6, 0.8, 0.15,
                    new ItemStack(Material.CHEST));
            Compat.spawn(world(), Compat.EXPLOSION, l, 2, 0.4, 0.4, 0.4, 0);
            soundAt(l, "block.chest.open", 1.8f, 0.3f);
            broadcastNear(Component.text("Debajo del ultimo disfraz no habia nada.", ACCENT));
        });
    }

    @Override
    public void cleanup() {
        clearRound();
        decoys.clear();
        super.cleanup();
    }

    // ============================================================== HABILIDADES ==

    /** 1. Embestida Salvaje: cruza la arena por una linea marcada. Solo destapado. */
    public void wildCharge() {
        if (!alive() || phase() != 1 || !revealed) return;
        Player target = randomTarget();
        if (target == null) return;
        face(target.getLocation());
        Location from = boss.getLocation();
        Vector dir = target.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        final Vector run = dir.normalize();
        java.util.Set<UUID> hitSet = new java.util.HashSet<>();

        soundAt(from, voice("ambient"), 1.6f, 0.5f);
        animate(50, tick -> {
            if (!alive() || !revealed) throw Stop.now();
            if (tick < 16) {
                for (double d = 1; d < 14; d += 1.2) {
                    Location g = Fx.ground(boss.getLocation().add(run.clone().multiply(d)), 4);
                    Compat.spawn(world(), Compat.INFESTED, g.clone().add(0, 0.2, 0), 1, 0.2, 0.05, 0.2, 0,
                            Compat.dust(GOLD, 1.3f));
                }
                return;
            }
            face(boss.getLocation().clone().add(run.clone().multiply(4)));
            boss.setVelocity(run.clone().multiply(0.85).setY(boss.getVelocity().getY()));
            Compat.spawn(world(), Compat.CLOUD, boss.getLocation(), 2, 0.3, 0.1, 0.3, 0.01);
            for (Player p : Fx.playersNear(boss.getLocation(), 2.2)) {
                if (!hitSet.add(p.getUniqueId())) continue;
                hit(p, 12 * damageBonus);
                push(p, run.clone().multiply(1.0).setY(0.45));
                soundAt(p.getLocation(), "entity.player.attack.knockback", 1.2f, 0.8f);
            }
        }, null);
    }

    /** 2. Pisoton Creciente: tres ondas desde donde pisa, cada una mas ancha. */
    public void growingStomp() {
        if (!alive() || phase() != 1 || !revealed) return;
        Location c = Fx.ground(boss.getLocation(), 4);
        soundAt(c, voice("step"), 1.8f, 0.4f);

        for (int wave = 0; wave < 3; wave++) {
            final int w = wave;
            later(wave * 16, () -> {
                if (!alive()) return;
                java.util.Set<UUID> struck = new java.util.HashSet<>();
                soundAt(c, "entity.generic.big_fall", 1.4f, 0.6f - w * 0.1f);
                animate(14, tick -> {
                    double radius = 1.5 + tick * (0.5 + w * 0.15);
                    Fx.ring(c, radius, (int) (radius * 6), l -> {
                        Location g = Fx.ground(l, 3);
                        Compat.spawn(world(), Compat.VAULT_CONNECTION, g.clone().add(0, 0.25, 0), 1, 0.1, 0.05, 0.1, 0,
                                Compat.dust(WOOD, 1.4f));
                    });
                    for (Player p : targets(radius + 1.0)) {
                        double d = p.getLocation().distance(c);
                        if (Math.abs(d - radius) > 1.1) continue;
                        if (!struck.add(p.getUniqueId())) continue;
                        hit(p, (8 + w * 2) * damageBonus);
                        push(p, new Vector(0, 0.5, 0));
                    }
                }, null);
            });
        }
    }

    /** 3. Chillido Bestial: el grito del animal de turno, en cono y a toda voz. */
    public void beastShriek() {
        if (!alive() || phase() != 1 || !revealed) return;
        Location origin = boss.getEyeLocation();
        Vector face = origin.getDirection().setY(0);
        if (face.lengthSquared() < 0.01) face = new Vector(1, 0, 0);
        final Vector dir = face.normalize();
        soundAt(origin, voice("ambient"), 2.0f, 0.3f);
        broadcastNear(Component.text("Chilla con una voz que no es suya.", ACCENT));

        animate(28, tick -> {
            if (!alive()) throw Stop.now();
            double d = 1 + tick * 0.4;
            if (d > 11) return;
            Fx.arc(boss.getLocation().add(0, 1.2, 0), dir, d, Math.PI * 0.55, (int) (d * 4), p ->
                    Compat.spawn(world(), Compat.SONIC_BOOM, p, 1, 0, 0, 0, 0));
            if (tick % 8 != 0) return;
            for (Player p : targets(11)) {
                Vector to = p.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
                if (to.lengthSquared() < 0.01 || to.normalize().dot(dir) < 0.45) continue;
                hit(p, 6 * damageBonus);
                Compat.apply(p, "nausea", 90, 0);
                Compat.apply(p, "slowness", 60, 1);
                push(p, to.normalize().multiply(0.6).setY(0.2));
            }
        }, null);
    }

    /** 4. Estampida de Senuelos: el rebano entero embiste a la vez. */
    public void decoyStampede() {
        if (!alive() || phase() != 1 || !revealed || decoys.isEmpty()) return;
        soundAt(loc(), "entity.ravager.step", 1.6f, 0.7f);
        broadcastNear(Component.text("¡El rebano entero embiste!", ACCENT));

        for (LivingEntity d : new ArrayList<>(decoys)) {
            if (!d.isValid()) continue;
            Player target = randomTarget();
            if (target == null) return;
            Vector run = target.getLocation().toVector().subtract(d.getLocation().toVector()).setY(0);
            if (run.lengthSquared() < 0.01) continue;
            final Vector v = run.normalize();
            final LivingEntity animal = d;
            java.util.Set<UUID> hitSet = new java.util.HashSet<>();
            animate(40, tick -> {
                if (!animal.isValid()) throw Stop.now();
                if (tick % 4 == 0) animal.setVelocity(v.clone().multiply(0.7).setY(animal.getVelocity().getY()));
                for (Player p : Fx.playersNear(animal.getLocation(), 1.6)) {
                    if (!hitSet.add(p.getUniqueId())) continue;
                    hit(p, 5 * damageBonus);
                    push(p, v.clone().multiply(0.7).setY(0.3));
                }
            }, null);
        }
    }

    /** 5. Camuflaje: el destello. Tambien salta solo si lo dejan demasiado a la vista. */
    public void camouflageCast() {
        camouflage();
    }

    /** 6. Ronda de Cofres: si esta fuera en fase 2, vuelve a esconderse. */
    public void chestRound() {
        if (!alive() || phase() != 2 || hidden) return;
        hideRound();
    }

    /** 7. Dentellada: el cofre del jefe pega un bocado a quien se arrime de mas. */
    public void chestBite() {
        if (!alive() || phase() != 2 || !hidden) return;
        Player near = Fx.nearest(boss.getLocation(), 3.5);
        if (near == null) return;
        face(near.getLocation());
        Location l = boss.getLocation();
        Compat.spawn(world(), Compat.ITEM, l.clone().add(0, 0.8, 0), 14, 0.3, 0.4, 0.3, 0.08,
                new ItemStack(Material.CHEST));
        soundAt(l, "entity.generic.eat", 1.6f, 0.4f);
        soundAt(l, "block.chest.open", 1.4f, 1.6f);
        hit(near, 10 * damageBonus);
        push(near, near.getLocation().toVector().subtract(l.toVector()).normalize().multiply(0.8).setY(0.35));
        near.sendActionBar(Component.text("¡Te ha mordido un cofre!", NamedTextColor.RED, TextDecoration.BOLD));
    }

    /** 8. Tajo Ladron: se lanza, corta y te ROBA la prisa: tu velocidad ahora es suya. */
    public void thiefSlash() {
        if (!alive() || phase() != 3) return;
        Player target = Fx.nearest(loc(), 12);
        if (target == null) return;
        face(target.getLocation());
        Vector run = target.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (run.lengthSquared() > 0.01) {
            boss.setVelocity(run.normalize().multiply(0.9).setY(0.2));
        }
        soundAt(loc(), "entity.player.attack.sweep", 1.4f, 0.7f);
        later(8, () -> {
            if (!alive()) return;
            // El corte es un tajo ancho: roba la prisa de TODOS los que pille en el arco.
            boolean stole = false;
            for (Player p : targets(3.2)) {
                hit(p, (12 + (unleashed ? 6 : 0)) * damageBonus);
                Compat.apply(p, "slowness", 100, 1);
                Compat.spawn(world(), Compat.SWEEP_ATTACK, p.getLocation().add(0, 1, 0), 2, 0.3, 0.3, 0.3, 0);
                soundAt(p.getLocation(), "entity.player.attack.strong", 1.3f, 0.7f);
                p.sendActionBar(Component.text("Te ha robado la prisa.", NamedTextColor.RED, TextDecoration.BOLD));
                stole = true;
            }
            if (stole) Compat.apply(boss, "speed", 100, 1);
        });
    }

    /** 9. Sombra del Otro: aparece detras del jugador al que copio y descarga. */
    public void othersShadow() {
        if (!alive() || phase() != 3) return;
        Player victim = mimickedId != null ? plugin.getServer().getPlayer(mimickedId) : null;
        if (victim == null || !Fx.isFightable(victim)
                || victim.getLocation().distanceSquared(arena) > 80 * 80) {
            victim = randomTarget();
        }
        if (victim == null) return;
        final Player target = victim;

        Location behind = target.getLocation().add(
                target.getLocation().getDirection().setY(0).normalize().multiply(-1.6));
        Compat.spawn(world(), Compat.POOF, boss.getLocation().add(0, 1, 0), 16, 0.3, 0.5, 0.3, 0.04);
        boss.teleport(behind);
        face(target.getLocation());
        Compat.spawn(world(), Compat.FLASH, behind.clone().add(0, 1, 0), 1);
        soundAt(behind, "entity.illusioner.mirror_move", 1.4f, 1.0f);
        target.sendActionBar(Component.text("Esta DETRAS de ti.", NamedTextColor.RED, TextDecoration.BOLD));

        later(10, () -> {
            if (!alive() || !Fx.isFightable(target)) return;
            if (boss.getLocation().distanceSquared(target.getLocation()) > 12) return;
            hit(target, (14 + (unleashed ? 6 : 0)) * damageBonus);
            Compat.spawn(world(), Compat.CRIT, target.getLocation().add(0, 1, 0), 20, 0.3, 0.4, 0.3, 0.25);
            soundAt(target.getLocation(), "entity.player.attack.crit", 1.4f, 0.8f);
        });
    }

    /** 10. Frenesi Carnicero: una tanda de golpes rapidisimos al que tenga delante. */
    public void butcherFrenzy() {
        if (!alive() || phase() != 3) return;
        int blows = unleashed ? 8 : 5;
        soundAt(loc(), "entity.ravager.attack", 1.5f, 1.2f);
        broadcastNear(Component.text("Entra en frenesi.", ACCENT));

        for (int i = 0; i < blows; i++) {
            later(i * 6, () -> {
                if (!alive()) return;
                // Cada golpe de la tanda barre a TODOS los que esten pegados, no a uno.
                for (Player near : targets(4.0)) {
                    hit(near, (4 + (unleashed ? 2 : 0)) * damageBonus);
                    Compat.spawn(world(), Compat.SWEEP_ATTACK, near.getLocation().add(0, 1, 0), 1, 0.2, 0.2, 0.2, 0);
                    soundAt(near.getLocation(), "entity.player.attack.sweep", 1.1f, 0.9f + random.nextFloat() * 0.3f);
                }
            });
        }
    }

    /** 11. Salto Carnicero: salta muy alto y cae de lleno sobre la marca. */
    public void butcherLeap() {
        if (!alive() || phase() != 3) return;
        Player target = randomTarget();
        if (target == null) return;
        face(target.getLocation());
        Location mark = Fx.ground(target.getLocation(), 4);
        soundAt(loc(), "entity.ravager.roar", 1.3f, 1.4f);

        animate(56, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < 20) {
                Fx.telegraph(world(), mark, 3.5, 0xC23B22);
                return;
            }
            if (tick == 20) {
                Vector jump = mark.clone().add(0, 0.5, 0).toVector().subtract(boss.getLocation().toVector());
                boss.setVelocity(jump.normalize().multiply(1.1).setY(0.95));
                soundAt(boss.getLocation(), "entity.player.attack.knockback", 1.3f, 0.6f);
                return;
            }
            Compat.spawn(world(), Compat.CLOUD, boss.getLocation(), 2, 0.2, 0.2, 0.2, 0.01);
            boolean landed = boss.isOnGround() || boss.getLocation().distanceSquared(mark) < 4;
            if (tick < 30 || !landed) return;
            Location bl = boss.getLocation();
            Compat.spawn(world(), Compat.EXPLOSION, bl, 2, 0.4, 0.2, 0.4, 0);
            soundAt(bl, "entity.generic.explode", 1.4f, 0.8f);
            for (Player p : Fx.playersNear(bl, 4.5)) {
                hit(p, (14 + (unleashed ? 6 : 0)) * damageBonus);
                push(p, p.getLocation().toVector().subtract(bl.toVector())
                        .normalize().multiply(1.1).setY(0.5));
            }
            throw Stop.now();
        }, null);
    }

    /** 12. Torbellino de Acero: gira sobre si mismo repartiendo tajos en tres ondas. */
    public void steelWhirlwind() {
        if (!alive() || phase() != 3) return;
        soundAt(loc(), "entity.player.attack.sweep", 1.6f, 0.6f);
        broadcastNear(Component.text("Gira como un torbellino.", ACCENT));

        for (int wave = 0; wave < 3; wave++) {
            final int w = wave;
            later(wave * 18, () -> {
                if (!alive()) return;
                java.util.Set<UUID> cut = new java.util.HashSet<>();
                animate(14, tick -> {
                    double radius = 1.5 + tick * 0.4 + w * 0.4;
                    Location cc = boss.getLocation().add(0, 1.0, 0);
                    Fx.ring(cc, radius, (int) (radius * 7), tick * 0.4, p ->
                            Compat.spawn(world(), Compat.SWEEP_ATTACK, p, 1, 0, 0, 0, 0));
                    for (Player p : targets(radius + 1.0)) {
                        double d = p.getLocation().distance(cc);
                        if (Math.abs(d - radius) > 1.1) continue;
                        if (!cut.add(p.getUniqueId())) continue;
                        hit(p, (8 + (unleashed ? 4 : 0)) * damageBonus);
                        push(p, p.getLocation().toVector().subtract(cc.toVector())
                                .normalize().multiply(0.7).setY(0.3));
                    }
                }, null);
                soundAt(boss.getLocation(), "entity.player.attack.sweep", 1.3f, 0.7f + w * 0.15f);
            });
        }
    }

    // ------------------------------------------------------------------ mensajeria

    private void broadcastNear(Component message) {
        Component line = Component.text("✦ ", ACCENT)
                .append(Component.text("Mimic  ", ACCENT, TextDecoration.BOLD))
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
