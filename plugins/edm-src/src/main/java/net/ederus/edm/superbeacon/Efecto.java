package net.ederus.edm.superbeacon;

import java.util.Map;

import org.bukkit.Material;

/**
 * Un efecto tal y como lo escribe un tipo en el config: "prisa", con su icono, su nombre
 * y lo que toque a su clase (nivel, atributo, multiplicador...).
 *
 * Cada {@link ClaseEfecto} tiene su subclase con sus datos. Aqui solo vive lo comun y las
 * dos ideas que hacen que el solapamiento sea justo:
 *   - grupo(): que efectos son "el mismo" aunque vengan de balizas distintas (Prisa II de
 *     una y Prisa III de otra son el grupo pocion:haste);
 *   - fuerza(): cual gana dentro del grupo. Gana el MAYOR, nunca se suman: dos balizas de
 *     Prisa II no dan Prisa IV, y cinco balizas de +2 corazones no dan +10.
 */
abstract class Efecto {

    private final String clave;
    private final String nombre;
    private final Material icono;

    protected Efecto(String clave, String nombre, Material icono) {
        this.clave = clave;
        this.nombre = nombre;
        this.icono = icono;
    }

    /** La clave dentro del tipo ("prisa"); es lo que se guarda como elegido. */
    final String clave() {
        return clave;
    }

    /** El nombre tal cual el config, con sus colores. */
    final String nombre() {
        return nombre;
    }

    final String nombrePlano() {
        return LectorTipos.plano(nombre);
    }

    final Material icono() {
        return icono;
    }

    abstract ClaseEfecto clase();

    /** Con que se compara al fusionar: del mismo grupo solo cuenta uno, el de mas fuerza. */
    abstract String grupo();

    /** Cuanto pesa dentro de su grupo: nivel, valor o multiplicador. */
    abstract double fuerza();

    /** Por que no se puede usar ahora mismo ("boosts apagados"), o null si se puede. */
    String falta() {
        return clase().falta(this);
    }

    /** Lo que ganas y donde, en una frase (ver ClaseEfecto.que). */
    String que() {
        return clase() == null ? "" : clase().que(this);
    }

    /**
     * Mete e en mejores si gana a lo que ya hubiera de su grupo. En un empate se queda el
     * que llego primero: da igual cual, pesan lo mismo, y asi no se repinta nada.
     */
    static void fusionar(Map<String, Efecto> mejores, Efecto e) {
        Efecto ya = mejores.get(e.grupo());
        if (ya == null || e.fuerza() > ya.fuerza()) mejores.put(e.grupo(), e);
    }
}
