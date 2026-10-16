package net.ederus.edm.anomaly.fx;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Como se coloca y se mueve una instancia de {@link ModeloBde}.
 *
 * @param base             punto del suelo del modelo (su centro horizontal). Su yaw y su pitch se ignoran.
 * @param yaw              giro fijo del modelo entero en grados, con el mismo sentido que el yaw de Minecraft.
 * @param escala           multiplica el modelo normalizado (radio 1): 3 da un modelo de 3 bloques de radio.
 * @param recolor          sustituye materiales de bloque al instanciar.
 * @param brillo           brillo forzado para todas las piezas; null usa el de cada pieza o 15/15.
 * @param registrar        recibe cada entidad en cuanto nace (el dueño la mete en su lista de limpieza).
 * @param giroPorTick      giro extra en Y en radianes por tick; negativo gira al reves.
 * @param ticksCrecimiento 0 aparece entero; mas de 0, cada pieza crece desde 0 con retardo segun su angulo.
 * @param animacion        reproduce en bucle las claves del editor.
 * @param alturaExtra      bloques que se sube el modelo sobre la base (no se escala).
 */
public record Opciones(Location base, float yaw, float escala, Map<Material, Material> recolor,
                       Display.Brightness brillo, Consumer<Entity> registrar, float giroPorTick,
                       int ticksCrecimiento, boolean animacion, float alturaExtra) {

    public Opciones {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(base.getWorld(), "mundo de la base");
        base = base.clone();
        if (!Float.isFinite(yaw)) throw new IllegalArgumentException("yaw no finito");
        if (!Float.isFinite(escala) || escala <= 0 || escala > 256) throw new IllegalArgumentException("escala fuera de rango");
        if (!Float.isFinite(giroPorTick)) throw new IllegalArgumentException("giro no finito");
        if (ticksCrecimiento < 0) throw new IllegalArgumentException("ticksCrecimiento negativo");
        if (!Float.isFinite(alturaExtra)) throw new IllegalArgumentException("alturaExtra no finita");
        EnumMap<Material, Material> copia = new EnumMap<>(Material.class);
        if (recolor != null) {
            for (Map.Entry<Material, Material> e : recolor.entrySet()) {
                copia.put(Objects.requireNonNull(e.getKey()), ModeloBde.bloque(e.getValue().name()));
            }
        }
        recolor = java.util.Collections.unmodifiableMap(copia);
        if (registrar == null) registrar = entidad -> { };
    }

    @Override
    public Location base() {
        return base.clone();
    }

    /** La misma configuracion en otra base. */
    public Opciones conBase(Location nueva) {
        return new Opciones(nueva, yaw, escala, recolor, brillo, registrar, giroPorTick,
                ticksCrecimiento, animacion, alturaExtra);
    }

    public static Builder en(Location base) {
        return new Builder(base);
    }

    public static final class Builder {
        private final Location base;
        private float yaw;
        private float escala = 1;
        private final Map<Material, Material> recolor = new EnumMap<>(Material.class);
        private Display.Brightness brillo;
        private Consumer<Entity> registrar;
        private float giroPorTick;
        private int ticksCrecimiento;
        private boolean animacion;
        private float alturaExtra;

        private Builder(Location base) {
            this.base = base;
        }

        public Builder yaw(float grados) { this.yaw = grados; return this; }
        public Builder escala(float escala) { this.escala = escala; return this; }
        public Builder recolor(Material desde, Material hacia) { this.recolor.put(desde, hacia); return this; }
        public Builder recolor(Map<Material, Material> mapa) { this.recolor.putAll(mapa); return this; }
        public Builder brillo(int bloque, int cielo) { this.brillo = new Display.Brightness(bloque, cielo); return this; }
        public Builder registrar(Consumer<Entity> registrar) { this.registrar = registrar; return this; }
        public Builder giroPorTick(float radianes) { this.giroPorTick = radianes; return this; }
        public Builder ticksCrecimiento(int ticks) { this.ticksCrecimiento = ticks; return this; }
        public Builder animacion(boolean animar) { this.animacion = animar; return this; }
        public Builder alturaExtra(float bloques) { this.alturaExtra = bloques; return this; }

        public Opciones build() {
            return new Opciones(base, yaw, escala, recolor, brillo, registrar, giroPorTick,
                    ticksCrecimiento, animacion, alturaExtra);
        }
    }
}
