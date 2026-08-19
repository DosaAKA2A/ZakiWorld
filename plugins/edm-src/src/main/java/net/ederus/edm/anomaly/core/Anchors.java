package net.ederus.edm.anomaly.core;

import org.bukkit.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Objetivos destructibles: el estandarte de guerra, las anclas de la resurreccion...
 *
 * Se llevan aparte de la vida normal de las entidades porque son soportes decorativos
 * (soportes de armadura) y su dano vanilla es impredecible. Aqui cada golpe cuenta uno,
 * venga de donde venga, y al llegar a cero se dispara la accion que dejo la habilidad.
 */
public final class Anchors {

    /** Un objetivo con sus golpes restantes y lo que pasa al romperlo. */
    public static final class Anchor {
        private final Entity entity;
        private int hitsLeft;
        private final Runnable onBreak;
        private final Runnable onHit;

        Anchor(Entity entity, int hits, Runnable onHit, Runnable onBreak) {
            this.entity = entity;
            this.hitsLeft = hits;
            this.onHit = onHit;
            this.onBreak = onBreak;
        }

        public Entity entity() {
            return entity;
        }

        public int hitsLeft() {
            return hitsLeft;
        }
    }

    private final Map<UUID, Anchor> anchors = new HashMap<>();

    public Anchor register(Entity entity, int hits, Runnable onHit, Runnable onBreak) {
        Anchor a = new Anchor(entity, hits, onHit, onBreak);
        anchors.put(entity.getUniqueId(), a);
        return a;
    }

    public boolean isAnchor(Entity e) {
        return e != null && anchors.containsKey(e.getUniqueId());
    }

    /**
     * Registra un golpe.
     *
     * @return true si el objetivo se rompio con este golpe
     */
    public boolean hit(Entity e) {
        Anchor a = anchors.get(e.getUniqueId());
        if (a == null) return false;
        a.hitsLeft--;
        if (a.hitsLeft > 0) {
            if (a.onHit != null) a.onHit.run();
            return false;
        }
        anchors.remove(e.getUniqueId());
        Fx.safeRemove(e);
        if (a.onBreak != null) a.onBreak.run();
        return true;
    }

    public void forget(Entity e) {
        if (e != null) anchors.remove(e.getUniqueId());
    }

    public void clear() {
        for (Anchor a : anchors.values()) Fx.safeRemove(a.entity);
        anchors.clear();
    }

    public int count() {
        return anchors.size();
    }
}
