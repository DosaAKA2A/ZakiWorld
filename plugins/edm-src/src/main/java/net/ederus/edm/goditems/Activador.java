package net.ederus.edm.goditems;

/**
 * Lo que hace saltar el comportamiento de un GodItem.
 *
 * El "agachado" NO esta aqui a proposito: es una CONDICION. Si fuera activador
 * habria que duplicar cada gesto (clic, clic agachado, clic corriendo...) y el
 * catalogo se iria al doble sin ganar nada.
 */
public enum Activador {

    /* --- gestos --- */
    CLIC_DERECHO,
    CLIC_IZQUIERDO,
    /** Cualquiera de los dos. Salta ADEMAS del especifico, si ambos existen. */
    CLIC,

    /* --- combate --- */
    GOLPEAR,
    GOLPEAR_JUGADOR,
    RECIBIR_GOLPE,
    MATAR,
    MATAR_JUGADOR,
    /** Antes de morir el portador. Puede cancelar la muerte (CANCELAR_EVENTO). */
    ANTES_DE_MORIR,
    MORIR,

    /* --- llevar el item encima --- */
    EQUIPAR,
    DESEQUIPAR,
    /** Pasa a la mano principal. */
    EMPUNAR,
    /** Deja de estar en la mano principal. */
    GUARDAR,

    /*
     * --- conjuntos de MMOItems ---
     * El set NO lo define GodItems: se lee de la etiqueta MMOITEMS_ITEM_SET que
     * MMOItems ya pone en cada pieza. Cuantas piezas hacen falta se puede fijar
     * con `piezas:` en el activador; por omision, todas las que el set tenga
     * declaradas en MMOItems.
     */
    SET_COMPLETO,
    SET_ROTO,

    /* --- ticks --- */
    /** Cada X ticks mientras este en la mano principal. */
    EN_MANO,
    /** Cada X ticks mientras este puesto como armadura. */
    PUESTO,
    /** Cada X ticks mientras este en cualquier hueco del inventario. */
    EN_INVENTARIO,

    /* --- inventario --- */
    CONSUMIR,
    TIRAR,
    RECOGER,

    /* --- mundo --- */
    ROMPER_BLOQUE,
    COLOCAR_BLOQUE,
    PROYECTIL_IMPACTA,
    REAPARECER,

    /** Solo por /gi trigger: lo llaman ConditionalEvents, DeluxeMenus o misiones. */
    DISPARADOR;

    /** Los tres que corren solos por tiempo, no por un gesto del jugador. */
    public boolean esTick() {
        return this == EN_MANO || this == PUESTO || this == EN_INVENTARIO;
    }

    /** Los que dependen de llevar puesto un conjunto entero de MMOItems. */
    public boolean esDeConjunto() {
        return this == SET_COMPLETO || this == SET_ROTO;
    }

    public static Activador porNombre(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
