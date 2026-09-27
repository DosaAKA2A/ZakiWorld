package net.ederus.calamity;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

/**
 * /lw hardcore ... y /lw level ... siguen funcionando: se reescriben a /calamidad.
 *
 * Hasta LethalWorld 1.1.1 la administracion de Calamity colgaba de /lw. Al separarse, /lw se
 * quedo con los mundos y esto paso a /calamidad, pero hay costumbre (el staff, las notas, los
 * scripts) y hay comandos guardados en la config que no tienen por que romperse: los premios
 * de hitos y contratos son "lw hardcore dar ..." y se lanzan por consola.
 *
 *  - "lw hardcore X ..." -> "calamidad X ..." (sobra la palabra hardcore)
 *  - "lw level X ..."    -> "calamidad level X ..." (level es un subcomando mas de /calamidad)
 *
 * Un comando llega por tres caminos, y los tres se cubren:
 *  - lo escribe un jugador: PlayerCommandPreprocessEvent;
 *  - lo escribe la consola (o RCON, o un bloque de comandos): ServerCommandEvent;
 *  - lo lanza un plugin con dispatchCommand, que es lo que hacen Hitos, Contratos, Entregas
 *    y Rankings con los comandos de la config: ahi no salta NINGUN evento, asi que ademas se
 *    envuelve el ejecutor de /lw (engancharLw). Sin eso, "lw hardcore dar llave ..." caeria
 *    en la ayuda de /lw y el premio se perderia sin un solo aviso.
 *
 * No se cancela nada ni se miran permisos: el comando reescrito sigue su camino normal y
 * /calamidad pide ederus.mundos, el mismo permiso que pedia /lw.
 */
public final class RedireccionComandos implements Listener {

    /** Como se puede escribir /lw: su nombre, el alias del plugin.yml y los dos con prefijo. */
    private static final Set<String> ETIQUETAS_LW = Set.of("lw", "lethalworld", "lethalworld:lw",
            "lethalworld:lethalworld");

    /**
     * Etiqueta, un espacio, hardcore|level y detras un espacio o nada: "lw hardcorex" o
     * "lw levels" no son lo viejo y se dejan pasar tal cual.
     */
    private static final Pattern VIEJO = Pattern.compile("^(\\S+) (hardcore|level)(?= |$)(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @EventHandler(priority = EventPriority.LOWEST)
    public void alEscribirJugador(PlayerCommandPreprocessEvent e) {
        String mensaje = e.getMessage();
        if (!mensaje.startsWith("/")) return;
        String nuevo = reescribir(mensaje.substring(1));
        if (nuevo != null) e.setMessage("/" + nuevo);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void alEscribirConsola(ServerCommandEvent e) {
        // La consola lo manda sin barra, pero hay quien la pone: se respeta lo que venga.
        String comando = e.getCommand();
        boolean barra = comando.startsWith("/");
        String nuevo = reescribir(barra ? comando.substring(1) : comando);
        if (nuevo != null) e.setCommand((barra ? "/" : "") + nuevo);
    }

    /**
     * La linea de comando (sin la barra) ya reescrita a /calamidad, o null si no es
     * /lw hardcore ni /lw level.
     */
    static String reescribir(String linea) {
        if (linea == null) return null;
        Matcher m = VIEJO.matcher(linea);
        if (!m.matches() || !ETIQUETAS_LW.contains(m.group(1).toLowerCase(Locale.ROOT))) return null;
        String resto = m.group(3);
        return m.group(2).equalsIgnoreCase("hardcore") ? "calamidad" + resto : "calamidad level" + resto;
    }

    // ------------------------------------------------------- dispatchCommand (sin evento)

    private static PluginCommand lw;
    private static CommandExecutor original;
    private static CommandExecutor puente;

    /**
     * Envuelve el ejecutor de /lw (el ComandoMundos de LethalWorld): si le llega hardcore o
     * level, lo manda a /calamidad; lo demas le llega igual que siempre. Es la red para los
     * dispatchCommand, que no pasan por los eventos de arriba. Se suelta en soltarLw().
     */
    static void engancharLw(CalamityPlugin plugin) {
        PluginCommand comando = plugin.lethalWorld().getCommand("lw");
        if (comando == null) {
            plugin.getLogger().warning("[Calamity] LethalWorld no tiene /lw: /lw hardcore solo se"
                    + " redirige al escribirlo, no desde dispatchCommand.");
            return;
        }
        CommandExecutor antes = comando.getExecutor();
        CommandExecutor nuevo = (quien, cmd, etiqueta, args) -> {
            String linea = args.length == 0 ? null : reescribir("lw " + String.join(" ", args));
            if (linea != null) return plugin.getServer().dispatchCommand(quien, linea);
            return antes.onCommand(quien, cmd, etiqueta, args);
        };
        comando.setExecutor(nuevo);
        lw = comando;
        original = antes;
        puente = nuevo;
    }

    /** Devuelve a /lw su ejecutor de siempre, si nadie lo ha cambiado despues. */
    static void soltarLw() {
        if (lw != null && lw.getExecutor() == puente) lw.setExecutor(original);
        lw = null;
        original = null;
        puente = null;
    }
}
