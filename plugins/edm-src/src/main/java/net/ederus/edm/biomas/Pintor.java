package net.ederus.edm.biomas;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.ederus.edm.Module;

/**
 * Pinta el bioma de una zona entera, repartido en ticks.
 *
 * Dos trampas que ya costaron en Lethal y que aqui se cubren desde el principio:
 * setBiome sobre un trozo NO cargado escribe en un trozo que el servidor tira sin
 * avisar, asi que cada trozo recibe su billete antes de tocarlo; y el cliente no
 * se entera del bioma nuevo hasta que se le reenvia el trozo.
 */
final class Pintor {

    /** Celdas de bioma por tick. Una arena de 400 bloques de lado son ~1M de celdas: 3-4 s. */
    private static final int CELDAS_POR_TICK = 20_000;
    /** Trozos reenviados por tick al terminar: reenviar es lo que mas pesa en la red. */
    private static final int REENVIOS_POR_TICK = 24;

    private Pintor() {
    }

    static Biome bioma(String id) {
        if (id == null || id.isBlank()) return null;
        String clave = id.contains(":") ? id.toLowerCase(java.util.Locale.ROOT)
                : BiomasPlugin.NAMESPACE + ":" + id.toLowerCase(java.util.Locale.ROOT);
        NamespacedKey key = NamespacedKey.fromString(clave);
        if (key == null) return null;
        try {
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).get(key);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Arranca la pintura. alTerminar corre en el hilo principal cuando ya se han
     * reenviado todos los trozos a quien los tuviera cargados.
     */
    static void pintar(BiomasPlugin modulo, World w, Zona z, Biome b, Runnable alTerminar) {
        Module dueno = modulo;
        int cx0 = Math.floorDiv(z.minX(), 4), cx1 = Math.floorDiv(z.maxX(), 4);
        int cy0 = Math.floorDiv(Math.max(z.minY(), w.getMinHeight()), 4);
        int cy1 = Math.floorDiv(Math.min(z.maxY(), w.getMaxHeight() - 1), 4);
        int cz0 = Math.floorDiv(z.minZ(), 4), cz1 = Math.floorDiv(z.maxZ(), 4);

        new BukkitRunnable() {
            int cx = cx0;
            int cz = cz0;
            final Set<Long> billetes = new HashSet<>();
            List<Long> reenviar;
            int indice;

            @Override
            public void run() {
                if (reenviar == null) {
                    int presupuesto = CELDAS_POR_TICK;
                    while (presupuesto > 0 && cx <= cx1) {
                        int bx = cx * 4, bz = cz * 4;
                        int chx = bx >> 4, chz = bz >> 4;
                        long clave = ((long) chx << 32) | (chz & 0xffffffffL);
                        if (billetes.add(clave)) w.addPluginChunkTicket(chx, chz, Module.dueno(dueno));
                        for (int cy = cy0; cy <= cy1; cy++) {
                            w.setBiome(bx, cy * 4, bz, b);
                        }
                        presupuesto -= (cy1 - cy0 + 1);
                        if (++cz > cz1) {
                            cz = cz0;
                            cx++;
                        }
                    }
                    if (cx <= cx1) return;
                    reenviar = new ArrayList<>(billetes);
                    return;
                }
                int hechos = 0;
                while (indice < reenviar.size() && hechos < REENVIOS_POR_TICK) {
                    long clave = reenviar.get(indice++);
                    int chx = (int) (clave >> 32), chz = (int) clave;
                    if (alguienCerca(w, chx, chz)) {
                        try {
                            w.refreshChunk(chx, chz);
                        } catch (Throwable ignored) {
                        }
                        hechos++;
                    }
                    w.removePluginChunkTicket(chx, chz, Module.dueno(dueno));
                }
                if (indice < reenviar.size()) return;
                cancel();
                if (alTerminar != null) alTerminar.run();
            }
        }.runTaskTimer(Module.dueno(dueno), 1L, 1L);
    }

    private static boolean alguienCerca(World w, int chx, int chz) {
        int vista = w.getViewDistance() + 1;
        for (Player p : w.getPlayers()) {
            int px = p.getLocation().getBlockX() >> 4, pz = p.getLocation().getBlockZ() >> 4;
            if (Math.abs(px - chx) <= vista && Math.abs(pz - chz) <= vista) return true;
        }
        return false;
    }
}
