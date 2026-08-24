package net.ederus.edm.comun;

import org.bukkit.entity.Player;

/**
 * En que cliente esta jugando alguien.
 *
 * Ederus va con online-mode=false y floodgate, asi que hay gente entrando desde
 * Bedrock. Y Bedrock, dentro de un menu de Java, NO PUEDE:
 *
 *   - hacer clic derecho
 *   - hacer shift + clic
 *   - soltar con Q
 *
 * Geyser traduce sus toques a clic izquierdo y punto. Cualquier accion colgada
 * del derecho o del shift, para ellos no existe. Por eso esto no es un detalle
 * cosmetico: decide que se les puede ofrecer y que hay que contarles de otra
 * forma.
 *
 * No se depende de la API de Floodgate a proposito: seria una dependencia mas
 * en el pom y un jar mas que tiene que estar presente al compilar. Floodgate le
 * da a los suyos un UUID con los 64 bits altos a CERO (el xuid va en los bajos),
 * cosa que un UUID de verdad no tiene nunca. Con eso basta y no ata el plugin a
 * nada.
 */
public final class Plataforma {

    private Plataforma() { }

    public static boolean esBedrock(Player jugador) {
        return jugador != null && jugador.getUniqueId().getMostSignificantBits() == 0L;
    }
}
