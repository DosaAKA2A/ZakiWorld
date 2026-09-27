package net.ederus.calamity.hardcore;

import net.ederus.calamity.CalamityPlugin;
import org.bukkit.OfflinePlayer;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Los %lethalworld_...% de PlaceholderAPI, por registro (DIS sec. 7).
 *
 * Cada modulo registra los suyos en su constructor con registrar(clave, funcion). Una
 * peticion %lethalworld_X% busca la clave X entera; si no esta, la clave registrada mas
 * larga que sea prefijo de X seguida de "_", y la funcion recibe lo que queda detras:
 * con "sello" registrada, %lethalworld_sello_heraldo-carmes% llama a la de "sello" con
 * "heraldo-carmes". Con la clave entera recibe "". Null = "no es nuestro" (PAPI deja el
 * texto tal cual); "" = vacio.
 *
 * OJO: PlaceholderAPI puede llamar desde otro hilo (scoreboards y tablists asincronos).
 * Las funciones tienen que ser baratas, no escribir nada y aceptar un jugador null
 * (papi parse --null) o desconectado.
 *
 * Esta clase no importa nada de PlaceholderAPI: la unica que lo hace es ExpansionLethal,
 * que solo se carga si el plugin esta. Sin PAPI el registro sigue funcionando (lo usan
 * los autotest) y simplemente nadie pregunta.
 */
public final class PlaceholdersLethal {

    private static final Map<String, BiFunction<OfflinePlayer, String, String>> VALORES = new ConcurrentHashMap<>();
    /** La ExpansionLethal registrada, o null. Object para no cargar la clase sin PAPI. */
    private static Object expansion;

    private PlaceholdersLethal() {
    }

    public static void registrar(String clave, BiFunction<OfflinePlayer, String, String> valor) {
        if (clave == null || valor == null) return;
        VALORES.put(clave.toLowerCase(Locale.ROOT), valor);
    }

    /** Lo que responde %lethalworld_<params>%, o null si no es de ninguna clave registrada. */
    public static String resolver(OfflinePlayer p, String params) {
        if (params == null || params.isEmpty()) return null;
        String q = params.toLowerCase(Locale.ROOT);
        try {
            BiFunction<OfflinePlayer, String, String> f = VALORES.get(q);
            if (f != null) return f.apply(p, "");
            for (int corte = q.lastIndexOf('_'); corte > 0; corte = q.lastIndexOf('_', corte - 1)) {
                f = VALORES.get(q.substring(0, corte));
                if (f != null) return f.apply(p, params.substring(corte + 1));
            }
        } catch (Throwable t) {
            // Un placeholder que revienta sale vacio: el scoreboard de todo el servidor no
            // puede depender de que un modulo nuestro este bien.
            return "";
        }
        return null;
    }

    /** Registra la expansion si PlaceholderAPI esta. Lo llama CalamityPlugin.onEnable. */
    public static void activar(CalamityPlugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            plugin.getLogger().info("[Calamity] Sin PlaceholderAPI: los %lethalworld_...% no se registran.");
            return;
        }
        try {
            ExpansionLethal e = new ExpansionLethal(plugin);
            if (e.register()) {
                expansion = e;
                plugin.getLogger().info("[Calamity] Placeholders %lethalworld_...% registrados ("
                        + VALORES.size() + " claves).");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[Calamity] No se pudieron registrar los placeholders: " + t);
        }
    }

    /** Suelta la expansion y vacia el registro. Lo llama CalamityPlugin.onDisable. */
    public static void desactivar() {
        Object e = expansion;
        expansion = null;
        if (e != null) {
            try {
                ((ExpansionLethal) e).unregister();
            } catch (Throwable ignorado) {
                // Si PlaceholderAPI ya se fue, no hay nada que soltar.
            }
        }
        VALORES.clear();
    }
}
