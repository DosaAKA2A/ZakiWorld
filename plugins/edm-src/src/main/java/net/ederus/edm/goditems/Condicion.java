package net.ederus.edm.goditems;

import java.util.List;

/** Una condicion del catalogo de GodItems. */
@FunctionalInterface
public interface Condicion {

    boolean vale(Ctx ctx, Args a);

    /**
     * Una condicion ya escrita en un YAML, con su negacion y su mensaje.
     *
     *   - 'AGACHADO'
     *   - '!ARDIENDO | &cNo puedes usarlo ardiendo'
     *   - 'VIDA menor 50% | &cSolo con la vida por debajo de la mitad'
     */
    record Prueba(Condicion condicion, Args args, boolean negada, String mensaje, String linea) {

        public boolean pasa(Ctx ctx) {
            boolean r;
            try {
                r = this.condicion.vale(ctx, this.args);
            } catch (Throwable t) {
                ctx.modulo().getLogger().warning("Condicion '" + this.linea + "' de "
                        + ctx.definicion().id() + " fallo: " + t);
                return false;
            }
            return this.negada != r;
        }
    }

    /**
     * Comprueba una lista entera. Si alguna falla, manda su mensaje (si lo
     * tiene) y devuelve false. Solo se manda el mensaje de LA PRIMERA que
     * falla: si un item pide cinco cosas, cinco lineas rojas de golpe no le
     * dicen a nadie que le pasa.
     */
    static boolean todas(Ctx ctx, List<Prueba> pruebas) {
        for (Prueba p : pruebas) {
            if (p.pasa(ctx)) continue;
            if (p.mensaje() != null && !p.mensaje().isBlank() && ctx.jugador() != null) {
                ctx.jugador().sendMessage(net.ederus.edm.comun.Estilo.legado(Textos.aplicar(ctx, p.mensaje())));
            }
            return false;
        }
        return true;
    }
}
