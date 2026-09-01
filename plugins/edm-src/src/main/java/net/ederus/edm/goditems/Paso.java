package net.ederus.edm.goditems;

import java.util.List;

/**
 * Un renglon de la lista de acciones.
 *
 * Casi siempre es una accion suelta, pero tres de ellos mandan sobre el resto y
 * por eso NO son acciones normales: ESPERAR corta el hilo, SI elige rama y
 * REPETIR multiplica. Escribirlos en el YAML como mapas anidados
 * (`si:`, `repetir:`) y no como texto plano es lo que evita el invento tipico de
 * estos plugins, que es una gramatica a medias con llaves dentro de una cadena.
 */
public sealed interface Paso permits Paso.Simple, Paso.Espera, Paso.Si, Paso.Repetir {

    /** Una accion del catalogo, ya troceada. */
    record Simple(Args args, Accion accion, String linea) implements Paso { }

    /** ESPERAR: lo unico que parte la ejecucion en dos ticks distintos. */
    record Espera(int ticks) implements Paso { }

    record Si(List<Condicion.Prueba> condiciones, List<Paso> entonces, List<Paso> siNo) implements Paso { }

    record Repetir(int veces, int cada, List<Paso> pasos) implements Paso { }
}
