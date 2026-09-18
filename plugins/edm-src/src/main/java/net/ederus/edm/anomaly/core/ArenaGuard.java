package net.ederus.edm.anomaly.core;

import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.boss.BossFight;
import net.ederus.edm.biomas.BiomasPlugin;
import net.ederus.edm.biomas.Zona;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Tags;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * La arena como REGION propia de Anomaly.
 *
 * El coliseo estaba protegido solo por WorldGuard, y WorldGuard no sabe nada de las
 * anomalias: cancelaba la rotura de los pilares de la Quimera y de la tela de Aragon
 * (son bloques dentro de una region cerrada) y cancelaba los golpes a cualquier cuerpo
 * que no fuera un monstruo (un golem, una cabra, un aldeano, un soporte de armadura).
 * Como EDM carga DESPUES que WorldGuard, sus avisos de prioridad normal ni se
 * enteraban: el evento les llegaba ya cancelado.
 *
 * Esto hace tres cosas, y las tres valen con WorldGuard puesto o sin el:
 *
 *   1. LO DE LA ANOMALIA SE ROMPE SIEMPRE. Un bloque que levanto la pelea (ver
 *      BossFight.ownsBlock) se resuelve aqui, en LOWEST, y el evento se da por
 *      cancelado para los demas: ninguna proteccion llega a verlo, asi que ni lo
 *      bloquea ni le suelta al jugador su "no puedes romper eso aqui". Vale dentro y
 *      fuera de la arena.
 *
 *   2. LA ARENA SE PROTEGE SOLA. Dentro de la zona (arena.zona) nadie rompe, pone,
 *      quema, revienta ni inunda nada que no sea de la anomalia. Quien tenga
 *      anomaly.arena.build (los operadores lo tienen) construye con normalidad.
 *
 *   3. A LO NUESTRO SE LE PEGA SIEMPRE. El golpe de un jugador a un jefe, a su
 *      maniqui, a un esbirro (de anomalia o de /esb) o a un objetivo destructible se
 *      devuelve si otro plugin lo cancelo. Se hace dos veces: en NORMAL, que es justo
 *      despues de WorldGuard, para que todo lo que venga detras (MMOItems, las
 *      habilidades de los esbirros) vea el golpe vivo; y en HIGHEST, desde el
 *      AnomalyManager, por si lo cancela alguien mas tarde.
 *
 * Lo que NO hace: puertas, palancas, macetas y demas clics de uso. Eso sigue siendo
 * cosa de WorldGuard si la region se mantiene, y las dos protecciones conviven.
 */
public final class ArenaGuard implements Listener {

    /** Quien tenga este permiso construye dentro de la arena como en cualquier sitio. */
    public static final String PERMISO = "anomaly.arena.build";

    private static final TextColor AVISO = TextColor.color(0xFF5C5C);

    private final AnomalyPlugin plugin;

    // La caja se resuelve como mucho una vez por segundo: BlockFromToEvent y compania
    // se disparan miles de veces y no pueden ir a leer el config en cada una.
    private long resueltaEn;
    private Zona zona;
    private boolean protegida;
    private boolean columnaEntera;

    /** Ultimo aviso a cada jugador (millis), para no repetirlo en cada golpe de pico. */
    private final Map<UUID, Long> ultimoAviso = new HashMap<>();
    /** A quien ya se le anoto en la bitacora que una proteccion le tumbaba los golpes. */
    private final Set<UUID> golpeAnotado = new HashSet<>();

    public ArenaGuard(AnomalyPlugin plugin) {
        this.plugin = plugin;
    }

    // ----------------------------------------------------------------------- la caja

    private void resolver() {
        long ahora = System.currentTimeMillis();
        if (ahora - resueltaEn < 1000) return;
        resueltaEn = ahora;
        String nombre = plugin.settings().arenaZone();
        BiomasPlugin biomas = BiomasPlugin.activo();
        zona = nombre.isBlank() || biomas == null ? null : biomas.zona(nombre);
        protegida = plugin.settings().arenaProtected();
        columnaEntera = plugin.settings().arenaFullColumn();
    }

    /** Olvida la caja guardada: lo llama el comando al cambiar la zona o la proteccion. */
    public void refrescar() {
        resueltaEn = 0;
    }

    /** La zona que hace de arena, o null si no hay ninguna puesta (o falta Lethal Biomes). */
    public Zona zona() {
        resolver();
        return zona;
    }

    /** Si la arena existe y se esta protegiendo ahora mismo. */
    public boolean activa() {
        resolver();
        return zona != null && protegida;
    }

    /** Si ese bloque cae dentro de la arena protegida. */
    public boolean protege(Block b) {
        return b != null && protege(b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    public boolean protege(Location l) {
        return l != null && l.getWorld() != null
                && protege(l.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    private boolean protege(World w, int x, int y, int z) {
        resolver();
        Zona a = zona;
        if (a == null || !protegida || w == null || !w.getName().equals(a.mundo())) return false;
        if (x < a.minX() || x > a.maxX() || z < a.minZ() || z > a.maxZ()) return false;
        // La columna entera por defecto: la zona se creo pensando en el clima, y su
        // altura no tiene por que cubrir el techo ni los cimientos del coliseo.
        return columnaEntera || (y >= a.minY() && y <= a.maxY());
    }

    private boolean puedeConstruir(Player p) {
        return p != null && p.hasPermission(PERMISO);
    }

    private BossFight pelea() {
        ActiveAnomaly event = plugin.manager().current();
        return event == null ? null : event.fight();
    }

    private void avisar(Player p, String texto) {
        long ahora = System.currentTimeMillis();
        Long antes = ultimoAviso.get(p.getUniqueId());
        if (antes != null && ahora - antes < 1500) return;
        ultimoAviso.put(p.getUniqueId(), ahora);
        p.sendActionBar(Component.text(texto, AVISO));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ultimoAviso.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------ 1 · lo de la anomalia

    /**
     * Romper. LOWEST y sin ignorar lo cancelado: aqui se decide antes que nadie.
     *
     * Un bloque de la anomalia lo resuelve la pelea (la Quimera derrumba su pilar) o,
     * si la pelea no tiene nada que decir, se quita sin soltar nada (la tela de
     * Aragon). En los dos casos el evento queda cancelado: es la forma de que
     * WorldGuard, CoreProtect o un plugin de oficios ni lo vean.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();

        BossFight pelea = pelea();
        if (pelea != null && esDeLaPelea(pelea, b)) {
            e.setCancelled(true);
            boolean resuelto = false;
            try {
                resuelto = pelea.onBlockBroken(b, p);
            } catch (Throwable t) {
                plugin.getLogger().warning("Fallo al reaccionar a un bloque roto: " + t);
            }
            if (!resuelto) quitar(b);
            return;
        }

        if (e.isCancelled() || !protege(b) || puedeConstruir(p)) return;
        e.setCancelled(true);
        avisar(p, pelea != null
                ? "Aquí solo se rompe lo que levanta la anomalía."
                : "La arena está protegida.");
    }

    private boolean esDeLaPelea(BossFight pelea, Block b) {
        try {
            return pelea.ownsBlock(b);
        } catch (Throwable t) {
            plugin.getLogger().warning("Fallo al preguntar de quien es un bloque: " + t);
            return false;
        }
    }

    /** Quita un bloque de la anomalia como si lo hubieran roto, pero sin soltar nada. */
    private void quitar(Block b) {
        Location centro = b.getLocation().add(0.5, 0.5, 0.5);
        try {
            Compat.spawn(b.getWorld(), Compat.BLOCK, centro, 18, 0.25, 0.25, 0.25, 0.05, b.getBlockData());
            b.getWorld().playSound(centro, b.getBlockData().getSoundGroup().getBreakSound(),
                    org.bukkit.SoundCategory.BLOCKS, 1.0f, 0.9f);
        } catch (Throwable ignored) {
            // El adorno no puede impedir que el bloque se quite.
        }
        b.setType(Material.AIR, false);
    }

    // ------------------------------------------------------- 2 · la arena protegida

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!protege(e.getBlock()) || puedeConstruir(e.getPlayer())) return;
        e.setCancelled(true);
        avisar(e.getPlayer(), "La arena está protegida.");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (!protege(e.getBlock()) || puedeConstruir(e.getPlayer())) return;
        e.setCancelled(true);
        avisar(e.getPlayer(), "La arena está protegida.");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (!protege(e.getBlock()) || puedeConstruir(e.getPlayer())) return;
        e.setCancelled(true);
        avisar(e.getPlayer(), "La arena está protegida.");
    }

    /** Las explosiones siguen haciendo dano a quien pillen; lo que no hacen es crater. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!activa()) return;
        e.blockList().removeIf(this::protege);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!activa()) return;
        e.blockList().removeIf(this::protege);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (protege(e.getBlock())) e.setCancelled(true);
    }

    /** Solo el fuego: que el musgo o la hierba se extiendan no es vandalismo. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (e.getNewState().getType() != Material.FIRE && e.getNewState().getType() != Material.SOUL_FIRE) return;
        if (protege(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (!protege(e.getBlock())) return;
        if (e.getPlayer() != null && puedeConstruir(e.getPlayer())) return;
        e.setCancelled(true);
    }

    /**
     * Ninguna entidad cambia un bloque de la arena: ni el enderman que se lleva uno,
     * ni el ravager que pisa el cultivo, ni la arena que cae, ni el jugador que salta
     * sobre la tierra arada (que tambien llega por aqui).
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (!protege(e.getBlock())) return;
        if (e.getEntity() instanceof Player p && puedeConstruir(p)) return;
        e.setCancelled(true);
    }

    /** El agua y la lava de fuera no entran. Lo de dentro fluye con normalidad. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (!activa()) return;
        if (protege(e.getToBlock()) && !protege(e.getBlock())) e.setCancelled(true);
    }

    /** Un piston de fuera no empuja ni arrastra bloques de la arena. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (cruzaElBorde(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (cruzaElBorde(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    private boolean cruzaElBorde(Block piston, List<Block> movidos, BlockFace hacia) {
        if (!activa() || protege(piston)) return false;
        if (protege(piston.getRelative(hacia))) return true;
        for (Block b : movidos) {
            if (protege(b) || protege(b.getRelative(hacia))) return true;
        }
        return false;
    }

    // --------------------------------------------------- cuadros, marcos y soportes

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if (!protege(e.getEntity().getLocation())) return;
        // Que se caiga porque le quitaron el bloque de detras no puede pasar aqui
        // dentro; lo que queda son explosiones y quien lo rompa a mano.
        if (e instanceof HangingBreakByEntityEvent por
                && por.getRemover() instanceof Player p && puedeConstruir(p)) return;
        if (e.getCause() == HangingBreakEvent.RemoveCause.PHYSICS) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (!protege(e.getEntity().getLocation())) return;
        if (e.getPlayer() != null && puedeConstruir(e.getPlayer())) return;
        e.setCancelled(true);
    }

    /** Girar un marco o quitarle lo que lleva puesto. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Hanging)) return;
        if (!protege(e.getRightClicked().getLocation()) || puedeConstruir(e.getPlayer())) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (!protege(e.getRightClicked().getLocation()) || puedeConstruir(e.getPlayer())) return;
        e.setCancelled(true);
    }

    /**
     * Los adornos de la arena no se rompen a golpes ni con una explosion. Los soportes
     * que son NUESTROS (las anclas, el estandarte) quedan fuera: esos estan para
     * romperlos, y de contar sus golpes se encarga el AnomalyManager.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDecorationDamage(EntityDamageEvent e) {
        Entity victima = e.getEntity();
        if (!(victima instanceof ArmorStand || victima instanceof Hanging || victima instanceof EnderCrystal)) return;
        if (esNuestra(victima) || !protege(victima.getLocation())) return;
        if (e instanceof EntityDamageByEntityEvent por) {
            Player p = AnomalyManager.attacker(por.getDamager());
            if (p != null && puedeConstruir(p)) return;
        }
        e.setCancelled(true);
    }

    /** Los cofres y barriles que decoran la arena no se abren. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !activa()) return;
        InventoryHolder dueno = e.getInventory().getHolder(false);
        Location donde = dueno instanceof BlockState estado ? estado.getLocation()
                : dueno instanceof DoubleChest doble ? doble.getLocation()
                : null;
        if (donde == null || !protege(donde) || puedeConstruir(p)) return;
        e.setCancelled(true);
        avisar(p, "La arena está protegida.");
    }

    // ------------------------------------------------ 3 · a lo nuestro se le pega

    /** Si esa entidad la ha puesto EDM para que la gente pelee contra ella. */
    public boolean esNuestra(Entity e) {
        if (e == null) return false;
        BossFight pelea = pelea();
        if (pelea != null && (e.equals(pelea.entity()) || e.equals(pelea.shell()))) return true;
        return Tags.isBoss(e) || Tags.isMinion(e)
                || plugin.anchors().isAnchor(e)
                || plugin.minionManager().isMinion(e);
    }

    /**
     * Devuelve el golpe de un jugador a una entidad nuestra si alguien lo cancelo.
     *
     * No mira la arena ni si hay anomalia abierta, a proposito: los esbirros de /esb
     * viven en las mazmorras, que son regiones de WorldGuard igual que el coliseo, y
     * ahi no hay ninguna anomalia que valga. Antes el desbloqueo exigia una abierta y
     * solo conocia la marca de los esbirros de jefe, asi que a un golem de mazmorra no
     * se le podia pegar nunca y a uno de anomalia solo mientras durara la pelea.
     *
     * @return true si el golpe estaba cancelado y se ha devuelto
     */
    public boolean devolverGolpe(EntityDamageByEntityEvent e) {
        if (!e.isCancelled() || !plugin.settings().bypassProtections()) return false;
        Entity victima = e.getEntity();
        if (!esNuestra(victima)) return false;
        Player p = AnomalyManager.attacker(e.getDamager());
        if (p == null) return false;
        e.setCancelled(false);
        /* Dentro de una region cerrada esto pasa en CADA golpe: se anota solo el
         * primero de cada jugador, que es lo que dice que la region lo bloqueaba. */
        if (golpeAnotado.add(p.getUniqueId())) {
            Location l = victima.getLocation();
            plugin.bitacora().anotar("proteccion", "golpe devuelto a " + p.getName(),
                    "sobre " + victima.getType(),
                    "en " + (l.getWorld() == null ? "?" : l.getWorld().getName())
                            + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(),
                    "solo se anota el primero");
        }
        return true;
    }

    /** Deja limpia la lista de "ya anotado" para la pelea siguiente. */
    public void olvidarAnotados() {
        golpeAnotado.clear();
    }

    /**
     * La primera pasada, en NORMAL. WorldGuard cancela en NORMAL y se registra antes
     * que EDM (lo tenemos en softdepend), asi que este aviso corre justo detras del
     * suyo y por delante de todo lo que escucha en HIGH y HIGHEST. La red de seguridad
     * para quien cancele mas tarde esta en AnomalyManager.onDamage.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onHit(EntityDamageByEntityEvent e) {
        devolverGolpe(e);
    }
}
