package net.ederus.edm.goditems;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Ejecuta una lista de pasos.
 *
 * La lista NO se recorre con un for: hay que poder pararla a mitad y seguir
 * varios ticks despues (ESPERAR) sin bloquear el hilo del servidor. Se lleva
 * una pila de iteradores; cuando aparece un ESPERAR se guarda la pila tal cual
 * y se programa la continuacion. Al volver, se sigue exactamente por donde iba.
 *
 * REPETIR no se implementa con un bucle sino aplanando: se empuja la sublista
 * repetida N veces con un ESPERAR intercalado. Asi la repeticion con retardo
 * sale gratis y no hace falta un segundo mecanismo de continuacion.
 */
public final class Motor {

    /**
     * Suelo de seguridad. Un item con miles de acciones en un mismo tick es un
     * error de quien lo escribio, y aqui se corta antes de que se lleve el TPS
     * por delante.
     */
    private static final int TOPE_POR_TICK = 4000;

    private final GodItemsPlugin modulo;

    public Motor(GodItemsPlugin modulo) {
        this.modulo = modulo;
    }

    public void lanzar(Ctx ctx, List<Paso> pasos) {
        if (pasos == null || pasos.isEmpty()) return;
        new Ejecucion(ctx, pasos).avanzar();
    }

    private final class Ejecucion {

        private final Ctx ctx;
        private final Deque<Iterator<Paso>> pila = new ArrayDeque<>();

        Ejecucion(Ctx ctx, List<Paso> pasos) {
            this.ctx = ctx;
            this.pila.push(pasos.iterator());
        }

        void avanzar() {
            int hechos = 0;
            while (!this.pila.isEmpty()) {
                if (this.ctx.cancelado()) return;
                if (++hechos > TOPE_POR_TICK) {
                    modulo.getLogger().warning("[GodItems] " + this.ctx.definicion().id()
                            + " paso de " + TOPE_POR_TICK + " acciones en un solo tick;"
                            + " se corta ahi. Reparte el trabajo con ESPERAR.");
                    return;
                }
                Iterator<Paso> it = this.pila.peek();
                if (!it.hasNext()) {
                    this.pila.pop();
                    continue;
                }
                Paso paso = it.next();
                switch (paso) {
                    case Paso.Simple s -> correr(s);
                    case Paso.Espera e -> {
                        if (e.ticks() <= 0) continue;
                        this.ctx.marcarDiferido();
                        modulo.core().getServer().getScheduler().runTaskLater(
                                modulo.core(), this::avanzar, e.ticks());
                        return;
                    }
                    case Paso.Si si -> {
                        List<Paso> rama = Condicion.todas(this.ctx, si.condiciones())
                                ? si.entonces() : si.siNo();
                        if (rama != null && !rama.isEmpty()) this.pila.push(rama.iterator());
                    }
                    case Paso.Repetir r -> {
                        if (r.veces() <= 0 || r.pasos().isEmpty()) continue;
                        this.pila.push(aplanar(r).iterator());
                    }
                }
            }
        }

        private void correr(Paso.Simple s) {
            try {
                s.accion().correr(this.ctx, s.args());
            } catch (Throwable t) {
                modulo.getLogger().warning("[GodItems] " + this.ctx.definicion().id()
                        + " fallo en la accion: " + s.linea() + " -> " + t);
                if (modulo.detalle()) t.printStackTrace();
            }
        }

        private List<Paso> aplanar(Paso.Repetir r) {
            List<Paso> out = new ArrayList<>(r.veces() * (r.pasos().size() + 1));
            for (int i = 0; i < r.veces(); i++) {
                if (i > 0 && r.cada() > 0) out.add(new Paso.Espera(r.cada()));
                out.addAll(r.pasos());
            }
            return out;
        }
    }
}
