package net.ederus.calamity.hardcore;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Lo que Calamity necesita de MMOItems, por reflexion (MT sec. 6.2), sin tenerlo en el pom.
 *
 * El servidor de pruebas no tiene MMOItems y produccion si: con reflexion el mismo jar
 * vale en los dos y, si MMOItems cambia su API, esto devuelve el valor neutro en vez de
 * tumbar el plugin. Mismo patron que PuenteMythicMobs: los Method se resuelven una vez,
 * desde las clases publicas (no desde getClass() de la implementacion).
 *
 * Comprobado con javap en _toolchain/libs (MMOItems 6.10.1, MythicLib 1.7.1):
 *   io.lumine.mythic.lib.api.item.NBTItem: static get(ItemStack), getString(String),
 *     double getStat(String) (le antepone "MMOITEMS_"), boolean hasType()
 *   net.Indyuce.mmoitems.MMOItems: static campo "plugin",
 *     ItemStack getItem(String tipo, String id)
 * Las stats viven en custom_data con claves MMOITEMS_<STAT>; el tipo y el id en
 * MMOITEMS_ITEM_TYPE y MMOITEMS_ITEM_ID; el tier en MMOITEMS_TIER.
 */
final class PuenteMmo {

    private static boolean resuelto;
    /** Null = MythicLib no esta o su API no encaja. */
    private static Method nbtGet;
    private static Method nbtGetString;
    private static Method nbtGetStat;
    private static Method nbtHasType;
    /** Null = MMOItems no esta o su API no encaja. */
    private static Field campoPlugin;
    private static Method getItem;

    private PuenteMmo() {
    }

    private static synchronized void resolver() {
        if (resuelto) return;
        resuelto = true;
        try {
            Class<?> nbt = Class.forName("io.lumine.mythic.lib.api.item.NBTItem");
            nbtGet = nbt.getMethod("get", ItemStack.class);
            nbtGetString = nbt.getMethod("getString", String.class);
            nbtGetStat = nbt.getMethod("getStat", String.class);
            nbtHasType = nbt.getMethod("hasType");
        } catch (Throwable t) {
            nbtGet = null;
        }
        try {
            Class<?> mmo = Class.forName("net.Indyuce.mmoitems.MMOItems");
            campoPlugin = mmo.getField("plugin");
            getItem = mmo.getMethod("getItem", String.class, String.class);
        } catch (Throwable t) {
            campoPlugin = null;
            getItem = null;
        }
    }

    /** Si MMOItems esta encendido y se le entiende el API (leer y crear). */
    static boolean disponible() {
        if (!Bukkit.getPluginManager().isPluginEnabled("MMOItems")) return false;
        resolver();
        return nbtGet != null && getItem != null && campoPlugin != null;
    }

    /** El NBTItem de MythicLib de un item, o null si no es un MMOItem o no se puede leer. */
    private static Object nbt(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        if (!Bukkit.getPluginManager().isPluginEnabled("MythicLib")) return null;
        resolver();
        if (nbtGet == null) return null;
        try {
            Object n = nbtGet.invoke(null, item);
            if (n == null || !Boolean.TRUE.equals(nbtHasType.invoke(n))) return null;
            return n;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String texto(Object nbt, String clave) {
        try {
            Object v = nbtGetString.invoke(nbt, clave);
            return v == null || v.toString().isBlank() ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** "TIPO.ID" del item (p. ej. "CALAMITY.YELMO_DE_CALAMIDAD"), o null si no es un MMOItem. */
    static String enlace(ItemStack item) {
        Object n = nbt(item);
        if (n == null) return null;
        String tipo = texto(n, "MMOITEMS_ITEM_TYPE"), id = texto(n, "MMOITEMS_ITEM_ID");
        return tipo == null || id == null ? null : tipo + "." + id;
    }

    /** El tier (UMBRAL, CALAMIDAD...) o null. */
    static String tier(ItemStack item) {
        Object n = nbt(item);
        return n == null ? null : texto(n, "MMOITEMS_TIER");
    }

    /** Una stat del item (ATTACK_DAMAGE, ARMOR...); 0 si no es un MMOItem o no la trae. */
    static double stat(ItemStack item, String stat) {
        Object n = nbt(item);
        if (n == null || stat == null) return 0;
        try {
            Object v = nbtGetStat.invoke(n, stat);
            return v instanceof Number d ? d.doubleValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Un item nuevo de MMOItems a partir de "TIPO.ID", o null si MMOItems no esta, el id
     * esta mal escrito o no existe. El que entrega decide que hacer con el null (Entregas:
     * P-M07 al staff y devolver lo cobrado).
     */
    static ItemStack crear(String tipoPuntoId) {
        if (tipoPuntoId == null) return null;
        int punto = tipoPuntoId.indexOf('.');
        if (punto <= 0 || punto == tipoPuntoId.length() - 1) return null;
        if (!disponible()) return null;
        try {
            Object mmo = campoPlugin.get(null);
            if (mmo == null) return null;
            Object it = getItem.invoke(mmo, tipoPuntoId.substring(0, punto), tipoPuntoId.substring(punto + 1));
            return it instanceof ItemStack s && !s.getType().isAir() ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
