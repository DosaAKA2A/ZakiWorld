package net.ederus.edm.superbeacon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * El indice espacial de las balizas: por mundo y por chunk, que balizas pueden alcanzar
 * algo de ese chunk.
 *
 * Cada baliza se apunta en todos los chunks que toca su circulo (los de las esquinas que
 * el circulo no roza, no). Asi preguntar "que balizas alcanzan este punto" es mirar UNA
 * lista corta, la de su chunk, y no recorrer todas las balizas del servidor: el ciclo de
 * efectos lo hace por cada jugador cada 2 s y sin-mobs en cada aparicion natural.
 *
 * Que este en la lista no quiere decir que alcance: quien pregunta hace la cuenta exacta
 * de distancia (Baliza.dentro), que es barata. Solo hilo principal.
 */
final class Alcance {

    private final Map<String, Map<Long, List<Baliza>>> celdas = new HashMap<>();

    void limpiar() {
        celdas.clear();
    }

    boolean vacio() {
        return celdas.isEmpty();
    }

    void anadir(Baliza b, int radio) {
        Map<Long, List<Baliza>> mundo = celdas.computeIfAbsent(b.mundo, k -> new HashMap<>());
        double cx = b.x + 0.5, cz = b.z + 0.5, r2 = (double) radio * radio;
        int desdeX = (b.x - radio) >> 4, hastaX = (b.x + radio) >> 4;
        int desdeZ = (b.z - radio) >> 4, hastaZ = (b.z + radio) >> 4;
        for (int x = desdeX; x <= hastaX; x++) {
            for (int z = desdeZ; z <= hastaZ; z++) {
                // El punto del chunk mas cercano al centro: si ni ese entra, el chunk sobra.
                double px = Math.max(x << 4, Math.min(cx, (x << 4) + 16));
                double pz = Math.max(z << 4, Math.min(cz, (z << 4) + 16));
                double dx = px - cx, dz = pz - cz;
                if (dx * dx + dz * dz > r2) continue;
                mundo.computeIfAbsent(clave(x, z), k -> new ArrayList<>(2)).add(b);
            }
        }
    }

    /** Las balizas apuntadas en el chunk de ese bloque; vacia si ninguna. No se modifica. */
    List<Baliza> en(String mundo, int bloqueX, int bloqueZ) {
        Map<Long, List<Baliza>> m = celdas.get(mundo);
        if (m == null) return List.of();
        List<Baliza> l = m.get(clave(bloqueX >> 4, bloqueZ >> 4));
        return l == null ? List.of() : l;
    }

    /** La misma que Chunk.getChunkKey: x en los 32 bits bajos, z en los altos. */
    static long clave(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }
}
