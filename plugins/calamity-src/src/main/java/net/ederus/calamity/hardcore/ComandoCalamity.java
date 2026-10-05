package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * Los mensajes de sistema de Calamity: el prefijo con la marca y el texto en los colores de la
 * Paleta.
 *
 * Hasta la 1.11 era tambien el /calamity de los jugadores. Desde la 1.12 /calamity es solo de staff
 * (net.ederus.calamity.ComandoRaiz) y lo que consultaban los jugadores lo abren los NPCs ("calamity
 * open <player> <id>", Npcs); la clase se queda por estos ayudantes, que usan todos los modulos.
 */
public final class ComandoCalamity {

    /**
     * Antes el rojo de muerte (#8B1A1A) del prefijo; no se leia sobre el chat. Queda como
     * alias del rojo claro de los avisos para no romper a quien lo use: lo nuevo va por Paleta.
     */
    @Deprecated
    public static final TextColor ROJO = Paleta.AVISO;

    private ComandoCalamity() {
    }

    /** "Calamity · " con el degradado de la marca (Paleta.prefijo). */
    public static Component prefijo() {
        return Paleta.prefijo();
    }

    /** Un mensaje de sistema de Calamity: prefijo y el texto en el color normal de la Paleta. */
    public static Component mensaje(String texto) {
        return Paleta.mensaje(texto);
    }

    /** Lo mismo con un cuerpo ya montado (nombres en DETALLE, cifras en CIFRA...). */
    public static Component mensaje(Component cuerpo) {
        return Paleta.mensaje(cuerpo);
    }
}
