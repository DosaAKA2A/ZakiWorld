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
    /** Cuerpo visible con forma de persona, si el jefe lleva uno. Ver wearShell(). */
    protected LivingEntity shell;
    private int phase = 1;
    private long ticks;
    private long busyUntil;
    private boolean finished;
    private double damageScale = 1.0;
    private long invulnerableSince;
    /** Jugadores con permiso de vuelo temporal y el tick en que se les retira. */
    private final java.util.Map<java.util.UUID, Long> airTime = new java.util.HashMap<>();

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

    /**
     * Cuanto hay que esperar tras la muerte antes de barrer la escena.
     *
     * Por defecto lo que dura una animacion de muerte normal. Herbola lo alarga porque
     * su llanto del Cantor sigue quince segundos DESPUES de que ella caiga, y si se
     * limpia antes se borra al loro en mitad de la cuenta atras.
     */
    public int deathAnimationTicks() {
        return 110;
    }

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
        tickAirTime();
        tickShell();

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
        if (allowLongInvulnerability()) return;
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
        clearAirTime();
        if (shell != null) {
            spawned.remove(shell);
            Fx.safeRemove(shell);
            shell = null;
        }
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
     * Aviso de que uno de los esbirros del jefe acaba de morir. El Coro Abisal lo usa
     * para comprobar si lo mataron en el orden correcto.
     */
    public void onMinionDeath(LivingEntity minion) {
    }

    /**
     * Aviso de que al jefe le acaban de hacer dano, con la cantidad ya final.
     * Darkness lo usa para saber cuando le han roto el ritual de curacion.
     */
    public void onDamaged(Player attacker, double amount) {
    }

    /**
     * Fraccion de vida por debajo de la cual el jefe NO puede bajar todavia.
     *
     * Un jugador con equipo bueno puede quitar media barra de un golpe, y eso se llevaba
     * por delante fases enteras: al Mimic le reventaban el cofre de la fase 2 y se moria
     * sin llegar a robarle la cara a nadie. Con esto el golpe se recorta para dejarlo
     * justo en el umbral, la transicion salta en el tick siguiente y la pelea se juega
     * entera. Por defecto 0: los demas jefes no lo necesitan.
     */
    public double survivalFloor() {
        return 0;
    }

    /**
     * Aviso de que un jugador ha roto un bloque durante el evento.
     *
     * @return true si hay que cancelar la rotura. Lo usan las anomalias que construyen
     *         algo de verdad, como los pilares de la Quimera: solo se puede romper la
     *         pieza central, y hacerlo derrumba el resto.
     */
    public boolean onBlockBroken(org.bukkit.block.Block block, Player who) {
        return false;
    }

    /**
     * Si un jefe puede estar invulnerable mucho rato POR DISENO.
     *
     * Por defecto no: el vigilante le quita la invulnerabilidad a la fuerza porque
     * casi siempre significa que una animacion guionizada murio a medias. El Coro
     * Abisal es la excepcion, porque su nucleo solo se abre cuando cae el coro.
     */
    protected boolean allowLongInvulnerability() {
        return false;
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
     *
     * Si el empuje tiene componente vertical seria, se le da permiso de vuelo temporal
     * al jugador. Sin eso el servidor lo expulsa por "volar" o por "moverse muy rapido"
     * en cuanto una habilidad lo levanta, que es exactamente lo que pasaba.
     */
    public void push(Player p, Vector velocity) {
        if (p == null || !plugin.settings().allowKnockback()) return;
        try {
            // Umbral bajo a proposito: el servidor no mide "cuanto" te levantan, mide que
            // estes en el aire sin motivo. Un empujon flojo hacia arriba tambien expulsa.
            if (velocity.getY() > 0.15) grantAirTime(p, 160);
            p.setVelocity(p.getVelocity().add(velocity));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Mueve al jugador a una velocidad EXACTA, en vez de sumarsela a la que lleva.
     *
     * Existe porque las habilidades que levantan despacio (el campo cinetico de Darkness)
     * llamaban a setVelocity a pelo y se saltaban el permiso de vuelo, que es justo lo
     * que hace que el servidor eche a la gente por "volar". Todo lo que levante a un
     * jugador tiene que pasar por aqui o por push().
     */
    public void lift(Player p, Vector velocity) {
        if (p == null || !plugin.settings().allowKnockback()) return;
        try {
            if (velocity.getY() > 0.05) grantAirTime(p, 160);
            p.setVelocity(velocity);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Le pone al jefe un CUERPO DE PERSONA con una skin concreta.
     *
     * El truco: la pelea la sigue llevando el mob de siempre —con su IA, su
     * pathfinding y su golpe— pero se vuelve invisible, y encima se le pega un
     * MANNEQUIN, que es una entidad viva con forma de jugador y perfil de skin propio.
     * Es la unica forma nativa de que se vea EXACTAMENTE la skin pedida: ni depende de
     * que haya LibsDisguises ni de que Mojang resuelva un nombre.
     *
     * Los golpes que reciba el maniqui se le pasan al jefe (lo hace AnomalyManager),
     * asi que para quien pelea todo funciona como si el cuerpo visible fuera el jefe.
     */
    protected void wearShell(io.papermc.paper.datacomponent.item.ResolvableProfile profile, Component name) {
        if (boss == null || profile == null) return;
        try {
            org.bukkit.entity.Mannequin body = world().spawn(boss.getLocation(),
                    org.bukkit.entity.Mannequin.class, m -> {
                        m.setProfile(profile);
                        m.setPersistent(false);
                        m.setGravity(false);
                        m.setCollidable(false);
                        m.setSilent(true);
                        if (name != null) {
                            m.customName(name);
                            m.setCustomNameVisible(true);
                        }
                    });
            shell = body;
            markMinion(body);
            boss.setInvisible(true);
            boss.setCustomNameVisible(false);
            // Y CALLADO. El mob de debajo sigue siendo un zombi (o lo que sea) y sus
            // gruñidos delatan lo que hay dentro del disfraz; los sonidos del jefe los
            // pone cada habilidad a mano.
            boss.setSilent(true);
            // El perfil, otra vez un par de ticks despues. Si por lo que sea no viajo
            // en el paquete de aparicion, este segundo intento lo arregla.
            later(2, () -> {
                if (shell instanceof org.bukkit.entity.Mannequin m && m.isValid()) m.setProfile(profile);
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("No se pudo poner el cuerpo con skin: " + t);
            shell = null;
        }
    }

    /** El cuerpo visible, si lo hay. Lo consulta el manager para redirigir los golpes. */
    public LivingEntity shell() {
        return shell;
    }

    /** Le cambia la cara al cuerpo visible una vez resuelta la skin. */
    public void reskinShell(io.papermc.paper.datacomponent.item.ResolvableProfile profile) {
        if (profile == null) return;
        if (shell instanceof org.bukkit.entity.Mannequin m && m.isValid()) m.setProfile(profile);
    }

    /** Lo que de verdad se ve: el maniqui si lo lleva, y si no el propio jefe. */
    public LivingEntity body() {
        return shell != null && shell.isValid() ? shell : boss;
    }

    /** Pinta el contorno del cuerpo VISIBLE, que con maniqui no es el mismo mob. */
    protected void glowBody(net.kyori.adventure.text.format.NamedTextColor color) {
        LivingEntity target = body();
        if (target == null) return;
        Glow.clear(target);
        if (color != null) Glow.apply(target, color);
    }

    /** Renombra el cuerpo visible y el jefe a la vez. */
    protected void renameBody(Component name) {
        if (boss != null && boss.isValid()) boss.customName(name);
        if (shell != null && shell.isValid()) {
            shell.customName(name);
            shell.setCustomNameVisible(true);
        }
    }

    /** El cuerpo sigue al jefe pegado, con su misma orientacion y su mismo equipo. */
    private void tickShell() {
        if (shell == null) return;
        if (!shell.isValid()) {
            shell = null;
            return;
        }
        try {
            shell.teleport(boss.getLocation());
            org.bukkit.inventory.EntityEquipment from = boss.getEquipment();
            org.bukkit.inventory.EntityEquipment to = shell.getEquipment();
            if (from != null && to != null && ticks() % 20 == 0) {
                to.setHelmet(from.getHelmet());
                to.setChestplate(from.getChestplate());
                to.setLeggings(from.getLeggings());
                to.setBoots(from.getBoots());
                to.setItemInMainHand(from.getItemInMainHand());
                to.setItemInOffHand(from.getItemInOffHand());
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Gira al jefe para que mire a un punto. Paper solo trae la version con ancla, y
     * ademas cambia de firma cada pocas versiones, asi que se aisla aqui.
     */
    public void face(Location at) {
        if (boss == null || !boss.isValid() || at == null) return;
        try {
            boss.lookAt(at.getX(), at.getY(), at.getZ(), io.papermc.paper.entity.LookAnchor.EYES);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Amarra a un jugador al suelo: ni se mueve ni salta, pero NO sale volando.
     *
     * La version anterior usaba jump_boost con amplificador 128, que era el truco de las
     * versiones viejas para anular el salto (128 se leia como -128 en un byte). En las
     * actuales el amplificador es un entero de verdad, asi que 128 son ciento veintiocho
     * niveles de salto: al intentar saltar te ibas al cielo y el servidor te expulsaba
     * por moverte demasiado rapido. Ahora se anula el salto a mano, tick a tick.
     */
    public void root(Player p, int ticksHeld) {
        if (p == null || !Fx.isFightable(p)) return;
        Compat.apply(p, "slowness", ticksHeld, 250);
        Compat.apply(p, "mining_fatigue", ticksHeld, 1);
        animate(ticksHeld, tick -> {
            if (!Fx.isFightable(p)) throw net.zakiworld.anomaly.core.Stop.now();
            Vector v = p.getVelocity();
            // Solo se corta lo que sube; caer se deja, que si no queda flotando.
            if (v.getY() > 0) p.setVelocity(new Vector(v.getX() * 0.2, -0.08, v.getZ() * 0.2));
        }, null);
    }

    /**
     * Deja al jugador "volar" durante un rato a ojos del servidor.
     *
     * No le da vuelo de creativo: solo desactiva la comprobacion que lo echaria del
     * servidor por estar en el aire sin motivo. Se le devuelve su estado original
     * despues, y nunca se le quita el vuelo a quien ya lo tuviera de antes.
     */
    public void grantAirTime(Player p, int ticks) {
        if (p == null || !p.isOnline()) return;
        if (p.getAllowFlight()) return;
        if (airTime.containsKey(p.getUniqueId())) {
            airTime.put(p.getUniqueId(), ticks() + ticks);
            return;
        }
        airTime.put(p.getUniqueId(), ticks() + ticks);
        p.setAllowFlight(true);
    }

    /** Devuelve el estado de vuelo a quien ya se le paso el efecto. */
    private void tickAirTime() {
        if (airTime.isEmpty()) return;
        airTime.entrySet().removeIf(e -> {
            if (ticks() < e.getValue()) return false;
            Player p = plugin.getServer().getPlayer(e.getKey());
            if (p != null && p.isOnline() && p.getGameMode() != org.bukkit.GameMode.CREATIVE
                    && p.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
                p.setAllowFlight(false);
                p.setFlying(false);
            }
            return true;
        });
    }

    /** Se llama al cerrar el evento: nadie se queda con el vuelo puesto. */
    private void clearAirTime() {
        for (java.util.UUID id : airTime.keySet()) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null && p.isOnline() && p.getGameMode() != org.bukkit.GameMode.CREATIVE
                    && p.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
                p.setAllowFlight(false);
                p.setFlying(false);
            }
        }
        airTime.clear();
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
