package net.ederus.edm.anomaly.minions;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Un generador plantado en el mundo: "aquí sale tal esbirro, de tal nivel, cada
 * tanto tiempo". Se coloca con la vela y se administra desde la lista de
 * generadores del menu. El rango de nivel es SUYO: el mismo tipo de esbirro puede
 * tener generadores flojos en una sala y brutales en otra.
 */
public final class MinionSpawner {

    private final String id;
    private final String typeId;
    private final String worldName;
    private final int x, y, z;

    private int minLevel;
    private int maxLevel;
    private int intervalSeconds;
    private int maxAlive;
    private int activationRadius;
    private boolean enabled = true;

    /** Si tiene cambios sin escribir en la ficha de su tipo. Nace sucia. */
    private boolean sucia = true;

    boolean sucia() {
        return sucia;
    }

    void limpia() {
        sucia = false;
    }

    /* Estado vivo, no se guarda: cuando toca el proximo intento de aparicion. */
    private long nextSpawnAt;

    public MinionSpawner(String id, String typeId, String worldName, int x, int y, int z) {
        this.id = id;
        this.typeId = typeId;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public String id() {
        return id;
    }

    public String typeId() {
        return typeId;
    }

    public String worldName() {
        return worldName;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    /** El punto exacto donde aparece el bicho: de pie sobre el bloque marcado. */
    public Location spot() {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return null;
        return new Location(w, x + 0.5, y + 1.0, z + 0.5);
    }

    public int minLevel() {
        return minLevel;
    }

    public void minLevel(int v) {
        sucia = true;
        this.minLevel = Math.max(1, Math.min(1000, v));
        if (maxLevel < minLevel) maxLevel = minLevel;
    }

    public int maxLevel() {
        return maxLevel;
    }

    public void maxLevel(int v) {
        sucia = true;
        this.maxLevel = Math.max(1, Math.min(1000, v));
        if (minLevel > maxLevel) minLevel = maxLevel;
    }

    /** Fija el rango de una vez; se ordena solo si vienen del reves. */
    public void levels(int min, int max) {
        sucia = true;
        int lo = Math.max(1, Math.min(1000, Math.min(min, max)));
        int hi = Math.max(1, Math.min(1000, Math.max(min, max)));
        this.minLevel = lo;
        this.maxLevel = hi;
    }

    public String levelLabel() {
        return minLevel == maxLevel ? String.valueOf(minLevel) : minLevel + " - " + maxLevel;
    }

    public int intervalSeconds() {
        return intervalSeconds;
    }

    public void intervalSeconds(int v) {
        sucia = true;
        this.intervalSeconds = Math.max(3, Math.min(3600, v));
    }

    public int maxAlive() {
        return maxAlive;
    }

    public void maxAlive(int v) {
        sucia = true;
        this.maxAlive = Math.max(1, Math.min(30, v));
    }

    public int activationRadius() {
        return activationRadius;
    }

    public void activationRadius(int v) {
        sucia = true;
        this.activationRadius = Math.max(8, Math.min(128, v));
    }

    public boolean enabled() {
        return enabled;
    }

    public void enabled(boolean v) {
        sucia = true;
        this.enabled = v;
    }

    public long nextSpawnAt() {
        return nextSpawnAt;
    }

    public void nextSpawnAt(long millis) {
        this.nextSpawnAt = millis;
    }
}
