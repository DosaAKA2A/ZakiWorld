package net.ederus.calamity.hardcore;

import net.ederus.calamity.CalamityPlugin;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dar un objeto a un jugador y, lo que no quepa, dejarlo a sus pies a su nombre (DIS M1
 * punto 5 y M31: "al suelo con setOwner 10 s").
 *
 * Por que a su nombre y solo 10 s: el que acaba de cobrar un premio con el inventario lleno
 * no puede perderlo porque otro pase por encima, pero un objeto que nadie puede coger para
 * siempre se queda en el mundo hasta que caduca. A los 10 s cualquiera puede cogerlo, salvo
 * lo ligado (M30), que su listener ya se lo impide a los demas.
 *
 * Es de WP1 (lo usan Aduana y Entregas); las tareas que sueltan el dueno van a nombre de
 * CalamityPlugin y se cancelan en parar().
 */
final class Suelo {

    private static final Set<BukkitTask> TAREAS = new HashSet<>();

    private Suelo() {
    }

    /** Lo mete en el inventario; lo que sobra, al suelo. True si algo ha ido al suelo. */
    static boolean dar(CalamityPlugin plugin, Player p, ItemStack item) {
        if (p == null || item == null || item.getType().isAir() || item.getAmount() <= 0) return false;
        Map<Integer, ItemStack> sobra = p.getInventory().addItem(item);
        if (sobra.isEmpty()) return false;
        for (ItemStack s : sobra.values()) soltar(plugin, p, s);
        return true;
    }

    /** Al suelo, a sus pies, solo para el durante 10 s y sin que un mob lo recoja. */
    static void soltar(CalamityPlugin plugin, Player p, ItemStack item) {
        if (p == null || item == null || item.getType().isAir()) return;
        Item it = p.getWorld().dropItem(p.getLocation(), item);
        it.setCanMobPickup(false);
        it.setPickupDelay(0);
        UUID dueno = p.getUniqueId();
        it.setOwner(dueno);
        final BukkitTask[] t = new BukkitTask[1];
        t[0] = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            TAREAS.remove(t[0]);
            if (it.isValid() && dueno.equals(it.getOwner())) it.setOwner(null);
        }, 200L);
        TAREAS.add(t[0]);
    }

    /** Al parar: los que queden en el suelo se quedan con su dueno (no pasa nada). */
    static void parar() {
        for (BukkitTask t : TAREAS) t.cancel();
        TAREAS.clear();
    }
}
