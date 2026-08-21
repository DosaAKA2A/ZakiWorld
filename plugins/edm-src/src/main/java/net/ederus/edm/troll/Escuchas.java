package net.ederus.edm.troll;

import io.papermc.paper.event.player.AsyncChatEvent;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Lo que hace que las marcas signifiquen algo.
 *
 * Un solo listener para todas: 36 clases de listener, una por broma, es lo que
 * hace que un plugin de estos pese en el tick de un servidor con gente. Aqui
 * cada evento pregunta por su marca y se va.
 *
 * Todos salen corriendo cuando no hay nada activo, que es el 99,9% del tiempo:
 * `tiene()` es una consulta a un mapa vacio.
 */
public final class Escuchas implements Listener {

    private final TrollPlugin modulo;

    public Escuchas(TrollPlugin modulo) {
        this.modulo = modulo;
    }

    private Estados estados() { return modulo.estados(); }

    // -------------------------------------------------------------- moverse

    @EventHandler(ignoreCancelled = true)
    public void alMoverse(PlayerMoveEvent e) {
        Player p = e.getPlayer();

        if (estados().tiene(p, Estados.Marca.CONGELADO)) {
            Location de = e.getFrom();
            Location a = e.getTo();
            /* Se compara la posicion, no el bloque: cancelar el evento entero
             * le congela tambien la camara y eso marea de verdad. Asi puede
             * mirar alrededor pero no andar. */
            if (a != null && (de.getX() != a.getX() || de.getY() != a.getY() || de.getZ() != a.getZ())) {
                Location quieto = de.clone();
                quieto.setYaw(a.getYaw());
                quieto.setPitch(a.getPitch());
                e.setTo(quieto);
            }
            return;
        }

        if (estados().tiene(p, Estados.Marca.CORRIENDO)) {
            /* Solo cuando pisa suelo: empujar en el aire lo manda a la luna. */
            if (p.isOnGround()) {
                p.setVelocity(p.getLocation().getDirection().setY(0).normalize().multiply(0.55)
                        .setY(p.getVelocity().getY()));
            }
        }
    }

    // ---------------------------------------------------------------- hablar

    @EventHandler(priority = EventPriority.LOWEST)
    public void alHablar(AsyncChatEvent e) {
        if (!estados().tiene(e.getPlayer(), Estados.Marca.MUDO)) return;
        e.setCancelled(true);
        /* Se le devuelve SU propio mensaje: la gracia es que lo vea salir y
         * nadie le conteste. Si no viera nada, sabria que esta silenciado. */
        e.getPlayer().sendMessage(e.message());
    }

    // ----------------------------------------------------------------- picar

    @EventHandler(ignoreCancelled = true)
    public void alRomper(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (estados().tiene(p, Estados.Marca.SIN_MINAR)) {
            e.setCancelled(true);
            p.sendActionBar(net.ederus.edm.comun.Estilo.legado("&cTu pico rebota"));
            return;
        }
        if (estados().tiene(p, Estados.Marca.PATATA)) {
            /* ADEMAS de lo suyo, no en lugar de lo suyo: quitarle el drop de
             * verdad seria robarle, y eso ya no es una broma. */
            e.getBlock().getWorld().dropItemNaturally(
                    e.getBlock().getLocation().add(0.5, 0.5, 0.5), new ItemStack(Material.POTATO));
        }
    }

    // --------------------------------------------------------------- recoger

    @EventHandler(ignoreCancelled = true)
    public void alRecoger(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (estados().tiene(p, Estados.Marca.SIN_RECOGER)) e.setCancelled(true);
    }

    // ------------------------------------------------------------ el aterrizaje

    @EventHandler(ignoreCancelled = true)
    public void alHacerseDaño(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!estados().tiene(p, Estados.Marca.SIN_CAIDA)) return;
        /* La red de las bromas de altura. Sin esto, "al cielo" es "matarlo por
         * la espalda", que es justo lo que este modulo no hace. */
        e.setCancelled(true);
    }

    // ------------------------------------------------------------ desconectar

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        int n = estados().quitarTodo(e.getPlayer().getUniqueId());
        if (n > 0) {
            modulo.core().getLogger().info("Deshechas " + n + " bromas de "
                    + e.getPlayer().getName() + " al salir.");
        }
    }
}
