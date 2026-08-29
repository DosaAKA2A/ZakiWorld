package net.ederus.edm.anomaly.core;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Marcas que el plugin deja en las entidades que crea. Sin esto no hay forma fiable
 * de distinguir "mi esqueleto jefe" de un esqueleto cualquiera tras un reinicio.
 */
public final class Tags {

    private static NamespacedKey BOSS;
    private static NamespacedKey MINION;
    private static NamespacedKey TEMP;
    private static NamespacedKey EVENT;
    private static NamespacedKey UNIQUE_DROP;

    private Tags() {
    }

    public static void init(Plugin plugin) {
        BOSS = new NamespacedKey(plugin, "boss");
        MINION = new NamespacedKey(plugin, "minion");
        TEMP = new NamespacedKey(plugin, "temp");
        EVENT = new NamespacedKey(plugin, "event");
        UNIQUE_DROP = new NamespacedKey(plugin, "unique_drop");
    }

    private static boolean ready() {
        return BOSS != null;
    }

    public static void markBoss(Entity e, String anomalyId) {
        if (!ready() || e == null) return;
        e.getPersistentDataContainer().set(BOSS, PersistentDataType.STRING, anomalyId);
    }

    public static String bossId(Entity e) {
        if (!ready() || e == null) return null;
        return e.getPersistentDataContainer().get(BOSS, PersistentDataType.STRING);
    }

    public static boolean isBoss(Entity e) {
        return bossId(e) != null;
    }

    public static void markMinion(Entity e, String anomalyId) {
        if (!ready() || e == null) return;
        e.getPersistentDataContainer().set(MINION, PersistentDataType.STRING, anomalyId);
    }

    public static boolean isMinion(Entity e) {
        if (!ready() || e == null) return false;
        return e.getPersistentDataContainer().has(MINION, PersistentDataType.STRING);
    }

    /** Entidad decorativa: si sobrevive al final del evento, el barredor la borra. */
    public static void markTemporary(Entity e) {
        if (!ready() || e == null) return;
        e.getPersistentDataContainer().set(TEMP, PersistentDataType.LONG, System.currentTimeMillis());
    }

    public static Long temporarySince(Entity e) {
        if (!ready() || e == null) return null;
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        return pdc.get(TEMP, PersistentDataType.LONG);
    }

    public static void markEvent(Entity e, String eventId) {
        if (!ready() || e == null) return;
        e.getPersistentDataContainer().set(EVENT, PersistentDataType.STRING, eventId);
    }

    public static String eventId(Entity e) {
        if (!ready() || e == null) return null;
        return e.getPersistentDataContainer().get(EVENT, PersistentDataType.STRING);
    }

    /**
     * Marca el item tirado al suelo que es el drop UNICO de una anomalia. La marca va
     * en la ENTIDAD de item, no en el ItemStack: al recogerlo el objeto queda limpio
     * y la marca muere con la entidad. Lo lee el aviso de "quien se lo llevo".
     */
    public static void markUniqueDrop(Entity e, String anomalyId) {
        if (!ready() || e == null) return;
        e.getPersistentDataContainer().set(UNIQUE_DROP, PersistentDataType.STRING, anomalyId);
    }

    public static String uniqueDropOf(Entity e) {
        if (!ready() || e == null) return null;
        return e.getPersistentDataContainer().get(UNIQUE_DROP, PersistentDataType.STRING);
    }

    /** Cualquier entidad creada por el plugin: jefe, esbirro o decoracion. */
    public static boolean isOurs(Entity e) {
        return isBoss(e) || isMinion(e) || temporarySince(e) != null;
    }
}
