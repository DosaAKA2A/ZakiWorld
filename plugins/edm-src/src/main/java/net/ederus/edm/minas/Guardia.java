package net.ederus.edm.minas;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;

import net.ederus.edm.comun.Estilo;
import net.kyori.adventure.text.Component;

/**
 * Lo que pasa con los bloques de una mina.
 *
 * Picar dentro se permite SIEMPRE a quien tenga acceso, aunque WorldGuard u otro
 * lo haya cancelado antes: se resuelve en HIGHEST, despues de todos, y se
 * devuelve. Lo que no es picar (poner, empujar con pistones, explotar, inundar)
 * se corta, porque la mina la rellena el modulo y nadie mas.
 *
 * Con "directo-al-inventario" lo picado no cae al suelo: va a la mochila, y lo
 * que no cabe si cae. Es lo que se espera de una mina de prision.
 */
public final class Guardia implements Listener {

    private final MinasPlugin plugin;
    /** Cuando se le dijo a cada uno por ultima vez que no puede, para no repetirlo cada clic. */
    private final Map<java.util.UUID, Long> avisados = new HashMap<>();

    public Guardia(MinasPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void alRomper(BlockBreakEvent e) {
        Block b = e.getBlock();
        Mina m = plugin.minas().en(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        if (m == null) return;
        Player p = e.getPlayer();

        if (m.reiniciando()) {
            e.setCancelled(true);
            p.sendActionBar(plugin.texto("reiniciando", "{sin-prefijo}&7La mina se está reiniciando, espera un momento."));
            return;
        }
        if (!plugin.puedeEntrar(p, m)) {
            e.setCancelled(true);
            avisarSinAcceso(p, m);
            return;
        }

        // Es suya: lo hayan cancelado o no antes, se pica.
        e.setCancelled(false);
        m.minado();

        if (plugin.getConfig().getBoolean("directo-al-inventario", true)
                && p.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            ItemStack herramienta = p.getInventory().getItemInMainHand();
            Collection<ItemStack> drops = b.getDrops(herramienta, p);
            e.setDropItems(false);
            Location suelo = b.getLocation().add(0.5, 0.5, 0.5);
            for (ItemStack d : drops) {
                for (ItemStack sobra : p.getInventory().addItem(d).values()) {
                    b.getWorld().dropItemNaturally(suelo, sobra);
                }
            }
        }

        int umbral = m.umbral();
        if (umbral > 0 && m.porcentajeMinado() >= umbral) {
            int espera = plugin.getConfig().getInt("umbral.espera-segundos", 5);
            if (m.segundos() < 0 || m.segundos() > espera) {
                plugin.reinicio().programar(m, espera);
                plugin.anotar("umbral", m.id(), Math.round(m.porcentajeMinado()) + "%", p.getName());
            }
        }
    }

    private void avisarSinAcceso(Player p, Mina m) {
        long ahora = System.currentTimeMillis();
        Long antes = avisados.get(p.getUniqueId());
        if (antes != null && ahora - antes < 2000) return;
        avisados.put(p.getUniqueId(), ahora);
        plugin.di(p, "sin-acceso", "No tienes acceso a la mina %mina%.", "%mina%", m.nombre());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void alPoner(BlockPlaceEvent e) {
        Block b = e.getBlock();
        Mina m = plugin.minas().en(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        if (m == null || plugin.esAdmin(e.getPlayer())) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(plugin.texto("no-poner", "{sin-prefijo}&7En una mina no se ponen bloques, solo se pican."));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void alExplotar(EntityExplodeEvent e) {
        quitarDeMinas(e.blockList().iterator());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void alExplotarBloque(BlockExplodeEvent e) {
        quitarDeMinas(e.blockList().iterator());
    }

    private void quitarDeMinas(Iterator<Block> it) {
        while (it.hasNext()) {
            Block b = it.next();
            if (plugin.minas().en(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()) != null) it.remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void alEmpujar(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            Block destino = b.getRelative(e.getDirection());
            if (enMina(b) || enMina(destino)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void alTirar(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (enMina(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** Agua o lava que quiera entrar desde fuera: se queda fuera. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void alFluir(BlockFromToEvent e) {
        if (enMina(e.getToBlock()) && !enMina(e.getBlock())) e.setCancelled(true);
    }

    private boolean enMina(Block b) {
        return plugin.minas().en(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()) != null;
    }

    /** Para que los avisos por barra tengan el mismo aire que el resto. */
    static Component apagado(String t) {
        return Estilo.texto(t, Estilo.APAGADO);
    }
}
