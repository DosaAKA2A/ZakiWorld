package net.ederus.lethalworld;

import java.lang.reflect.Method;
import java.util.UUID;

import org.bukkit.entity.Entity;

/**
 * Saber si un mob lo puso MythicMobs, sin depender de MythicMobs para compilar.
 *
 * Los mobs de MythicMobs vienen con su dificultad ya puesta a mano. Lethal World los
 * estaba adoptando (aparecen con reason CUSTOM, asi que se le colaban por el barrido de
 * adoptarCerca) y les reescribia vida y dano por nivel: salian escalados dos veces y no
 * habia quien los matara. Desde aqui se reconocen y se dejan en paz.
 *
 * Por reflexion porque no hay jar de MythicMobs en _toolchain/libs y no merece la pena
 * meter uno de pago solo para una llamada. Todo lo dudoso devuelve false: sin MythicMobs,
 * o si cambian el API, el plugin se comporta exactamente como antes de este cambio.
 */
final class PuenteMythicMobs {

    private static boolean resuelto;
    /** Null = MythicMobs no esta o su API no encaja. No se vuelve a intentar. */
    private static Method inst;
    private static Method getMobManager;
    private static Method isMythicMob;
    private static Method isActiveMob;

    private PuenteMythicMobs() {
    }

    /** Si MythicMobs esta y se le entiende el API. Para el aviso del arranque. */
    static synchronized boolean disponible() {
        resolver();
        return isMythicMob != null || isActiveMob != null;
    }

    /** Si esta entidad es un mob de MythicMobs. Ante cualquier duda, false. */
    static boolean esMythicMob(Entity entidad) {
        if (entidad == null) return false;
        if (!disponible()) return false;
        try {
            Object api = inst.invoke(null);
            if (api == null) return false;
            Object manager = getMobManager.invoke(api);
            if (manager == null) return false;
            if (isMythicMob != null) {
                return Boolean.TRUE.equals(isMythicMob.invoke(manager, entidad));
            }
            return Boolean.TRUE.equals(isActiveMob.invoke(manager, entidad.getUniqueId()));
        } catch (Throwable ignorado) {
            return false;
        }
    }

    private static synchronized void resolver() {
        if (resuelto) return;
        resuelto = true;
        try {
            Class<?> bukkit = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            inst = bukkit.getMethod("inst");
            getMobManager = bukkit.getMethod("getMobManager");
            Class<?> manager = getMobManager.getReturnType();
            try {
                isMythicMob = manager.getMethod("isMythicMob", Entity.class);
            } catch (NoSuchMethodException sinEse) {
                // Respaldo por si en alguna version solo esta la variante por UUID.
                isActiveMob = manager.getMethod("isActiveMob", UUID.class);
            }
        } catch (Throwable ignorado) {
            inst = null;
            getMobManager = null;
            isMythicMob = null;
            isActiveMob = null;
        }
    }
}
