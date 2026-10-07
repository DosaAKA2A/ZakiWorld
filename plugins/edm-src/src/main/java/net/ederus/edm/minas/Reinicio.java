package net.ederus.edm.minas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import net.ederus.edm.Module;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Estilo;
import net.ederus.edm.minas.api.MineResetCompleteEvent;
import net.ederus.edm.minas.api.MineResetReason;
import net.ederus.edm.minas.api.MineResetStartEvent;
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
 * el tic haga lo suyo, asi el aviso y la salida son los mismos de siempre. Lo que
 * si hace es dejar apuntado el motivo, para que el evento diga THRESHOLD.
 *
 * 1.80.0 · Cada relleno lanza MineResetStartEvent al empezar y MineResetCompleteEvent
 * al poner el ultimo bloque, con el mismo cycleId. Si el relleno se corta (se apaga
 * el modulo, la mina se borra o cambia de zona, o su mundo se descarga) se para
 * ahi y el Complete no sale.
 */
public final class Reinicio {

    private final MinasPlugin plugin;
    private BukkitTask reloj;
    /** Los rellenos en marcha por id de mina: uno como mucho por mina, y se cortan si el modulo se apaga. */
    private final Map<String, BukkitRunnable> rellenos = new HashMap<>();

    public Reinicio(MinasPlugin plugin) {
        this.plugin = plugin;
    }

    public void arrancar() {
        reloj = plugin.getServer().getScheduler().runTaskTimer(Module.dueno(plugin), this::tic, 20L, 20L);
    }

    public void parar() {
        if (reloj != null) reloj.cancel();
        for (BukkitRunnable r : new ArrayList<>(rellenos.values())) {
            try {
                r.cancel();
            } catch (IllegalStateException ignorado) {
                // Ya no estaba programado.
            }
        }
        rellenos.clear();
    }

    private void tic() {
        List<Integer> avisos = plugin.getConfig().getIntegerList("avisos.segundos");
        for (Mina m : plugin.minas().todas()) {
            if (!m.conZona() || m.reiniciando() || m.proximo() <= 0) continue;
            int s = m.segundos();
            if (s <= 0) {
                reiniciar(m, m.motivoProgramado() == MineResetReason.THRESHOLD ? "umbral" : "reloj");
                continue;
            }
            if (avisos.contains(s) && m.ultimoAviso() != s) {
                m.ultimoAviso(s);
                avisar(m, plugin.texto("aviso-reinicio", "{sin-prefijo}&7La mina &x&E&8&A&2&5&C%mina% &7se reinicia en &f%segundos% s",
                        "%mina%", m.nombre(), "%segundos%", String.valueOf(s)));
            }
        }
    }

    /** Hay un relleno en marcha para esa mina (aunque la mina se haya recargado a medias). */
    public boolean rellenando(String id) {
        return rellenos.containsKey(id);
    }

    /** Adelanta el reinicio a dentro de `segundos`, si no habia otro antes. Lo usa el umbral. */
    public void programar(Mina m, int segundos) {
        long cuando = System.currentTimeMillis() + Math.max(1, segundos) * 1000L;
        if (m.proximo() <= 0 || m.proximo() > cuando) {
            m.proximo(cuando);
            m.motivoProgramado(MineResetReason.THRESHOLD);
        }
    }

    /** El motivo de la bitacora ("reloj", "umbral", "comando", "menu") en el del evento. */
    static MineResetReason motivo(String motivo) {
        return switch (motivo) {
            case "umbral" -> MineResetReason.THRESHOLD;
            case "comando" -> MineResetReason.COMMAND;
            case "menu" -> MineResetReason.MENU;
            default -> MineResetReason.TIMER;
        };
    }

    /**
     * Rellena la mina. Devuelve false si no se puede (sin zona, sin bloques, ya en
     * marcha o mundo descargado).
     */
    public boolean reiniciar(Mina m, String motivo) {
        World w = m.mundo();
        boolean enMarcha = m.reiniciando() || rellenos.containsKey(m.id());
        if (w == null || !m.conZona() || m.partes().isEmpty() || enMarcha) {
            if (m.intervalo() > 0 && !enMarcha) {
                // Sin bloques o sin mundo no hay nada que rellenar; el reloj sigue
                // para que no se quede clavado en 0.
                m.proximo(System.currentTimeMillis() + m.intervalo() * 1000L);
                m.motivoProgramado(null);
            }
            return false;
        }
        m.reiniciando(true);
        m.motivoProgramado(null);
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

        // La foto de este relleno: lo que dicen los dos eventos y contra lo que se comprueba
        // en cada lote que la mina sigue siendo la misma.
        final String id = m.id();
        final String mundo = m.mundoNombre();
        final int x0 = m.minX(), y0 = m.minY(), z0 = m.minZ(), x1 = m.maxX(), y1 = m.maxY(), z1 = m.maxZ();
        final UUID ciclo = UUID.randomUUID();
        final MineResetReason razon = motivo(motivo);

        plugin.core().getServer().getPluginManager()
                .callEvent(new MineResetStartEvent(id, w, x0, y0, z0, x1, y1, z1, ciclo, razon));

        BukkitRunnable tarea = new BukkitRunnable() {
            /** La mina que se rellena; tras un /mine reload es el objeto nuevo con el mismo id. */
            Mina mina = m;
            int x = x0, y = y0, z = z0;
            long puestos = 0;

            @Override
            public void run() {
                String corte = comprobar();
                if (corte != null) {
                    mina.reiniciando(false);
                    plugin.anotar("relleno-cortado", id, motivo, corte, puestos + " bloques");
                    acabar();
                    return;
                }
                int n = 0;
                while (n < porTick) {
                    int r = ThreadLocalRandom.current().nextInt(suma);
                    int i = 0;
                    while (acumulado.get(i) <= r) i++;
                    w.getBlockAt(x, y, z).setType(mats.get(i), false);
                    n++;
                    puestos++;
                    if (++z > z1) {
                        z = z0;
                        if (++x > x1) {
                            x = x0;
                            if (++y > y1) {
                                // acabar() pase lo que pase: si terminar() fallara, la tarea
                                // seguiria viva y repetiria el Complete con bloques fuera de la caja.
                                try {
                                    terminar();
                                } finally {
                                    acabar();
                                }
                                return;
                            }
                        }
                    }
                }
            }

            /** null si se puede seguir; si no, por que se corta. */
            private String comprobar() {
                if (Bukkit.getWorld(mundo) != w) return "mundo descargado";
                Mina actual = plugin.minas().de(id);
                if (actual == null) return "mina borrada";
                if (!actual.conZona() || !mundo.equals(actual.mundoNombre())
                        || actual.minX() != x0 || actual.minY() != y0 || actual.minZ() != z0
                        || actual.maxX() != x1 || actual.maxY() != y1 || actual.maxZ() != z1) {
                    return "zona cambiada";
                }
                if (actual != mina) {
                    // /mine reload carga la mina de nuevo: el relleno sigue con la nueva.
                    mina.reiniciando(false);
                    mina = actual;
                    mina.reiniciando(true);
                }
                return null;
            }

            private void acabar() {
                rellenos.remove(id, this);
                cancel();
            }

            private void terminar() {
                mina.minados(0);
                mina.reiniciando(false);
                mina.motivoProgramado(null);
                mina.proximo(mina.intervalo() > 0 ? System.currentTimeMillis() + mina.intervalo() * 1000L : 0);
                long ms = System.currentTimeMillis() - inicio;
                plugin.anotar("reinicio", id, motivo, puestos + " bloques", ms + " ms");
                plugin.core().getServer().getPluginManager().callEvent(
                        new MineResetCompleteEvent(id, w, x0, y0, z0, x1, y1, z1, ciclo, razon, puestos, ms));
                avisar(mina, plugin.texto("reiniciada", "{sin-prefijo}&x&E&8&A&2&5&C%mina% &7se ha reiniciado.",
                        "%mina%", mina.nombre()));
                Location centro = mina.salida();
                if (centro != null) {
                    Compat.sound(w, centro, "block.respawn_anchor.charge", 0.8f, 1.2f);
                }
            }
        };
        rellenos.put(id, tarea);
        tarea.runTaskTimer(Module.dueno(plugin), 0L, 1L);
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
