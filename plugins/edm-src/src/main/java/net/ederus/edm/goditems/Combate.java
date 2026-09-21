package net.ederus.edm.goditems;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * La memoria corta del combate: combo, racha y marcas.
 *
 * Vive en memoria y NO sobrevive a un reinicio, igual que los cooldowns y por la
 * misma razon: son datos de segundos. Escribirlos en el PDC de cada jugador en
 * cada golpe seria pagar disco por algo que caduca en dos segundos.
 *
 * El COMBO cuenta golpes seguidos AL MISMO objetivo. En cuanto cambias de
 * objetivo o pasan mas de dos segundos, vuelve a uno: asi "combo 5" significa
 * de verdad cinco golpes encadenados y no cinco golpes de toda la tarde.
 *
 * La RACHA cuenta muertes seguidas sin morir tu. Se reinicia al morir.
 */
public final class Combate {

    /** Lo que dura un combo sin recibir otro golpe. */
    private static final long COMBO_MS = 2000L;

    private record Golpes(UUID objetivo, int veces, long ultimo) { }

    private final Map<UUID, Golpes> combos = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> rachas = new ConcurrentHashMap<>();
    /** Entidad marcada -> cuando caduca la marca, en milisegundos. */
    private final Map<UUID, Long> marcas = new ConcurrentHashMap<>();

    /* ================================================================ combo */

    /** Apunta un golpe y devuelve el combo que queda. */
    public int golpe(Player j, Entity objetivo) {
        if (j == null || objetivo == null) return 0;
        long ahora = System.currentTimeMillis();
        Golpes antes = this.combos.get(j.getUniqueId());
        int veces = (antes != null
                && antes.objetivo().equals(objetivo.getUniqueId())
                && ahora - antes.ultimo() <= COMBO_MS)
                ? antes.veces() + 1 : 1;
        this.combos.put(j.getUniqueId(), new Golpes(objetivo.getUniqueId(), veces, ahora));
        return veces;
    }

    /** El combo actual, ya caducado si toca. */
    public int combo(Player j) {
        if (j == null) return 0;
        Golpes g = this.combos.get(j.getUniqueId());
        if (g == null) return 0;
        if (System.currentTimeMillis() - g.ultimo() > COMBO_MS) {
            this.combos.remove(j.getUniqueId());
            return 0;
        }
        return g.veces();
    }

    public void reiniciarCombo(Player j) {
        if (j != null) this.combos.remove(j.getUniqueId());
    }

    /* ================================================================ racha */

    /** Suma una muerte a la racha y devuelve el total. */
    public int sumarRacha(Player j) {
        if (j == null) return 0;
        return this.rachas.merge(j.getUniqueId(), 1, Integer::sum);
    }

    public int racha(Player j) {
        if (j == null) return 0;
        Integer n = this.rachas.get(j.getUniqueId());
        return n == null ? 0 : n;
    }

    public void reiniciarRacha(Player j) {
        if (j != null) this.rachas.remove(j.getUniqueId());
    }

    /* ================================================================ marcas */

    public void marcar(Entity e, int ticks) {
        if (e == null || ticks <= 0) return;
        this.marcas.put(e.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
    }

    public boolean marcado(Entity e) {
        if (e == null) return false;
        Long hasta = this.marcas.get(e.getUniqueId());
        if (hasta == null) return false;
        if (hasta <= System.currentTimeMillis()) {
            this.marcas.remove(e.getUniqueId());
            return false;
        }
        return true;
    }

    /* =============================================================== limpieza */

    public void olvidar(UUID uuid) {
        this.combos.remove(uuid);
        this.rachas.remove(uuid);
        this.marcas.remove(uuid);
    }

    /** Las marcas de lo que ya murio o caduco. La llama la tarea de la barra. */
    public void repasar() {
        long ahora = System.currentTimeMillis();
        this.marcas.values().removeIf(h -> h <= ahora);
    }

    public void limpiar() {
        this.combos.clear();
        this.rachas.clear();
        this.marcas.clear();
    }

    /* ================================================================ jefes */

    /**
     * Si algo cuenta como jefe.
     *
     * Primero se pregunta a MythicMobs por reflexion (si esta, un MythicMob es
     * un jefe por definicion para esto); si no esta o no lo es, se mira la vida
     * maxima contra el umbral. Asi el activador funciona en un servidor sin
     * MythicMobs sin dejar de ser preciso en uno que si lo tenga.
     */
    public static boolean esJefe(Entity e, double vidaMinima) {
        if (!(e instanceof LivingEntity le)) return false;
        if (esMythicMob(e)) return true;
        return Textos.maxVida(le) >= vidaMinima;
    }

    private static Boolean hayMythic;

    public static boolean esMythicMob(Entity e) {
        if (e == null) return false;
        if (hayMythic == null) {
            hayMythic = org.bukkit.Bukkit.getPluginManager().getPlugin("MythicMobs") != null;
        }
        if (!hayMythic) return false;
        try {
            Class<?> api = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = api.getMethod("inst").invoke(null);
            Object mobs = inst.getClass().getMethod("getMobManager").invoke(inst);
            Object r = mobs.getClass().getMethod("isMythicMob", Entity.class).invoke(mobs, e);
            return r instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }
}
