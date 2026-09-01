package net.ederus.edm.goditems;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;

/**
 * Los eventos de Bukkit traducidos a activadores.
 *
 * Todo va en prioridad NORMAL o mas tarde, nunca antes: MMOItems calcula el
 * daño de sus items en su propio listener, y colarse delante deja sus stats a
 * medias. Cancelar un golpe DESPUES de que el haya hecho sus cuentas no le
 * descuadra nada.
 */
public final class Escuchas implements Listener {

    private final GodItemsPlugin modulo;

    public Escuchas(GodItemsPlugin modulo) {
        this.modulo = modulo;
    }

    /* ------------------------------------------------------------- gestos */

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void alInteractuar(PlayerInteractEvent e) {
        if (e.getHand() == null) return;
        ItemStack item = e.getItem();
        GodItem def = this.modulo.identidad().definicionDe(item);
        if (def == null) return;

        Action a = e.getAction();
        boolean derecho = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
        boolean izquierdo = a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK;
        if (!derecho && !izquierdo) return;

        /* Un clic al aire dispara PlayerInteractEvent UNA VEZ POR MANO. Si el
         * mismo GodItem esta en las dos, el gesto se ejecutaria dos veces y el
         * jugador solo hizo uno. Se descarta el repetido del mismo tick. */
        if (repetido(e.getPlayer(), def.id())) return;

        boolean algo = this.modulo.disparar(e.getPlayer(), item, def,
                derecho ? Activador.CLIC_DERECHO : Activador.CLIC_IZQUIERDO, e, e.getHand(), null, null);
        algo |= this.modulo.disparar(e.getPlayer(), item, def, Activador.CLIC, e, e.getHand(), null, null);

        /* `exclusivo` calla a la habilidad que el item pueda tener por su set de
         * MMOItems / MythicLib: cancelando la interaccion, su listener no llega
         * a verla. Sin esto, un mismo clic derecho dispara las dos cosas. */
        if (algo && def.exclusivo()) e.setCancelled(true);
    }

    /** jugador -> "tick#item" del ultimo clic atendido. */
    private final java.util.Map<java.util.UUID, String> ultimoClic = new java.util.HashMap<>();

    private boolean repetido(Player j, String itemId) {
        String marca = this.modulo.core().getServer().getCurrentTick() + "#" + itemId;
        return marca.equals(this.ultimoClic.put(j.getUniqueId(), marca));
    }

    /* ------------------------------------------------------------ combate */

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void alGolpear(EntityDamageByEntityEvent e) {
        Entity atacante = e.getDamager();
        if (atacante instanceof Projectile pr && pr.getShooter() instanceof Entity dueno) {
            atacante = dueno;
        }
        if (atacante instanceof Player j) {
            ItemStack item = j.getInventory().getItemInMainHand();
            GodItem def = this.modulo.identidad().definicionDe(item);
            if (def != null) {
                this.modulo.disparar(j, item, def, Activador.GOLPEAR, e, EquipmentSlot.HAND,
                        e.getEntity(), null);
                if (e.getEntity() instanceof Player) {
                    this.modulo.disparar(j, item, def, Activador.GOLPEAR_JUGADOR, e, EquipmentSlot.HAND,
                            e.getEntity(), null);
                }
            }
        }
        if (e.getEntity() instanceof Player victima) {
            recorrerEquipo(victima, (item, def, hueco) ->
                    this.modulo.disparar(victima, item, def, Activador.RECIBIR_GOLPE, e, hueco,
                            e.getDamager(), null));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void alMatar(EntityDeathEvent e) {
        Player j = e.getEntity().getKiller();
        if (j == null) return;
        ItemStack item = j.getInventory().getItemInMainHand();
        GodItem def = this.modulo.identidad().definicionDe(item);
        if (def == null) return;
        this.modulo.disparar(j, item, def, Activador.MATAR, e, EquipmentSlot.HAND, e.getEntity(), null);
        if (e.getEntity() instanceof Player) {
            this.modulo.disparar(j, item, def, Activador.MATAR_JUGADOR, e, EquipmentSlot.HAND,
                    e.getEntity(), null);
        }
    }

    /**
     * ANTES_DE_MORIR: el golpe que iba a matar, aun cancelable.
     *
     * Se mira aqui y no en PlayerDeathEvent porque ahi ya esta muerto y no hay
     * vuelta atras. Un item de "segunda vida" tiene que poder cancelar ESTE
     * evento; para eso existe el activador.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void antesDeMorir(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player j)) return;
        if (j.getHealth() - e.getFinalDamage() > 0) return;
        recorrerEquipo(j, (item, def, hueco) ->
                this.modulo.disparar(j, item, def, Activador.ANTES_DE_MORIR, e, hueco, null, null));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void alMorir(org.bukkit.event.entity.PlayerDeathEvent e) {
        Player j = e.getEntity();
        recorrerEquipo(j, (item, def, hueco) -> {
            this.modulo.disparar(j, item, def, Activador.MORIR, e, hueco, null, null);
            /* `conservar-al-morir` se resuelve sacando el item de los drops y
             * devolviendoselo al reaparecer. Es lo unico que funciona con
             * keepInventory apagado, que es como esta Ederus. */
            if (def.conservarAlMorir()) this.modulo.guardarParaRespawn(j, item, e);
        });
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void alReaparecer(PlayerRespawnEvent e) {
        this.modulo.devolverGuardados(e.getPlayer());
        Player j = e.getPlayer();
        this.modulo.core().getServer().getScheduler().runTaskLater(this.modulo.core(), () ->
                recorrerEquipo(j, (item, def, hueco) ->
                        this.modulo.disparar(j, item, def, Activador.REAPARECER, null, hueco, null, null)), 2L);
    }

    /* ------------------------------------------------------- llevar encima */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alCambiarArmadura(PlayerArmorChangeEvent e) {
        Player j = e.getPlayer();
        GodItem puesto = this.modulo.identidad().definicionDe(e.getNewItem());
        GodItem quitado = this.modulo.identidad().definicionDe(e.getOldItem());
        if (quitado != null) {
            this.modulo.disparar(j, e.getOldItem(), quitado, Activador.DESEQUIPAR, e, null, null, null);
        }
        if (puesto != null) {
            this.modulo.disparar(j, e.getNewItem(), puesto, Activador.EQUIPAR, e, null, null, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alCambiarDeHueco(PlayerItemHeldEvent e) {
        Player j = e.getPlayer();
        ItemStack guarda = j.getInventory().getItem(e.getPreviousSlot());
        ItemStack empuna = j.getInventory().getItem(e.getNewSlot());
        GodItem defGuarda = this.modulo.identidad().definicionDe(guarda);
        GodItem defEmpuna = this.modulo.identidad().definicionDe(empuna);
        if (defGuarda != null) {
            this.modulo.disparar(j, guarda, defGuarda, Activador.GUARDAR, e, EquipmentSlot.HAND, null, null);
        }
        if (defEmpuna != null) {
            this.modulo.disparar(j, empuna, defEmpuna, Activador.EMPUNAR, e, EquipmentSlot.HAND, null, null);
        }
    }

    /* ---------------------------------------------------------- inventario */

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void alConsumir(PlayerItemConsumeEvent e) {
        GodItem def = this.modulo.identidad().definicionDe(e.getItem());
        if (def == null) return;
        this.modulo.disparar(e.getPlayer(), e.getItem(), def, Activador.CONSUMIR, e,
                e.getHand(), null, null);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void alTirar(PlayerDropItemEvent e) {
        ItemStack item = e.getItemDrop().getItemStack();
        GodItem def = this.modulo.identidad().definicionDe(item);
        if (def == null) return;
        this.modulo.disparar(e.getPlayer(), item, def, Activador.TIRAR, e, null, null, null);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void alRecoger(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player j)) return;
        ItemStack item = e.getItem().getItemStack();
        GodItem def = this.modulo.identidad().definicionDe(item);
        if (def == null) return;
        this.modulo.disparar(j, item, def, Activador.RECOGER, e, null, null, null);
    }

    /* --------------------------------------------------------------- mundo */

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void alRomper(BlockBreakEvent e) {
        ItemStack item = e.getPlayer().getInventory().getItemInMainHand();
        GodItem def = this.modulo.identidad().definicionDe(item);
        if (def == null) return;
        this.modulo.disparar(e.getPlayer(), item, def, Activador.ROMPER_BLOQUE, e, EquipmentSlot.HAND,
                null, e.getBlock().getLocation().add(0.5, 0.5, 0.5));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void alColocar(BlockPlaceEvent e) {
        ItemStack item = e.getItemInHand();
        GodItem def = this.modulo.identidad().definicionDe(item);
        if (def == null) return;
        this.modulo.disparar(e.getPlayer(), item, def, Activador.COLOCAR_BLOQUE, e, e.getHand(),
                null, e.getBlock().getLocation().add(0.5, 0.5, 0.5));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void alImpactar(ProjectileHitEvent e) {
        String id = e.getEntity().getPersistentDataContainer()
                .get(this.modulo.identidad().clave(), PersistentDataType.STRING);
        if (id == null) return;
        GodItem def = this.modulo.registro().porId(id);
        if (def == null) return;
        if (!(e.getEntity().getShooter() instanceof Player j)) return;
        this.modulo.disparar(j, j.getInventory().getItemInMainHand(), def,
                Activador.PROYECTIL_IMPACTA, e, EquipmentSlot.HAND, e.getHitEntity(),
                e.getHitBlock() != null
                        ? e.getHitBlock().getLocation().add(0.5, 0.5, 0.5)
                        : e.getEntity().getLocation());
    }

    /* ------------------------------------------------------------ limpieza */

    @EventHandler(priority = EventPriority.MONITOR)
    public void alSalir(PlayerQuitEvent e) {
        this.modulo.olvidar(e.getPlayer());
        this.ultimoClic.remove(e.getPlayer().getUniqueId());
    }

    /* --------------------------------------------------------------- ayuda */

    private interface ConEquipo {
        void con(ItemStack item, GodItem def, EquipmentSlot hueco);
    }

    /** Manos y armadura, que es lo que "lleva puesto" alguien a estos efectos. */
    private void recorrerEquipo(Player j, ConEquipo que) {
        var inv = j.getInventory();
        mirar(que, inv.getItemInMainHand(), EquipmentSlot.HAND);
        mirar(que, inv.getItemInOffHand(), EquipmentSlot.OFF_HAND);
        mirar(que, inv.getHelmet(), EquipmentSlot.HEAD);
        mirar(que, inv.getChestplate(), EquipmentSlot.CHEST);
        mirar(que, inv.getLeggings(), EquipmentSlot.LEGS);
        mirar(que, inv.getBoots(), EquipmentSlot.FEET);
    }

    private void mirar(ConEquipo que, ItemStack item, EquipmentSlot hueco) {
        GodItem def = this.modulo.identidad().definicionDe(item);
        if (def != null) que.con(item, def, hueco);
    }
}
