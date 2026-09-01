package net.ederus.edm.goditems;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import net.ederus.edm.anomaly.core.Compat;
import net.ederus.edm.anomaly.core.Fx;

/**
 * Los tres efectos gordos del catalogo, sacados de sitios donde YA se ven bien
 * en produccion en vez de reinventados:
 *
 *  - RAYO_BEACON  es la columna de `Alba#rayosDelAlba`: dos BlockDisplay
 *                 anidados, la base bajo el suelo para que el rayo emerja del
 *                 subsuelo y no nazca de la nada.
 *  - ESPADA_CAIDA es el swordfall de RIP: espadas que caen de punta y se clavan.
 *  - CIELO        es el cielo del cliente de `rip/EffectRunner#sky`: hora falsa
 *                 por jugador, que no toca la del mundo y por tanto no afecta a
 *                 mobs, granjas ni al dormir.
 *
 * Todo se limpia solo. Una entidad de dibujo que se queda viva es basura que ya
 * no borra nadie: no la mata el chunk, no la mata el reinicio y se acumula.
 */
public final class Efectos {

    private Efectos() { }

    /* --------------------------------------------------------- RAYO_BEACON */

    public static void rayoBeacon(GodItemsPlugin modulo, Location donde, int ticks,
                                  Material funda, Material nucleo, double ancho) {
        World w = donde.getWorld();
        if (w == null) return;
        /* La base va MUY por debajo del suelo: el rayo tiene que salir del
         * subsuelo. Naciendo a la altura de los pies se ve el corte y parece
         * una caja de cristal, no un haz. */
        double baseY = Math.max(w.getMinHeight() + 1, donde.getY() - 25);
        Location base = new Location(w, donde.getX(), baseY, donde.getZ());
        float alto = (float) Math.min(320, w.getMaxHeight() - baseY + 40);

        List<BlockDisplay> haces = new ArrayList<>(2);
        BlockDisplay f = Fx.lightColumn(w, base, funda, (float) ancho, alto);
        BlockDisplay n = Fx.lightColumn(w, base, nucleo, (float) (ancho * 0.6), alto);
        if (f != null) haces.add(f);
        if (n != null) haces.add(n);
        if (haces.isEmpty()) return;

        modulo.core().getServer().getScheduler().runTaskLater(modulo.core(), () -> {
            for (BlockDisplay d : haces) Fx.safeRemove(d);
            Location pie = Fx.ground(donde.clone().add(0, 1, 0), 12);
            Compat.spawn(w, Compat.END_ROD, pie.clone().add(0, 1, 0), 12, 0.2, 1.2, 0.2, 0.03);
        }, Math.max(1, ticks));
    }

    /* -------------------------------------------------------- ESPADA_CAIDA */

    /**
     * Espadas que caen del cielo y se clavan alrededor de un punto.
     *
     * La formula es la del swordfall de RIP: caen con la punta hacia abajo, se
     * paran en el suelo real (no en la Y del centro, que en una cuesta deja la
     * mitad flotando) y se quedan un rato clavadas antes de deshacerse.
     */
    public static void espadaCaida(GodItemsPlugin modulo, Location centro, int cuantas,
                                   double radio, Material material, double dano, int clavadas) {
        World w = centro.getWorld();
        if (w == null) return;
        ItemStack hoja = new ItemStack(material);
        int n = Math.max(1, Math.min(48, cuantas));

        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 / n * i;
            double r = radio * (0.35 + Math.random() * 0.65);
            Location suelo = Fx.ground(new Location(w,
                    centro.getX() + Math.cos(a) * r,
                    centro.getY() + 2,
                    centro.getZ() + Math.sin(a) * r), 16);
            int retardo = i * 2 + (int) (Math.random() * 4);
            modulo.core().getServer().getScheduler().runTaskLater(modulo.core(),
                    () -> unaEspada(modulo, w, suelo, hoja, dano, clavadas), retardo);
        }
    }

    private static void unaEspada(GodItemsPlugin modulo, World w, Location suelo,
                                  ItemStack hoja, double dano, int clavadas) {
        Location alto = suelo.clone().add(0, 14, 0);
        ItemDisplay d = Fx.itemDisplay(w, alto, hoja, 1.6f);
        if (d == null) return;
        /* Punta abajo: la espada apunta al suelo, no al cielo. */
        Fx.aim(d, new Vector(0, -1, 0), 1.6f, 0);
        Compat.sound(w, alto, "entity.arrow.shoot", 0.7f, 0.6f);

        final int caida = 10;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(0);
        d.setTeleportDuration(caida);
        modulo.core().getServer().getScheduler().runTaskLater(modulo.core(),
                () -> { if (d.isValid()) d.teleport(suelo.clone().add(0, 0.2, 0)); }, 1L);

        modulo.core().getServer().getScheduler().runTaskLater(modulo.core(), () -> {
            Compat.sound(w, suelo, "item.trident.hit_ground", 1.0f, 0.8f);
            Compat.spawn(w, Compat.CRIT, suelo.clone().add(0, 0.3, 0), 14, 0.3, 0.2, 0.3, 0.1);
            Compat.spawn(w, Compat.SWEEP_ATTACK, suelo.clone().add(0, 0.4, 0), 2, 0.2, 0.1, 0.2, 0.0);
            if (dano > 0) {
                for (Player p : Fx.playersNear(suelo, 1.8)) p.damage(dano);
            }
        }, caida + 1L);

        modulo.core().getServer().getScheduler().runTaskLater(modulo.core(),
                () -> Fx.safeRemove(d), caida + 2L + Math.max(0, clavadas));
    }

    /* ---------------------------------------------------------------- CIELO */

    /**
     * Cambia la hora que ve el CLIENTE, no la del mundo. Siempre con vuelta:
     * dejar a alguien con la hora fijada es dejarle el cielo roto hasta que se
     * desconecte, y no hay forma de que el jugador lo arregle por su cuenta.
     */
    public static void cielo(GodItemsPlugin modulo, List<Player> quienes, long hora, int ticks) {
        List<Player> tocados = new ArrayList<>(quienes);
        for (Player p : tocados) {
            try {
                p.setPlayerTime(hora, false);
            } catch (Throwable ignored) {
            }
        }
        if (ticks <= 0) return;
        modulo.core().getServer().getScheduler().runTaskLater(modulo.core(), () -> {
            for (Player p : tocados) {
                try {
                    if (p.isOnline()) p.resetPlayerTime();
                } catch (Throwable ignored) {
                }
            }
        }, ticks);
    }

    /* ------------------------------------------------------------ EXPLOSION */

    /**
     * Explosion que NO rompe el mundo. Es a proposito: Ederus es un survival con
     * bases y regiones, y un item que agujerea el suelo es un item que acaba
     * retirado a la semana.
     */
    public static void explosion(GodItemsPlugin modulo, Location centro, double radio, int anillos,
                                 double dano, boolean empuje, LivingEntity fuente) {
        World w = centro.getWorld();
        if (w == null) return;
        Compat.sound(w, centro, "entity.generic.explode", 1.4f, 0.8f);
        Compat.spawn(w, Compat.EXPLOSION_EMITTER, centro, 1);
        for (int i = 1; i <= Math.max(1, anillos); i++) {
            double r = radio * i / Math.max(1, anillos);
            int puntos = (int) Math.max(8, r * 10);
            Fx.ring(centro, r, puntos, l ->
                    Compat.spawn(w, Compat.LARGE_SMOKE, l, 1, 0.05, 0.05, 0.05, 0.01));
        }
        if (dano <= 0 && !empuje) return;
        for (org.bukkit.entity.Entity e : w.getNearbyEntities(centro, radio, radio, radio)) {
            if (!(e instanceof LivingEntity le) || le.isDead()) continue;
            if (fuente != null && le.equals(fuente)) continue;
            double d = le.getLocation().distance(centro);
            if (d > radio) continue;
            double caida = 1.0 - (d / radio);
            if (dano > 0) le.damage(dano * caida, fuente);
            if (empuje) {
                Vector v = le.getLocation().toVector().subtract(centro.toVector());
                if (v.lengthSquared() < 0.01) v = new Vector(0, 1, 0);
                le.setVelocity(v.normalize().multiply(1.2 * caida).setY(0.45 * caida + 0.2));
            }
        }
    }
}
