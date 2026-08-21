package net.ederus.edm.troll;

import org.bukkit.Material;

/**
 * Una broma del catalogo.
 *
 * Es un dato, no una clase por broma: 50 ficheros de 8 lineas cada uno no se
 * mantienen. La gracia va en la lambda y todo lo demas (permiso, icono, si hace
 * falta confirmar) sale de aqui.
 *
 * NADA de esto es codigo de TrollBoss. La lista de bromas es la suya, que no es
 * de nadie; la implementacion es nuestra y por eso EDM no hereda su GPL.
 */
public record Troll(String id, String nombre, String descripcion,
                    Material icono, Familia familia, boolean destructivo,
                    int segundos, Accion accion) {

    /** Para agrupar el menu y para que se entienda de un vistazo que hace cada una. */
    public enum Familia {
        SUSTO("Sustos"),
        MOVIMIENTO("Movimiento"),
        INVENTARIO("Inventario"),
        FALSO("Mentiras"),
        MUNDO("El mundo");

        private final String nombre;
        Familia(String nombre) { this.nombre = nombre; }
        public String nombre() { return nombre; }
    }

    @FunctionalInterface
    public interface Accion {
        void aplicar(Contexto c);
    }

    /** ederus.troll.<id>, para poder dar bromas sueltas a un moderador. */
    public String permiso() { return "ederus.troll." + id; }

    /** Las que duran un rato se pueden deshacer antes de tiempo. */
    public boolean temporal() { return segundos > 0; }

    public static Troll de(String id, String nombre, String descripcion, Material icono,
                           Familia familia, Accion accion) {
        return new Troll(id, nombre, descripcion, icono, familia, false, 0, accion);
    }

    public static Troll temporal(String id, String nombre, String descripcion, Material icono,
                                 Familia familia, int segundos, Accion accion) {
        return new Troll(id, nombre, descripcion, icono, familia, false, segundos, accion);
    }

    /**
     * Una broma que borra progreso: mata, tira el inventario o cambia el mundo.
     *
     * En un survival OP el equipo de MMOItems ES el progreso, y un clic de mas
     * de un admin no se deshace con un backup sin tirar tambien tres horas de
     * todos los demas. Estas piden confirmacion y permiso aparte.
     */
    public static Troll destructiva(String id, String nombre, String descripcion, Material icono,
                                    Familia familia, Accion accion) {
        return new Troll(id, nombre, descripcion, icono, familia, true, 0, accion);
    }
}
