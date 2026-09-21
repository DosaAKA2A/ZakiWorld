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
    /**
     * Un golpe critico con el item en la mano: el de las stats de MMOItems
     * (critical-strike-chance, lo calcula MythicLib) o el de caer saltando.
     * Salta una sola vez por golpe aunque coincidan los dos.
     */
    CRITICO,
    RECIBIR_GOLPE,
    /** Que te asesten a TI un critico. El mismo criterio que CRITICO, del otro lado. */
    RECIBIR_CRITICO,
    /*
     * Esquivar, bloquear y parar. Los tres los decide MythicLib con las stats de
     * MMOItems (dodge-rating, block-rating, parry-rating). Sin MythicLib delante
     * no salta ninguno: no hay equivalente en vanilla que signifique lo mismo.
     */
    ESQUIVAR,
    BLOQUEAR,
    /** El parry de MythicLib. OJO: nada que ver con la ACCION PARAR, que corta la lista. */
    PARAR,
    MATAR,
    MATAR_JUGADOR,
    /** Matar algo que cuente como jefe: un MythicMob, o vida maxima >= `vida-minima`. */
    MATAR_JEFE,
    /** Al llegar a N muertes seguidas sin morir. El numero, en `racha:` del bloque. */
    RACHA,
    /** Antes de morir el portador. Puede cancelar la muerte (CANCELAR_EVENTO). */
    ANTES_DE_MORIR,
    MORIR,
    /** Daño de caida llevandolo. Sin golpeado; el golpe llega en `%dano%`. */
    CAER,

    /* --- llevar el item encima --- */
    EQUIPAR,
    DESEQUIPAR,
    /** Pasa a la mano principal. */
    EMPUNAR,
    /** Deja de estar en la mano principal. */
    GUARDAR,
    /** Conectar con el item en el inventario. */
    ENTRAR,
    /** Desconectar con el item en el inventario. */
    SALIR,

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
    /**
     * Cada `ticks:` mientras el item este en el inventario.
     *
     * Se parece a EN_INVENTARIO pero NO es lo mismo: los tres de arriba se
     * revisan al compas de `ticks-de-revision` (10 por defecto), asi que un
     * `cada: 3` acaba yendo cada 10. TEMPORIZADOR lleva su propio reloj, a un
     * tick de resolucion, y respeta el intervalo que le pongas.
     */
    TEMPORIZADOR,

    /* --- inventario --- */
    CONSUMIR,
    TIRAR,
    RECOGER,
    /** Terminar de comerse CUALQUIER cosa mientras lo llevas. Distinto de CONSUMIR. */
    COMER,
    PESCAR,

    /* --- gestos del cuerpo --- */
    AGACHARSE,
    LEVANTARSE,
    EMPEZAR_CORRER,
    PARAR_CORRER,
    SALTAR,
    EMPEZAR_PLANEAR,
    PARAR_PLANEAR,
    ENTRAR_AGUA,
    SALIR_AGUA,
    DORMIR,
    DESPERTAR,

    /* --- mundo --- */
    ROMPER_BLOQUE,
    COLOCAR_BLOQUE,
    /** Clic derecho sobre un bloque. Se puede acotar con `bloque:` en el activador. */
    INTERACTUAR_BLOQUE,
    /** Clic derecho sobre una entidad. */
    INTERACTUAR_ENTIDAD,
    PROYECTIL_IMPACTA,
    /** Solo si el proyectil golpea algo vivo. Salta ADEMAS de PROYECTIL_IMPACTA. */
    PROYECTIL_IMPACTA_ENTIDAD,
    /** Solo si el proyectil se clava en un bloque. Salta ADEMAS de PROYECTIL_IMPACTA. */
    PROYECTIL_IMPACTA_BLOQUE,
    /** Soltar una flecha, un tridente, una bola de nieve, un huevo o una perla. */
    DISPARAR,
    CAMBIAR_MUNDO,
    ENTRAR_REGION,
    SALIR_REGION,
    REAPARECER,

    /* --- otros plugins --- */
    /** Subida de nivel de AuraSkills. Se puede acotar con `habilidad:`. */
    SUBIR_NIVEL,

    /** Solo por /gi trigger: lo llaman ConditionalEvents, DeluxeMenus o misiones. */
    DISPARADOR;

    /** Los tres que corren solos por tiempo, no por un gesto del jugador. */
    public boolean esTick() {
        return this == EN_MANO || this == PUESTO || this == EN_INVENTARIO
                || this == TEMPORIZADOR;
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
