package net.ederus.edm.superbeacon;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Todo lo que un Super Beacon lleva consigo, este donde este: en un objeto (su PDC), en
 * el mundo (dentro de una {@link Baliza}) o esperando a su dueño (un {@link Pendiente}).
 *
 * Es lo que hace que "cada vez que se pique lo mantiene": al recogerlo se escribe esta
 * ficha en el objeto, al colocarlo se lee, y nada se pierde por el camino. El id es la
 * identidad de la baliza y no cambia nunca; con el se impide que dos copias del mismo
 * objeto (un clic central en creativo) den dos balizas activas.
 *
 * @param id          el de la baliza, para siempre
 * @param tipo        la clave del tipo en superbeacon/config.yml
 * @param dueno       null si todavia no tiene (transferible y sin colocar nunca)
 * @param duenoNombre el nombre con el que se le conoce; puede ir sin UUID si se entrego
 *                    por nombre a alguien que aun no habia entrado al servidor
 * @param clan        el clan que recibe sus efectos, si se fijo al entregarlo o colocarlo
 * @param vence       instante de caducidad en epoch ms; 0, no caduca
 * @param elegidos    los efectos elegidos en su menu (claves del tipo)
 */
record Ficha(UUID id, String tipo, UUID dueno, String duenoNombre, String clan, long vence,
             List<String> elegidos) {

    Ficha {
        elegidos = elegidos == null ? List.of() : List.copyOf(elegidos);
        duenoNombre = vacio(duenoNombre);
        clan = vacio(clan);
    }

    private static String vacio(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    boolean caduca() {
        return vence > 0;
    }

    boolean vencida(long ahora) {
        return vence > 0 && ahora >= vence;
    }

    /** Tiene dueño fijado, sea por UUID o solo por nombre. */
    boolean ligada() {
        return dueno != null || duenoNombre != null;
    }

    Ficha conDueno(UUID uuid, String nombre) {
        return new Ficha(id, tipo, uuid, nombre, clan, vence, elegidos);
    }

    Ficha conElegidos(Collection<String> nuevos) {
        return new Ficha(id, tipo, dueno, duenoNombre, clan, vence, List.copyOf(nuevos));
    }

    /** El dueño como se le nombra en un texto: su nombre, o el principio de su UUID. */
    String duenoTexto() {
        if (duenoNombre != null) return duenoNombre;
        return dueno == null ? "-" : dueno.toString().substring(0, 8);
    }
}
