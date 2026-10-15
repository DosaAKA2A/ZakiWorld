package net.ederus.edm.anomaly.core;

import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.boss.BossFight;
import net.ederus.edm.comun.Tags;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * El mensaje de muerte de quien cae contra una anomalia.
 *
 * Sin esto el chat contaba lo que veia el servidor: "asesinado por Zombie" (Alba pelea
 * con un zombi invisible debajo del maniqui), "asesinado por Vex" (un esbirro sin
 * nombre), o el jugador "murio" a secas cuando el golpe venia de una habilidad sin
 * entidad. Aqui se mira si la muerte la causo la anomalia abierta —el jefe, su cuerpo
 * visible, sus esbirros, lo que disparan o invocan, o un hit() de hace un momento— y
 * en ese caso el mensaje dice su nombre.
 */
public final class PlayerDeaths implements Listener {

    /** Cuanto puede pasar entre el ultimo hit() y la muerte para atribuirsela. */
    private static final long HIT_WINDOW_MS = 2000;

    private final AnomalyPlugin plugin;

    public PlayerDeaths(AnomalyPlugin plugin) {
        this.plugin = plugin;
    }

    /*
     * En HIGHEST para escribir encima de lo que ponga vanilla y los plugins de chat
     * que lo traducen, pero dejando MONITOR libre para quien solo quiera leerlo.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent e) {
        ActiveAnomaly event = plugin.manager().current();
        if (event == null || event.fight() == null) return;
        Player victim = e.getPlayer();
        if (!causedByAnomaly(e, victim, event)) return;
        if (e.deathMessage() == null) return; // alguien las tiene apagadas: se respeta

        AnomalyType type = event.type();
        e.deathMessage(Component.text(victim.getName() + " ha sido asesinado por ", NamedTextColor.GRAY)
                .append(Component.text(type.display(), type.color())));
    }

    private boolean causedByAnomaly(PlayerDeathEvent e, Player victim, ActiveAnomaly event) {
        // Lo que mata quitando la vida a pelo avisa antes: manda sobre todo lo demas.
        if (event.killWithin(victim, HIT_WINDOW_MS)) return true;
        // La fuente de dano moderna ya separa quien pega (directa) de quien lo causo.
        try {
            var source = e.getDamageSource();
            if (fromAnomaly(source.getCausingEntity(), event) || fromAnomaly(source.getDirectEntity(), event)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        EntityDamageEvent last = victim.getLastDamageCause();
        if (last instanceof EntityDamageByEntityEvent by) {
            if (fromAnomaly(by.getDamager(), event)) return true;
            // Le pego otra cosa con nombre (otro jugador, un mob del mundo): no es nuestra.
            return false;
        }
        /* Sin entidad detras: p.damage sin fuente (el jefe ya no estaba) o setHealth.
         * Solo cuenta si la anomalia le acababa de pegar, y solo por las causas que
         * dejan esas llamadas; una caida o la lava siguen siendo una caida o la lava. */
        if (!event.hitWithin(victim, HIT_WINDOW_MS)) return false;
        if (last == null) return true;
        return switch (last.getCause()) {
            case CUSTOM, MAGIC, KILL -> true;
            default -> false;
        };
    }

    /**
     * Si esa entidad es de la anomalia abierta o la lanzo ella: el jefe, su maniqui,
     * un esbirro suyo, o un proyectil, nube, colmillos o TNT cuyo tirador u origen sea
     * uno de esos. Lo usa tambien el manager para saber a que golpe aplicar el
     * multiplicador de dano.
     */
    public static boolean fromAnomaly(Entity entity, ActiveAnomaly event) {
        if (entity == null || event == null) return false;
        Entity origin = originOf(entity);
        return isPart(entity, event) || (origin != entity && isPart(origin, event));
    }

    /** Quien esta detras de algo que pega a distancia. Si no hay nadie, la propia entidad. */
    private static Entity originOf(Entity entity) {
        Object src = null;
        if (entity instanceof Projectile proj) src = proj.getShooter();
        else if (entity instanceof AreaEffectCloud cloud) src = cloud.getSource();
        else if (entity instanceof EvokerFangs fangs) src = fangs.getOwner();
        else if (entity instanceof TNTPrimed tnt) src = tnt.getSource();
        return src instanceof Entity e ? e : entity;
    }

    private static boolean isPart(Entity entity, ActiveAnomaly event) {
        if (entity == null) return false;
        BossFight fight = event.fight();
        if (fight != null && (entity.equals(fight.entity()) || entity.equals(fight.shell()))) return true;
        String id = event.typeId();
        if (id.equals(Tags.bossId(entity))) return true;
        return id.equals(Tags.minionId(entity));
    }
}
