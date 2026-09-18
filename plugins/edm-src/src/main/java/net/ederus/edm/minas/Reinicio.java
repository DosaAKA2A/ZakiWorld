package net.ederus.edm.minas;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import net.ederus.edm.Module;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Estilo;
import net.kyori.adventure.text.Component;

/**
 * El reloj y el relleno de las minas.
 *
 * Un tic por segundo mira cada mina: si toca reiniciar, se saca a la gente y se
 * rellena; si falta poco, se avisa. El relleno va por lotes de N bloques por tick
 * (config) y sin fisicas, para que una mina grande no congele el servidor ni
 * dispare mil actualizaciones de agua y arena.
 *
 * El umbral no reinicia por su cuenta: adelanta el reloj unos segundos y deja que
 * el tic haga lo suyo, asi el aviso y la salida son los mismos de siempre.
 */
public final class Reinicio {

    private final MinasPlugin plugin;
    private BukkitTask reloj;

    public Reinicio(MinasPlugin plugin) {
        this.plugin = plugin;
    }

    public void arrancar() {
        reloj = plugin.getServer().getScheduler().runTaskTimer(Module.dueno(plugin), this::tic, 20L, 20L);
    }

    public void parar() {
        if (reloj != null) reloj.cancel();
    }

    private void tic() {
        List<Integer> avisos = plugin.getConfig().getIntegerList("avisos.segundos");
        for (Mina m : plugin.minas().todas()) {
            if (!m.conZona() || m.reiniciando() || m.proximo() <= 0) continue;
            int s = m.segundos();
            if (s <= 0) {
                reiniciar(m, "reloj");
                continue;
            }
            if (avisos.contains(s) && m.ultimoAviso() != s) {
                m.ultimoAviso(s);
                avisar(m, plugin.texto("aviso-reinicio", "{sin-prefijo}&7La mina &x&E&8&A&2&5&C%mina% &7se reinicia en &f%segundos% s",
                        "%mina%", m.nombre(), "%segundos%", String.valueOf(s)));
            }
        }
    }

    /** Adelanta el reinicio a dentro de `segundos`, si no habia otro antes. */
    public void programar(Mina m, int segundos) {
        long cuando = System.currentTimeMillis() + Math.max(1, segundos) * 1000L;
        if (m.proximo() <= 0 || m.proximo() > cuando) m.proximo(cuando);
    }

    /**
     * Rellena la mina. Devuelve false si no se puede (sin zona, sin bloques, ya en
     * marcha o mundo descargado).
     */
    public boolean reiniciar(Mina m, String motivo) {
        World w = m.mundo();
        if (w == null || !m.conZona() || m.partes().isEmpty() || m.reiniciando()) {
            if (m.intervalo() > 0 && !m.reiniciando()) {
                // Sin bloques o sin mundo no hay nada que rellenar; el reloj sigue
                // para que no se quede clavado en 0.
                m.proximo(System.currentTimeMillis() + m.intervalo() * 1000L);
            }
            return false;
        }
        m.reiniciando(true);
        sacar(m);

        // La ruleta: partes acumuladas, un numero al azar por bloque.
        List<Material> mats = new ArrayList<>();
        List<Integer> acumulado = new ArrayList<>();
        int total = 0;
        for (Map.Entry<Material, Integer> e : m.partes().entrySet()) {
            total += e.getValue();
            mats.add(e.getKey());
            acumulado.add(total);
        }
        final int suma = total;
        final int porTick = Math.max(200, plugin.getConfig().getInt("relleno.bloques-por-tick", 3000));
        final long inicio = System.currentTimeMillis();

        new BukkitRunnable() {
            int x = m.minX(), y = m.minY(), z = m.minZ();
            long puestos = 0;

            @Override
            public void run() {
                int n = 0;
                while (n < porTick) {
                    int r = ThreadLocalRandom.current().nextInt(suma);
                    int i = 0;
                    while (acumulado.get(i) <= r) i++;
                    w.getBlockAt(x, y, z).setType(mats.get(i), false);
                    n++;
                    puestos++;
                    if (++z > m.maxZ()) {
                        z = m.minZ();
                        if (++x > m.maxX()) {
                            x = m.minX();
                            if (++y > m.maxY()) {
                                terminar();
                                cancel();
                                return;
                            }
                        }
                    }
                }
            }

            private void terminar() {
                m.minados(0);
                m.reiniciando(false);
                m.proximo(m.intervalo() > 0 ? System.currentTimeMillis() + m.intervalo() * 1000L : 0);
                long ms = System.currentTimeMillis() - inicio;
                plugin.anotar("reinicio", m.id(), motivo, puestos + " bloques", ms + " ms");
                avisar(m, plugin.texto("reiniciada", "{sin-prefijo}&x&E&8&A&2&5&C%mina% &7se ha reiniciado.",
                        "%mina%", m.nombre()));
                Location centro = m.salida();
                if (centro != null) {
                    Compat.sound(w, centro, "block.respawn_anchor.charge", 0.8f, 1.2f);
                }
            }
        }.runTaskTimer(Module.dueno(plugin), 0L, 1L);
        return true;
    }

    /** Saca de la caja a quien este dentro, antes de que el relleno lo entierre. */
    private void sacar(Mina m) {
        World w = m.mundo();
        if (w == null) return;
        for (Player p : w.getPlayers()) {
            if (!m.contiene(p.getLocation())) continue;
            Location a = m.spawn();
            if (a == null) {
                Location l = p.getLocation();
                a = new Location(w, l.getX(), m.maxY() + 1.0, l.getZ(), l.getYaw(), l.getPitch());
            }
            p.teleport(a);
            plugin.di(p, "sacado", "La mina se está reiniciando; te hemos sacado.");
        }
    }

    /** Un aviso a quien este dentro o cerca de la mina: barra de accion y, si se pide, chat. */
    private void avisar(Mina m, Component linea) {
        World w = m.mundo();
        if (w == null) return;
        int radio = plugin.getConfig().getInt("avisos.radio", 48);
        boolean chat = plugin.getConfig().getBoolean("avisos.tambien-en-el-chat", false);
        for (Player p : w.getPlayers()) {
            if (!m.cerca(p.getLocation(), radio)) continue;
            p.sendActionBar(linea);
            if (chat) p.sendMessage(Estilo.aviso(linea));
        }
    }
}
