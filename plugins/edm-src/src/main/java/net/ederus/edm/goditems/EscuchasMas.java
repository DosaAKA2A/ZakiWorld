package net.ederus.edm.goditems;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import com.destroystokyo.paper.event.entity.EntityJumpEvent;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;

/**
 * Los activadores del vocabulario ampliado.
 *
 * Van en una clase aparte de `Escuchas` y no porque sean distintos, sino porque
 * aquella ya era la lista completa de lo basico y meter veinte escuchas mas
 * dentro la convertia en un fichero que nadie vuelve a leer entero. Aqui esta
 * todo lo que nacio despues: gestos del cuerpo, mundo, region, pesca y rachas.
 *
 * Las reglas de prioridad son las mismas que alli: NORMAL o mas tarde, nunca
 * antes, para no colarse delante del calculo de daño de MMOItems.
 */
public final class EscuchasMas implements Listener {

    private final GodItemsPlugin modulo;

    /** Ultimo estado conocido, para convertir "esta" en "acaba de". */
    private final Map<UUID, Boolean> enAgua = new HashMap<>();
    private final Map<UUID, Boolean> planeando = new HashMap<>();
    private final Map<UUID, String> region = new HashMap<>();

    public EscuchasMas(GodItemsPlugin modulo) {
        this.modulo = modulo;
    }

    /* ============================================================== combate */

    /**
     * RECIBIR_CRITICO: el critico de vanilla visto desde el golpeado.
     *
     * El de MythicLib llega por `EscuchasMmo`, igual que en CRITICO. El guardia
     * de `Criticos` es el que impide que un golpe critico por los dos caminos
     * dispare el efecto dos veces.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alRecibirCriticoVanilla(EntityDamageByEntityEvent e) {
        if (!e.isCritical()) return;
        recibirCritico(e.getEntity(), e);
    }

    /** Dispara RECIBIR_CRITICO en todo el equipo del golpeado, una vez por golpe. */
    public void recibirCritico(Entity golpeado, org.bukkit.event.Event e) {
        if (!(golpeado instanceof Player victima)) return;
        if (!Criticos.primeroRecibido(victima)) return;
        Entity atacante = e instanceof EntityDamageByEntityEvent d ? d.getDamager() : null;
        if (atacante instanceof Projectile pr && pr.getShooter() instanceof Entity dueno) {
            atacante = dueno;
        }
        this.modulo.dispararEnEquipo(victima, Activador.RECIBIR_CRITICO, e, atacante, null, null);
    }

    /** El combo se lleva aqui: un contador por jugador y objetivo, sin item de por medio. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alContarCombo(EntityDamageByEntityEvent e) {
        Entity atacante = e.getDamager();
        if (atacante instanceof Projectile pr && pr.getShooter() instanceof Entity dueno) {
            atacante = dueno;
        }
        if (atacante instanceof Player j) this.modulo.combate().golpe(j, e.getEntity());
    }

    /**
     * MATAR_JEFE y RACHA.
     *
     * Van en el mismo sitio porque las dos cuelgan de la misma muerte y asi el
     * orden queda claro: primero sube la racha, luego se mira si toca disparar.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void alMatarAlgo(EntityDeathEvent e) {
        Player j = e.getEntity().getKiller();
        if (j == null) return;
        ItemStack item = j.getInventory().getItemInMainHand();
        GodItem def = this.modulo.identidad().definicionDe(item);
        int racha = this.modulo.combate().sumarRacha(j);

        if (def != null) {
            GodItem.Bloque b = def.bloque(Activador.MATAR_JEFE);
            if (b != null && Combate.esJefe(e.getEntity(), b.vidaMinima())) {
                this.modulo.disparar(j, item, def, Activador.MATAR_JEFE, e, EquipmentSlot.HAND,
                        e.getEntity(), null);
            }
        }
        /* La racha la lleva el jugador, no el arma: cualquier GodItem que tenga
         * encima puede reaccionar a ella, no solo el que dio el ultimo golpe. */
        this.modulo.dispararEnInventario(j, Activador.RACHA, e,
                b -> racha > 0 && racha % b.racha() == 0);
    }

    /** Morir corta la racha y el combo. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void alMorirCortarRacha(org.bukkit.event.entity.PlayerDeathEvent e) {
        this.modulo.combate().reiniciarRacha(e.getEntity());
        this.modulo.combate().reiniciarCombo(e.getEntity());
    }

    /** CAER: el daño de caida, con el item encima. Nadie a quien apuntar. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alCaer(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(e.getEntity() instanceof Player j)) return;
        this.modulo.dispararEnEquipo(j, Activador.CAER, e, null, null, null);
    }

    /* =============================================================== gestos */

    /**
     * INTERACTUAR_BLOQUE e INTERACTUAR_ENTIDAD.
     *
     * Son mas finos que CLIC_DERECHO, que salta tambien al aire. `bloque:` en el
     * activador acota a un material concreto sin tener que escribir una condicion.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void alTocarBloque(PlayerInteractEvent e) {
        if (e.getHand() == null || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;
        ItemStack item = e.getItem();
        GodItem def = this.modulo.identidad().definicionDe(item);
        if (def == null) return;
        GodItem.Bloque b = def.bloque(Activador.INTERACTUAR_BLOQUE);
        if (b == null) return;
        String material = e.getClickedBlock().getType().name();
        if (!b.filtro().isBlank() && !b.filtro().equalsIgnoreCase(material)) return;
        this.modulo.disparar(e.getPlayer(), item, def, Activador.INTERACTUAR_BLOQUE, e, e.getHand(),
                null, e.getClickedBlock().getLocation().add(0.5, 0.5, 0.5));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void alTocarEntidad(PlayerInteractEntityEvent e) {
        ItemStack item = e.getPlayer().getInventory().getItem(e.getHand());
        GodItem def = this.modulo.identidad().definicionDe(item);
        if (def == null) return;
        GodItem.Bloque b = def.bloque(Activador.INTERACTUAR_ENTIDAD);
        if (b == null) return;
        if (!b.filtro().isBlank() && !b.filtro().equalsIgnoreCase(e.getRightClicked().getType().name())) return;
        this.modulo.disparar(e.getPlayer(), item, def, Activador.INTERACTUAR_ENTIDAD, e, e.getHand(),
                e.getRightClicked(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alAgacharse(PlayerToggleSneakEvent e) {
        this.modulo.dispararEnEquipo(e.getPlayer(),
                e.isSneaking() ? Activador.AGACHARSE : Activador.LEVANTARSE, e, null, null, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alCorrer(PlayerToggleSprintEvent e) {
        this.modulo.dispararEnEquipo(e.getPlayer(),
                e.isSprinting() ? Activador.EMPEZAR_CORRER : Activador.PARAR_CORRER, e, null, null, null);
    }

    /**
     * SALTAR. Paper lo avisa con PlayerJumpEvent; sin el habria que adivinarlo
     * mirando la velocidad en Y en cada movimiento, que es caro y falla.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alSaltar(PlayerJumpEvent e) {
        this.modulo.dispararEnEquipo(e.getPlayer(), Activador.SALTAR, e, null, null, null);
    }

    /** El mismo salto de una montura o de un caballo no cuenta: solo el del jugador. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alSaltarBicho(EntityJumpEvent e) {
        if (e.getEntity() instanceof Player j) {
            this.modulo.dispararEnEquipo(j, Activador.SALTAR, e, null, null, null);
        }
    }

    /**
     * Planear y el agua se miran en el movimiento y no tienen evento propio que
     * sirva: lo que interesa es el CAMBIO de estado, no el estado. Se guarda el
     * anterior por jugador y solo se dispara cuando pasa de uno a otro.
     *
     * Solo se mira cuando se cambia de BLOQUE, no en cada micromovimiento: es la
     * diferencia entre un puñado de comprobaciones y veinte por segundo y jugador.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alMoverse(PlayerMoveEvent e) {
        if (!cambioDeBloque(e)) return;
        Player j = e.getPlayer();
        UUID id = j.getUniqueId();

        boolean agua = j.getLocation().getBlock().isLiquid();
        Boolean antesAgua = this.enAgua.put(id, agua);
        if (antesAgua != null && antesAgua != agua) {
            this.modulo.dispararEnEquipo(j, agua ? Activador.ENTRAR_AGUA : Activador.SALIR_AGUA,
                    e, null, null, null);
        }

        boolean planea = j.isGliding();
        Boolean antesPlanea = this.planeando.put(id, planea);
        if (antesPlanea != null && antesPlanea != planea) {
            this.modulo.dispararEnEquipo(j,
                    planea ? Activador.EMPEZAR_PLANEAR : Activador.PARAR_PLANEAR, e, null, null, null);
        }

        regiones(j, e, j.getLocation());
    }

    private static boolean cambioDeBloque(PlayerMoveEvent e) {
        Location a = e.getFrom();
        Location b = e.getTo();
        return b == null || a.getBlockX() != b.getBlockX() || a.getBlockY() != b.getBlockY()
                || a.getBlockZ() != b.getBlockZ();
    }

    /**
     * ENTRAR_REGION y SALIR_REGION.
     *
     * WorldGuard no lanza eventos de entrada y salida en su version libre, asi
     * que se comparan los ids que mandan en el punto con los del paso anterior.
     * `region:` en el activador dice cual interesa; vacio, cualquiera.
     */
    private void regiones(Player j, org.bukkit.event.Event e, Location donde) {
        String ahora = this.modulo.regiones().nombres(donde);
        String antes = this.region.put(j.getUniqueId(), ahora);
        if (antes == null || antes.equals(ahora)) return;
        for (String r : ahora.split(",\\s*")) {
            if (!r.isBlank() && !contiene(antes, r)) {
                this.modulo.dispararEnEquipo(j, Activador.ENTRAR_REGION, e, null, null,
                        b -> b.filtro().isBlank() || b.filtro().equalsIgnoreCase(r));
            }
        }
        for (String r : antes.split(",\\s*")) {
            if (!r.isBlank() && !contiene(ahora, r)) {
                this.modulo.dispararEnEquipo(j, Activador.SALIR_REGION, e, null, null,
                        b -> b.filtro().isBlank() || b.filtro().equalsIgnoreCase(r));
            }
        }
    }

    private static boolean contiene(String lista, String uno) {
        for (String r : lista.split(",\\s*")) {
            if (r.equalsIgnoreCase(uno)) return true;
        }
        return false;
    }

    /* ============================================================ inventario */

    /** COMER: terminar de comerse cualquier cosa. CONSUMIR es comerse el item. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alComer(PlayerItemConsumeEvent e) {
        this.modulo.dispararEnEquipo(e.getPlayer(), Activador.COMER, e, null, null, null);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void alPescar(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        ItemStack item = e.getPlayer().getInventory().getItemInMainHand();
        GodItem def = this.modulo.identidad().definicionDe(item);
        if (def == null) return;
        this.modulo.disparar(e.getPlayer(), item, def, Activador.PESCAR, e, EquipmentSlot.HAND,
                e.getCaught(), null);
    }

    /* ================================================================ mundo */

    /**
     * DISPARAR: soltar un proyectil con el item en la mano.
     *
     * Se marca el proyectil con el id del GodItem, igual que hace la accion
     * PROYECTIL, para que PROYECTIL_IMPACTA sepa luego de quien salio. Asi una
     * flecha de un arco GodItem trae su comportamiento hasta donde cae.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alDisparar(ProjectileLaunchEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player j)) return;
        ItemStack item = j.getInventory().getItemInMainHand();
        GodItem def = this.modulo.identidad().definicionDe(item);
        if (def == null) return;
        /* La marca solo se pone si el item PIDE alguno de los activadores de
         * proyectil. Marcar siempre le colaria PROYECTIL_IMPACTA a cualquier
         * arco que ya sea un GodItem, y eso cambiaria a la fuerza items que
         * llevan tiempo funcionando sin que nadie lo haya pedido. */
        if (def.bloque(Activador.PROYECTIL_IMPACTA) != null
                || def.bloque(Activador.PROYECTIL_IMPACTA_ENTIDAD) != null
                || def.bloque(Activador.PROYECTIL_IMPACTA_BLOQUE) != null) {
            e.getEntity().getPersistentDataContainer().set(this.modulo.identidad().clave(),
                    PersistentDataType.STRING, def.id());
        }
        this.modulo.disparar(j, item, def, Activador.DISPARAR, e, EquipmentSlot.HAND,
                e.getEntity(), null);
    }

    /** Los dos subtipos del impacto. El general lo dispara `Escuchas`. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void alImpactarFino(ProjectileHitEvent e) {
        String id = e.getEntity().getPersistentDataContainer()
                .get(this.modulo.identidad().clave(), PersistentDataType.STRING);
        if (id == null) return;
        GodItem def = this.modulo.registro().porId(id);
        if (def == null) return;
        if (!(e.getEntity().getShooter() instanceof Player j)) return;
        ItemStack item = j.getInventory().getItemInMainHand();

        if (e.getHitEntity() instanceof LivingEntity vivo && !vivo.isDead()) {
            this.modulo.disparar(j, item, def, Activador.PROYECTIL_IMPACTA_ENTIDAD, e,
                    EquipmentSlot.HAND, vivo, null);
        } else if (e.getHitBlock() != null) {
            this.modulo.disparar(j, item, def, Activador.PROYECTIL_IMPACTA_BLOQUE, e,
                    EquipmentSlot.HAND, null, e.getHitBlock().getLocation().add(0.5, 0.5, 0.5));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alCambiarDeMundo(PlayerChangedWorldEvent e) {
        this.modulo.dispararEnEquipo(e.getPlayer(), Activador.CAMBIAR_MUNDO, e, null, null, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alDormir(PlayerBedEnterEvent e) {
        if (e.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
        this.modulo.dispararEnEquipo(e.getPlayer(), Activador.DORMIR, e, null, null, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alDespertar(PlayerBedLeaveEvent e) {
        this.modulo.dispararEnEquipo(e.getPlayer(), Activador.DESPERTAR, e, null, null, null);
    }

    /* =============================================================== sesion */

    @EventHandler(priority = EventPriority.MONITOR)
    public void alEntrar(PlayerJoinEvent e) {
        Player j = e.getPlayer();
        this.enAgua.put(j.getUniqueId(), j.getLocation().getBlock().isLiquid());
        this.planeando.put(j.getUniqueId(), j.isGliding());
        this.region.put(j.getUniqueId(), this.modulo.regiones().nombres(j.getLocation()));
        /* Un par de ticks de margen: al entrar, el inventario aun se esta
         * cargando y un item que reaccione a ENTRAR no se veria a si mismo. */
        this.modulo.core().getServer().getScheduler().runTaskLater(this.modulo.core(),
                () -> this.modulo.dispararEnInventario(j, Activador.ENTRAR, null, b -> true), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alSalirDelJuego(PlayerQuitEvent e) {
        Player j = e.getPlayer();
        this.modulo.dispararEnInventario(j, Activador.SALIR, e, b -> true);
        this.enAgua.remove(j.getUniqueId());
        this.planeando.remove(j.getUniqueId());
        this.region.remove(j.getUniqueId());
    }
}
