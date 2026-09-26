// STUB de WP0: lo reescribe WP0 (0b)
package net.ederus.lethalworld.hardcore;

import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Registro de subcomandos de /lw hardcore y /calamity. Esqueleto de 0a: no registra nada.
 */
public final class Subcomandos {

    private static final Subcomandos LW = new Subcomandos();
    private static final Subcomandos CALAMITY = new Subcomandos();

    private Subcomandos() {
    }

    public static Subcomandos lw() {
        return LW;
    }

    public static Subcomandos calamity() {
        return CALAMITY;
    }

    public void registrar(String nombre, String ayuda, String permiso,
                          BiConsumer<CommandSender, String[]> accion, Function<String[], List<String>> tab) {
    }

    /** args[0] es el nombre del subcomando. True si lo atendio. */
    public boolean ejecutar(CommandSender quien, String[] args) {
        return false;
    }

    public List<String> nombres(CommandSender quien) {
        return List.of();
    }

    public List<String> tab(CommandSender quien, String[] args) {
        return List.of();
    }

    /** Pares [nombre, ayuda] para la ayuda del comando. */
    public List<String[]> ayuda(CommandSender quien) {
        return List.of();
    }

    public void vaciar() {
    }
}
