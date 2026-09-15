package net.ederus.edm.biomas;

import org.bukkit.Location;

/**
 * Una caja del mundo cuyo bioma controla el modulo: la arena de las anomalias, un
 * evento, lo que sea. Guarda el bioma con el que se creo para poder devolverlo.
 *
 * @param base   el bioma que tenia el centro al crear la zona; es lo que pinta "limpiar"
 * @param actual el clima pintado ahora mismo, o null si esta en su bioma base
 */
public record Zona(String nombre, String mundo, int minX, int minY, int minZ,
                   int maxX, int maxY, int maxZ, String base, String actual) {

    public Zona {
        int ax = Math.min(minX, maxX), bx = Math.max(minX, maxX);
        int ay = Math.min(minY, maxY), by = Math.max(minY, maxY);
        int az = Math.min(minZ, maxZ), bz = Math.max(minZ, maxZ);
        minX = ax; maxX = bx; minY = ay; maxY = by; minZ = az; maxZ = bz;
    }

    public Zona conActual(String clima) {
        return new Zona(nombre, mundo, minX, minY, minZ, maxX, maxY, maxZ, base, clima);
    }

    public boolean contiene(Location l) {
        if (l == null || l.getWorld() == null || !l.getWorld().getName().equals(mundo)) return false;
        return l.getX() >= minX && l.getX() < maxX + 1
                && l.getY() >= minY && l.getY() < maxY + 1
                && l.getZ() >= minZ && l.getZ() < maxZ + 1;
    }

    /** Celdas de bioma (4x4x4) que ocupa. Es lo que cuesta pintarla. */
    public long celdas() {
        long x = (Math.floorDiv(maxX, 4) - Math.floorDiv(minX, 4) + 1);
        long y = (Math.floorDiv(maxY, 4) - Math.floorDiv(minY, 4) + 1);
        long z = (Math.floorDiv(maxZ, 4) - Math.floorDiv(minZ, 4) + 1);
        return x * y * z;
    }

    public String medidas() {
        return (maxX - minX + 1) + "x" + (maxY - minY + 1) + "x" + (maxZ - minZ + 1);
    }
}
