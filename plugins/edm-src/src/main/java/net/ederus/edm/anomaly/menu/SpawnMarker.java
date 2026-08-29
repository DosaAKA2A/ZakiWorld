package net.ederus.edm.anomaly.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.core.AnomalyType;
import net.ederus.edm.anomaly.core.Compat;
import net.ederus.edm.anomaly.core.Fx;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * El modo de marcar el punto de aparicion de una anomalia.
 *
 * Desde el menu se pulsa "Punto de aparicion", el menu se cierra y el SIGUIENTE
 * bloque que el admin golpee (o toque con click derecho) queda marcado como el
 * spawner de esa anomalia: ahi aparecera siempre que se pulse Iniciar. Marcar solo
 * marca; la anomalia se sigue activando desde el menu, como siempre.
 *
 * Vale golpear Y tocar a proposito: desde Bedrock (Geyser) el toque corto llega como
 * click derecho y el gesto de picar como izquierdo, asi que cualquiera de los dos
 * funciona igual desde consola, movil o Java.
 */
public final class SpawnMarker implements Listener {

    /** Cuanto dura el modo marcar antes de caducar solo. */
    private static final long TIMEOUT_MS = 60_000;

    private record Pending(String anomalyId, long expiresAt) {
    }

    private final AnomalyPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public SpawnMarker(AnomalyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Arranca el modo marcar para ese admin y esa anomalia. */
    public void begin(Player player, AnomalyType type) {
        pending.put(player.getUniqueId(), new Pending(type.id(), System.currentTimeMillis() + TIMEOUT_MS));
        // Cerrar el inventario DENTRO del click que nos trajo aqui esta prohibido por
        // la API; un tick despues es seguro y nadie nota la diferencia.
        plugin.getServer().getScheduler().runTask(net.ederus.edm.Module.dueno(plugin), () -> {
            if (player.isOnline()) player.closeInventory();
        });
        player.sendMessage(plugin.prefix()
                .append(Component.text("Golpea o toca el bloque donde quieres que aparezca ", NamedTextColor.WHITE))
                .append(Component.text(type.display(), type.color(), TextDecoration.BOLD))
                .append(Component.text(".", NamedTextColor.WHITE)));
        player.sendMessage(plugin.prefix()
                .append(Component.text("Tienes 60 segundos. Para cancelar, vuelve a abrir el menu.",
                        NamedTextColor.GRAY)));
        Compat.sound(player.getWorld(), player.getLocation(), "block.note_block.pling", 0.7f, 1.6f);
    }

    /** Cancela el modo marcar si estaba puesto; lo llama el menu al abrirse. */
    public void cancel(Player player) {
        pending.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        // Cada click llega dos veces (mano principal y secundaria); con una basta.
        if (e.getHand() != EquipmentSlot.HAND) return;
        Pending pend = pending.get(e.getPlayer().getUniqueId());
        if (pend == null) return;
        if (System.currentTimeMillis() > pend.expiresAt()) {
            pending.remove(e.getPlayer().getUniqueId());
            e.getPlayer().sendMessage(plugin.prefix()
                    .append(Component.text("El modo de marcar caduco sin tocar nada.", NamedTextColor.GRAY)));
            return;
        }
        Action a = e.getAction();
        if (a != Action.LEFT_CLICK_BLOCK && a != Action.RIGHT_CLICK_BLOCK) return;
        Block block = e.getClickedBlock();
        if (block == null) return;

        // El golpe es para marcar, no para romper ni para usar el bloque.
        e.setCancelled(true);
        pending.remove(e.getPlayer().getUniqueId());

        AnomalyType type = plugin.registry().get(pend.anomalyId());
        if (type == null) return;

        // El jefe aparece DE PIE SOBRE el bloque marcado, centrado.
        Location spot = block.getLocation().add(0.5, 1.0, 0.5);
        plugin.registry().setSpawnPoint(type, spot);

        // La marca se ve: un anillo del color de la anomalia sobre el bloque elegido.
        var dust = Compat.dust(type.color().value(), 1.6f);
        Fx.ring(spot.clone().add(0, 0.2, 0), 1.1, 20, l ->
                Compat.spawn(block.getWorld(), Compat.DUST, l, 1, 0, 0, 0, 0, dust));
        Compat.spawn(block.getWorld(), Compat.END_ROD, spot.clone().add(0, 0.6, 0), 12, 0.25, 0.4, 0.25, 0.02);
        Compat.sound(block.getWorld(), spot, "block.respawn_anchor.set_spawn", 0.9f, 1.2f);

        Player player = e.getPlayer();
        player.sendMessage(plugin.prefix()
                .append(Component.text("Punto marcado  ", NamedTextColor.GREEN))
                .append(Component.text(type.display(), type.color(), TextDecoration.BOLD))
                .append(Component.text("  aparecera en ", NamedTextColor.WHITE))
                .append(Component.text(block.getX() + " " + (block.getY() + 1) + " " + block.getZ(),
                        NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text("  (" + block.getWorld().getName() + ")", NamedTextColor.GRAY)));
        player.sendMessage(plugin.prefix()
                .append(Component.text("La anomalia se activa como siempre, con el boton ", NamedTextColor.GRAY))
                .append(Component.text("Iniciar", NamedTextColor.GREEN, TextDecoration.BOLD))
                .append(Component.text(" del menu.", NamedTextColor.GRAY)));

        // De vuelta al panel un instante despues, con el punto ya pintado en su boton.
        plugin.getServer().getScheduler().runTaskLater(net.ederus.edm.Module.dueno(plugin), () -> {
            if (player.isOnline()) plugin.menus().openHub(player);
        }, 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }
}
