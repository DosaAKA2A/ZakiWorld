package net.ederus.edm.superbeacon;

import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Un Super Beacon que espera a su destinatario: le toco por /superbeacon give estando
 * desconectado o sin hueco, se recogio y aun no se le habia dado, o se devolvio porque su
 * bloque desaparecio o lo quito el staff.
 *
 * Es la tercera casa de una baliza (objeto, bloque o pendiente) y existe para que una
 * baliza comprada no se pierda NUNCA: se escribe en disco ANTES de dar el objeto, y si el
 * servidor cae entre medias, al volver sigue esperando.
 *
 * El destinatario va por UUID si se conoce; si no (se entrego por nombre a quien aun no
 * habia entrado), por nombre, sin distinguir mayusculas.
 */
final class Pendiente {

    final Ficha ficha;
    /** El material del objeto, por si su tipo ya no existe en el config. */
    final Material material;
    final UUID para;
    final String paraNombre;
    /** give, recogida, devolucion, retirada: para la bitacora y para leer data.yml. */
    final String motivo;
    final long desde;

    Pendiente(Ficha ficha, Material material, UUID para, String paraNombre, String motivo, long desde) {
        this.ficha = ficha;
        this.material = material;
        this.para = para;
        this.paraNombre = paraNombre == null || paraNombre.isBlank() ? null : paraNombre;
        this.motivo = motivo;
        this.desde = desde;
    }

    boolean esPara(Player p) {
        if (para != null) return para.equals(p.getUniqueId());
        return paraNombre != null && paraNombre.equalsIgnoreCase(p.getName());
    }

    String paraTexto() {
        if (paraNombre != null) return paraNombre;
        return para == null ? "-" : para.toString().substring(0, 8);
    }
}
