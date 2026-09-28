package net.ederus.edm.boost;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerExpChangeEvent;

/**
 * Donde se nota el boost de experiencia vanilla.
 *
 * Aqui antes vivia tambien el boost de drops (mobs y bloques). Se quito en EDM 1.74.0
 * porque se uso para duplicar: no queda ningun listener que toque drops.
 *
 * El reparto de cantidades no se redondea hacia abajo a lo bruto. Un x1.5 sobre 1 de
 * experiencia daria siempre 1 (o sea, nada), asi que la parte decimal se juega a los
 * dados: x1.5 de 1 sale 1 la mitad de las veces y 2 la otra mitad. Con x2 el azar no
 * entra nunca, que es el caso normal.
 */
final class Efectos implements Listener {

    private final BoostPlugin.Fuente fuente;

    Efectos(BoostPlugin.Fuente fuente) {
        this.fuente = fuente;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alGanarExp(PlayerExpChangeEvent e) {
        if (e.getAmount() <= 0) return;
        double mult = fuente.en(e.getPlayer(), Tipo.EXP);
        if (mult <= 1.0) return;
        e.setAmount(escalar(e.getAmount(), mult));
    }

    /** n por el multiplicador, con la parte decimal jugada a los dados. */
    static int escalar(int n, double mult) {
        if (n <= 0) return n;
        double exacto = n * mult;
        int entero = (int) Math.floor(exacto);
        double resto = exacto - entero;
        if (resto > 0 && Math.random() < resto) entero++;
        return Math.max(n, entero);
    }

    /** Lo mismo para cantidades grandes (MobCoins). */
    static long escalar(long n, double mult) {
        if (n <= 0) return n;
        double exacto = n * mult;
        long entero = (long) Math.floor(exacto);
        double resto = exacto - entero;
        if (resto > 0 && Math.random() < resto) entero++;
        return Math.max(n, entero);
    }
}
