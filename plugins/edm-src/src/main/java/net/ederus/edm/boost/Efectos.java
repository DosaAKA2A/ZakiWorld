package net.ederus.edm.boost;

import java.util.List;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Donde se nota el boost: experiencia y drops.
 *
 * El reparto de cantidades no se redondea hacia abajo a lo bruto. Un x1.5 sobre un
 * drop de 1 daria siempre 1 (o sea, nada), asi que la parte decimal se juega a los
 * dados: x1.5 de 1 sale 1 la mitad de las veces y 2 la otra mitad. Con x2 el azar
 * no entra nunca, que es el caso normal.
 */
final class Efectos implements Listener {

    private final BoostPlugin modulo;

    Efectos(BoostPlugin modulo) {
        this.modulo = modulo;
    }

    /* --------------------------------------------------------- experiencia */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alGanarExp(PlayerExpChangeEvent e) {
        if (!modulo.habilitado(Tipo.EXP)) return;
        double mult = modulo.servicio().multiplicador(e.getPlayer().getUniqueId(), Tipo.EXP);
        if (mult <= 1.0 || e.getAmount() <= 0) return;
        if (modulo.mundoExcluido(e.getPlayer().getWorld().getName())) return;
        e.setAmount(escalar(e.getAmount(), mult));
    }

    /* --------------------------------------------------------------- drops */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alMorirUnMob(EntityDeathEvent e) {
        if (!modulo.habilitado(Tipo.DROPS)) return;
        if (e.getEntity() instanceof Player) return;           // el inventario de un muerto no se duplica
        Player asesino = e.getEntity().getKiller();
        if (asesino == null) return;
        double mult = modulo.servicio().multiplicador(asesino.getUniqueId(), Tipo.DROPS);
        if (mult <= 1.0) return;
        if (modulo.mundoExcluido(e.getEntity().getWorld().getName())) return;
        multiplicarPilas(e.getDrops(), mult, modulo.materialesExcluidos());
        e.setDroppedExp(escalar(e.getDroppedExp(), mult));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alRomperUnBloque(BlockDropItemEvent e) {
        if (!modulo.habilitado(Tipo.DROPS)) return;
        double mult = modulo.servicio().multiplicador(e.getPlayer().getUniqueId(), Tipo.DROPS);
        if (mult <= 1.0) return;
        if (modulo.mundoExcluido(e.getBlock().getWorld().getName())) return;
        if (modulo.materialesExcluidos().contains(e.getBlockState().getType())) return;
        for (Item item : e.getItems()) {
            ItemStack pila = item.getItemStack();
            if (modulo.materialesExcluidos().contains(pila.getType())) continue;
            pila.setAmount(Math.min(pila.getMaxStackSize() * 4, escalar(pila.getAmount(), mult)));
            item.setItemStack(pila);
        }
    }

    /* ------------------------------------------------------------- utilidad */

    /** Multiplica cada pila de la lista, saltando lo excluido. */
    static void multiplicarPilas(List<ItemStack> pilas, double mult, Set<Material> excluidos) {
        for (ItemStack pila : pilas) {
            if (pila == null || pila.getType().isAir()) continue;
            if (excluidos.contains(pila.getType())) continue;
            pila.setAmount(Math.min(pila.getMaxStackSize() * 4, escalar(pila.getAmount(), mult)));
        }
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
}
