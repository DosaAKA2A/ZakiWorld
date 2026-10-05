package net.ederus.edm.superbeacon;

import org.bukkit.block.Container;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.PrepareInventoryResultEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;

import io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent;

/**
 * Todo lo que le puede pasar a un Super Beacon como OBJETO (el bloque es cosa de
 * {@link Guardia}).
 *
 * En el suelo: no caduca ni se rompe (cactus, explosiones, fuego, lava, rayos...). Es algo
 * comprado; que desaparezca a los 5 minutos de tirarlo o en un cactus no tiene sentido. Lo
 * que aun asi se pierde por una via que sabemos leer (el vacio, un /kill) vuelve como
 * pendiente a su dueño (ver {@link Perdidas}).
 *
 * Como ingrediente: nunca. Con el faro de serie no hay receta que lo use, pero un tipo puede
 * llevar otro bloque (un bloque de diamante se haria nueve diamantes en la mesa, uno de
 * carbon arderia en un horno). Fuera del resultado en la mesa de crafteo y en el crafter;
 * no se cuece ni se quema en hornos y hogueras; yunque, afiladora, herreria, cortapiedras,
 * telar y cartografia no dan resultado (en el yunque tampoco se renombra: un nombre falso
 * es la forma mas facil de colar uno barato por uno caro); y dispensadores y soltadores no
 * lo sueltan, colocan ni usan: se queda dentro.
 *
 * Todo empieza por mirar si el objeto es nuestro (Objeto.id: el PDC, barato); lo demas no se
 * toca.
 */
final class GuardiaObjeto implements Listener {

    private final SuperBeaconPlugin plugin;

    GuardiaObjeto(SuperBeaconPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean nuestro(ItemStack it) {
        return plugin.objeto().id(it) != null;
    }

    private boolean alguno(ItemStack[] cosas) {
        if (cosas == null) return false;
        for (ItemStack it : cosas) {
            if (nuestro(it)) return true;
        }
        return false;
    }

    /* ============================================================== en el suelo */

    /**
     * Al abrir un cofre (o cualquier inventario con casillas: barril, shulker, ender...), los
     * Super Beacons que haya dentro se repintan con el lore de ahora. Solo cambia nombre y
     * lore; el PDC, que es la verdad, no se toca.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alAbrir(org.bukkit.event.inventory.InventoryOpenEvent e) {
        if (plugin.detenido()) return;
        plugin.objeto().renovar(e.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alTirarse(ItemSpawnEvent e) {
        if (nuestro(e.getEntity().getItemStack())) e.getEntity().setUnlimitedLifetime(true);
    }

    /** Red para los que ya estaban en el suelo antes (un chunk que se carga). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void alCaducar(ItemDespawnEvent e) {
        if (nuestro(e.getEntity().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void alDanarse(EntityDamageEvent e) {
        if (e.getEntity() instanceof Item i && nuestro(i.getItemStack())) e.setCancelled(true);
    }

    /* =========================================================== como ingrediente */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void alPrepararCrafteo(PrepareItemCraftEvent e) {
        if (alguno(e.getInventory().getMatrix())) e.getInventory().setResult(null);
    }

    /** Red por si otro plugin repone el resultado despues de nosotros. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void alCraftear(CraftItemEvent e) {
        if (alguno(e.getInventory().getMatrix())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void alCraftearSolo(CrafterCraftEvent e) {
        if (e.getBlock().getState(false) instanceof Container c && alguno(c.getInventory().getContents())) {
            e.setCancelled(true);
        }
    }

    /** Hornos, ahumadores, altos hornos y hogueras. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void alCocer(BlockCookEvent e) {
        if (nuestro(e.getSource())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void alQuemarComoCombustible(FurnaceBurnEvent e) {
        if (nuestro(e.getFuel())) e.setCancelled(true);
    }

    /**
     * Yunque, afiladora, herreria, cortapiedras, telar y cartografia: en Paper todos lanzan
     * este evento (o uno que hereda de el y comparte su lista de oyentes).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void alPrepararResultado(PrepareInventoryResultEvent e) {
        if (alguno(e.getInventory().getContents())) e.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void alElegirEnCortapiedras(PlayerStonecutterRecipeSelectEvent e) {
        if (nuestro(e.getStonecutterInventory().getInputItem())) e.setCancelled(true);
    }

    /** Dispensadores y soltadores: no lo sueltan, colocan ni usan; se queda dentro. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void alDispensar(BlockDispenseEvent e) {
        if (nuestro(e.getItem())) e.setCancelled(true);
    }

    /* ================================================================ perdidas */

    /**
     * Un Super Beacon tirado que se va del mundo de verdad: por el vacio (OUT_OF_WORLD), por
     * tiempo (DESPAWN, si a pesar de todo pasa), muerto (DEATH: un /kill) o explotado. Vuelve
     * como pendiente a su dueño (Entregas.perdidaEnElSuelo).
     *
     * Las demas causas no: recogido por un jugador, una tolva o un mob (PICKUP) sigue
     * existiendo; descargado con su chunk (UNLOAD) tambien; y quitado por un plugin (PLUGIN)
     * puede ser un recolector que lo guarda en otro sitio, y devolverlo seria duplicarlo. Esa
     * queda en la bitacora para mirarla a mano. Un objeto ya vacio (recogido entero) no lleva
     * PDC y no se toma por nuestro.
     *
     * Va en su propia clase porque EntityRemoveEvent es API interna de Paper (aunque 26.2 la
     * lanza): si algun dia desaparece, falla solo este registro y lo demas sigue.
     */
    static final class Perdidas implements Listener {

        private final SuperBeaconPlugin plugin;

        Perdidas(SuperBeaconPlugin plugin) {
            this.plugin = plugin;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void alIrse(EntityRemoveEvent e) {
            if (!(e.getEntity() instanceof Item item)) return;
            // La causa primero: recoger objetos es de lo mas frecuente del servidor y asi no se lee
            // nada. PICKUP, UNLOAD, MERGE...: el objeto sigue existiendo en algun sitio.
            EntityRemoveEvent.Cause causa = e.getCause();
            boolean perdida = causa == EntityRemoveEvent.Cause.OUT_OF_WORLD || causa == EntityRemoveEvent.Cause.DESPAWN
                    || causa == EntityRemoveEvent.Cause.DEATH || causa == EntityRemoveEvent.Cause.EXPLODE;
            if (!perdida && causa != EntityRemoveEvent.Cause.PLUGIN) return;
            ItemStack it = item.getItemStack();
            Ficha f = plugin.objeto().leer(it);
            if (f == null) return;
            String donde = item.getWorld().getName() + " " + item.getLocation().getBlockX() + " "
                    + item.getLocation().getBlockY() + " " + item.getLocation().getBlockZ();
            if (perdida) {
                plugin.entregas().perdidaEnElSuelo(f, it.getType(), item.getThrower(), e.getCause().name(), donde);
                return;
            }
            plugin.getLogger().warning("[SuperBeacon] Un plugin quito del suelo el Super Beacon "
                    + f.id().toString().substring(0, 8) + " (" + f.tipo() + ", " + donde + "). Si no lo guardo"
                    + " en otro sitio, se ha perdido: esta en la bitacora para reponerlo a mano.");
            plugin.anotar("quitado-por-plugin", f.id().toString(), f.tipo(), f.duenoTexto(), donde);
        }
    }
}
