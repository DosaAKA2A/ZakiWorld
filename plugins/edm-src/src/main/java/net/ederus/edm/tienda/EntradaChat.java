package net.ederus.edm.tienda;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Preguntarle algo al jugador por el chat.
 *
 * Es lo que deja escribir una cantidad exacta o un texto de busqueda sin yunques
 * ni libros: se cierra el menu, se espera UNA linea y se vuelve a abrir donde
 * estaba.
 *
 * Tres cuidados que no son opcionales:
 *
 *  1. El evento de chat es ASINCRONO. Abrir un inventario desde ahi tumba el
 *     servidor, asi que la respuesta se atiende siempre con runTask.
 *  2. La pregunta CADUCA. Sin caducidad, el que se olvida se queda con la
 *     siguiente frase que escriba secuestrada, aunque sea media hora despues.
 *  3. La linea se cancela para que no salga por el chat publico: nadie quiere
 *     que su "128" lo lea el servidor entero.
 */
public final class EntradaChat implements Listener {

    /** Lo que se espera de un jugador y hasta cuando. */
    private record Pregunta(Consumer<String> respuesta, Runnable alCancelar, long caduca) { }

    private static final long VIDA_MS = 60_000L;

    /** Lo que escribe para salir sin hacer nada. */
    private static final String CANCELAR = "cancelar";

    private final Plugin plugin;
    private final Map<UUID, Pregunta> esperando = new ConcurrentHashMap<>();

    public EntradaChat(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Cierra lo que tenga abierto y espera una linea suya. */
    public void pedir(Player jugador, Consumer<String> respuesta, Runnable alCancelar) {
        esperando.put(jugador.getUniqueId(),
                new Pregunta(respuesta, alCancelar, System.currentTimeMillis() + VIDA_MS));
        /* El cierre va un tick despues a proposito: esto se llama desde dentro
         * de un clic de inventario, y cerrar la ventana en mitad de su propio
         * evento es la clase de cosa que deja el cursor con un item fantasma. */
        plugin.getServer().getScheduler().runTask(plugin, () -> jugador.closeInventory());
    }

    public boolean esperando(Player jugador) {
        return esperando.containsKey(jugador.getUniqueId());
    }

    public void olvidar(Player jugador) {
        esperando.remove(jugador.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void alEscribir(AsyncChatEvent e) {
        Pregunta p = esperando.remove(e.getPlayer().getUniqueId());
        if (p == null) return;
        /* Caducada: la frase vuelve a ser suya y sale por el chat normal. */
        if (System.currentTimeMillis() > p.caduca()) return;

        e.setCancelled(true);
        String texto = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player jugador = e.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!jugador.isOnline()) return;
            if (texto.isEmpty() || texto.equalsIgnoreCase(CANCELAR)) {
                if (p.alCancelar() != null) p.alCancelar().run();
                return;
            }
            p.respuesta().accept(texto);
        });
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        esperando.remove(e.getPlayer().getUniqueId());
    }
}
