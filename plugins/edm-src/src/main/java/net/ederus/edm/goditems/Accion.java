package net.ederus.edm.goditems;

/** Una accion del catalogo de GodItems. */
@FunctionalInterface
public interface Accion {

    void correr(Ctx ctx, Args a);

    /**
     * true si todo lo que va detras del `@objetivo` es texto y no hay que
     * buscarle `clave:valor` (MENSAJE, COMANDO_CONSOLA...). Lo decide el
     * catalogo, no quien escribe el YAML.
     */
    default boolean textoLibre() {
        return false;
    }
}
