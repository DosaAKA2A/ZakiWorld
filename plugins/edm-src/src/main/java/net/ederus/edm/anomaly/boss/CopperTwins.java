package net.ederus.edm.anomaly.boss;

import net.kyori.adventure.bossbar.BossBar;
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
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * KEM y KAM, los Gemelos de Cobre.
 *
 * Un jefe DOBLE: dos golems de cobre gigantes que pelean a la vez y con dos barras
 * de vida independientes. No tienen fases; tienen algo peor: no se puede matar a uno
 * y dejar al otro. Si uno cae y el que queda sigue en pie demasiado tiempo, el caido
 * VUELVE con la mitad del porcentaje de vida que le quede a su hermano. La unica
 * forma de terminar la pelea es tumbarlos casi a la vez.
 *
 *   KEM  (naranja, cobre nuevo)  — el artillero: dano en area, debuffs y control.
 *   KAM  (verde, cobre oxidado)  — el yunque: aguanta, pega de cerca y castiga huir.
 *
 * Cada uno tiene su MECANICA FIRMADA, y las dos se juegan igual: quedandose quieto.
 *   · KEM canta Quietud de Oxido: quien se mueva se queda PETRIFICADO un minuto,
 *     brillando en naranja, a merced de lo que pase alrededor.
 *   · KAM canta la Marca de la Muerte: quien se mueva se lleva la marca verde y
 *     cinco segundos despues recibe dos mil de dano. No hay armadura para eso.
 *
 * Por dentro, KEM es el jefe que el manager rastrea y KAM va marcado como esbirro;
 * el dano que se le hace a KAM tambien cuenta para el botin (ver onMinionDamaged).
 * Ninguno de los dos puede morir del todo mientras el otro siga en pie: al llegar al
 * suelo se quedan ABATIDOS, que es lo que permite que exista la resurreccion.
 */
public final class CopperTwins extends BossFight {

    public static final String ID = "gemelos_cobre";
    /** El color de marca del dueto: el naranja del cobre recien fundido. */
    public static final TextColor ACCENT = TextColor.color(0xE86F2C);

    /** El naranja de KEM y el verde de KAM, para telegrafias y particulas. */
    private static final int KEM_RGB = 0xE86F2C;
    private static final int KAM_RGB = 0x4CC08A;

    /** Cuanto tarda un gemelo caido en volver si su hermano sigue en pie. */
    private static final int REVIVE_TICKS = 600;
    /** Lo que dura la petrificacion de KEM: un minuto clavado. */
    private static final int PETRIFY_TICKS = 1200;
    /** El dano de la Marca de la Muerte. Practicamente un disparo unico. */
    private static final double DEATH_MARK_DAMAGE = 2000;

    private CopperGolem kam;
    /** Vida logica de KAM: la barra y la resurreccion se leen de aqui. */
    private double kamMaxHealth = 1;

    private boolean kemDown;
    private boolean kamDown;
    private long kemDownAt;
    private long kamDownAt;
    /** Las estatuas que quedan donde cayo cada uno, mientras esta abatido. */
    private Entity kemStatue;
    private Entity kamStatue;

    /** Las dos barras: una por gemelo. Este jefe no usa las de fase. */
    private BossBar kemBar;
    private BossBar kamBar;
    private final Set<Player> barViewers = new HashSet<>();

    /** Las habilidades de KAM, que van a su propio ritmo y no al del motor. */
    private final List<Ability> kamPool = new ArrayList<>();
    private long kamBusyUntil;

    /**
     * Hasta que tick dura la TREGUA de una mecanica de quietud.
     *
     * Cuando uno de los dos pide que nadie se mueva, ellos tampoco pueden pegar: se
     * paran, escuchan y esperan el veredicto. Sin esto la mecanica era una trampa —
     * te clavaban en el sitio y te seguian moliendo mientras no podias apartarte.
     */
    private long truceUntil;

    /** Golpe cuerpo a cuerpo a mano: los golems de cobre no traen ataque vanilla. */
    private long kemSwingAt;
    private long kamSwingAt;

    /** Capas de Herrumbre por jugador: cada una pega mas fuerte que la anterior. */
    private final Map<UUID, Integer> rust = new HashMap<>();
    /** Quien esta petrificado ahora mismo, para no encadenar dos petrificaciones. */
    private final Set<UUID> petrified = new HashSet<>();

    public CopperTwins(AnomalyPlugin plugin, ActiveAnomaly event, Location arena) {
        super(plugin, event, arena);
        for (Ability a : plugin.registry().twinsAbilities()) {
            // El motor lleva el ritmo de KEM; KAM tiene el suyo para que los dos
            // puedan estar haciendo algo a la vez, que es la gracia de un jefe doble.
            if (a.id().startsWith("kam_")) {
                kamPool.add(a);
            } else {
                abilities.add(a);
            }
        }
    }

    @Override
    public String bossName() {
        return "Kem y Kam";
    }

    /** Sin fases: la unica progresion es que caigan los dos. */
    @Override
    public int phaseCount() {
        return 1;
    }

    /** Dos cuerpos vivos no caben en una barra por fases: se pinta las suyas. */
    @Override
    public boolean usesOwnBars() {
        return true;
    }

    // -------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        double total = plugin.registry().scaledHealth(plugin.registry().get(ID), targets(96).size());
        // La vida configurada es la del DUETO: cada gemelo se lleva la mitad, para que
        // subir la vida en el menu siga significando lo mismo que en los demas jefes.
        double each = Math.max(200, total / 2.0);

        Location kemAt = arena.clone().add(2.5, 0, 0);
        Location kamAt = arena.clone().add(-2.5, 0, 0);

        boss = forgeTwin(kemAt, true, each);
        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        Glow.apply(boss, NamedTextColor.GOLD);

        kam = forgeTwin(kamAt, false, each);
        markMinion(kam);
        Glow.apply(kam, NamedTextColor.GREEN);
        kamMaxHealth = each;

        applyHealth(each);

        kemBar = BossBar.bossBar(barTitle(true), 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
        kamBar = BossBar.bossBar(barTitle(false), 1.0f, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_10);

        arrival();
    }

    /**
     * Funde un gemelo. El color sale del estado de oxidacion del cobre: el nuevo es
     * naranja y el oxidado es verde, asi que no hace falta tenir nada.
     *
     * Van ENCERADOS a proposito: sin eso el cobre sigue oxidandose solo y a mitad de
     * la pelea KEM habria virado a verde, que es justo lo que no se quiere.
     */
    private CopperGolem forgeTwin(Location at, boolean orange, double health) {
        CopperGolem g = world().spawn(at, CopperGolem.class, c -> {
            c.setPersistent(false);
            c.setRemoveWhenFarAway(false);
            try {
                c.setWeatheringState(orange
                        ? io.papermc.paper.world.WeatheringCopperState.UNAFFECTED
                        : io.papermc.paper.world.WeatheringCopperState.OXIDIZED);
                c.setOxidizing(CopperGolem.Oxidizing.waxed());
            } catch (Throwable ignored) {
            }
            // Dos veces y media el tamaño original: un golem de cobre normal al lado
            // de veinte jugadores no parece un jefe, parece un adorno.
            Compat.setAttribute(c, "scale", 2.5);
            Compat.setAttribute(c, "max_health", Math.min(health, VANILLA_HEALTH_CAP));
            Compat.setAttribute(c, "movement_speed", orange ? 0.30 : 0.26);
            Compat.setAttribute(c, "knockback_resistance", 1.0);
            Compat.setAttribute(c, "follow_range", 64);
            c.setHealth(Math.min(health, VANILLA_HEALTH_CAP));
            c.customName(Component.text(orange ? "KEM" : "KAM",
                    orange ? TextColor.color(KEM_RGB) : TextColor.color(KAM_RGB), TextDecoration.BOLD));
            c.setCustomNameVisible(true);
        });
        return g;
    }

    private void arrival() {
        boss.setInvulnerable(true);
        kam.setInvulnerable(true);
        busyFor(70);
        soundAt(arena, "block.copper.place", 1.7f, 0.5f);
        soundAt(arena, "entity.iron_golem.repair", 1.5f, 0.6f);
        titleNear(Component.text("KEM  y  KAM", ACCENT, TextDecoration.BOLD),
                Component.text("Tienen que caer LOS DOS", NamedTextColor.GRAY));

        animate(70, tick -> {
            double t = tick / 70.0;
            Fx.ring(arena, t * 7, (int) (t * 7 * 5) + 6, l ->
                    Compat.spawn(world(), Compat.DUST, Fx.ground(l, 4).add(0, 0.2, 0), 1, 0, 0, 0, 0,
                            Compat.dust(tick % 2 == 0 ? KEM_RGB : KAM_RGB, 1.6f)));
            if (tick % 14 == 0) soundAt(arena, "block.copper.step", 1.4f, 0.6f);
        }, () -> {
            if (alive()) boss.setInvulnerable(false);
            if (kamUp()) kam.setInvulnerable(false);
            soundAt(arena, "block.copper.break", 1.6f, 0.7f);
        });
    }

    // ------------------------------------------------------------------ estado vivo

    /** Hay una mecanica de quietud en marcha: los dos gemelos estan en tregua. */
    private boolean truce() {
        return ticks() < truceUntil;
    }

    /** Abre la tregua: los dos se paran hasta que se resuelva la quietud. */
    private void callTruce(int duration) {
        truceUntil = Math.max(truceUntil, ticks() + duration);
        // El motor lleva a KEM: se le deja ocupado para que no encadene nada encima.
        busyFor(duration);
        // Y KAM va por su cuenta, asi que hay que frenarlo aparte.
        kamBusyUntil = Math.max(kamBusyUntil, ticks() + duration);
        // Ninguno de los dos remata el golpe que tuviera a medias.
        kemSwingAt = Math.max(kemSwingAt, ticks() + duration);
        kamSwingAt = Math.max(kamSwingAt, ticks() + duration);
        for (LivingEntity twin : new LivingEntity[]{boss, kam}) {
            if (twin instanceof Mob mob && twin.isValid()) {
                try {
                    mob.getPathfinder().stopPathfinding();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** KAM esta en pie: existe, no esta abatido y no esta muerto. */
    private boolean kamUp() {
        return kam != null && kam.isValid() && !kam.isDead() && !kamDown;
    }

    /** KEM esta en pie: vivo de verdad y no abatido. */
    private boolean kemUp() {
        return alive() && !kemDown;
    }

    /** Fraccion de vida de KAM, para su barra y para la resurreccion. */
    private double kamFraction() {
        if (kam == null || !kam.isValid() || kamDown) return 0;
        return Fx.clamp(kam.getHealth() / Math.max(1, kamMaxHealth), 0, 1);
    }

    /**
     * El piso de vida de KEM.
     *
     * Mientras KAM siga en juego —en pie o esperando volver— KEM no puede morir: al
     * llegar abajo se queda ABATIDO. Solo cuando KAM esta fuera de la pelea se le deja
     * caer de verdad, y esa muerte es la que cierra el evento.
     */
    @Override
    public double survivalFloor() {
        return kamStillInPlay() ? 0.02 : 0;
    }

    /** KAM cuenta como "en juego" si esta en pie o si todavia puede resucitar. */
    private boolean kamStillInPlay() {
        return kamUp() || (kamDown && kemUp());
    }

    // ----------------------------------------------------------------------- ciclo

    @Override
    protected void ambient() {
        tickBars();
        tickTwins();
        tickDownedTwins();
        if (!kemDown) driveMelee(boss, true);
        if (kamUp()) driveMelee(kam, false);
        tickKamAbilities();
    }

    /** Refresca las dos barras y a quien las ve. */
    private void tickBars() {
        if (kemBar == null || kamBar == null) return;
        kemBar.progress((float) (kemDown ? 0 : healthFraction()));
        kamBar.progress((float) kamFraction());
        kemBar.name(barTitle(true));
        kamBar.name(barTitle(false));

        if (ticks() % 20 != 0) return;
        Set<Player> should = new HashSet<>(
                Fx.viewersNear(loc(), plugin.settings().participationRadius() + 24));
        for (Player p : new HashSet<>(barViewers)) {
            if (should.contains(p) && p.isOnline()) continue;
            p.hideBossBar(kemBar);
            p.hideBossBar(kamBar);
            barViewers.remove(p);
        }
        for (Player p : should) {
            if (barViewers.add(p)) {
                p.showBossBar(kemBar);
                p.showBossBar(kamBar);
            }
        }
    }

    private Component barTitle(boolean orange) {
        boolean down = orange ? kemDown : !kamUp();
        TextColor color = orange ? TextColor.color(KEM_RGB) : TextColor.color(KAM_RGB);
        Component name = Component.text(orange ? "KEM" : "KAM", color, TextDecoration.BOLD);
        Component tag = down
                ? Component.text("  ABATIDO", NamedTextColor.DARK_GRAY, TextDecoration.BOLD)
                : Component.text(orange ? "  ·  artillero" : "  ·  yunque", TextColor.color(0x555555));
        return Component.text("✦ ", color).append(name).append(tag);
    }

    /** Mantiene a los gemelos enteros: ni fuego, ni ahogo, ni deriva del color. */
    private void tickTwins() {
        if (kam == null || !kam.isValid()) return;
        kam.setFireTicks(0);
        kam.setRemainingAir(kam.getMaximumAir());
        if (ticks() % 100 == 0) {
            try {
                kam.setWeatheringState(io.papermc.paper.world.WeatheringCopperState.OXIDIZED);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * El corazon del jefe doble: la cuenta atras de la resurreccion.
     *
     * Un gemelo abatido se levanta con LA MITAD del porcentaje que le quede a su
     * hermano, asi que dejar a uno tirado mientras se pega al otro no funciona: cuanto
     * mas sano este el que sigue en pie, mas fuerte vuelve el caido.
     */
    private void tickDownedTwins() {
        // KEM al suelo: no muere, se abate.
        if (!kemDown && alive() && healthFraction() <= 0.025 && kamStillInPlay()) {
            downKem();
        }
        // Los dos en el suelo: se acabo.
        if (kemDown && kamDown) {
            finishBoth();
            return;
        }
        if (kemDown && kamUp() && ticks() - kemDownAt >= REVIVE_TICKS) {
            reviveKem();
        }
        if (kamDown && kemUp() && ticks() - kamDownAt >= REVIVE_TICKS) {
            reviveKam();
        }
        if (ticks() % 40 != 0) return;
        if (kemDown && kamUp()) {
            warn(Component.text("KEM vuelve en ", NamedTextColor.GRAY)
                    .append(Component.text(((REVIVE_TICKS - (ticks() - kemDownAt)) / 20) + "s  ",
                            TextColor.color(KEM_RGB), TextDecoration.BOLD))
                    .append(Component.text("¡TUMBEN A KAM!", NamedTextColor.RED, TextDecoration.BOLD)));
        } else if (kamDown && kemUp()) {
            warn(Component.text("KAM vuelve en ", NamedTextColor.GRAY)
                    .append(Component.text(((REVIVE_TICKS - (ticks() - kamDownAt)) / 20) + "s  ",
                            TextColor.color(KAM_RGB), TextDecoration.BOLD))
                    .append(Component.text("¡TUMBEN A KEM!", NamedTextColor.RED, TextDecoration.BOLD)));
        }
    }

    private void downKem() {
        kemDown = true;
        kemDownAt = ticks();
        boss.setInvulnerable(true);
        boss.setAI(false);
        boss.setSilent(true);
        boss.setInvisible(true);
        kemStatue = raiseStatue(boss.getLocation(), true);
        collapseFx(boss.getLocation(), KEM_RGB);
        titleNear(Component.text("KEM HA CAIDO", TextColor.color(KEM_RGB), TextDecoration.BOLD),
                Component.text("Tumben a KAM antes de que se levante", NamedTextColor.GRAY));
    }

    private void downKam() {
        kamDown = true;
        kamDownAt = ticks();
        Location where = kam != null && kam.isValid() ? kam.getLocation() : loc();
        kamStatue = raiseStatue(where, false);
        collapseFx(where, KAM_RGB);
        if (kam != null && kam.isValid()) {
            spawned.remove(kam);
            Fx.safeRemove(kam);
        }
        titleNear(Component.text("KAM HA CAIDO", TextColor.color(KAM_RGB), TextDecoration.BOLD),
                Component.text("Tumben a KEM antes de que se levante", NamedTextColor.GRAY));
    }

    private void reviveKem() {
        double half = kamFraction() / 2.0;
        kemDown = false;
        dropStatue(kemStatue, KEM_RGB);
        kemStatue = null;
        boss.setInvisible(false);
        boss.setAI(true);
        boss.setSilent(false);
        boss.setInvulnerable(false);
        double max = Compat.getAttribute(boss, "max_health", 1);
        boss.setHealth(Math.max(1, Math.min(max, max * half)));
        reviveFx(boss.getLocation(), KEM_RGB, "KEM");
    }

    private void reviveKam() {
        double half = healthFraction() / 2.0;
        kamDown = false;
        Location at = kamStatue != null && kamStatue.isValid()
                ? kamStatue.getLocation() : loc().clone().add(2, 0, 2);
        dropStatue(kamStatue, KAM_RGB);
        kamStatue = null;
        kam = forgeTwin(Fx.ground(at, 5), false, kamMaxHealth);
        markMinion(kam);
        Glow.apply(kam, NamedTextColor.GREEN);
        double max = Compat.getAttribute(kam, "max_health", 1);
        kam.setHealth(Math.max(1, Math.min(max, max * half)));
        reviveFx(at, KAM_RGB, "KAM");
    }

    /** Los dos abatidos: se deja morir a KEM de verdad y el evento se cierra. */
    private void finishBoth() {
        if (!alive()) return;
        dropStatue(kemStatue, KEM_RGB);
        dropStatue(kamStatue, KAM_RGB);
        kemStatue = null;
        kamStatue = null;
        boss.setInvisible(false);
        boss.setInvulnerable(false);
        boss.setHealth(0);
    }

    /** La estatua que queda en pie donde cayo un gemelo: el recordatorio de que vuelve. */
    private Entity raiseStatue(Location at, boolean orange) {
        Material m = Material.matchMaterial(orange ? "COPPER_BLOCK" : "OXIDIZED_COPPER");
        if (m == null) m = Material.COPPER_BLOCK;
        Entity statue = Fx.blockDisplay(world(), Fx.ground(at, 4).add(0, 0.4, 0), m, 1.6f);
        markMinion(statue);
        return statue;
    }

    private void dropStatue(Entity statue, int rgb) {
        if (statue == null || !statue.isValid()) return;
        Compat.spawn(world(), Compat.DUST, statue.getLocation(), 20, 0.5, 0.5, 0.5, 0,
                Compat.dust(rgb, 1.8f));
        spawned.remove(statue);
        Fx.safeRemove(statue);
    }

    private void collapseFx(Location at, int rgb) {
        soundAt(at, "entity.iron_golem.death", 1.7f, 0.5f);
        soundAt(at, "block.copper.break", 1.6f, 0.4f);
        Compat.spawn(world(), Compat.BLOCK, at.clone().add(0, 1, 0), 60, 1.0, 1.0, 1.0, 0.1,
                Material.COPPER_BLOCK.createBlockData());
        Compat.spawn(world(), Compat.DUST, at.clone().add(0, 1, 0), 40, 1.0, 1.0, 1.0, 0,
                Compat.dust(rgb, 2.0f));
    }

    private void reviveFx(Location at, int rgb, String who) {
        soundAt(at, "entity.iron_golem.repair", 1.8f, 0.6f);
        soundAt(at, "block.beacon.activate", 1.4f, 0.7f);
        Compat.spawn(world(), Compat.DUST, at.clone().add(0, 1, 0), 60, 1.0, 1.5, 1.0, 0,
                Compat.dust(rgb, 2.2f));
        titleNear(Component.text(who + " SE LEVANTA", TextColor.color(rgb), TextDecoration.BOLD),
                Component.text("Habia que tumbarlos a la vez", NamedTextColor.GRAY));
    }

    // ------------------------------------------------------- movimiento y melee

    /**
     * Los golems de cobre no traen ataque de combate: se les da objetivo con el
     * navegador y el golpe se reparte a mano cuando lo tienen al alcance.
     */
    private void driveMelee(LivingEntity twin, boolean orange) {
        if (twin == null || !twin.isValid()) return;
        // En tregua no persiguen ni pegan: se quedan tan quietos como el resto.
        if (truce()) {
            if (ticks() % 20 == 0) {
                Compat.spawn(world(), Compat.DUST, twin.getLocation().add(0, 2.4, 0), 3,
                        0.2, 0.2, 0.2, 0, Compat.dust(orange ? KEM_RGB : KAM_RGB, 1.2f));
            }
            return;
        }
        if (ticks() % 10 == 0 && twin instanceof Mob mob) {
            Player prey = orange ? Fx.farthest(twin.getLocation(), 40) : Fx.nearest(twin.getLocation(), 40);
            if (prey == null) prey = Fx.nearest(twin.getLocation(), 64);
            if (prey != null) {
                try {
                    mob.getPathfinder().moveTo(prey, orange ? 1.0 : 1.15);
                } catch (Throwable ignored) {
                }
                twin.lookAt(prey.getLocation().getX(), prey.getEyeLocation().getY(),
                        prey.getLocation().getZ(), io.papermc.paper.entity.LookAnchor.EYES);
            }
        }
        long ready = orange ? kemSwingAt : kamSwingAt;
        if (ticks() < ready) return;
        List<Player> hits = Fx.playersNear(twin.getLocation(), orange ? 3.6 : 4.2);
        if (hits.isEmpty()) return;
        if (orange) {
            kemSwingAt = ticks() + 30;
        } else {
            kamSwingAt = ticks() + 24;
        }
        soundAt(twin.getLocation(), "entity.iron_golem.attack", 1.5f, orange ? 1.1f : 0.7f);
        for (Player p : hits) {
            strike(twin, p, orange ? 9 : 15);
            push(p, p.getLocation().toVector().subtract(twin.getLocation().toVector())
                    .normalize().multiply(orange ? 0.4 : 0.75).setY(0.3));
        }
    }

    /** El ritmo propio de KAM: sin esto solo actuaria uno de los dos a la vez. */
    private void tickKamAbilities() {
        if (!kamUp() || kamPool.isEmpty() || truce()) return;
        if (ticks() < kamBusyUntil || ticks() % 10 != 0) return;
        List<Ability> ready = new ArrayList<>();
        int total = 0;
        for (Ability a : kamPool) {
            if (!a.ready(ticks())) continue;
            ready.add(a);
            total += a.weight();
        }
        if (ready.isEmpty()) return;
        int roll = random.nextInt(total);
        for (Ability a : ready) {
            roll -= a.weight();
            if (roll >= 0) continue;
            a.startCooldown(ticks());
            kamBusyUntil = ticks() + a.castTicks();
            try {
                a.cast(this);
            } catch (Throwable t) {
                plugin.getLogger().warning("Habilidad " + a.id() + " de KAM fallo: " + t);
            }
            return;
        }
    }

    /**
     * Dano atribuido al gemelo que lo hace, con el multiplicador del menu aplicado.
     * La Herrumbre de KEM se cobra aqui: cada capa sube lo que duele todo lo demas.
     */
    private void strike(LivingEntity source, Player p, double amount) {
        if (p == null || !Fx.isFightable(p)) return;
        double dmg = amount * plugin.registry().damageMultiplier(event.type());
        dmg *= 1 + 0.12 * rust.getOrDefault(p.getUniqueId(), 0);
        try {
            if (source != null && source.isValid()) {
                p.damage(dmg, source);
            } else {
                p.damage(dmg);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------- las dos firmadas

    /**
     * 1. QUIETUD DE OXIDO (KEM). Cuatro segundos de aviso y despues lee la arena:
     * quien se haya movido se queda PETRIFICADO un minuto entero, brillando en
     * naranja para que todo el mundo vea quien no obedecio.
     */
    public void rustStillness() {
        if (!kemUp()) return;
        // Los dos se paran: si exigen quietud, la cumplen ellos tambien.
        callTruce(115);
        Map<UUID, Location> frozen = new HashMap<>();
        soundAt(loc(), "block.copper.place", 1.8f, 0.4f);
        titleNear(Component.text("QUIETUD DE OXIDO", TextColor.color(KEM_RGB), TextDecoration.BOLD),
                Component.text("NO TE MUEVAS  ·  ellos tampoco pegan", NamedTextColor.RED));

        animate(120, tick -> {
            if (!kemUp()) throw Stop.now();
            if (tick == 20) {
                for (Player p : targets()) frozen.put(p.getUniqueId(), p.getLocation().clone());
                soundAt(loc(), "block.copper.hit", 1.6f, 1.4f);
                return;
            }
            if (tick < 20) return;
            if (tick < 100) {
                if (tick % 10 == 0) {
                    Fx.ring(Fx.ground(loc(), 4).add(0, 0.2, 0), 3 + (tick - 20) * 0.12, 20, l ->
                            Compat.spawn(world(), Compat.DUST, l, 1, 0, 0, 0, 0,
                                    Compat.dust(KEM_RGB, 1.5f)));
                    for (Player p : targets()) {
                        p.sendActionBar(Component.text("QUIETO  ", TextColor.color(KEM_RGB),
                                TextDecoration.BOLD)
                                .append(Component.text(((100 - tick) / 20 + 1) + "s", NamedTextColor.WHITE)));
                    }
                }
                return;
            }
            if (tick != 100) return;
            soundAt(loc(), "block.copper.break", 1.8f, 0.5f);
            for (Player p : targets()) {
                Location was = frozen.get(p.getUniqueId());
                if (was == null) continue;
                if (was.getWorld() == p.getWorld() && was.distanceSquared(p.getLocation()) < 0.35) {
                    p.sendActionBar(Component.text("Aguantaste quieto.", NamedTextColor.GREEN));
                    continue;
                }
                petrify(p);
            }
        }, null);
    }

    /** Deja a un jugador de piedra un minuto, con su brillo naranja de vergüenza. */
    private void petrify(Player p) {
        if (!petrified.add(p.getUniqueId())) return;
        Glow.clear(p);
        Glow.apply(p, NamedTextColor.GOLD);
        p.setGlowing(true);
        root(p, PETRIFY_TICKS);
        Compat.apply(p, "slowness", PETRIFY_TICKS, 250);
        Compat.apply(p, "mining_fatigue", PETRIFY_TICKS, 250);
        Compat.apply(p, "weakness", PETRIFY_TICKS, 250);
        soundAt(p.getLocation(), "block.copper.place", 1.5f, 0.5f);
        p.showTitle(Title.title(
                Component.text("PETRIFICADO", TextColor.color(KEM_RGB), TextDecoration.BOLD),
                Component.text("Un minuto de cobre", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1600), Duration.ofMillis(500))));

        animate(PETRIFY_TICKS, tick -> {
            if (!Fx.isFightable(p)) throw Stop.now();
            if (tick % 8 == 0) {
                Fx.ring(p.getLocation().add(0, 1.0, 0), 0.6, 8, tick * 0.2, l ->
                        Compat.spawn(world(), Compat.DUST, l, 1, 0, 0, 0, 0, Compat.dust(KEM_RGB, 1.3f)));
            }
            if (tick % 20 == 0) {
                p.sendActionBar(Component.text("PETRIFICADO  ", TextColor.color(KEM_RGB),
                        TextDecoration.BOLD)
                        .append(Component.text(((PETRIFY_TICKS - tick) / 20) + "s", NamedTextColor.WHITE)));
            }
        }, () -> {
            petrified.remove(p.getUniqueId());
            Glow.clear(p);
            p.setGlowing(false);
            if (Fx.isFightable(p)) {
                p.sendActionBar(Component.text("El cobre se resquebraja. Ya puedes moverte.",
                        NamedTextColor.GREEN));
            }
        });
    }

    /**
     * 2. MARCA DE LA MUERTE (KAM). El mismo trato que su hermano, pero sin segunda
     * oportunidad: quien se mueva se lleva la marca verde y cinco segundos despues
     * recibe dos mil de dano. No hay armadura en el servidor que aguante eso.
     */
    public void deathMark() {
        if (!kamUp()) return;
        // Igual que su hermano: mientras la marca busca, nadie pega.
        callTruce(115);
        Map<UUID, Location> frozen = new HashMap<>();
        soundAt(kam.getLocation(), "entity.iron_golem.damage", 1.8f, 0.4f);
        titleNear(Component.text("MARCA DE LA MUERTE", TextColor.color(KAM_RGB), TextDecoration.BOLD),
                Component.text("QUIETOS  ·  ellos tampoco pegan", NamedTextColor.RED));

        animate(120, tick -> {
            if (!kamUp()) throw Stop.now();
            if (tick == 20) {
                for (Player p : targets()) frozen.put(p.getUniqueId(), p.getLocation().clone());
                soundAt(kam.getLocation(), "block.copper.hit", 1.6f, 0.6f);
                return;
            }
            if (tick < 20) return;
            if (tick < 100) {
                if (tick % 10 == 0) {
                    Fx.ring(Fx.ground(kam.getLocation(), 4).add(0, 0.2, 0), 3 + (tick - 20) * 0.12, 20, l ->
                            Compat.spawn(world(), Compat.DUST, l, 1, 0, 0, 0, 0,
                                    Compat.dust(KAM_RGB, 1.5f)));
                    for (Player p : targets()) {
                        p.sendActionBar(Component.text("QUIETO  ", TextColor.color(KAM_RGB),
                                TextDecoration.BOLD)
                                .append(Component.text(((100 - tick) / 20 + 1) + "s", NamedTextColor.WHITE)));
                    }
                }
                return;
            }
            if (tick != 100) return;
            for (Player p : targets()) {
                Location was = frozen.get(p.getUniqueId());
                if (was == null) continue;
                if (was.getWorld() == p.getWorld() && was.distanceSquared(p.getLocation()) < 0.35) {
                    p.sendActionBar(Component.text("La marca no te encuentra.", NamedTextColor.GREEN));
                    continue;
                }
                markForDeath(p);
            }
        }, null);
    }

    /** La marca verde: cinco segundos de cuenta atras y dos mil de dano. */
    private void markForDeath(Player p) {
        Glow.clear(p);
        Glow.apply(p, NamedTextColor.GREEN);
        p.setGlowing(true);
        soundAt(p.getLocation(), "entity.wither.spawn", 1.2f, 1.8f);
        p.showTitle(Title.title(
                Component.text("MARCADO", TextColor.color(KAM_RGB), TextDecoration.BOLD),
                Component.text("Cinco segundos", NamedTextColor.RED),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1400), Duration.ofMillis(400))));

        animate(100, tick -> {
            if (!Fx.isFightable(p)) throw Stop.now();
            if (tick % 4 == 0) {
                Fx.ring(p.getLocation().add(0, 2.4, 0), 0.5, 6, tick * 0.5, l ->
                        Compat.spawn(world(), Compat.DUST, l, 1, 0, 0, 0, 0, Compat.dust(KAM_RGB, 1.6f)));
            }
            if (tick % 20 == 0) {
                soundAt(p.getLocation(), "block.note_block.bass", 1.2f, 0.5f + tick / 100f);
                p.sendActionBar(Component.text("MARCA DE LA MUERTE  ", TextColor.color(KAM_RGB),
                        TextDecoration.BOLD)
                        .append(Component.text(((100 - tick) / 20) + "s", NamedTextColor.RED)));
            }
            if (tick != 99) return;
            soundAt(p.getLocation(), "entity.generic.explode", 1.8f, 0.4f);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, p.getLocation().add(0, 1, 0), 1);
            strike(kam != null && kam.isValid() ? kam : boss, p, DEATH_MARK_DAMAGE);
        }, () -> {
            Glow.clear(p);
            p.setGlowing(false);
        });
    }

    // -------------------------------------------------------- KEM: area y debuffs

    /** 3. Nube de Verdin: veneno y nauseas sobre varios a la vez. */
    public void verdigrisCloud() {
        if (!kemUp()) return;
        List<Player> marks = pickTargets(3);
        if (marks.isEmpty()) return;
        soundAt(loc(), "entity.witch.throw", 1.5f, 0.7f);
        for (Player m : marks) {
            Location at = Fx.ground(m.getLocation(), 4);
            animate(140, tick -> {
                if (tick % 6 != 0) return;
                Fx.ring(at.clone().add(0, 0.4, 0), 3.2, 16, tick * 0.1, l ->
                        Compat.spawn(world(), Compat.DUST, l, 1, 0.1, 0.3, 0.1, 0,
                                Compat.dust(KAM_RGB, 1.4f)));
                Compat.spawn(world(), Compat.SNEEZE, at.clone().add(0, 0.8, 0), 6, 1.4, 0.5, 1.4, 0.02);
                if (tick % 20 != 0) return;
                for (Player p : Fx.playersNear(at, 3.4)) {
                    strike(boss, p, 3);
                    Compat.apply(p, "poison", 60, 0);
                    Compat.apply(p, "nausea", 100, 0);
                }
            }, null);
        }
    }

    /** 4. Descarga de Cobre: el rayo salta de uno a otro, que para eso conduce. */
    public void copperArc() {
        if (!kemUp()) return;
        List<Player> chain = nearestTargets(6);
        if (chain.isEmpty()) return;
        soundAt(loc(), "entity.lightning_bolt.thunder", 1.2f, 1.6f);
        Location from = center();
        for (int i = 0; i < chain.size(); i++) {
            Player p = chain.get(i);
            final Location prev = i == 0 ? from : chain.get(i - 1).getLocation().add(0, 1, 0);
            later(i * 6, () -> {
                if (!kemUp() || !Fx.isFightable(p)) return;
                Fx.beam(prev, p.getLocation().add(0, 1, 0), 0.4, l -> {
                    Compat.spawn(world(), Compat.ELECTRIC_SPARK, l, 2, 0.05, 0.05, 0.05, 0.02);
                    Compat.spawn(world(), Compat.DUST, l, 1, 0, 0, 0, 0, Compat.dust(KEM_RGB, 1.2f));
                });
                strike(boss, p, 9);
                soundAt(p.getLocation(), "entity.lightning_bolt.impact", 0.9f, 1.5f);
            });
        }
    }

    /** 5. Onda Oxidante: la onda radial que reparte lentitud y debilidad. */
    public void oxidizingWave() {
        if (!kemUp()) return;
        Location c = Fx.ground(boss.getLocation(), 4);
        Set<UUID> touched = new HashSet<>();
        soundAt(c, "block.copper.step", 1.7f, 0.4f);
        animate(70, tick -> {
            if (!kemUp()) throw Stop.now();
            if (tick < 18) {
                Fx.telegraph(world(), c, 10.0, KEM_RGB);
                return;
            }
            double radius = (tick - 18) * 0.55;
            if (radius > 10) return;
            Fx.shockwave(world(), c, radius, Compat.DUST_PLUME, 5);
            for (Player p : Fx.playersNear(c, radius + 1.0)) {
                if (p.getLocation().distance(c) < radius - 1.4) continue;
                if (!touched.add(p.getUniqueId())) continue;
                strike(boss, p, 10);
                Compat.apply(p, "slowness", 120, 1);
                Compat.apply(p, "weakness", 120, 0);
            }
        }, null);
    }

    /** 6. Lluvia de Esquirlas: astillas de cobre sobre las marcas de todos. */
    public void shrapnelRain() {
        if (!kemUp()) return;
        List<Player> marks = targets();
        if (marks.isEmpty()) return;
        soundAt(loc(), "block.copper.break", 1.5f, 1.3f);
        for (Player m : marks) {
            Location mark = Fx.ground(m.getLocation(), 5);
            animate(50, tick -> {
                if (tick < 26) {
                    Fx.telegraph(world(), mark, 2.4, KEM_RGB);
                    return;
                }
                if (tick != 26) return;
                for (double h = 10; h > 0; h -= 1.0) {
                    Compat.spawn(world(), Compat.BLOCK, mark.clone().add(0, h, 0), 2, 0.2, 0.2, 0.2, 0.02,
                            Material.COPPER_BLOCK.createBlockData());
                }
                soundAt(mark, "block.copper.break", 1.3f, 0.9f);
                for (Player p : Fx.playersNear(mark, 2.6)) {
                    strike(boss, p, 12);
                    addRust(p);
                }
            }, null);
        }
    }

    /** 7. Campo Magnetico: el cobre tira de todo el mundo hacia KEM. */
    public void magneticField() {
        if (!kemUp()) return;
        soundAt(loc(), "block.beacon.power_select", 1.4f, 0.5f);
        warn(Component.text("KEM tira de ustedes.", TextColor.color(KEM_RGB)));
        animate(100, tick -> {
            if (!kemUp()) throw Stop.now();
            Location c = center();
            if (tick % 4 == 0) {
                Fx.sphere(c, 3.0 + Math.sin(tick * 0.2) * 0.6, 26, l ->
                        Compat.spawn(world(), Compat.DUST, l, 1, 0, 0, 0, 0, Compat.dust(KEM_RGB, 1.3f)));
            }
            if (tick % 8 != 0) return;
            for (Player p : targets(18)) {
                Vector pull = c.toVector().subtract(p.getLocation().toVector());
                if (pull.lengthSquared() < 9) continue;
                push(p, pull.normalize().multiply(0.55).setY(0.18));
            }
        }, null);
    }

    /** 8. Herrumbre: una capa mas de oxido encima; todo lo que venga dolera mas. */
    public void rustPlague() {
        if (!kemUp()) return;
        soundAt(loc(), "block.copper.hit", 1.5f, 0.8f);
        for (Player p : pickTargets(5)) {
            addRust(p);
            Compat.apply(p, "mining_fatigue", 200, 1);
            Compat.spawn(world(), Compat.DUST, p.getLocation().add(0, 1.2, 0), 20, 0.4, 0.6, 0.4, 0,
                    Compat.dust(KEM_RGB, 1.5f));
        }
    }

    /** Suma una capa de herrumbre y avisa a quien la lleva. */
    private void addRust(Player p) {
        int layers = Math.min(5, rust.getOrDefault(p.getUniqueId(), 0) + 1);
        rust.put(p.getUniqueId(), layers);
        p.sendActionBar(Component.text("HERRUMBRE ", TextColor.color(KEM_RGB), TextDecoration.BOLD)
                .append(Component.text("x" + layers, NamedTextColor.WHITE))
                .append(Component.text("  (+" + (layers * 12) + "% de dano recibido)", NamedTextColor.GRAY)));
    }

    /** 9. Pararrayos: planta un poste que llama al rayo hasta que lo tumben. */
    public void lightningRod() {
        if (!kemUp()) return;
        double a = random.nextDouble() * Math.PI * 2;
        Location spot = Fx.ground(loc().clone().add(Math.cos(a) * 7, 1, Math.sin(a) * 7), 5);
        Material rod = Material.matchMaterial("LIGHTNING_ROD");
        org.bukkit.entity.ArmorStand stand = world().spawn(spot, org.bukkit.entity.ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setGravity(false);
            s.setPersistent(false);
            s.setBasePlate(false);
            s.setSmall(true);
            s.customName(Component.text("Pararrayos", TextColor.color(KEM_RGB), TextDecoration.BOLD));
            s.setCustomNameVisible(true);
            org.bukkit.inventory.EntityEquipment eq = s.getEquipment();
            if (eq != null) eq.setHelmet(new org.bukkit.inventory.ItemStack(
                    rod != null ? rod : Material.COPPER_BLOCK));
        });
        markMinion(stand);
        plugin.anchors().register(stand, 4,
                () -> soundAt(stand.getLocation(), "block.copper.hit", 1.1f, 0.9f),
                () -> soundAt(stand.getLocation(), "block.copper.break", 1.4f, 0.8f));
        warn(Component.text("Pararrayos plantado. Tumbenlo.", TextColor.color(KEM_RGB)));

        animate(400, tick -> {
            if (!stand.isValid() || !plugin.anchors().isAnchor(stand)) throw Stop.now();
            Location l = stand.getLocation();
            if (tick % 10 == 0) {
                Compat.spawn(world(), Compat.ELECTRIC_SPARK, l.clone().add(0, 1.4, 0), 3, 0.2, 0.3, 0.2, 0.02);
            }
            if (tick % 80 != 0) return;
            world().strikeLightningEffect(l);
            soundAt(l, "entity.lightning_bolt.impact", 1.5f, 1.0f);
            for (Player p : Fx.playersNear(l, 6)) {
                strike(boss, p, 11);
                Compat.apply(p, "slowness", 60, 0);
            }
        }, () -> {
            plugin.anchors().forget(stand);
            spawned.remove(stand);
            Fx.safeRemove(stand);
        });
    }

    /** 10. Estatica: durante un rato, correr cerca de KEM da calambre. */
    public void staticCharge() {
        if (!kemUp()) return;
        soundAt(loc(), "block.conduit.ambient", 1.4f, 1.4f);
        warn(Component.text("El aire alrededor de KEM chispea.", TextColor.color(KEM_RGB)));
        Map<UUID, Location> last = new HashMap<>();
        animate(160, tick -> {
            if (!kemUp()) throw Stop.now();
            if (tick % 10 != 0) return;
            for (Player p : targets(14)) {
                Location was = last.put(p.getUniqueId(), p.getLocation().clone());
                if (was == null || was.getWorld() != p.getWorld()) continue;
                if (was.distanceSquared(p.getLocation()) < 4.0) continue;
                strike(boss, p, 4);
                Compat.spawn(world(), Compat.ELECTRIC_SPARK, p.getLocation().add(0, 1, 0), 10,
                        0.3, 0.4, 0.3, 0.05);
                soundAt(p.getLocation(), "entity.bee.sting", 0.8f, 1.6f);
            }
        }, null);
    }

    /** 11. Detonacion de Oxido: cargas retardadas bajo varios a la vez. */
    public void rustDetonation() {
        if (!kemUp()) return;
        List<Player> marks = pickTargets(4);
        if (marks.isEmpty()) return;
        soundAt(loc(), "block.copper.place", 1.5f, 1.2f);
        for (Player m : marks) {
            Location at = Fx.ground(m.getLocation(), 4);
            animate(70, tick -> {
                if (tick < 50) {
                    if (tick % 6 == 0) {
                        Fx.telegraph(world(), at, 3.0, KEM_RGB);
                        soundAt(at, "block.note_block.hat", 0.9f, 0.6f + tick / 50f);
                    }
                    return;
                }
                if (tick != 50) return;
                Compat.spawn(world(), Compat.EXPLOSION, at.clone().add(0, 0.6, 0), 3, 0.5, 0.4, 0.5, 0);
                soundAt(at, "entity.generic.explode", 1.4f, 1.0f);
                for (Player p : Fx.playersNear(at, 3.2)) {
                    strike(boss, p, 14);
                    push(p, p.getLocation().toVector().subtract(at.toVector())
                            .normalize().multiply(0.7).setY(0.5));
                }
            }, null);
        }
    }

    /** 12. Salva de Cobre: tres andanadas de proyectiles al grupo entero. */
    public void copperVolley() {
        if (!kemUp()) return;
        soundAt(loc(), "block.copper.break", 1.4f, 1.5f);
        for (int round = 0; round < 3; round++) {
            later(round * 18, () -> {
                if (!kemUp()) return;
                for (Player p : pickTargets(4)) {
                    Location from = center();
                    Fx.beam(from, p.getLocation().add(0, 1, 0), 0.6, l ->
                            Compat.spawn(world(), Compat.DUST, l, 1, 0, 0, 0, 0,
                                    Compat.dust(KEM_RGB, 1.2f)));
                    strike(boss, p, 7);
                    soundAt(p.getLocation(), "block.copper.hit", 1.0f, 1.4f);
                }
            });
        }
    }

    // ------------------------------------------------------------- KAM: yunque

    /** 13. Puño de Cobre: el mandoble en cono, lento y con aviso. */
    public void copperFist() {
        if (!kamUp()) return;
        Player aim = Fx.nearest(kam.getLocation(), 9);
        if (aim == null) return;
        kam.lookAt(aim.getLocation().getX(), aim.getEyeLocation().getY(), aim.getLocation().getZ(),
                io.papermc.paper.entity.LookAnchor.EYES);
        final Vector dir = aim.getLocation().toVector().subtract(kam.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        dir.normalize();
        soundAt(kam.getLocation(), "entity.iron_golem.attack", 1.6f, 0.5f);

        animate(40, tick -> {
            if (!kamUp()) throw Stop.now();
            if (tick < 22) {
                Fx.arc(kam.getLocation().add(0, 0.3, 0), dir, 5.0, Math.toRadians(100), 14, l ->
                        Compat.spawn(world(), Compat.DUST, Fx.ground(l, 3).add(0, 0.15, 0), 1, 0, 0, 0, 0,
                                Compat.dust(KAM_RGB, 1.5f)));
                return;
            }
            if (tick != 22) return;
            soundAt(kam.getLocation(), "entity.iron_golem.damage", 1.7f, 0.4f);
            for (double r = 1.5; r <= 6.0; r += 1.2) {
                Fx.arc(kam.getLocation().add(0, 0.8, 0), dir, r, Math.toRadians(100), 12, l ->
                        Compat.spawn(world(), Compat.CRIT, l, 2, 0.1, 0.1, 0.1, 0.05));
            }
            for (Player p : Fx.playersNear(kam.getLocation(), 6.2)) {
                Vector to = p.getLocation().toVector().subtract(kam.getLocation().toVector()).setY(0);
                if (to.lengthSquared() > 0.01 && to.normalize().dot(dir) < 0.45) continue;
                strike(kam, p, 20);
                push(p, to.normalize().multiply(1.1).setY(0.45));
            }
        }, null);
    }

    /** 14. Embestida Verdosa: carga en linea recta arrollando lo que pille. */
    public void verdantCharge() {
        if (!kamUp()) return;
        Player aim = randomTarget();
        if (aim == null) return;
        final Vector run = aim.getLocation().toVector().subtract(kam.getLocation().toVector()).setY(0);
        if (run.lengthSquared() < 0.01) return;
        run.normalize();
        Set<UUID> hitOnce = new HashSet<>();
        soundAt(kam.getLocation(), "entity.ravager.roar", 1.5f, 0.5f);
        warn(Component.text("KAM carga.", TextColor.color(KAM_RGB)));

        animate(60, tick -> {
            if (!kamUp()) throw Stop.now();
            if (tick < 20) {
                for (double d = 1; d < 16; d += 1.3) {
                    Location g = Fx.ground(kam.getLocation().add(run.clone().multiply(d)), 4);
                    Compat.spawn(world(), Compat.DUST, g.clone().add(0, 0.2, 0), 1, 0.2, 0, 0.2, 0,
                            Compat.dust(KAM_RGB, 1.4f));
                }
                return;
            }
            kam.setVelocity(run.clone().multiply(0.9).setY(kam.getVelocity().getY()));
            Compat.spawn(world(), Compat.DUST_PLUME, kam.getLocation(), 4, 0.3, 0.1, 0.3, 0.01);
            for (Player p : Fx.playersNear(kam.getLocation(), 3.0)) {
                if (!hitOnce.add(p.getUniqueId())) continue;
                strike(kam, p, 18);
                push(p, run.clone().multiply(1.2).setY(0.5));
            }
        }, null);
    }

    /** 15. Pisoton del Yunque: onda corta pero brutal alrededor de KAM. */
    public void anvilStomp() {
        if (!kamUp()) return;
        Location c = Fx.ground(kam.getLocation(), 4);
        Set<UUID> struck = new HashSet<>();
        soundAt(c, "entity.iron_golem.attack", 1.7f, 0.4f);
        animate(50, tick -> {
            if (!kamUp()) throw Stop.now();
            if (tick < 18) {
                Fx.telegraph(world(), c, 7.0, KAM_RGB);
                return;
            }
            double radius = (tick - 18) * 0.5;
            if (radius > 7) return;
            Fx.shockwave(world(), c, radius, Compat.DUST_PLUME, 6);
            for (Player p : Fx.playersNear(c, radius + 1.0)) {
                if (p.getLocation().distance(c) < radius - 1.3) continue;
                if (!struck.add(p.getUniqueId())) continue;
                strike(kam, p, 16);
                push(p, p.getLocation().toVector().subtract(c.toVector()).normalize().setY(0.55));
            }
        }, null);
    }

    /** 16. Muro de Cobre: se cubre, aguanta el doble y devuelve parte del golpe. */
    public void copperWall() {
        if (!kamUp()) return;
        soundAt(kam.getLocation(), "block.copper.place", 1.6f, 0.6f);
        warn(Component.text("KAM se cubre: pegarle ahora devuelve el golpe.",
                TextColor.color(KAM_RGB)));
        animate(160, tick -> {
            if (!kamUp()) throw Stop.now();
            Compat.apply(kam, "resistance", 30, 2);
            if (tick % 6 == 0) {
                Fx.sphere(kam.getLocation().add(0, 1.4, 0), 2.0, 20, l ->
                        Compat.spawn(world(), Compat.DUST, l, 1, 0, 0, 0, 0, Compat.dust(KAM_RGB, 1.6f)));
            }
            if (tick % 30 != 0) return;
            for (Player p : Fx.playersNear(kam.getLocation(), 4.5)) {
                strike(kam, p, 5);
                Compat.spawn(world(), Compat.CRIT, p.getLocation().add(0, 1, 0), 8, 0.2, 0.3, 0.2, 0.05);
            }
        }, null);
    }

    /** 17. Barrido de Brazos: gira sobre si mismo repartiendo a todos los pegados. */
    public void armSweep() {
        if (!kamUp()) return;
        soundAt(kam.getLocation(), "entity.iron_golem.attack", 1.5f, 0.8f);
        animate(60, tick -> {
            if (!kamUp()) throw Stop.now();
            Location c = kam.getLocation().add(0, 1.0, 0);
            double a = tick * 0.5;
            Fx.ring(c, 4.5, 10, a, l ->
                    Compat.spawn(world(), Compat.SWEEP_ATTACK, l, 1, 0.05, 0.05, 0.05, 0));
            if (tick % 12 != 0) return;
            for (Player p : Fx.playersNear(kam.getLocation(), 4.8)) {
                strike(kam, p, 8);
                push(p, p.getLocation().toVector().subtract(kam.getLocation().toVector())
                        .normalize().multiply(0.6).setY(0.3));
            }
        }, null);
    }

    /** 18. Agarre y Lanzamiento: coge al mas cercano y lo tira contra los suyos. */
    public void grabAndThrow() {
        if (!kamUp()) return;
        Player prey = Fx.nearest(kam.getLocation(), 5);
        if (prey == null) return;
        soundAt(prey.getLocation(), "entity.iron_golem.attack", 1.6f, 0.7f);
        prey.sendActionBar(Component.text("KAM te tiene agarrado.", NamedTextColor.RED, TextDecoration.BOLD));
        root(prey, 30);

        animate(50, tick -> {
            if (!kamUp() || !Fx.isFightable(prey)) throw Stop.now();
            if (tick < 30) {
                Fx.beam(kam.getLocation().add(0, 1.5, 0), prey.getLocation().add(0, 1, 0), 0.5, l ->
                        Compat.spawn(world(), Compat.DUST, l, 1, 0, 0, 0, 0, Compat.dust(KAM_RGB, 1.4f)));
                return;
            }
            if (tick != 30) return;
            List<Player> rest = targets(24);
            rest.remove(prey);
            Location to = rest.isEmpty() ? loc().clone().add(6, 0, 6) : rest.get(0).getLocation();
            Vector fly = to.toVector().subtract(prey.getLocation().toVector()).setY(0);
            if (fly.lengthSquared() < 0.01) fly.add(new Vector(1, 0, 0));
            lift(prey, fly.normalize().multiply(1.5).setY(0.85));
            strike(kam, prey, 12);
            soundAt(prey.getLocation(), "entity.player.attack.knockback", 1.5f, 0.6f);
            later(20, () -> {
                for (Player p : Fx.playersNear(prey.getLocation(), 3.0)) {
                    if (p.equals(prey)) continue;
                    strike(kam, p, 9);
                }
            });
        }, null);
    }

    /** 19. Provocacion: quien se aleje de KAM lo paga; se acabo el dar vueltas. */
    public void taunt() {
        if (!kamUp()) return;
        soundAt(kam.getLocation(), "entity.iron_golem.repair", 1.5f, 0.5f);
        titleNear(Component.text("KAM RUGE", TextColor.color(KAM_RGB), TextDecoration.BOLD),
                Component.text("Alejarse de el duele", NamedTextColor.GRAY));
        animate(160, tick -> {
            if (!kamUp()) throw Stop.now();
            if (tick % 20 != 0) return;
            for (Player p : targets()) {
                double d = p.getLocation().distance(kam.getLocation());
                if (d < 9) continue;
                strike(kam, p, 5);
                Compat.spawn(world(), Compat.DUST, p.getLocation().add(0, 2, 0), 8, 0.2, 0.2, 0.2, 0,
                        Compat.dust(KAM_RGB, 1.4f));
                p.sendActionBar(Component.text("Demasiado lejos de KAM.", NamedTextColor.RED));
            }
        }, null);
    }

    /** 20. Placa Reactiva: durante unos segundos devuelve en area lo que le peguen. */
    public void reactivePlating() {
        if (!kamUp()) return;
        soundAt(kam.getLocation(), "block.copper.hit", 1.6f, 0.5f);
        warn(Component.text("KAM se electrifica.", TextColor.color(KAM_RGB)));
        animate(120, tick -> {
            if (!kamUp()) throw Stop.now();
            if (tick % 4 == 0) {
                Fx.ring(kam.getLocation().add(0, 1.2, 0), 1.8, 10, tick * 0.3, l ->
                        Compat.spawn(world(), Compat.ELECTRIC_SPARK, l, 1, 0, 0, 0, 0.02));
            }
            if (tick % 25 != 0) return;
            for (Player p : Fx.playersNear(kam.getLocation(), 6)) {
                strike(kam, p, 6);
                Compat.apply(p, "slowness", 40, 0);
            }
        }, null);
    }

    /** 21. Salto de Yunque: sube y cae de lleno sobre la marca. */
    public void anvilLeap() {
        if (!kamUp()) return;
        Player target = randomTarget();
        if (target == null) return;
        Location mark = Fx.ground(target.getLocation(), 5);
        soundAt(kam.getLocation(), "entity.iron_golem.step", 1.6f, 0.5f);

        animate(70, tick -> {
            if (!kamUp()) throw Stop.now();
            if (tick == 0) {
                kam.setVelocity(new Vector(0, 1.3, 0));
                return;
            }
            if (tick < 30) {
                Fx.telegraph(world(), mark, 4.0, KAM_RGB);
                return;
            }
            if (tick == 30) {
                kam.teleport(mark.clone().add(0, 10, 0));
                return;
            }
            if (tick < 40) {
                kam.setVelocity(new Vector(0, -1.6, 0));
                return;
            }
            if (tick != 40) return;
            kam.teleport(mark);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, mark, 1);
            Compat.spawn(world(), Compat.BLOCK, mark, 60, 1.2, 0.4, 1.2, 0.2,
                    Material.OXIDIZED_COPPER.createBlockData());
            soundAt(mark, "entity.iron_golem.damage", 1.8f, 0.4f);
            for (Player p : Fx.playersNear(mark, 5)) {
                strike(kam, p, 19);
                push(p, p.getLocation().toVector().subtract(mark.toVector())
                        .normalize().multiply(1.0).setY(0.6));
            }
        }, null);
    }

    /** 22. Muralla: se planta delante del grupo y frena a quien intente cruzarlo. */
    public void bulwark() {
        if (!kamUp()) return;
        soundAt(kam.getLocation(), "block.copper.place", 1.5f, 0.7f);
        animate(140, tick -> {
            if (!kamUp()) throw Stop.now();
            Compat.apply(kam, "resistance", 30, 1);
            if (tick % 8 == 0) {
                Fx.ring(Fx.ground(kam.getLocation(), 4).add(0, 0.2, 0), 5.0, 22, l ->
                        Compat.spawn(world(), Compat.DUST, l, 1, 0, 0, 0, 0, Compat.dust(KAM_RGB, 1.3f)));
            }
            if (tick % 15 != 0) return;
            for (Player p : Fx.playersNear(kam.getLocation(), 5.2)) {
                Compat.apply(p, "slowness", 40, 2);
            }
        }, null);
    }

    // ------------------------------------------------------------ los dos a la vez

    /** 23. Sincronia: si estan juntos, se curan el uno al otro. Hay que separarlos. */
    public void synchrony() {
        if (!kemUp() || !kamUp()) return;
        double d = boss.getLocation().distance(kam.getLocation());
        if (d > 14) {
            warn(Component.text("Los gemelos se buscan. No los dejen juntarse.", ACCENT));
        }
        soundAt(loc(), "block.beacon.power_select", 1.5f, 1.2f);
        animate(120, tick -> {
            if (!kemUp() || !kamUp()) throw Stop.now();
            Location a = center();
            Location b = kam.getLocation().add(0, 1.2, 0);
            if (a.distance(b) > 12) {
                if (tick % 20 == 0) warn(Component.text("Separados: la sincronia no prende.",
                        NamedTextColor.GREEN));
                return;
            }
            if (tick % 4 == 0) {
                Fx.beam(a, b, 0.5, l -> {
                    Compat.spawn(world(), Compat.DUST, l, 1, 0, 0, 0, 0, Compat.dust(KEM_RGB, 1.2f));
                    Compat.spawn(world(), Compat.DUST, l, 1, 0, 0, 0, 0, Compat.dust(KAM_RGB, 1.2f));
                });
            }
            if (tick % 20 != 0) return;
            heal(boss, 12);
            heal(kam, 12);
            Compat.spawn(world(), Compat.HEART, a, 3, 0.4, 0.4, 0.4, 0);
            Compat.spawn(world(), Compat.HEART, b, 3, 0.4, 0.4, 0.4, 0);
        }, null);
    }

    private void heal(LivingEntity e, double amount) {
        if (e == null || !e.isValid()) return;
        double max = Compat.getAttribute(e, "max_health", e.getHealth());
        e.setHealth(Math.min(max, e.getHealth() + amount));
    }

    /** 24. Resonancia Gemela: los dos golpean el suelo a la vez; dos ondas cruzadas. */
    public void twinResonance() {
        if (!kemUp() && !kamUp()) return;
        soundAt(loc(), "block.copper.break", 1.8f, 0.4f);
        titleNear(Component.text("RESONANCIA GEMELA", ACCENT, TextDecoration.BOLD),
                Component.text("Dos ondas, ningun hueco comodo", NamedTextColor.GRAY));
        if (kemUp()) waveFrom(boss.getLocation(), KEM_RGB, 11);
        if (kamUp()) later(18, () -> waveFrom(kam.getLocation(), KAM_RGB, 11));
    }

    private void waveFrom(Location from, int rgb, double maxRadius) {
        Location c = Fx.ground(from, 4);
        Set<UUID> touched = new HashSet<>();
        animate(60, tick -> {
            double radius = tick * 0.45;
            if (radius > maxRadius) throw Stop.now();
            Fx.ring(c, radius, Math.max(12, (int) (radius * 5)), l ->
                    Compat.spawn(world(), Compat.DUST, Fx.ground(l, 3).add(0, 0.2, 0), 1, 0, 0, 0, 0,
                            Compat.dust(rgb, 1.5f)));
            for (Player p : Fx.playersNear(c, radius + 1.0)) {
                if (p.getLocation().distance(c) < radius - 1.4) continue;
                if (!touched.add(p.getUniqueId())) continue;
                strike(boss, p, 11);
                push(p, p.getLocation().toVector().subtract(c.toVector()).normalize().setY(0.35));
            }
        }, null);
    }

    /** 25. Relevo: se intercambian el sitio; el yunque aparece donde estaba el artillero. */
    public void relay() {
        if (!kemUp() || !kamUp()) return;
        Location a = boss.getLocation();
        Location b = kam.getLocation();
        Compat.spawn(world(), Compat.DUST, a.clone().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0,
                Compat.dust(KEM_RGB, 1.7f));
        Compat.spawn(world(), Compat.DUST, b.clone().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0,
                Compat.dust(KAM_RGB, 1.7f));
        boss.teleport(b);
        kam.teleport(a);
        soundAt(a, "entity.enderman.teleport", 1.4f, 0.6f);
        soundAt(b, "entity.enderman.teleport", 1.4f, 0.6f);
        warn(Component.text("Se han cambiado el sitio.", ACCENT));
        for (Player p : Fx.playersNear(a, 4)) strike(kam, p, 8);
        for (Player p : Fx.playersNear(b, 4)) strike(boss, p, 8);
    }

    // -------------------------------------------------------------------- escuchas

    /**
     * Lanzar una habilidad a mano tiene que alcanzar TAMBIEN a las de KAM, que no
     * viven en la lista del motor sino en su propio pool. Sin esto `/anomaly test`
     * no puede probar la mitad del jefe.
     */
    @Override
    public boolean castNow(String abilityId) {
        for (Ability a : kamPool) {
            if (!a.id().equalsIgnoreCase(abilityId)) continue;
            a.startCooldown(ticks());
            kamBusyUntil = ticks() + a.castTicks();
            try {
                a.cast(this);
            } catch (Throwable t) {
                plugin.getLogger().warning("Habilidad " + a.id() + " de KAM fallo a mano: " + t);
            }
            return true;
        }
        return super.castNow(abilityId);
    }

    /** KAM cae: no muere del todo, se queda abatido esperando a que caiga su hermano. */
    @Override
    public void onMinionDeath(LivingEntity minion) {
        if (kam == null || minion == null || !minion.getUniqueId().equals(kam.getUniqueId())) return;
        downKam();
    }

    /**
     * El dano hecho a KAM tambien cuenta: el manager ya lo apunta para el botin, y
     * aqui se aprovecha para que la barra reaccione en el acto en vez de al tick.
     */
    @Override
    public void onMinionDamaged(LivingEntity minion, Player attacker, double amount) {
        if (kam == null || minion == null || !minion.getUniqueId().equals(kam.getUniqueId())) return;
        if (kamBar != null) kamBar.progress((float) kamFraction());
    }

    @Override
    protected void onPhaseChange(int from, int to) {
        // Sin fases a proposito: lo unico que marca el ritmo es cual de los dos sigue en pie.
    }

    @Override
    public void onDeath() {
        Location l = loc();
        soundAt(l, "entity.iron_golem.death", 1.8f, 0.4f);
        soundAt(l, "block.copper.break", 1.7f, 0.5f);
        titleNear(Component.text("KEM  y  KAM", ACCENT, TextDecoration.BOLD),
                Component.text("Los dos a la vez, por fin", NamedTextColor.GRAY));

        for (int i = 0; i < 3; i++) {
            final int ring = i;
            later(i * 10, () -> {
                Fx.ring(l, 3 + ring * 3.0, 30 + ring * 8, p ->
                        Compat.spawn(world(), Compat.DUST, p, 2, 0.1, 0.4, 0.1, 0,
                                Compat.dust(ring % 2 == 0 ? KEM_RGB : KAM_RGB, 2.0f)));
                Compat.spawn(world(), Compat.BLOCK, l.clone().add(0, 1, 0), 40, 1.5, 1.0, 1.5, 0.12,
                        Material.COPPER_BLOCK.createBlockData());
            });
        }
    }

    @Override
    public void cleanup() {
        for (Player p : new HashSet<>(barViewers)) {
            if (kemBar != null) p.hideBossBar(kemBar);
            if (kamBar != null) p.hideBossBar(kamBar);
        }
        barViewers.clear();
        // Nadie se queda de piedra ni marcado despues de que la pelea termine.
        for (UUID id : new HashSet<>(petrified)) {
            Player p = plugin.getServer().getPlayer(id);
            if (p == null) continue;
            Glow.clear(p);
            p.setGlowing(false);
        }
        petrified.clear();
        rust.clear();
        if (kam != null && kam.isValid()) {
            Glow.clear(kam);
            spawned.remove(kam);
            Fx.safeRemove(kam);
        }
        kam = null;
        dropStatue(kemStatue, KEM_RGB);
        dropStatue(kamStatue, KAM_RGB);
        kemStatue = null;
        kamStatue = null;
        super.cleanup();
    }

    private void titleNear(Component title, Component subtitle) {
        for (Player p : Fx.viewersNear(loc(), 90)) {
            p.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(500))));
        }
    }
}
