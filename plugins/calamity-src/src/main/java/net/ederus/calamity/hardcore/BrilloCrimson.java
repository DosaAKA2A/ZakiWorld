package net.ederus.calamity.hardcore;

import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import net.ederus.edm.anomaly.core.Glow;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Calamity 1.8.0 · Quien empuna la Crimson Masamune en la mano principal brilla en rojo (el modulo
 * Glow de EDM, el de los jefes y los minijefes); al soltarla, deja de brillar. En cualquier mundo,
 * como las particulas rojas que le pone MMOItems.
 *
 * Se mira cuando cambia lo que lleva en la mano (EntityEquipmentChangedEvent de Paper, el mismo que
 * usa Ligado para el equipo), al entrar al servidor y al arrancar. Solo se le quita el brillo a
 * quien se lo puso esto: un brillo que venga de otra cosa no se toca.
 *
 * La Crimson se reconoce por su id de MMOItems (forja.piezas.crimson). Sin MMOItems no brilla nadie.
 */
final class BrilloCrimson implements Listener {

    private final Hardcore hc;
    /** A quien se lo ha puesto esto (y hay que quitarselo al soltarla). */
    private final Set<UUID> brillan = new HashSet<>();

    BrilloCrimson(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) hc.seguro("ambush", () -> revisar(p));
    }

    /** El id de MMOItems de la Crimson ("CALAMITY_ARMAS.CRIMSON_MASAMUNE"), o null. */
    private String id() {
        Entregas e = hc.entregas();
        return e == null ? null : e.idMmo("forja:crimson");
    }

    private boolean esCrimson(ItemStack it) {
        if (it == null || it.getType().isAir() || !PuenteMmo.disponible()) return false;
        String id = id();
        return id != null && id.equals(PuenteMmo.enlace(it));
    }

    /** Brilla si la lleva en la mano principal; si no, y el brillo era de esto, se apaga. */
    void revisar(Player p) {
        if (p == null || !p.isOnline()) return;
        UUID u = p.getUniqueId();
        boolean lleva = esCrimson(p.getInventory().getItemInMainHand());
        if (lleva && brillan.add(u)) {
            Glow.apply(p, NamedTextColor.RED);
        } else if (!lleva && brillan.remove(u)) {
            apagar(p);
        }
    }

    private static void apagar(Player p) {
        try {
            Glow.clear(p);
        } catch (Throwable ignorado) {
            // Sin el marcador sigue el brillo blanco: se apaga igual justo debajo.
        }
        p.setGlowing(false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMano(EntityEquipmentChangedEvent e) {
        if (!(e.getEntity() instanceof Player p) || !e.getEquipmentChanges().containsKey(EquipmentSlot.HAND)) return;
        hc.seguro("ambush", () -> revisar(p));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntrar(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // Un tick despues: al entrar aun no tiene el inventario cargado del todo.
        hc.plugin().getServer().getScheduler().runTask(hc.plugin(), () -> hc.seguro("ambush", () -> revisar(p)));
    }

    /** Al salir se le quita (el equipo del marcador se guarda en disco y lo apuntaria por su nombre). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSalir(PlayerQuitEvent e) {
        if (brillan.remove(e.getPlayer().getUniqueId())) apagar(e.getPlayer());
    }

    void parar() {
        HandlerList.unregisterAll(this);
        for (UUID u : brillan) {
            Player p = hc.plugin().getServer().getPlayer(u);
            if (p != null) apagar(p);
        }
        brillan.clear();
    }
}
