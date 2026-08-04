package net.zakiworld.anomaly.boss;

import net.kyori.adventure.text.Component;
import net.zakiworld.anomaly.AnomalyPlugin;
import net.zakiworld.anomaly.core.Anim;
import net.zakiworld.anomaly.core.Compat;
import net.zakiworld.anomaly.core.Fx;
import net.zakiworld.anomaly.core.Glow;
import net.zakiworld.anomaly.core.Tags;
import net.zakiworld.anomaly.core.ActiveAnomaly;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntConsumer;

/**
 * Base de todo jefe de anomalia: lleva la cuenta de la fase, decide que habilidad
 * lanzar y limpia todo lo que haya creado cuando el evento termina.
 *
 * Las subclases solo declaran sus habilidades y como se ve el jefe; el ritmo del
 * combate y la limpieza son iguales para todos.
 */
public abstract class BossFight {

    protected final AnomalyPlugin plugin;
    protected final ActiveAnomaly event;
    protected final Location arena;
    protected final Random random = new Random();
    protected final List<Ability> abilities = new ArrayList<>();
    /** Esbirros y entidades decorativas; se borran todas al acabar. */
    protected final List<Entity> spawned = new ArrayList<>();

    /** Tope duro del atributo max_health en Minecraft. Pasarse de aqui rompe el spawn. */
    public static final double VANILLA_HEALTH_CAP = 1024;

    protected LivingEntity boss;
    private int phase = 1;
    private long ticks;
    private long busyUntil;
    private boolean finished;
    private double damageScale = 1.0;
    private long invulnerableSince;

    /** La animacion guionizada mas larga es la resurreccion (13 s); esto le da margen. */
    private static final int MAX_INVULNERABLE_TICKS = 400;

    protected BossFight(AnomalyPlugin plugin, ActiveAnomaly event, Location arena) {
        this.plugin = plugin;
        this.event = event;
        this.arena = arena.clone();
    }

    // ------------------------------------------------------------------- contrato

    /** Crea el jefe y lo deja en el mundo. Debe rellenar el campo boss. */
    public abstract void spawn();

    /** La animacion de cambio de fase. Se llama una vez por transicion. */
    protected abstract void onPhaseChange(int from, int to);

    /** La animacion de muerte, antes de repartir el botin. */
    public abstract void onDeath();

    /** Nombre corto para los mensajes de combate. */
    public abstract String bossName();

    // --------------------------------------------------------------------- estado

    public LivingEntity entity() {
        return boss;
    }

    public Location arena() {
        return arena;
    }

    public int phase() {
        return phase;
    }

    public long ticks() {
        return ticks;
    }

    public List<Ability> abilities() {
        return abilities;
    }

    public boolean alive() {
        return boss != null && boss.isValid() && !boss.isDead();
    }

    /**
     * Fija la vida del jefe respetando el tope de 1024 de Minecraft.
     *
     * Si la vida configurada lo supera, la entidad se queda en el tope y el exceso se
     * cobra reduciendo el dano que recibe: para el jugador la pelea dura exactamente lo
     * que dicen los ajustes, pero el servidor nunca ve un valor ilegal. La barra sigue
     * siendo correcta porque es una fraccion, no un absoluto.
     */
    protected void applyHealth(double logicalMax) {
        double entityMax = Math.min(logicalMax, VANILLA_HEALTH_CAP);
        Compat.setAttribute(boss, "max_health", entityMax);
        boss.setHealth(Math.min(entityMax, Compat.getAttribute(boss, "max_health", entityMax)));
        this.damageScale = logicalMax <= 0 ? 1.0 : entityMax / logicalMax;
    }

    /** Cuanto se multiplica el dano entrante para que la vida efectiva sea la configurada. */
    public double damageScale() {
        return damageScale;
    }

    public double healthFraction() {
        if (!alive()) return 0;
        double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
        return max <= 0 ? 0 : Fx.clamp(boss.getHealth() / max, 0, 1);
    }

    /** Marca al jefe como ocupado: no lanzara nada mas hasta que acabe la animacion. */
    public void busyFor(int ticksBusy) {
        busyUntil = Math.max(busyUntil, ticks + ticksBusy);
    }

    public boolean busy() {
        return ticks < busyUntil;
    }

    // ----------------------------------------------------------------------- ciclo

    public void tick() {
        if (finished) return;
        ticks++;
        if (!alive()) return;

        // Ni se quema al amanecer ni se ahoga: el combate no lo decide el entorno.
        boss.setFireTicks(0);
        boss.setRemainingAir(boss.getMaximumAir());

        watchInvulnerability();

        int newPhase = PhaseBars.currentPhase(healthFraction());
        if (newPhase != phase) {
            int old = phase;
            phase = newPhase;
            for (Ability a : abilities) a.reset();
            try {
                onPhaseChange(old, newPhase);
            } catch (Throwable t) {
                plugin.getLogger().warning("Fallo en el cambio de fase " + old + "->" + newPhase + ": " + t);
            }
        }

        ambient();

        if (!busy() && ticks % 10 == 0) {
            tryCast();
        }
    }

    /**
     * Red de seguridad contra el peor fallo posible: que una animacion guionizada
     * ponga al jefe invulnerable y muera por el camino antes de devolverlo a la
     * normalidad. Eso deja un jefe inmortal y una pelea que no se puede terminar.
     *
     * Si lleva demasiado tiempo intocable, se le quita a la fuerza y queda constancia.
     */
    private void watchInvulnerability() {
        if (!boss.isInvulnerable()) {
            invulnerableSince = 0;
            return;
        }
        if (invulnerableSince == 0) {
            invulnerableSince = ticks;
            return;
        }
        if (ticks - invulnerableSince <= MAX_INVULNERABLE_TICKS) return;
        boss.setInvulnerable(false);
        invulnerableSince = 0;
        plugin.getLogger().warning("El jefe llevaba mas de " + (MAX_INVULNERABLE_TICKS / 20)
                + "s invulnerable; se le ha quitado a la fuerza para que la pelea pueda acabar.");
    }

    /** Efecto de fondo constante; por defecto no hay ninguno. */
    protected void ambient() {
    }

    /**
     * Lanza una habilidad concreta ahora mismo, saltandose fase y enfriamiento.
     * Es lo que usa /anomaly test para poder revisar una animacion sin esperar.
     *
     * @return false si no existe ninguna habilidad con ese id
     */
    public boolean castNow(String abilityId) {
        for (Ability a : abilities) {
            if (!a.id().equalsIgnoreCase(abilityId)) continue;
            a.startCooldown(ticks);
            busyFor(a.castTicks());
            try {
                a.cast(this);
            } catch (Throwable t) {
                plugin.getLogger().warning("Habilidad " + a.id() + " fallo al lanzarse a mano: " + t);
            }
            return true;
        }
        return false;
    }

    private void tryCast() {
        List<Ability> pool = new ArrayList<>();
        int total = 0;
        for (Ability a : abilities) {
            if (!a.availableIn(phase) || !a.ready(ticks)) continue;
            pool.add(a);
            total += a.weight();
        }
        if (pool.isEmpty()) return;

        int roll = random.nextInt(total);
        for (Ability a : pool) {
            roll -= a.weight();
            if (roll >= 0) continue;
            a.startCooldown(ticks);
            busyFor(a.castTicks());
            try {
                a.cast(this);
            } catch (Throwable t) {
                plugin.getLogger().warning("Habilidad " + a.id() + " fallo al lanzarse: " + t);
            }
            return;
        }
    }

    // -------------------------------------------------------------------- limpieza

    public void cleanup() {
        finished = true;
        for (Entity e : spawned) {
            Glow.clear(e);
            Fx.safeRemove(e);
        }
        spawned.clear();
        if (boss != null) {
            // Sacarlo del equipo de brillo antes de borrarlo: si no, el equipo se llena
            // de UUID de entidades muertas y el marcador crece sin parar.
            Glow.clear(boss);
            Fx.safeRemove(boss);
        }
    }

    /** Registra una entidad para que la limpieza final se la lleve por delante. */
    protected <T extends Entity> T track(T entity) {
        spawned.add(entity);
        return entity;
    }

    /** Borra una entidad decorativa tras N ticks. */
    protected void expire(Entity entity, int ticksToLive) {
        Anim.later(plugin, ticksToLive, () -> {
            spawned.remove(entity);
            Fx.safeRemove(entity);
        });
    }

    // -------------------------------------------------------------------- utiles

    public World world() {
        return arena.getWorld();
    }

    public Location loc() {
        return alive() ? boss.getLocation() : arena.clone();
    }

    public Location center() {
        return alive() ? boss.getLocation().add(0, 1, 0) : arena.clone().add(0, 1, 0);
    }

    /** Jugadores peleables dentro del radio de combate. */
    public List<Player> targets() {
        return Fx.playersNear(loc(), plugin.settings().participationRadius());
    }

    public List<Player> targets(double radius) {
        return Fx.playersNear(loc(), radius);
    }

    public Player randomTarget() {
        List<Player> list = targets();
        return list.isEmpty() ? null : list.get(random.nextInt(list.size()));
    }

    public void sound(String key, float volume, float pitch) {
        Compat.sound(world(), loc(), key, volume, pitch);
    }

    public void soundAt(Location where, String key, float volume, float pitch) {
        Compat.sound(world(), where, key, volume, pitch);
    }

    public void particle(Particle p, Location where, int count, double ox, double oy, double oz, double extra) {
        Compat.spawn(world(), p, where, count, ox, oy, oz, extra);
    }

    public void animate(int duration, IntConsumer perTick, Runnable onEnd) {
        Anim.run(plugin, duration, perTick, onEnd);
    }

    public void later(int delay, Runnable action) {
        Anim.later(plugin, delay, action);
    }

    /**
     * Cuanto se multiplica el dano que RECIBE el jefe. Lo usan las anomalias para
     * volverse mas fragiles o mas duras segun lo que este pasando en la pelea.
     */
    public double incomingDamageMultiplier() {
        return 1.0;
    }

    /**
     * Igual que el anterior pero sabiendo QUIEN pega. Lo usa el Storm Rider para que
     * en su fase de vuelo la espada no valga y haya que sacar el arco.
     */
    public double incomingDamageMultiplier(Entity damager) {
        return incomingDamageMultiplier();
    }

    /**
     * Aviso de que el jefe (o uno de sus esbirros) acaba de pegar a un jugador.
     * Por defecto no hace nada; el Conejo Asesino lo usa para multiplicarse.
     */
    public void onDealtDamage(Player victim, org.bukkit.entity.Entity dealer) {
    }

    /**
     * Dano de una habilidad a un jugador, atribuido al jefe para que cuente como
     * muerte suya. Todas las habilidades pasan por aqui, y por eso este es el unico
     * sitio donde hace falta aplicar el multiplicador configurado.
     */
    public void hit(Player p, double amount) {
        if (p == null || !Fx.isFightable(p)) return;
        amount *= plugin.registry().damageMultiplier(event.type());
        try {
            if (boss != null && boss.isValid()) {
                p.damage(amount, boss);
            } else {
                p.damage(amount);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Empuje. Respeta el interruptor de config: en Rip el usuario prohibio que las
     * animaciones movieran a nadie, y aqui se puede apagar igual sin tocar codigo.
     */
    public void push(Player p, Vector velocity) {
        if (p == null || !plugin.settings().allowKnockback()) return;
        try {
            p.setVelocity(p.getVelocity().add(velocity));
        } catch (Throwable ignored) {
        }
    }

    /** Aviso en la barra de accion a todos los que estan viendo la pelea, peleen o no. */
    public void warn(Component message) {
        for (Player p : Fx.viewersNear(loc(), plugin.settings().participationRadius())) {
            p.sendActionBar(message);
        }
    }

    protected void markMinion(Entity e) {
        Tags.markMinion(e, event.typeId());
        Tags.markEvent(e, event.id());
        track(e);
    }
}
