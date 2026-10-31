package net.ederus.edm.goditems;

import org.bukkit.entity.Entity;

/**
 * Lo poco que Distancia necesita de MythicLib, aislado aqui y siempre en try.
 *
 * Un arco de MMOItems no dispara una flecha normal: MMOItems le cuelga a la flecha
 * un ProjectileMetadata de MythicLib con las stats del tirador (su ATTACK_DAMAGE)
 * y la fuerza de la carga, y al impactar MythicLib pone ese daño (x Poder) y le
 * pasa sus criticos si el tipo de item hace daño WEAPON (el BOW lo hace). Las
 * flechas que fabricamos nosotros (el abanico triple) no pasan por MMOItems; para
 * que peguen y critiquen igual que la de verdad se les copia esa ficha.
 *
 * Sin MythicLib todo devuelve "no hay" y Distancia sigue con el daño de vanilla.
 */
final class MythicFlechas {

    private MythicFlechas() {
    }

    /** True si la flecha lleva la ficha de MythicLib (aun no ha impactado o MMOItems la registro). */
    static boolean tiene(Entity proyectil) {
        try {
            return io.lumine.mythic.lib.entity.ProjectileMetadata.get(proyectil) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** El daño que MythicLib le pondra al impactar (ATTACK_DAMAGE x carga, sin Poder), o -1. */
    static double dano(Entity proyectil) {
        try {
            var m = io.lumine.mythic.lib.entity.ProjectileMetadata.get(proyectil);
            return m == null ? -1 : m.getDamage();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Le copia a `copia` la ficha de `origen`: mismo tirador (stats congeladas en
     * el disparo), mismos tipos de daño, mismo arco y misma carga. Devuelve false
     * si no se pudo (sin MythicLib o el origen no la tiene).
     */
    static boolean copiar(Entity origen, Entity copia) {
        try {
            var m = io.lumine.mythic.lib.entity.ProjectileMetadata.get(origen);
            if (m == null) return false;
            var nueva = io.lumine.mythic.lib.entity.ProjectileMetadata.create(m.getShooter(), m.getDamageTypes(),
                    io.lumine.mythic.lib.entity.ProjectileType.ARROW, copia);
            nueva.setSourceItem(m.getSourceItem());
            nueva.setCustomDamage(m.isCustomDamage());
            nueva.setDamageMultiplier(m.getDamageMultiplier());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
