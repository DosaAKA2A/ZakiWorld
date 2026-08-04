package net.zakiworld.anomaly.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.zakiworld.anomaly.AnomalyPlugin;
import net.zakiworld.anomaly.boss.BossFight;
import net.zakiworld.anomaly.boss.PhaseBars;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * El director del evento: abre la anomalia, la mantiene viva, reparte el botin y
 * se asegura de que no quede nada suelto en el mundo cuando termina.
 *
 * Solo puede haber una anomalia abierta a la vez. Es a proposito: el anuncio pierde
 * fuerza si hay tres a la vez, y el servidor tambien.
 */
public final class AnomalyManager implements Listener {

    private final AnomalyPlugin plugin;
    private ActiveAnomaly current;
    private BukkitTask ticker;
    private BukkitTask autoTask;
    private final List<Chunk> forced = new ArrayList<>();
    private boolean searching;

    public AnomalyManager(AnomalyPlugin plugin) {
        this.plugin = plugin;
    }

    public ActiveAnomaly current() {
        return current;
    }

    public boolean active() {
        return current != null;
    }

    public boolean searching() {
        return searching;
    }

    // ------------------------------------------------------------------- apertura

    /**
     * Busca sitio y abre la anomalia. La busqueda es asincrona, asi que el resultado
     * llega por el callback: true si se abrio, false si no habia ningun sitio valido.
     */
    public void start(AnomalyType type, Consumer<Boolean> done) {
        if (active() || searching) {
            done.accept(false);
            return;
        }
        searching = true;
        plugin.sites().find(type, loc -> {
            searching = false;
            if (loc == null) {
                done.accept(false);
                return;
            }
            open(type, loc);
            done.accept(true);
        });
    }

    /** Abre la anomalia en un punto concreto, sin buscar ni comprobar protecciones. */
    public void open(AnomalyType type, Location where) {
        if (active()) return;
        ActiveAnomaly event = new ActiveAnomaly(type, where);
        current = event;

        forceLoad(where);

        BossFight fight = type.create(plugin, event, where);
        event.fight(fight);
        event.bars(new PhaseBars(type.display(), type.color()));

        try {
            fight.spawn();
        } catch (Throwable t) {
            plugin.getLogger().severe("No se pudo crear el jefe de " + type.id() + ": " + t);
            stop(true);
            return;
        }

        event.state(ActiveAnomaly.State.ACTIVA);
        plugin.announcer().opened(event);
        plugin.getLogger().info("Anomalia " + type.id() + " abierta en " + describe(where));

        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private static String describe(Location l) {
        return (l.getWorld() == null ? "?" : l.getWorld().getName())
                + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    /**
     * Mantiene cargados los trozos de la arena. Sin esto, si todo el mundo muere y se
     * aleja, el jefe se congela a medias y el evento no se cierra nunca.
     */
    private void forceLoad(Location where) {
        if (where.getWorld() == null) return;
        int cx = where.getBlockX() >> 4;
        int cz = where.getBlockZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                try {
                    Chunk c = where.getWorld().getChunkAt(cx + dx, cz + dz);
                    c.setForceLoaded(true);
                    forced.add(c);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void releaseChunks() {
        for (Chunk c : forced) {
            try {
                c.setForceLoaded(false);
            } catch (Throwable ignored) {
            }
        }
        forced.clear();
    }

    // ----------------------------------------------------------------------- ciclo

    private void tick() {
        ActiveAnomaly event = current;
        if (event == null) return;
        BossFight fight = event.fight();
        if (fight == null) return;

        try {
            fight.tick();
        } catch (Throwable t) {
            plugin.getLogger().warning("Fallo en el tick del jefe: " + t);
        }

        PhaseBars bars = event.bars();
        if (bars != null) {
            bars.update(fight.healthFraction());
            // Recalcular quien ve las barras cuesta un barrido de jugadores;
            // una vez por segundo es de sobra y no se nota al entrar al radio.
            if (fight.ticks() % 20 == 0) {
                bars.refreshViewers(fight.loc(), plugin.settings().participationRadius() + 24);
            }
        }

        if (fight.ticks() % 10 == 0 && plugin.settings().lightPillar()) beacon(event, fight.loc());

        if (event.state() == ActiveAnomaly.State.ACTIVA
                && event.elapsedSeconds() > plugin.settings().timeLimitMinutes() * 60L) {
            plugin.announcer().expired(event);
            stop(true);
        }
    }

    /**
     * El pilar de luz sobre el jefe, del color de la anomalia.
     *
     * Las coordenadas del anuncio te dejan en la zona, pero encontrar al jefe entre
     * arboles seguia siendo cosa de suerte. Esto se ve desde lejos y de noche marca
     * el sitio como una baliza.
     */
    private void beacon(ActiveAnomaly event, Location where) {
        // Sin color de brillo no hay pilar: esa anomalia quiere pillarte por sorpresa.
        if (event.type().glowColor() == null) return;
        Location base = Fx.ground(where, 4);
        var dust = Compat.dust(event.type().glowColor().value(), 2.2f);
        // Pocas particulas y grandes: cada una forzada es un paquete por jugador a la
        // redonda, asi que el pilar se dibuja espaciado en vez de denso.
        for (double y = 0.5; y <= 40; y += 2.5) {
            Compat.spawnForced(where.getWorld(), Compat.DUST, base.clone().add(0, y, 0), 1,
                    0.14, 0.2, 0.14, 0, dust);
        }
        Fx.ring(base.clone().add(0, 0.3, 0), 3.0, 12, l ->
                Compat.spawnForced(where.getWorld(), Compat.DUST, Fx.ground(l, 3).add(0, 0.2, 0), 1,
                        0, 0, 0, 0, dust));
    }

    // ---------------------------------------------------------------------- cierre

    /** Cierra el evento y borra todo lo que haya quedado. */
    public void stop(boolean silent) {
        ActiveAnomaly event = current;
        current = null;
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        releaseChunks();
        plugin.anchors().clear();
        if (event == null) return;
        event.state(ActiveAnomaly.State.CERRANDO);
        if (event.bars() != null) event.bars().removeAll();
        if (event.fight() != null) event.fight().cleanup();
        if (!silent) {
            plugin.getServer().sendMessage(Component.text("✦ La anomalia se cerro.", NamedTextColor.GRAY));
        }
    }

    /** Cierre limpio del plugin: no deja jefes ni decoracion en el mundo. */
    public void shutdown() {
        if (autoTask != null) {
            autoTask.cancel();
            autoTask = null;
        }
        stop(true);
        Anim.cancelAll();
    }

    // ------------------------------------------------------------------ automatico

    public void restartScheduler() {
        if (autoTask != null) {
            autoTask.cancel();
            autoTask = null;
        }
        if (!plugin.settings().autoEnabled()) return;
        long period = plugin.settings().autoIntervalMinutes() * 60L * 20L;
        autoTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::autoTrigger, period, period);
        plugin.getLogger().info("Anomalias automaticas cada " + plugin.settings().autoIntervalMinutes() + " min.");
    }

    private void autoTrigger() {
        if (active() || searching) return;
        int online = 0;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (Fx.isFightable(p)) online++;
        }
        if (online < plugin.settings().autoMinPlayers()) return;

        String choice = plugin.settings().autoAnomaly();
        AnomalyType type = "aleatoria".equalsIgnoreCase(choice)
                ? plugin.registry().randomEnabled()
                : plugin.registry().get(choice);
        if (type == null || !plugin.registry().isEnabled(type)) return;
        start(type, ok -> {
            if (!ok) plugin.getLogger().info("Anomalia automatica descartada: no habia sitio libre.");
        });
    }

    // -------------------------------------------------------------------- escuchas

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();

        // Los objetivos destructibles no usan la vida vanilla: cada golpe cuenta uno.
        if (plugin.anchors().isAnchor(victim)) {
            e.setCancelled(true);
            if (attacker(e.getDamager()) != null) plugin.anchors().hit(victim);
            return;
        }

        ActiveAnomaly event = current;
        if (event == null || event.fight() == null) return;
        LivingEntity boss = event.fight().entity();

        if (boss != null && victim.equals(boss)) {
            Player p = attacker(e.getDamager());
            if (p == null) return;
            // El merito se apunta con el dano "real" que hizo el jugador, antes de
            // reescalarlo al tope de vida de la entidad: asi el ranking no depende
            // de un detalle interno del plugin.
            event.addDamage(p, e.getFinalDamage());

            double factor = event.fight().damageScale() * event.fight().incomingDamageMultiplier(e.getDamager());
            if (factor != 1.0) e.setDamage(e.getDamage() * factor);
            return;
        }

        // El jefe o uno de los suyos le ha pegado a alguien: hay anomalias que
        // reaccionan a eso, como el Conejo, que se multiplica en cada mordisco.
        if (victim instanceof Player hurt
                && (e.getDamager().equals(boss) || Tags.isMinion(e.getDamager()))) {
            try {
                event.fight().onDealtDamage(hurt, e.getDamager());
            } catch (Throwable t) {
                plugin.getLogger().warning("Fallo al reaccionar a un golpe del jefe: " + t);
            }
        }

        // Ni el jefe pega a sus esbirros ni ellos a el.
        if (boss != null && Tags.isMinion(victim) && e.getDamager().equals(boss)) {
            e.setCancelled(true);
            return;
        }
        if (boss != null && victim.equals(boss) && Tags.isMinion(e.getDamager())) {
            e.setCancelled(true);
        }
    }

    private Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
        }
        return null;
    }

    /** Los esbirros no se pelean entre ellos ni con el jefe. */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (!Tags.isOurs(e.getEntity())) return;
        Entity target = e.getTarget();
        if (target != null && Tags.isOurs(target)) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        ActiveAnomaly event = current;
        if (event == null || event.fight() == null) return;
        LivingEntity boss = event.fight().entity();

        if (Tags.isMinion(e.getEntity())) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            return;
        }
        if (boss == null || !e.getEntity().equals(boss)) return;

        // El botin del jefe lo decide la tabla, nunca la tabla vanilla del esqueleto.
        e.getDrops().clear();
        e.setDroppedExp(0);
        defeat(event);
    }

    private void defeat(ActiveAnomaly event) {
        event.state(ActiveAnomaly.State.CERRANDO);
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        Location where = event.fight().loc();

        try {
            event.fight().onDeath();
        } catch (Throwable t) {
            plugin.getLogger().warning("Fallo en la animacion de muerte: " + t);
        }

        List<String> report = plugin.drops().award(event.typeId(), event.damage(), where);
        plugin.announcer().defeated(event, report);
        if (event.bars() != null) event.bars().removeAll();

        // Se deja respirar la animacion de muerte antes de barrer la escena.
        Anim.later(plugin, 110, () -> {
            if (current == event) {
                current = null;
                releaseChunks();
                plugin.anchors().clear();
                event.fight().cleanup();
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ActiveAnomaly event = current;
        if (event != null && event.bars() != null) event.bars().viewers().remove(e.getPlayer());
    }

    /** Aviso a un operador de que algo no salio. */
    public void tell(CommandSender who, Component message) {
        who.sendMessage(plugin.prefix().append(message));
    }
}
