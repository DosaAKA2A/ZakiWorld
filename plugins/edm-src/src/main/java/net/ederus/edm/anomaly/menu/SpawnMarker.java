package net.ederus.edm.anomaly.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.ederus.edm.comun.menu.MenuUtil;
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
 * El modo de marcar el punto del Coliseo, el unico sitio donde salen las anomalias.
 *
 * Desde el menu se pulsa "Punto del Coliseo", el menu se cierra y el SIGUIENTE
 * bloque que el admin golpee (o toque con clic derecho) queda como el Coliseo
 * (automatico.coliseo del config.yml): ahi aparecen todas, a mano o por turno.
 * Marcar solo marca; no abre nada.
 *
 * Vale golpear Y tocar a proposito: desde Bedrock (Geyser) el toque corto llega como
 * clic derecho y el gesto de picar como izquierdo, asi que cualquiera de los dos
 * funciona igual desde consola, movil o Java.
 */
public final class SpawnMarker implements Listener {

    /** Cuanto dura el modo marcar antes de caducar solo. */
    private static final long TIMEOUT_MS = 60_000;

    private final AnomalyPlugin plugin;
    /** Quien esta marcando y hasta cuando vale. */
    private final Map<UUID, Long> pending = new HashMap<>();

    public SpawnMarker(AnomalyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Arranca el modo marcar el Coliseo para ese admin. */
    public void begin(Player player) {
        pending.put(player.getUniqueId(), System.currentTimeMillis() + TIMEOUT_MS);
        // Cerrar el inventario DENTRO del clic que nos trajo aqui esta prohibido por
        // la API; un tick despues es seguro y nadie nota la diferencia.
        plugin.getServer().getScheduler().runTask(net.ederus.edm.Module.dueno(plugin), () -> {
            if (player.isOnline()) player.closeInventory();
        });
        player.sendMessage(plugin.prefix()
                .append(Component.text("Golpea o toca el bloque donde quieres el ", NamedTextColor.WHITE))
                .append(Component.text("Coliseo", MenuUtil.GOLD))
                .append(Component.text(". Las anomalías saldrán encima de él.", NamedTextColor.WHITE)));
        player.sendMessage(plugin.prefix()
                .append(Component.text("Tienes 60 segundos. Para cancelar, vuelve a abrir el menú.",
                        NamedTextColor.GRAY)));
        Compat.sound(player.getWorld(), player.getLocation(), "block.note_block.pling", 0.7f, 1.6f);
    }

    /** Cancela el modo marcar si estaba puesto; lo llama el menu al abrirse. */
    public void cancel(Player player) {
        pending.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        // Cada clic llega dos veces (mano principal y secundaria); con una basta.
        if (e.getHand() != EquipmentSlot.HAND) return;
        Long expires = pending.get(e.getPlayer().getUniqueId());
        if (expires == null) return;
        if (System.currentTimeMillis() > expires) {
            pending.remove(e.getPlayer().getUniqueId());
            e.getPlayer().sendMessage(plugin.prefix()
                    .append(Component.text("El modo de marcar caducó sin tocar nada.", NamedTextColor.GRAY)));
            return;
        }
        Action a = e.getAction();
        if (a != Action.LEFT_CLICK_BLOCK && a != Action.RIGHT_CLICK_BLOCK) return;
        Block block = e.getClickedBlock();
        if (block == null) return;

        // El golpe es para marcar, no para romper ni para usar el bloque.
        e.setCancelled(true);
        pending.remove(e.getPlayer().getUniqueId());

        // El jefe aparece DE PIE SOBRE el bloque marcado, centrado.
        Location spot = block.getLocation().add(0.5, 1.0, 0.5);
        plugin.settings().setColiseum(spot);

        // La marca se ve: un anillo dorado sobre el bloque elegido.
        var dust = Compat.dust(MenuUtil.GOLD.value(), 1.6f);
        Fx.ring(spot.clone().add(0, 0.2, 0), 1.1, 20, l ->
                Compat.spawn(block.getWorld(), Compat.DUST, l, 1, 0, 0, 0, 0, dust));
        Compat.spawn(block.getWorld(), Compat.END_ROD, spot.clone().add(0, 0.6, 0), 12, 0.25, 0.4, 0.25, 0.02);
        Compat.sound(block.getWorld(), spot, "block.respawn_anchor.set_spawn", 0.9f, 1.2f);

        Player player = e.getPlayer();
        player.sendMessage(plugin.prefix()
                .append(Component.text("Coliseo marcado en ", NamedTextColor.GREEN))
                .append(Component.text(block.getX() + " " + (block.getY() + 1) + " " + block.getZ(),
                        NamedTextColor.WHITE))
                .append(Component.text("  (" + block.getWorld().getName() + ")", NamedTextColor.GRAY)));
        player.sendMessage(plugin.prefix()
                .append(Component.text("Todas las anomalías salen aquí, a mano y por turno.", NamedTextColor.GRAY)));

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
