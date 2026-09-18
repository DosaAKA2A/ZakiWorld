package net.ederus.edm.comun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * El unico sitio desde el que EDM paga MobCoins.
 *
 * UltimateMobCoins no tiene API, asi que se le habla por su comando de consola. Lo
 * importante de tenerlo aqui y no repartido: el aviso al jugador sale SIEMPRE igual,
 * y un mundo que se pinta su propia barra de accion (Calamity y su cordura) puede
 * quedarse con el aviso en vez de que se lo pisen.
 */
public final class MobCoins {

    /** Color del oro de las monedas, el mismo en todos los avisos. */
    public static final TextColor ORO = TextColor.color(0xFFD35C);

    /** A quien avisar en vez de escribir en la barra de accion, si alguien se apunta. */
    public interface Aviso {
        /** Devuelve true si se ha encargado del aviso y no hay que tocar la barra. */
        boolean avisar(Player jugador, long cantidad);
    }

    private static Aviso aviso;

    private MobCoins() {
    }

    /**
     * Quien quiera quedarse con el aviso lo dice aqui. Lo usa el mundo Calamity, que
     * tiene la barra de accion ocupada con la cordura y mete el pago dentro de ella.
     */
    public static void aviso(Aviso nuevo) {
        aviso = nuevo;
    }

    /**
     * Paga y avisa. En silencio para el plugin (--silent) porque el mensaje bueno es
     * el nuestro; si la cantidad no es positiva no se hace nada.
     */
    public static void pagar(Plugin plugin, Player jugador, long cantidad) {
        if (jugador == null || cantidad <= 0) return;
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "mobcoins give " + jugador.getName() + " " + cantidad + " --silent");
        } catch (Throwable t) {
            plugin.getLogger().warning("No se pudieron pagar MobCoins a " + jugador.getName() + ": " + t);
            return;
        }
        Aviso a = aviso;
        if (a != null && a.avisar(jugador, cantidad)) return;
        jugador.sendActionBar(Component.text("+" + cantidad + " MobCoins", ORO));
    }
}
