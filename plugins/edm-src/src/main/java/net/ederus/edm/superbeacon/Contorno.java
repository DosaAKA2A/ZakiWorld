package net.ederus.edm.superbeacon;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * "Ver alcance": el borde del circulo dibujado con particulas durante 10 s, solo para
 * quien lo pide (Player.spawnParticle, no World: nadie mas lo ve).
 *
 * Sobrio a proposito: una sola fila de polvo fino del color del tipo, a la altura de los
 * pies de quien mira, repintada cada medio segundo. Va "forzada" porque el cliente no
 * dibuja particulas a mas de 32 bloques, y con radio 48 el borde entero quedaria fuera.
 */
final class Contorno {

    static final int VECES = 20;
    static final long CADA_TICKS = 10L;

    private final SuperBeaconPlugin plugin;
    private final Map<UUID, BukkitTask> tareas = new HashMap<>();

    Contorno(SuperBeaconPlugin plugin) {
        this.plugin = plugin;
    }

    void mostrar(Player p, Baliza b, TipoBaliza t) {
        UUID id = p.getUniqueId();
        parar(id);
        int r = t.radio;
        int puntos = Math.max(24, Math.min(360, (int) Math.round(2 * Math.PI * r / 1.2)));
        Particle.DustOptions polvo = new Particle.DustOptions(Color.fromRGB(t.color()), 1.2f);
        double cx = b.x + 0.5, cz = b.z + 0.5;
        int[] veces = {0};
        BukkitTask tarea = Bukkit.getScheduler().runTaskTimer(plugin.core(), () -> {
            Player q = Bukkit.getPlayer(id);
            if (q == null || ++veces[0] > VECES || plugin.detenido() || !q.getWorld().getName().equals(b.mundo)) {
                parar(id);
                return;
            }
            double y = Math.max(b.y - r, q.getLocation().getY() + 0.15);
            for (int i = 0; i < puntos; i++) {
                double a = Math.PI * 2 * i / puntos;
                q.spawnParticle(Particle.DUST, cx + Math.cos(a) * r, y, cz + Math.sin(a) * r,
                        1, 0, 0, 0, 0, polvo, true);
            }
        }, 0L, CADA_TICKS);
        tareas.put(id, tarea);
    }

    void parar(UUID jugador) {
        BukkitTask t = tareas.remove(jugador);
        if (t != null) t.cancel();
    }

    void pararTodo() {
        for (BukkitTask t : tareas.values()) t.cancel();
        tareas.clear();
    }
}
