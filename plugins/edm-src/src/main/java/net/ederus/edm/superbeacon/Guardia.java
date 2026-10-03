package net.ederus.edm.superbeacon;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;

import com.destroystokyo.paper.event.block.BeaconEffectEvent;
import com.destroystokyo.paper.event.block.BlockDestroyEvent;

/**
 * Todo lo que le puede pasar al bloque de un Super Beacon, y los jugadores que entran y
 * salen.
 *
 * Colocar: se valida en HIGH (despues de las protecciones de WorldGuard, ProtectionStones
 * y compañia, con ignoreCancelled: si ellas dicen que no, no se mira nada) y se apunta en
 * MONITOR, cuando ya nadie lo va a cancelar. Apuntarlo antes dejaria una baliza registrada
 * sin bloque si otro plugin cancela despues.
 *
 * Picar: solo quien puede gestionarla. El evento se cancela SIEMPRE y la recogida la hace
 * el modulo: asi el bloque nunca suelta su drop vanilla y ningun plugin posterior que
 * des-cancele el evento puede sacar un faro y un Super Beacon de la misma baliza.
 *
 * Proteger: explosiones, pistones, agua y lava, fuego, desgaste y entidades que cambian
 * bloques. Lo que se escape por una via que no controlamos (WorldEdit, un plugin que pone
 * aire a pelo) lo detecta la revision de cada 5 s y la baliza vuelve a su dueño.
 *
 * Un faro vanilla sin registro no se toca en ninguno de estos eventos: todas las
 * comprobaciones empiezan por buscar el bloque en el registro, que es O(1).
 */
final class Guardia implements Listener {

    private final SuperBeaconPlugin plugin;
    /** Cuando se le dijo a cada uno "esto es de otro", para no repetirlo en cada golpe. */
    private final Map<UUID, Long> avisados = new HashMap<>();

    Guardia(SuperBeaconPlugin plugin) {
        this.plugin = plugin;
    }

    private Baliza en(Block b) {
        return plugin.registro().en(b);
    }

    /* ================================================================ colocar */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alColocar(BlockPlaceEvent e) {
        // Poner un bloque ENCIMA del de una baliza (si su bloque fuera de los que se
        // reemplazan, como la nieve) la borraria sin pasar por picarla.
        Baliza debajo = en(e.getBlockPlaced());
        if (debajo != null && e.getBlockReplacedState().getType() == debajo.material) {
            e.setCancelled(true);
            return;
        }
        Ficha f = plugin.objeto().leer(e.getItemInHand());
        if (f == null) return;
        if (!plugin.entregas().puedeColocar(e.getPlayer(), f, e.getBlockPlaced(),
                e.getBlockReplacedState().getType())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alQuedarColocado(BlockPlaceEvent e) {
        if (!e.canBuild()) return;
        Ficha f = plugin.objeto().leer(e.getItemInHand());
        if (f == null) return;
        plugin.entregas().colocar(e.getPlayer(), f, e.getBlockPlaced());
    }

    /* =================================================================== usar */

    /**
     * Usar el bloque abre NUESTRO menu y nunca la ventana del faro vanilla. Agachado y con
     * algo en la mano no se toca: es poner un bloque al lado, como con cualquier bloque.
     * Si una proteccion ya nego usar el bloque, no se abre nada.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void alUsar(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Baliza b = en(e.getClickedBlock());
        if (b == null) return;
        Player p = e.getPlayer();
        if (p.isSneaking() && (!p.getInventory().getItemInMainHand().getType().isAir()
                || !p.getInventory().getItemInOffHand().getType().isAir())) {
            return;
        }
        boolean negado = e.useInteractedBlock() == Event.Result.DENY;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND || negado) return;
        if (plugin.puedeGestionar(p, b)) {
            plugin.menu().abrir(p, b);
        } else {
            ajeno(p, b, false);
        }
    }

    /** Red por si alguien abre la ventana del faro por otra via (otro plugin, un comando). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alAbrirFaro(InventoryOpenEvent e) {
        if (e.getInventory().getType() != InventoryType.BEACON) return;
        Location l = e.getInventory().getLocation();
        if (l == null || l.getWorld() == null) return;
        if (plugin.registro().en(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ()) != null) {
            e.setCancelled(true);
        }
    }

    /** Con piramide el haz se enciende (es cosmetico y se deja), pero sus efectos vanilla no. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alEfectoFaro(BeaconEffectEvent e) {
        if (en(e.getBlock()) != null) e.setCancelled(true);
    }

    /* ================================================================= picar */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alRomper(BlockBreakEvent e) {
        Baliza b = en(e.getBlock());
        if (b == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!plugin.puedeGestionar(p, b)) {
            ajeno(p, b, true);
            return;
        }
        plugin.entregas().recoger(p, b);
    }

    /**
     * Red: si al final de todo el evento de picar NO esta cancelado, algun plugin lo
     * des-cancelo despues de nosotros. Se vuelve a cancelar sin drops (mejor un evento
     * re-cancelado en MONITOR que un faro vanilla de regalo) y se avisa en la consola.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alRomperSinCancelar(BlockBreakEvent e) {
        Baliza b = en(e.getBlock());
        if (b == null) return;
        e.setDropItems(false);
        e.setExpToDrop(0);
        e.setCancelled(true);
        plugin.getLogger().warning("[SuperBeacon] Otro plugin dejo picar el Super Beacon " + b.idCorto() + " en "
                + b.donde() + " (" + e.getPlayer().getName() + "); se volvio a cancelar.");
    }

    private void ajeno(Player p, Baliza b, boolean alPicar) {
        long ahora = System.currentTimeMillis();
        Long antes = avisados.get(p.getUniqueId());
        if (antes != null && ahora - antes < 2000) return;
        avisados.put(p.getUniqueId(), ahora);
        if (alPicar) {
            plugin.textos().manda(p, "romper-ajeno",
                    "&#FF5C5CEste Super Beacon es de &f%dueno%&#FF5C5C: solo su dueño puede recogerlo.",
                    "%dueno%", b.duenoTexto());
            return;
        }
        TipoBaliza t = plugin.tipo(b.tipo);
        String clan = t != null && t.beneficia == TipoBaliza.Beneficia.CLAN ? plugin.motor().clanDe(b) : null;
        if (clan != null) {
            plugin.textos().manda(p, "ajeno-clan",
                    "&7Este Super Beacon es de &f%dueno% &7y beneficia al clan &f%clan%&7.",
                    "%dueno%", b.duenoTexto(), "%clan%", clan);
        } else {
            plugin.textos().manda(p, "ajeno", "&7Este Super Beacon es de &f%dueno%&7.", "%dueno%", b.duenoTexto());
        }
    }

    /* =============================================================== proteger */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void alExplotar(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> en(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void alExplotarBloque(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> en(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alEmpujar(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (en(b) != null) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alTirar(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (en(b) != null) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** Agua o lava que se lo llevaria por delante (con un bloque que no sea macizo). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alFluir(BlockFromToEvent e) {
        if (en(e.getToBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alArder(BlockBurnEvent e) {
        if (en(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alDesgastarse(BlockFadeEvent e) {
        if (en(e.getBlock()) != null) e.setCancelled(true);
    }

    /** Oxidarse, cuajar... un bloque que cambia solo dejaria de ser "su" bloque. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alFormarse(BlockFormEvent e) {
        if (en(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alSecarseHojas(LeavesDecayEvent e) {
        if (en(e.getBlock()) != null) e.setCancelled(true);
    }

    /** Enderman, wither, bloques que caen, ovejas... nada que no sea un jugador picando. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alCambiarBloque(EntityChangeBlockEvent e) {
        if (en(e.getBlock()) != null) e.setCancelled(true);
    }

    /** Fisicas de Paper: un bloque que se rompe porque le quitaron el apoyo, y similares. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alDestruirse(BlockDestroyEvent e) {
        if (en(e.getBlock()) != null) e.setCancelled(true);
    }

    /* ================================================================== chunks */

    /** Un tick despues: se mira si su bloque sigue y se le pone el holograma. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void alCargarChunk(ChunkLoadEvent e) {
        List<Baliza> aqui = plugin.registro().enChunk(e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ());
        if (aqui.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin.core(), () -> {
            if (plugin.detenido()) return;
            for (Baliza b : aqui) plugin.revisar(b, true);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alDescargarChunk(ChunkUnloadEvent e) {
        for (Baliza b : plugin.registro().enChunk(e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ())) {
            plugin.hologramas().quitar(b.id);
        }
    }

    /* ============================================================== jugadores */

    /**
     * Al entrar: fuera cualquier modificador superbeacon: que le haya quedado (red; son
     * transitorios), se le reconoce el vuelo si se lo dimos nosotros y se le entrega lo
     * pendiente cuando ya ha cargado (2 s).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void alEntrar(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.entregas().olvidarAviso(p.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin.core(), () -> {
            if (!p.isOnline() || plugin.detenido()) return;
            int restos = plugin.atributos().barrer(p);
            if (restos > 0) {
                plugin.getLogger().info("[SuperBeacon] " + p.getName() + " entro con " + restos
                        + " modificador(es) superbeacon: de antes; quitados.");
            }
            if (plugin.vuelo().apuntado(p)) plugin.motor().sembrar(p, plugin.vuelo().marcador);
            if (plugin.registro().ligar(p) > 0) plugin.motor().reindexar();
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin.core(), () -> {
            if (p.isOnline() && !plugin.detenido()) plugin.entregas().entregarTodo(p);
        }, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alSalir(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.motor().olvidar(p);
        plugin.contorno().parar(p.getUniqueId());
        plugin.clanes().olvidar(p.getUniqueId());
        plugin.entregas().olvidarAviso(p.getUniqueId());
        avisados.remove(p.getUniqueId());
    }

    /** Tras reaparecer, el siguiente ciclo vuelve a poner los atributos aunque no hayan cambiado. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void alReaparecer(PlayerRespawnEvent e) {
        plugin.atributos().olvidar(e.getPlayer().getUniqueId());
    }
}
