package net.ederus.calamity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import net.ederus.calamity.hardcore.ComandosViejos;
import net.ederus.calamity.hardcore.Paleta;
import net.ederus.calamity.hardcore.Subcomandos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

/**
 * Lo viejo sigue funcionando un tiempo: /lw hardcore, /lw level y el comando de staff en espanol de
 * la 1.11 se reescriben al /calamity de ahora (ComandosViejos dice como). TEMPORAL.
 *
 * Un comando llega por tres caminos, y los tres se cubren:
 *  - lo escribe un jugador: PlayerCommandPreprocessEvent. Solo /lw hardcore y /lw level (los
 *    escribe el staff por costumbre); el comando en espanol ya no existe para los jugadores. Al
 *    staff (calamity.admin) que lo escribe por costumbre no se le ejecuta: se le dice la forma
 *    nueva (pista); a los demas, el "comando desconocido" de siempre;
 *  - lo escribe la consola (o RCON, o un bloque de comandos): ServerCommandEvent;
 *  - lo lanza un plugin con dispatchCommand (Citizens con el clic de un NPC, las crates, los
 *    premios de la config): ahi no salta NINGUN evento. Para /lw se envuelve su ejecutor
 *    (engancharLw) y para el comando en espanol hay uno OCULTO (registrarOculto) que solo atiende
 *    a la consola: no sale en el tab ni en /help de nadie, y a un jugador, aunque sea op, le dice
 *    que no existe.
 *
 * Cada linea vieja distinta se avisa UNA vez por arranque en la consola con su forma nueva, para
 * que el staff la cambie donde este guardada (el NPC, la crate, el config). Cuando ya no salga
 * ningun aviso, se quita esto y el comando oculto.
 *
 * No se cancela nada ni se miran permisos: el comando reescrito sigue su camino normal y /calamity
 * pide calamity.admin.
 */
public final class RedireccionComandos implements Listener {

    /** Los nombres del comando oculto: los dos del plugin.yml de la 1.11. */
    static final List<String> OCULTOS = List.of("calamidad", "cld");

    private static CalamityPlugin plugin;
    private static final Set<String> avisadas = new HashSet<>();

    RedireccionComandos(CalamityPlugin plugin) {
        RedireccionComandos.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void alEscribirJugador(PlayerCommandPreprocessEvent e) {
        String mensaje = e.getMessage();
        if (!mensaje.startsWith("/")) return;
        String linea = mensaje.substring(1);
        // El comando en espanol no se le traduce a un jugador: para el ya no existe. Al staff se le
        // dice como es ahora, sin ejecutarlo (que lo escriba bien la proxima vez).
        String pista = pista(linea, propias);
        if (pista != null) {
            if (!e.getPlayer().hasPermission(Subcomandos.PERMISO)) return;
            e.setCancelled(true);
            e.getPlayer().sendMessage(Paleta.mensaje(Component.text("Ese comando cambió. Ahora es ")
                    .append(Component.text("/" + pista, Paleta.CIFRA).clickEvent(ClickEvent.suggestCommand("/" + pista)))
                    .append(Component.text("."))));
            return;
        }
        if (!esDeLw(linea)) return;
        String nuevo = ComandosViejos.viejo(linea);
        if (nuevo != null) e.setMessage("/" + nuevo);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void alEscribirConsola(ServerCommandEvent e) {
        // La consola lo manda sin barra, pero hay quien la pone: se respeta lo que venga.
        String comando = e.getCommand();
        boolean barra = comando.startsWith("/");
        String linea = barra ? comando.substring(1) : comando;
        String nuevo = ComandosViejos.viejo(linea);
        if (nuevo == null) return;
        avisar(linea, nuevo);
        e.setCommand((barra ? "/" : "") + nuevo);
    }

    private static boolean esDeLw(String linea) {
        int espacio = linea.indexOf(' ');
        String raiz = (espacio < 0 ? linea : linea.substring(0, espacio)).toLowerCase(Locale.ROOT);
        return ComandosViejos.RAICES_LW.contains(raiz);
    }

    /**
     * La forma nueva de una linea del comando oculto (sin barra), o null si no es suyo: su raiz
     * (calamidad, cld y las dos con "calamity:") tiene que estar en propias, los nombres que el
     * comando oculto consiguio para si. Si otro plugin tiene /cld, "/cld ..." es de ese plugin.
     */
    public static String pista(String linea, Set<String> propias) {
        if (linea == null) return null;
        String l = linea.strip();
        int espacio = l.indexOf(' ');
        String raiz = (espacio < 0 ? l : l.substring(0, espacio)).toLowerCase(Locale.ROOT);
        if (!propias.contains(raiz) || !ComandosViejos.RAICES.contains(raiz)) return null;
        return ComandosViejos.traducir(l);
    }

    /** Una vez por linea distinta y por arranque: "comando viejo: X, ahora es Y". */
    static void avisar(String viejo, String nuevo) {
        if (plugin == null || !avisadas.add(viejo)) return;
        plugin.getLogger().warning("[Calamity] Comando viejo: \"" + viejo + "\". Ahora es \"" + nuevo
                + "\". Cámbialo donde esté guardado (NPC, crate, config): el viejo dejará de funcionar.");
    }

    // ------------------------------------------------------- dispatchCommand (sin evento)

    private static PluginCommand lw;
    private static CommandExecutor original;
    private static CommandExecutor puente;
    private static final List<Command> ocultos = new ArrayList<>();
    /** Las raices del comando oculto que son nuestras: las que se registraron sin chocar y las de "calamity:". */
    private static final Set<String> propias = new HashSet<>();

    /**
     * Envuelve el ejecutor de /lw (el ComandoMundos de LethalWorld): si le llega hardcore o
     * level, lo manda a /calamity; lo demas le llega igual que siempre. Es la red para los
     * dispatchCommand, que no pasan por los eventos de arriba. Se suelta en soltar().
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
            String linea = args.length == 0 ? null : "lw " + String.join(" ", args);
            String traducida = ComandosViejos.viejo(linea);
            if (traducida != null) {
                if (!(quien instanceof Player)) avisar(linea, traducida);
                return plugin.getServer().dispatchCommand(quien, traducida);
            }
            return antes.onCommand(quien, cmd, etiqueta, args);
        };
        comando.setExecutor(nuevo);
        lw = comando;
        original = antes;
        puente = nuevo;
    }

    /**
     * El comando de staff de la 1.11, solo para la consola y sin dejarse ver: lo usan los NPCs de
     * Citizens, las crates y las recompensas que aun no se han cambiado. testPermissionSilent dice
     * que no a cualquier jugador, y Paper no manda al cliente (ni al tab ni a /help) lo que no pasa
     * esa prueba; si un jugador lo escribe igual, ve el "comando desconocido" de siempre (y el staff,
     * la pista de alEscribirJugador, que lo para antes).
     */
    static void registrarOculto(CalamityPlugin plugin) {
        CommandMap mapa = plugin.getServer().getCommandMap();
        for (String nombre : OCULTOS) {
            Command c = new Command(nombre) {
                @Override
                public boolean execute(CommandSender quien, String etiqueta, String[] args) {
                    if (quien instanceof Player) {
                        quien.sendMessage(ComandoRaiz.desconocido());
                        return true;
                    }
                    String linea = nombre + (args.length == 0 ? "" : " " + String.join(" ", args));
                    String traducida = ComandosViejos.traducir(linea);
                    avisar(linea, traducida);
                    return plugin.getServer().dispatchCommand(quien, traducida);
                }

                @Override
                public boolean testPermissionSilent(CommandSender quien) {
                    return !(quien instanceof Player);
                }

                @Override
                public List<String> tabComplete(CommandSender quien, String alias, String[] args) {
                    return List.of();
                }
            };
            c.setDescription("Calamity: forma vieja, solo consola (temporal)");
            boolean suyo = mapa.register(nombre, "calamity", c);
            ocultos.add(c);
            propias.add("calamity:" + nombre);
            if (suyo) propias.add(nombre);
            if (!suyo) plugin.getLogger().warning("[Calamity] Otro plugin ya usa /" + nombre
                    + ": la forma vieja solo se traduce cuando la escribe la consola.");
        }
    }

    /** Devuelve a /lw su ejecutor de siempre (si nadie lo ha cambiado despues) y quita el comando oculto. */
    static void soltar() {
        if (lw != null && lw.getExecutor() == puente) lw.setExecutor(original);
        lw = null;
        original = null;
        puente = null;
        if (plugin != null && !ocultos.isEmpty()) {
            CommandMap mapa = plugin.getServer().getCommandMap();
            for (Command c : ocultos) {
                c.unregister(mapa);
                mapa.getKnownCommands().values().removeIf(x -> x == c);
            }
        }
        ocultos.clear();
        propias.clear();
        avisadas.clear();
    }
}
