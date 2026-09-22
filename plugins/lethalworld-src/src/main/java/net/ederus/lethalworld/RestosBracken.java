package net.ederus.lethalworld;

import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Quita los modificadores de Bracken que se quedan pegados fuera de Lethal World.
 *
 * El datapack pone, al entrar en Panacea, "bracken:panacea_armor" (-60 % de armadura) y
 * solo lo quita con el logro "changed_dimension from bracken:panacea". Saliendo por un
 * teletransporte de plugin, muriendo o desconectando dentro, ese logro no salta, y el
 * jugador se queda con 8 de armadura en vez de 20 en el mundo normal.
 *
 * Fuera de un mundo de Lethal World ningun modificador "bracken:" tiene sentido, asi que
 * al cambiar de mundo, al reaparecer y al entrar al servidor se barren todos. Va un tick
 * despues del evento, para correr detras de las funciones del datapack.
 */
final class RestosBracken implements Listener {

    private static final String NS = "bracken";

    private final LethalWorldPlugin plugin;

    RestosBracken(LethalWorldPlugin plugin) {
        this.plugin = plugin;
    }

    /** Los que ya estaban dentro cuando arranca el plugin (un /reload, un reinicio con gente). */
    void barrerConectados() {
        for (Player p : plugin.getServer().getOnlinePlayers()) barrer(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCambiarMundo(PlayerChangedWorldEvent e) {
        luego(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReaparecer(PlayerRespawnEvent e) {
        luego(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntrar(PlayerJoinEvent e) {
        luego(e.getPlayer());
    }

    private void luego(Player p) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) barrer(p);
        }, 1L);
    }

    private void barrer(Player p) {
        if (LethalWorldPlugin.esMundo(p.getWorld())) return;
        List<String> quitados = new ArrayList<>();
        for (Attribute a : Registry.ATTRIBUTE) {
            AttributeInstance inst = p.getAttribute(a);
            if (inst == null) continue;
            for (AttributeModifier m : List.copyOf(inst.getModifiers())) {
                if (!NS.equals(m.getKey().getNamespace())) continue;
                inst.removeModifier(m);
                quitados.add(m.getKey().asString());
            }
        }
        if (!quitados.isEmpty()) {
            plugin.getLogger().info("[Lethal World] " + p.getName() + " salio con restos de Bracken, quitados: "
                    + String.join(", ", quitados));
        }
    }
}
