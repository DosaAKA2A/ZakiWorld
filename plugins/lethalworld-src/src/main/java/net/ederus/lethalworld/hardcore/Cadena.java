package net.ederus.lethalworld.hardcore;

import net.ederus.edm.comun.Fx;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * La cadena del Tiron, de verdad: bloques de cadena de hierro (BlockDisplay) puestos en fila
 * de la mano de la PARCA a la presa y girados en su direccion, a brillo maximo para que se
 * vea de noche. Se recoloca en cada llamada (interpolado, sin tirones) y se quita al acabar
 * el aviso. Es la de PeleaParca.pintarCadena sacada a su clase para la anomalia de EDM
 * (1.2.0); la reserva sigue con la suya para no tocarla mas de lo justo.
 */
final class Cadena {

    /** Largo de cada eslabon. */
    private static final double ESLABON = 1.0;

    private final List<BlockDisplay> eslabones = new ArrayList<>();

    void pintar(Location desde, Location hasta) {
        World w = desde.getWorld();
        Vector d = hasta.toVector().subtract(desde.toVector());
        double largo = d.length();
        if (w == null || largo < 0.3) {
            quitar();
            return;
        }
        int n = (int) Math.max(1, Math.min(24, Math.ceil(largo / ESLABON)));
        double tramo = largo / n;
        Vector u = d.multiply(1 / largo);
        Quaternionf giro = new Quaternionf().rotationTo(new Vector3f(0, 1, 0),
                new Vector3f((float) u.getX(), (float) u.getY(), (float) u.getZ()));
        float ancho = 0.9f;
        // El modelo de la cadena va de 0 a 1 en cada eje: se centra en X y Z antes de girar.
        Vector3f centrado = giro.transform(new Vector3f(-ancho / 2, 0, -ancho / 2));
        Transformation tr = new Transformation(centrado, giro, new Vector3f(ancho, (float) tramo, ancho), new Quaternionf());
        while (eslabones.size() > n) Fx.safeRemove(eslabones.remove(eslabones.size() - 1));
        for (int i = 0; i < n; i++) {
            Location en = desde.clone().add(u.clone().multiply(i * tramo));
            // Sin giro propio: la rotacion va entera en la transformacion.
            en.setYaw(0);
            en.setPitch(0);
            BlockDisplay b = i < eslabones.size() ? eslabones.get(i) : null;
            if (b != null && b.isValid()) {
                b.teleport(en);
                b.setInterpolationDelay(0);
                b.setTransformation(tr);
                continue;
            }
            BlockDisplay nuevo = w.spawn(en, BlockDisplay.class, e -> {
                e.setBlock(Material.IRON_CHAIN.createBlockData());
                e.setPersistent(false);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setTeleportDuration(2);
                e.setInterpolationDuration(2);
                e.setTransformation(tr);
            });
            if (i < eslabones.size()) eslabones.set(i, nuevo);
            else eslabones.add(nuevo);
        }
    }

    void quitar() {
        for (BlockDisplay b : eslabones) Fx.safeRemove(b);
        eslabones.clear();
    }
}
