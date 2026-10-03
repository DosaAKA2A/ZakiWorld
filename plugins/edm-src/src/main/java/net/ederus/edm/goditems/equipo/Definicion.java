package net.ederus.edm.goditems.equipo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.potion.PotionEffectType;

import net.ederus.edm.goditems.Paso;

/**
 * Lo que dicen los YAML de `plugins/EDM/goditems/equipo/`, ya leido.
 *
 * Todo son records inmutables: la configuracion se cambia entera en cada
 * recarga (una referencia volatil) y nadie la toca por dentro, asi que se puede
 * leer desde cualquier sitio sin miedo a verla a medias.
 */
public final class Definicion {

    private Definicion() { }

    /** Como se escribe una cifra en el lore. */
    public enum Formato {
        /** Una fraccion que se ensena en tanto por ciento: 0.05 -> "5%". */
        PORCENTAJE,
        /** Puntos de porcentaje tal cual: 1.5 -> "1,5%". */
        PUNTOS,
        /** Un numero sin mas: 2 -> "2". */
        NUMERO
    }

    /**
     * Una clave de dominio (`calamity.cordura-drenaje`, `pesca.cajas`...).
     *
     * @param lore    la linea del lore, con {valor} (con signo) y {cifra} (sin signo); vacia = no sale
     * @param menos   true si un valor positivo QUITA algo (drenaje, percances): se ensena con "-"
     * @param tope    lo maximo que puede sumar un jugador (null = sin tope)
     * @param minimo  lo minimo (null = sin minimo)
     * @param grupo   el bloque del lore en el que sale (su cabecera va en `grupos:`)
     * @param nombre  como se llama en /gi equipo
     */
    public record Clave(String id, String lore, Formato formato, boolean menos, Double tope, Double minimo,
                        String grupo, String nombre) {

        /** El valor ya topado. */
        public double topar(double v) {
            double out = v;
            if (this.tope != null) out = Math.min(this.tope, out);
            if (this.minimo != null) out = Math.max(this.minimo, out);
            return out;
        }
    }

    /** Un efecto de pocion permanente mientras se lleva. nivel es el amplificador (0 = nivel I). */
    public record Pocion(PotionEffectType tipo, int nivel) { }

    /** Un modificador de atributo mientras se lleva. clave es como se escribio (max_health). */
    public record Atributo(Attribute atributo, String clave, double valor, AttributeModifier.Operation operacion) { }

    /** Lo que da algo que se lleva: claves de dominio, pociones y atributos. */
    public record Efectos(Map<String, Double> claves, List<Pocion> pociones, List<Atributo> atributos) {

        public static final Efectos NADA = new Efectos(Map.of(), List.of(), List.of());

        public boolean vacio() {
            return this.claves.isEmpty() && this.pociones.isEmpty() && this.atributos.isEmpty();
        }
    }

    /** Una pieza por su TIPO.ID de MMOItems. lore = si GodItems le escribe sus lineas. */
    public record Pieza(String id, Efectos efectos, boolean lore, String fichero) { }

    /**
     * Un escalon de un set: con al menos `necesita` piezas puestas, suma sus
     * efectos y, al ganarlo o perderlo, lanza sus acciones del motor de GodItems.
     * Los escalones se acumulan, como los bonos de MMOItems: con 4 piezas valen
     * el de 2 y el de 4.
     */
    public record Escalon(int necesita, Efectos efectos, List<Paso> alActivar, List<Paso> alPerder) { }

    /**
     * Un set. Sus piezas son la lista `piezas` (TIPO.ID) o, con `set-mmoitems`,
     * todas las que MMOItems marca con ese set; pueden ir las dos cosas a la vez.
     *
     * @param total    cuantas piezas tiene el set para el "(2/5)" del lore; 0 = las de la lista
     * @param cabecera la cabecera de sus bloques de lore (null = la del config.yml)
     */
    public record Conjunto(String id, String nombre, List<String> piezas, String setMmo, int total,
                           List<Escalon> escalones, boolean lore, String cabecera, String fichero) {

        public boolean tiene(String pieza, String setDeLaPieza) {
            if (pieza != null && this.piezas.contains(pieza)) return true;
            return this.setMmo != null && this.setMmo.equalsIgnoreCase(setDeLaPieza);
        }

        /** El escalon mas bajo: con eso "se lleva el set". */
        public int minimo() {
            int m = Integer.MAX_VALUE;
            for (Escalon e : this.escalones) m = Math.min(m, e.necesita());
            return m == Integer.MAX_VALUE ? Math.max(1, this.piezas.size()) : m;
        }
    }

    /**
     * Una carnada de pesca (EDM 1.75.0). No se lleva puesta: la vende y la pone
     * PremioPescao sobre una caña, que guarda en su PDC `pescao:carnada` (el id)
     * y `pescao:carnada_usos` (las pescas que le quedan). Mientras esa caña este
     * en una mano y le queden usos, sus efectos suman como los de una pieza mas.
     */
    public record Carnada(String id, String nombre, Efectos efectos, String fichero) { }

    /** Todo lo leido de la carpeta. */
    public record Config(Map<String, Clave> claves, Map<String, String> grupos, Map<String, Pieza> piezas,
                         List<Conjunto> sets, Map<String, Carnada> carnadas, List<String> avisos) {

        public static final Config VACIA = new Config(Map.of(), Map.of(), Map.of(), List.of(), Map.of(), List.of());

        public boolean vacia() {
            return this.piezas.isEmpty() && this.sets.isEmpty() && this.carnadas.isEmpty();
        }

        public Carnada carnada(String id) {
            return id == null ? null : this.carnadas.get(id.toLowerCase(java.util.Locale.ROOT));
        }

        /** Todos los TIPO.ID que nombra algo (piezas y listas de los sets). */
        public Set<String> conocidas() {
            Set<String> out = new LinkedHashSet<>(this.piezas.keySet());
            for (Conjunto s : this.sets) out.addAll(s.piezas());
            return out;
        }

        public boolean haySetsMmo() {
            for (Conjunto s : this.sets) if (s.setMmo() != null) return true;
            return false;
        }

        public Conjunto set(String id) {
            if (id == null) return null;
            for (Conjunto s : this.sets) if (s.id().equalsIgnoreCase(id)) return s;
            return null;
        }
    }
}
